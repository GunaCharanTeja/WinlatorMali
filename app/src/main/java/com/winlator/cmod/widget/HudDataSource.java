package com.winlator.cmod.widget;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;

import com.winlator.cmod.core.CPUStatus;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.concurrent.atomic.AtomicInteger;

public class HudDataSource {
    public final AtomicInteger gpuLoad       = new AtomicInteger(-1);
    public final AtomicInteger cpuLoad       = new AtomicInteger(-1);
    public final AtomicInteger batteryMw     = new AtomicInteger(-1);
    public final AtomicInteger batteryTempC  = new AtomicInteger(-1);
    public final AtomicInteger cpuTempC      = new AtomicInteger(-1);
    public final AtomicInteger batteryPct    = new AtomicInteger(-1);
    public final AtomicInteger ramUsagePct  = new AtomicInteger(-1);

    private HandlerThread thread;
    private Handler handler;
    private final Context context;
    private final BatteryManager batteryManager;

    private String gpuPath = null;
    private boolean gpuFailed = false;
    private long prevGpuBusy = 0, prevGpuTotal = 0;
    private long lastMaliGpuInfoMs = -1;
    private long lastMaliGpuInfoWallMs = -1;
    private java.util.List<String> discoveredCpuTempPaths = null;

    private static final String[] GPU_PATHS = {
        "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
        "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
        "/sys/class/kgsl/kgsl-3d0/devfreq/adrenoboost",
        "/sys/class/misc/mali0/device/utilisation",
        "/sys/class/misc/mali0/device/gpuinfo",
        "/sys/devices/platform/mali/utilization",
        "/sys/devices/platform/13000000.mali/utilization",
        "/sys/module/mali_kbase/parameters/mali_gpu_utilization",
        "/sys/module/mali/parameters/mali_gpu_utilization",
        "/proc/mali/utilization",
        "/sys/kernel/gpu/gpu_busy",
        "/sys/module/ged/parameters/gpu_loading",
        "/sys/class/devfreq/mtk-dvfs-gpu/gpu_loading",
        "/sys/class/devfreq/gpufreq/gpu_load",
        "/sys/class/devfreq/gpu/load",
        "/sys/class/misc/pvrsrvkm/device/utilisation",
        "/proc/gpufreq/gpufreq_power_dump",
    };

    private static final String[] CPU_TEMP_PATHS = {
        "/sys/class/thermal/thermal_zone7/temp", // SD8 Gen 2/3
        "/sys/class/thermal/thermal_zone0/temp", // Common MTK / Generic
        "/sys/class/thermal/thermal_zone1/temp",
        "/proc/mtktscpu/mtktscpu",               // Legacy MediaTek
        "/sys/class/thermal/cpu-thermal/temp"
    };

    private static final String GPU_BUSY = "/sys/class/kgsl/kgsl-3d0/gpubusy";

    private static final String[] CURRENT_PATHS = {
        "/sys/class/power_supply/battery/current_now",
        "/sys/class/power_supply/bms/current_now",
        "/sys/class/power_supply/maxfg/current_now",
        "/sys/class/power_supply/maxfg/ibat_now"
    };

    private static final String[] VOLTAGE_PATHS = {
        "/sys/class/power_supply/battery/voltage_now",
        "/sys/class/power_supply/bms/voltage_now",
        "/sys/class/power_supply/maxfg/voltage_now",
        "/sys/class/power_supply/maxfg/vbat_now"
    };

    public HudDataSource(Context context) {
        this.context = context.getApplicationContext();
        this.batteryManager = (BatteryManager) this.context.getSystemService(Context.BATTERY_SERVICE);
        thread = new HandlerThread("WinlatorHUD", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    public void start() {
        if (!thread.isAlive()) {
            thread = new HandlerThread("WinlatorHUD", android.os.Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            handler = new Handler(thread.getLooper());
            gpuFailed = false;
            gpuPath = null;
        }
        handler.removeCallbacksAndMessages(null);
        handler.post(this::poll);
    }

    public void stop() {
        handler.removeCallbacksAndMessages(null);
        thread.quitSafely();
    }

    private void poll() {
        pollGpu();
        pollCpu();
        pollCpuTemp();
        pollBattery();
        pollRam();
        handler.postDelayed(this::poll, 1500);
    }

    private void pollGpu() {
        if (gpuFailed) return;

        if (gpuPath != null) {
            int v = gpuPath.equals(GPU_BUSY) ? readGpuBusy() : (gpuPath.endsWith("gpuinfo") ? readGpuInfo(gpuPath) : readPercent(gpuPath));
            if (v >= 0) { gpuLoad.set(v); return; }
            gpuPath = null;
        }

        String path = findGpuPath();
        if (path != null) {
            gpuPath = path;
            int v = path.equals(GPU_BUSY) ? readGpuBusy() : (path.endsWith("gpuinfo") ? readGpuInfo(path) : readPercent(path));
            gpuLoad.set(v >= 0 ? v : 0);
            return;
        }

        int v = readGpuBusy();
        if (v >= 0) { gpuPath = GPU_BUSY; gpuLoad.set(v); return; }

        gpuFailed = true;
        gpuLoad.set(-1);
    }

    private String findGpuPath() {
        for (String p : GPU_PATHS) {
            File f = new File(p);
            if (f.exists() && f.canRead()) {
                if (p.endsWith("gpubusy") || p.endsWith("gpuinfo")) return p;
                if (readPercent(p) >= 0) return p;
            }
        }

        try {
            File devfreqDir = new File("/sys/class/devfreq");
            if (devfreqDir.isDirectory()) {
                File[] subdirs = devfreqDir.listFiles();
                if (subdirs != null) {
                    for (File dir : subdirs) {
                        if (dir.isDirectory()) {
                            String[] candidates = {"gpu_load", "gpu_loading", "load", "percent", "utilisation", "utilization", "gpuinfo"};
                            for (String name : candidates) {
                                File f = new File(dir, name);
                                if (f.isFile() && f.canRead()) {
                                    if (name.equals("gpuinfo")) return f.getPath();
                                    if (readPercent(f.getPath()) >= 0) return f.getPath();
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        try {
            File platformDir = new File("/sys/devices/platform");
            if (platformDir.isDirectory()) {
                File[] subdirs = platformDir.listFiles();
                if (subdirs != null) {
                    for (File dir : subdirs) {
                        String nameLower = dir.getName().toLowerCase();
                        if (dir.isDirectory() && (nameLower.contains("mali") || nameLower.contains("gpu") || nameLower.contains("kgsl"))) {
                            String[] candidates = {"utilization", "utilisation", "gpu_load", "gpu_loading", "load", "percent", "gpuinfo"};
                            for (String name : candidates) {
                                File f = new File(dir, name);
                                if (f.isFile() && f.canRead()) {
                                    if (name.equals("gpuinfo")) return f.getPath();
                                    if (readPercent(f.getPath()) >= 0) return f.getPath();
                                }
                            }
                            
                            File[] subFiles = dir.listFiles();
                            if (subFiles != null) {
                                for (File sub : subFiles) {
                                    if (sub.isDirectory()) {
                                        for (String sname : candidates) {
                                            File f = new File(sub, sname);
                                            if (f.isFile() && f.canRead()) {
                                                if (sname.equals("gpuinfo")) return f.getPath();
                                                if (readPercent(f.getPath()) >= 0) return f.getPath();
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    private int readInt(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String l = r.readLine();
            if (l == null) return -1;
            return Integer.parseInt(l.trim().replaceAll("[^0-9]", ""));
        } catch (Exception e) { return -1; }
    }

    private int readPercent(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String l = r.readLine();
            if (l == null) return -1;
            int val = Integer.parseInt(l.trim().replaceAll("[^0-9]", ""));
            return (val >= 0 && val <= 100) ? val : -1;
        } catch (Exception e) { return -1; }
    }

    private int readGpuBusy() {
        try (BufferedReader r = new BufferedReader(new FileReader(GPU_BUSY))) {
            String l = r.readLine();
            if (l == null) return -1;
            String[] p = l.trim().split("\\s+");
            if (p.length < 2) return -1;
            long busy = Long.parseLong(p[0]), total = Long.parseLong(p[1]);
            long dB = busy - prevGpuBusy, dT = total - prevGpuTotal;
            prevGpuBusy = busy; prevGpuTotal = total;
            return dT > 0 ? (int) Math.min(100, dB * 100L / dT) : 0;
        } catch (Exception e) { return -1; }
    }

    private void pollCpu() {
        try {
            short[] clocks = CPUStatus.getCurrentClockSpeeds();
            long cur = 0, max = 0;
            for (int i = 0; i < clocks.length; i++) {
                cur += clocks[i];
                max += CPUStatus.getMaxClockSpeed(i);
            }
            cpuLoad.set(max > 0 ? (int) Math.min(100, cur * 100L / max) : -1);
        } catch (Exception e) {
            cpuLoad.set(-1);
        }
    }

    private void pollCpuTemp() {
        for (String p : discoverCpuTempPaths()) {
            int v = readInt(p);
            if (v > 0) {
                if (v > 5000) v /= 1000;
                else if (v > 200) v /= 10;
                
                if (v > 0 && v < 120) {
                    cpuTempC.set(v);
                    return;
                }
            }
        }

        for (String p : CPU_TEMP_PATHS) {
            int v = readInt(p);
            if (v > 0) {
                // Scaling detection:
                // Values like 45000 are millidegrees (divide by 1000)
                // Values like 450 are degrees * 10 (divide by 10)
                // Values like 45 are just degrees
                if (v > 5000) v /= 1000;
                else if (v > 200) v /= 10;
                
                if (v > 0 && v < 120) { // Basic sanity check for valid CPU temp
                    cpuTempC.set(v);
                    return;
                }
            }
        }
        cpuTempC.set(-1);
    }

    private void pollBattery() {
        try {
            Intent batt = context.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (batt == null) return;

            int temp = batt.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
            batteryTempC.set(temp > 0 ? temp / 10 : -1);

            int pct = batt.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            batteryPct.set(pct);

            int status = batt.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                            || status == BatteryManager.BATTERY_STATUS_FULL;
            if (charging) {
                batteryMw.set(-2);
            } else {
                int mv = batt.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
                if (mv <= 0) {
                    long uv = firstNonZero(VOLTAGE_PATHS);
                    if (uv > 0) mv = (int) (uv / 1000L);
                }

                long uA = 0;
                if (batteryManager != null)
                    uA = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                if (uA == Long.MIN_VALUE) uA = 0;
                if (uA == 0) uA = firstNonZero(CURRENT_PATHS);

                if (mv > 0 && uA != 0) {
                    long mw = Math.abs(uA) * mv / 1_000_000L;
                    batteryMw.set(mv > 5000 ? (int) (mw * 2) : (int) mw);
                } else {
                    batteryMw.set(-1);
                }
            }
        } catch (Exception ignored) {}
    }

    private void pollRam() {
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.FileReader("/proc/meminfo"))) {
            long memTotal = -1, memAvail = -1;
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("MemTotal:"))     memTotal = parseMeminfoKb(line);
                else if (line.startsWith("MemAvailable:")) { memAvail = parseMeminfoKb(line); break; }
            }
            if (memTotal > 0 && memAvail >= 0)
                ramUsagePct.set((int)(100L * (memTotal - memAvail) / memTotal));
            else ramUsagePct.set(-1);
        } catch (Exception e) { ramUsagePct.set(-1); }
    }

    private long parseMeminfoKb(String line) {
        try {
            String[] parts = line.trim().split("\\s+");
            return Long.parseLong(parts[1]);
        } catch (Exception e) { return -1; }
    }


    private long firstNonZero(String[] paths) {
        for (String path : paths) {
            long v = readSysFsLong(path);
            if (v != 0) return v;
        }
        return 0;
    }

    private long readSysFsLong(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String l = r.readLine();
            return l != null ? Long.parseLong(l.trim()) : 0;
        } catch (Exception e) { return 0; }
    }

    private String readSysFsString(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String l = r.readLine();
            return l != null ? l : "";
        } catch (Exception e) { return ""; }
    }

    private int readGpuInfo(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            r.readLine(); // skip line 0
            String line = r.readLine(); // line 1
            if (line == null) return -1;
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 0) return -1;
            long gpuMs = Long.parseLong(parts[parts.length - 1]);
            long now = android.os.SystemClock.elapsedRealtime();
            long prevMs = lastMaliGpuInfoMs;
            long prevWall = lastMaliGpuInfoWallMs;
            lastMaliGpuInfoMs = gpuMs;
            lastMaliGpuInfoWallMs = now;
            if (prevMs < 0 || prevWall <= 0) return -1;
            long wallDelta = now - prevWall;
            if (wallDelta <= 0) return -1;
            long gpuDelta = gpuMs - prevMs;
            if (gpuDelta < 0) gpuDelta = 0;
            return (int) Math.min(100, (gpuDelta * 100L) / wallDelta);
        } catch (Exception e) { return -1; }
    }

    private java.util.List<String> discoverCpuTempPaths() {
        if (discoveredCpuTempPaths != null) return discoveredCpuTempPaths;
        
        java.util.List<CpuTempCandidate> candidates = new java.util.ArrayList<>();
        String[] roots = {"/sys/class/thermal", "/sys/devices/virtual/thermal"};
        
        for (String root : roots) {
            File dir = new File(root);
            if (!dir.exists() || !dir.isDirectory()) continue;
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File file : files) {
                if (file.isDirectory() && file.getName().startsWith("thermal_zone")) {
                    File typeFile = new File(file, "type");
                    File tempFile = new File(file, "temp");
                    if (typeFile.exists() && tempFile.exists() && tempFile.canRead()) {
                        String type = readSysFsString(typeFile.getPath()).trim().toLowerCase(java.util.Locale.US);
                        int rank = getCpuTempRank(type);
                        if (rank >= 0) {
                            candidates.add(new CpuTempCandidate(tempFile.getPath(), rank));
                        }
                    }
                }
            }
        }
        
        java.util.Collections.sort(candidates, (c1, c2) -> Integer.compare(c1.rank, c2.rank));
        
        java.util.List<String> paths = new java.util.ArrayList<>();
        for (CpuTempCandidate c : candidates) {
            if (!paths.contains(c.path)) paths.add(c.path);
        }
        
        discoveredCpuTempPaths = paths;
        return paths;
    }
    
    private int getCpuTempRank(String type) {
        if (type.contains("gpu")) return -1;
        if (type.contains("cpu-silicon")) return 0;
        if (type.contains("cpu-0")) return 1;
        if (type.contains("cpu")) return 2;
        if (type.contains("soc")) return 3;
        if (type.contains("s5p-tmu")) return 4;
        if (type.contains("cputop")) return 5;
        if (type.contains("tsens")) return 6;
        if (type.contains("cluster")) return 7;
        if (type.contains("big") || type.contains("little")) return 8;
        return -1;
    }
    
    private static class CpuTempCandidate {
        final String path;
        final int rank;
        CpuTempCandidate(String path, int rank) {
            this.path = path;
            this.rank = rank;
        }
    }
}
