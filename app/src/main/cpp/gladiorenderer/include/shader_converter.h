#ifndef GLADIO_SHADER_CONVERTER_H
#define GLADIO_SHADER_CONVERTER_H

#include "shader_material.h"
#include "shader_chunks.h"

#define COMPILE_STATUS_ERROR 0
#define COMPILE_STATUS_PENDING 1
#define COMPILE_STATUS_SUCCESS 2

typedef struct ShaderFunction {
    char* name;
    int lineStart;
    int lineEnd;
    bool hasBody;
    IntArray paramTypes;
} ShaderFunction;

typedef struct ShaderVariable {
    GLenum type;
    char* name;
    char* blockName;
    uint8_t typeQualifier;
    int location;
    int scopeId;
    int groupId;
    int arraySize;
    bool isMember;
    bool outOfScope;
    void* next;
    void* members;
    int lineStart;
} ShaderVariable;

typedef struct ShaderCode {
    ArrayList lines;
    ArrayMap variables;
    ArrayList functions;
    ArrayMap definedMacros;
    int scopeCount;

    ShaderFunction* lastFunction;
    ShaderVariable* lastVariable;
    ShaderVariable* lastInterfaceBlock;
    int groupCount;
    uint32_t flags;
    short version;
} ShaderCode;

typedef struct ShaderObject {
    GLuint id;
    GLenum type;
    ShaderCode code;
    bool attached;
    bool deleted;
    char compileStatus;
} ShaderObject;

typedef struct ShaderProgram {
    GLuint id;
    SparseArray fragDataLocations;
    ArrayList attachedShaders;
    bool hasBuiltinUniforms;
    bool hasBuiltinColor;

    struct {
        int attributes[VERTEX_ATTRIB_COUNT];
        int alphaTest;
        int projectionMatrix;
        int modelViewMatrix;
        int modelViewProjectionMatrix;
        int textureMatrix[MAX_TEXTURES];
        int fog[5];
    } location;
} ShaderProgram;

extern void ShaderConverter_setShaderSource(GLuint shaderId, GLsizei count, ArrayBuffer* inputBuffer);
extern void ShaderConverter_getShaderSource(ShaderObject* shader, ArrayBuffer* outSource);
extern GLuint ShaderConverter_createShader(GLenum type);
extern ShaderObject* ShaderConverter_getShader(GLuint shaderId);
extern void ShaderConverter_deleteShader(GLuint shaderId);
extern ShaderProgram* ShaderConverter_getProgram(GLuint programId);
extern void ShaderConverter_deleteProgram(GLuint programId);
extern GLuint ShaderConverter_createProgram();
extern void ShaderConverter_attachShader(GLuint programId, GLuint shaderId);
extern void ShaderConverter_detachShader(GLuint programId, GLuint shaderId);
extern void ShaderConverter_linkProgram(GLuint programId);
extern void ShaderConverter_getShaderiv(GLuint shaderId, GLenum pname, GLint* params);
extern void ShaderConverter_getProgramiv(GLuint target, GLenum pname, GLint* params);
extern void ShaderConverter_updateBoundProgram();
extern void ShaderConverter_onDestroy(GLClientState* clientState);

#define MARK_VARIABLE_NAME(name) \
    char* originName = name; \
    while (*name == '+' || *name == '-') name++; \
    char* originChrs[2] = {strchr(name, '['), NULL}; \
    do { \
        if (originChrs[0]) { \
            originChrs[0][0] = '\0'; \
        } \
        else if (isalnum(name[0]) || name[0] == '_') { \
            originChrs[1] = strchr(name, '.'); \
            if (originChrs[1]) originChrs[1][0] = '\0'; \
        } \
    } \
    while (0)

#define UNMARK_VARIABLE_NAME(name) \
    do { \
        if (originChrs[0]) originChrs[0][0] = '['; \
        if (originChrs[1]) originChrs[1][0] = '.'; \
        name = originName; \
    } \
    while (0)

#define MARK_START_COMMENT(line) \
    char* comment = strstr(oldLine, "//"); \
    if (!comment) comment = strstr(oldLine, "/*"); \
    if (comment) comment[0] = '\0'

#define UNMARK_START_COMMENT() \
    if (comment) comment[0] = '/';

static inline int extractVariableArrayIndex(char* input) {
    char string[128];
    if (!substrv(input, '[', ']', string)) return -1;
    char* value = trim(string);
    return is_int(value) ? strtol(value, NULL, 10) : INT32_MAX;
}

#endif