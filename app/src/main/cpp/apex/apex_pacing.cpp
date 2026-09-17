#include "apex_engine.h"

namespace apex {

constexpr float DIS_MIN_GEN_RATIO = 1.25f;
constexpr float DIS_RATIO_HYST    = 0.05f;
constexpr float DIS_RATIO_SLACK   = 0.10f;

void ApexEngine::onFrameCaptured(int64_t nowNanos, bool isActualNewFrame) {
    if (!mActive.load(std::memory_order_relaxed)) return;
    if (!isActualNewFrame) return; // WinNative DIS only tracks real source frames!

    int64_t lastTime = mLastRealFrameTimeNanos.load(std::memory_order_acquire);
    if (lastTime > 0) {
        float delta = static_cast<float>(nowNanos - lastTime);

        // Outlier rejection (>500ms hitch/loading filter)
        if (delta > 1000000.0f && delta < 500000000.0f) {
            mDeltaHistory[mHistoryIdx] = delta;
            mHistoryIdx = (mHistoryIdx + 1) % mDeltaHistory.size();

            std::copy(mDeltaHistory.begin(), mDeltaHistory.end(), mSortedHistory.begin());
            std::sort(mSortedHistory.begin(), mSortedHistory.end());
            float medianDelta = mSortedHistory[mSortedHistory.size() / 2];

            // WinNative DIS EWMA rate tracking (15% smoothing)
            if (mTypicalDeltaNanos == 33333334.0f) {
                mTypicalDeltaNanos = medianDelta;
            } else {
                mTypicalDeltaNanos += (medianDelta - mTypicalDeltaNanos) * 0.15f;
            }
        }
    }

    mLastRealFrameTimeNanos.store(nowNanos, std::memory_order_release);

    // --- Auto-Multiplier Planning: Proactively reach and maintain Target FPS ---
    int target = mTargetFPS.load(std::memory_order_acquire);
    float desiredFps = (target > 0) ? static_cast<float>(target) : 60.0f;

    if (mSmoothedDesired < 1.0f) mSmoothedDesired = desiredFps;
    mSmoothedDesired += (desiredFps - mSmoothedDesired) * 0.25f;

    float sourceFps = (mTypicalDeltaNanos > 1000000.0f) ? (1000000000.0f / mTypicalDeltaNanos) : 30.0f;
    float ratio = mSmoothedDesired / std::max(1.0f, sourceFps);

    int currentGen = mPlannedGen;
    int proposedGen = 0;

    // Use a slightly more aggressive threshold to ensure we hit the target FPS
    float threshold = (currentGen > 0) ? (DIS_MIN_GEN_RATIO - DIS_RATIO_HYST) : DIS_MIN_GEN_RATIO;
    if (ratio >= threshold) {
        // Calculate required multiplier to reach target
        // if source is 40 and target is 60, ratio is 1.5. ceil(1.5 - 0.1) = 2. proposedGen = 1 (2x)
        int outputs = static_cast<int>(std::ceil(ratio - DIS_RATIO_SLACK));
        proposedGen = std::clamp(outputs - 1, 1, 3);
    }

    // --- TrackCost GPU Backpressure Governor & Recovery ---
    if (nowNanos >= mHoldUntilNanos && mCostLimit < 3) {
        mCostLimit = 3;
    }

    if (proposedGen > currentGen) {
        if (nowNanos < mHoldUntilNanos || proposedGen > mCostLimit) {
            proposedGen = std::min(proposedGen, mCostLimit);
        }
    }

    // Detect GPU Saturation (Allow 20% drop for Mali-G615 headroom)
    if (currentGen > 1 && mDeltaAtRaise > 0.0f && mTypicalDeltaNanos > mDeltaAtRaise * 1.20f) {
        if (mDropSinceNanos == 0) {
            mDropSinceNanos = nowNanos;
        } else if (nowNanos - mDropSinceNanos >= 2000000000LL) { // 2.0s persistence for stability
            mCostLimit = std::max(1, currentGen - 1);
            mHoldUntilNanos = nowNanos + 5000000000LL; // 5.0s hold
            proposedGen = mCostLimit;
            mDropSinceNanos = 0;
            mDeltaAtRaise = mTypicalDeltaNanos;
        }
    } else {
        mDropSinceNanos = 0;
    }

    // Asymmetric Streak Debouncing: 2 frames to step UP, 3 frames to step DOWN
    if (proposedGen > currentGen) {
        mGenHighStreak++;
        mGenLowStreak = 0;
        if (mGenHighStreak >= 2) {
            mPlannedGen = proposedGen;
            mGenHighStreak = 0;
            mDeltaAtRaise = mTypicalDeltaNanos;
            mLastCostChangeNanos = nowNanos;
        }
    } else if (proposedGen < currentGen) {
        mGenLowStreak++;
        mGenHighStreak = 0;
        if (mGenLowStreak >= 3) {
            mPlannedGen = proposedGen;
            mGenLowStreak = 0;
            mDeltaAtRaise = mTypicalDeltaNanos;
            mLastCostChangeNanos = nowNanos;
        }
    } else {
        mGenHighStreak = 0;
        mGenLowStreak = 0;
    }

    // --- Liquid-Multiplier Smoothing: Ultra-Stable (0.05 weight) ---
    float multiplier = (mPlannedGen > 0) ? static_cast<float>(mPlannedGen + 1) : 1.0f;
    float currentMult = mAutoMultiplierVal.load(std::memory_order_acquire);

    // Slower transition for "infinite" buttery feel
    float nextMult = currentMult + (multiplier - currentMult) * 0.05f;
    mAutoMultiplierVal.store(nextMult, std::memory_order_release);
    mAutoMultiplier.store(static_cast<int>(std::round(nextMult)), std::memory_order_release);
}

float ApexEngine::getInterpolationFactor(int64_t nowNanos) {
    if (!mActive.load(std::memory_order_relaxed)) return 0.0f;
    if (mRealFramesCaptured.load(std::memory_order_relaxed) < 2) return 0.5f;

    int framesSince = mFramesSinceReal.load(std::memory_order_acquire);
    float mult = std::max(2.0f, (float)mAutoMultiplier.load(std::memory_order_acquire));

    // Restore Linear Timing: Essential for game motion consistency
    float factor = static_cast<float>(framesSince) / mult;

    int64_t lastRealTime = mLastRealFrameTimeNanos.load(std::memory_order_acquire);
    if (lastRealTime > 0 && mTypicalDeltaNanos > 1000000.0f) {
        float elapsedNanos = static_cast<float>(nowNanos - lastRealTime);
        float continuousPhase = elapsedNanos / mTypicalDeltaNanos;

        // 90% strict timing, 10% continuous drift for the "Original" buttery feel
        factor = factor * 0.90f + std::clamp(continuousPhase / mult, 0.0f, 1.0f) * 0.10f;
    }

    return std::clamp(factor, 0.01f, 0.99f);
}

} // namespace apex
