#pragma once

#include <GLES3/gl32.h>
#include <vector>
#include <string>
#include <atomic>
#include <array>
#include <mutex>
#include <android/log.h>

#define APEX_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "ApexDIS", __VA_ARGS__)

namespace apex {

static constexpr uint32_t DIS_SLOTS = 3;
static constexpr int DIS_PRESET_FAST = 0;
static constexpr int DIS_PRESET_BALANCE = 1;
static constexpr int DIS_PRESET_QUALITY = 2;

struct DisLevel {
    int width, height;
    int sparseWidth, sparseHeight;
    GLuint lumaTex[DIS_SLOTS];
    GLuint gradientTex;
    GLuint sparseFlowTex[2];
    GLuint denseFlowTex;
    GLuint refinedFlowTex;
    GLuint vrPrepTex;
    GLuint vrWtTex;
    GLuint vrATex;
    GLuint vrBTex;
    GLuint vrDWTex[2];
};

class ApexEngine {
public:
    static ApexEngine& getInstance();

    void init(int width, int height);
    void updateDimensions(int width, int height);
    void destroy();

    void processFrame(GLuint inputTextureId, GLuint outputFboId, int width, int height,
                      int viewX, int viewY, int viewWidth, int viewHeight, bool isNewRealFrame);
    void processFrameWithData(GLuint inputTextureId, GLuint depthTextureId, GLuint hudTextureId,
                              GLuint outputFboId, int width, int height);

    void compileShaders();
    void blitQuad(GLuint tex, float uMin = 0.0f, float vMin = 0.0f, float uScale = 1.0f, float vScale = 1.0f);

    // Unified 10-Pass Fluid Engine Interface
    void dispatchLuma(GLuint inTex, uint32_t slot);
    void dispatchInverseSearch(int level, int coarseLevel, GLuint lastLuma, GLuint nextLuma,
                               GLuint lastGrad, GLuint coarseFlow, GLuint outSparseImg,
                               int sparseW, int sparseH);
    void dispatchPropagate(int dist, GLuint flowInTex, GLuint flowOutImg, int sparseW, int sparseH);
    void dispatchFluidDensifySetup(GLuint sparseFlow, GLuint lastLuma, GLuint nextLuma,
                                   GLuint prevColor, GLuint nextColor,
                                   GLuint outDense, GLuint outPrep, GLuint outDWInit, GLuint outA, GLuint outB, int w, int h);
    void dispatchVrSor(GLuint aTex, GLuint bTex, GLuint dWinTex, GLuint dWoutImg, float omega, int parity, int w, int h);
    void dispatchInterpolate(GLuint prevColor, GLuint nextColor, GLuint denseFlow, GLuint dW,
                             GLuint outImg, float t, int w, int h);

    void logHeartbeatIfDue(int64_t nowNanos);

    // Atomic State & UI Configurations
    std::atomic<bool> mActive{false};
    std::atomic<bool> mLoggingEnabled{true};
    std::atomic<int> mQualityPreset{DIS_PRESET_FAST};
    std::atomic<int> mTargetFPS{60};
    std::atomic<float> mShutterGain{0.0f};
    std::atomic<float> mFlowScale{1.0f};
    std::atomic<float> mLiquidFeel{0.5f};
    std::atomic<float> mEdgeGuard{0.5f};
    std::atomic<float> mRenderScale{1.0f};
    std::atomic<bool> mDebugOverlay{false};

    // Pacing & Multiplier State
    void onFrameCaptured(int64_t nowNanos, bool isActualNewFrame);
    float getInterpolationFactor(int64_t nowNanos);
    int getAutoMultiplier() const;
    float getTypicalDeltaNanos() const;
    bool isRenderingGeneratedFrame() const;
    void setPendingRealFrame(bool pending);

    // Telemetry
    int getActualRealFrameCount();
    int getGeneratedFrameCount();
    int getCompiledShaderCount() const;
    std::string getDiagnostics();

    // Setters
    void setActive(bool enabled);
    bool isActive() const;
    void setQualityPreset(int quality);
    int getQualityPreset() const;
    void setLoggingEnabled(bool enabled);
    bool isLoggingEnabled() const;
    void setTargetFPS(int fps);
    int getTargetFPS() const;
    void setShutterGain(float gain);
    float getShutterGain() const;
    void setFlowScale(float scale);
    float getFlowScale() const;
    void setLiquidFeel(float feel);
    float getLiquidFeel() const;
    void setEdgeGuard(float guard);
    float getEdgeGuard() const;
    void setRenderScale(float scale);
    float getRenderScale() const;
    void setDebugOverlay(bool enabled);
    bool isDebugOverlay() const;

private:
    ApexEngine();
    ~ApexEngine();
    void ensureResources(int width, int height);
    void cleanupResources();

    bool mInitialized{false};
    int mSurfaceWidth{0}, mSurfaceHeight{0};
    int mScaledWidth{0}, mScaledHeight{0};
    uint32_t mNumLevels{0};
    DisLevel mLevels[1];

    GLuint mColorRingTex[DIS_SLOTS]{0};
    GLuint mInterpOutTex{0};
    GLuint mCaptureFbo{0};
    uint32_t mCurrentSlot{0};
    uint32_t mPreviousSlot{0};

    GLuint mProgLuma{0};
    GLuint mProgInverseSearch{0};
    GLuint mProgPropagate{0};
    GLuint mProgFluidDensifySetup{0};
    GLuint mProgVrSor{0};
    GLuint mProgInterpolate{0};

    GLuint mQuadProg{0};
    GLuint mQuadVao{0};
    GLuint mQuadVbo{0};

    int mCompiledShaderCount{0};

    // Pacing internal
    std::atomic<int> mPlannedGen{1};
    std::atomic<int> mAutoMultiplier{2};
    std::atomic<float> mAutoMultiplierVal{2.0f};
    std::atomic<int64_t> mLastRealFrameTimeNanos{0};
    std::atomic<bool> mRenderingGeneratedFrame{false};
    std::atomic<bool> mPendingRealFrame{false};
    std::atomic<int> mFramesSinceReal{0};
    float mTypicalDeltaNanos{16666667.0f};
    std::array<float, 10> mDeltaHistory;
    std::array<float, 10> mSortedHistory;
    int mHistoryIdx{0};
    float mSmoothedDesired{60.0f};

    // Pacing Streak/Hold
    int mGenHighStreak{0};
    int mGenLowStreak{0};
    int mCostLimit{3};
    int64_t mHoldUntilNanos{0};
    int64_t mDropSinceNanos{0};
    float mDeltaAtRaise{0.0f};
    int64_t mLastCostChangeNanos{0};

    std::atomic<int> mActualRealFrameCount{0};
    std::atomic<int> mGeneratedFrameCount{0};
    std::atomic<int> mRealFramesCaptured{0};
};

} // namespace apex
