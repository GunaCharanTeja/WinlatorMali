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

        // Low-FPS Stabilization Rule:
        // When baseline FPS < 20 FPS (delta > 50ms), clamp to stable 2x integer step
        if (mTypicalDeltaNanos >= 50000000.0f) {
            mAutoMultiplier.store(2, std::memory_order_release);
            mAutoMultiplierVal.store(2.0f, std::memory_order_release);
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
                // Unlimited Dynamic Mode
                int mult = 2;
                if (mTypicalDeltaNanos > 65000000.0f) mult = 2; // Locked to 2x for sub-15fps
                else if (mTypicalDeltaNanos > 35000000.0f) mult = 3;
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

    float discretePhase = static_cast<float>(std::clamp(framesSince, 1, mult - 1)) / static_cast<float>(mult);

    // Premium Liquid Sync: Always use the exact spatial midpoint (e.g. 0.5 for 2x, 0.33/0.66 for 3x).
    // This matches the behavior of DLSS 3 / FSR 3 and ensures perfectly continuous motion
    // even if the base game has minor timing jitter.
    float factor = discretePhase;

    factor = std::clamp(factor, 0.05f, 0.95f);

    mLastFactor = factor;
    mMinFactor = std::min(mMinFactor, factor);
    mMaxFactor = std::max(mMaxFactor, factor);

    return factor;
}

} // namespace apex
