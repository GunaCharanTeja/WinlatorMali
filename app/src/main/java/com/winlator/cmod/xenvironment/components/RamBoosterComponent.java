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
    private boolean smartNeedHammer = false;

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

    private boolean isToastEnabled() {
        if (shortcut != null) {
            String val = shortcut.getExtra("ramBoosterToastEnabled");
            if (!val.isEmpty()) return val.equals("1");
        }
        return container.isRamBoosterToastEnabled();
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
        if (profile.equals("manual")) {
            if (shortcut != null) {
                String val = shortcut.getExtra("ramBoosterCrisisThreshold");
                if (!val.isEmpty()) return Integer.parseInt(val);
            }
            return container.getRamBoosterCrisisThreshold();
        }

        boolean isAdreno = com.winlator.cmod.core.GPUInformation.isAdrenoGPU(environment.getContext());
        int hardCap = isAdreno ? 94 : 91; 

        switch (profile) {
            case "low": return Math.min(94, hardCap);
            case "medium": return Math.min(90, hardCap);
            case "high": return Math.min(86, hardCap);
            case "aggressive": return Math.min(82, hardCap);
            case "max": return Math.min(78, hardCap);
            default: return Math.min(smartCrisisThreshold, hardCap);
        }
    }

    private int getPreCrisisThreshold() {
        String profile = getProfile();
        if (profile.equals("manual")) {
            if (shortcut != null) {
                String val = shortcut.getExtra("ramBoosterPreCrisisThreshold");
                if (!val.isEmpty()) return Integer.parseInt(val);
            }
            return container.getRamBoosterPreCrisisThreshold();
        }

        boolean isAdreno = com.winlator.cmod.core.GPUInformation.isAdrenoGPU(environment.getContext());
        int hardCap = isAdreno ? 89 : 86;

        switch (profile) {
            case "low": return Math.min(88, hardCap);
            case "medium": return Math.min(83, hardCap);
            case "high": return Math.min(80, hardCap);
            case "aggressive": return Math.min(75, hardCap);
            case "max": return Math.min(70, hardCap);
            default: return Math.min(smartPreCrisisThreshold, hardCap);
        }
    }

    private double getCrisisIntensity() {
        String profile = getProfile();
        if (profile.equals("manual")) {
            if (shortcut != null) {
                String val = shortcut.getExtra("ramBoosterCrisisIntensity");
                if (!val.isEmpty()) return Double.parseDouble(val);
            }
            return container.getRamBoosterCrisisIntensity();
        }
        return 45.0; 
    }

    private double getPreCrisisIntensity() {
        String profile = getProfile();
        if (profile.equals("manual")) {
            if (shortcut != null) {
                String val = shortcut.getExtra("ramBoosterPreCrisisIntensity");
                if (!val.isEmpty()) return Double.parseDouble(val);
            }
            return container.getRamBoosterPreCrisisIntensity();
        }
        return 20.0;
    }

    @Override
    public void start() {
        if (!isEnabled()) return;

        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleWithFixedDelay(this::checkMemory, 5, 2, TimeUnit.SECONDS);
        Log.i(TAG, "RamBooster started. Profile: " + getProfile());

        double initialIntensity = getProfile().equals("manual") ? getCrisisIntensity() : 35.0;
        triggerBoost(initialIntensity); 
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
        boolean isAdreno = com.winlator.cmod.core.GPUInformation.isAdrenoGPU(environment.getContext());
        
        if (totalGB <= 4) {
            smartCrisisThreshold = isAdreno ? 86 : 84;
            smartPreCrisisThreshold = 78;
        } else if (totalGB <= 8) {
            smartCrisisThreshold = isAdreno ? 90 : 86;
            smartPreCrisisThreshold = 82;
        } else {
            smartCrisisThreshold = isAdreno ? 93 : 89;
            smartPreCrisisThreshold = 84;
        }

        // SMART V2: If last boost freed < 300MB, escalate to Hammer next time
        if (lastBoostGain > 0 && lastBoostGain < (300 * 1024 * 1024)) { 
            smartCrisisThreshold -= 1;
            smartPreCrisisThreshold -= 2;
            smartNeedHammer = true;
            Log.d(TAG, "Smart Auto: Esclating to Hammer profile for next boost.");
        } else {
            smartNeedHammer = false;
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

        usageHistory.add(currentUsage);
        if (usageHistory.size() > HISTORY_SIZE) usageHistory.removeFirst();

        if (getProfile().equals("smart")) {
            updateSmartThresholds(totalMem);
        }

        if (now <= saturationBackoff) return;

        int thresholdCrisis = getCrisisThreshold();
        int preCrisisLevel = getPreCrisisThreshold();

        // Proactive Trend Alert V2 (More sensitive)
        if (usageHistory.size() >= 2) {
            int recentDelta = currentUsage - usageHistory.getLast();
            if (recentDelta >= 3 && currentUsage > (preCrisisLevel - 8)) {
                Log.i(TAG, "Intelligence Alert: Rapid usage growth detected. Throttling background.");
                triggerBoost(30.0);
                return;
            }
        }

        if (currentUsage < thresholdCrisis) crisisStreak = 0;

        if (currentUsage >= thresholdCrisis && (now - lastBoostTick > 30000) && (now > crisisBackoff)) {
            crisisStreak++;
            if (crisisStreak >= 2) {
                crisisBackoff = now + 120000;
                crisisStreak = 0;
                Log.w(TAG, "CRISIS 2x consecutive. Backing off.");
                return;
            }

            Log.w(TAG, "CRISIS! RAM " + currentUsage + "%. Triggering boost.");
            lastBoostTick = now;
            triggerBoost(getCrisisIntensity());
            return;
        }

        if (now <= crisisBackoff) return;

        if (preCrisisLevel > 0 && currentUsage >= preCrisisLevel && currentUsage < thresholdCrisis && (now - lastPreCrisisTick > 60000)) {
            lastPreCrisisTick = now;
            lastBoostTick = now;
            Log.i(TAG, "Pre-Crisis! RAM " + currentUsage + "%. Triggering boost.");
            triggerBoost(getPreCrisisIntensity());
        }
    }

    private void triggerBoost(double percentage) {
        if (isBoosting.compareAndSet(false, true)) {
            if (hud != null && hud.isUserEnabled()) hud.setRamBoosterStatus("Boosting...");
            if (isToastEnabled()) {
                String profile = getProfile();
                String msg = "RAM Booster: Boosting...";
                if (profile.equals("manual")) {
                    msg = String.format(java.util.Locale.US, "RAM Booster: Manual Pulse (%.0f%% Intensity)", percentage);
                }
                com.winlator.cmod.core.AppUtils.showToast(environment.getContext(), msg);
            }

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

                    boolean isManual = getProfile().equals("manual");
                    double floorPct = isManual ? 0.05 : (com.winlator.cmod.core.GPUInformation.isAdrenoGPU(context) ? 0.10 : 0.13);
                    long safetyFloor = (long) (totalMem * floorPct);

                    long targetBytes = (long) (totalMem * (percentage / 100.0));
                    long maxSafe = availBefore - safetyFloor;
                    
                    if (maxSafe < (50 * 1024 * 1024)) {
                        targetBytes = 150 * 1024 * 1024; // Increased rescue poke to 150MB
                    } else if (targetBytes > maxSafe) {
                        targetBytes = maxSafe;
                    }

                    long absoluteCap = isManual ? 6144L * 1024 * 1024 : 1536L * 1024 * 1024;
                    if (targetBytes > absoluteCap) targetBytes = absoluteCap;

                    int chunkSleep = com.winlator.cmod.core.GPUInformation.isAdrenoGPU(context) ? 25 : 35;
                    
                    // Smart escalates hold time if needed
                    int holdMs = (isManual || getProfile().equals("max") || (getProfile().equals("smart") && smartNeedHammer)) ? 7000 : 3500;
                    
                    Log.d(TAG, "Native Pressure: " + (targetBytes / (1024*1024)) + "MB for " + holdMs + "ms");
                    RamBooster.pressure(targetBytes, chunkSleep, holdMs);

                    Thread.sleep(2500);

                    activityManager.getMemoryInfo(memoryInfo);
                    lastBoostGain = memoryInfo.availMem - availBefore;
                    long gainMB = lastBoostGain / (1024 * 1024);
                    
                    if (gainMB > 15) {
                        saturatedCounter = 0;
                        Log.i(TAG, "Boost Result: Freed " + gainMB + "MB");
                        String gainStr = "+" + gainMB + "MB";
                        if (hud != null && hud.isUserEnabled()) {
                            hud.setRamBoosterStatus(gainStr);
                            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                                if (hud != null) hud.setRamBoosterStatus("");
                            }, 5000);
                        }
                        if (isToastEnabled()) {
                            com.winlator.cmod.core.AppUtils.showToast(environment.getContext(), "RAM Booster: " + gainStr);
                        }
                    } else {
                        saturatedCounter++;
                        if (saturatedCounter >= 4) { // Slightly more patient saturation logic
                            saturationBackoff = System.currentTimeMillis() + 300000;
                            if (isToastEnabled()) {
                                com.winlator.cmod.core.AppUtils.showToast(environment.getContext(), "RAM Booster: Max Reached (Saturated)");
                            }
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
