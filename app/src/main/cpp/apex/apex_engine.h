#pragma once

#include <GLES3/gl31.h>
#include <GLES2/gl2ext.h>
#include <android/log.h>
#include <atomic>
#include <chrono>
#include <array>
#include <algorithm>
#include <cmath>
#include <cstdint>

#define APEX_TAG "ApexEngine"
#define APEX_LOGI(...) __android_log_print(ANDROID_LOG_INFO, APEX_TAG, __VA_ARGS__)
#define APEX_LOGW(...) __android_log_print(ANDROID_LOG_WARN, APEX_TAG, __VA_ARGS__)
#define APEX_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, APEX_TAG, __VA_ARGS__)
#define APEX_LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, APEX_TAG, __VA_ARGS__)

namespace apex {

enum QualityPreset {
    QUALITY_ULTRA_PERFORMANCE = 0, // 1 Fused Pass (1:1 Native Resolution)
    QUALITY_PERFORMANCE       = 1, // 3 Passes (1:1 Native L0/L0/Output)
    QUALITY_BALANCED          = 2, // 5 Passes (1:1 Native + 1/2 Pyramid)
    QUALITY_HIGH_QUALITY      = 3, // 11 Passes (1:1 Native 3-Tier Pyramid + Golden Spiral)
    QUALITY_DESKTOP_QUALITY   = 4  // 16 Passes (Full 1:1 Native Neural-Optical Hierarchy)
};

inline const char* getQualityPresetName(int preset) {
    switch (preset) {
        case QUALITY_ULTRA_PERFORMANCE: return "Ultra Performance (1:1 Fused Neural Pass)";
        case QUALITY_PERFORMANCE:       return "Performance (1:1 4-Pass Neural)";
        case QUALITY_BALANCED:          return "Balanced (1:1 7-Pass Hierarchical)";
        case QUALITY_HIGH_QUALITY:      return "High Quality (1:1 11-Pass Golden Spiral)";
        case QUALITY_DESKTOP_QUALITY:   return "Desktop Max (Full 16-Pass Neural-Optical Hierarchy)";
        default:                        return "Unknown Preset";
    }
}

class ApexEngine {
public:
    static ApexEngine& getInstance();

    // Lifecycle & Dimensions
    void init(int width, int height);
    void updateDimensions(int width, int height);
    void destroy();

    // Pacing & Timing
    void onFrameCaptured(int64_t nowNanos, bool isActualNewFrame);
    float getInterpolationFactor(int64_t nowNanos);
    bool isRenderingGeneratedFrame() const;
    void setPendingRealFrame(bool pending);

    // Frame Execution
    void processFrame(GLuint inputTextureId, GLuint outputFboId, int width, int height);

    // Atomic Settings (Thread-Safe UI Controls)
    void setActive(bool isEnabled);
    bool isActive() const;

    void setQualityPreset(int quality);
    int getQualityPreset() const;

    void setTargetFPS(int fps);
    int getTargetFPS() const;

    void setShutterGain(float gain);
    float getShutterGain() const;

    void setFlowScale(float scale);
    float getFlowScale() const;

    // Telemetry & FPS Counters
    int getActualRealFrameCount();
    int getGeneratedFrameCount();
    int getAutoMultiplier() const;
    float getTypicalDeltaNanos() const;

private:
    ApexEngine();
    ~ApexEngine();

    ApexEngine(const ApexEngine&) = delete;
    ApexEngine& operator=(const ApexEngine&) = delete;

    // GPU Resource Management
    void ensureResources(int width, int height);
    void cleanupResources();
    void compileShaders();

    // Compute & Warping Dispatches
    void runComputePipeline(GLuint currTex, GLuint prevTex, int width, int height);
    void runWarpingPass(GLuint currTex, GLuint prevTex, GLuint mvTex, GLuint outputFboId, float factor, int width, int height);
    void logHeartbeatIfDue(int64_t nowNanos);

    // Atomic State & UI Configurations
    std::atomic<bool> mActive{false};
    std::atomic<int> mQualityPreset{QUALITY_PERFORMANCE};
    std::atomic<int> mTargetFPS{60};
    std::atomic<float> mShutterGain{0.5f};
    std::atomic<float> mFlowScale{1.0f};

    // Pacing & Delta State
    std::atomic<bool> mPendingRealFrame{false};
    std::atomic<bool> mRenderingGeneratedFrame{false};
    std::atomic<int> mAutoMultiplier{2};
    std::atomic<float> mAutoMultiplierVal{2.0f};
    std::atomic<int> mFramesSinceReal{0};
    std::atomic<int> mRealFramesCaptured{0};
    std::atomic<int64_t> mLastRealFrameTimeNanos{0};

    // 8-Sample Sliding-Window Median Filter
    std::array<float, 8> mDeltaHistory{};
    std::array<float, 8> mSortedHistory{};
    int mHistoryIndex{0};
    float mTypicalDeltaNanos{33333334.0f}; // Default 30 FPS guess (33.3ms)

    // Raw Factor Telemetry Tracking
    float mLastFactor{0.5f};
    float mMinFactor{1.0f};
    float mMaxFactor{0.0f};

    // Telemetry Counters
    std::atomic<int> mActualRealFrameCount{0};
    std::atomic<int> mGeneratedFrameCount{0};
    int64_t mLastHeartbeatTimeNanos{0};
    int mHeartbeatRealFrames{0};
    int mHeartbeatGenFrames{0};

    // Surface & Texture Dimensions
    int mSurfaceWidth{0};
    int mSurfaceHeight{0};
    int mMvWidth{0};
    int mMvHeight{0};
    bool mInitialized{false};

    // GPU Textures & FBOs
    GLuint mCurrentCapturedTexture{0};
    GLuint mPreviousCapturedTexture{0};
    GLuint mMotionVectorTexture{0};
    GLuint mMvHistoryTexture{0};
    GLuint mCaptureFbo{0};

    // Intermediate Multi-Pass Storage Textures (GL_RGBA16F - 16 Full Neural-Optical Passes)
    std::array<GLuint, 16> mDesktopPassTextures{};

    // Quad VAO & VBO
    GLuint mQuadVao{0};
    GLuint mQuadVbo{0};

    // Shader Programs
    GLuint mComputeProgramFused{0};   // Quality 0 (Ultra Perf)
    GLuint mComputeProgramMulti{0};   // Quality 1-4 (Multi-Pass)
    GLuint mWarpingProgram{0};        // Warping & Inpainting Fragment Program
};

} // namespace apex
