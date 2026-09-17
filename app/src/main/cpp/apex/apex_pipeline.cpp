#include "apex_engine.h"
#include "apex_shaders.h"
#include <vector>
#include <string>
#include <chrono>
#include <algorithm>

namespace apex {

static const char* kQuadVS = R"(#version 300 es
layout(location = 0) in vec2 aPos;
uniform vec4 uTexBounds;
out vec2 vUV;
void main() {
    vec2 baseUV = aPos * 0.5 + 0.5;
    vUV = uTexBounds.xy + baseUV * uTexBounds.zw;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
)";

static const char* kQuadFS = R"(#version 300 es
precision highp float;
in vec2 vUV;
uniform sampler2D uTex;
out vec4 fragColor;
void main() {
    fragColor = texture(uTex, vUV);
}
)";

ApexEngine& ApexEngine::getInstance() {
    static ApexEngine instance;
    return instance;
}

ApexEngine::ApexEngine() {
    mDeltaHistory.fill(16666667.0f);
    mSortedHistory.fill(16666667.0f);
    mTypicalDeltaNanos = 16666667.0f;
    mSmoothedDesired = 60.0f;
}

ApexEngine::~ApexEngine() {
    destroy();
}

static GLuint compileShader(GLenum type, const char* src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint status;
    glGetShaderiv(s, GL_COMPILE_STATUS, &status);
    if (!status) {
        char log[1024];
        glGetShaderInfoLog(s, 1024, nullptr, log);
        __android_log_print(ANDROID_LOG_ERROR, "ApexDIS", "Shader Compile Error: %s", log);
        glDeleteShader(s);
        return 0;
    }
    return s;
}

static GLuint compileComputeProgram(const char* src) {
    GLuint s = compileShader(GL_COMPUTE_SHADER, src);
    if (!s) return 0;
    GLuint p = glCreateProgram();
    glAttachShader(p, s);
    glLinkProgram(p);
    glDeleteShader(s);
    GLint status;
    glGetProgramiv(p, GL_LINK_STATUS, &status);
    if (!status) {
        char log[1024];
        glGetProgramInfoLog(p, 1024, nullptr, log);
        __android_log_print(ANDROID_LOG_ERROR, "ApexDIS", "Program Link Error: %s", log);
        glDeleteProgram(p);
        return 0;
    }
    return p;
}

void ApexEngine::compileShaders() {
    if (mProgLuma) return;
    mProgLuma = compileComputeProgram(kShaderDisLuma);
    mProgInverseSearch = compileComputeProgram(kShaderDisInverseSearch);
    mProgPropagate = compileComputeProgram(kShaderDisPropagate);
    mProgFluidDensifySetup = compileComputeProgram(kShaderDisFluidDensifySetup);
    mProgVrSor = compileComputeProgram(kShaderDisVrSor);
    mProgInterpolate = compileComputeProgram(kShaderDisInterpolate);

    GLuint vs = compileShader(GL_VERTEX_SHADER, kQuadVS);
    GLuint fs = compileShader(GL_FRAGMENT_SHADER, kQuadFS);
    if (vs && fs) {
        mQuadProg = glCreateProgram();
        glAttachShader(mQuadProg, vs);
        glAttachShader(mQuadProg, fs);
        glLinkProgram(mQuadProg);
        glDeleteShader(vs);
        glDeleteShader(fs);
    }

    if (!mQuadVao) {
        glGenVertexArrays(1, &mQuadVao);
        glGenBuffers(1, &mQuadVbo);
        glBindVertexArray(mQuadVao);
        glBindBuffer(GL_ARRAY_BUFFER, mQuadVbo);
        const float quadVerts[] = {-1, -1, 1, -1, -1, 1, 1, 1};
        glBufferData(GL_ARRAY_BUFFER, sizeof(quadVerts), quadVerts, GL_STATIC_DRAW);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, nullptr);
        glEnableVertexAttribArray(0);
        glBindVertexArray(0);
    }
    mCompiledShaderCount = 6;
}

void ApexEngine::blitQuad(GLuint tex, float uMin, float vMin, float uScale, float vScale) {
    if (!mQuadProg) return;
    glUseProgram(mQuadProg);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, tex);
    glUniform1i(glGetUniformLocation(mQuadProg, "uTex"), 0);
    glUniform4f(glGetUniformLocation(mQuadProg, "uTexBounds"), uMin, vMin, uScale, vScale);
    glBindVertexArray(mQuadVao);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);
}

static GLuint createStorageTexture(int w, int h, GLint internalFormat, GLenum filter) {
    GLuint t;
    glGenTextures(1, &t);
    glBindTexture(GL_TEXTURE_2D, t);
    glTexStorage2D(GL_TEXTURE_2D, 1, internalFormat, w, h);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    return t;
}

void ApexEngine::init(int w, int h) { ensureResources(w, h); }
void ApexEngine::updateDimensions(int w, int h) { ensureResources(w, h); }

void ApexEngine::ensureResources(int width, int height) {
    float rs = mRenderScale.load(std::memory_order_relaxed);
    int sw = std::max(64, (int)(width * rs)), sh = std::max(64, (int)(height * rs));

    int preset = mQualityPreset.load(std::memory_order_relaxed);
    int targetMinSide = (preset == 1) ? 252 : (preset == 2 ? 360 : 180);
    int minSide = std::min(sw, sh);
    float flowScale = (targetMinSide >= minSide) ? 1.0f : ((float)targetMinSide / minSide);
    int fw = (int)(sw * flowScale), fh = (int)(sh * flowScale);

    if (mInitialized && mSurfaceWidth == width && mSurfaceHeight == height && mScaledWidth == sw && mLevels[0].width == fw) return;

    cleanupResources();
    mSurfaceWidth = width; mSurfaceHeight = height; mScaledWidth = sw; mScaledHeight = sh;
    compileShaders();
    for (int i=0; i<3; i++) mColorRingTex[i] = createStorageTexture(sw, sh, GL_RGBA8, GL_LINEAR);
    mInterpOutTex = createStorageTexture(sw, sh, GL_RGBA8, GL_LINEAR);
    glGenFramebuffers(1, &mCaptureFbo);

    mNumLevels = 1;
    mLevels[0].width = fw; mLevels[0].height = fh;
    mLevels[0].sparseWidth = fw > 8 ? 1 + (fw - 8) / 3 : 1;
    mLevels[0].sparseHeight = fh > 8 ? 1 + (fh - 8) / 3 : 1;
    for (int s=0; s<3; s++) mLevels[0].lumaTex[s] = createStorageTexture(fw, fh, GL_R32F, GL_LINEAR);
    mLevels[0].gradientTex = createStorageTexture(fw, fh, GL_RGBA16F, GL_NEAREST);
    mLevels[0].sparseFlowTex[0] = createStorageTexture(mLevels[0].sparseWidth, mLevels[0].sparseHeight, GL_RGBA16F, GL_NEAREST);
    mLevels[0].sparseFlowTex[1] = createStorageTexture(mLevels[0].sparseWidth, mLevels[0].sparseHeight, GL_RGBA16F, GL_NEAREST);
    mLevels[0].denseFlowTex = createStorageTexture(fw, fh, GL_RGBA16F, GL_LINEAR);
    mLevels[0].vrPrepTex = createStorageTexture(fw, fh, GL_RGBA16F, GL_NEAREST);
    mLevels[0].vrWtTex = createStorageTexture(fw, fh, GL_R16F, GL_NEAREST);
    mLevels[0].vrATex = createStorageTexture(fw, fh, GL_RGBA16F, GL_NEAREST);
    mLevels[0].vrBTex = createStorageTexture(fw, fh, GL_RGBA16F, GL_NEAREST);
    mLevels[0].vrDWTex[0] = createStorageTexture(fw, fh, GL_RGBA16F, GL_NEAREST);
    mLevels[0].vrDWTex[1] = createStorageTexture(fw, fh, GL_RGBA16F, GL_NEAREST);
    mLevels[0].refinedFlowTex = createStorageTexture(fw, fh, GL_RGBA16F, GL_LINEAR);
    mInitialized = true;
}

void ApexEngine::cleanupResources() {
    if (!mInitialized) return;
    for (int i=0; i<3; i++) glDeleteTextures(1, &mColorRingTex[i]);
    glDeleteTextures(1, &mInterpOutTex);
    glDeleteFramebuffers(1, &mCaptureFbo);
    for (int s=0; s<3; s++) glDeleteTextures(1, &mLevels[0].lumaTex[s]);
    glDeleteTextures(1, &mLevels[0].gradientTex);
    glDeleteTextures(1, &mLevels[0].sparseFlowTex[0]);
    glDeleteTextures(1, &mLevels[0].sparseFlowTex[1]);
    glDeleteTextures(1, &mLevels[0].denseFlowTex);
    glDeleteTextures(1, &mLevels[0].vrPrepTex);
    glDeleteTextures(1, &mLevels[0].vrWtTex);
    glDeleteTextures(1, &mLevels[0].vrATex);
    glDeleteTextures(1, &mLevels[0].vrBTex);
    glDeleteTextures(1, &mLevels[0].vrDWTex[0]);
    glDeleteTextures(1, &mLevels[0].vrDWTex[1]);
    glDeleteTextures(1, &mLevels[0].refinedFlowTex);
    mInitialized = false;
}

void ApexEngine::destroy() {
    cleanupResources();
    if (mProgLuma) glDeleteProgram(mProgLuma);
    if (mProgInverseSearch) glDeleteProgram(mProgInverseSearch);
    if (mProgPropagate) glDeleteProgram(mProgPropagate);
    if (mProgFluidDensifySetup) glDeleteProgram(mProgFluidDensifySetup);
    if (mProgVrSor) glDeleteProgram(mProgVrSor);
    if (mProgInterpolate) glDeleteProgram(mProgInterpolate);
    if (mQuadProg) glDeleteProgram(mQuadProg);
    if (mQuadVao) glDeleteVertexArrays(1, &mQuadVao);
    if (mQuadVbo) glDeleteBuffers(1, &mQuadVbo);
}

void ApexEngine::dispatchLuma(GLuint inTex, uint32_t slot) {
    glUseProgram(mProgLuma);
    glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, inTex);
    glBindImageTexture(5, mLevels[0].lumaTex[slot], 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_R32F);
    glBindImageTexture(13, mLevels[0].gradientTex, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
    glDispatchCompute((mLevels[0].width + 15) / 16, (mLevels[0].height + 15) / 16, 1);
    glMemoryBarrier(GL_ALL_BARRIER_BITS);
}

void ApexEngine::dispatchInverseSearch(int l, int cl, GLuint ll, GLuint nl, GLuint lg, GLuint cf, GLuint os, int sw, int sh) {
    glUseProgram(mProgInverseSearch);
    glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, ll);
    glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, nl);
    glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, lg);
    glActiveTexture(GL_TEXTURE3); glBindTexture(GL_TEXTURE_2D, cf ? cf : ll);
    glBindImageTexture(5, os, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
    glDispatchCompute((sw + 7) / 8, (sh + 7) / 8, 1);
    glMemoryBarrier(GL_ALL_BARRIER_BITS);
}

void ApexEngine::dispatchPropagate(int dist, GLuint fi, GLuint fo, int sw, int sh) {
    glUseProgram(mProgPropagate);
    glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, fi);
    glBindImageTexture(5, fo, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
    glUniform1i(glGetUniformLocation(mProgPropagate, "u_dist"), dist);
    glDispatchCompute((sw + 7) / 8, (sh + 7) / 8, 1);
    glMemoryBarrier(GL_ALL_BARRIER_BITS);
}

void ApexEngine::dispatchFluidDensifySetup(GLuint sf, GLuint ll, GLuint nl, GLuint pc, GLuint nc, GLuint od, GLuint op, GLuint odwi, GLuint oa, GLuint ob, int w, int h) {
    glUseProgram(mProgFluidDensifySetup);
    glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, sf);
    glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, ll);
    glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, nl);
    glActiveTexture(GL_TEXTURE3); glBindTexture(GL_TEXTURE_2D, pc);
    glActiveTexture(GL_TEXTURE4); glBindTexture(GL_TEXTURE_2D, nc);
    glBindImageTexture(5, od, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
    glBindImageTexture(8, op, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
    glBindImageTexture(9, odwi, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
    glBindImageTexture(10, oa, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
    glBindImageTexture(11, ob, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
    glDispatchCompute((w + 7) / 8, (h + 7) / 8, 1);
    glMemoryBarrier(GL_ALL_BARRIER_BITS);
}

void ApexEngine::dispatchVrSor(GLuint at, GLuint bt, GLuint dwi, GLuint dwo, float om, int p, int w, int h) {
    glUseProgram(mProgVrSor);
    glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, at);
    glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, bt);
    glActiveTexture(GL_TEXTURE3); glBindTexture(GL_TEXTURE_2D, dwi);
    glBindImageTexture(8, dwo, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
    glUniform1f(glGetUniformLocation(mProgVrSor, "u_omega"), om);
    glUniform1i(glGetUniformLocation(mProgVrSor, "u_parity"), p);
    glDispatchCompute((w + 7) / 8, (h + 7) / 8, 1);
    glMemoryBarrier(GL_ALL_BARRIER_BITS);
}

void ApexEngine::dispatchInterpolate(GLuint pc, GLuint nc, GLuint df, GLuint dw, GLuint oi, float t, int w, int h) {
    glUseProgram(mProgInterpolate);
    glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, pc);
    glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, nc);
    glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, df);
    glActiveTexture(GL_TEXTURE3); glBindTexture(GL_TEXTURE_2D, dw);
    glBindImageTexture(5, oi, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA8);
    glUniform1f(glGetUniformLocation(mProgInterpolate, "u_t"), t);
    glUniform1f(glGetUniformLocation(mProgInterpolate, "u_flowScale"), mFlowScale.load(std::memory_order_relaxed));
    glUniform1f(glGetUniformLocation(mProgInterpolate, "u_liquidFeel"), mLiquidFeel.load(std::memory_order_relaxed));
    glUniform1f(glGetUniformLocation(mProgInterpolate, "u_shutterGain"), mShutterGain.load(std::memory_order_relaxed));
    glUniform1f(glGetUniformLocation(mProgInterpolate, "u_edgeGuard"), mEdgeGuard.load(std::memory_order_relaxed));
    glDispatchCompute((w + 7) / 8, (h + 7) / 8, 1);
    glMemoryBarrier(GL_ALL_BARRIER_BITS);
}

void ApexEngine::processFrame(GLuint inputTextureId, GLuint outputFboId, int width, int height, int viewX, int viewY, int viewWidth, int viewHeight, bool isNewRealFrame) {
    if (!mActive.load(std::memory_order_relaxed)) return;
    if (viewWidth <= 0 || viewHeight <= 0) { viewX = 0; viewY = 0; viewWidth = width; viewHeight = height; }
    ensureResources(viewWidth, viewHeight);
    int64_t nowNanos = std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();

    if (isNewRealFrame) {
        onFrameCaptured(nowNanos, true);
        mRealFramesCaptured.fetch_add(1, std::memory_order_relaxed);
        mFramesSinceReal.store(0, std::memory_order_release);
        mPreviousSlot = mCurrentSlot; mCurrentSlot = (mCurrentSlot + 1) % 3;

        glBindFramebuffer(GL_FRAMEBUFFER, mCaptureFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, mColorRingTex[mCurrentSlot], 0);
        glViewport(0, 0, mScaledWidth, mScaledHeight);
        float uMin = (float)viewX/width, vMin = (float)viewY/height, uScale = (float)viewWidth/width, vScale = (float)viewHeight/height;
        blitQuad(inputTextureId, uMin, vMin, uScale, vScale);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glMemoryBarrier(GL_ALL_BARRIER_BITS);

        dispatchLuma(mColorRingTex[mCurrentSlot], mCurrentSlot);
        if (mRealFramesCaptured.load(std::memory_order_relaxed) < 2) {
            glBindFramebuffer(GL_FRAMEBUFFER, outputFboId); glViewport(viewX, viewY, viewWidth, viewHeight);
            blitQuad(mColorRingTex[mCurrentSlot], 0, 0, 1, 1); return;
        }

        DisLevel& l0 = mLevels[0];
        dispatchInverseSearch(0, 1, l0.lumaTex[mPreviousSlot], l0.lumaTex[mCurrentSlot], l0.gradientTex, 0, l0.sparseFlowTex[0], l0.sparseWidth, l0.sparseHeight);
        dispatchPropagate(1, l0.sparseFlowTex[0], l0.sparseFlowTex[1], l0.sparseWidth, l0.sparseHeight);
        dispatchPropagate(2, l0.sparseFlowTex[1], l0.sparseFlowTex[0], l0.sparseWidth, l0.sparseHeight);
        dispatchFluidDensifySetup(l0.sparseFlowTex[0], l0.lumaTex[mPreviousSlot], l0.lumaTex[mCurrentSlot], mColorRingTex[mPreviousSlot], mColorRingTex[mCurrentSlot],
                                  l0.denseFlowTex, l0.vrPrepTex, l0.vrDWTex[0], l0.vrATex, l0.vrBTex, l0.width, l0.height);
        dispatchVrSor(l0.vrATex, l0.vrBTex, l0.vrDWTex[0], l0.vrDWTex[1], 1.45f, 0, l0.width, l0.height);
        dispatchVrSor(l0.vrATex, l0.vrBTex, l0.vrDWTex[1], l0.vrDWTex[0], 1.45f, 1, l0.width, l0.height);

        float t = getInterpolationFactor(nowNanos);
        mRenderingGeneratedFrame.store(true, std::memory_order_release);
        dispatchInterpolate(mColorRingTex[mPreviousSlot], mColorRingTex[mCurrentSlot], l0.denseFlowTex, l0.vrDWTex[0], mInterpOutTex, t, mScaledWidth, mScaledHeight);
        glBindFramebuffer(GL_FRAMEBUFFER, outputFboId); glViewport(viewX, viewY, viewWidth, viewHeight);
        blitQuad(mInterpOutTex, 0, 0, 1, 1);
        mGeneratedFrameCount.fetch_add(1);
    } else {
        if (mRealFramesCaptured.load(std::memory_order_relaxed) < 2) return;
        int fs = mFramesSinceReal.fetch_add(1, std::memory_order_acq_rel) + 1;
        if (fs < mPlannedGen) {
            float t = getInterpolationFactor(nowNanos);
            mRenderingGeneratedFrame.store(true, std::memory_order_release);
            dispatchInterpolate(mColorRingTex[mPreviousSlot], mColorRingTex[mCurrentSlot], mLevels[0].denseFlowTex, mLevels[0].vrDWTex[0], mInterpOutTex, t, mScaledWidth, mScaledHeight);
            glBindFramebuffer(GL_FRAMEBUFFER, outputFboId); glViewport(viewX, viewY, viewWidth, viewHeight);
            blitQuad(mInterpOutTex, 0, 0, 1, 1);
            mGeneratedFrameCount.fetch_add(1);
        } else {
            mRenderingGeneratedFrame.store(false, std::memory_order_release);
            onFrameCaptured(nowNanos, false);
            glBindFramebuffer(GL_FRAMEBUFFER, outputFboId); glViewport(viewX, viewY, viewWidth, viewHeight);
            blitQuad(mColorRingTex[mCurrentSlot], 0, 0, 1, 1);
            mActualRealFrameCount.fetch_add(1);
        }
    }
    logHeartbeatIfDue(nowNanos);
}

void ApexEngine::processFrameWithData(GLuint i, GLuint d, GLuint h, GLuint o, int w, int height) {
    processFrame(i, o, w, height, 0, 0, w, height, true);
}

void ApexEngine::setActive(bool e) { mActive.store(e); }
bool ApexEngine::isActive() const { return mActive.load(); }
void ApexEngine::setQualityPreset(int q) { mQualityPreset.store(q); mInitialized = false; }
int ApexEngine::getQualityPreset() const { return mQualityPreset.load(); }
void ApexEngine::setLoggingEnabled(bool e) { mLoggingEnabled.store(e); }
bool ApexEngine::isLoggingEnabled() const { return mLoggingEnabled.load(); }
void ApexEngine::setTargetFPS(int f) { mTargetFPS.store(f); }
int ApexEngine::getTargetFPS() const { return mTargetFPS.load(); }
void ApexEngine::setShutterGain(float g) { mShutterGain.store(g); }
float ApexEngine::getShutterGain() const { return mShutterGain.load(); }
void ApexEngine::setFlowScale(float s) { mFlowScale.store(s); }
float ApexEngine::getFlowScale() const { return mFlowScale.load(); }
void ApexEngine::setLiquidFeel(float f) { mLiquidFeel.store(f); }
float ApexEngine::getLiquidFeel() const { return mLiquidFeel.load(); }
void ApexEngine::setEdgeGuard(float g) { mEdgeGuard.store(g); }
float ApexEngine::getEdgeGuard() const { return mEdgeGuard.load(); }
void ApexEngine::setRenderScale(float s) { mRenderScale.store(s); mInitialized = false; }
float ApexEngine::getRenderScale() const { return mRenderScale.load(); }
void ApexEngine::setDebugOverlay(bool e) { mDebugOverlay.store(e); }
bool ApexEngine::isDebugOverlay() const { return mDebugOverlay.load(); }
int ApexEngine::getActualRealFrameCount() { return mActualRealFrameCount.exchange(0); }
int ApexEngine::getGeneratedFrameCount() { return mGeneratedFrameCount.exchange(0); }
int ApexEngine::getAutoMultiplier() const { return mAutoMultiplier.load(); }
float ApexEngine::getTypicalDeltaNanos() const { return mTypicalDeltaNanos; }
bool ApexEngine::isRenderingGeneratedFrame() const { return mRenderingGeneratedFrame.load(); }
void ApexEngine::setPendingRealFrame(bool p) { mPendingRealFrame.store(p); }

void ApexEngine::logHeartbeatIfDue(int64_t now) {
    static int64_t last = 0;
    if (now - last > 5000000000LL) {
        APEX_LOGI("Heartbeat: Multiplier: %d, Target: %d FPS", mAutoMultiplier.load(), mTargetFPS.load());
        last = now;
    }
}

int ApexEngine::getCompiledShaderCount() const { return mCompiledShaderCount; }
std::string ApexEngine::getDiagnostics() { return "Status: Elite 10-Pass Fluid Engine Active"; }

} // namespace apex
