#pragma once

#include <mutex>

#include "renderer_jni.hpp"

struct Texture {
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
    std::unique_ptr<Texture> texture;
    bool isDirectContent;
    bool isDisplayX;
    void *data;
    jobject drawableObj;
    AHardwareBuffer *ahb;
    int sync_fence;
};