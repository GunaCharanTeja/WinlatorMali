#ifndef GLADIO_GL_CLIENT_STATE_H
#define GLADIO_GL_CLIENT_STATE_H

#include "gladio.h"
#include "gl_vao.h"

#ifdef GL_SERVER
#include "gl_texture.h"
#include "gl_framebuffer.h"
#endif

typedef struct GLClientState {
    uint8_t activeTexture;
    uint8_t activeTexCoord;
    int indexStart;
    GLVertexArrayObject defaultVAO;
    GLVertexArrayObject* vao;
    SparseArray vertexArrays;
    SparseArray* buffers;

#ifdef GL_SERVER
    ShaderProgram* program;
    GLTexture* texture[MAX_TEXTURES][MAX_TEXTURE_TARGETS];
    GLuint framebuffer[MAX_FRAMEBUFFER_TARGETS];
    ARBProgram* arbProgram[MAX_ARB_PROGRAM_TARGETS];
    GLuint renderbuffer;

    SparseArray* textures;
    SparseArray* arbPrograms;
    SparseArray* programs;
    SparseArray* shaders;
    SparseArray* framebuffers;
    SparseArray* queries;
#else
    ArrayList persistentBuffers;
    GLuint program;
    GLuint arbProgram[MAX_ARB_PROGRAM_TARGETS];

    struct {
        short unpackRowLength;
        short unpackImageHeight;
    } pixelStore;
#endif

    bool usingSharedState;
} GLClientState;

static inline void GLClientState_init(GLClientState* clientState, GLClientState* sharedState) {
    if (sharedState) {
        clientState->usingSharedState = true;
        clientState->buffers = sharedState->buffers;
#ifdef GL_SERVER
        clientState->textures = sharedState->textures;
        clientState->arbPrograms = sharedState->arbPrograms;
        clientState->programs = sharedState->programs;
        clientState->shaders = sharedState->shaders;
        clientState->queries = sharedState->queries;
        clientState->framebuffers = sharedState->framebuffers;
#endif
    }
    else {
        clientState->usingSharedState = false;
        clientState->buffers = calloc(1, sizeof(SparseArray));
#ifdef GL_SERVER
        clientState->textures = calloc(1, sizeof(SparseArray));
        clientState->arbPrograms = calloc(1, sizeof(SparseArray));
        clientState->programs = calloc(1, sizeof(SparseArray));
        clientState->shaders = calloc(1, sizeof(SparseArray));
        clientState->queries = calloc(1, sizeof(SparseArray));
        clientState->framebuffers = calloc(1, sizeof(SparseArray));
#endif
    }
}

static inline void GLClientState_destroy(GLClientState* clientState) {
    SparseArray_free(&clientState->vertexArrays, true);

#ifndef GL_SERVER
    ArrayList_free(&clientState->persistentBuffers, false);
#endif

    if (!clientState->usingSharedState) {
        GLBuffer_onDestroy(clientState);
        SparseArray_free(clientState->buffers, false);
        MEMFREE(clientState->buffers);

#ifdef GL_SERVER
        SparseArray_free(clientState->textures, true);
        MEMFREE(clientState->textures);

        ARBProgram_onDestroy(clientState);
        SparseArray_free(clientState->arbPrograms, false);
        MEMFREE(clientState->arbPrograms);

        ShaderConverter_onDestroy(clientState);
        SparseArray_free(clientState->programs, false);
        MEMFREE(clientState->programs);
        SparseArray_free(clientState->shaders, false);
        MEMFREE(clientState->shaders);

        SparseArray_free(clientState->framebuffers, true);
        MEMFREE(clientState->framebuffers);

        SparseArray_free(clientState->queries, true);
        MEMFREE(clientState->queries);
#endif
    }
}

static inline bool GLClientState_isLegacyEnabledWithProgram(GLClientState* clientState, int arrayIdx) {
    bool hasBoundProgram = clientState->program || clientState->arbProgram[0];
    return clientState->vao->attribs[arrayIdx].state == VERTEX_ATTRIB_LEGACY_ENABLED && hasBoundProgram;
}

static inline int GLClientState_getArrayIndex(GLenum array) {
    switch (array) {
        case GL_VERTEX_ARRAY:
            return POSITION_ARRAY_INDEX;
        case GL_COLOR_ARRAY:
            return COLOR_ARRAY_INDEX;
        case GL_NORMAL_ARRAY:
            return NORMAL_ARRAY_INDEX;
        case GL_TEXTURE_COORD_ARRAY:
            return TEXCOORD_ARRAY_INDEX;
        case GL_GENERIC_VERTEX_ARRAY:
            return GENERIC_VERTEX_ARRAY_INDEX;
        default:
            return -1;
    }
}

#endif