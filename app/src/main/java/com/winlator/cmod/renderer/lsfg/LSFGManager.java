package com.winlator.cmod.renderer.lsfg;

import com.winlator.cmod.renderer.GLRenderer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

public class LSFGManager {
    private final GLRenderer renderer;
    private boolean active = false;
    private long lastRealFrameTimeNanos = 0;
    private float smoothedFrameDeltaNanos = 33333334f;
    private volatile boolean pendingRealFrame = false;
    private final AtomicInteger generatedFrameCount = new AtomicInteger(0);
    private final AtomicInteger actualRealFrameCount = new AtomicInteger(0);
    private final AtomicInteger gameFrameCount = new AtomicInteger(0);
    private int realFramesCaptured = 0;
    
    private int autoMultiplier = 2;
    private float autoMultiplierVal = 2.0f;
    private int framesSinceReal = 0;

    private final float[] deltaHistory = new float[8];
    private int historyIndex = 0;
    private float typicalDeltaNanos = 33333334f;
    private int targetFPS = 60;
    
    private boolean lastWasGenerated = false;
    private boolean renderingGeneratedFrame = false;

    public LSFGManager(GLRenderer renderer) {
        this.renderer = renderer;
        Arrays.fill(deltaHistory, 33333334f);
    }

    public void setEnabled(boolean enabled) {
        if (this.active == enabled) return;
        this.active = enabled;
        // Dynamically toggle RENDERMODE_CONTINUOUSLY for GL thread rate-limiting
        renderer.xServerView.post(() -> renderer.xServerView.setApexMode(enabled));
        if (enabled) {
            realFramesCaptured = 0;
            framesSinceReal = 0;
            typicalDeltaNanos = 33333334f;
            lastRealFrameTimeNanos = 0;
            autoMultiplier = 2;
            autoMultiplierVal = 2.0f;
            historyIndex = 0;
            pendingRealFrame = false;
            Arrays.fill(deltaHistory, 33333334f);
            lastWasGenerated = false;
            renderingGeneratedFrame = false;
            renderer.startChoreographer();
        } else {
            pendingRealFrame = false;
            renderer.stopChoreographer();
        }
    }

    public boolean isActive() {
        return active;
    }

    public void setMultiplier(int multiplier) {}
    public int getMultiplier() { return autoMultiplier; }
    public void setTargetFPS(int targetFPS) { this.targetFPS = targetFPS; }
    public int getTargetFPS() { return targetFPS; }
    public boolean isPendingRealFrame() { return pendingRealFrame; }

    public void notifyRealFramePending() {
        this.pendingRealFrame = true;
        this.gameFrameCount.incrementAndGet();
        renderer.xServerView.requestRender();
    }

    public boolean prepareFrame() {
        if (!active || realFramesCaptured < 2) {
            renderingGeneratedFrame = false;
            return false;
        }
        if (targetFPS > 0) {
            // Locked Target FPS Mode: Decouple from autoMultiplier limits to keep target FPS constant.
            // If the game FPS is higher than target FPS (autoMultiplierVal < 1.0f), bypass interpolation.
            if (autoMultiplierVal < 1.0f) {
                renderingGeneratedFrame = false;
                return false;
            }
            renderingGeneratedFrame = !pendingRealFrame;
        } else {
            // Unlimited Mode: Use autoMultiplier cadence limits.
            if (autoMultiplier <= 1) {
                renderingGeneratedFrame = false;
                return false;
            }
            if (pendingRealFrame) {
                renderingGeneratedFrame = false;
            } else if (framesSinceReal >= (autoMultiplier - 1)) {
                renderingGeneratedFrame = false;
            } else {
                renderingGeneratedFrame = true;
            }
        }
        return renderingGeneratedFrame;
    }

    public boolean isRenderingGeneratedFrame() {
        return renderingGeneratedFrame;
    }

    public boolean isGeneratedFrame() {
        return renderingGeneratedFrame;
    }

    public float getInterpolationFactor() {
        if (!active || realFramesCaptured < 2) return 0;
        if (!renderingGeneratedFrame) return 0.0f;
        if (targetFPS > 0) {
            // Decoupled cadence-based interpolation with upper clamp for late frames using fractional multiplier
            return Math.min(0.99f, (float)(framesSinceReal + 1) / autoMultiplierVal);
        } else {
            return (float)(framesSinceReal + 1) / (float)autoMultiplier;
        }
    }

    public void onFrameCaptured() {
        boolean isActualNewFrame = pendingRealFrame;
        if (pendingRealFrame) {
            actualRealFrameCount.incrementAndGet();
            pendingRealFrame = false;
        }
        
        realFramesCaptured++;
        framesSinceReal = 0;
        long now = System.nanoTime();
        
        if (isActualNewFrame && lastRealFrameTimeNanos > 0) {
            float delta = (now - lastRealFrameTimeNanos);
            deltaHistory[historyIndex] = delta;
            historyIndex = (historyIndex + 1) % deltaHistory.length;
            float[] sorted = deltaHistory.clone();
            Arrays.sort(sorted);
            float medianDelta = sorted[sorted.length / 2];

            // Exponential Moving Average (EMA) with 0.85 smoothing factor to eliminate jitter
            if (typicalDeltaNanos == 33333334f) {
                typicalDeltaNanos = medianDelta;
            } else {
                typicalDeltaNanos = typicalDeltaNanos * 0.85f + medianDelta * 0.15f;
            }

            if (targetFPS > 0) {
                // If game FPS exceeds target FPS, bypass interpolation (using a tight 1.05x margin)
                if (typicalDeltaNanos <= (1000000000.0f / targetFPS) * 1.05f) {
                    autoMultiplier = 1; // Bypass
                    autoMultiplierVal = (float) typicalDeltaNanos / (1000000000.0f / targetFPS);
                } else {
                    autoMultiplierVal = (float) typicalDeltaNanos / (1000000000.0f / targetFPS);
                    autoMultiplier = Math.max(2, (int) Math.ceil(autoMultiplierVal));
                }
            } else {
                // Hysteresis threshold logic to prevent multiplier oscillation stutters (Unlimited mode)
                int targetMultiplier = autoMultiplier;
                if (targetMultiplier == 2) {
                    if (typicalDeltaNanos > 38000000f) {
                        targetMultiplier = 3;
                    }
                } else if (targetMultiplier == 3) {
                    if (typicalDeltaNanos < 30000000f) {
                        targetMultiplier = 2;
                    } else if (typicalDeltaNanos > 72000000f) {
                        targetMultiplier = 4;
                    }
                } else if (targetMultiplier == 4) {
                    if (typicalDeltaNanos < 62000000f) {
                        targetMultiplier = 3;
                    }
                }
                autoMultiplier = targetMultiplier;
                autoMultiplierVal = (float) autoMultiplier;
            }
        }
        
        if (isActualNewFrame) {
            lastRealFrameTimeNanos = now;
        }
    }

    public void onPostDraw() {
        if (!active) return;
        if (renderingGeneratedFrame) {
            generatedFrameCount.incrementAndGet();
            framesSinceReal++;
        }
    }

    public boolean shouldRender() { return true; }
    public int getRealFramesCaptured() { return realFramesCaptured; }
    public long getFrameDeltaMs() { return (long) (typicalDeltaNanos / 1000000.0f); }
    public long getTypicalDeltaNanos() { return (long) typicalDeltaNanos; }
    public int getActualRealFrameCount() { return actualRealFrameCount.get(); }
    public int getGameFrameCount() { return gameFrameCount.get(); }
    public int getGeneratedFrameCount() { return generatedFrameCount.get(); }
    public void resetFrameCounts() {
        actualRealFrameCount.set(0);
        generatedFrameCount.set(0);
        gameFrameCount.set(0);
    }
}
