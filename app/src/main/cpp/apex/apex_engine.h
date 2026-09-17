#pragma once

#include <GLES3/gl32.h>
#include <vector>
#include <string>
#include <atomic>
#include <array>
#include <android/log.h>

#define APEX_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "ApexDIS", __VA_ARGS__)
#define APEX_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "ApexDIS", __VA_ARGS__)
#define APEX_LOGW(...) __android_log_print(ANDROID_LOG_WARN, "ApexDIS", __VA_ARGS__)

namespace apex {

static constexpr uint32_t DIS_SLOTS = 3;
static constexpr uint32_t MAX_PYR_LEVELS = 4;

struct DisLevel {
    int width{0}, height{0};
    int sparseWidth{0}, sparseHeight{0};
    GLuint lumaTex[DIS_SLOTS]{0};
    GLuint gradientTex{0};
    GLuint sparseFlowTex[2]{0}; // Ping-pong for propagation
    GLuint denseFlowTex{0};
    GLuint vrATex{0};
    GLuint vrBTex{0};
    GLuint vrDWTex[2]{0};       // Ping-pong for Red-Black SOR sweeps
};

class ApexEngine {
public:
    static ApexEngine& getInstance();

    void init(int width, int height);
    void updateDimensions(int width, int height);
    void destroy();

    void processFrame(GLuint inputTextureId, GLuint outputFboId, int width, int height,
                      int viewX, int viewY, int viewWidth, int viewHeight, bool isNewRealFrame);

    // Legacy support for JNI bridge
    void processFrameWithData(GLuint i, GLuint d, GLuint h, GLuint o, int w, int height);

    void compileShaders();
    void blitQuad(GLuint tex, float uMin = 0.0f, float vMin = 0.0f, float uScale = 1.0f, float vScale = 1.0f);

    // Physical 20-Pass Dispatch Interface
    void dispatchLumaGrad(int level, GLuint inTex, uint32_t slot);
    void dispatchHierarchicalSearch(int level, GLuint lastLuma, GLuint nextLuma, GLuint lastGrad,
                                     GLuint coarseFlow, GLuint outSparse, int sw, int sh, int coarseLevel);
    void dispatchPropagate(int level, GLuint lastLuma, GLuint nextLuma, GLuint fi, GLuint fo, int sw, int sh, int dist);
    void dispatchDensify(GLuint sparseFlow, GLuint lastLuma, GLuint nextLuma, GLuint denseFlow, int w, int h);
    void dispatchVrSetup(GLuint denseFlow, GLuint prevColor, GLuint nextColor, GLuint outA, GLuint outB, GLuint outDW, int w, int h);
    void dispatchVrSor(GLuint at, GLuint bt, GLuint dwi, GLuint dwo, float om, int p, int w, int h);
    void dispatchInterpolate(GLuint pc, GLuint nc, GLuint df, GLuint dw, GLuint oi, float t, int w, int h);
    void dispatchRcas(GLuint inTex, GLuint outImage, int w, int h, float sharpness);

    // Pacing & Telemetry
    void onFrameCaptured(int64_t nowNanos, bool isActualNewFrame);
    float getInterpolationFactor(int64_t nowNanos);
    int getAutoMultiplier() const { return mAutoMultiplier.load(); }
    int getSourceFrameCount() { return mRealFramesCapturedCount.exchange(0); }
    int getPresentedRealFrameCount() { return mActualRealFrameCount.exchange(0); }
    int getGeneratedFrameCount() { return mGeneratedFrameCount.exchange(0); }
    int getCompiledShaderCount() const;
    bool isHealthy() const;
    std::string getDiagnostics();

    // Atomic Settings
    void setActive(bool e) { mActive.store(e); }
    bool isActive() const { return mActive.load(); }
    void setQualityPreset(int q) { mQualityPreset.store(q); mInitialized = false; }
    int getQualityPreset() const { return mQualityPreset.load(); }
    void setLoggingEnabled(bool e) { mLoggingEnabled.store(e); }
    bool isLoggingEnabled() const { return mLoggingEnabled.load(); }
    void setTargetFPS(int f) { mTargetFPS.store(f); }
    int getTargetFPS() const { return mTargetFPS.load(); }
    void setShutterGain(float g) { mShutterGain.store(g); }
    float getShutterGain() const { return mShutterGain.load(); }
    void setFlowScale(float s) { mFlowScale.store(s); }
    float getFlowScale() const { return mFlowScale.load(); }
    void setLiquidFeel(float f) { mLiquidFeel.store(f); }
    float getLiquidFeel() const { return mLiquidFeel.load(); }
    void setEdgeGuard(float g) { mEdgeGuard.store(g); }
    float getEdgeGuard() const { return mEdgeGuard.load(); }
    void setRenderScale(float s) { mRenderScale.store(s); mInitialized = false; }
    float getRenderScale() const { return mRenderScale.load(); }
    void setPendingRealFrame(bool p) { mPendingRealFrame.store(p); }
    void setDebugOverlay(bool e) { mDebugOverlay.store(e); }
    bool isDebugOverlay() const { return mDebugOverlay.load(); }
    bool isRenderingGeneratedFrame() const { return mRenderingGeneratedFrame.load(); }

private:
    ApexEngine();
    ~ApexEngine();
    void ensureResources(int width, int height);
    void cleanupResources();

    bool mInitialized{false};
    bool mShaderCompileSuccess{false};
    bool mResourceAllocSuccess{false};
    bool mFboComplete{false};
    std::string mShaderErrorDetails;
    std::string mResourceErrorDetails;
    GLenum mLastGLError{GL_NO_ERROR};

    uint64_t mTotalFramesProcessed{0};
    uint64_t mTotalRealFramesPresented{0};
    uint64_t mTotalGenFramesPresented{0};
    uint64_t mFallbackCount{0};

    int mSurfaceWidth{0}, mSurfaceHeight{0};
    int mScaledWidth{0}, mScaledHeight{0};
    int mFlowWidth{320}, mFlowHeight{180};
    DisLevel mLevels[MAX_PYR_LEVELS];

    GLuint mColorRingTex[DIS_SLOTS]{0};    // Native-res real frames
    GLuint mFlowColorTex[DIS_SLOTS]{0};    // 180p downscaled flow inputs
    GLuint mNativeWarpTex{0};               // Native-res intermediate warped frame
    GLuint mInterpOutTex{0};                // Final output texture (before screen blit)
    GLuint mCaptureFbo{0};
    uint32_t mCurrentSlot{0};
    uint32_t mPreviousSlot{0};

    // Shaders
    GLuint mProgLumaGrad{0};
    GLuint mProgInverseSearch{0};
    GLuint mProgPropagate{0};
    GLuint mProgDensify{0};
    GLuint mProgVrSetup{0};
    GLuint mProgVrSor{0};
    GLuint mProgInterpolate{0};
    GLuint mProgRcas{0};
    GLuint mQuadProg{0}, mQuadVao{0}, mQuadVbo{0};
    int mCompiledShaderCount{0};

    // Atomics
    std::atomic<bool> mActive{false}, mLoggingEnabled{true}, mDebugOverlay{false}, mPendingRealFrame{false}, mRenderingGeneratedFrame{false};
    std::atomic<int> mQualityPreset{0}, mTargetFPS{60}, mPlannedGen{1}, mAutoMultiplier{2};
    std::atomic<float> mShutterGain{0.0f}, mFlowScale{1.0f}, mLiquidFeel{0.5f}, mEdgeGuard{0.5f}, mRenderScale{1.0f}, mAutoMultiplierVal{2.0f};

    // Pacing History
    std::atomic<int64_t> mLastRealFrameTimeNanos{0};
    std::atomic<int64_t> mLastPresentedNanos{0};
    std::atomic<int> mFramesSinceReal{0};
    float mTypicalDeltaNanos{16666667.0f};
    std::array<float, 20> mDeltaHistory;
    std::array<float, 20> mSortedHistory;
    int mHistoryIdx{0};
    float mSmoothedDesired{60.0f};

    // GPU Backpressure
    int mGenHighStreak{0}, mGenLowStreak{0}, mCostLimit{3};
    int64_t mHoldUntilNanos{0}, mDropSinceNanos{0};
    float mDeltaAtRaise{0.0f};
    int64_t mLastCostChangeNanos{0};

    std::atomic<int> mActualRealFrameCount{0}, mGeneratedFrameCount{0}, mRealFramesCaptured{0}, mRealFramesCapturedCount{0};
};

} // namespace apex
