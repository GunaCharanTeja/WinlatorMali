#include "gl_texture.h"
#include "gl_context.h"

static GLuint maxTextureId = 1;
static GLTexture nullTexture = {0};

static GLTexture* createNamedTexture(GLuint id) {
    GLTexture* texture = calloc(1, sizeof(GLTexture));
    texture->type = GL_TEXTURE_2D;
    glGenTextures(1, &texture->id);
    SparseArray_put(currentRenderer->clientState.textures, id, texture);
    return texture;
}

GLuint GLTexture_create() {
    GLX_CONTEXT_LOCK();
    GLuint id = maxTextureId++;
    createNamedTexture(id);
    GLX_CONTEXT_UNLOCK();
    return id;
}

GLTexture* GLTexture_getBound(GLenum target) {
    uint8_t activeTexture = currentRenderer->clientState.activeTexture;
    return currentRenderer->clientState.texture[activeTexture][indexOfGLTarget(target)];
}

void GLTexture_bind(GLenum target, GLuint id) {
    bool isTextureRect = target == GL_TEXTURE_RECTANGLE;
    target = parseTexTarget(target);
    uint8_t activeTexture = currentRenderer->clientState.activeTexture;
    if (id == 0) {
        currentRenderer->clientState.texture[activeTexture][indexOfGLTarget(target)] = NULL;
        glBindTexture(target, 0);
        return;
    }
    GLX_CONTEXT_LOCK();
    GLTexture* texture = SparseArray_get(currentRenderer->clientState.textures, id);
    if (!texture) texture = createNamedTexture(id);
    texture->type = target;
    texture->normalizeCoords = isTextureRect;
    GLX_CONTEXT_UNLOCK();
    currentRenderer->clientState.texture[activeTexture][indexOfGLTarget(target)] = texture;
    glBindTexture(target, texture->id);
}

void GLTexture_setActiveUnit(GLenum unit) {
    currentRenderer->clientState.activeTexture = MIN(unit - GL_TEXTURE0, MAX_TEXTURES-1);
    glActiveTexture(unit);
}

GLTexture* GLTexture_get(GLuint id) {
    GLX_CONTEXT_LOCK();
    GLTexture* texture = SparseArray_get(currentRenderer->clientState.textures, id);
    GLX_CONTEXT_UNLOCK();
    return texture ? texture : &nullTexture;
}

void GLTexture_delete(GLuint id) {
    GLX_CONTEXT_LOCK();
    GLClientState* clientState = &currentRenderer->clientState;
    GLTexture* texture = SparseArray_get(clientState->textures, id);
    if (texture) {
        for (int i = 0, j; i < MAX_TEXTURES; i++) {
            for (j = 0; j < MAX_TEXTURE_TARGETS; j++) {
                if (texture == clientState->texture[i][j]) clientState->texture[i][j] = NULL;
            }
        }

        glDeleteTextures(1, &texture->id);
        SparseArray_remove(clientState->textures, id);
        free(texture);
    }
    GLX_CONTEXT_UNLOCK();
}

GLuint GLTexture_getBindingId(GLenum target) {
    GLX_CONTEXT_LOCK();
    GLuint bindingId = 0;
    GLClientState* clientState = &currentRenderer->clientState;
    GLTexture* texture = clientState->texture[clientState->activeTexture][indexOfGLTarget(target)];
    if (texture) {
        for (int i = 0; i < clientState->textures->size; i++) {
            if (clientState->textures->entries[i].value == texture) {
                bindingId = clientState->textures->entries[i].key;
                break;
            }
        }
    }
    GLX_CONTEXT_UNLOCK();
    return bindingId;
}