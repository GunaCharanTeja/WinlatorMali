package com.winlator.cmod.logs;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Where logs live and which files are ours, after Bannerlator's LogLocation / LogRotation / LogInventory.
 *
 * Every log goes into {@code <log root>/<game>/} (the shortcut name, or the container name), the root being
 * Documents/WinlatorMali by default: a file manager reaches it, unlike Android/data. At the start of a launch the
 * previous run's files move into {@code previous/<timestamp>/} and the oldest beyond the keep count are deleted, so a
 * crashed run is never overwritten by the next one. Only names on {@link #isOurs}'s allowlist are ever listed, moved or
 * deleted: the log root can be a folder the user chose, full of their own files.
 */
public final class LogFiles {
    private static final String TAG = "LogFiles";

    public static final String PREF_LOCATION = "log_location";          // documents | download | custom
    public static final String PREF_CUSTOM_PATH = "log_location_custom_path";
    public static final String PREF_PER_GAME = "log_per_game_folders";
    public static final String PREF_KEEP_RUNS = "log_keep_runs";
    public static final int DEFAULT_KEEP_RUNS = 5;
    public static final String LOCATION_DOCUMENTS = "documents";
    public static final String LOCATION_DOWNLOAD = "download";
    public static final String LOCATION_CUSTOM = "custom";

    // What to record (Log Manager switches). Wine debug and Box64 logs keep their old preference names.
    public static final String PREF_WINE = "enable_wine_debug";
    public static final String PREF_BOX64 = "enable_box64_logs";
    public static final String PREF_DXVK = "enable_dxvk_logs";            // default on
    public static final String PREF_LOGCAT = "enable_logcat";              // default on
    public static final String PREF_CRASH = "enable_crash_reports";        // default on
    public static final String PREF_EXIT_AUTOSAVE = "exit_reasons_autosave"; // default off

    /** Master switch: off, nothing at all is recorded (every "what to record" switch reads as off). */
    public static final String PREF_ENABLED = "logs_enabled";

    public static boolean isOn(Context context, String pref) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (PREF_ENABLED.equals(pref)) return prefs.getBoolean(PREF_ENABLED, true);
        boolean recording = PREF_WINE.equals(pref) || PREF_BOX64.equals(pref) || PREF_DXVK.equals(pref)
            || PREF_LOGCAT.equals(pref) || PREF_CRASH.equals(pref) || PREF_EXIT_AUTOSAVE.equals(pref);
        if (recording && !prefs.getBoolean(PREF_ENABLED, true)) return false;
        boolean def = PREF_DXVK.equals(pref) || PREF_LOGCAT.equals(pref) || PREF_CRASH.equals(pref) || PREF_PER_GAME.equals(pref);
        return prefs.getBoolean(pref, def);
    }

    /** The switch's own saved value, ignoring the master switch (for showing it in the Log Manager). */
    public static boolean savedValue(Context context, String pref) {
        boolean def = PREF_DXVK.equals(pref) || PREF_LOGCAT.equals(pref) || PREF_CRASH.equals(pref) || PREF_PER_GAME.equals(pref);
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(pref, def);
    }

    public static final String APP_FOLDER = "_app";       // logcat and crash reports, not tied to a game
    public static final String ARCHIVE_DIR = "previous";
    public static final String WINE_LOG = "wine_debug.log";
    public static final String LOGCAT_LOG = "logcat.log";

    private LogFiles() {}

    // ---------------------------------------------------------------------------------------------------------------
    // Location

    /** The log root, created when missing. Falls back to internal storage when the chosen folder is not writable. */
    public static File root(Context context) {
        File fallback = new File(context.getFilesDir(), "logs");
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            File storage = Environment.getExternalStorageDirectory();
            String mode = prefs.getString(PREF_LOCATION, LOCATION_DOCUMENTS);
            String custom = prefs.getString(PREF_CUSTOM_PATH, "");
            File dir = LOCATION_DOWNLOAD.equals(mode) ? new File(storage, "Download/WinlatorMali/logs")
                : LOCATION_CUSTOM.equals(mode) && custom != null && !custom.isEmpty() ? new File(custom)
                : new File(storage, "Documents/WinlatorMali/logs");
            if (!dir.exists()) dir.mkdirs();
            if (dir.isDirectory() && dir.canWrite()) return dir;
        }
        catch (Exception e) {
            Log.w(TAG, "log root unavailable, using internal storage", e);
        }
        if (!fallback.exists()) fallback.mkdirs();
        return fallback;
    }

    /** {@code <root>/<game>/}, created when missing; the log root itself when "Folder for each game" is off. */
    public static File gameDir(Context context, String gameName) {
        if (!isOn(context, PREF_PER_GAME)) return root(context);
        File dir = new File(root(context), folderName(gameName));
        if (!dir.exists()) dir.mkdirs();
        return dir.isDirectory() ? dir : root(context);
    }

    public static File appDir(Context context) {
        return gameDir(context, APP_FOLDER);
    }

    /** Only what a filesystem rejects is stripped, so "Grand Theft Auto IV" stays readable. */
    public static String folderName(String name) {
        if (name == null) return APP_FOLDER;
        String safe = name.replaceAll("[/\\\\:*?\"<>|\\x00-\\x1F]", "").trim().replaceAll("[. ]+$", "");
        if (safe.isEmpty() || safe.equals(".") || safe.equals("..")) return APP_FOLDER;
        return safe.length() > 120 ? safe.substring(0, 120).trim() : safe;
    }

    public static int keepRuns(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getInt(PREF_KEEP_RUNS, DEFAULT_KEEP_RUNS);
    }

    public static String timestamp(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date(millis));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Ownership

    /**
     * A file this app wrote, and the only definition of it: every listing, rotation and delete goes through here.
     * DXVK names its logs after the executable, hence the suffix forms.
     */
    public static boolean isOurs(String name) {
        String n = name.toLowerCase(Locale.US);
        if (n.startsWith("crash_") && n.endsWith(".txt")) return true;
        if (!n.endsWith(".log")) return false;
        return n.equals(WINE_LOG) || n.equals(LOGCAT_LOG) || n.equals("vkd3d-proton.log")
            || n.endsWith("_d3d8.log") || n.endsWith("_d3d9.log") || n.endsWith("_d3d10.log")
            || n.endsWith("_d3d11.log") || n.endsWith("_d3d12.log") || n.endsWith("_dxgi.log");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Rotation

    /** Move the last run's files into previous/<timestamp>/ and keep at most {@code keep} archives. Never throws. */
    public static void rotate(File gameDir, int keep) {
        try {
            File[] stale = gameDir.listFiles(f -> f.isFile() && isOurs(f.getName()));
            if (stale != null && stale.length > 0) {
                if (keep <= 0) {
                    for (File f : stale) f.delete();
                }
                else {
                    long newest = 0;
                    for (File f : stale) newest = Math.max(newest, f.lastModified());
                    File archive = new File(new File(gameDir, ARCHIVE_DIR), timestamp(newest > 0 ? newest : System.currentTimeMillis()));
                    archive.mkdirs();
                    for (File f : stale) if (!f.renameTo(new File(archive, f.getName()))) f.delete();
                }
            }
            File[] runs = new File(gameDir, ARCHIVE_DIR).listFiles(File::isDirectory);
            if (runs != null && runs.length > Math.max(keep, 0)) {
                Arrays.sort(runs, (a, b) -> b.getName().compareTo(a.getName()));
                for (int i = Math.max(keep, 0); i < runs.length; i++) deleteTree(runs[i]);
            }
        }
        catch (Exception e) {
            Log.w(TAG, "rotate failed for " + gameDir, e);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Inventory

    /** One game's logs (or the _app bucket). */
    public static final class Group {
        public final String name;
        public final File dir;
        public final int fileCount;
        public final int archivedRuns;
        public final long bytes;
        public final long lastModified;

        Group(String name, File dir, int fileCount, int archivedRuns, long bytes, long lastModified) {
            this.name = name;
            this.dir = dir;
            this.fileCount = fileCount;
            this.archivedRuns = archivedRuns;
            this.bytes = bytes;
            this.lastModified = lastModified;
        }

        public boolean isApp() {
            return APP_FOLDER.equals(name);
        }
    }

    /** One run of a game: the current one, or an archived launch under previous/. */
    public static final class Run {
        public final File dir;
        public final boolean current;
        public final long millis;

        Run(File dir, boolean current, long millis) {
            this.dir = dir;
            this.current = current;
            this.millis = millis;
        }
    }

    /** The group of one game, or null when it has no logs yet (for "View logs" on a shortcut or container). */
    public static Group forGame(Context context, String gameName) {
        String folder = isOn(context, PREF_PER_GAME) ? folderName(gameName) : null;
        for (Group g : scan(context)) if (folder == null ? g.dir.equals(root(context)) : g.name.equals(folder)) return g;
        return null;
    }

    /** Groups holding logs we wrote, newest activity first. Folders without any of our files are left out. */
    public static List<Group> scan(Context context) {
        List<Group> out = new ArrayList<>();
        File base = root(context);
        // Loose files in the root: everything when "Folder for each game" is off.
        List<File> loose = filesIn(base);
        if (!loose.isEmpty()) {
            long bytes = 0, newest = 0;
            for (File f : loose) { bytes += f.length(); newest = Math.max(newest, f.lastModified()); }
            out.add(new Group("All logs", base, loose.size(), 0, bytes, newest));
        }
        File[] kids = base.listFiles(File::isDirectory);
        if (kids == null) return out;
        for (File dir : kids) {
            if (ARCHIVE_DIR.equals(dir.getName())) continue;
            int count = 0, archives = 0;
            long bytes = 0, newest = 0;
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (f.isDirectory() && ARCHIVE_DIR.equals(f.getName())) {
                    File[] runs = f.listFiles(File::isDirectory);
                    archives = runs != null ? runs.length : 0;
                    bytes += sizeOfTree(f);
                    newest = Math.max(newest, f.lastModified());
                }
                else if (f.isFile() && isOurs(f.getName())) {
                    count++;
                    bytes += f.length();
                    newest = Math.max(newest, f.lastModified());
                }
            }
            if (count > 0 || archives > 0) out.add(new Group(dir.getName(), dir, count, archives, bytes, newest));
        }
        Collections.sort(out, (a, b) -> Long.compare(b.lastModified, a.lastModified));
        return out;
    }

    /** The current run first, then archived ones newest first. */
    public static List<Run> runs(File groupDir) {
        List<Run> out = new ArrayList<>();
        if (!filesIn(groupDir).isEmpty()) out.add(new Run(groupDir, true, groupDir.lastModified()));
        File[] archived = new File(groupDir, ARCHIVE_DIR).listFiles(File::isDirectory);
        if (archived != null) {
            List<Run> old = new ArrayList<>();
            for (File f : archived) {
                long millis = f.lastModified();
                try { millis = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).parse(f.getName()).getTime(); }
                catch (Exception ignored) {}
                old.add(new Run(f, false, millis));
            }
            Collections.sort(old, (a, b) -> Long.compare(b.millis, a.millis));
            out.addAll(old);
        }
        return out;
    }

    /** Our log files directly in {@code dir}, newest first. */
    public static List<File> filesIn(File dir) {
        List<File> out = new ArrayList<>();
        File[] files = dir != null ? dir.listFiles() : null;
        if (files != null) for (File f : files) if (f.isFile() && isOurs(f.getName())) out.add(f);
        Collections.sort(out, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return out;
    }

    /** Delete our files in a group (and its previous/ archive), nothing else. Returns how many files went. */
    public static int deleteGroup(Context context, Group group) {
        int deleted = 0;
        File[] files = group.dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    if (ARCHIVE_DIR.equals(f.getName())) deleted += deleteTree(f);
                }
                else if (isOurs(f.getName()) && f.delete()) deleted++;
            }
        }
        File[] left = group.dir.listFiles();
        if (left != null && left.length == 0 && !group.dir.equals(root(context))) group.dir.delete();
        return deleted;
    }

    /** Delete one archived or current run's files. */
    public static int deleteRun(Run run) {
        if (!run.current) return deleteTree(run.dir);
        int deleted = 0;
        for (File f : filesIn(run.dir)) if (f.delete()) deleted++;
        return deleted;
    }

    public static long totalBytes(List<Group> groups) {
        long total = 0;
        for (Group g : groups) total += g.bytes;
        return total;
    }

    public static String humanBytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.US, "%.1f KB", b / 1024f);
        if (b < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", b / (1024f * 1024f));
        return String.format(Locale.US, "%.2f GB", b / (1024f * 1024f * 1024f));
    }

    private static long sizeOfTree(File f) {
        if (f.isFile()) return f.length();
        long total = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) total += sizeOfTree(k);
        return total;
    }

    private static int deleteTree(File f) {
        int n = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) n += deleteTree(k);
        if (f.isFile()) { if (f.delete()) n++; }
        else f.delete();
        return n;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Redaction

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}");
    private static final Pattern SECRET = Pattern.compile("(?i)((?:token|password|passwd|secret|api[_-]?key|authorization|cookie)\\s*[=:]\\s*)\\S+");
    private static final Pattern STEAMID = Pattern.compile("\\b7656119\\d{10}\\b");

    /**
     * Strip what a shared log must not carry: e-mail addresses, tokens and keys, Steam IDs. Files land where the user
     * can share them directly, so this runs when they are written, not when they are sent. A line the redactor
     * cannot handle is withheld rather than written unredacted.
     */
    public static String redact(String line) {
        if (line == null) return "";
        try {
            if (line.indexOf('@') < 0 && line.indexOf('=') < 0 && line.indexOf(':') < 0 && line.indexOf("eyJ") < 0 && line.indexOf("7656119") < 0)
                return line;
            String out = EMAIL.matcher(line).replaceAll("<email>");
            out = JWT.matcher(out).replaceAll("<token>");
            out = SECRET.matcher(out).replaceAll("$1<redacted>");
            return STEAMID.matcher(out).replaceAll("<steamid>");
        }
        catch (Throwable t) {
            return "[line withheld: redaction failed]";
        }
    }
}
