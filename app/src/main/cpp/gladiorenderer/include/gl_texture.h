#ifndef GLADIO_GL_TEXTURE_H
#define GLADIO_GL_TEXTURE_H

#include "gladio.h"

typedef struct GLTexture {
    GLuint id;
    GLenum type;
    GLint originFormat;
    short width;
    short height;
    bool generateMipmap;
    bool normalizeCoords;
} GLTexture;

extern GLuint GLTexture_create();
extern GLTexture* GLTexture_getBound(GLenum target);
extern void GLTexture_bind(GLenum target, GLuint id);
extern void GLTexture_setActiveUnit(GLenum unit);
extern GLTexture* GLTexture_get(GLuint id);
extern GLuint GLTexture_getBindingId(GLenum target);
extern void GLTexture_delete(GLuint id);

#endif