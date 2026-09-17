#include "apex_engine.h"
#include "apex_shaders.h"
#include <vector>
#include <string>
#include <chrono>
#include <algorithm>

namespace apex {

ApexEngine& ApexEngine::getInstance() {
    static ApexEngine instance;
    return instance;
}

ApexEngine::ApexEngine() {
    mDeltaHistory.fill(16666667.0f);
    mSortedHistory.fill(16666667.0f);
}

ApexEngine::~ApexEngine() {
    destroy();
}

static const char* getGlErrorString(GLenum err) {
    switch (err) {
        case GL_NO_ERROR: return "GL_NO_ERROR";
        case GL_INVALID_ENUM: return "GL_INVALID_ENUM";
        case GL_INVALID_VALUE: return "GL_INVALID_VALUE";
        case GL_INVALID_OPERATION: return "GL_INVALID_OPERATION";
        case GL_OUT_OF_MEMORY: return "GL_OUT_OF_MEMORY";
        case GL_INVALID_FRAMEBUFFER_OPERATION: return "GL_INVALID_FRAMEBUFFER_OPERATION";
        default: return "GL_UNKNOWN_ERROR";
    }
}

static GLuint compileComputeProgram(const char* name, const char* src, std::string& errOut) {
    while (glGetError() != GL_NO_ERROR); // drain prior errors
    GLuint s = glCreateShader(GL_COMPUTE_SHADER);
    if (!s) {
        errOut = std::string(name) + ": glCreateShader returned 0";
        APEX_LOGE("%s", errOut.c_str());
        return 0;
    }
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint status = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &status);
    if (!status) {
        char log[1024];
        glGetShaderInfoLog(s, sizeof(log), nullptr, log);
        errOut = std::string(name) + " compile failed: " + log;
        APEX_LOGE("%s", errOut.c_str());
        glDeleteShader(s);
        return 0;
    }
    GLuint p = glCreateProgram();
    if (!p) {
        errOut = std::string(name) + ": glCreateProgram returned 0";
        APEX_LOGE("%s", errOut.c_str());
        glDeleteShader(s);
        return 0;
    }
    glAttachShader(p, s);
    glLinkProgram(p);
    glGetProgramiv(p, GL_LINK_STATUS, &status);
    if (!status) {
        char log[1024];
        glGetProgramInfoLog(p, sizeof(log), nullptr, log);
        errOut = std::string(name) + " link failed: " + log;
        APEX_LOGE("%s", errOut.c_str());
        glDeleteProgram(p);
        glDeleteShader(s);
        return 0;
    }
    glDeleteShader(s);
    return p;
}

static GLuint compileGraphicsProgram(const char* name, const char* vs_src, const char* fs_src, std::string& errOut) {
    while (glGetError() != GL_NO_ERROR);
    GLuint vs = glCreateShader(GL_VERTEX_SHADER);
    glShaderSource(vs, 1, &vs_src, nullptr);
    glCompileShader(vs);
    GLint status = 0;
    glGetShaderiv(vs, GL_COMPILE_STATUS, &status);
    if (!status) {
        char log[1024];
        glGetShaderInfoLog(vs, sizeof(log), nullptr, log);
        errOut = std::string(name) + " VS compile failed: " + log;
        APEX_LOGE("%s", errOut.c_str());
        glDeleteShader(vs);
        return 0;
    }

    GLuint fs = glCreateShader(GL_FRAGMENT_SHADER);
    glShaderSource(fs, 1, &fs_src, nullptr);
    glCompileShader(fs);
    glGetShaderiv(fs, GL_COMPILE_STATUS, &status);
    if (!status) {
        char log[1024];
        glGetShaderInfoLog(fs, sizeof(log), nullptr, log);
        errOut = std::string(name) + " FS compile failed: " + log;
        APEX_LOGE("%s", errOut.c_str());
        glDeleteShader(vs);
        glDeleteShader(fs);
        return 0;
    }

    GLuint p = glCreateProgram();
    glAttachShader(p, vs);
    glAttachShader(p, fs);
    glLinkProgram(p);
    glGetProgramiv(p, GL_LINK_STATUS, &status);
    if (!status) {
        char log[1024];
        glGetProgramInfoLog(p, sizeof(log), nullptr, log);
        errOut = std::string(name) + " link failed: " + log;
        APEX_LOGE("%s", errOut.c_str());
        glDeleteProgram(p);
        glDeleteShader(vs);
        glDeleteShader(fs);
        return 0;
    }
    glDeleteShader(vs);
    glDeleteShader(fs);
    return p;
}

void ApexEngine::compileShaders() {
    if (mProgLumaGrad && mProgInverseSearch && mProgPropagate && mProgDensify &&
        mProgVrSetup && mProgVrSor && mProgInterpolate && mProgRcas && mQuadProg) {
        return;
    }

    mShaderErrorDetails.clear();
    mCompiledShaderCount = 0;

    auto compileOne = [this](const char* name, GLuint& prog, const char* src) {
        if (!prog) {
            std::string err;
            prog = compileComputeProgram(name, src, err);
            if (!prog) {
                if (!mShaderErrorDetails.empty()) mShaderErrorDetails += "; ";
                mShaderErrorDetails += err;
            }
        }
        if (prog) mCompiledShaderCount++;
    };

    compileOne("DisLumaGrad", mProgLumaGrad, kShaderDisLumaGrad);
    compileOne("DisInverseSearch", mProgInverseSearch, kShaderDisInverseSearch);
    compileOne("DisPropagate", mProgPropagate, kShaderDisPropagate);
    compileOne("DisDensify", mProgDensify, kShaderDisDensify);
    compileOne("DisVrSetup", mProgVrSetup, kShaderDisVrSetup);
    compileOne("DisVrSor", mProgVrSor, kShaderDisVrSor);
    compileOne("DisInterpolate", mProgInterpolate, kShaderDisInterpolate);
    compileOne("DisRcas", mProgRcas, kShaderDisRcas);

    static const char* kQuadVS = R"(#version 300 es
    precision highp float;
    uniform vec4 uTexBounds;
    out vec2 vUV;
    void main() {
        // Fullscreen quad generated purely via gl_VertexID (0:(-1,-1), 1:(1,-1), 2:(-1,1), 3:(1,1))
        vec2 pos = vec2(
            (gl_VertexID == 1 || gl_VertexID == 3) ? 1.0 : -1.0,
            (gl_VertexID >= 2) ? 1.0 : -1.0
        );
        vec2 baseUV = pos * 0.5 + 0.5;
        vUV = uTexBounds.xy + baseUV * uTexBounds.zw;
        gl_Position = vec4(pos, 0.0, 1.0);
    }
    )";
    static const char* kQuadFS = R"(#version 300 es
    precision highp float;
    in vec2 vUV;
    uniform sampler2D uTex;
    out vec4 fragColor;
    void main() { fragColor = texture(uTex, vUV); }
    )";
    if (!mQuadProg) {
        std::string qErr;
        mQuadProg = compileGraphicsProgram("QuadBlit", kQuadVS, kQuadFS, qErr);
        if (!mQuadProg) {
            if (!mShaderErrorDetails.empty()) mShaderErrorDetails += "; ";
            mShaderErrorDetails += qErr;
        } else {
            glUseProgram(mQuadProg);
            GLint uTexLoc = glGetUniformLocation(mQuadProg, "uTex");
            if (uTexLoc >= 0) glUniform1i(uTexLoc, 0);
            glUseProgram(0);
        }
    }

    if (!mQuadVao && mQuadProg) {
        glGenVertexArrays(1, &mQuadVao);
    }

    mShaderCompileSuccess = (mCompiledShaderCount == 8 && mQuadProg != 0);
    if (!mShaderCompileSuccess) {
        APEX_LOGE("ApexDIS Shader verification FAILED (%d/8 compiled). Details: %s",
                  mCompiledShaderCount, mShaderErrorDetails.c_str());
    } else {
        APEX_LOGI("ApexDIS Shader verification: SUCCESS (8/8 compute shaders + blit quad OK)");
    }
}

static GLuint createStorageTexture(int w, int h, GLint internalFormat, GLenum filter, const char* name, std::string& errOut) {
    while (glGetError() != GL_NO_ERROR); // drain prior errors
    GLuint t = 0;
    glGenTextures(1, &t);
    if (!t) {
        errOut = std::string(name) + ": glGenTextures failed";
        APEX_LOGE("%s", errOut.c_str());
        return 0;
    }
    glBindTexture(GL_TEXTURE_2D, t);
    glTexStorage2D(GL_TEXTURE_2D, 1, internalFormat, w, h);
    GLenum err = glGetError();
    if (err != GL_NO_ERROR) {
        errOut = std::string(name) + " (" + std::to_string(w) + "x" + std::to_string(h) + ") glTexStorage2D failed: " + getGlErrorString(err);
        APEX_LOGE("%s", errOut.c_str());
        glDeleteTextures(1, &t);
        return 0;
    }
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    return t;
}

void ApexEngine::init(int w, int h) { ensureResources(w, h); }
void ApexEngine::updateDimensions(int w, int h) { ensureResources(w, h); }

void ApexEngine::ensureResources(int width, int height) {
    if (width <= 0 || height <= 0) {
        mResourceAllocSuccess = false;
        mResourceErrorDetails = "Invalid dimensions: " + std::to_string(width) + "x" + std::to_string(height);
        APEX_LOGE("%s", mResourceErrorDetails.c_str());
        return;
    }

    float renderScale = std::clamp(mRenderScale.load(), 0.25f, 1.0f);
    int sw = std::max(64, (int)(width * renderScale + 0.5f));
    int sh = std::max(64, (int)(height * renderScale + 0.5f));
    sw = (sw + 1) & ~1;
    sh = (sh + 1) & ~1;

    uint32_t minSide = 180;
    int preset = mQualityPreset.load();
    if (preset == 1) minSide = 252;
    else if (preset == 2) minSide = 360;

    uint32_t minor = width < height ? width : height;
    float k = (float)minSide / (float)(minor > 0 ? minor : 1);
    int fw = std::max(64, (int)(width * k + 0.5f));
    int fh = std::max(64, (int)(height * k + 0.5f));

    if (mInitialized && width == mSurfaceWidth && height == mSurfaceHeight &&
        sw == mScaledWidth && sh == mScaledHeight && fw == mFlowWidth && fh == mFlowHeight) {
        return;
    }

    cleanupResources();
    mSurfaceWidth = width;
    mSurfaceHeight = height;
    mScaledWidth = sw;
    mScaledHeight = sh;
    mFlowWidth = fw;
    mFlowHeight = fh;
    mResourceAllocSuccess = true;
    mResourceErrorDetails.clear();

    compileShaders();
    if (!mShaderCompileSuccess) {
        mInitialized = false;
        return;
    }

    std::string err;
    for (uint32_t i = 0; i < DIS_SLOTS; i++) {
        mColorRingTex[i] = createStorageTexture(sw, sh, GL_RGBA8, GL_LINEAR, "ColorRingTex", err);
        if (!mColorRingTex[i]) { mResourceAllocSuccess = false; mResourceErrorDetails += err + "; "; }

        mFlowColorTex[i] = createStorageTexture(fw, fh, GL_RGBA8, GL_LINEAR, "FlowColorTex", err);
        if (!mFlowColorTex[i]) { mResourceAllocSuccess = false; mResourceErrorDetails += err + "; "; }
    }
    mNativeWarpTex = createStorageTexture(sw, sh, GL_RGBA8, GL_LINEAR, "NativeWarpTex", err);
    if (!mNativeWarpTex) { mResourceAllocSuccess = false; mResourceErrorDetails += err + "; "; }

    mInterpOutTex = createStorageTexture(sw, sh, GL_RGBA8, GL_LINEAR, "InterpOutTex", err);
    if (!mInterpOutTex) { mResourceAllocSuccess = false; mResourceErrorDetails += err + "; "; }

    glGenFramebuffers(1, &mCaptureFbo);
    if (!mCaptureFbo) {
        mResourceAllocSuccess = false;
        mResourceErrorDetails += "glGenFramebuffers failed; ";
    }

    for (uint32_t i = 0; i < MAX_PYR_LEVELS; i++) {
        int lw = fw >> i, lh = fh >> i;
        if (lw < 1) lw = 1;
        if (lh < 1) lh = 1;
        mLevels[i].width = lw;
        mLevels[i].height = lh;
        mLevels[i].sparseWidth = lw > 8 ? 1 + (lw - 8) / 3 : 1;
        mLevels[i].sparseHeight = lh > 8 ? 1 + (lh - 8) / 3 : 1;

        for (uint32_t s = 0; s < DIS_SLOTS; s++) {
            mLevels[i].lumaTex[s] = createStorageTexture(lw, lh, GL_R32F, GL_LINEAR, "LumaTex", err);
            if (!mLevels[i].lumaTex[s]) { mResourceAllocSuccess = false; mResourceErrorDetails += err + "; "; }
        }
        mLevels[i].gradientTex = createStorageTexture(lw, lh, GL_RGBA16F, GL_NEAREST, "GradientTex", err);
        if (!mLevels[i].gradientTex) { mResourceAllocSuccess = false; mResourceErrorDetails += err + "; "; }

        mLevels[i].sparseFlowTex[0] = createStorageTexture(mLevels[i].sparseWidth, mLevels[i].sparseHeight, GL_RGBA16F, GL_LINEAR, "SparseFlow0", err);
        if (!mLevels[i].sparseFlowTex[0]) { mResourceAllocSuccess = false; mResourceErrorDetails += err + "; "; }

        mLevels[i].sparseFlowTex[1] = createStorageTexture(mLevels[i].sparseWidth, mLevels[i].sparseHeight, GL_RGBA16F, GL_LINEAR, "SparseFlow1", err);
        if (!mLevels[i].sparseFlowTex[1]) { mResourceAllocSuccess = false; mResourceErrorDetails += err + "; "; }

        mLevels[i].denseFlowTex = createStorageTexture(lw, lh, GL_RGBA16F, GL_LINEAR, "DenseFlowTex", err);
        if (!mLevels[i].denseFlowTex) { mResourceAllocSuccess = false; mResourceErrorDetails += err + "; "; }

        mLevels[i].vrATex = createStorageTexture(lw, lh, GL_RGBA16F, GL_NEAREST, "vrATex", err);
        if (!mLevels[i].vrATex) { mResourceAllocSuccess = false; mResourceErrorDetails += err + "; "; }

        mLevels[i].vrBTex = createStorageTexture(lw, lh, GL_RGBA16F, GL_NEAREST, "vrBTex", err);
        if (!mLevels[i].vrBTex) { mResourceAllocSuccess = false; mResourceErrorDetails += err + "; "; }

        mLevels[i].vrDWTex[0] = createStorageTexture(lw, lh, GL_RGBA16F, GL_LINEAR, "vrDWTex0", err);
        if (!mLevels[i].vrDWTex[0]) { mResourceAllocSuccess = false; mResourceErrorDetails += err + "; "; }

        mLevels[i].vrDWTex[1] = createStorageTexture(lw, lh, GL_RGBA16F, GL_LINEAR, "vrDWTex1", err);
        if (!mLevels[i].vrDWTex[1]) { mResourceAllocSuccess = false; mResourceErrorDetails += err + "; "; }
    }

    if (mCaptureFbo && mColorRingTex[0]) {
        glBindFramebuffer(GL_FRAMEBUFFER, mCaptureFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, mColorRingTex[0], 0);
        GLenum fboStatus = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        if (fboStatus != GL_FRAMEBUFFER_COMPLETE) {
            mFboComplete = false;
            mResourceAllocSuccess = false;
            mResourceErrorDetails += "Capture FBO incomplete: 0x" + std::to_string(fboStatus) + "; ";
            APEX_LOGE("ApexDIS Capture FBO incomplete: 0x%x", fboStatus);
        } else {
            mFboComplete = true;
        }
    } else {
        mFboComplete = false;
    }

    if (mResourceAllocSuccess && mShaderCompileSuccess && mFboComplete) {
        mInitialized = true;
        APEX_LOGI("ApexEngine successfully initialized: Native %dx%d, Flow %dx%d (%d levels, 20 passes)",
                  width, height, fw, fh, MAX_PYR_LEVELS);
    } else {
        mInitialized = false;
        APEX_LOGE("ApexEngine initialization FAILED! Resources: %s, Shaders: %s, FBO: %s. Details: %s",
                  mResourceAllocSuccess ? "OK" : "FAIL",
                  mShaderCompileSuccess ? "OK" : "FAIL",
                  mFboComplete ? "OK" : "FAIL",
                  mResourceErrorDetails.c_str());
    }
}

void ApexEngine::cleanupResources() {
    if (!mInitialized) return;
    for (uint32_t i = 0; i < DIS_SLOTS; i++) {
        if (mColorRingTex[i]) { glDeleteTextures(1, &mColorRingTex[i]); mColorRingTex[i] = 0; }
        if (mFlowColorTex[i]) { glDeleteTextures(1, &mFlowColorTex[i]); mFlowColorTex[i] = 0; }
    }
    if (mNativeWarpTex) { glDeleteTextures(1, &mNativeWarpTex); mNativeWarpTex = 0; }
    if (mInterpOutTex) { glDeleteTextures(1, &mInterpOutTex); mInterpOutTex = 0; }
    if (mCaptureFbo) { glDeleteFramebuffers(1, &mCaptureFbo); mCaptureFbo = 0; }

    for (uint32_t i = 0; i < MAX_PYR_LEVELS; i++) {
        for (uint32_t s = 0; s < DIS_SLOTS; s++) {
            if (mLevels[i].lumaTex[s]) { glDeleteTextures(1, &mLevels[i].lumaTex[s]); mLevels[i].lumaTex[s] = 0; }
        }
        if (mLevels[i].gradientTex) { glDeleteTextures(1, &mLevels[i].gradientTex); mLevels[i].gradientTex = 0; }
        if (mLevels[i].sparseFlowTex[0]) { glDeleteTextures(1, &mLevels[i].sparseFlowTex[0]); mLevels[i].sparseFlowTex[0] = 0; }
        if (mLevels[i].sparseFlowTex[1]) { glDeleteTextures(1, &mLevels[i].sparseFlowTex[1]); mLevels[i].sparseFlowTex[1] = 0; }
        if (mLevels[i].denseFlowTex) { glDeleteTextures(1, &mLevels[i].denseFlowTex); mLevels[i].denseFlowTex = 0; }
        if (mLevels[i].vrATex) { glDeleteTextures(1, &mLevels[i].vrATex); mLevels[i].vrATex = 0; }
        if (mLevels[i].vrBTex) { glDeleteTextures(1, &mLevels[i].vrBTex); mLevels[i].vrBTex = 0; }
        if (mLevels[i].vrDWTex[0]) { glDeleteTextures(1, &mLevels[i].vrDWTex[0]); mLevels[i].vrDWTex[0] = 0; }
        if (mLevels[i].vrDWTex[1]) { glDeleteTextures(1, &mLevels[i].vrDWTex[1]); mLevels[i].vrDWTex[1] = 0; }
    }
    mInitialized = false;
}

void ApexEngine::destroy() {
    cleanupResources();
    if (mProgLumaGrad) { glDeleteProgram(mProgLumaGrad); mProgLumaGrad = 0; }
    if (mProgInverseSearch) { glDeleteProgram(mProgInverseSearch); mProgInverseSearch = 0; }
    if (mProgPropagate) { glDeleteProgram(mProgPropagate); mProgPropagate = 0; }
    if (mProgDensify) { glDeleteProgram(mProgDensify); mProgDensify = 0; }
    if (mProgVrSetup) { glDeleteProgram(mProgVrSetup); mProgVrSetup = 0; }
    if (mProgVrSor) { glDeleteProgram(mProgVrSor); mProgVrSor = 0; }
    if (mProgInterpolate) { glDeleteProgram(mProgInterpolate); mProgInterpolate = 0; }
    if (mProgRcas) { glDeleteProgram(mProgRcas); mProgRcas = 0; }
    if (mQuadProg) { glDeleteProgram(mQuadProg); mQuadProg = 0; }
    if (mQuadVao) { glDeleteVertexArrays(1, &mQuadVao); mQuadVao = 0; }
    if (mQuadVbo) { glDeleteBuffers(1, &mQuadVbo); mQuadVbo = 0; }
    mCompiledShaderCount = 0;
    mShaderCompileSuccess = false;
    mResourceAllocSuccess = false;
    mFboComplete = false;
}

void ApexEngine::blitQuad(GLuint tex, float uMin, float vMin, float uScale, float vScale) {
    if (!mQuadProg || tex == 0) return;

    GLboolean depthTest = glIsEnabled(GL_DEPTH_TEST);
    GLboolean cullFace  = glIsEnabled(GL_CULL_FACE);
    GLboolean scissor   = glIsEnabled(GL_SCISSOR_TEST);
    GLboolean blend     = glIsEnabled(GL_BLEND);
    GLboolean stencil   = glIsEnabled(GL_STENCIL_TEST);

    if (depthTest) glDisable(GL_DEPTH_TEST);
    if (cullFace)  glDisable(GL_CULL_FACE);
    if (scissor)   glDisable(GL_SCISSOR_TEST);
    if (blend)     glDisable(GL_BLEND);
    if (stencil)   glDisable(GL_STENCIL_TEST);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);

    glUseProgram(mQuadProg);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, tex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    GLint uTexLoc = glGetUniformLocation(mQuadProg, "uTex");
    if (uTexLoc >= 0) glUniform1i(uTexLoc, 0);

    GLint uBoundsLoc = glGetUniformLocation(mQuadProg, "uTexBounds");
    if (uBoundsLoc >= 0) glUniform4f(uBoundsLoc, uMin, vMin, uScale, vScale);

    if (mQuadVao) glBindVertexArray(mQuadVao);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    if (mQuadVao) glBindVertexArray(0);

    if (depthTest) glEnable(GL_DEPTH_TEST);
    if (cullFace)  glEnable(GL_CULL_FACE);
    if (scissor)   glEnable(GL_SCISSOR_TEST);
    if (blend)     glEnable(GL_BLEND);
    if (stencil)   glEnable(GL_STENCIL_TEST);
}

void ApexEngine::dispatchLumaGrad(int level, GLuint inTex, uint32_t slot) {
    glUseProgram(mProgLumaGrad);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, inTex);
    glBindImageTexture(1, mLevels[level].lumaTex[slot], 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_R32F);
    glBindImageTexture(2, mLevels[level].gradientTex, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
    glDispatchCompute((mLevels[level].width + 15) / 16, (mLevels[level].height + 15) / 16, 1);
    glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
}

void ApexEngine::dispatchHierarchicalSearch(int level, GLuint lastLuma, GLuint nextLuma, GLuint lastGrad,
                                            GLuint coarseFlow, GLuint outSparse, int sw, int sh, int coarseLevel) {
    glUseProgram(mProgInverseSearch);
    glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, lastLuma);
    glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, nextLuma);
    glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, lastGrad);
    glActiveTexture(GL_TEXTURE3); glBindTexture(GL_TEXTURE_2D, coarseFlow ? coarseFlow : lastLuma);
    glBindImageTexture(4, outSparse, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
    glUniform1i(glGetUniformLocation(mProgInverseSearch, "u_level"), level);
    glUniform1i(glGetUniformLocation(mProgInverseSearch, "u_coarseLevel"), coarseLevel);
    glDispatchCompute((sw + 7) / 8, (sh + 7) / 8, 1);
    glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
}

void ApexEngine::dispatchPropagate(int level, GLuint lastLuma, GLuint nextLuma, GLuint fi, GLuint fo, int sw, int sh, int dist) {
    (void)level;
    glUseProgram(mProgPropagate);
    glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, lastLuma);
    glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, nextLuma);
    glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, fi);
    glBindImageTexture(3, fo, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
    glUniform1i(glGetUniformLocation(mProgPropagate, "u_dist"), dist);
    glDispatchCompute((sw + 7) / 8, (sh + 7) / 8, 1);
    glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
}

void ApexEngine::dispatchDensify(GLuint sparseFlow, GLuint lastLuma, GLuint nextLuma, GLuint denseFlow, int w, int h) {
    glUseProgram(mProgDensify);
    glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, sparseFlow);
    glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, lastLuma);
    glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, nextLuma);
    glBindImageTexture(3, denseFlow, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
    glDispatchCompute((w + 7) / 8, (h + 7) / 8, 1);
    glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
}

void ApexEngine::dispatchVrSetup(GLuint denseFlow, GLuint prevColor, GLuint nextColor, GLuint outA, GLuint outB, GLuint outDW, int w, int h) {
    glUseProgram(mProgVrSetup);
    glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, denseFlow);
    glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, prevColor);
    glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, nextColor);
    glBindImageTexture(3, outA, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
    glBindImageTexture(4, outB, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
    glBindImageTexture(5, outDW, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
    glDispatchCompute((w + 7) / 8, (h + 7) / 8, 1);
    glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
}

void ApexEngine::dispatchVrSor(GLuint at, GLuint bt, GLuint dwi, GLuint dwo, float om, int p, int w, int h) {
    glUseProgram(mProgVrSor);
    glBindImageTexture(0, at, 0, GL_FALSE, 0, GL_READ_ONLY, GL_RGBA16F);
    glBindImageTexture(1, bt, 0, GL_FALSE, 0, GL_READ_ONLY, GL_RGBA16F);
    glBindImageTexture(2, dwi, 0, GL_FALSE, 0, GL_READ_ONLY, GL_RGBA16F);
    glBindImageTexture(3, dwo, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
    glUniform1f(glGetUniformLocation(mProgVrSor, "u_omega"), om);
    glUniform1i(glGetUniformLocation(mProgVrSor, "u_parity"), p);
    glDispatchCompute((w + 7) / 8, (h + 7) / 8, 1);
    glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
}

void ApexEngine::dispatchInterpolate(GLuint pc, GLuint nc, GLuint df, GLuint dw, GLuint oi, float t, int w, int h) {
    glUseProgram(mProgInterpolate);
    glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, pc);
    glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, nc);
    glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, df);
    glActiveTexture(GL_TEXTURE3); glBindTexture(GL_TEXTURE_2D, dw);
    glBindImageTexture(4, oi, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA8);
    glUniform1f(glGetUniformLocation(mProgInterpolate, "u_t"), t);
    glUniform1f(glGetUniformLocation(mProgInterpolate, "u_flowScale"), mFlowScale.load());
    glUniform1f(glGetUniformLocation(mProgInterpolate, "u_liquidFeel"), mLiquidFeel.load());
    glUniform1f(glGetUniformLocation(mProgInterpolate, "u_shutterGain"), mShutterGain.load());
    glUniform1f(glGetUniformLocation(mProgInterpolate, "u_edgeGuard"), mEdgeGuard.load());
    glDispatchCompute((w + 7) / 8, (h + 7) / 8, 1);
    glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
    glBindImageTexture(4, 0, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA8);
    glActiveTexture(GL_TEXTURE3); glBindTexture(GL_TEXTURE_2D, 0);
    glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, 0);
    glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, 0);
    glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, 0);
}

void ApexEngine::dispatchRcas(GLuint inTex, GLuint outImage, int w, int h, float sharpness) {
    glUseProgram(mProgRcas);
    glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, inTex);
    glBindImageTexture(1, outImage, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA8);
    glUniform1f(glGetUniformLocation(mProgRcas, "u_sharpness"), sharpness);
    glDispatchCompute((w + 15) / 16, (h + 15) / 16, 1);
    glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
    glBindImageTexture(1, 0, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA8);
    glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, 0);
}

bool ApexEngine::isHealthy() const {
    return mInitialized && mShaderCompileSuccess && mResourceAllocSuccess && mFboComplete;
}

int ApexEngine::getCompiledShaderCount() const {
    if (mInitialized && (!mShaderCompileSuccess || !mResourceAllocSuccess || !mFboComplete)) {
        return -1;
    }
    return mCompiledShaderCount;
}

std::string ApexEngine::getDiagnostics() {
    std::string diag;
    diag.reserve(512);

    diag += "ApexDIS [20-Pass DIS Status]\n";
    diag += "• Active: " + std::string(mActive.load() ? "YES" : "NO");
    diag += " | Healthy: " + std::string(isHealthy() ? "YES" : "NO");
    diag += " | Shaders: " + std::to_string(mCompiledShaderCount) + "/8 " + (mShaderCompileSuccess ? "[OK]" : "[FAIL]");
    if (!mShaderErrorDetails.empty()) {
        diag += " (" + mShaderErrorDetails + ")";
    }
    diag += "\n";

    diag += "• Resources: " + std::string(mResourceAllocSuccess ? "[OK]" : "[FAIL]");
    diag += " | FBO: " + std::string(mFboComplete ? "[Complete]" : "[Incomplete]");
    if (!mResourceErrorDetails.empty()) {
        diag += " (" + mResourceErrorDetails + ")";
    }
    diag += "\n";

    const char* presetName = "Fast (180p)";
    int preset = mQualityPreset.load();
    if (preset == 1) presetName = "Balanced (252p)";
    else if (preset == 2) presetName = "Quality (360p)";

    diag += "• Native: " + std::to_string(mSurfaceWidth) + "x" + std::to_string(mSurfaceHeight);
    if (mScaledWidth != mSurfaceWidth || mScaledHeight != mSurfaceHeight) {
        diag += " (Scaled: " + std::to_string(mScaledWidth) + "x" + std::to_string(mScaledHeight) + " @" + std::to_string((int)(mRenderScale.load() * 100)) + "%)";
    }
    diag += " -> Flow: " + std::to_string(mFlowWidth) + "x" + std::to_string(mFlowHeight);
    diag += " (" + std::string(presetName) + ")\n";

    float srcFps = (mTypicalDeltaNanos > 1000000.0f) ? (1000000000.0f / mTypicalDeltaNanos) : 0.0f;
    float deltaMs = mTypicalDeltaNanos / 1000000.0f;
    char pbuf[128];
    snprintf(pbuf, sizeof(pbuf), "• Source: %.1f FPS (%.2f ms) | Target: %d FPS | Multiplier: %.1fx (Planned: %dx)\n",
             srcFps, deltaMs, mTargetFPS.load(), mAutoMultiplierVal.load(), mPlannedGen + 1);
    diag += pbuf;

    snprintf(pbuf, sizeof(pbuf), "• Backpressure: CostLimit=%d, DropPersistence=%s, Streaks=(+%d, -%d)\n",
             mCostLimit, (mDropSinceNanos > 0 ? "ACTIVE" : "NONE"), mGenHighStreak, mGenLowStreak);
    diag += pbuf;

    snprintf(pbuf, sizeof(pbuf), "• Frames: Total=%llu, RealPresented=%llu, GenPresented=%llu, Fallbacks=%llu\n",
             (unsigned long long)mTotalFramesProcessed,
             (unsigned long long)mTotalRealFramesPresented,
             (unsigned long long)mTotalGenFramesPresented,
             (unsigned long long)mFallbackCount);
    diag += pbuf;

    GLenum glErr = glGetError();
    if (glErr != GL_NO_ERROR) {
        mLastGLError = glErr;
    }
    diag += "• Last GL Error: " + std::string(getGlErrorString(mLastGLError));

    return diag;
}

void ApexEngine::processFrame(GLuint inputTextureId, GLuint outputFboId, int width, int height,
                              int viewX, int viewY, int viewWidth, int viewHeight, bool isNewRealFrame) {
    if (!mActive.load(std::memory_order_relaxed)) return;

    mTotalFramesProcessed++;

    if (viewWidth <= 0 || viewHeight <= 0) {
        viewX = 0; viewY = 0; viewWidth = width; viewHeight = height;
    }
    if (viewWidth <= 0 || viewHeight <= 0 || inputTextureId == 0) {
        mFallbackCount++;
        return;
    }

    ensureResources(viewWidth, viewHeight);

    if (!isHealthy()) {
        mFallbackCount++;
        glBindFramebuffer(GL_FRAMEBUFFER, outputFboId);
        glViewport(viewX, viewY, viewWidth, viewHeight);
        if (mQuadProg) {
            blitQuad(inputTextureId, (float)viewX / width, (float)viewY / height, (float)viewWidth / width, (float)viewHeight / height);
        }
        if (isNewRealFrame) {
            mActualRealFrameCount.fetch_add(1);
            mTotalRealFramesPresented++;
        }
        return;
    }

    int64_t nowNanos = std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();

    if (isNewRealFrame) {
        onFrameCaptured(nowNanos, true);
        mRealFramesCaptured.fetch_add(1);
        mRealFramesCapturedCount.fetch_add(1);
        mFramesSinceReal.store(0);
        mPreviousSlot = mCurrentSlot;
        mCurrentSlot = (mCurrentSlot + 1) % DIS_SLOTS;

        // 1. Capture full native resolution real frame
        glBindFramebuffer(GL_FRAMEBUFFER, mCaptureFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, mColorRingTex[mCurrentSlot], 0);
        glViewport(0, 0, mScaledWidth, mScaledHeight);
        blitQuad(inputTextureId, (float)viewX / width, (float)viewY / height, (float)viewWidth / width, (float)viewHeight / height);

        // Flush tile buffer before sampling mColorRingTex
        glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

        // 2. Downscale into decoupled flow texture (180p / 252p / 360p)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, mFlowColorTex[mCurrentSlot], 0);
        glViewport(0, 0, mFlowWidth, mFlowHeight);
        blitQuad(mColorRingTex[mCurrentSlot], 0, 0, 1, 1);

        glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        if (mRealFramesCaptured.load() < 2) {
            glBindFramebuffer(GL_FRAMEBUFFER, outputFboId);
            glViewport(viewX, viewY, viewWidth, viewHeight);
            blitQuad(mColorRingTex[mCurrentSlot], 0, 0, 1, 1);
            mActualRealFrameCount.fetch_add(1);
            mTotalRealFramesPresented++;
            mLastPresentedNanos.store(nowNanos, std::memory_order_relaxed);
            return;
        }

        // Pacing & Target FPS Governor:
        int targetFPS = mTargetFPS.load(std::memory_order_relaxed);
        int64_t lastPres = mLastPresentedNanos.load(std::memory_order_relaxed);

        // Relaxed Governor: In Continuous Mode, Java handles the main throttle.
        // We only bypass here if we are rendering faster than 250 FPS to avoid GPU flooding.
        if (mPlannedGen == 0 || (targetFPS > 0 && lastPres > 0 && (nowNanos - lastPres < 4000000LL))) {
            // Multiplier 1x or safety ceiling reached:
            glBindFramebuffer(GL_FRAMEBUFFER, outputFboId);
            glViewport(viewX, viewY, viewWidth, viewHeight);
            blitQuad(mColorRingTex[mCurrentSlot], 0, 0, 1, 1);
            mActualRealFrameCount.fetch_add(1);
            mTotalRealFramesPresented++;
            mLastPresentedNanos.store(nowNanos, std::memory_order_relaxed);
            return;
        }

        // Passes 1-4: Luma & Gradient Pyramid for all 4 levels
        dispatchLumaGrad(0, mFlowColorTex[mCurrentSlot], mCurrentSlot);
        for (uint32_t i = 1; i < MAX_PYR_LEVELS; i++) {
            dispatchLumaGrad(i, mLevels[i - 1].lumaTex[mCurrentSlot], mCurrentSlot);
        }

        // Passes 5-12: Coarse-to-fine (Levels 3 down to 0)
        GLuint coarseFlow = 0;
        int coarseLevel = MAX_PYR_LEVELS - 1;
        for (int i = coarseLevel; i >= 0; i--) {
            DisLevel& lvl = mLevels[i];
            // Inverse Search (Passes 5-8)
            dispatchHierarchicalSearch(i, lvl.lumaTex[mPreviousSlot], lvl.lumaTex[mCurrentSlot],
                                       lvl.gradientTex, coarseFlow, lvl.sparseFlowTex[0],
                                       lvl.sparseWidth, lvl.sparseHeight, coarseLevel);

            // 4-Way Candidate Propagation (Passes 9-12)
            dispatchPropagate(i, lvl.lumaTex[mPreviousSlot], lvl.lumaTex[mCurrentSlot],
                              lvl.sparseFlowTex[0], lvl.sparseFlowTex[1],
                              lvl.sparseWidth, lvl.sparseHeight, 1);
            coarseFlow = lvl.sparseFlowTex[1];
        }

        // Pass 13: 9-Tap Bilateral Guided Densification on Level 0
        DisLevel& l0 = mLevels[0];
        dispatchDensify(l0.sparseFlowTex[1], l0.lumaTex[mPreviousSlot], l0.lumaTex[mCurrentSlot],
                        l0.denseFlowTex, l0.width, l0.height);

        // Pass 14: Hardware-Accelerated Interpolator (G0.5)
        float t = getInterpolationFactor(nowNanos);
        dispatchInterpolate(mColorRingTex[mPreviousSlot], mColorRingTex[mCurrentSlot],
                            l0.denseFlowTex, l0.denseFlowTex, mInterpOutTex, t, mScaledWidth, mScaledHeight);

        // PRESENT G0.5 FIRST!
        glBindFramebuffer(GL_FRAMEBUFFER, outputFboId);
        glViewport(viewX, viewY, viewWidth, viewHeight);
        blitQuad(mInterpOutTex, 0, 0, 1, 1);
        mGeneratedFrameCount.fetch_add(1);
        mTotalGenFramesPresented++;
        mLastPresentedNanos.store(nowNanos, std::memory_order_relaxed);
    } else {
        // Off-VSYNC pulse from Choreographer
        if (mRealFramesCaptured.load() < 2 || mPlannedGen == 0) {
            glBindFramebuffer(GL_FRAMEBUFFER, outputFboId);
            glViewport(viewX, viewY, viewWidth, viewHeight);
            blitQuad(mColorRingTex[mCurrentSlot], 0, 0, 1, 1);
            return;
        }

        int targetFPS = mTargetFPS.load(std::memory_order_relaxed);
        int64_t lastPres = mLastPresentedNanos.load(std::memory_order_relaxed);

        int fs = mFramesSinceReal.fetch_add(1) + 1;
        if (fs < mPlannedGen) {
            // Multi-generation (3x or 4x): output intermediate frame G_t
            // Relaxed pacing: prioritize generation to hit target FPS
            float t = getInterpolationFactor(nowNanos);
            dispatchInterpolate(mColorRingTex[mPreviousSlot], mColorRingTex[mCurrentSlot],
                                mLevels[0].denseFlowTex, mLevels[0].denseFlowTex, mInterpOutTex, t, mScaledWidth, mScaledHeight);
            glBindFramebuffer(GL_FRAMEBUFFER, outputFboId);
            glViewport(viewX, viewY, viewWidth, viewHeight);
            blitQuad(mInterpOutTex, 0, 0, 1, 1);
            mGeneratedFrameCount.fetch_add(1);
            mTotalGenFramesPresented++;
            mLastPresentedNanos.store(nowNanos, std::memory_order_relaxed);
        } else if (fs == mPlannedGen) {
            // PRESENT BUFFERED REAL FRAME R1 SECOND!
            onFrameCaptured(nowNanos, false);
            glBindFramebuffer(GL_FRAMEBUFFER, outputFboId);
            glViewport(viewX, viewY, viewWidth, viewHeight);
            blitQuad(mColorRingTex[mCurrentSlot], 0, 0, 1, 1);
            mActualRealFrameCount.fetch_add(1);
            mTotalRealFramesPresented++;
            mLastPresentedNanos.store(nowNanos, std::memory_order_relaxed);
        } else {
            // Safety fallback: if invoked beyond planned quota,
            // blit the latest valid frame so Framebuffer 0 is never left un-rendered
            glBindFramebuffer(GL_FRAMEBUFFER, outputFboId);
            glViewport(viewX, viewY, viewWidth, viewHeight);
            blitQuad(mColorRingTex[mCurrentSlot], 0, 0, 1, 1);
            return;
        }
    }

    GLenum glErr = glGetError();
    if (glErr != GL_NO_ERROR) {
        mLastGLError = glErr;
        APEX_LOGE("ApexDIS runtime GL error: %s (0x%x)", getGlErrorString(glErr), glErr);
    }
}

void ApexEngine::processFrameWithData(GLuint i, GLuint d, GLuint h, GLuint o, int w, int height) {
    (void)d; (void)h;
    processFrame(i, o, w, height, 0, 0, w, height, true);
}

} // namespace apex
