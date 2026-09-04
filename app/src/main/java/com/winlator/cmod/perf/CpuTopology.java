package com.winlator.cmod.perf;

import android.util.Log;

import com.winlator.cmod.core.CPUStatus;
import com.winlator.cmod.core.ProcessHelper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * CPU Cluster topology detection and affinity manager.
 * Detects Prime / Big CPU cores by inspecting per-core max clock speeds from sysfs.
 */
public class CpuTopology {
    private static final String TAG = "CpuTopology";

    public static List<Integer> detectBigCoreIndices() {
        int count = Runtime.getRuntime().availableProcessors();
        if (count <= 0) return new ArrayList<>();

        int[] maxFreqs = new int[count];
        int maxFreq = 0;

        for (int i = 0; i < count; i++) {
            int freq = CPUStatus.getMaxClockSpeed(i);
            if (freq <= 0) {
                // Try direct read from sysfs if CPUStatus returned 0
                freq = readIntFromSysfs("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq");
                if (freq <= 0) {
                    freq = readIntFromSysfs("/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_max_freq");
                }
            }
            maxFreqs[i] = freq;
            if (freq > maxFreq) {
                maxFreq = freq;
            }
        }

        List<Integer> bigCores = new ArrayList<>();
        if (maxFreq > 0) {
            // Include cores matching the highest max frequency or within 10% of max
            int threshold = (int) (maxFreq * 0.90f);
            for (int i = 0; i < count; i++) {
                if (maxFreqs[i] >= threshold) {
                    bigCores.add(i);
                }
            }
        }

        // Fallback: If detection couldn't find distinct big cores, use upper half of cores
        if (bigCores.isEmpty() && count > 1) {
            for (int i = count / 2; i < count; i++) {
                bigCores.add(i);
            }
        }

        Log.d(TAG, "Detected Big Cores: " + bigCores + " (maxFreq=" + maxFreq + " MHz)");
        return bigCores;
    }

    public static String getBigCoreCpuList() {
        List<Integer> bigCores = detectBigCoreIndices();
        if (bigCores.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bigCores.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(bigCores.get(i));
        }
        return sb.toString();
    }

    public static int getBigCoreMask() {
        List<Integer> bigCores = detectBigCoreIndices();
        int mask = 0;
        for (int core : bigCores) {
            mask |= (1 << core);
        }
        return mask;
    }

    public static int applyBigCoreAffinity(int guestRootPid) {
        int mask = getBigCoreMask();
        if (mask == 0 || guestRootPid <= 0) return 0;

        int count = 0;
        if (ProcessHelper.setLinuxAffinity(guestRootPid, mask)) {
            count++;
        }

        // Apply to all child processes in the guest subtree
        List<Integer> childPids = collectGuestPids(guestRootPid);
        for (int pid : childPids) {
            if (ProcessHelper.setLinuxAffinity(pid, mask)) {
                count++;
            }
        }
        Log.d(TAG, "Applied Big-Core affinity (mask=0x" + Integer.toHexString(mask) + ") to " + count + " processes");
        return count;
    }

    private static List<Integer> collectGuestPids(int rootPid) {
        List<Integer> result = new ArrayList<>();
        File proc = new File("/proc");
        File[] procDirs = proc.listFiles(f -> f.isDirectory() && f.getName().matches("\\d+"));
        if (procDirs == null) return result;

        Map<Integer, Integer> ppidOf = new HashMap<>();
        List<Integer> pids = new ArrayList<>();

        for (File d : procDirs) {
            try {
                int pid = Integer.parseInt(d.getName());
                File statFile = new File(d, "stat");
                if (!statFile.exists()) continue;
                String stat = readFirstLine(statFile);
                int rp = stat.lastIndexOf(')');
                if (rp >= 0 && rp + 2 < stat.length()) {
                    String[] after = stat.substring(rp + 2).trim().split("\\s+");
                    if (after.length >= 2) {
                        int ppid = Integer.parseInt(after[1]);
                        ppidOf.put(pid, ppid);
                        pids.add(pid);
                    }
                }
            } catch (Throwable ignored) {}
        }

        HashSet<Integer> subtree = new HashSet<>();
        subtree.add(rootPid);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int pid : pids) {
                if (!subtree.contains(pid)) {
                    Integer pp = ppidOf.get(pid);
                    if (pp != null && subtree.contains(pp)) {
                        subtree.add(pid);
                        result.add(pid);
                        changed = true;
                    }
                }
            }
        }
        return result;
    }

    private static int readIntFromSysfs(String path) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line = reader.readLine();
            if (line != null) {
                return (int) (Long.parseLong(line.trim()) / 1000); // kHz -> MHz
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private static String readFirstLine(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line = reader.readLine();
            return line != null ? line : "";
        } catch (Throwable t) {
            return "";
        }
    }
}
