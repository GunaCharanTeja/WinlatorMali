package com.winlator.cmod.saves;

import android.os.Environment;
import android.util.Log;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class GameSaveBackup {
    private static final String TAG = "GameSaveBackup";
    public static final String SAVES_BACKUP_DIR = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath() + "/Winlator/Saves";
    public static final String MANIFEST_FILE_NAME = "winlator_save_manifest.json";
    private static final String FORMAT_V2 = "winlator_save_backup_v2";
    private static final String CUSTOM_DIRS_EXTRA = "customSaveDirs";
    private static final String CONTAINER_PREFIX = "@container/";

    // Relative to the user profile (drive_c/users/xuser), searched for folders named like the game.
    private static final String[] USER_SAVE_ROOTS = {
        "Documents/My Games",
        "Documents",
        "Saved Games",
        "AppData/Local",
        "AppData/LocalLow",
        "AppData/Roaming",
        "Local Settings/Application Data"
    };

    // Relative to drive_c, also searched for folders named like the game.
    private static final String[] SHARED_SAVE_ROOTS = {
        "users/Public/Documents",
        "ProgramData"
    };

    // Relative to drive_c: where Steam emulators and cracks keep saves, one folder per Steam AppID.
    private static final String[] EMULATOR_SAVE_ROOTS = {
        "users/Public/Documents/Steam/CODEX",
        "users/Public/Documents/Steam/RUNE",
        "users/Public/Documents/Steam/EMPRESS",
        "users/Public/Documents/Steam",
        "users/Public/Documents/OnlineFix",
        "users/Public/Documents/EMPRESS",
        "users/xuser/AppData/Roaming/Goldberg SteamEmu Saves",
        "users/xuser/AppData/Roaming/GSE Saves",
        "users/xuser/AppData/Roaming/Steam/CODEX",
        "users/xuser/AppData/Roaming/CODEX",
        "users/xuser/AppData/Roaming/SmartSteamEmu",
        "users/xuser/AppData/Roaming/CPY_SAVES",
        "users/xuser/AppData/Roaming/EMPRESS",
        "users/xuser/AppData/Roaming/3DMGAME",
        "users/xuser/AppData/Roaming/NemirtingasEpicEmu",
        "users/xuser/AppData/Local/SKIDROW",
        "users/xuser/Documents/CPY_SAVES",
        "users/xuser/Documents/SKIDROW",
        "ProgramData/Steam/RLD!",
        "ProgramData/Steam/Player",
        "ProgramData/Steam/dodi",
        "ProgramData/Steam"
    };

    // Relative to drive_c: what a whole-container backup includes.
    private static final String[] CONTAINER_BACKUP_ROOTS = {
        "users/xuser/Documents",
        "users/xuser/Saved Games",
        "users/xuser/AppData/Local",
        "users/xuser/AppData/LocalLow",
        "users/xuser/AppData/Roaming",
        "users/Public/Documents",
        "ProgramData/Steam"
    };

    private static final Set<String> GAME_DIR_SAVE_NAMES = new HashSet<>(java.util.Arrays.asList(
        "save", "saves", "savegame", "savegames", "savedata", "saved", "profile", "profiles", "userdata"
    ));

    private static final Pattern INI_APP_ID = Pattern.compile("(?i)^\\s*(?:real)?app_?id\\s*=\\s*(\\d{3,10})\\s*$");

    public static class SaveLocationInfo {
        public File directory;
        public String title;
        public String friendlyPath;
        public long totalBytes;
        public int fileCount;
        public long lastModified;
        public boolean custom;
        public boolean suggested = true;
        public boolean missing;

        public String getFormattedSize() {
            return formatBytes(totalBytes);
        }

        public String getFormattedLastModified() {
            if (lastModified <= 0) return "";
            SimpleDateFormat sdf = new SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault());
            return sdf.format(new Date(lastModified));
        }
    }

    public enum ArchiveStatus {
        VALID_WINLATOR_MANIFEST,
        GENERIC_SAVE_ARCHIVE,
        INVALID_NON_SAVE_ARCHIVE
    }

    public static class ArchiveInspectionResult {
        public ArchiveStatus status;
        public String gameName;
        public String createdAt;
        public int fileCount;
        public long totalBytes;
        public List<String> detectedPaths = new ArrayList<>();
        public String errorMessage;
    }

    public interface StreamOpener {
        InputStream open() throws IOException;
    }

    public enum ConflictMode {
        BACKUP_THEN_OVERWRITE,
        OVERWRITE,
        NEWER_ONLY,
        SKIP_EXISTING
    }

    public static class PlannedFile {
        File target;
        long zipTime;
        boolean exists;
    }

    public static class RestorePlan {
        final Map<String, PlannedFile> files = new LinkedHashMap<>();
        final Map<String, File> dirs = new LinkedHashMap<>();
        public int conflicts;
        public int rejected;
        public String gameName = "";
        public final Set<String> locations = new LinkedHashSet<>();
        File driveC;
        File gameDir;

        public int fileCount() {
            return files.size();
        }
    }

    public static class RestoreResult {
        public int written;
        public int skipped;
        public int rejected;
        public File preRestoreBackup;
    }

    public static File getDriveC(Container container) {
        return new File(container.getRootDir(), ".wine/drive_c");
    }

    public static File getGameDir(Shortcut shortcut) {
        if (shortcut == null) return null;
        File exe = shortcut.resolveExeFile();
        File dir = exe != null ? exe.getParentFile() : null;
        return dir != null && dir.isDirectory() ? dir : null;
    }

    // ---------------------------------------------------------------- discovery

    /**
     * Every save folder worth showing for a shortcut: name matches in the usual Windows save locations,
     * AppID-named folders from Steam emulators/cracks, save folders next to the exe, and folders the
     * user added by hand.
     */
    public static List<SaveLocationInfo> getSaveLocations(Shortcut shortcut) {
        List<SaveLocationInfo> result = new ArrayList<>();
        if (shortcut == null || shortcut.container == null) return result;

        Container container = shortcut.container;
        File driveC = getDriveC(container);
        File userDir = new File(driveC, "users/xuser");
        File gameDir = getGameDir(shortcut);

        String gameName = normalize(shortcut.name);
        String exeBaseName = "";
        File exe = shortcut.resolveExeFile();
        if (exe != null) exeBaseName = normalize(exe.getName().replaceAll("(?i)\\.exe$", ""));

        LinkedHashMap<String, File> matched = new LinkedHashMap<>();
        LinkedHashMap<String, File> possible = new LinkedHashMap<>();

        for (String rel : USER_SAVE_ROOTS) scanByName(new File(userDir, rel), gameName, exeBaseName, matched);
        for (String rel : SHARED_SAVE_ROOTS) scanByName(new File(driveC, rel), gameName, exeBaseName, matched);

        Set<String> appIds = findAppIds(shortcut, gameDir, driveC);
        for (String rel : EMULATOR_SAVE_ROOTS) {
            File[] children = new File(driveC, rel).listFiles(File::isDirectory);
            if (children == null) continue;
            for (File child : children) {
                String name = child.getName();
                if (appIds.contains(name) || isFuzzyMatch(normalize(name), gameName, exeBaseName)) {
                    putUnique(matched, child);
                } else if (appIds.isEmpty() && name.matches("\\d{3,10}")) {
                    putUnique(possible, child);
                }
            }
        }

        if (gameDir != null) {
            File[] children = gameDir.listFiles(File::isDirectory);
            if (children != null) {
                for (File child : children) {
                    if (GAME_DIR_SAVE_NAMES.contains(child.getName().toLowerCase(Locale.ROOT))) putUnique(matched, child);
                }
            }
            // Goldberg's local-save mode keeps saves in a folder named inside local_save.txt.
            for (File marker : new File[]{new File(gameDir, "local_save.txt"), new File(gameDir, "steam_settings/local_save.txt")}) {
                String folder = readFirstLine(marker);
                if (folder != null && !folder.isEmpty()) {
                    File dir = new File(gameDir, folder);
                    if (dir.isDirectory()) putUnique(matched, dir);
                }
            }
        }

        Set<String> customKeys = new HashSet<>();
        for (File dir : getCustomSaveDirs(shortcut)) {
            customKeys.add(dir.getAbsolutePath());
            SaveLocationInfo info = inspectFolder(dir, container, gameDir);
            if (info == null) {
                info = new SaveLocationInfo();
                info.directory = dir;
                info.title = dir.getName();
                info.friendlyPath = formatFriendlyPath(dir, container, gameDir);
                info.missing = true;
                info.suggested = false;
            }
            info.custom = true;
            result.add(info);
        }

        for (File dir : matched.values()) {
            if (customKeys.contains(dir.getAbsolutePath())) continue;
            SaveLocationInfo info = inspectFolder(dir, container, gameDir);
            if (info != null && info.fileCount > 0) result.add(info);
        }

        for (File dir : possible.values()) {
            if (customKeys.contains(dir.getAbsolutePath()) || matched.containsKey(dir.getAbsolutePath())) continue;
            SaveLocationInfo info = inspectFolder(dir, container, gameDir);
            if (info != null && info.fileCount > 0) {
                info.suggested = false;
                result.add(info);
            }
        }

        return result;
    }

    private static void scanByName(File root, String gameName, String exeName, Map<String, File> out) {
        File[] children = root.listFiles(File::isDirectory);
        if (children == null) return;
        for (File child : children) {
            if (isFuzzyMatch(normalize(child.getName()), gameName, exeName)) {
                putUnique(out, child);
                continue;
            }
            File[] subChildren = child.listFiles(File::isDirectory);
            if (subChildren == null) continue;
            for (File sub : subChildren) {
                if (isFuzzyMatch(normalize(sub.getName()), gameName, exeName)) putUnique(out, sub);
            }
        }
    }

    private static void putUnique(Map<String, File> map, File dir) {
        map.put(dir.getAbsolutePath(), dir);
    }

    /** Steam AppIDs for the game: the shortcut's own Steam ID, steam_appid.txt, and AppId keys in emulator/crack .ini files. */
    private static Set<String> findAppIds(Shortcut shortcut, File gameDir, File driveC) {
        Set<String> ids = new LinkedHashSet<>();
        String steamId = shortcut.getSteamId();
        if (steamId != null && steamId.trim().matches("\\d+")) ids.add(steamId.trim());

        String driveCPath = driveC.getAbsolutePath();
        File dir = gameDir;
        for (int depth = 0; dir != null && depth < 3; depth++, dir = dir.getParentFile()) {
            if (dir.getAbsolutePath().equals(driveCPath)) break;
            addIdFromFile(new File(dir, "steam_appid.txt"), ids);
            addIdFromFile(new File(dir, "steam_settings/steam_appid.txt"), ids);
            File[] inis = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".ini"));
            if (inis == null) continue;
            for (File ini : inis) {
                if (!ini.isFile() || ini.length() > 128 * 1024) continue;
                try (BufferedReader reader = new BufferedReader(new FileReader(ini))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        Matcher m = INI_APP_ID.matcher(line);
                        if (m.matches()) ids.add(m.group(1));
                    }
                } catch (IOException ignored) {}
            }
        }
        return ids;
    }

    private static void addIdFromFile(File file, Set<String> ids) {
        String line = readFirstLine(file);
        if (line != null && line.matches("\\d{3,10}")) ids.add(line);
    }

    private static String readFirstLine(File file) {
        if (!file.isFile() || file.length() > 4096) return null;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line = reader.readLine();
            return line != null ? line.trim() : null;
        } catch (IOException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- custom folders

    public static List<File> getCustomSaveDirs(Shortcut shortcut) {
        List<File> dirs = new ArrayList<>();
        String raw = shortcut.getExtra(CUSTOM_DIRS_EXTRA);
        if (raw == null || raw.isEmpty()) return dirs;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                String entry = array.getString(i);
                if (entry.startsWith(CONTAINER_PREFIX)) {
                    dirs.add(new File(shortcut.container.getRootDir(), entry.substring(CONTAINER_PREFIX.length())));
                } else {
                    dirs.add(new File(entry));
                }
            }
        } catch (Exception ignored) {}
        return dirs;
    }

    /** Whether a folder may be used as a save location for this shortcut (and so restored into later). */
    public static boolean isAllowedSaveDir(Shortcut shortcut, File dir) {
        String path = canonical(dir);
        if (path == null) return false;
        for (String root : allowedRoots(shortcut.container, getGameDir(shortcut))) {
            if (isInside(path, root)) return true;
        }
        return false;
    }

    public static boolean addCustomSaveDir(Shortcut shortcut, File dir) {
        if (!isAllowedSaveDir(shortcut, dir)) return false;
        List<File> dirs = getCustomSaveDirs(shortcut);
        for (File d : dirs) {
            if (d.getAbsolutePath().equals(dir.getAbsolutePath())) return true;
        }
        dirs.add(dir);
        saveCustomSaveDirs(shortcut, dirs);
        return true;
    }

    public static void removeCustomSaveDir(Shortcut shortcut, File dir) {
        List<File> dirs = getCustomSaveDirs(shortcut);
        dirs.removeIf(d -> d.getAbsolutePath().equals(dir.getAbsolutePath()));
        saveCustomSaveDirs(shortcut, dirs);
    }

    private static void saveCustomSaveDirs(Shortcut shortcut, List<File> dirs) {
        String rootPath = shortcut.container.getRootDir().getAbsolutePath();
        JSONArray array = new JSONArray();
        for (File d : dirs) {
            String path = d.getAbsolutePath();
            // Stored relative to the container when inside it, so it survives the container being moved.
            array.put(isInside(path, rootPath) ? CONTAINER_PREFIX + path.substring(rootPath.length() + 1) : path);
        }
        shortcut.putExtra(CUSTOM_DIRS_EXTRA, array.length() > 0 ? array.toString() : null);
        shortcut.saveData();
    }

    // ---------------------------------------------------------------- display helpers

    private static SaveLocationInfo inspectFolder(File dir, Container container, File gameDir) {
        if (dir == null || !dir.isDirectory()) return null;

        SaveLocationInfo info = new SaveLocationInfo();
        info.directory = dir;
        info.title = dir.getName();
        info.friendlyPath = formatFriendlyPath(dir, container, gameDir);

        long[] stats = calculateFolderStats(dir);
        info.totalBytes = stats[0];
        info.fileCount = (int) stats[1];
        info.lastModified = stats[2];
        return info;
    }

    public static String formatFriendlyPath(File file, Container container, File gameDir) {
        if (file == null) return "";
        String path = file.getAbsolutePath();

        if (container != null) {
            String userPrefix = new File(getDriveC(container), "users/xuser").getAbsolutePath();
            if (isInside(path, userPrefix)) return relative(path, userPrefix).replace("/", " ❯ ");

            String driveCPrefix = getDriveC(container).getAbsolutePath();
            if (isInside(path, driveCPrefix)) return ("C: ❯ " + relative(path, driveCPrefix).replace("/", " ❯ ")).trim();
        }

        if (gameDir != null && isInside(path, gameDir.getAbsolutePath())) {
            return ("Game folder ❯ " + relative(path, gameDir.getAbsolutePath()).replace("/", " ❯ ")).trim();
        }

        String storage = Environment.getExternalStorageDirectory().getAbsolutePath();
        if (isInside(path, storage)) return ("Storage ❯ " + relative(path, storage).replace("/", " ❯ ")).trim();
        return path;
    }

    private static String relative(String path, String root) {
        return path.length() > root.length() ? path.substring(root.length() + 1) : "";
    }

    private static long[] calculateFolderStats(File dir) {
        long totalBytes = 0;
        long fileCount = 0;
        long latestModified = dir.lastModified();

        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    long[] sub = calculateFolderStats(f);
                    totalBytes += sub[0];
                    fileCount += sub[1];
                    if (sub[2] > latestModified) latestModified = sub[2];
                } else {
                    totalBytes += f.length();
                    fileCount++;
                    if (f.lastModified() > latestModified) latestModified = f.lastModified();
                }
            }
        }
        return new long[]{totalBytes, fileCount, latestModified};
    }

    public static String formatBytes(long bytes) {
        if (bytes <= 0) return "0 KB";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0f);
        return String.format(Locale.US, "%.2f MB", bytes / (1024.0f * 1024.0f));
    }

    // ---------------------------------------------------------------- backup

    /**
     * Zip key for a path, recording where it lives so restore can put it back: drive_c/..., game_dir/...
     * (relative to the game's exe folder, for games outside drive C:), or abs/... for anything else.
     */
    private static String zipKeyFor(File file, File driveC, File gameDir) {
        String path = file.getAbsolutePath();
        String driveCPath = driveC.getAbsolutePath();
        if (isInside(path, driveCPath)) return "drive_c" + path.substring(driveCPath.length());
        if (gameDir != null) {
            String gamePath = gameDir.getAbsolutePath();
            if (isInside(path, gamePath)) return "game_dir" + path.substring(gamePath.length());
        }
        return "abs" + path;
    }

    public static File backupGameSaves(Shortcut shortcut, List<File> saveDirs) throws IOException {
        if (shortcut == null || shortcut.container == null) throw new IOException("Invalid shortcut or container");

        File driveC = getDriveC(shortcut.container);
        File gameDir = getGameDir(shortcut);
        String safeName = shortcut.name.replaceAll("[^a-zA-Z0-9_-]", "_");
        File zipFile = newBackupFile(safeName + "_SaveBackup_");

        int totalFiles = 0;
        long totalBytes = 0;
        JSONArray locations = new JSONArray();
        for (File dir : saveDirs) {
            long[] stats = calculateFolderStats(dir);
            totalBytes += stats[0];
            totalFiles += (int) stats[1];
            locations.put(formatFriendlyPath(dir, shortcut.container, gameDir));
        }

        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFile)))) {
            writeManifest(zos, shortcut.name, totalFiles, totalBytes, locations, gameDir);
            Set<String> written = new HashSet<>();
            for (File dir : saveDirs) {
                if (dir.isDirectory()) zipDirectory(dir, zipKeyFor(dir, driveC, gameDir), zos, written);
            }
        }

        Log.i(TAG, "Game saves backed up to: " + zipFile.getAbsolutePath());
        return zipFile;
    }

    public static File backupContainerSaves(Container container) throws IOException {
        if (container == null) throw new IOException("Container is null");
        File driveC = getDriveC(container);
        if (!driveC.isDirectory()) throw new IOException("Container drive C: does not exist");

        String safeName = container.getName().replaceAll("[^a-zA-Z0-9_-]", "_");
        File zipFile = newBackupFile("Container_" + safeName + "_AllSaves_");

        int totalFiles = 0;
        long totalBytes = 0;
        JSONArray locations = new JSONArray();
        List<File> roots = new ArrayList<>();
        for (String rel : CONTAINER_BACKUP_ROOTS) {
            File root = new File(driveC, rel);
            if (!root.isDirectory()) continue;
            long[] stats = calculateFolderStats(root);
            if (stats[1] == 0) continue;
            roots.add(root);
            totalBytes += stats[0];
            totalFiles += (int) stats[1];
            locations.put(formatFriendlyPath(root, container, null));
        }

        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFile)))) {
            writeManifest(zos, "All Container Saves (" + container.getName() + ")", totalFiles, totalBytes, locations, null);
            // Tracks entries already written: overlapping roots or symlinks would otherwise add duplicate
            // entries, which makes ZipOutputStream throw and the whole backup fail.
            Set<String> written = new HashSet<>();
            for (File root : roots) zipDirectory(root, zipKeyFor(root, driveC, null), zos, written);
        }

        Log.i(TAG, "Container saves backed up to: " + zipFile.getAbsolutePath());
        return zipFile;
    }

    private static File newBackupFile(String prefix) throws IOException {
        File exportDir = new File(SAVES_BACKUP_DIR);
        if (!exportDir.exists() && !exportDir.mkdirs()) throw new IOException("Cannot create " + SAVES_BACKUP_DIR);
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return new File(exportDir, prefix + timestamp + ".zip");
    }

    private static void writeManifest(ZipOutputStream zos, String gameName, int fileCount, long totalBytes, JSONArray locations, File gameDir) throws IOException {
        try {
            JSONObject manifest = new JSONObject();
            manifest.put("format", FORMAT_V2);
            manifest.put("app", "Winlator Mali");
            manifest.put("game_name", gameName);
            manifest.put("created_at", new SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()).format(new Date()));
            manifest.put("file_count", fileCount);
            manifest.put("total_bytes", totalBytes);
            manifest.put("locations", locations);
            if (gameDir != null) manifest.put("game_dir", gameDir.getAbsolutePath());

            zos.putNextEntry(new ZipEntry(MANIFEST_FILE_NAME));
            zos.write(manifest.toString(2).getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        }
    }

    private static void zipDirectory(File folder, String parentPath, ZipOutputStream zos, Set<String> written) throws IOException {
        // An explicit entry for each folder, so empty save folders are recreated on restore too.
        String dirEntry = parentPath + "/";
        if (written.add(dirEntry)) {
            ZipEntry entry = new ZipEntry(dirEntry);
            entry.setTime(folder.lastModified());
            zos.putNextEntry(entry);
            zos.closeEntry();
        }

        File[] files = folder.listFiles();
        if (files == null) return;

        byte[] buffer = new byte[8192];
        for (File file : files) {
            String name = parentPath + "/" + file.getName();
            if (file.isDirectory()) {
                zipDirectory(file, name, zos, written);
            } else if (written.add(name)) {
                ZipEntry entry = new ZipEntry(name);
                entry.setTime(file.lastModified());
                zos.putNextEntry(entry);
                try (FileInputStream fis = new FileInputStream(file)) {
                    int read;
                    while ((read = fis.read(buffer)) != -1) zos.write(buffer, 0, read);
                }
                zos.closeEntry();
            }
        }
    }

    // ---------------------------------------------------------------- inspection

    public static ArchiveInspectionResult inspectArchive(InputStream is) {
        ArchiveInspectionResult result = new ArchiveInspectionResult();
        int validSaveEntries = 0;

        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(is))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();

                if (name.equals(MANIFEST_FILE_NAME)) {
                    JSONObject json = readJson(zis);
                    if (json != null) {
                        result.status = ArchiveStatus.VALID_WINLATOR_MANIFEST;
                        result.gameName = json.optString("game_name", "Game Saves");
                        result.createdAt = json.optString("created_at", "");
                        result.fileCount = json.optInt("file_count", 0);
                        result.totalBytes = json.optLong("total_bytes", 0);
                        JSONArray locs = json.optJSONArray("locations");
                        if (locs != null) {
                            for (int i = 0; i < locs.length(); i++) result.detectedPaths.add(locs.optString(i));
                        }
                        return result;
                    }
                }

                if (!entry.isDirectory() && isProbableSavePath(name)) {
                    validSaveEntries++;
                    if (result.detectedPaths.size() < 5) result.detectedPaths.add(name);
                }
                zis.closeEntry();
            }
        } catch (Exception e) {
            result.status = ArchiveStatus.INVALID_NON_SAVE_ARCHIVE;
            result.errorMessage = "Failed to read ZIP archive: " + e.getMessage();
            return result;
        }

        if (validSaveEntries > 0) {
            result.status = ArchiveStatus.GENERIC_SAVE_ARCHIVE;
            result.fileCount = validSaveEntries;
            result.gameName = "Generic / External Saves";
        } else {
            result.status = ArchiveStatus.INVALID_NON_SAVE_ARCHIVE;
            result.errorMessage = "No valid Windows/Wine save data (Documents, AppData, Saved Games) found in this archive.";
        }
        return result;
    }

    private static JSONObject readJson(ZipInputStream zis) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = zis.read(buf)) != -1) baos.write(buf, 0, r);
            return new JSONObject(baos.toString("UTF-8"));
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isProbableSavePath(String path) {
        String p = path.toLowerCase(Locale.ROOT).replace("\\", "/");
        return p.contains("users/") || p.contains("documents/") || p.contains("saved games/") ||
               p.contains("appdata/") || p.contains("programdata/") || p.contains("game_dir/") ||
               p.endsWith(".sav") || p.endsWith(".dat") || p.endsWith(".save") || p.endsWith(".sl2") || p.endsWith(".sl3");
    }

    // ---------------------------------------------------------------- restore

    private static List<String> allowedRoots(Container container, File gameDir) {
        List<String> roots = new ArrayList<>();
        String root = canonical(container.getRootDir());
        if (root != null) roots.add(root);
        if (gameDir != null) {
            String game = canonical(gameDir);
            if (game != null) roots.add(game);
        }
        String storage = canonical(Environment.getExternalStorageDirectory());
        if (storage != null) roots.add(storage);
        return roots;
    }

    /**
     * Reads the archive and works out where every entry goes, without writing anything: lets the caller
     * show the destinations and ask how to handle files that already exist. Entries that would land
     * outside this container, the game's folder or shared storage (e.g. "../" tricks) are refused.
     */
    public static RestorePlan planRestore(Container container, Shortcut shortcut, StreamOpener opener) throws IOException {
        RestorePlan plan = new RestorePlan();
        plan.driveC = getDriveC(container);

        List<ZipEntry> entries = new ArrayList<>();
        JSONObject manifest = null;
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(opener.open()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals(MANIFEST_FILE_NAME)) manifest = readJson(zis);
                else entries.add(entry);
                zis.closeEntry();
            }
        }

        File gameDir = getGameDir(shortcut);
        if (gameDir == null && manifest != null) {
            String recorded = manifest.optString("game_dir", "");
            if (!recorded.isEmpty()) {
                File candidate = new File(recorded);
                String path = canonical(candidate);
                // Only trusted if it's somewhere we'd let a restore write anyway.
                if (candidate.isDirectory() && path != null) {
                    for (String root : allowedRoots(container, null)) {
                        if (isInside(path, root)) {
                            gameDir = candidate;
                            break;
                        }
                    }
                }
            }
        }
        plan.gameDir = gameDir;
        if (manifest != null) plan.gameName = manifest.optString("game_name", "");

        List<String> roots = allowedRoots(container, gameDir);
        Set<String> parents = new LinkedHashSet<>();

        for (ZipEntry entry : entries) {
            File target = mapEntry(entry.getName(), plan.driveC, gameDir);
            String targetPath = target != null ? canonical(target) : null;
            boolean allowed = false;
            if (targetPath != null) {
                for (String root : roots) {
                    if (isInside(targetPath, root)) {
                        allowed = true;
                        break;
                    }
                }
            }

            if (!allowed) {
                if (!entry.isDirectory()) plan.rejected++;
                continue;
            }

            if (entry.isDirectory()) {
                plan.dirs.put(entry.getName(), target);
                continue;
            }

            PlannedFile planned = new PlannedFile();
            planned.target = target;
            planned.zipTime = entry.getTime();
            planned.exists = target.exists();
            if (planned.exists) plan.conflicts++;
            plan.files.put(entry.getName(), planned);

            File parent = target.getParentFile();
            if (parent != null) parents.add(formatFriendlyPath(parent, container, gameDir));
        }

        if (manifest != null && manifest.optJSONArray("locations") != null) {
            JSONArray locs = manifest.optJSONArray("locations");
            for (int i = 0; i < locs.length(); i++) plan.locations.add(locs.optString(i));
        } else {
            plan.locations.addAll(parents);
        }
        return plan;
    }

    private static File mapEntry(String rawName, File driveC, File gameDir) {
        String name = rawName.replace('\\', '/');
        while (name.startsWith("/")) name = name.substring(1);
        if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
        if (name.isEmpty()) return null;

        if (name.equals("drive_c")) return driveC;
        if (name.startsWith("drive_c/")) return new File(driveC, remapUser(name.substring("drive_c/".length())));
        if (name.startsWith("users/")) return new File(driveC, remapUser(name));
        if (name.equals("game_dir") || name.startsWith("game_dir/")) {
            if (gameDir == null) return null;
            return name.equals("game_dir") ? gameDir : new File(gameDir, name.substring("game_dir/".length()));
        }
        if (name.startsWith("abs/")) return new File("/" + name.substring("abs/".length()));
        return new File(driveC, "users/xuser/Documents/" + name);
    }

    // Saves from another Wine prefix (steamuser, a real Windows user name, ...) belong to this container's xuser.
    private static String remapUser(String path) {
        if (!path.startsWith("users/")) return path;
        String rest = path.substring("users/".length());
        int slash = rest.indexOf('/');
        String user = slash >= 0 ? rest.substring(0, slash) : rest;
        if (user.equalsIgnoreCase("Public") || user.equals("xuser")) return path;
        return "users/xuser" + (slash >= 0 ? rest.substring(slash) : "");
    }

    public static RestoreResult executeRestore(RestorePlan plan, ConflictMode mode, String backupLabel, StreamOpener opener) throws IOException {
        RestoreResult result = new RestoreResult();
        result.rejected = plan.rejected;

        if (mode == ConflictMode.BACKUP_THEN_OVERWRITE && plan.conflicts > 0) {
            result.preRestoreBackup = backupExistingFiles(plan, backupLabel);
        }

        for (File dir : plan.dirs.values()) {
            if (!dir.exists()) dir.mkdirs();
        }

        byte[] buffer = new byte[8192];
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(opener.open()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                PlannedFile planned = plan.files.get(entry.getName());
                if (planned == null || entry.isDirectory()) {
                    zis.closeEntry();
                    continue;
                }

                File target = planned.target;
                boolean exists = target.exists();
                boolean skip = target.isDirectory() ||
                        (exists && mode == ConflictMode.SKIP_EXISTING) ||
                        (exists && mode == ConflictMode.NEWER_ONLY && (planned.zipTime <= 0 || target.lastModified() >= planned.zipTime));
                if (skip) {
                    result.skipped++;
                    zis.closeEntry();
                    continue;
                }

                File parent = target.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                try (FileOutputStream fos = new FileOutputStream(target)) {
                    int read;
                    while ((read = zis.read(buffer)) != -1) fos.write(buffer, 0, read);
                }
                if (planned.zipTime > 0) target.setLastModified(planned.zipTime);
                result.written++;
                zis.closeEntry();
            }
        }

        Log.i(TAG, "Restore finished: " + result.written + " written, " + result.skipped + " skipped, " + result.rejected + " refused");
        return result;
    }

    /** Zips the files a restore is about to overwrite, in the same layout, so the restore can be undone. */
    private static File backupExistingFiles(RestorePlan plan, String label) throws IOException {
        String safeName = (label == null || label.isEmpty() ? "Saves" : label).replaceAll("[^a-zA-Z0-9_-]", "_");
        File zipFile = newBackupFile(safeName + "_BeforeRestore_");

        int count = 0;
        long bytes = 0;
        for (PlannedFile planned : plan.files.values()) {
            if (planned.exists && planned.target.isFile()) {
                count++;
                bytes += planned.target.length();
            }
        }

        JSONArray locations = new JSONArray();
        for (String loc : plan.locations) locations.put(loc);

        byte[] buffer = new byte[8192];
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFile)))) {
            writeManifest(zos, (label == null ? "Saves" : label) + " (before restore)", count, bytes, locations, plan.gameDir);
            Set<String> written = new HashSet<>();
            for (PlannedFile planned : plan.files.values()) {
                if (!planned.exists || !planned.target.isFile()) continue;
                String name = zipKeyFor(planned.target, plan.driveC, plan.gameDir);
                if (!written.add(name)) continue;
                ZipEntry entry = new ZipEntry(name);
                entry.setTime(planned.target.lastModified());
                zos.putNextEntry(entry);
                try (FileInputStream fis = new FileInputStream(planned.target)) {
                    int read;
                    while ((read = fis.read(buffer)) != -1) zos.write(buffer, 0, read);
                }
                zos.closeEntry();
            }
        }
        return zipFile;
    }

    // ---------------------------------------------------------------- utils

    private static String canonical(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean isInside(String path, String root) {
        return path.equals(root) || path.startsWith(root.endsWith("/") ? root : root + "/");
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static boolean isFuzzyMatch(String child, String game, String exe) {
        if (child.isEmpty()) return false;
        // The reverse check needs a few letters, or short folder names like "EA" or "Steam" match half the games.
        if (!game.isEmpty() && (child.contains(game) || (child.length() >= 4 && game.contains(child)))) return true;
        if (!exe.isEmpty() && (child.contains(exe) || (child.length() >= 4 && exe.contains(child)))) return true;
        return false;
    }
}
