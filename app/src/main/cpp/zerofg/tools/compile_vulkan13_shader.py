#!/usr/bin/env python3

"""Compile one ZeroFG compute shader for Vulkan 1.3 and emit a C header."""

import os
import struct
import subprocess
import sys


def find_tool(name):
    executable = name + (".exe" if os.name == "nt" else "")
    sdk = os.environ.get("VULKAN_SDK")
    if sdk:
        for directory in ("Bin", "bin"):
            candidate = os.path.join(sdk, directory, executable)
            if os.path.isfile(candidate):
                return candidate
    return executable


def main():
    if len(sys.argv) < 4:
        print("usage: compile_vulkan13_shader.py input output symbol [-D...]",
              file=sys.stderr)
        return 1
    source, output, symbol = sys.argv[1:4]
    defines = sys.argv[4:]
    if any(not value.startswith("-D") for value in defines):
        print("only -D arguments are accepted", file=sys.stderr)
        return 1

    os.makedirs(os.path.dirname(output), exist_ok=True)
    temporary = output + ".unoptimized.spv"
    optimized = output + ".spv"
    try:
        command = [find_tool("glslangValidator"), source, "-V",
                   "--target-env", "vulkan1.3", "-S", "comp",
                   "-o", temporary]
        command.extend(defines)
        result = subprocess.run(command, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True)
        if result.returncode:
            sys.stderr.write(result.stdout)
            return result.returncode
        result = subprocess.run([find_tool("spirv-opt"), "-O", temporary,
                                 "-o", optimized], stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True)
        if result.returncode:
            sys.stderr.write(result.stdout)
            return result.returncode

        with open(optimized, "rb") as binary:
            data = binary.read()
        if len(data) % 4:
            print("SPIR-V output is not word aligned", file=sys.stderr)
            return 1
        words = struct.unpack("<%dI" % (len(data) // 4), data)
        with open(output, "w", newline="\n") as header:
            header.write("// Generated from %s for Vulkan 1.3.\n" %
                         os.path.basename(source))
            header.write("#pragma once\n#include <cstdint>\n\n")
            header.write("inline constexpr uint32_t %s[] = {\n" % symbol)
            for index in range(0, len(words), 6):
                line = ", ".join("0x%08Xu" % word
                                 for word in words[index:index + 6])
                header.write("    %s,\n" % line)
            header.write("};\n")
    finally:
        for path in (temporary, optimized):
            try:
                os.remove(path)
            except FileNotFoundError:
                pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
