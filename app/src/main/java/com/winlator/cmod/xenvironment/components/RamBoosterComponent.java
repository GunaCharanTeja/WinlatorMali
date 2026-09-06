package com.winlator.cmod.xenvironment.components;

import android.app.ActivityManager;
import android.content.Context;
import android.util.Log;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.core.RamBooster;
import com.winlator.cmod.widget.WinlatorHUD;
import com.winlator.cmod.xenvironment.EnvironmentComponent;

import java.util.LinkedList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class RamBoosterComponent extends EnvironmentComponent {
    private static final String TAG = "RamBoosterComponent";
    private final Container container;
    private final Shortcut shortcut;
    private ScheduledExecutorService executor;
    private WinlatorHUD hud;
    private final AtomicBoolean isBoosting = new AtomicBoolean(false);
    private long lastBoostTick = 0;
    private long lastPreCrisisTick = 0;
    private int crisisStreak = 0;
    private long crisisBackoff = 0;

    // Smart Auto adaptive state
    private int smartCrisisThreshold = 90;
    private int smartPreCrisisThreshold = 83;
    private long lastBoostGain = 0;
    private long lastSmartCalcTime = 0;

    // Trend analysis
    private final LinkedList<Integer> usageHistory = new LinkedList<>();
    private static final int HISTORY_SIZE = 5;
    private int saturatedCounter = 0;
    private long saturationBackoff = 0;

    public RamBoosterComponent(Container container, Shortcut shortcut) {
        this.container = container;
        this.shortcut = shortcut;
        Log.d(TAG, "Initialized for container: " + container.getName());
    }

    public void setHUD(WinlatorHUD hud) {
        this.hud = hud;
    }

    private boolean isEnabled() {
        if (shortcut != null) {
            String val = shortcut.getExtra("ramBoosterEnabled");
            if (!val.isEmpty()) return val.equals("1");
        }
        return container.isRamBoosterEnabled();
    }

    private String getProfile() {
        if (shortcut != null) {
            String val = shortcut.getExtra("ramBoosterProfile");
            if (!val.isEmpty()) return val;
        }
        return container.getRamBoosterProfile();
    }

    private int getCrisisThreshold() {
        String profile = getProfile();
        switch (profile) {
            case "low": return 94;
            case "medium": return 90;
            case "high": return 86;
            case "aggressive": return 82;
            case "max": return 78;
            case "manual":
                if (shortcut != null) {
                    String val = shortcut.getExtra("ramBoosterCrisisThreshold");
                    if (!val.isEmpty()) return Integer.parseInt(val);
                }
                return container.getRamBoosterCrisisThreshold();
            default: return smartCrisisThreshold;
        }
    }

    private int getPreCrisisThreshold() {
        String profile = getProfile();
        switch (profile) {
            case "low": return 88;
            case "medium": return 83;
            case "high": return 80;
            case "aggressive": return 75;
            case "max": return 70;
            case "manual":
                if (shortcut != null) {
                    String val = shortcut.getExtra("ramBoosterPreCrisisThreshold");
                    if (!val.isEmpty()) return Integer.parseInt(val);
                }
                return container.getRamBoosterPreCrisisThreshold();
            default: return smartPreCrisisThreshold;
        }
    }

    @Override
    public void start() {
        if (!isEnabled()) return;

        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleWithFixedDelay(this::checkMemory, 5, 2, TimeUnit.SECONDS);
        Log.i(TAG, "RamBooster started. Profile: " + getProfile());

        triggerBoost(35.0); // Slightly more aggressive initial boost
    }

    @Override
    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void updateSmartThresholds(long totalMem) {
        long now = System.currentTimeMillis();
        if (now - lastSmartCalcTime < 60000) return; 
        lastSmartCalcTime = now;

        long totalGB = totalMem / (1024 * 1024 * 1024);
        
        if (totalGB <= 4) {
            smartCrisisThreshold = 86;
            smartPreCrisisThreshold = 78;
        } else if (totalGB <= 8) {
            smartCrisisThreshold = 90;
            smartPreCrisisThreshold = 83;
        } else {
            smartCrisisThreshold = 93; // Lowered from 94 for better headroom on flagships
            smartPreCrisisThreshold = 85;
        }

        // If last boost freed little, we are likely saturated or thresholds are too high
        if (lastBoostGain > 0 && lastBoostGain < (150 * 1024 * 1024)) { 
            smartCrisisThreshold -= 1;
            smartPreCrisisThreshold -= 2;
        }
    }

    private void checkMemory() {
        if (isBoosting.get()) return;

        Context context = environment.getContext();
        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager == null) return;
        
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);

        long totalMem = memoryInfo.totalMem;
        long availMem = memoryInfo.availMem;
        int currentUsage = (int) ((totalMem - availMem) * 100 / totalMem);
        long now = System.currentTimeMillis();

        // Update trend history
        usageHistory.add(currentUsage);
        if (usageHistory.size() > HISTORY_SIZE) usageHistory.removeFirst();

        if (getProfile().equals("smart")) {
            updateSmartThresholds(totalMem);
        }

        if (now <= saturationBackoff) return;

        int thresholdCrisis = getCrisisThreshold();
        int preCrisisLevel = getPreCrisisThreshold();

        // Proactive Trend Trigger: If usage jumped > 4% in last 4 seconds
        if (usageHistory.size() >= 3) {
            int recentDelta = currentUsage - usageHistory.get(usageHistory.size() - 3);
            if (recentDelta >= 4 && currentUsage > (preCrisisLevel - 5)) {
                Log.i(TAG, "Trend Alert: Sudden RAM spike (+" + recentDelta + "%). Triggering early boost.");
                triggerBoost(25.0);
                return;
            }
        }

        // Give Up Mechanism
        if (currentUsage < thresholdCrisis) crisisStreak = 0;

        if (currentUsage >= thresholdCrisis && (now - lastBoostTick > 30000) && (now > crisisBackoff)) {
            crisisStreak++;
            if (crisisStreak >= 2) {
                crisisBackoff = now + 120000;
                crisisStreak = 0;
                Log.w(TAG, "CRISIS 2x consecutive. Backing off for 2 minutes.");
                return;
            }

            Log.w(TAG, "CRISIS! RAM " + currentUsage + "%. Triggering 45% boost.");
            lastBoostTick = now;
            triggerBoost(45.0);
            return;
        }

        if (now <= crisisBackoff) return;

        if (preCrisisLevel > 0 && currentUsage >= preCrisisLevel && currentUsage < thresholdCrisis && (now - lastPreCrisisTick > 60000)) {
            lastPreCrisisTick = now;
            lastBoostTick = now;
            Log.i(TAG, "Pre-Crisis! RAM " + currentUsage + "%. Triggering 20% boost.");
            triggerBoost(20.0);
        }
    }

    private void triggerBoost(double percentage) {
        if (isBoosting.compareAndSet(false, true)) {
            if (hud != null && hud.isUserEnabled()) hud.setRamBoosterStatus("Boosting...");
            com.winlator.cmod.core.AppUtils.showToast(environment.getContext(), "RAM Booster: Boosting...");

            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    Context context = environment.getContext();
                    ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                    if (activityManager == null) {
                        isBoosting.set(false);
                        return;
                    }
                    
                    ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
                    activityManager.getMemoryInfo(memoryInfo);
                    
                    long totalMem = memoryInfo.totalMem;
                    long availBefore = memoryInfo.availMem;

                    boolean isAdreno = com.winlator.cmod.core.GPUInformation.isAdrenoGPU(context);
                    double floorPct = isAdreno ? 0.12 : 0.15;
                    long safetyFloor = (long) (totalMem * floorPct);

                    if (availBefore <= safetyFloor) {
                        isBoosting.set(false);
                        if (hud != null) hud.setRamBoosterStatus("");
                        return;
                    }

                    // For "Max" or "Aggressive" profile, we use a more intense pulse
                    String profile = getProfile();
                    int pulseIntensity = profile.equals("max") || profile.equals("aggressive") ? 2 : 1;
                    
                    long targetBytes = (long) (totalMem * (percentage / 100.0));
                    long maxSafe = availBefore - safetyFloor;
                    if (targetBytes > maxSafe) targetBytes = maxSafe;

                    long absoluteCap = 1536L * 1024 * 1024;
                    if (targetBytes > absoluteCap) targetBytes = absoluteCap;

                    int chunkSleep = isAdreno ? 25 : 35;
                    
                    // Native pressure call
                    RamBooster.pressure(targetBytes, chunkSleep);
                    
                    // If intense, pulse again briefly
                    if (pulseIntensity > 1 && (availBefore - safetyFloor > 200 * 1024 * 1024)) {
                        Thread.sleep(500);
                        RamBooster.pressure(200 * 1024 * 1024, chunkSleep);
                    }

                    // Settle period
                    Thread.sleep(2500);

                    activityManager.getMemoryInfo(memoryInfo);
                    lastBoostGain = memoryInfo.availMem - availBefore;
                    long gainMB = lastBoostGain / (1024 * 1024);
                    
                    if (gainMB > 10) {
                        saturatedCounter = 0;
                        Log.i(TAG, "Boost Result: Freed " + gainMB + "MB");
                        String gainStr = "+" + gainMB + "MB";
                        if (hud != null && hud.isUserEnabled()) {
                            hud.setRamBoosterStatus(gainStr);
                            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                                if (hud != null) hud.setRamBoosterStatus("");
                            }, 5000);
                        }
                        com.winlator.cmod.core.AppUtils.showToast(environment.getContext(), "RAM Booster: " + gainStr);
                    } else {
                        // System is saturated, nothing left to kill
                        saturatedCounter++;
                        if (saturatedCounter >= 3) {
                            saturationBackoff = System.currentTimeMillis() + 300000; // 5 min break
                            Log.i(TAG, "System saturated. Entering saturation backoff for 5 minutes.");
                            com.winlator.cmod.core.AppUtils.showToast(environment.getContext(), "RAM Booster: System Saturated (No apps to kill)");
                        }
                        if (hud != null) hud.setRamBoosterStatus("");
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Boost failed", e);
                    if (hud != null) hud.setRamBoosterStatus("");
                } finally {
                    isBoosting.set(false);
                }
            });
        }
    }
}
