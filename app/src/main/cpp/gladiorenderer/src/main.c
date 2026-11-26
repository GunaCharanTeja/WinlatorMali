#include "gl_context.h"
#include <android/log.h>

#define LOG_TAG "GladioRenderer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

JNIEXPORT jlong JNICALL
Java_com_winlator_cmod_xserver_extensions_GLXExtension_createGLContext(JNIEnv *env, jobject obj,
                                                                      jint clientFd) {
    LOGI("Java_com_winlator_cmod_xserver_extensions_GLXExtension_createGLContext(clientFd=%d)", clientFd);
    GLContext* context = createGLContext(env, obj, clientFd);
    if (!context) {
        LOGE("createGLContext failed for clientFd=%d", clientFd);
    }
    return (jlong)context;
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xserver_extensions_GLXExtension_destroyGLContext(JNIEnv *env, jobject obj,
                                                                       jlong contextPtr) {
    LOGI("Java_com_winlator_cmod_xserver_extensions_GLXExtension_destroyGLContext(contextPtr=%p)", (void*)contextPtr);
    destroyGLContext(env, (GLContext*)contextPtr);
}

JNIEXPORT jlong JNICALL
Java_com_winlator_cmod_xserver_extensions_GLXExtension_createGLXContext(JNIEnv *env, jobject obj,
                                                                       jint contextId, jlong sharedContextPtr) {
    LOGI("Java_com_winlator_cmod_xserver_extensions_GLXExtension_createGLXContext(contextId=%d, shared=%p)", contextId, (void*)sharedContextPtr);
    GLXContext* context = createGLXContext(contextId, (GLXContext*)sharedContextPtr);
    if (!context) {
        LOGE("createGLXContext failed for contextId=%d", contextId);
    }
    return (jlong)context;
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xserver_extensions_GLXExtension_destroyGLXContext(JNIEnv *env, jobject obj,
                                                                        jlong contextPtr) {
    LOGI("Java_com_winlator_cmod_xserver_extensions_GLXExtension_destroyGLXContext(contextPtr=%p)", (void*)contextPtr);
    destroyGLXContext((GLXContext*)contextPtr);
}

// Fallback symbols for non-cmod package if ever referenced
JNIEXPORT jlong JNICALL
Java_com_winlator_xserver_extensions_GLXExtension_createGLContext(JNIEnv *env, jobject obj,
                                                                  jint clientFd) {
    return Java_com_winlator_cmod_xserver_extensions_GLXExtension_createGLContext(env, obj, clientFd);
}

JNIEXPORT void JNICALL
Java_com_winlator_xserver_extensions_GLXExtension_destroyGLContext(JNIEnv *env, jobject obj,
                                                                   jlong contextPtr) {
    Java_com_winlator_cmod_xserver_extensions_GLXExtension_destroyGLContext(env, obj, contextPtr);
}

JNIEXPORT jlong JNICALL
Java_com_winlator_xserver_extensions_GLXExtension_createGLXContext(JNIEnv *env, jobject obj,
                                                                   jint contextId, jlong sharedContextPtr) {
    return Java_com_winlator_cmod_xserver_extensions_GLXExtension_createGLXContext(env, obj, contextId, sharedContextPtr);
}

JNIEXPORT void JNICALL
Java_com_winlator_xserver_extensions_GLXExtension_destroyGLXContext(JNIEnv *env, jobject obj,
                                                                    jlong contextPtr) {
    Java_com_winlator_cmod_xserver_extensions_GLXExtension_destroyGLXContext(env, obj, contextPtr);
}