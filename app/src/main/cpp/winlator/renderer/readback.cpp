// GPU copy of a GL client's finished frame (GLX/Gladio) into an X drawable's
// AHardwareBuffer, so the EGL renderer can present it like any other window.
// Runs on the caller's thread with the caller's EGL context current.
//
// The copy never blocks the client: the destination image/texture/framebuffer are cached,
// and completion is handed to the renderer as a native fence that its GPU waits on.

#include "readback.hpp"

#include <algorithm>
#include <cstdint>
#include <mutex>
#include <unordered_map>
#include <vector>
#include <unistd.h>
#include <jni.h>
#include <android/log.h>

#define EGL_EGLEXT_PROTOTYPES
#define GL_GLEXT_PROTOTYPES

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>

#define LOG_TAG "Readback"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static constexpr EGLTimeKHR FENCE_TIMEOUT_NS = 100000000; // 100 ms, only when native fences are unavailable
static constexpr size_t MAX_CACHED_TARGETS = 6;

struct BlitTarget {
    EGLContext context;
    AHardwareBuffer *ahb;
    EGLImageKHR image;
    GLuint texture;
    GLuint framebuffer;
    uint64_t lastUse;
};

static std::mutex cacheLock;
static std::vector<BlitTarget> cache;
static uint64_t useCounter = 0;

static std::mutex fenceLock;
static std::unordered_map<AHardwareBuffer *, int> pendingFences;

int takeReadbackFence(AHardwareBuffer *ahb) {
    std::lock_guard<std::mutex> lock(fenceLock);
    if (pendingFences.empty())
        return -1;

    auto it = pendingFences.find(ahb);
    if (it == pendingFences.end())
        return -1;

    int fd = it->second;
    pendingFences.erase(it);
    return fd;
}

static void setReadbackFence(AHardwareBuffer *ahb, int fd) {
    std::lock_guard<std::mutex> lock(fenceLock);
    auto it = pendingFences.find(ahb);
    if (it != pendingFences.end()) {
        close(it->second);
        it->second = fd;
    }
    else pendingFences[ahb] = fd;
}

static void destroyTarget(EGLDisplay display, BlitTarget &target, EGLContext current) {
    // GL objects can only be deleted in their own context; otherwise they go away with it.
    if (target.context == current) {
        glDeleteFramebuffers(1, &target.framebuffer);
        glDeleteTextures(1, &target.texture);
    }
    eglDestroyImageKHR(display, target.image);
    AHardwareBuffer_release(target.ahb);
}

// Called with cacheLock held.
static BlitTarget *getTarget(EGLDisplay display, EGLContext context, AHardwareBuffer *ahb) {
    for (auto &target : cache) {
        if (target.context == context && target.ahb == ahb) {
            target.lastUse = ++useCounter;
            return &target;
        }
    }

    EGLClientBuffer clientBuffer = eglGetNativeClientBufferANDROID(ahb);
    const EGLint attribs[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
    EGLImageKHR image = clientBuffer ? eglCreateImageKHR(display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, clientBuffer, attribs) : EGL_NO_IMAGE_KHR;
    if (image == EGL_NO_IMAGE_KHR) {
        LOGE("Failed to create EGLImage for drawable buffer");
        return nullptr;
    }

    GLint prevTexture = 0, prevDrawFramebuffer = 0;
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &prevTexture);
    glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &prevDrawFramebuffer);

    BlitTarget target{};
    target.context = context;
    target.ahb = ahb;
    target.image = image;

    glGenTextures(1, &target.texture);
    glBindTexture(GL_TEXTURE_2D, target.texture);
    glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, image);

    glGenFramebuffers(1, &target.framebuffer);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, target.framebuffer);
    glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target.texture, 0);
    bool complete = glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;

    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, prevDrawFramebuffer);
    glBindTexture(GL_TEXTURE_2D, prevTexture);

    if (!complete) {
        AHardwareBuffer_Desc desc{};
        AHardwareBuffer_describe(ahb, &desc);
        LOGE("Drawable buffer is not renderable (%ux%u format %u)", desc.width, desc.height, desc.format);
        glDeleteFramebuffers(1, &target.framebuffer);
        glDeleteTextures(1, &target.texture);
        eglDestroyImageKHR(display, image);
        return nullptr;
    }

    // Keep the buffer alive (and its address unique) while cached.
    AHardwareBuffer_acquire(ahb);

    if (cache.size() >= MAX_CACHED_TARGETS) {
        auto oldest = std::min_element(cache.begin(), cache.end(), [](const BlitTarget &a, const BlitTarget &b) { return a.lastUse < b.lastUse; });
        destroyTarget(display, *oldest, context);
        cache.erase(oldest);
    }

    target.lastUse = ++useCounter;
    cache.push_back(target);
    return &cache.back();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_xserver_Drawable_blitReadBuffer(JNIEnv *env, jclass clazz, jlong ahbPtr, jint width, jint height, jboolean flipY) {
    auto ahb = reinterpret_cast<AHardwareBuffer *>(ahbPtr);
    EGLDisplay display = eglGetCurrentDisplay();
    EGLContext context = eglGetCurrentContext();
    if (!ahb || display == EGL_NO_DISPLAY || context == EGL_NO_CONTEXT)
        return JNI_FALSE;

    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(ahb, &desc);
    int dstWidth = std::min<int>(width, desc.width);
    int dstHeight = std::min<int>(height, desc.height);

    std::lock_guard<std::mutex> lock(cacheLock);
    BlitTarget *target = getTarget(display, context, ahb);
    if (!target)
        return JNI_FALSE;

    GLint prevDrawFramebuffer = 0;
    glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &prevDrawFramebuffer);
    GLboolean scissor = glIsEnabled(GL_SCISSOR_TEST);

    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, target->framebuffer);
    if (scissor) glDisable(GL_SCISSOR_TEST);

    // GL read buffers are bottom-up, X drawables are top-down.
    glBlitFramebuffer(0, 0, dstWidth, dstHeight,
                      0, flipY ? dstHeight : 0, dstWidth, flipY ? 0 : dstHeight,
                      GL_COLOR_BUFFER_BIT, GL_NEAREST);

    if (scissor) glEnable(GL_SCISSOR_TEST);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, prevDrawFramebuffer);

    // Hand completion to the renderer's GPU instead of stalling this thread.
    // eglDupNativeFenceFDANDROID is not in the NDK stubs, so it is resolved at runtime.
    static auto dupNativeFenceFD = reinterpret_cast<PFNEGLDUPNATIVEFENCEFDANDROIDPROC>(eglGetProcAddress("eglDupNativeFenceFDANDROID"));
    EGLSyncKHR fence = dupNativeFenceFD ? eglCreateSyncKHR(display, EGL_SYNC_NATIVE_FENCE_ANDROID, nullptr) : EGL_NO_SYNC_KHR;
    glFlush();
    if (fence != EGL_NO_SYNC_KHR) {
        int fd = dupNativeFenceFD(display, fence);
        eglDestroySyncKHR(display, fence);
        if (fd >= 0) {
            setReadbackFence(ahb, fd);
            return JNI_TRUE;
        }
    }

    // No native fence support: wait here so the renderer never shows a half-copied frame.
    EGLSyncKHR cpuFence = eglCreateSyncKHR(display, EGL_SYNC_FENCE_KHR, nullptr);
    if (cpuFence != EGL_NO_SYNC_KHR) {
        eglClientWaitSyncKHR(display, cpuFence, EGL_SYNC_FLUSH_COMMANDS_BIT_KHR, FENCE_TIMEOUT_NS);
        eglDestroySyncKHR(display, cpuFence);
    }
    else glFinish();

    return JNI_TRUE;
}
