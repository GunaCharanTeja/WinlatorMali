package com.winlator.cmod.perf;

import android.os.Process;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Non-root thread-priority boost for guest worker processes & app worker threads.
 *
 * Rules:
 * 1. Never downgrades already-hot threads (such as UI/render threads at nice <= -10).
 * 2. Targets threads with headroom: guest CPU workers (Box64/Wine/proot children at nice 0)
 *    and audio/worker threads.
 * 3. Exact restoration of original nice values on exit.
 */
public class PerfPriority {
    private static final String TAG = "PerfPriority";

    private static final int[] BOOST_LADDER = new int[]{
        Process.THREAD_PRIORITY_URGENT_DISPLAY, // -8
        Process.THREAD_PRIORITY_DISPLAY,        // -4
        Process.THREAD_PRIORITY_FOREGROUND      // -2
    };

    private static final String[] APP_THREAD_TOKENS = new String[]{
        "audio", "worker", "render", "present", "vk", "gl", "box", "fex", "pulse"
    };

    private static final ConcurrentHashMap<Integer, Integer> originalNice = new ConcurrentHashMap<>();

    public static int boost(int guestRootPid) {
        int count = 0;
        for (int tid : collectAppThreadTids()) {
            if (boostTid(tid)) count++;
        }
        if (guestRootPid > 0) {
            for (int tid : collectGuestTids(guestRootPid)) {
                if (boostTid(tid)) count++;
            }
        }
        Log.d(TAG, "Priority boost: upgraded " + count + " thread(s) (guestRootPid=" + guestRootPid + ")");
        return count;
    }

    public static int restore() {
        int count = 0;
        for (Map.Entry<Integer, Integer> entry : originalNice.entrySet()) {
            try {
                Process.setThreadPriority(entry.getKey(), entry.getValue());
                count++;
            } catch (Throwable ignored) {}
        }
        Log.d(TAG, "Priority boost: restored " + count + " thread(s)");
        originalNice.clear();
        return count;
    }

    private static boolean boostTid(int tid) {
        int cur;
        try {
            cur = Process.getThreadPriority(tid);
        } catch (Throwable t) {
            return false;
        }

        for (int target : BOOST_LADDER) {
            if (target >= cur) continue; // target is equal or lower priority -> never downgrade

            try {
                if (!originalNice.containsKey(tid)) {
                    originalNice.put(tid, cur);
                }
                Process.setThreadPriority(tid, target);
                int after = Process.getThreadPriority(tid);
                if (after < cur) {
                    Log.d(TAG, "tid=" + tid + " nice " + cur + " -> " + after);
                    return true;
                }
                if (after == cur) {
                    originalNice.remove(tid);
                }
            } catch (Throwable t) {
                originalNice.remove(tid);
            }
        }
        return false;
    }

    private static List<Integer> collectAppThreadTids() {
        List<Integer> out = new ArrayList<>();
        File tasksDir = new File("/proc/self/task");
        File[] tasks = tasksDir.listFiles();
        if (tasks == null) return out;

        for (File task : tasks) {
            try {
                int tid = Integer.parseInt(task.getName());
                File commFile = new File(task, "comm");
                if (commFile.exists()) {
                    String comm = readFirstLine(commFile).toLowerCase().trim();
                    for (String token : APP_THREAD_TOKENS) {
                        if (comm.contains(token)) {
                            out.add(tid);
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        return out;
    }

    private static List<Integer> collectGuestTids(int rootPid) {
        List<Integer> tids = new ArrayList<>();
        File proc = new File("/proc");
        File[] procDirs = proc.listFiles(f -> f.isDirectory() && f.getName().matches("\\d+"));
        if (procDirs == null) return tids;

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
                        changed = true;
                    }
                }
            }
        }

        for (int pid : subtree) {
            File taskDir = new File("/proc/" + pid + "/task");
            File[] taskFiles = taskDir.listFiles();
            if (taskFiles != null) {
                for (File t : taskFiles) {
                    try {
                        tids.add(Integer.parseInt(t.getName()));
                    } catch (Throwable ignored) {}
                }
            }
        }
        return tids;
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
