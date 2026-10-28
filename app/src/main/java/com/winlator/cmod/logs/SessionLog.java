package com.winlator.cmod.logs;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.util.Log;

import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.ProcessHelper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One game session's log files, after Bannerlator: the Wine/Box64 output this session prints goes to
 * {@code <log root>/<game>/wine_debug.log} (redacted line by line), DXVK and VKD3D write their own logs into the same
 * folder, and when the session ends the app's own logcat is dumped next to them. Only active while Wine debug or
 * Box64/FEXCore logs are switched on in Settings; with both off nothing here runs.
 */
public final class SessionLog {
    private static final String TAG = "SessionLog";
    public static final int LOGCAT_LINES = 10000;

    private static File dir;
    private static PrintWriter writer;
    private static Callback<String> callback;

    private SessionLog() {}

    /** Anything to record for a game session (any of Wine debug, Box64, DXVK, logcat switched on). */
    public static boolean anyEnabled(Context context) {
        return LogFiles.isOn(context, LogFiles.PREF_WINE) || LogFiles.isOn(context, LogFiles.PREF_BOX64)
            || LogFiles.isOn(context, LogFiles.PREF_DXVK) || LogFiles.isOn(context, LogFiles.PREF_LOGCAT);
    }

    /**
     * Start a game session's logs: the game's folder is rotated (previous run archived), and when Wine debug or Box64 /
     * FEXCore logs are on, wine_debug.log is opened with a header and this session's guest output goes into it.
     * Returns the folder, or null when nothing is recorded.
     */
    public static synchronized File start(Context context, String gameName, String headerDetails) {
        stop(context, false);
        if (!anyEnabled(context)) return null;
        try {
            dir = LogFiles.gameDir(context, gameName);
            // Only rotate inside a folder of this game's own: the shared root holds other games' files.
            if (!dir.equals(LogFiles.root(context))) LogFiles.rotate(dir, LogFiles.keepRuns(context));
            if (!LogFiles.isOn(context, LogFiles.PREF_WINE) && !LogFiles.isOn(context, LogFiles.PREF_BOX64)) return dir;
            File logFile = new File(dir, LogFiles.WINE_LOG);
            writer = new PrintWriter(new BufferedWriter(new FileWriter(logFile, false)), true);
            writer.println(deviceHeader(context, gameName));
            if (headerDetails != null && !headerDetails.isEmpty()) writer.println(LogFiles.redact(headerDetails));
            writer.println("=== Wine output ===");
            final PrintWriter w = writer;
            callback = line -> {
                synchronized (SessionLog.class) {
                    if (w == writer) w.println(LogFiles.redact(line));
                }
            };
            ProcessHelper.addDebugCallback(callback);
            Log.d(TAG, "session log -> " + logFile.getAbsolutePath());
            return dir;
        }
        catch (Exception e) {
            Log.e(TAG, "could not open the session log", e);
            dir = null;
            writer = null;
            return null;
        }
    }

    /** The folder of the running session, or null. */
    public static synchronized File currentDir() {
        return dir;
    }

    /** Close the session's log; with {@code withLogcat}, dump the app's logcat into the same folder first. */
    public static synchronized void stop(Context context, boolean withLogcat) {
        if (callback != null) ProcessHelper.removeDebugCallback(callback);
        callback = null;
        if (writer != null) {
            writer.println("=== Session ended ===");
            writer.close();
        }
        writer = null;
        if (withLogcat && dir != null && context != null && LogFiles.isOn(context, LogFiles.PREF_LOGCAT)) {
            final File target = new File(dir, LogFiles.LOGCAT_LOG);
            final Context appContext = context.getApplicationContext();
            new Thread(() -> FileUtils.writeString(target, deviceHeader(appContext, null) + logcat(LOGCAT_LINES)), "LogcatDump").start();
        }
        dir = null;
    }

    /** Like {@link #stop}, with logcat written before returning: for an exit that ends the process right after. */
    public static void stopAndWait(Context context) {
        File folder;
        synchronized (SessionLog.class) {
            folder = dir;
            stop(context, false);
        }
        if (folder != null && LogFiles.isOn(context, LogFiles.PREF_LOGCAT))
            FileUtils.writeString(new File(folder, LogFiles.LOGCAT_LOG), deviceHeader(context, null) + logcat(LOGCAT_LINES));
    }

    /** The last {@code lines} of this app's own logcat (no other app's output, so nothing of theirs is shared). */
    public static String logcat(int lines) {
        try {
            Process p = Runtime.getRuntime().exec("logcat -d -t " + lines + " --pid=" + android.os.Process.myPid());
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(LogFiles.redact(line)).append('\n');
            }
            p.destroy();
            return sb.length() == 0 ? "(logcat returned no lines)\n" : sb.toString();
        }
        catch (Exception e) {
            return "Failed to capture logcat: " + e.getMessage() + "\n";
        }
    }

    /** Dump the app's logcat into _app/logcat.log right now. Call off the main thread. */
    public static File captureAppLogcat(Context context) {
        File out = new File(LogFiles.appDir(context), LogFiles.LOGCAT_LOG);
        FileUtils.writeString(out, deviceHeader(context, null) + logcat(LOGCAT_LINES));
        return out;
    }

    /**
     * Why the app's recent processes ended (crash, native crash, ANR, low memory, killed by the system...), from
     * Android's own records, newest first; written to _app/crash_<time>.txt when {@code save}. Android 11+.
     */
    public static String exitReasons(Context context, boolean save) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "Exit reasons need Android 11 or newer.";
        StringBuilder sb = new StringBuilder();
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            List<ApplicationExitInfo> infos = am.getHistoricalProcessExitReasons(context.getPackageName(), 0, 8);
            if (infos.isEmpty()) return "Android has no exit records for this app yet.";
            for (ApplicationExitInfo info : infos) {
                sb.append(LogFiles.timestamp(info.getTimestamp())).append("  ").append(reasonName(info.getReason()))
                  .append("  (").append(info.getProcessName()).append(")\n");
                String desc = info.getDescription();
                if (desc != null && !desc.isEmpty()) sb.append("    ").append(LogFiles.redact(desc)).append('\n');
                sb.append("    memory: ").append(info.getPss() / 1024).append(" MB PSS, importance ").append(info.getImportance()).append('\n');
            }
            if (save) FileUtils.writeString(new File(LogFiles.appDir(context), "crash_" + LogFiles.timestamp(System.currentTimeMillis()) + ".txt"),
                deviceHeader(context, null) + "=== Recent exit reasons ===\n" + sb);
        }
        catch (Exception e) {
            return "Could not read exit reasons: " + e.getMessage();
        }
        return sb.toString();
    }

    /** "Exit reasons (auto-save on launch)": save Android's records once there is a new one since the last save. */
    public static void autosaveExitReasons(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !LogFiles.isOn(context, LogFiles.PREF_EXIT_AUTOSAVE)) return;
        final Context appContext = context.getApplicationContext();
        new Thread(() -> {
            try {
                ActivityManager am = (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
                List<ApplicationExitInfo> infos = am.getHistoricalProcessExitReasons(appContext.getPackageName(), 0, 1);
                if (infos.isEmpty()) return;
                android.content.SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(appContext);
                long newest = infos.get(0).getTimestamp();
                if (newest <= prefs.getLong("exit_reasons_saved_until", 0)) return;
                exitReasons(appContext, true);
                prefs.edit().putLong("exit_reasons_saved_until", newest).apply();
            }
            catch (Exception ignored) {}
        }, "ExitReasons").start();
    }

    /**
     * Crash reports: an app crash writes _app/crash_<time>.txt (the stack trace, plus this app's logcat) before Android
     * ends the process. Installed once per process; the previous handler still runs after it.
     */
    public static void installCrashHandler(Context context) {
        if (crashHandlerInstalled) return;
        crashHandlerInstalled = true;
        final Context appContext = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                if (LogFiles.isOn(appContext, LogFiles.PREF_CRASH)) {
                    java.io.StringWriter trace = new java.io.StringWriter();
                    error.printStackTrace(new PrintWriter(trace));
                    FileUtils.writeString(new File(LogFiles.appDir(appContext), "crash_" + LogFiles.timestamp(System.currentTimeMillis()) + ".txt"),
                        deviceHeader(appContext, null) + "=== Crash in thread " + thread.getName() + " ===\n"
                        + LogFiles.redact(trace.toString()) + "\n=== logcat ===\n" + logcat(2000));
                }
            }
            catch (Throwable ignored) {}
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    private static boolean crashHandlerInstalled;

    private static String reasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_CRASH: return "Crash (Java)";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "Crash (native)";
            case ApplicationExitInfo.REASON_ANR: return "Not responding (ANR)";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "Killed: low memory";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "Killed: excessive resource use";
            case ApplicationExitInfo.REASON_SIGNALED: return "Killed by a signal";
            case ApplicationExitInfo.REASON_EXIT_SELF: return "Exited normally";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "Stopped by the user";
            case ApplicationExitInfo.REASON_USER_STOPPED: return "Force-stopped";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "Permission changed";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "Dependency died";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "Initialization failure";
            case ApplicationExitInfo.REASON_OTHER: return "Other (system)";
            default: return "Unknown";
        }
    }

    /** App, device and Android version: the first thing anyone reading a shared log needs. */
    public static String deviceHeader(Context context, String gameName) {
        String version = "unknown";
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            version = pi.versionName + " (" + pi.versionCode + ")";
        }
        catch (Exception ignored) {}
        StringBuilder device = new StringBuilder();
        Set<String> seen = new HashSet<>();
        for (String part : new String[]{Build.MANUFACTURER, Build.BRAND, Build.MODEL}) {
            if (part == null) continue;
            for (String token : part.trim().split("\\s+")) {
                if (token.isEmpty() || !seen.add(token.toLowerCase(Locale.US))) continue;
                if (device.length() > 0) device.append(' ');
                device.append(token);
            }
        }
        return "=== WinlatorMali log ===\n"
            + "Captured: " + LogFiles.timestamp(System.currentTimeMillis()) + "\n"
            + (gameName != null ? "Game: " + gameName + "\n" : "")
            + "App: " + version + "\n"
            + "Device: " + device + " (" + (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? Build.SOC_MODEL : Build.HARDWARE) + ")\n"
            + "Android: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")\n";
    }
}
