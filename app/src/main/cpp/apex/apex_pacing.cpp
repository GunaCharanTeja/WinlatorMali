#include "apex_engine.h"

namespace apex {

void ApexEngine::setActive(bool isEnabled) {
    bool prev = mActive.exchange(isEnabled, std::memory_order_acq_rel);
    if (prev != isEnabled) {
        APEX_LOGI("ApexEngine::setActive(%s) - Previous state: %s",
                  isEnabled ? "TRUE" : "FALSE", prev ? "TRUE" : "FALSE");
    }
    if (!isEnabled) {
        mRenderingGeneratedFrame.store(false, std::memory_order_release);
        mPendingRealFrame.store(false, std::memory_order_release);
        mRealFramesCaptured.store(0, std::memory_order_release);
        mFramesSinceReal.store(0, std::memory_order_release);
        mLastHeartbeatTimeNanos = 0;
        mHeartbeatRealFrames = 0;
        mHeartbeatGenFrames = 0;
        mMinFactor = 1.0f;
        mMaxFactor = 0.0f;
    }
}

bool ApexEngine::isActive() const {
    return mActive.load(std::memory_order_acquire);
}

void ApexEngine::setQualityPreset(int quality) {
    int clamped = std::clamp(quality, 0, 4);
    int prev = mQualityPreset.exchange(clamped, std::memory_order_acq_rel);
    if (prev != clamped) {
        APEX_LOGI("ApexEngine::setQualityPreset(%d) -> %s (was: %s)",
                  clamped, getQualityPresetName(clamped), getQualityPresetName(prev));
    }
}

int ApexEngine::getQualityPreset() const {
    return mQualityPreset.load(std::memory_order_acquire);
}

void ApexEngine::setTargetFPS(int fps) {
    int prev = mTargetFPS.exchange(fps, std::memory_order_acq_rel);
    if (prev != fps) {
        APEX_LOGI("ApexEngine::setTargetFPS(%d) - was: %d", fps, prev);
    }
}

int ApexEngine::getTargetFPS() const {
    return mTargetFPS.load(std::memory_order_acquire);
}

void ApexEngine::setShutterGain(float gain) {
    float clamped = std::clamp(gain, 0.0f, 1.0f);
    float prev = mShutterGain.exchange(clamped, std::memory_order_acq_rel);
    if (std::abs(prev - clamped) > 0.001f) {
        APEX_LOGI("ApexEngine::setShutterGain(%.2f) - was: %.2f", clamped, prev);
    }
}

float ApexEngine::getShutterGain() const {
    return mShutterGain.load(std::memory_order_acquire);
}

void ApexEngine::setFlowScale(float scale) {
    float clamped = std::clamp(scale, 0.1f, 1.0f);
    float prev = mFlowScale.exchange(clamped, std::memory_order_acq_rel);
    if (std::abs(prev - clamped) > 0.001f) {
        APEX_LOGI("ApexEngine::setFlowScale(%.2f) - was: %.2f", clamped, prev);
    }
}

float ApexEngine::getFlowScale() const {
    return mFlowScale.load(std::memory_order_acquire);
}

void ApexEngine::setPendingRealFrame(bool pending) {
    mPendingRealFrame.store(pending, std::memory_order_release);
}

bool ApexEngine::isRenderingGeneratedFrame() const {
    return mRenderingGeneratedFrame.load(std::memory_order_acquire);
}

int ApexEngine::getActualRealFrameCount() {
    return mActualRealFrameCount.exchange(0, std::memory_order_acq_rel);
}

int ApexEngine::getGeneratedFrameCount() {
    return mGeneratedFrameCount.exchange(0, std::memory_order_acq_rel);
}

int ApexEngine::getAutoMultiplier() const {
    return mAutoMultiplier.load(std::memory_order_acquire);
}

float ApexEngine::getTypicalDeltaNanos() const {
    return mTypicalDeltaNanos;
}

void ApexEngine::onFrameCaptured(int64_t nowNanos, bool isActualNewFrame) {
    if (!mActive.load(std::memory_order_relaxed)) return;

    if (isActualNewFrame) {
        mActualRealFrameCount.fetch_add(1, std::memory_order_relaxed);
    }

    mRealFramesCaptured.fetch_add(1, std::memory_order_relaxed);
    mFramesSinceReal.store(0, std::memory_order_release);

    int64_t lastTime = mLastRealFrameTimeNanos.load(std::memory_order_acquire);
    if ((isActualNewFrame || lastTime > 0) && lastTime > 0) {
        float delta = static_cast<float>(nowNanos - lastTime);

        // Outlier rejection (>300ms loading hitch filter)
        if (delta > 1000000.0f && delta < 300000000.0f) {
            mDeltaHistory[mHistoryIndex] = delta;
            mHistoryIndex = (mHistoryIndex + 1) % mDeltaHistory.size();

            std::copy(mDeltaHistory.begin(), mDeltaHistory.end(), mSortedHistory.begin());
            std::sort(mSortedHistory.begin(), mSortedHistory.end());
            float medianDelta = mSortedHistory[mSortedHistory.size() / 2];

            // Low-Inertia EMA: 40% historical memory, 60% new median
            if (mTypicalDeltaNanos == 33333334.0f) {
                mTypicalDeltaNanos = medianDelta;
            } else {
                mTypicalDeltaNanos = mTypicalDeltaNanos * 0.40f + medianDelta * 0.60f;
            }
        } else if (delta >= 300000000.0f) {
            APEX_LOGD("Pacing: Rejected loading hitch / outlier frame delta (%.1f ms)", delta / 1000000.0f);
        }

        // Low-FPS Stabilization & Anti-Stutter Engine:
        // Level 1: Extreme Low-FPS (<15 FPS, delta > 66.6ms) -> Lock rigidly to 2x integer pacing
        // Level 2: Sub-30 FPS (15-30 FPS, delta 33.3ms - 66.6ms) -> Adaptive 2x/3x integer stabilization
        // Level 3: Normal/High FPS (>30 FPS) -> Dynamic rate matching
        if (mTypicalDeltaNanos >= 66666666.0f) {
            // Sub-15 FPS: Strictly lock to 2x with zero cadence jitter
            mAutoMultiplier.store(2, std::memory_order_release);
            mAutoMultiplierVal.store(2.0f, std::memory_order_release);
        } else if (mTypicalDeltaNanos >= 33333333.0f) {
            // 15 - 30 FPS: Lock to stable 2x/3x cadence
            int target = mTargetFPS.load(std::memory_order_acquire);
            if (target >= 90) {
                mAutoMultiplier.store(3, std::memory_order_release);
                mAutoMultiplierVal.store(3.0f, std::memory_order_release);
            } else {
                mAutoMultiplier.store(2, std::memory_order_release);
                mAutoMultiplierVal.store(2.0f, std::memory_order_release);
            }
        } else {
            int target = mTargetFPS.load(std::memory_order_acquire);
            if (target > 0) {
                float targetIntervalNanos = 1000000000.0f / static_cast<float>(target);
                if (mTypicalDeltaNanos <= targetIntervalNanos * 1.05f) {
                    mAutoMultiplier.store(1, std::memory_order_release);
                    mAutoMultiplierVal.store(mTypicalDeltaNanos / targetIntervalNanos, std::memory_order_release);
                } else {
                    float val = mTypicalDeltaNanos / targetIntervalNanos;
                    mAutoMultiplierVal.store(val, std::memory_order_release);
                    int mult = std::clamp(static_cast<int>(std::ceil(val)), 2, 4);
                    mAutoMultiplier.store(mult, std::memory_order_release);
                }
            } else {
                // Unlimited Dynamic High-Refresh Mode
                int mult = (mTypicalDeltaNanos > 25000000.0f) ? 3 : 2;
                mAutoMultiplier.store(mult, std::memory_order_release);
                mAutoMultiplierVal.store(static_cast<float>(mult), std::memory_order_release);
            }
        }
    }

    if (isActualNewFrame || lastTime == 0) {
        mLastRealFrameTimeNanos.store(nowNanos, std::memory_order_release);
    }
}

float ApexEngine::getInterpolationFactor(int64_t nowNanos) {
    if (!mActive.load(std::memory_order_relaxed)) return 0.0f;
    if (mRealFramesCaptured.load(std::memory_order_relaxed) < 2) return 0.5f;

    int framesSince = mFramesSinceReal.load(std::memory_order_acquire);
    int mult = std::max(2, mAutoMultiplier.load(std::memory_order_acquire));

    // Mathematical Spline Midpoint Lock:
    // Even if game frametimes fluctuate wildly (e.g. 18ms -> 45ms -> 22ms),
    // intermediate generated frames are presented at exact mathematical midpoints
    // (e.g. 0.50 for 2x, 0.33 / 0.66 for 3x), completely decoupling presentation from game render stutter.
    float discretePhase = static_cast<float>(std::clamp(framesSince, 1, mult - 1)) / static_cast<float>(mult);

    float factor = std::clamp(discretePhase, 0.05f, 0.95f);

    mLastFactor = factor;
    mMinFactor = std::min(mMinFactor, factor);
    mMaxFactor = std::max(mMaxFactor, factor);

    return factor;
}

} // namespace apex
