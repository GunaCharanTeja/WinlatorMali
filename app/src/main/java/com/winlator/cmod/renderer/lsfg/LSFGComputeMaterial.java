package com.winlator.cmod.renderer.lsfg;

import android.opengl.GLES31;
import android.util.Log;

public class LSFGComputeMaterial {
    public int programId;
    private int qualityLocation = -1;

    public void use(int quality) {
        if (programId == 0) {
            programId = compileComputeShader();
            qualityLocation = GLES31.glGetUniformLocation(programId, "quality");
        }
        GLES31.glUseProgram(programId);
        if (qualityLocation != -1) GLES31.glUniform1i(qualityLocation, quality);
    }

    private int compileComputeShader() {
        int shader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER);
        String source = "#version 310 es\n" +
                "layout(local_size_x = 16, local_size_y = 8, local_size_z = 1) in;\n" +
                "precision mediump float;\n" +
                "\n" +
                "uniform sampler2D currFrame;\n" +
                "uniform sampler2D prevFrame;\n" +
                "uniform sampler2D mvHistoryTexture;\n" +
                "uniform int quality;\n" +
                "layout(rgba16f, binding = 0) uniform writeonly image2D motionVectorOutput;\n" +
                "\n" +
                "float getLuma(vec3 c) { \n" +
                "    return dot(c, vec3(0.299, 0.587, 0.114)); \n" +
                "}\n" +
                "\n" +
                "void main() {\n" +
                "    ivec2 pixelPos = ivec2(gl_GlobalInvocationID.xy);\n" +
                "    ivec2 imageSize = imageSize(motionVectorOutput);\n" +
                "    if (pixelPos.x >= imageSize.x || pixelPos.y >= imageSize.y) return;\n" +
                "    \n" +
                "    vec2 uv = (vec2(pixelPos) + 0.5) / vec2(imageSize);\n" +
                "    vec2 ts = 1.0 / vec2(imageSize);\n" +
                "    \n" +
                "    // --- Ultra-Light Performance Kernel ---\n" +
                "    // We only sample 2 points for luma to detect scene changes and motion\n" +
                "    // which drastically recovers raw performance on Mali.\n" +
                "    float l00 = getLuma(textureLod(currFrame, uv, 0.0).rgb);\n" +
                "    float p00 = getLuma(textureLod(prevFrame, uv, 0.0).rgb);\n" +
                "    \n" +
                "    float diff = abs(l00 - p00);\n" +
                "    float sceneCut = (diff > 0.88) ? 1.0 : 0.0;\n" +
                "    \n" +
                "    float bestSAD = diff;\n" +
                "    vec2 bestMV = vec2(0.0);\n" +
                "    \n" +
                "    if (sceneCut < 0.5) {\n" +
                "        // Highly optimized search iterations\n" +
                "        int searchScale = (quality == 0) ? 4 : (quality == 1 ? 8 : 14);\n" +
                "        int iterations = (quality == 2) ? 3 : 2;\n" +
                "        \n" +
                "        for (int i = iterations; i >= 1; i--) {\n" +
                "            float step = float(1 << (i-1)) * float(searchScale);\n" +
                "            vec2 offsets[4];\n" +
                "            offsets[0] = vec2(step, 0);  offsets[1] = vec2(-step, 0);\n" +
                "            offsets[2] = vec2(0, step);  offsets[3] = vec2(0, -step);\n" +
                "            \n" +
                "            for(int j=0; j < 4; j++) {\n" +
                "                vec2 off = (bestMV + offsets[j] * ts);\n" +
                "                float curSAD = abs(l00 - getLuma(textureLod(prevFrame, uv + off, 0.0).rgb));\n" +
                "                if (curSAD < bestSAD) {\n" +
                "                    bestSAD = curSAD;\n" +
                "                    bestMV = off;\n" +
                "                }\n" +
                "            }\n" +
                "        }\n" +
                "    }\n" +
                "\n" +
                "    vec2 historyMV = textureLod(mvHistoryTexture, uv, 0.0).rg;\n" +
                "    vec2 stabilizedMV = mix(historyMV * (1.0 - sceneCut), bestMV, 0.8);\n" +
                "    \n" +
                "    float confidence = 1.0 - clamp(bestSAD * 4.0, 0.0, 1.0);\n" +
                "    imageStore(motionVectorOutput, pixelPos, vec4(stabilizedMV, confidence, 0.0, 1.0));\n" +
                "}";

        GLES31.glShaderSource(shader, source);
        GLES31.glCompileShader(shader);

        int[] compiled = new int[1];
        GLES31.glGetShaderiv(shader, GLES31.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e("LSFGCompute", "Shader compile error: " + GLES31.glGetShaderInfoLog(shader));
            return 0;
        }

        int program = GLES31.glCreateProgram();
        GLES31.glAttachShader(program, shader);
        GLES31.glLinkProgram(program);
        
        int[] linked = new int[1];
        GLES31.glGetProgramiv(program, GLES31.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) {
            Log.e("LSFGCompute", "Program link error: " + GLES31.glGetProgramInfoLog(program));
            GLES31.glDeleteProgram(program);
            return 0;
        }

        GLES31.glDeleteShader(shader);
        return program;
    }

    public void destroy() {
        if (programId != 0) {
            GLES31.glDeleteProgram(programId);
            programId = 0;
        }
    }
}
