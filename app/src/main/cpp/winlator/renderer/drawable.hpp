#pragma once

#include <cstdint>
#include <mutex>
#include <memory>
#include <android/hardware_buffer.h>

#include "renderer_jni.hpp"

struct GLTexture {
    int id;
    bool isDirty;
    EGLImageKHR eglImage;
    bool sizeChanged;
};

struct Drawable {
    int id;
    int width;
    int format;
    int height;
    int stride;
    std::unique_ptr<GLTexture> glTexture;
    bool isDirectContent;
    // Client buffers (DRI3) are allocated as RGBA but hold X11 BGRA pixels.
    bool swapRB;
    // Pending buffer after a resize, swapped in once the client has drawn into it.
    AHardwareBuffer *pendingAhb;
    int pendingWidth;
    int pendingHeight;
    int pendingStride;
    int64_t pendingSince;
    void *data;
    jobject drawableObj;
    AHardwareBuffer *ahb;
    int sync_fence;
};

// Render thread only: switches to the buffer queued by a resize.
inline void applyPendingBuffer(Drawable *drawable) {
    if (drawable->pendingAhb == nullptr)
        return;

    if (drawable->ahb != nullptr)
        AHardwareBuffer_release(drawable->ahb);

    drawable->ahb = drawable->pendingAhb;
    drawable->width = drawable->pendingWidth;
    drawable->height = drawable->pendingHeight;
    drawable->stride = drawable->pendingStride;
    drawable->pendingAhb = nullptr;

    if (drawable->glTexture != nullptr)
        drawable->glTexture->sizeChanged = true;
}
