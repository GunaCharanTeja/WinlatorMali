#ifndef GLADIO_DEBUG_UTILS_H
#define GLADIO_DEBUG_UTILS_H

#include "gladio.h"
#include "gl_renderer.h"

static inline void printShaderLines(GLenum type, GLuint shaderId, GLuint programId, char* shaderSource, int len) {
    println("================ SHADER INFO: %s:%u GL_PROGRAM:%u ================", glEnumToString(type), shaderId, programId);
    int lineNo = 1;
    FOREACH_LINE(shaderSource, len + 1, println("%d: %s", lineNo++, line););
}

static inline void debugShaderCode(GLenum shaderType, const char* shaderCode) {
    int shaderId = ShaderConverter_createShader(shaderType);

    ArrayBuffer stringBuf = {0};
    ArrayBuffer_putInt(&stringBuf, strlen(shaderCode));
    ArrayBuffer_putString(&stringBuf, shaderCode);
    ShaderConverter_setShaderSource(shaderId, 1, &stringBuf);
    ShaderObject* shader = ShaderConverter_getShader(shaderId);

    ArrayBuffer shaderSource = {0};
    ShaderConverter_getShaderSource(shader, &shaderSource);

    FOREACH_LINE(shaderSource.buffer, strlen(shaderSource.buffer) + 1, println("%s", line););
    exit(0);
}

static inline void printASMSource(uint32_t id, char* asmSource) {
    println("================ ASM SOURCE: %d ================", id);
    int lineNo = 1;
    FOREACH_LINE(asmSource, strlen(asmSource) + 1, println("%d: %s", lineNo++, line););
}

static inline void debugASMSource(GLenum type, char* asmSource) {
    ARBProgram arbProgram = {0};
    arbProgram.type = type;
    asmSource = strdup(asmSource);
    ARBProgram_setSource(&arbProgram, GL_PROGRAM_FORMAT_ASCII_ARB, strdup(asmSource), strlen(asmSource));
    free(asmSource);

    println("================ SHADER CODE ================");
    FOREACH_LINE(arbProgram.shaderCode, strlen(arbProgram.shaderCode) + 1, println("%s", line););
    exit(0);
}

static inline void writeTexImageFile(GLTexture* texture) {
    char filename[255];
    sprintf(filename, APP_CACHE_DIR "/gl-textures/texture-%d-%d-%dx%d-%x-%x.bmp", GLTexture_getBindingId(texture->type), texture->id, texture->width, texture->height, texture->type, texture->originFormat);
    if (!isDirectoryExists(APP_CACHE_DIR "/gl-textures")) createDirectory(APP_CACHE_DIR "/gl-textures");
    int imageSize;
    char* pixels = GLRenderer_getTexImage(currentRenderer, texture->type, 0, GL_RGBA, GL_UNSIGNED_BYTE, &imageSize);
    if (!pixels) return;

    bool isEmpty = true;
    for (int i = 0; i < imageSize; i += 4) {
        uint8_t r = pixels[i+0];
        uint8_t g = pixels[i+1];
        uint8_t b = pixels[i+2];

        if (r > 5 || g > 5 || b > 5) {
            isEmpty = false;
            break;
        }
    }

    if (!isEmpty) writeBMPImageFile(filename, texture->width, texture->height, 4, pixels);
}

#endif