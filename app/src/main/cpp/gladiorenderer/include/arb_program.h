#ifndef GLADIO_ARB_PROGRAM_H
#define GLADIO_ARB_PROGRAM_H

#include "gladio.h"

typedef struct ARBUniform {
    char type;
    int location;
    int index;
    float value[4];
} ARBUniform;

typedef struct ARBVariable {
    char* name;
    int type;
    int arraySize;
    ArrayList uniforms;
    char* value;
} ARBVariable;

typedef struct ARBProgram {
    GLuint id;
    GLenum type;
    GLuint shaderId;
    GLuint threadId;
    char* shaderCode;
    char* asmSource;
    char samplerTypes[MAX_TEXTURES];
    ArrayList variables;
    ShaderMaterial* material;
    uint8_t numTextures;
} ARBProgram;

extern ARBProgram* ARBProgram_create();
extern ARBProgram* ARBProgram_get(GLuint programId);
extern ARBProgram* ARBProgram_getBound(GLenum target);
extern void ARBProgram_bind(GLenum target, GLuint programId);
extern void ARBProgram_setSource(ARBProgram* program, GLenum format, char* string, GLuint length);
extern void ARBProgram_delete(GLuint programId);
extern void ARBProgram_setEnvParameter(GLenum target, GLuint index, GLfloat x, GLfloat y, GLfloat z, GLfloat w);
extern void ARBProgram_setLocalParameter(ARBProgram* program, GLuint index, GLfloat x, GLfloat y, GLfloat z, GLfloat w);
extern void ARBProgram_onDestroy(GLClientState* clientState);
extern bool ARBProgram_isActive(GLenum target);

#define MARK_END_LINE(s) \
    int lineEnd = -1; \
    char lineOldChr; \
    do { \
        char* source = (s); \
        char* semicolon = strchr(source, ';'); \
        if (semicolon) { \
            lineEnd = semicolon - source; \
            while (isspace(source[lineEnd-1])) lineEnd--; \
            lineOldChr = source[lineEnd]; \
            source[lineEnd] = '\0'; \
        } \
    } \
    while (0)

#define UNMARK_END_LINE(s) \
     do { \
        if (lineEnd != -1) { \
            char* source = (s); \
            source[lineEnd] = lineOldChr; \
            lineEnd = -1; \
        } \
    } \
    while (0)

#define ARB_UNIFORM_TYPE_CONST 1
#define ARB_UNIFORM_TYPE_PROGRAM_LOCAL 2
#define ARB_UNIFORM_TYPE_PROGRAM_ENV 3

#endif