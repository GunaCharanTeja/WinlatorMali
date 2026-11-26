package com.winlator.cmod.xenvironment.components;

import android.app.ActivityManager;
import android.content.Context;
import android.util.Log;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.core.RamBooster;
import com.winlator.cmod.widget.WinlatorHUD;
import com.winlator.cmod.xenvironment.EnvironmentComponent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
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

    private static class SystemMemSnapshot {
        final long totalMem;
        final long availMem;
        final long swapTotal;
        final long swapFree;

        SystemMemSnapshot(long totalMem, long availMem, long swapTotal, long swapFree) {
            this.totalMem = totalMem;
            this.availMem = availMem;
            this.swapTotal = swapTotal;
            this.swapFree = swapFree;
        }
    }

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

        boolean isSmart = getProfile().equals("smart");
        if (isSmart) {
            Log.i(TAG, "Smart Auto: Executing Startup Pre-Flush to vacate background apps into zRAM.");
            triggerBoost(35.0, 2000);
        } else {
            double initialIntensity = getProfile().equals("manual") ? getCrisisIntensity() : 35.0;
            triggerBoost(initialIntensity, -1);
        }
    }

    @Override
    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private static SystemMemSnapshot readMemInfo(Context context) {
        long total = 0, avail = 0, swapTotal = 0, swapFree = 0;
        File meminfo = new File("/proc/meminfo");
        if (meminfo.exists()) {
            try (BufferedReader br = new BufferedReader(new FileReader(meminfo))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("MemTotal:")) {
                        total = parseMemLineKb(line) * 1024L;
                    } else if (line.startsWith("MemAvailable:")) {
                        avail = parseMemLineKb(line) * 1024L;
                    } else if (line.startsWith("SwapTotal:")) {
                        swapTotal = parseMemLineKb(line) * 1024L;
                    } else if (line.startsWith("SwapFree:")) {
                        swapFree = parseMemLineKb(line) * 1024L;
                    }
                }
            } catch (Exception ignored) {}
        }

        if (total <= 0 || avail <= 0) {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                if (total <= 0) total = mi.totalMem;
                if (avail <= 0) avail = mi.availMem;
            }
        }
        return new SystemMemSnapshot(total, avail, swapTotal, swapFree);
    }

    private static long parseMemLineKb(String line) {
        try {
            int colon = line.indexOf(':');
            if (colon >= 0) {
                String rest = line.substring(colon + 1).trim();
                int space = rest.indexOf(' ');
                String num = space > 0 ? rest.substring(0, space) : rest;
                return Long.parseLong(num);
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private static long getProcessRssBytes(int pid) {
        if (pid <= 0) return 0;
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/" + pid + "/statm"))) {
            String line = reader.readLine();
            if (line != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 2) {
                    long residentPages = Long.parseLong(parts[1]);
                    return residentPages * 4096L;
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private static long getAppAndGameMemoryBytes() {
        long totalRss = getProcessRssBytes(android.os.Process.myPid());
        int guestPid = GuestProgramLauncherComponent.getPid();
        if (guestPid > 0) {
            totalRss += getProcessRssBytes(guestPid);
            try (BufferedReader reader = new BufferedReader(new FileReader("/proc/" + guestPid + "/task/" + guestPid + "/children"))) {
                String line = reader.readLine();
                if (line != null) {
                    for (String childPidStr : line.trim().split("\\s+")) {
                        if (!childPidStr.isEmpty()) {
                            try {
                                totalRss += getProcessRssBytes(Integer.parseInt(childPidStr));
                            } catch (Exception ignored) {}
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        return totalRss;
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
            Log.d(TAG, "Smart Auto: Escalating to Hammer profile for next boost.");
        } else {
            smartNeedHammer = false;
        }
    }

    private void checkMemory() {
        if (isBoosting.get()) return;

        Context context = environment.getContext();
        SystemMemSnapshot mem = readMemInfo(context);
        if (mem.totalMem <= 0) return;

        int currentUsage = (int) ((mem.totalMem - mem.availMem) * 100 / mem.totalMem);
        long now = System.currentTimeMillis();

        usageHistory.add(currentUsage);
        if (usageHistory.size() > HISTORY_SIZE) usageHistory.removeFirst();

        boolean isSmart = getProfile().equals("smart");
        if (isSmart) {
            updateSmartThresholds(mem.totalMem);
        }

        if (now <= saturationBackoff) return;

        int thresholdCrisis = getCrisisThreshold();
        int preCrisisLevel = getPreCrisisThreshold();

        // Self-awareness: differentiate game memory from external bloatware
        long gameAndAppRss = 0;
        long reclaimableBloat = 0;
        boolean gameDominates = false;
        boolean hasLargeSwapCushion = false;

        if (isSmart) {
            gameAndAppRss = getAppAndGameMemoryBytes();
            long totalUsed = mem.totalMem - mem.availMem;
            long backgroundRam = Math.max(0, totalUsed - gameAndAppRss);
            long essentialOs = 700L * 1024 * 1024; // SystemServer, SurfaceFlinger, Audio, Input
            reclaimableBloat = Math.max(0, backgroundRam - essentialOs);
            gameDominates = (gameAndAppRss > (mem.totalMem * 0.65)) || (reclaimableBloat < 350L * 1024 * 1024);
            hasLargeSwapCushion = (mem.swapFree > 1500L * 1024 * 1024);
        }

        // 1. Proactive Trend Alert (Spike Detection)
        if (usageHistory.size() >= 2) {
            int recentDelta = currentUsage - usageHistory.get(usageHistory.size() - 2);
            if (recentDelta >= 3 && currentUsage > (preCrisisLevel - 8)) {
                if (isSmart && gameDominates) {
                    Log.d(TAG, "Smart Auto: Spike (+" + recentDelta + "%) detected from game asset streaming. Suppressing boost to preserve smooth loading.");
                } else {
                    Log.i(TAG, "Intelligence Alert: Rapid usage growth detected (+" + recentDelta + "%). Proactively trimming background bloat.");
                    triggerBoost(30.0, 2000);
                    return;
                }
            }
        }

        if (currentUsage < thresholdCrisis) crisisStreak = 0;

        // 2. Crisis Handling
        if (currentUsage >= thresholdCrisis && (now - lastBoostTick > 30000) && (now > crisisBackoff)) {
            if (isSmart && gameDominates) {
                if (hasLargeSwapCushion) {
                    Log.d(TAG, "Smart Auto: RAM is " + currentUsage + "% but game owns " + (gameAndAppRss / (1024 * 1024)) + "MB with " + (mem.swapFree / (1024 * 1024)) + "MB free swap cushion. Suppressing boost to avoid lag.");
                    return;
                }

                // Defcon 1 Crisis: Both physical RAM AND swap are critically low
                boolean swapCriticallyLow = (mem.swapTotal > 0 && mem.swapFree < 400L * 1024 * 1024);
                if (mem.availMem < 250L * 1024 * 1024 && swapCriticallyLow) {
                    Log.w(TAG, "Smart Auto: DEFCON 1 CRISIS! Both RAM & Swap near exhaustion. Emergency survival pulse.");
                    lastBoostTick = now;
                    triggerBoost(15.0, 1200);
                    return;
                } else {
                    Log.d(TAG, "Smart Auto: High RAM usage, but game legitimately owns memory and bloat is minimal. Suppressing to prevent suicide.");
                    return;
                }
            }

            crisisStreak++;
            if (crisisStreak >= 2) {
                crisisBackoff = now + 120000;
                crisisStreak = 0;
                Log.w(TAG, "CRISIS 2x consecutive. Backing off.");
                return;
            }

            Log.w(TAG, "CRISIS! RAM " + currentUsage + "%. Triggering aggressive boost.");
            lastBoostTick = now;
            triggerBoost(getCrisisIntensity(), -1);
            return;
        }

        if (now <= crisisBackoff) return;

        // 3. Pre-Crisis Handling
        if (preCrisisLevel > 0 && currentUsage >= preCrisisLevel && currentUsage < thresholdCrisis && (now - lastPreCrisisTick > 60000)) {
            if (isSmart && gameDominates) {
                Log.d(TAG, "Smart Auto: Pre-Crisis reached, but game owns memory. Suppressing background trim.");
                return;
            }
            lastPreCrisisTick = now;
            lastBoostTick = now;
            Log.i(TAG, "Pre-Crisis! RAM " + currentUsage + "%. Triggering background trim.");
            triggerBoost(getPreCrisisIntensity(), 2500);
        }
    }

    private void triggerBoost(double percentage) {
        triggerBoost(percentage, -1);
    }

    private void triggerBoost(double percentage, int holdMsOverride) {
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
                    SystemMemSnapshot mem = readMemInfo(context);
                    if (mem.totalMem <= 0) {
                        isBoosting.set(false);
                        return;
                    }
                    
                    long totalMem = mem.totalMem;
                    long availBefore = mem.availMem;

                    boolean isManual = getProfile().equals("manual");
                    double floorPct = isManual ? 0.05 : (com.winlator.cmod.core.GPUInformation.isAdrenoGPU(context) ? 0.10 : 0.13);
                    long safetyFloor = (long) (totalMem * floorPct);

                    long targetBytes = (long) (totalMem * (percentage / 100.0));
                    long maxSafe = availBefore - safetyFloor;
                    
                    if (maxSafe < (50 * 1024 * 1024)) {
                        // Safe micro-pulse when free RAM is thin, avoiding suicidal over-allocation
                        targetBytes = Math.min(80L * 1024 * 1024, Math.max(25L * 1024 * 1024, availBefore / 3));
                    } else if (targetBytes > maxSafe) {
                        targetBytes = maxSafe;
                    }

                    long absoluteCap = isManual ? 6144L * 1024 * 1024 : 1536L * 1024 * 1024;
                    if (targetBytes > absoluteCap) targetBytes = absoluteCap;

                    int chunkSleep = com.winlator.cmod.core.GPUInformation.isAdrenoGPU(context) ? 25 : 35;
                    
                    int holdMs;
                    if (holdMsOverride > 0) {
                        holdMs = holdMsOverride;
                    } else {
                        holdMs = (isManual || getProfile().equals("max") || (getProfile().equals("smart") && smartNeedHammer)) ? 7000 : 3500;
                    }
                    
                    Log.d(TAG, "Native Pressure: " + (targetBytes / (1024 * 1024)) + "MB for " + holdMs + "ms");
                    RamBooster.pressure(targetBytes, chunkSleep, holdMs);

                    Thread.sleep(2500);

                    SystemMemSnapshot memAfter = readMemInfo(context);
                    lastBoostGain = memAfter.availMem - availBefore;
                    long gainMB = lastBoostGain / (1024 * 1024);
                    int currentUsage = (int) ((memAfter.totalMem - memAfter.availMem) * 100 / memAfter.totalMem);
                    
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
                        // Fast saturation detection: if RAM is high and boost yielded nothing, sleep immediately
                        if (saturatedCounter >= 4 || (currentUsage >= 88 && saturatedCounter >= 2)) {
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
