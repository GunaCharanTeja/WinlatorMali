#include <jni.h>
#include "apex_engine.h"
#include <android/log.h>
#include <chrono>
#include <EGL/egl.h>
#include <EGL/eglext.h>

#define LOG_TAG "ApexEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT void JNICALL
Java_com_winlator_cmod_renderer_ApexNativeBridge_nativeProcessFrame(
    JNIEnv* env, jclass clazz, jint inputTextureId, jint outputFboId, jint width, jint height) {
    (void)env; (void)clazz;
    
    if (apex::ApexEngine::getInstance().isActive()) {
        apex::ApexEngine::getInstance().processFrame(
            static_cast<GLuint>(inputTextureId),
            static_cast<GLuint>(outputFboId),
            width, height
        );
    }
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_renderer_ApexNativeBridge_nativeProcessFrameWithData(
    JNIEnv* env, jclass clazz, jint inputTextureId, jint depthTextureId, jint hudTextureId, jint outputFboId, jint width, jint height) {
    (void)env; (void)clazz;
    
    if (apex::ApexEngine::getInstance().isActive()) {
        apex::ApexEngine::getInstance().processFrameWithData(
            static_cast<GLuint>(inputTextureId),
            static_cast<GLuint>(depthTextureId),
            static_cast<GLuint>(hudTextureId),
            static_cast<GLuint>(outputFboId),
            width, height
        );
    }
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_renderer_ApexNativeBridge_nativeInit(JNIEnv* env, jclass clazz, jint width, jint height) {
    (void)env; (void)clazz;
    apex::ApexEngine::getInstance().init(width, height);
    LOGI("ApexEngine nativeInit called (%dx%d)", width, height);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_renderer_ApexNativeBridge_nativeSetActive(JNIEnv* env, jclass clazz, jboolean active) {
    (void)env; (void)clazz;
    apex::ApexEngine::getInstance().setActive(active);
    LOGI("ApexEngine active set to: %d", active);
}

JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_renderer_ApexNativeBridge_nativeIsActive(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return apex::ApexEngine::getInstance().isActive() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_renderer_ApexNativeBridge_nativeSetQuality(JNIEnv* env, jclass clazz, jint quality) {
    (void)env; (void)clazz;
    apex::ApexEngine::getInstance().setQualityPreset(quality);
}

JNIEXPORT jint JNICALL
Java_com_winlator_cmod_renderer_ApexNativeBridge_nativeGetQuality(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return apex::ApexEngine::getInstance().getQualityPreset();
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_renderer_ApexNativeBridge_nativeSetLoggingEnabled(JNIEnv* env, jclass clazz, jboolean enabled) {
    (void)env; (void)clazz;
    apex::ApexEngine::getInstance().setLoggingEnabled(enabled);
}

JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_renderer_ApexNativeBridge_nativeIsLoggingEnabled(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return apex::ApexEngine::getInstance().isLoggingEnabled() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_renderer_ApexNativeBridge_nativeSetTargetFPS(JNIEnv* env, jclass clazz, jint fps) {
    (void)env; (void)clazz;
    apex::ApexEngine::getInstance().setTargetFPS(fps);
}

JNIEXPORT jint JNICALL
Java_com_winlator_cmod_renderer_ApexNativeBridge_nativeGetTargetFPS(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return apex::ApexEngine::getInstance().getTargetFPS();
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_renderer_ApexNativeBridge_nativeSetShutterGain(JNIEnv* env, jclass clazz, jfloat gain) {
    (void)env; (void)clazz;
    apex::ApexEngine::getInstance().setShutterGain(gain);
}

JNIEXPORT jfloat JNICALL
Java_com_winlator_cmod_renderer_ApexNativeBridge_nativeGetShutterGain(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return apex::ApexEngine::getInstance().getShutterGain();
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_renderer_ApexNativeBridge_nativeSetFlowScale(JNIEnv* env, jclass clazz, jfloat scale) {
    (void)env; (void)clazz;
    apex::ApexEngine::getInstance().setFlowScale(scale);
}

JNIEXPORT jfloat JNICALL
Java_com_winlator_cmod_renderer_ApexNativeBridge_nativeGetFlowScale(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return apex::ApexEngine::getInstance().getFlowScale();
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_renderer_ApexNativeBridge_nativeUpdateDimensions(JNIEnv* env, jclass clazz, jint width, jint height) {
    (void)env; (void)clazz;
    apex::ApexEngine::getInstance().updateDimensions(width, height);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_renderer_ApexNativeBridge_nativeOnFrameCaptured(JNIEnv* env, jclass clazz, jboolean isActualNewFrame) {
    (void)env; (void)clazz; (void)isActualNewFrame;
    apex::ApexEngine::getInstance().setPendingRealFrame(true);
}

JNIEXPORT jfloat JNICALL
Java_com_winlator_cmod_renderer_ApexNativeBridge_nativeGetInterpolationFactor(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    int64_t nowNanos = std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
    return apex::ApexEngine::getInstance().getInterpolationFactor(nowNanos);
}

JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_renderer_ApexNativeBridge_nativeIsGeneratedFrame(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return apex::ApexEngine::getInstance().isRenderingGeneratedFrame() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_renderer_ApexNativeBridge_nativeSetDebugOverlay(JNIEnv* env, jclass clazz, jboolean enabled) {
    (void)env; (void)clazz;
    apex::ApexEngine::getInstance().setDebugOverlay(enabled);
}

JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_renderer_ApexNativeBridge_nativeIsDebugOverlay(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return apex::ApexEngine::getInstance().isDebugOverlay() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_renderer_ApexNativeBridge_nativeDestroy(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    apex::ApexEngine::getInstance().destroy();
    LOGI("ApexEngine destroyed");
}

JNIEXPORT jint JNICALL
Java_com_winlator_cmod_renderer_ApexNativeBridge_nativeGetRealFPS(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return apex::ApexEngine::getInstance().getActualRealFrameCount();
}

JNIEXPORT jint JNICALL
Java_com_winlator_cmod_renderer_ApexNativeBridge_nativeGetGenFPS(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return apex::ApexEngine::getInstance().getGeneratedFrameCount();
}

JNIEXPORT jint JNICALL
Java_com_winlator_cmod_renderer_ApexNativeBridge_nativeGetAutoMultiplier(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return apex::ApexEngine::getInstance().getAutoMultiplier();
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_renderer_NativeRenderer_eglSwapBuffersWrapper(JNIEnv* env, jclass clazz, jlong dpy, jlong surf) {
    (void)env; (void)clazz;
    EGLDisplay display = reinterpret_cast<EGLDisplay>(dpy);
    EGLSurface surface = reinterpret_cast<EGLSurface>(surf);
    if (display != EGL_NO_DISPLAY && surface != EGL_NO_SURFACE) {
        eglSwapBuffers(display, surface);
    }
}

JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_renderer_NativeRenderer_initEGLContext(JNIEnv* env, jclass clazz, jobject nativeWindow) {
    (void)env; (void)clazz; (void)nativeWindow;
    return JNI_TRUE;
}

JNIEXPORT jlong JNICALL
Java_com_winlator_cmod_renderer_NativeRenderer_getEGLDisplay(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return reinterpret_cast<jlong>(eglGetCurrentDisplay());
}

JNIEXPORT jlong JNICALL
Java_com_winlator_cmod_renderer_NativeRenderer_getEGLSurface(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return reinterpret_cast<jlong>(eglGetCurrentSurface(EGL_DRAW));
}

} // extern "C"
