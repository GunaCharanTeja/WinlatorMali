#include "apex_engine.h"
#include "apex_shaders.h"
#include <vector>
#include <cstring>
#include <iomanip>
#include <sstream>

namespace apex {

ApexEngine& ApexEngine::getInstance() {
    static ApexEngine instance;
    return instance;
}

ApexEngine::ApexEngine() {
    mDesktopPassTextures.fill(0);
    mDeltaHistory.fill(33333334.0f);
    mSortedHistory.fill(33333334.0f);
}

ApexEngine::~ApexEngine() {
    destroy();
}

static const char* getShaderTypeName(GLenum type) {
    switch (type) {
        case GL_VERTEX_SHADER:   return "VERTEX_SHADER";
        case GL_FRAGMENT_SHADER: return "FRAGMENT_SHADER";
        case GL_COMPUTE_SHADER:  return "COMPUTE_SHADER";
        default:                 return "UNKNOWN_SHADER";
    }
}

static const char* getGlErrorString(GLenum err) {
    switch (err) {
        case GL_NO_ERROR:                      return "GL_NO_ERROR";
        case GL_INVALID_ENUM:                  return "GL_INVALID_ENUM";
        case GL_INVALID_VALUE:                 return "GL_INVALID_VALUE";
        case GL_INVALID_OPERATION:             return "GL_INVALID_OPERATION";
        case GL_INVALID_FRAMEBUFFER_OPERATION: return "GL_INVALID_FRAMEBUFFER_OPERATION";
        case GL_OUT_OF_MEMORY:                 return "GL_OUT_OF_MEMORY";
        default:                               return "GL_UNKNOWN_ERROR";
    }
}

static GLuint compileShader(GLenum type, const char* source) {
    if (!source || std::strlen(source) == 0) {
        __android_log_print(ANDROID_LOG_ERROR, "ApexEngine", "ApexShader: FAILED TO COMPILE - Source is empty or null");
        return 0;
    }

    GLuint shader = glCreateShader(type);
    if (shader == 0) {
        __android_log_print(ANDROID_LOG_ERROR, "ApexEngine", "ApexShader: FAILED TO COMPILE - glCreateShader returned 0");
        return 0;
    }

    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);

    GLint success = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &success);
    if (!success) {
        GLint logLen = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &logLen);
        std::vector<char> infoLog(std::max(1, logLen));
        if (logLen > 0) {
            glGetShaderInfoLog(shader, logLen, nullptr, infoLog.data());
        }
        __android_log_print(ANDROID_LOG_ERROR, "ApexEngine", "ApexShader: FAILED TO COMPILE - %s", infoLog.data());
        glDeleteShader(shader);
        return 0;
    }

    APEX_LOGD("Shader compiled successfully: ID %u (%s, %zu bytes)", shader, getShaderTypeName(type), std::strlen(source));
    return shader;
}

static GLuint createProgram(GLuint vs, GLuint fs) {
    if (!vs || !fs) {
        APEX_LOGE("createProgram: Invalid vertex (%u) or fragment (%u) shader handles", vs, fs);
        return 0;
    }

    GLuint program = glCreateProgram();
    if (program == 0) {
        APEX_LOGE("createProgram: glCreateProgram failed! GL Error: %s", getGlErrorString(glGetError()));
        return 0;
    }

    glAttachShader(program, vs);
    glAttachShader(program, fs);
    glLinkProgram(program);

    GLint success = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &success);
    if (!success) {
        GLint logLen = 0;
        glGetProgramiv(program, GL_INFO_LOG_LENGTH, &logLen);
        std::vector<char> infoLog(std::max(1, logLen));
        if (logLen > 0) {
            glGetProgramInfoLog(program, logLen, nullptr, infoLog.data());
        }
        APEX_LOGE("=================================================================");
        APEX_LOGE("GRAPHICS PROGRAM LINKING FAILED! [VS: %u, FS: %u]", vs, fs);
        APEX_LOGE("InfoLog:\n%s", infoLog.data());
        APEX_LOGE("=================================================================");
        glDeleteProgram(program);
        return 0;
    }

    APEX_LOGI("Graphics Program linked successfully: ID %u [VS: %u, FS: %u]", program, vs, fs);
    return program;
}

static GLuint createComputeProgram(const char* computeSource) {
    GLuint cs = compileShader(GL_COMPUTE_SHADER, computeSource);
    if (!cs) {
        APEX_LOGE("createComputeProgram: Compute shader compilation failed");
        return 0;
    }

    GLuint program = glCreateProgram();
    if (program == 0) {
        APEX_LOGE("createComputeProgram: glCreateProgram failed! GL Error: %s", getGlErrorString(glGetError()));
        glDeleteShader(cs);
        return 0;
    }

    glAttachShader(program, cs);
    glLinkProgram(program);
    glDeleteShader(cs);

    GLint success = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &success);
    if (!success) {
        GLint logLen = 0;
        glGetProgramiv(program, GL_INFO_LOG_LENGTH, &logLen);
        std::vector<char> infoLog(std::max(1, logLen));
        if (logLen > 0) {
            glGetProgramInfoLog(program, logLen, nullptr, infoLog.data());
        }
        APEX_LOGE("=================================================================");
        APEX_LOGE("COMPUTE PROGRAM LINKING FAILED!");
        APEX_LOGE("InfoLog:\n%s", infoLog.data());
        APEX_LOGE("=================================================================");
        glDeleteProgram(program);
        return 0;
    }

    APEX_LOGI("Compute Program linked successfully: ID %u", program);
    return program;
}

void ApexEngine::compileShaders() {
    APEX_LOGI("Compiling Apex Optical Flow & Warping Shaders...");

    if (mComputeProgramFused == 0) {
        mComputeProgramFused = createComputeProgram(kComputeShaderFused);
    }
    if (mComputeProgramMulti == 0) {
        mComputeProgramMulti = createComputeProgram(kComputeShaderMulti);
    }
    if (mWarpingProgram == 0) {
        GLuint vs = compileShader(GL_VERTEX_SHADER, kWarpingVertexShader);
        GLuint fs = compileShader(GL_FRAGMENT_SHADER, kWarpingFragmentShader);
        if (vs && fs) {
            mWarpingProgram = createProgram(vs, fs);
            glDeleteShader(vs);
            glDeleteShader(fs);
        }
    }

    if (mComputeProgramFused && mComputeProgramMulti && mWarpingProgram) {
        APEX_LOGI("All Apex Shaders compiled and linked successfully. (Fused: %u, Multi: %u, Warping: %u)",
                  mComputeProgramFused, mComputeProgramMulti, mWarpingProgram);
    } else {
        APEX_LOGE("One or more Apex shaders failed to compile! (Fused: %u, Multi: %u, Warping: %u)",
                  mComputeProgramFused, mComputeProgramMulti, mWarpingProgram);
    }

    // Cache all uniform locations and set sampler unit bindings once (Bug 3 fix)
    if (mComputeProgramFused) {
        mLocFused_currFrame = glGetUniformLocation(mComputeProgramFused, "currFrame");
        mLocFused_prevFrame = glGetUniformLocation(mComputeProgramFused, "prevFrame");
        mLocFused_mvHistory = glGetUniformLocation(mComputeProgramFused, "mvHistoryTexture");
        glUseProgram(mComputeProgramFused);
        glUniform1i(mLocFused_currFrame, 0);
        glUniform1i(mLocFused_prevFrame, 1);
        glUniform1i(mLocFused_mvHistory, 2);
    }
    if (mComputeProgramMulti) {
        mLocMulti_quality    = glGetUniformLocation(mComputeProgramMulti, "quality");
        mLocMulti_passIndex  = glGetUniformLocation(mComputeProgramMulti, "passIndex");
        mLocMulti_currFrame  = glGetUniformLocation(mComputeProgramMulti, "currFrame");
        mLocMulti_prevFrame  = glGetUniformLocation(mComputeProgramMulti, "prevFrame");
        mLocMulti_mvHistory  = glGetUniformLocation(mComputeProgramMulti, "mvHistoryTexture");
        mLocMulti_lumaL0     = glGetUniformLocation(mComputeProgramMulti, "lumaTexL0");
        mLocMulti_lumaL1     = glGetUniformLocation(mComputeProgramMulti, "lumaTexL1");
        mLocMulti_lumaL2     = glGetUniformLocation(mComputeProgramMulti, "lumaTexL2");
        mLocMulti_coarseMV   = glGetUniformLocation(mComputeProgramMulti, "coarseMVTex");
        mLocMulti_midMV      = glGetUniformLocation(mComputeProgramMulti, "midMVTex");
        mLocMulti_rawMV      = glGetUniformLocation(mComputeProgramMulti, "rawMVTex");
        mLocMulti_divergence = glGetUniformLocation(mComputeProgramMulti, "divergenceTex");
        mLocMulti_filteredMV = glGetUniformLocation(mComputeProgramMulti, "filteredMVTex");
        mLocMulti_lumaL3     = glGetUniformLocation(mComputeProgramMulti, "lumaTexL3");
        mLocMulti_dilated    = glGetUniformLocation(mComputeProgramMulti, "dilatedMVTex");
        glUseProgram(mComputeProgramMulti);
        glUniform1i(mLocMulti_currFrame,  0);
        glUniform1i(mLocMulti_prevFrame,  1);
        glUniform1i(mLocMulti_mvHistory,  2);
        glUniform1i(mLocMulti_lumaL0,     3);
        glUniform1i(mLocMulti_lumaL1,     4);
        glUniform1i(mLocMulti_lumaL2,     5);
        glUniform1i(mLocMulti_coarseMV,   6);
        glUniform1i(mLocMulti_midMV,      7);
        glUniform1i(mLocMulti_rawMV,      8);
        glUniform1i(mLocMulti_divergence, 9);
        glUniform1i(mLocMulti_filteredMV, 10);
        glUniform1i(mLocMulti_lumaL3,     11);
        glUniform1i(mLocMulti_dilated,    15);
    }
    if (mWarpingProgram) {
        mLocWarp_currCapture    = glGetUniformLocation(mWarpingProgram, "currentCapturedTexture");
        mLocWarp_prevCapture    = glGetUniformLocation(mWarpingProgram, "previousCapturedTexture");
        mLocWarp_motionVector   = glGetUniformLocation(mWarpingProgram, "motionVectorTexture");
        mLocWarp_resolution     = glGetUniformLocation(mWarpingProgram, "resolution");
        mLocWarp_interpolFactor = glGetUniformLocation(mWarpingProgram, "interpolationFactor");
        mLocWarp_qualityMode    = glGetUniformLocation(mWarpingProgram, "qualityMode");
        mLocWarp_blurIntensity  = glGetUniformLocation(mWarpingProgram, "uBlurIntensity");
        mLocWarp_flowScale      = glGetUniformLocation(mWarpingProgram, "uFlowScale");
        mLocWarp_debugOverlay   = glGetUniformLocation(mWarpingProgram, "uDebugOverlay");
        glUseProgram(mWarpingProgram);
        glUniform1i(mLocWarp_currCapture,  0);
        glUniform1i(mLocWarp_prevCapture,  1);
        glUniform1i(mLocWarp_motionVector, 2);
    }
    glUseProgram(0);
    APEX_LOGI("Uniform locations cached and sampler units initialized.");
}

static void createStorageTexture(GLuint& tex, int width, int height, const char* name) {
    if (tex == 0) {
        glGenTextures(1, &tex);
    }
    glBindTexture(GL_TEXTURE_2D, tex);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA16F, width, height);
    
    GLenum err = glGetError();
    if (err != GL_NO_ERROR) {
        APEX_LOGE("createStorageTexture (%s %dx%d) failed! Error: %s (0x%X)",
                  name, width, height, getGlErrorString(err), err);
    }

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glBindTexture(GL_TEXTURE_2D, 0);

    APEX_LOGD("Created RGBA16F Storage Texture: %s (ID %u, %dx%d)", name, tex, width, height);
}

static void createColorTexture(GLuint& tex, int width, int height, const char* name) {
    if (tex == 0) {
        glGenTextures(1, &tex);
    }
    glBindTexture(GL_TEXTURE_2D, tex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);

    GLenum err = glGetError();
    if (err != GL_NO_ERROR) {
        APEX_LOGE("createColorTexture (%s %dx%d) failed! Error: %s (0x%X)",
                  name, width, height, getGlErrorString(err), err);
    }

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glBindTexture(GL_TEXTURE_2D, 0);

    APEX_LOGD("Created RGBA8 Color Texture: %s (ID %u, %dx%d)", name, tex, width, height);
}

void ApexEngine::ensureResources(int width, int height) {
    int quality = mQualityPreset.load(std::memory_order_acquire);

    if (mSurfaceWidth != width || mSurfaceHeight != height) {
        APEX_LOGI("Dimension changed (%dx%d -> %dx%d), reallocating GPU resources...",
                  mSurfaceWidth, mSurfaceHeight, width, height);
        cleanupResources();
        mSurfaceWidth = width;
        mSurfaceHeight = height;
    }

    // Always-needed base resources
    if (mCurrentCapturedTexture == 0)
        createColorTexture(mCurrentCapturedTexture, width, height, "CurrentCapturedTexture");
    if (mPreviousCapturedTexture == 0)
        createColorTexture(mPreviousCapturedTexture, width, height, "PreviousCapturedTexture");
    if (mCaptureFbo == 0) {
        glGenFramebuffers(1, &mCaptureFbo);
        glBindFramebuffer(GL_FRAMEBUFFER, mCaptureFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, mCurrentCapturedTexture, 0);
        GLenum fboStatus = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (fboStatus != GL_FRAMEBUFFER_COMPLETE)
            APEX_LOGE("Capture FBO incomplete! Status: 0x%X", fboStatus);
        else
            APEX_LOGI("Capture FBO created and verified complete (ID %u)", mCaptureFbo);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }
    if (mMotionVectorTexture == 0)
        createStorageTexture(mMotionVectorTexture, width, height, "MotionVectorTexture");
    if (mMvHistoryTexture == 0)
        createStorageTexture(mMvHistoryTexture, width, height, "MvHistoryTexture");

    // Compute pyramid dimensions
    int wL1 = std::max(1, width / 2),  hL1 = std::max(1, height / 2);
    int wL2 = std::max(1, width / 4),  hL2 = std::max(1, height / 4);
    int wL3 = std::max(1, width / 8),  hL3 = std::max(1, height / 8);

    // Quality-conditional intermediate pass textures (Bug 4: correct sizes per actual dispatch resolution)
    if (quality >= QUALITY_PERFORMANCE) {
        // [0] lumaTexL0 — L0 neural features (passIndex=1 output, full res)
        if (mDesktopPassTextures[0] == 0)
            createStorageTexture(mDesktopPassTextures[0], width, height, "Pass0_L0Features");
        // [5] rawMVTex — forward MV field (passIndex=6 output, FULL res — was wrongly 1/8)
        if (mDesktopPassTextures[5] == 0)
            createStorageTexture(mDesktopPassTextures[5], width, height, "Pass5_RawForwardMV");
        // [7] filteredMVTex — 7x7 filtered MV (passIndex=8 output, FULL res — was wrongly 1/2)
        if (mDesktopPassTextures[7] == 0)
            createStorageTexture(mDesktopPassTextures[7], width, height, "Pass7_FilteredMV");
    }
    if (quality >= QUALITY_BALANCED) {
        // [1] lumaTexL1 — L1 half-scale features (passIndex=2 output)
        if (mDesktopPassTextures[1] == 0)
            createStorageTexture(mDesktopPassTextures[1], wL1, hL1, "Pass1_L1Features");
        // [4] midMVTex — mid-scale MV (passIndex=5 output, L1 half res — was wrongly 1/16)
        if (mDesktopPassTextures[4] == 0)
            createStorageTexture(mDesktopPassTextures[4], wL1, hL1, "Pass4_MidScaleMV");
        // [6] divergenceTex — backward flow MV (passIndex=7 output, FULL res — was wrongly 1/4)
        if (mDesktopPassTextures[6] == 0)
            createStorageTexture(mDesktopPassTextures[6], width, height, "Pass6_BackwardMV");
    }
    if (quality >= QUALITY_HIGH_QUALITY) {
        // [2] lumaTexL2 — L2 quarter-scale features (passIndex=3 output)
        if (mDesktopPassTextures[2] == 0)
            createStorageTexture(mDesktopPassTextures[2], wL2, hL2, "Pass2_L2Features");
        // [3] coarseMVTex — coarse MV (passIndex=4 output, L2 QUARTER res — was wrongly 1/8)
        if (mDesktopPassTextures[3] == 0)
            createStorageTexture(mDesktopPassTextures[3], wL2, hL2, "Pass3_CoarseMV");
    }
    if (quality == QUALITY_DESKTOP_QUALITY) {
        // [8] lumaTexL3 — L3 1/8x features (passIndex=10 output, L3 res)
        if (mDesktopPassTextures[8] == 0)
            createStorageTexture(mDesktopPassTextures[8], wL3, hL3, "Pass8_L3Features");
        // [10] coarseL3MVTex — L3 coarse MV (passIndex=11 output, L3 res)
        if (mDesktopPassTextures[10] == 0)
            createStorageTexture(mDesktopPassTextures[10], wL3, hL3, "Pass10_L3CoarseMV");
        // [12] dilatedMVTex — dilation/inpainting buffer (passIndex=8 2nd call, full res)
        if (mDesktopPassTextures[12] == 0)
            createStorageTexture(mDesktopPassTextures[12], width, height, "Pass12_DilatedMV");
        // [9],[11],[13],[14],[15] are never bound to any active pass — not allocated
    }

    // Full-screen quad VAO & VBO
    if (mQuadVao == 0) {
        const float quadVerts[] = { 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f };
        glGenVertexArrays(1, &mQuadVao);
        glGenBuffers(1, &mQuadVbo);
        glBindVertexArray(mQuadVao);
        glBindBuffer(GL_ARRAY_BUFFER, mQuadVbo);
        glBufferData(GL_ARRAY_BUFFER, sizeof(quadVerts), quadVerts, GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 2 * sizeof(float), nullptr);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
        APEX_LOGI("Full-screen quad VAO (%u) and VBO (%u) initialized", mQuadVao, mQuadVbo);
    }
}

void ApexEngine::cleanupResources() {
    APEX_LOGI("Cleaning up ApexEngine GPU resources...");
    if (mCurrentCapturedTexture) { glDeleteTextures(1, &mCurrentCapturedTexture); mCurrentCapturedTexture = 0; }
    if (mPreviousCapturedTexture) { glDeleteTextures(1, &mPreviousCapturedTexture); mPreviousCapturedTexture = 0; }
    if (mMotionVectorTexture) { glDeleteTextures(1, &mMotionVectorTexture); mMotionVectorTexture = 0; }
    if (mMvHistoryTexture) { glDeleteTextures(1, &mMvHistoryTexture); mMvHistoryTexture = 0; }

    for (GLuint& tex : mDesktopPassTextures) {
        if (tex) {
            glDeleteTextures(1, &tex);
            tex = 0;
        }
    }

    if (mCaptureFbo) { glDeleteFramebuffers(1, &mCaptureFbo); mCaptureFbo = 0; }
    if (mQuadVbo) { glDeleteBuffers(1, &mQuadVbo); mQuadVbo = 0; }
    if (mQuadVao) { glDeleteVertexArrays(1, &mQuadVao); mQuadVao = 0; }
}

void ApexEngine::init(int width, int height) {
    APEX_LOGI("ApexEngine::init(%d, %d) - Initializing native GLES 3.1 Frame Generation Engine", width, height);
    mSurfaceWidth = width;
    mSurfaceHeight = height;
    compileShaders();
    ensureResources(width, height);
    mInitialized = true;
    APEX_LOGI("ApexEngine initialization complete! Active: %s, Preset: %s, Target FPS: %d",
              mActive.load() ? "TRUE" : "FALSE", getQualityPresetName(mQualityPreset.load()), mTargetFPS.load());
}

void ApexEngine::updateDimensions(int width, int height) {
    if (mSurfaceWidth != width || mSurfaceHeight != height) {
        APEX_LOGI("ApexEngine::updateDimensions(%dx%d -> %dx%d)", mSurfaceWidth, mSurfaceHeight, width, height);
        init(width, height);
    }
}

void ApexEngine::destroy() {
    APEX_LOGI("ApexEngine::destroy() - Destroying pipeline and shaders");
    cleanupResources();
    if (mComputeProgramFused) { glDeleteProgram(mComputeProgramFused); mComputeProgramFused = 0; }
    if (mComputeProgramMulti) { glDeleteProgram(mComputeProgramMulti); mComputeProgramMulti = 0; }
    if (mWarpingProgram) { glDeleteProgram(mWarpingProgram); mWarpingProgram = 0; }
    mInitialized = false;
    APEX_LOGI("ApexEngine destroyed successfully");
}

void ApexEngine::runComputePipeline(GLuint currTex, GLuint prevTex, int width, int height) {
    int quality = mQualityPreset.load(std::memory_order_acquire);

    // Ping-pong motion vector history
    std::swap(mMotionVectorTexture, mMvHistoryTexture);

    if (quality == QUALITY_ULTRA_PERFORMANCE) {
        // Preset 0: 1 Fused Pass (1:1 Native Resolution Evaluation)
        glUseProgram(mComputeProgramFused);
        // Sampler unit bindings set once in compileShaders(); just rebind textures
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, currTex);
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, prevTex);
        glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, mMvHistoryTexture);

        glBindImageTexture(0, mMotionVectorTexture, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glDispatchCompute((width + 15) / 16, (height + 7) / 8, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
        return;
    }

    // Presets 1 - 4: Full Hierarchical Multi-Pass Optical Flow (Always 1:1 Native Output)
    glUseProgram(mComputeProgramMulti);
    glUniform1i(mLocMulti_quality, quality);

    // Bind textures to their pre-assigned sampler units (set once in compileShaders)
    glActiveTexture(GL_TEXTURE0);  glBindTexture(GL_TEXTURE_2D, currTex);
    glActiveTexture(GL_TEXTURE1);  glBindTexture(GL_TEXTURE_2D, prevTex);
    glActiveTexture(GL_TEXTURE2);  glBindTexture(GL_TEXTURE_2D, mMvHistoryTexture);
    glActiveTexture(GL_TEXTURE3);  glBindTexture(GL_TEXTURE_2D, mDesktopPassTextures[0]);
    glActiveTexture(GL_TEXTURE4);  glBindTexture(GL_TEXTURE_2D, mDesktopPassTextures[1]);
    glActiveTexture(GL_TEXTURE5);  glBindTexture(GL_TEXTURE_2D, mDesktopPassTextures[2]);
    glActiveTexture(GL_TEXTURE6);  glBindTexture(GL_TEXTURE_2D, mDesktopPassTextures[3]);
    glActiveTexture(GL_TEXTURE7);  glBindTexture(GL_TEXTURE_2D, mDesktopPassTextures[4]);
    glActiveTexture(GL_TEXTURE8);  glBindTexture(GL_TEXTURE_2D, mDesktopPassTextures[5]);
    glActiveTexture(GL_TEXTURE9);  glBindTexture(GL_TEXTURE_2D, mDesktopPassTextures[6]);
    glActiveTexture(GL_TEXTURE10); glBindTexture(GL_TEXTURE_2D, mDesktopPassTextures[7]);
    glActiveTexture(GL_TEXTURE11); glBindTexture(GL_TEXTURE_2D, mDesktopPassTextures[8]);
    // Slot 12 (lumaTexL4/[9]) and slot 14 (consistencyTex/[11]) are unused — skip
    glActiveTexture(GL_TEXTURE13); glBindTexture(GL_TEXTURE_2D, mDesktopPassTextures[10]);
    glActiveTexture(GL_TEXTURE15); glBindTexture(GL_TEXTURE_2D, mDesktopPassTextures[12]);

    GLint locPass = mLocMulti_passIndex;

    int wL1 = std::max(1, width / 2);
    int hL1 = std::max(1, height / 2);
    int wL2 = std::max(1, width / 4);
    int hL2 = std::max(1, height / 4);
    int wL3 = std::max(1, width / 8);
    int hL3 = std::max(1, height / 8);
    int wL4 = std::max(1, width / 16);
    int hL4 = std::max(1, height / 16);

    if (quality == QUALITY_PERFORMANCE) {
        // Preset 1: 4 Passes (1:1 Neural Features -> 1:1 Guided Flow -> 7x7 Median -> 1:1 Output)
        glUniform1i(locPass, 1);
        glBindImageTexture(0, mDesktopPassTextures[0], 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glDispatchCompute((width + 15) / 16, (height + 7) / 8, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

        glUniform1i(locPass, 6);
        glBindImageTexture(0, mDesktopPassTextures[5], 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glDispatchCompute((width + 15) / 16, (height + 7) / 8, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

        glUniform1i(locPass, 8);
        glBindImageTexture(0, mDesktopPassTextures[7], 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glDispatchCompute((width + 15) / 16, (height + 7) / 8, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

        glUniform1i(locPass, 9);
        glBindImageTexture(0, mMotionVectorTexture, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glDispatchCompute((width + 15) / 16, (height + 7) / 8, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
    }
    else if (quality == QUALITY_BALANCED) {
        // Preset 2: 7 Passes (1:1 L0 Features -> 1/2 L1 Features -> 1/2 L1 Search -> 1:1 L0 Search -> Parity Check -> 7x7 Filter -> Output)
        glUniform1i(locPass, 1);
        glBindImageTexture(0, mDesktopPassTextures[0], 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glDispatchCompute((width + 15) / 16, (height + 7) / 8, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

        glUniform1i(locPass, 2);
        glBindImageTexture(0, mDesktopPassTextures[1], 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glDispatchCompute((wL1 + 15) / 16, (hL1 + 7) / 8, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

        glUniform1i(locPass, 5);
        glBindImageTexture(0, mDesktopPassTextures[4], 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glDispatchCompute((wL1 + 15) / 16, (hL1 + 7) / 8, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

        glUniform1i(locPass, 6);
        glBindImageTexture(0, mDesktopPassTextures[5], 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glDispatchCompute((width + 15) / 16, (height + 7) / 8, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

        glUniform1i(locPass, 7);
        glBindImageTexture(0, mDesktopPassTextures[6], 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glDispatchCompute((width + 15) / 16, (height + 7) / 8, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

        glUniform1i(locPass, 8);
        glBindImageTexture(0, mDesktopPassTextures[7], 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glDispatchCompute((width + 15) / 16, (height + 7) / 8, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

        glUniform1i(locPass, 9);
        glBindImageTexture(0, mMotionVectorTexture, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glDispatchCompute((width + 15) / 16, (height + 7) / 8, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
    }
    else if (quality == QUALITY_HIGH_QUALITY) {
        // Preset 3: 11 Passes (Full 3-Level Pyramid + 64-Point Spiral + Parity Check + 7x7 Filter + Final Output)
        glUniform1i(locPass, 1);
        glBindImageTexture(0, mDesktopPassTextures[0], 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glDispatchCompute((width + 15) / 16, (height + 7) / 8, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

        glUniform1i(locPass, 2);
        glBindImageTexture(0, mDesktopPassTextures[1], 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glDispatchCompute((wL1 + 15) / 16, (hL1 + 7) / 8, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

        glUniform1i(locPass, 3);
        glBindImageTexture(0, mDesktopPassTextures[2], 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glDispatchCompute((wL2 + 15) / 16, (hL2 + 7) / 8, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

        glUniform1i(locPass, 4);
        glBindImageTexture(0, mDesktopPassTextures[3], 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glDispatchCompute((wL2 + 15) / 16, (hL2 + 7) / 8, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

        glUniform1i(locPass, 5);
        glBindImageTexture(0, mDesktopPassTextures[4], 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glDispatchCompute((wL1 + 15) / 16, (hL1 + 7) / 8, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

        glUniform1i(locPass, 6);
        glBindImageTexture(0, mDesktopPassTextures[5], 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glDispatchCompute((width + 15) / 16, (height + 7) / 8, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

        glUniform1i(locPass, 7);
        glBindImageTexture(0, mDesktopPassTextures[6], 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glDispatchCompute((width + 15) / 16, (height + 7) / 8, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

        glUniform1i(locPass, 8);
        glBindImageTexture(0, mDesktopPassTextures[7], 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glDispatchCompute((width + 15) / 16, (height + 7) / 8, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

        glUniform1i(locPass, 9);
        glBindImageTexture(0, mMotionVectorTexture, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glDispatchCompute((width + 15) / 16, (height + 7) / 8, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
    }
    else if (quality == QUALITY_DESKTOP_QUALITY) {
        // Preset 4: Full 16-Pass Ultra Maximum Genetic Potential Neural-Optical Pipeline
        // Pass 1: L0 1:1 Neural Feature Extraction
        glUniform1i(locPass, 1);
        glBindImageTexture(0, mDesktopPassTextures[0], 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glDispatchCompute((width + 15) / 16, (height + 7) / 8, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

        // Pass 2: L1 Half Neural Features
        glUniform1i(locPass, 2);
        glBindImageTexture(0, mDesktopPassTextures[1], 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glDispatchCompute((wL1 + 15) / 16, (hL1 + 7) / 8, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

        // Pass 3: L2 Quarter Neural Features
        glUniform1i(locPass, 3);
        glBindImageTexture(0, mDesktopPassTextures[2], 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glDispatchCompute((wL2 + 15) / 16, (hL2 + 7) / 8, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

        // Pass 4: L3 1/8x Ultra-Coarse Features (1024px Reach)
        glUniform1i(locPass, 10); // passIndex=10: L3 downsample from L2 (was erroneously 3)
        glBindImageTexture(0, mDesktopPassTextures[8], 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glDispatchCompute((wL3 + 15) / 16, (hL3 + 7) / 8, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

        // Pass 5: L3 Deep Coarse 64-Point Golden Spiral Search
        glUniform1i(locPass, 11); // passIndex=11: L3 coarse search (was duplicate 4)
        glBindImageTexture(0, mDesktopPassTextures[10], 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glDispatchCompute((wL3 + 15) / 16, (hL3 + 7) / 8, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

        // Pass 6: L2 Coarse 64-Point Guided Search
        glUniform1i(locPass, 4);
        glBindImageTexture(0, mDesktopPassTextures[3], 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glDispatchCompute((wL2 + 15) / 16, (hL2 + 7) / 8, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

        // Pass 7: L1 Mid-Scale 64-Point Guided Tensor Search
        glUniform1i(locPass, 5);
        glBindImageTexture(0, mDesktopPassTextures[4], 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glDispatchCompute((wL1 + 15) / 16, (hL1 + 7) / 8, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

        // Pass 8: L0 Native 1:1 Fine 64-Point Subpixel Forward Matching
        glUniform1i(locPass, 6);
        glBindImageTexture(0, mDesktopPassTextures[5], 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glDispatchCompute((width + 15) / 16, (height + 7) / 8, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

        // Pass 9: L0 Reverse Backward Flow & Consistency Parity Check (T1 -> T0)
        glUniform1i(locPass, 7);
        glBindImageTexture(0, mDesktopPassTextures[6], 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glDispatchCompute((width + 15) / 16, (height + 7) / 8, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

        // Pass 10: L0 49-Sample (7x7) Spatial-Temporal Bilateral Median Tensor
        glUniform1i(locPass, 8);
        glBindImageTexture(0, mDesktopPassTextures[7], 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glDispatchCompute((width + 15) / 16, (height + 7) / 8, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

        // Pass 11: L0 Vector Field Dilation & Inpainting Buffer
        glUniform1i(locPass, 8);
        glBindImageTexture(0, mDesktopPassTextures[12], 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glDispatchCompute((width + 15) / 16, (height + 7) / 8, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

        // Pass 12: L0 Final Temporal Momentum & Reprojection Output
        glUniform1i(locPass, 9);
        glBindImageTexture(0, mMotionVectorTexture, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glDispatchCompute((width + 15) / 16, (height + 7) / 8, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
    }
}

void ApexEngine::runWarpingPass(GLuint currTex, GLuint prevTex, GLuint mvTex, GLuint outputFboId, float factor, int width, int height) {
    glBindFramebuffer(GL_FRAMEBUFFER, outputFboId);
    glViewport(0, 0, width, height);

    glUseProgram(mWarpingProgram);
    // Sampler unit bindings set once in compileShaders(); just rebind textures
    glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, currTex);
    glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, prevTex);
    glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, mvTex);

    glUniform2f(mLocWarp_resolution, static_cast<float>(width), static_cast<float>(height));
    glUniform1f(mLocWarp_interpolFactor, factor);
    glUniform1f(mLocWarp_qualityMode, static_cast<float>(mQualityPreset.load(std::memory_order_relaxed)));
    glUniform1f(mLocWarp_blurIntensity, mShutterGain.load(std::memory_order_relaxed));
    glUniform1f(mLocWarp_flowScale, mFlowScale.load(std::memory_order_relaxed));
    glUniform1i(mLocWarp_debugOverlay, mDebugOverlay.load(std::memory_order_relaxed) ? 1 : 0);

    glBindVertexArray(mQuadVao);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);

    glBindTexture(GL_TEXTURE_2D, 0);
}

void ApexEngine::logHeartbeatIfDue(int64_t nowNanos) {
    if (mLastHeartbeatTimeNanos == 0) {
        mLastHeartbeatTimeNanos = nowNanos;
        return;
    }

    int64_t elapsed = nowNanos - mLastHeartbeatTimeNanos;
    if (elapsed >= 2000000000LL) { // 2.0s Heartbeat
        float deltaSec = static_cast<float>(elapsed) / 1000000000.0f;
        float realFps = static_cast<float>(mHeartbeatRealFrames) / deltaSec;
        float genFps = static_cast<float>(mHeartbeatGenFrames) / deltaSec;
        float totalFps = realFps + genFps;
        float typicalMs = mTypicalDeltaNanos / 1000000.0f;
        float liveMult = (realFps > 0.1f) ? (totalFps / realFps) : 1.0f;

        // Statistical analysis across 8-sample frametime history
        float sumDelta = 0.0f;
        float minDelta = 1e9f;
        float maxDelta = 0.0f;
        for (float d : mDeltaHistory) {
            float ms = d / 1000000.0f;
            sumDelta += ms;
            minDelta = std::min(minDelta, ms);
            maxDelta = std::max(maxDelta, ms);
        }
        float meanDelta = sumDelta / mDeltaHistory.size();
        float varianceSum = 0.0f;
        for (float d : mDeltaHistory) {
            float diff = (d / 1000000.0f) - meanDelta;
            varianceSum += diff * diff;
        }
        float jitterMs = std::sqrt(varianceSum / mDeltaHistory.size());

        // Sparkline generation for 8-frame delivery history
        char sparkline[32];
        int sparkIdx = 0;
        sparkline[sparkIdx++] = '[';
        for (size_t i = 0; i < mDeltaHistory.size(); i++) {
            float val = mDeltaHistory[i] / 1000000.0f;
            if (val <= meanDelta * 0.90f)      sparkline[sparkIdx++] = '_';
            else if (val <= meanDelta * 1.10f) sparkline[sparkIdx++] = '-';
            else if (val <= meanDelta * 1.40f) sparkline[sparkIdx++] = '=';
            else                               sparkline[sparkIdx++] = '^';
        }
        sparkline[sparkIdx++] = ']';
        sparkline[sparkIdx] = '\0';

        const char* statusStr = (jitterMs < 2.5f) ? "OPTIMAL GLIDE (0 Jitter)" : 
                                (jitterMs < 6.0f) ? "STABLE FLOW" : 
                                (jitterMs < 14.0f ? "MODERATE VARIANCE" : "GAME HITCHING");

        APEX_LOGI("=========================== [APEX FRAMEGEN TELEMETRY] ===========================");
        APEX_LOGI(" ◈ PERF   :: Output: %5.1f FPS (Real: %4.1f | Gen: %4.1f) | Multiplier: %4.2fx | Preset: %s",
                  totalFps, realFps, genFps, liveMult, getQualityPresetName(mQualityPreset.load()));
        APEX_LOGI(" ◈ PACING :: Status: %s | Cadence: %5.2f ms (Target: %d FPS) | Jitter: ±%.2f ms",
                  statusStr, typicalMs, mTargetFPS.load(), jitterMs);
        APEX_LOGI(" ◈ MOTION :: Phase: MONOTONIC (Factor: %.3f) | Reach: 256px Multi-Scale | Disocclusion: ACTIVE",
                  mLastFactor);
        APEX_LOGI(" ◈ DELTAS :: History: %s min=%.1fms, avg=%.1fms, max=%.1fms | Range: [%.1f, %.1f, %.1f, %.1f, %.1f, %.1f, %.1f, %.1f] ms",
                  sparkline, minDelta, meanDelta, maxDelta,
                  mDeltaHistory[0] / 1000000.0f, mDeltaHistory[1] / 1000000.0f,
                  mDeltaHistory[2] / 1000000.0f, mDeltaHistory[3] / 1000000.0f,
                  mDeltaHistory[4] / 1000000.0f, mDeltaHistory[5] / 1000000.0f,
                  mDeltaHistory[6] / 1000000.0f, mDeltaHistory[7] / 1000000.0f);
        APEX_LOGI("================================================================================");

        mHeartbeatRealFrames = 0;
        mHeartbeatGenFrames = 0;
        mLastHeartbeatTimeNanos = nowNanos;
        mMinFactor = 1.0f;
        mMaxFactor = 0.0f;
    }
}

void ApexEngine::processFrame(GLuint inputTextureId, GLuint outputFboId, int width, int height) {
    if (!mActive.load(std::memory_order_relaxed)) {
        return;
    }

    if (!mInitialized || mSurfaceWidth != width || mSurfaceHeight != height) {
        init(width, height);
    }

    int64_t nowNanos = std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();

    // Consume pending real frame flag set when DXVK/Wine updates window content
    bool hasPendingReal = mPendingRealFrame.exchange(false, std::memory_order_acq_rel);
    bool isReal = hasPendingReal || (mRealFramesCaptured.load(std::memory_order_relaxed) < 2);

    if (isReal) {
        // REAL GAME FRAME: Ingest to history, update optical flow, display real frame (factor = 1.0)
        mHeartbeatRealFrames++;
        mActualRealFrameCount.fetch_add(1, std::memory_order_relaxed);

        int64_t lastRealTime = mLastRealFrameTimeNanos.load(std::memory_order_acquire);
        std::swap(mPreviousCapturedTexture, mCurrentCapturedTexture);

        glBindFramebuffer(GL_FRAMEBUFFER, mCaptureFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, mCurrentCapturedTexture, 0);
        glViewport(0, 0, width, height);

        // Blit incoming game frame into current capture texture
        runWarpingPass(inputTextureId, inputTextureId, mMotionVectorTexture, mCaptureFbo, 1.0f, width, height);

        if (lastRealTime > 0) {
            float delta = static_cast<float>(nowNanos - lastRealTime);
            if (delta > 1000000.0f && delta < 300000000.0f) {
                mDeltaHistory[mHistoryIndex] = delta;
                mHistoryIndex = (mHistoryIndex + 1) % mDeltaHistory.size();

                std::copy(mDeltaHistory.begin(), mDeltaHistory.end(), mSortedHistory.begin());
                std::sort(mSortedHistory.begin(), mSortedHistory.end());
                float medianDelta = mSortedHistory[mSortedHistory.size() / 2];

                mTypicalDeltaNanos = mTypicalDeltaNanos * 0.40f + medianDelta * 0.60f;

                // Update adaptive frame multiplier (Bug 5 fix: onFrameCaptured() was dead code)
                int target = mTargetFPS.load(std::memory_order_acquire);
                if (mTypicalDeltaNanos >= 66666666.0f) {
                    // Sub-15 FPS: hard-lock to 2x
                    mAutoMultiplier.store(2, std::memory_order_release);
                    mAutoMultiplierVal.store(2.0f, std::memory_order_release);
                } else if (mTypicalDeltaNanos >= 33333333.0f) {
                    // 15-30 FPS: 2x default, 3x if targeting 90+ FPS
                    int mult = (target >= 90) ? 3 : 2;
                    mAutoMultiplier.store(mult, std::memory_order_release);
                    mAutoMultiplierVal.store(static_cast<float>(mult), std::memory_order_release);
                } else if (target > 0) {
                    // >30 FPS with a target: compute ratio
                    float targetInterval = 1000000000.0f / static_cast<float>(target);
                    if (mTypicalDeltaNanos <= targetInterval * 1.05f) {
                        mAutoMultiplier.store(1, std::memory_order_release);
                        mAutoMultiplierVal.store(mTypicalDeltaNanos / targetInterval, std::memory_order_release);
                    } else {
                        float val = mTypicalDeltaNanos / targetInterval;
                        mAutoMultiplierVal.store(val, std::memory_order_release);
                        mAutoMultiplier.store(std::clamp(static_cast<int>(std::ceil(val)), 2, 4), std::memory_order_release);
                    }
                } else {
                    // Unlimited: dynamic high-refresh
                    int mult = (mTypicalDeltaNanos > 25000000.0f) ? 3 : 2;
                    mAutoMultiplier.store(mult, std::memory_order_release);
                    mAutoMultiplierVal.store(static_cast<float>(mult), std::memory_order_release);
                }
            }
        }
        mLastRealFrameTimeNanos.store(nowNanos, std::memory_order_release);

        // Run optical flow compute shaders between current and previous frame
        runComputePipeline(mCurrentCapturedTexture, mPreviousCapturedTexture, width, height);

        int realCount = mRealFramesCaptured.fetch_add(1, std::memory_order_relaxed);
        mFramesSinceReal.store(0, std::memory_order_release);
        mRenderingGeneratedFrame.store(false, std::memory_order_release);

        // Present smooth interpolated intermediate frame first (t = 0.5);
        // on subsequent off-VSYNC pulse, the real frame (t = 1.0) is presented.
        float factor = (realCount < 2) ? 1.0f : 0.5f;
        runWarpingPass(mCurrentCapturedTexture, mPreviousCapturedTexture, mMotionVectorTexture, outputFboId, factor, width, height);
    } else {
        // GENERATED / OFF-VSYNC CADENCE: Present real frame (t = 1.0) or progressive multiplier steps
        mHeartbeatGenFrames++;
        mGeneratedFrameCount.fetch_add(1, std::memory_order_relaxed);
        int framesSince = mFramesSinceReal.fetch_add(1, std::memory_order_relaxed);

        int mult = std::max(2, mAutoMultiplier.load(std::memory_order_acquire));
        float factor = (framesSince + 1 >= mult - 1) ? 1.0f : (static_cast<float>(framesSince + 2) / static_cast<float>(mult));
        runWarpingPass(mCurrentCapturedTexture, mPreviousCapturedTexture, mMotionVectorTexture, outputFboId, factor, width, height);
        mRenderingGeneratedFrame.store(true, std::memory_order_release);
    }

    logHeartbeatIfDue(nowNanos);
}

} // namespace apex
