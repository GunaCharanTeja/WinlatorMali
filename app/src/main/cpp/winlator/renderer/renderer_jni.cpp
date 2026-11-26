#include "egl.hpp"

#include <chrono>

#define STB_IMAGE_IMPLEMENTATION
#include "stb_image.h"

#define LOG_TAG "Renderer"
#define printf(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

JNICache cache;
JNIXServer xserver;
WindowManager windowManager;
CursorManager cursorManager;
EGLRenderer renderer;

extern "C" jint JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    vm->GetEnv((void**)&env, JNI_VERSION_1_6);

    cache.init(vm, env);

    return JNI_VERSION_1_6;
}

static std::unique_ptr<struct Drawable> createDrawable(JNIEnv *env, jobject drawableObj, bool isDirectContent) {
    auto drawable = std::make_unique<struct Drawable>();
    drawable->id = env->GetIntField(drawableObj, cache.drawableID);
    drawable->glTexture = nullptr;
    drawable->width = env->GetShortField(drawableObj, cache.drawableWidth);
    drawable->height = env->GetShortField(drawableObj, cache.drawableHeight);
    drawable->ahb = (AHardwareBuffer *)env->GetLongField(drawableObj, cache.drawableAHB);
    drawable->stride = env->GetShortField(drawableObj, cache.drawableStride);
    drawable->format = env->GetIntField(drawableObj, cache.drawableFormat);
    drawable->data = nullptr;
    drawable->isDirectContent = isDirectContent;
    drawable->swapRB = false;
    drawable->pendingAhb = nullptr;
    drawable->drawableObj = env->NewGlobalRef(drawableObj);

    // The renderer keeps its own reference so a buffer freed by the client stays valid until the renderer drops it.
    if (drawable->ahb != nullptr) {
        AHardwareBuffer_acquire(drawable->ahb);

        // Client buffers declared RGBA carry X11 BGRA pixels (the old GL path hid this with a CPU copy).
        if (isDirectContent) {
            AHardwareBuffer_Desc desc{};
            AHardwareBuffer_describe(drawable->ahb, &desc);
            drawable->swapRB = desc.format == AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM || desc.format == AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM;
        }
    }

    // Drawables without a hardware buffer are uploaded from their CPU memory instead.
    if (drawable->ahb == nullptr) {
        jobject dataObj = env->GetObjectField(drawableObj, cache.drawableData);
        if (dataObj != nullptr) {
            drawable->data = env->GetDirectBufferAddress(dataObj);
            env->DeleteLocalRef(dataObj);
        }
    }
    return drawable;
}

static void releaseDrawable(JNIEnv *env, Drawable *drawable) {
    if (!drawable)
        return;

    if (drawable->glTexture != nullptr)
        renderer.destroyTexture(drawable->glTexture.get());

    if (drawable->ahb != nullptr) {
        AHardwareBuffer_release(drawable->ahb);
        drawable->ahb = nullptr;
    }

    if (drawable->pendingAhb != nullptr) {
        AHardwareBuffer_release(drawable->pendingAhb);
        drawable->pendingAhb = nullptr;
    }

    if (drawable->drawableObj != nullptr) {
        env->DeleteGlobalRef(drawable->drawableObj);
        drawable->drawableObj = nullptr;
    }
}

static std::unique_ptr<struct Cursor> createRootCursor(JNIEnv *env, jobject context) {
    jclass contextClass = env->GetObjectClass(context);
    jmethodID getAssets = env->GetMethodID(contextClass, "getAssets", "()Landroid/content/res/AssetManager;");
    jobject assetManagerObject = env->CallObjectMethod(context, getAssets);
    AAssetManager *assetManager = AAssetManager_fromJava(env, assetManagerObject);

    AAsset *cursorAsset = AAssetManager_open(assetManager, "cursor.png", AASSET_MODE_BUFFER);
    if (!cursorAsset)
        return nullptr;

    off_t len = AAsset_getLength(cursorAsset);
    const unsigned char *data = static_cast<const unsigned char *>(AAsset_getBuffer(cursorAsset));

    int w, h, c;
    unsigned char *cursorData = stbi_load_from_memory(data, len, &w, &h, &c, 4);
    AAsset_close(cursorAsset);

    if (!cursorData)
        return nullptr;

    auto cursorDrawable = std::make_unique<struct Drawable>();
    cursorDrawable->id = -1;
    cursorDrawable->glTexture = nullptr;
    cursorDrawable->isDirectContent = false;
    cursorDrawable->format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    cursorDrawable->width = w;
    cursorDrawable->height = h;
    cursorDrawable->data = nullptr;
    cursorDrawable->drawableObj = nullptr;

    AHardwareBuffer_Desc desc{};
    desc.width = w;
    desc.height = h;
    desc.format = cursorDrawable->format;
    desc.layers = 1;
    desc.usage = AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN |
                 AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN |
                 AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;

    if (AHardwareBuffer_allocate(&desc, &cursorDrawable->ahb) != 0) {
        stbi_image_free(cursorData);
        return nullptr;
    }

    AHardwareBuffer_Desc outDesc{};
    AHardwareBuffer_describe(cursorDrawable->ahb, &outDesc);
    cursorDrawable->stride = outDesc.stride;

    uint8_t *dst;
    if (AHardwareBuffer_lock(cursorDrawable->ahb, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, nullptr, reinterpret_cast<void **>(&dst)) != 0) {
        stbi_image_free(cursorData);
        return nullptr;
    }

    for (int y = 0; y < h; ++y) {
        memcpy(dst + y * cursorDrawable->stride * 4, cursorData + y * w * 4, w * 4);
    }

    AHardwareBuffer_unlock(cursorDrawable->ahb, nullptr);
    stbi_image_free(cursorData);

    auto rootCursor = std::make_unique<struct Cursor>();
    rootCursor->id = cursorDrawable->id;
    rootCursor->image = std::move(cursorDrawable);
    rootCursor->hotspotX = 0;
    rootCursor->hotspotY = 0;
    rootCursor->visible = true;
    rootCursor->cursorObj = nullptr;
    return rootCursor;
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeInit(JNIEnv *env, jobject thiz, jobject context, jobject xServer) {
    jobject windowManagerObj = env->GetObjectField(xServer, cache.windowManager);
    jobject inputDeviceManagerObj = env->GetObjectField(xServer, cache.inputDeviceManager);
    jobject rootWindowObj = env->GetObjectField(windowManagerObj, cache.rootWindow);

    auto rootWindow = std::make_unique<struct Window>();

    rootWindow->id = env->GetIntField(rootWindowObj, cache.windowID);
    rootWindow->width = env->CallShortMethod(rootWindowObj, cache.windowGetWidth);
    rootWindow->height = env->CallShortMethod(rootWindowObj, cache.windowGetHeight);
    rootWindow->x = env->CallShortMethod(rootWindowObj, cache.windowGetX);
    rootWindow->y = env->CallShortMethod(rootWindowObj, cache.windowGetY);
    rootWindow->z_order = INT32_MIN;
    rootWindow->className = "";

    jobject drawableObj = env->CallObjectMethod(rootWindowObj, cache.windowGetContent);
    rootWindow->drawable = createDrawable(env, drawableObj, false);
    env->DeleteLocalRef(drawableObj);

    rootWindow->cursor = nullptr;
    rootWindow->parent = nullptr;
    rootWindow->surface = nullptr;
    rootWindow->mapped = true;
    rootWindow->enabled = true;
    rootWindow->inputOutput = true;
    rootWindow->compositeRedirected = false;
    rootWindow->hasContent = false;
    rootWindow->externalContent = nullptr;

    jobject attributes = env->GetObjectField(rootWindowObj, cache.windowAttributes);
    rootWindow->attributes = env->NewGlobalRef(attributes);
    rootWindow->windowObj = env->NewGlobalRef(rootWindowObj);

    env->DeleteLocalRef(rootWindowObj);
    env->DeleteLocalRef(attributes);

    windowManager.setRootWindow(rootWindow.get());
    windowManager.addWindow(rootWindow->id, std::move(rootWindow));

    cursorManager.setRootCursor(createRootCursor(env, context));

    xserver.windowManager = env->NewGlobalRef(windowManagerObj);
    xserver.inputDeviceManager = env->NewGlobalRef(inputDeviceManagerObj);
    xserver.refreshRate = env->CallFloatMethod(context, cache.getRefreshRate);
    xserver.xserver = env->NewGlobalRef(xServer);
    xserver.xserverDisplayActivity = env->NewGlobalRef(context);

    env->DeleteLocalRef(windowManagerObj);
    env->DeleteLocalRef(inputDeviceManagerObj);

    renderer.windowManager = &windowManager;
    renderer.cursorManager = &cursorManager;
    renderer.cache = &cache;
    renderer.xServer = &xserver;
    renderer.start();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeCreateWindow(JNIEnv *env, jobject thiz, jobject windowObj, jint parentId) {
    auto window = std::make_unique<struct Window>();
    window->id = env->GetIntField(windowObj, cache.windowID);
    window->width = env->CallShortMethod(windowObj, cache.windowGetWidth);
    window->height = env->CallShortMethod(windowObj, cache.windowGetHeight);
    window->x = env->CallShortMethod(windowObj, cache.windowGetX);
    window->y = env->CallShortMethod(windowObj, cache.windowGetY);
    window->z_order = 100;
    window->className = "";

    bool isInputOutput = env->CallBooleanMethod(windowObj, cache.windowIsInputOutput);
    window->inputOutput = isInputOutput;
    window->drawable = nullptr;

    if (isInputOutput) {
        jobject drawableObj = env->CallObjectMethod(windowObj, cache.windowGetContent);
        window->drawable = createDrawable(env, drawableObj, false);
        env->DeleteLocalRef(drawableObj);
    }

    window->cursor = nullptr;
    window->mapped = false;
    window->hasContent = false;
    window->parent = nullptr;
    window->surface = nullptr;
    window->compositeRedirected = false;
    window->externalContent = nullptr;
    window->enabled = true;

    jobject attributes = env->GetObjectField(windowObj, cache.windowAttributes);
    window->attributes = env->NewGlobalRef(attributes);
    window->windowObj = env->NewGlobalRef(windowObj);

    env->DeleteLocalRef(attributes);

    if (parentId > -1) {
        auto parent = windowManager.getWindow(parentId);
        if (parent) {
            window->parent = parent;
            parent->children.push_back(window.get());
        }
    }

    windowManager.addWindow(window->id, std::move(window));
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeMapWindow(JNIEnv *env, jobject thiz, jint id) {
    auto window = windowManager.getWindow(id);
    if (!window)
        return;

    window->mapped = true;

    renderer.queueEvent([]{ renderer.updateScene(); });
    renderer.requestRenderer();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeUnmapWindow(JNIEnv *env, jobject thiz, jint id) {
    auto window = windowManager.getWindow(id);
    if (!window)
        return;

    window->mapped = false;

    renderer.queueEvent([]{ renderer.updateScene(); });
    renderer.requestRenderer();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeDestroyWindow(JNIEnv *env, jobject thiz, jint id) {
    renderer.queueEvent([id] {
        auto window = windowManager.getWindow(id);
        if (!window)
            return;

        JNIEnv *env = cache.getEnv();
        if (window->inputOutput && window->drawable)
            releaseDrawable(env, window->drawable.get());

        for (auto& [contentId, content] : window->directContents)
            releaseDrawable(env, content.get());

        window->externalContent = nullptr;
        window->directContents.clear();

        env->DeleteGlobalRef(window->attributes);
        env->DeleteGlobalRef(window->windowObj);

        windowManager.deleteWindow(window);
        renderer.updateScene();
    });
    renderer.requestRenderer();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeCreateCursor(JNIEnv *env, jobject thiz, jobject cursorObj) {
    jobject drawableObj = env->GetObjectField(cursorObj, cache.cursorImage);

    auto cursor = std::make_unique<struct Cursor>();
    cursor->id = env->GetIntField(cursorObj, cache.cursorID);
    cursor->image = createDrawable(env, drawableObj, false);
    cursor->hotspotX = env->GetIntField(cursorObj, cache.cursorHotspotX);
    cursor->hotspotY = env->GetIntField(cursorObj, cache.cursorHotspotY);
    cursor->visible = env->CallBooleanMethod(cursorObj, cache.cursorIsVisible);
    cursor->cursorObj = env->NewGlobalRef(cursorObj);

    env->DeleteLocalRef(drawableObj);

    cursorManager.addCursor(cursor->id, std::move(cursor));
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeFreeCursor(JNIEnv *env, jobject thiz, jint id) {
    renderer.queueEvent([id] {
        auto cursor = cursorManager.getCursor(id);
        if (!cursor)
            return;

        JNIEnv *env = cache.getEnv();
        releaseDrawable(env, cursor->image.get());

        {
            auto lock = windowManager.windowLock.lock();
            for (auto& [windowId, window] : windowManager.getWindowTree()) {
                if (window->cursor == cursor)
                    window->cursor = nullptr;
            }
        }

        cursorManager.removeCursor(env, cursor);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeBindCursor(JNIEnv *env, jobject thiz, jint windowId, jint cursorId, jboolean visible) {
    auto window = windowManager.getWindow(windowId);
    if (!window)
        return;

    auto cursor = cursorManager.getCursor(cursorId);
    if (!cursor)
        return;

    cursor->visible = visible;

    window->cursor = cursor;
    for (auto &child : window->children)
        child->cursor = cursor;

    renderer.requestRenderer();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativePointerMove(JNIEnv *env, jobject thiz, jint posX, jint posY) {
    cursorManager.pointer.posX = posX;
    cursorManager.pointer.posY = posY;

    renderer.requestCursorRender();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeChangeWindowZOrder(JNIEnv *env, jobject thiz, jint stackMode, jint id, jint siblingId) {
    auto window = windowManager.getWindow(id);
    auto sibling = windowManager.getWindow(siblingId);

    if (!window)
        return;

    windowManager.changeZOrder(stackMode, window, sibling);

    renderer.queueEvent([]{ renderer.updateScene(); });
    renderer.requestRenderer();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeUpdateWindowGeometry(JNIEnv *env, jobject thiz, jint id, jint width, jint height, jint x, jint y, jboolean resized) {
    auto window = windowManager.getWindow(id);
    if (!window)
        return;

    window->width = width;
    window->height = height;
    window->x = x;
    window->y = y;

    if (resized && window->inputOutput && window->drawable) {
        env->DeleteGlobalRef(window->drawable->drawableObj);
        jobject drawableObj = env->CallObjectMethod(window->windowObj, cache.windowGetContent);
        window->drawable->drawableObj = env->NewGlobalRef(drawableObj);
        env->DeleteLocalRef(drawableObj);

        auto newAhb = (AHardwareBuffer *)env->GetLongField(window->drawable->drawableObj, cache.drawableAHB);
        int newStride = env->GetShortField(window->drawable->drawableObj, cache.drawableStride);
        void *newData = nullptr;
        if (newAhb != nullptr) {
            AHardwareBuffer_acquire(newAhb);
        }
        else {
            jobject dataObj = env->GetObjectField(window->drawable->drawableObj, cache.drawableData);
            if (dataObj != nullptr) {
                newData = env->GetDirectBufferAddress(dataObj);
                env->DeleteLocalRef(dataObj);
            }
        }

        int64_t now = std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
        renderer.queueEvent([id, newAhb, newData, width, height, newStride, now] {
            auto window = windowManager.getWindow(id);
            if (!window || !window->drawable) {
                if (newAhb != nullptr) AHardwareBuffer_release(newAhb);
                return;
            }

            auto drawable = window->drawable.get();
            if (newAhb != nullptr) {
                // Keep presenting the old buffer until the client draws into the new one.
                if (drawable->pendingAhb != nullptr)
                    AHardwareBuffer_release(drawable->pendingAhb);
                drawable->pendingAhb = newAhb;
                drawable->pendingWidth = width;
                drawable->pendingHeight = height;
                drawable->pendingStride = newStride;
                drawable->pendingSince = now;
                if (drawable->ahb == nullptr)
                    applyPendingBuffer(drawable);
            }
            else {
                if (drawable->ahb != nullptr) {
                    AHardwareBuffer_release(drawable->ahb);
                    drawable->ahb = nullptr;
                }
                drawable->data = newData;
                drawable->width = width;
                drawable->height = height;
                drawable->stride = newStride;
                if (drawable->glTexture != nullptr)
                    drawable->glTexture->sizeChanged = true;
            }
        });
    }

    if (resized) {
        renderer.queueEvent([]{ renderer.updateScene(); });
    } else {
        renderer.queueEvent([id]{
            auto window = windowManager.getWindow(id);
            if (window)
                renderer.updateWindowPosition(window);
        });
        renderer.queueEvent([]{ renderer.updateScene(); });
    }
    renderer.requestRenderer();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeUpdateWindowContent(JNIEnv *env, jobject thiz, jint id) {
    auto window = windowManager.getWindow(id);
    if (!window)
        return;

    window->hasContent = true;
    renderer.markContentUpdate(id, false);
    if (window->drawable && window->drawable->ahb == nullptr && window->drawable->glTexture != nullptr)
        window->drawable->glTexture->isDirty = true;

    // Unsynchronized peek: a missed pending buffer is still picked up by the render-side timeout.
    if (window->drawable && window->drawable->pendingAhb != nullptr) {
        renderer.queueEvent([id] {
            auto window = windowManager.getWindow(id);
            if (window && window->drawable)
                applyPendingBuffer(window->drawable.get());
        });
    }
    renderer.requestRenderer();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeReparentWindow(JNIEnv *env, jobject thiz, jint id, jint parentId, jshort x, jshort y) {
    auto window = windowManager.getWindow(id);
    auto parent = windowManager.getWindow(parentId);

    if (!window || !parent)
        return;

    window->x = x;
    window->y = y;

    windowManager.reparentWindow(window, parent);

    renderer.queueEvent([id]{
        auto window = windowManager.getWindow(id);
        if (window)
            renderer.updateWindowPosition(window);
    });
    renderer.queueEvent([]{ renderer.updateScene(); });
    renderer.requestRenderer();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeToggleFullscreen(JNIEnv *env, jobject thiz) {
    renderer.toggleFullscreen = true;
    renderer.requestRenderer();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeSetCursorVisible(JNIEnv *env, jobject thiz, jboolean visible) {
    renderer.cursorVisible = visible;
    renderer.requestRenderer();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeSetScreenOffsetYRelativeToCursor(JNIEnv *env, jobject thiz, jboolean cond) {
    renderer.screenOffsetYRelativeToCursor = cond;
    renderer.requestRenderer();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeSetMagnifierZoom(JNIEnv *env, jobject thiz, jfloat magnifierZoom) {
    renderer.magnifierZoom = magnifierZoom;
    renderer.requestRenderer();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeSetUnviewableWMClass(JNIEnv *env, jobject thiz, jstring unviewableWMName) {
    const char *chars = env->GetStringUTFChars(unviewableWMName, nullptr);
    std::string str(chars);
    env->ReleaseStringUTFChars(unviewableWMName, chars);
    windowManager.setUnviewableWMClass(str);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeSetWindowClassName(JNIEnv *env, jobject thiz, jint id, jstring className) {
    auto window = windowManager.getWindow(id);
    if (!window)
        return;

    const char *chars = env->GetStringUTFChars(className, nullptr);
    std::string str(chars);
    env->ReleaseStringUTFChars(className, chars);
    window->className = str;
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeSetPostProcessing(JNIEnv *env, jobject thiz, jstring config) {
    const char *chars = env->GetStringUTFChars(config, nullptr);
    std::string str(chars);
    env->ReleaseStringUTFChars(config, chars);
    renderer.setPostProcessing(str);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeSetScaleMode(JNIEnv *env, jobject thiz, jint mode, jint alignment) {
    renderer.setScaleMode(mode, alignment);
}

// out = {scale, offsetX, offsetY}: X screen point = game point * scale + offset.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeGetFitMapping(JNIEnv *env, jobject thiz, jfloatArray out) {
    float values[3];
    bool active = renderer.getFitMapping(&values[0], &values[1], &values[2]);
    env->SetFloatArrayRegion(out, 0, 3, values);
    return active;
}

// Frame generation for the HUD: {multiplier (0 = off), game fps, shown fps, ms per generated frame}.
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeGetFrameGenStats(JNIEnv *env, jclass clazz) {
    static auto lastTime = std::chrono::steady_clock::now();
    static uint32_t lastShown = 0;
    auto now = std::chrono::steady_clock::now();
    uint32_t shown = renderer.fgShownFrames.load();
    float seconds = std::chrono::duration<float>(now - lastTime).count();
    float shownFps = seconds > 0 ? float(shown - lastShown) / seconds : 0.0f;
    lastTime = now;
    lastShown = shown;
    float values[4] = {float(renderer.fgStatMultiplier.load()), renderer.fgStatRealFps.load(), shownFps,
                       renderer.fgStatGenerateMs.load()};
    jfloatArray out = env->NewFloatArray(4);
    env->SetFloatArrayRegion(out, 0, 4, values);
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeRequestRender(JNIEnv *env, jobject thiz) {
    renderer.requestRenderer();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeCreateSurface(JNIEnv *env, jobject thiz, jobject surface) {
    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    renderer.createSurface(window);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeDestroySurface(JNIEnv *env, jobject thiz) {
    renderer.destroySurface();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeChangeSurface(JNIEnv *env, jobject thiz, jint width, jint height) {
    renderer.changeSurface(width, height);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativePause(JNIEnv *env, jobject thiz) {
    renderer.pause();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeResume(JNIEnv *env, jobject thiz) {
    renderer.resume();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeStop(JNIEnv *env, jobject thiz) {
    renderer.stop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeAddDirectContent(JNIEnv *env, jobject thiz, jint windowId, jobject drawableObj) {
    Drawable *drawable = createDrawable(env, drawableObj, true).release();

    renderer.queueEvent([windowId, drawable] {
        std::unique_ptr<struct Drawable> content(drawable);
        auto window = windowManager.getWindow(windowId);
        if (!window) {
            releaseDrawable(cache.getEnv(), content.get());
            return;
        }

        if (window->parent)
            window->parent->surface = window;

        auto it = window->directContents.find(content->id);
        if (it != window->directContents.end()) {
            if (window->externalContent == it->second.get())
                window->externalContent = nullptr;
            releaseDrawable(cache.getEnv(), it->second.get());
        }

        window->directContents[content->id] = std::move(content);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeUpdateDirectContent(JNIEnv *env, jobject thiz, jint windowId, jint drawableId) {
    renderer.queueEvent([windowId, drawableId] {
        auto window = windowManager.getWindow(windowId);
        if (!window)
            return;

        auto it = window->directContents.find(drawableId);
        if (it == window->directContents.end())
            return;

        window->externalContent = it->second.get();
    });
    renderer.markContentUpdate(windowId, true);
    renderer.requestRenderer();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeRemoveDirectContent(JNIEnv *env, jobject thiz, jint windowId, jint drawableId) {
    renderer.queueEvent([windowId, drawableId] {
        auto window = windowManager.getWindow(windowId);
        if (!window)
            return;

        auto it = window->directContents.find(drawableId);
        if (it == window->directContents.end())
            return;

        if (window->externalContent == it->second.get())
            window->externalContent = nullptr;

        releaseDrawable(cache.getEnv(), it->second.get());
        window->directContents.erase(it);

        if (window->directContents.empty() && window->parent && window->parent->surface == window)
            window->parent->surface = nullptr;
    });
    renderer.requestRenderer();
}
