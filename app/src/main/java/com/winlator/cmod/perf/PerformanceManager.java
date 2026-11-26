package com.winlator.cmod.perf;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;
import androidx.preference.PreferenceManager;

/**
 * Unified Performance Management Engine for Winlator Mali.
 * Coordinates all non-root and system performance enhancements.
 */
public class PerformanceManager {
    private static final String TAG = "PerformanceManager";

    public static final String PREF_GAME_MODE_SIGNAL = "perf_game_mode_signal";
    public static final String PREF_THREAD_PRIORITY_BOOST = "perf_thread_priority_boost";
    public static final String PREF_PREFER_BIG_CORES = "perf_prefer_big_cores";
    public static final String PREF_SUSTAINED_PERFORMANCE = "perf_sustained_performance";
    public static final String PREF_SAMSUNG_PERF_BOOST = "perf_samsung_boost";

    private static SamsungSPerfDriver samsungDriver = null;
    private static int currentGuestPid = -1;
    private static boolean isSessionActive = false;

    public static void init(Context context) {
        if (samsungDriver == null && context != null && SamsungSPerfDriver.isSamsungDevice()) {
            samsungDriver = new SamsungSPerfDriver(context);
        }
    }

    public static void onGameStart(Activity activity, int guestRootPid, SharedPreferences prefs) {
        if (activity == null) return;
        currentGuestPid = guestRootPid;
        isSessionActive = true;

        if (prefs == null) {
            prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        }

        init(activity);

        // 1. Android 13+ Game Mode Signal
        if (prefs.getBoolean(PREF_GAME_MODE_SIGNAL, true)) {
            GameModeSignal.enterGameplay(activity);
        }

        // 2. Sustained Performance Mode
        if (prefs.getBoolean(PREF_SUSTAINED_PERFORMANCE, false)) {
            setSustainedPerformance(activity, true);
        }

        // 3. Thread Priority Boost
        if (prefs.getBoolean(PREF_THREAD_PRIORITY_BOOST, true) && guestRootPid > 0) {
            PerfPriority.boost(guestRootPid);
        }

        // 4. Prefer Big Cores
        if (prefs.getBoolean(PREF_PREFER_BIG_CORES, false) && guestRootPid > 0) {
            CpuTopology.applyBigCoreAffinity(guestRootPid);
        }

        // 5. Samsung Galaxy Performance SDK Boost
        if (prefs.getBoolean(PREF_SAMSUNG_PERF_BOOST, true) && samsungDriver != null && samsungDriver.isSupported()) {
            samsungDriver.startBoost();
        }

        Log.d(TAG, "Performance profile applied on game start (pid=" + guestRootPid + ")");
    }

    public static void onGameResume(Activity activity, int guestRootPid) {
        if (activity == null) return;
        if (guestRootPid > 0) currentGuestPid = guestRootPid;
        isSessionActive = true;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);

        if (prefs.getBoolean(PREF_GAME_MODE_SIGNAL, true)) {
            GameModeSignal.enterGameplay(activity);
        }

        if (prefs.getBoolean(PREF_SUSTAINED_PERFORMANCE, false)) {
            setSustainedPerformance(activity, true);
        }

        if (prefs.getBoolean(PREF_THREAD_PRIORITY_BOOST, true) && currentGuestPid > 0) {
            PerfPriority.boost(currentGuestPid);
        }

        if (prefs.getBoolean(PREF_PREFER_BIG_CORES, false) && currentGuestPid > 0) {
            CpuTopology.applyBigCoreAffinity(currentGuestPid);
        }

        if (prefs.getBoolean(PREF_SAMSUNG_PERF_BOOST, true) && samsungDriver != null && samsungDriver.isSupported()) {
            samsungDriver.startBoost();
        }
    }

    public static void onGamePause(Activity activity) {
        if (activity == null) return;
        GameModeSignal.exitGameplay(activity);
        setSustainedPerformance(activity, false);
        if (samsungDriver != null && samsungDriver.isSupported()) {
            samsungDriver.stopBoost();
        }
    }

    public static void onGameStop(Activity activity) {
        if (activity == null) return;
        isSessionActive = false;
        GameModeSignal.exitGameplay(activity);
        setSustainedPerformance(activity, false);
        PerfPriority.restore();
        if (samsungDriver != null && samsungDriver.isSupported()) {
            samsungDriver.stopBoost();
        }
        currentGuestPid = -1;
        Log.d(TAG, "Performance profile restored on game stop");
    }

    public static void applySettingsLive(Activity activity, SharedPreferences prefs) {
        if (activity == null || !isSessionActive) return;
        if (prefs == null) prefs = PreferenceManager.getDefaultSharedPreferences(activity);

        if (prefs.getBoolean(PREF_GAME_MODE_SIGNAL, true)) {
            GameModeSignal.enterGameplay(activity);
        } else {
            GameModeSignal.exitGameplay(activity);
        }

        setSustainedPerformance(activity, prefs.getBoolean(PREF_SUSTAINED_PERFORMANCE, false));

        if (prefs.getBoolean(PREF_THREAD_PRIORITY_BOOST, true)) {
            if (currentGuestPid > 0) PerfPriority.boost(currentGuestPid);
        } else {
            PerfPriority.restore();
        }

        if (prefs.getBoolean(PREF_PREFER_BIG_CORES, false)) {
            if (currentGuestPid > 0) CpuTopology.applyBigCoreAffinity(currentGuestPid);
        }

        if (samsungDriver != null && samsungDriver.isSupported()) {
            if (prefs.getBoolean(PREF_SAMSUNG_PERF_BOOST, true)) {
                samsungDriver.startBoost();
            } else {
                samsungDriver.stopBoost();
            }
        }
    }

    public static void updateGuestPid(Activity activity, int pid) {
        if (pid <= 0) return;
        currentGuestPid = pid;
        if (activity != null && isSessionActive) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
            if (prefs.getBoolean(PREF_THREAD_PRIORITY_BOOST, true)) {
                PerfPriority.boost(pid);
            }
            if (prefs.getBoolean(PREF_PREFER_BIG_CORES, false)) {
                CpuTopology.applyBigCoreAffinity(pid);
            }
        }
    }

    public static boolean isSamsungSupported() {
        return samsungDriver != null && samsungDriver.isSupported();
    }

    private static void setSustainedPerformance(Activity activity, boolean enable) {
        if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        try {
            PowerManager pm = (PowerManager) activity.getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isSustainedPerformanceModeSupported()) {
                activity.getWindow().setSustainedPerformanceMode(enable);
                Log.d(TAG, "Sustained performance mode set to " + enable);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to set sustained performance mode: " + t.getMessage());
        }
    }
}
