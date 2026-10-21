cmake_minimum_required(VERSION 3.22)
project(zerofg VERSION 1.0.4 LANGUAGES CXX)

# ZeroFG: a Vulkan frame generation engine. This builds the static library
# `zerofg` (and the alias `zerofg::zerofg`).
#
# Needs at configure/build time:
#   - the Vulkan headers: ZEROFG_VULKAN_HEADERS_DIR, or a Vulkan SDK that
#     find_package(Vulkan) can locate;
#   - for the shaders, nothing more: a release ships them compiled
#     (shaders/prebuilt). With Python 3, glslangValidator and spirv-opt (the
#     Vulkan SDK: set VULKAN_SDK, or have both on PATH) they are compiled from
#     shaders/rc3 instead. ZEROFG_PREBUILT_SHADERS picks one: AUTO (the
#     default: compile when the tools are there), ON, OFF.
# Nothing links against a Vulkan loader: ZeroFG resolves every entry point
# through the vkGetInstanceProcAddr the host passes in.
#
# Changing a shader: build the target zerofg_update_prebuilt_shaders (with the
# tools) and commit shaders/prebuilt with the change; zerofg_check_prebuilt_shaders
# fails while the shipped SPIR-V differs from the sources.

set(ZEROFG_VULKAN_HEADERS_DIR "" CACHE PATH
    "Directory holding vulkan/vulkan.h (empty: find_package(Vulkan))")
set(ZEROFG_PREBUILT_SHADERS "AUTO" CACHE STRING
    "Use the shipped SPIR-V in shaders/prebuilt: AUTO, ON or OFF")
set_property(CACHE ZEROFG_PREBUILT_SHADERS PROPERTY STRINGS AUTO ON OFF)

find_package(Python3 COMPONENTS Interpreter)
find_program(ZEROFG_GLSLANG_VALIDATOR glslangValidator
             HINTS "$ENV{VULKAN_SDK}/Bin" "$ENV{VULKAN_SDK}/bin")
find_program(ZEROFG_SPIRV_OPT spirv-opt
             HINTS "$ENV{VULKAN_SDK}/Bin" "$ENV{VULKAN_SDK}/bin")
if(Python3_Interpreter_FOUND AND ZEROFG_GLSLANG_VALIDATOR AND ZEROFG_SPIRV_OPT)
  set(_zerofg_shader_tools TRUE)
else()
  set(_zerofg_shader_tools FALSE)
endif()
if(ZEROFG_PREBUILT_SHADERS STREQUAL "AUTO")
  if(_zerofg_shader_tools)
    set(_zerofg_prebuilt FALSE)
  else()
    set(_zerofg_prebuilt TRUE)
  endif()
elseif(ZEROFG_PREBUILT_SHADERS)
  set(_zerofg_prebuilt TRUE)
else()
  if(NOT _zerofg_shader_tools)
    message(FATAL_ERROR "ZEROFG_PREBUILT_SHADERS is OFF, but Python 3, "
            "glslangValidator or spirv-opt is missing")
  endif()
  set(_zerofg_prebuilt FALSE)
endif()
if(_zerofg_prebuilt)
  message(STATUS "ZeroFG: shipped SPIR-V (shaders/prebuilt)")
else()
  message(STATUS "ZeroFG: shaders compiled from shaders/rc3")
endif()

if(ZEROFG_VULKAN_HEADERS_DIR)
  set(_zerofg_vulkan_include "${ZEROFG_VULKAN_HEADERS_DIR}")
else()
  find_package(Vulkan REQUIRED)
  set(_zerofg_vulkan_include "${Vulkan_INCLUDE_DIRS}")
endif()

set(_zerofg_shader_compiler
    "${CMAKE_CURRENT_SOURCE_DIR}/tools/compile_vulkan13_shader.py")
set(_zerofg_shader_source_dir "${CMAKE_CURRENT_SOURCE_DIR}/shaders/rc3")
set(_zerofg_shader_prebuilt_dir "${CMAKE_CURRENT_SOURCE_DIR}/shaders/prebuilt/rc3")
if(_zerofg_prebuilt)
  set(_zerofg_shader_output_dir "${_zerofg_shader_prebuilt_dir}")
else()
  set(_zerofg_shader_output_dir "${CMAKE_CURRENT_BINARY_DIR}/generated/rc3")
endif()
set(_zerofg_shader_headers)

function(zerofg_compile_shader output_name source_name symbol)
  set(_output "${_zerofg_shader_output_dir}/${output_name}.h")
  if(_zerofg_prebuilt)
    if(NOT EXISTS "${_output}")
      message(FATAL_ERROR "ZeroFG: ${_output} is missing; build "
              "zerofg_update_prebuilt_shaders with the shader tools")
    endif()
    set(_zerofg_shader_headers ${_zerofg_shader_headers} "${_output}"
        PARENT_SCOPE)
    return()
  endif()
  add_custom_command(
    OUTPUT "${_output}"
    COMMAND "${Python3_EXECUTABLE}" "${_zerofg_shader_compiler}"
            "${_zerofg_shader_source_dir}/${source_name}"
            "${_output}" "${symbol}" ${ARGN}
    DEPENDS "${_zerofg_shader_source_dir}/${source_name}"
            "${_zerofg_shader_source_dir}/rc3_common.glsl"
            "${_zerofg_shader_source_dir}/d4c_common.glsl"
            "${_zerofg_shader_compiler}"
    VERBATIM
    COMMENT "Compiling ZeroFG shader ${output_name} for Vulkan 1.3")
  set(_zerofg_shader_headers ${_zerofg_shader_headers} "${_output}"
      PARENT_SCOPE)
endfunction()

zerofg_compile_shader(rc3_crop crop.comp zerofg_rc3_crop_spv)
zerofg_compile_shader(rc3_d0 d0_frontend.comp zerofg_rc3_d0_spv)
zerofg_compile_shader(rc3_d0a d0a_lattice.comp zerofg_rc3_d0a_spv)
zerofg_compile_shader(rc3_d0b d0b_luma_full.comp zerofg_rc3_d0b_spv)
zerofg_compile_shader(rc3_d0p d0p_photo.comp zerofg_rc3_d0p_spv)
zerofg_compile_shader(rc3_d1 d1_evidence.comp zerofg_rc3_d1_spv)
zerofg_compile_shader(rc3_d2_h0 d2_refine.comp zerofg_rc3_d2_h0_spv
                      -DZFG_H1_ENABLED=0)
zerofg_compile_shader(rc3_d3 d3_cells.comp zerofg_rc3_d3_spv)
zerofg_compile_shader(rc3_d4 d4_fine.comp zerofg_rc3_d4_spv)
zerofg_compile_shader(rc3_d4sg d4_fine.comp zerofg_rc3_d4sg_spv
                      -DZFG_SUBGROUP=1)
zerofg_compile_shader(rc3_d4c d4c_camera.comp zerofg_rc3_d4c_spv)
zerofg_compile_shader(rc3_d4cc d4c_cost.comp zerofg_rc3_d4cc_spv)
zerofg_compile_shader(rc3_d4cp d4c_pick.comp zerofg_rc3_d4cp_spv)
zerofg_compile_shader(rc3_d5a d5a_resolve.comp zerofg_rc3_d5a_spv)
zerofg_compile_shader(rc3_d5ah d5a_resolve.comp zerofg_rc3_d5ah_spv
                      -DZFG_HALF=1)

if(NOT _zerofg_prebuilt)
  set_source_files_properties(${_zerofg_shader_headers} PROPERTIES GENERATED TRUE)
  # The maintainer's pair: copy the SPIR-V just compiled into shaders/prebuilt,
  # and fail while what is shipped there differs from it.
  add_custom_target(zerofg_update_prebuilt_shaders
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${_zerofg_shader_prebuilt_dir}"
    COMMAND "${CMAKE_COMMAND}" -E copy_if_different ${_zerofg_shader_headers}
            "${_zerofg_shader_prebuilt_dir}"
    DEPENDS ${_zerofg_shader_headers}
    COMMENT "Updating ZeroFG's shipped SPIR-V in shaders/prebuilt/rc3"
    VERBATIM)
  set(_zerofg_check_commands)
  foreach(_header IN LISTS _zerofg_shader_headers)
    get_filename_component(_name "${_header}" NAME)
    list(APPEND _zerofg_check_commands COMMAND "${CMAKE_COMMAND}" -E compare_files
         "${_header}" "${_zerofg_shader_prebuilt_dir}/${_name}")
  endforeach()
  add_custom_target(zerofg_check_prebuilt_shaders
    ${_zerofg_check_commands}
    DEPENDS ${_zerofg_shader_headers}
    COMMENT "Checking ZeroFG's shipped SPIR-V against shaders/rc3"
    VERBATIM)
endif()

add_library(zerofg STATIC
  src/zerofg.cc
  src/rc3/rc3_context.cc
  ${_zerofg_shader_headers}
)
add_library(zerofg::zerofg ALIAS zerofg)

target_include_directories(zerofg
  PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${_zerofg_vulkan_include}
  PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/src
    ${_zerofg_shader_output_dir}
)
target_compile_features(zerofg PUBLIC cxx_std_20)
