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

    // Aggressive Target FPS logic: use ceil to ensure we reach the target
    if (target > 0 && sourceFps >= static_cast<float>(target) - 2.0f) {
        proposedGen = 0; // Native game FPS already hits or exceeds Target FPS: bypass generation
    } else {
        proposedGen = std::max(1, (int)std::ceil(ratio - 0.1f) - 1);
        proposedGen = std::clamp(proposedGen, 1, 3);
    }

    // Always allow max multiplier if needed to hit target
    mCostLimit = 3;

    // Asymmetric Streak Debouncing: Immediate step UP, very slow step DOWN
    if (proposedGen > currentGen) {
        mGenHighStreak++;
        mGenLowStreak = 0;
        if (mGenHighStreak >= 1) { // React instantly to performance drops
            mPlannedGen = proposedGen;
            mGenHighStreak = 0;
            mDeltaAtRaise = mTypicalDeltaNanos;
            mLastCostChangeNanos = nowNanos;
            if (mLoggingEnabled.load(std::memory_order_relaxed)) {
                APEX_LOGI("ApexDIS Multiplier stepped UP to %dx (Source: %.1f FPS, Target: %d FPS)",
                          mPlannedGen + 1, sourceFps, target);
            }
        }
    } else if (proposedGen < currentGen) {
        mGenLowStreak++;
        mGenHighStreak = 0;
        if (mGenLowStreak >= 30) { // Very sticky high multiplier (approx 1s at 30fps) - stay high!
            mPlannedGen = proposedGen;
            mGenLowStreak = 0;
            mDeltaAtRaise = mTypicalDeltaNanos;
            mLastCostChangeNanos = nowNanos;
            if (mLoggingEnabled.load(std::memory_order_relaxed)) {
                APEX_LOGI("ApexDIS Multiplier stepped DOWN to %dx (Source: %.1f FPS, Target: %d FPS)",
                          mPlannedGen + 1, sourceFps, target);
            }
        }
    } else {
        mGenHighStreak = 0;
        mGenLowStreak = 0;
    }

    // Direct and responsive multiplier tracking
    float multiplier = (mPlannedGen > 0) ? static_cast<float>(mPlannedGen + 1) : 1.0f;
    float currentMult = mAutoMultiplierVal.load(std::memory_order_acquire);
    float nextMult = currentMult + (multiplier - currentMult) * 0.50f;
    mAutoMultiplierVal.store(nextMult, std::memory_order_release);
    mAutoMultiplier.store(static_cast<int>(std::round(multiplier)), std::memory_order_release);
}

float ApexEngine::getInterpolationFactor(int64_t nowNanos) {
    if (!mActive.load(std::memory_order_relaxed)) return 0.0f;
    if (mRealFramesCaptured.load(std::memory_order_relaxed) < 2) return 0.5f;

    int framesSince = mFramesSinceReal.load(std::memory_order_acquire);
    float mult = std::max(2.0f, (float)mAutoMultiplier.load(std::memory_order_acquire));

    // Subframe phase: uniform distribution across generated frames
    // For 2x (mult=2): framesSince=0 -> (0+1)/2 = 0.50 (true midpoint)
    // For 3x (mult=3): framesSince=0 -> 0.33, framesSince=1 -> 0.67
    float factor = static_cast<float>(framesSince + 1) / mult;

    int64_t lastRealTime = mLastRealFrameTimeNanos.load(std::memory_order_acquire);
    if (lastRealTime > 0 && mTypicalDeltaNanos > 1000000.0f) {
        float elapsedNanos = static_cast<float>(nowNanos - lastRealTime);
        float continuousPhase = elapsedNanos / mTypicalDeltaNanos;

        // 90% strict timing, 10% continuous drift
        factor = factor * 0.90f + std::clamp(continuousPhase / mult, 0.0f, 1.0f) * 0.10f;
    }

    return std::clamp(factor, 0.01f, 0.99f);
}

} // namespace apex
