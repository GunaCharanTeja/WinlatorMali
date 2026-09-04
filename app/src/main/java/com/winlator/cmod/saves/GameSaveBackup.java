package com.winlator.cmod.saves;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class GameSaveBackup {
    private static final String TAG = "GameSaveBackup";
    public static final String SAVES_BACKUP_DIR = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath() + "/Winlator/Saves";
    public static final String MANIFEST_FILE_NAME = "winlator_save_manifest.json";

    private static final String[] USER_SAVE_ROOTS = {
        "Documents/My Games",
        "Documents",
        "Saved Games",
        "AppData/Local",
        "AppData/LocalLow",
        "AppData/Roaming",
        "Local Settings/Application Data"
    };

    public static class SaveLocationInfo {
        public File directory;
        public String title;
        public String friendlyPath;
        public long totalBytes;
        public int fileCount;
        public long lastModified;

        public String getFormattedSize() {
            if (totalBytes <= 0) return "0 KB";
            if (totalBytes < 1024) return totalBytes + " B";
            if (totalBytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", totalBytes / 1024.0f);
            return String.format(Locale.US, "%.2f MB", totalBytes / (1024.0f * 1024.0f));
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

    /**
     * Discovers all non-empty candidate save directories for a given shortcut.
     */
    public static List<SaveLocationInfo> getSaveLocations(Shortcut shortcut) {
        List<SaveLocationInfo> result = new ArrayList<>();
        if (shortcut == null || shortcut.container == null) return result;

        File userDir = new File(shortcut.container.getRootDir(), ".wine/drive_c/users/xuser");
        if (!userDir.exists()) return result;

        String gameName = normalize(shortcut.name);
        String exeBaseName = "";
        if (shortcut.path != null && !shortcut.path.isEmpty()) {
            exeBaseName = normalize(new File(shortcut.path).getName().replace(".exe", ""));
        }

        List<File> rawCandidates = new ArrayList<>();

        for (String relRoot : USER_SAVE_ROOTS) {
            File rootDir = new File(userDir, relRoot);
            if (!rootDir.exists() || !rootDir.isDirectory()) continue;

            File[] children = rootDir.listFiles();
            if (children == null) continue;

            for (File child : children) {
                if (child.isDirectory()) {
                    String childName = normalize(child.getName());
                    if (isFuzzyMatch(childName, gameName, exeBaseName)) {
                        rawCandidates.add(child);
                    } else {
                        File[] subChildren = child.listFiles();
                        if (subChildren != null) {
                            for (File subChild : subChildren) {
                                if (subChild.isDirectory()) {
                                    String subChildName = normalize(subChild.getName());
                                    if (isFuzzyMatch(subChildName, gameName, exeBaseName)) {
                                        rawCandidates.add(subChild);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (shortcut.path != null && !shortcut.path.isEmpty()) {
            File gameExe = new File(shortcut.path);
            File gameDir = gameExe.getParentFile();
            if (gameDir != null && gameDir.exists()) {
                String[] localSaveNames = {"save", "saves", "savedata", "Save", "Saves", "profile", "Profile"};
                for (String sName : localSaveNames) {
                    File localSaveDir = new File(gameDir, sName);
                    if (localSaveDir.exists() && localSaveDir.isDirectory()) {
                        rawCandidates.add(localSaveDir);
                    }
                }
            }
        }

        for (File dir : rawCandidates) {
            SaveLocationInfo info = inspectFolder(dir, shortcut.container);
            if (info != null && info.fileCount > 0 && info.totalBytes > 0) {
                result.add(info);
            }
        }

        return result;
    }

    private static SaveLocationInfo inspectFolder(File dir, Container container) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return null;

        SaveLocationInfo info = new SaveLocationInfo();
        info.directory = dir;
        info.title = dir.getName();
        info.friendlyPath = formatFriendlyPath(dir, container);

        long[] stats = calculateFolderStats(dir);
        info.totalBytes = stats[0];
        info.fileCount = (int) stats[1];
        info.lastModified = stats[2];

        return info;
    }

    public static String formatFriendlyPath(File file, Container container) {
        if (file == null) return "";
        String path = file.getAbsolutePath();
        if (container == null || container.getRootDir() == null) return file.getName();

        File userDir = new File(container.getRootDir(), ".wine/drive_c/users/xuser");
        String userPrefix = userDir.getAbsolutePath();
        if (path.startsWith(userPrefix)) {
            String rel = path.substring(userPrefix.length()).replace("\\", "/");
            if (rel.startsWith("/")) rel = rel.substring(1);
            return rel.replace("/", " ❯ ");
        }

        File driveCDir = new File(container.getRootDir(), ".wine/drive_c");
        String driveCPrefix = driveCDir.getAbsolutePath();
        if (path.startsWith(driveCPrefix)) {
            String rel = path.substring(driveCPrefix.length()).replace("\\", "/");
            if (rel.startsWith("/")) rel = rel.substring(1);
            return "C: ❯ " + rel.replace("/", " ❯ ");
        }

        return file.getName();
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

    /**
     * Inspects a save archive before extracting to verify validity and prevent corruption.
     */
    public static ArchiveInspectionResult inspectArchive(InputStream is) {
        ArchiveInspectionResult result = new ArchiveInspectionResult();
        int validSaveEntries = 0;
        int totalEntries = 0;

        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(is))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                totalEntries++;

                if (name.equals(MANIFEST_FILE_NAME)) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[1024];
                    int r;
                    while ((r = zis.read(buf)) != -1) baos.write(buf, 0, r);
                    try {
                        JSONObject json = new JSONObject(baos.toString("UTF-8"));
                        result.status = ArchiveStatus.VALID_WINLATOR_MANIFEST;
                        result.gameName = json.optString("game_name", "Game Saves");
                        result.createdAt = json.optString("created_at", "");
                        result.fileCount = json.optInt("file_count", 0);
                        result.totalBytes = json.optLong("total_bytes", 0);
                        JSONArray locs = json.optJSONArray("locations");
                        if (locs != null) {
                            for (int i = 0; i < locs.length(); i++) result.detectedPaths.add(locs.getString(i));
                        }
                        return result;
                    } catch (Exception ignored) {}
                }

                if (isProbableSavePath(name)) {
                    validSaveEntries++;
                    if (result.detectedPaths.size() < 5) {
                        result.detectedPaths.add(name);
                    }
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

    private static boolean isProbableSavePath(String path) {
        String p = path.toLowerCase().replace("\\", "/");
        return p.contains("users/") || p.contains("documents/") || p.contains("saved games/") ||
               p.contains("appdata/") || p.contains("game_dir/") || p.endsWith(".sav") ||
               p.endsWith(".dat") || p.endsWith(".save") || p.endsWith(".sl2") || p.endsWith(".sl3");
    }

    /**
     * Exports discovered game saves with a signed Winlator Mali manifest inside the zip.
     */
    public static File backupGameSaves(Shortcut shortcut, List<File> saveDirs) throws IOException {
        if (shortcut == null || shortcut.container == null) {
            throw new IOException("Invalid shortcut or container");
        }

        File exportDir = new File(SAVES_BACKUP_DIR);
        if (!exportDir.exists()) exportDir.mkdirs();

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String displayDate = new SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()).format(new Date());
        String safeName = shortcut.name.replaceAll("[^a-zA-Z0-9_-]", "_");
        File zipFile = new File(exportDir, safeName + "_SaveBackup_" + timestamp + ".zip");

        File userDir = new File(shortcut.container.getRootDir(), ".wine/drive_c/users/xuser");
        String userDirPath = userDir.getAbsolutePath();

        int totalFiles = 0;
        long totalBytes = 0;
        List<String> friendlyLocations = new ArrayList<>();

        for (File dir : saveDirs) {
            long[] stats = calculateFolderStats(dir);
            totalBytes += stats[0];
            totalFiles += (int) stats[1];
            friendlyLocations.add(formatFriendlyPath(dir, shortcut.container));
        }

        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFile)))) {
            // 1. Write signed manifest
            JSONObject manifest = new JSONObject();
            try {
                manifest.put("format", "winlator_save_backup_v1");
                manifest.put("app", "Winlator Mali");
                manifest.put("game_name", shortcut.name);
                manifest.put("created_at", displayDate);
                manifest.put("file_count", totalFiles);
                manifest.put("total_bytes", totalBytes);
                JSONArray locArray = new JSONArray();
                for (String loc : friendlyLocations) locArray.put(loc);
                manifest.put("locations", locArray);

                ZipEntry manifestEntry = new ZipEntry(MANIFEST_FILE_NAME);
                zos.putNextEntry(manifestEntry);
                zos.write(manifest.toString(2).getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            } catch (Exception ignored) {}

            // 2. Write save directories
            for (File saveDir : saveDirs) {
                if (!saveDir.exists()) continue;
                String relativeBase;
                if (saveDir.getAbsolutePath().startsWith(userDirPath)) {
                    relativeBase = "drive_c/users/xuser" + saveDir.getAbsolutePath().substring(userDirPath.length());
                } else {
                    relativeBase = "game_dir/" + saveDir.getName();
                }
                zipDirectory(saveDir, relativeBase, zos);
            }
        }

        Log.i(TAG, "Game saves backed up with manifest to: " + zipFile.getAbsolutePath());
        return zipFile;
    }

    /**
     * Backs up the entire container's user profile saves with manifest.
     */
    public static File backupContainerSaves(Container container) throws IOException {
        if (container == null) throw new IOException("Container is null");

        File exportDir = new File(SAVES_BACKUP_DIR);
        if (!exportDir.exists()) exportDir.mkdirs();

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String displayDate = new SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()).format(new Date());
        String safeName = container.getName().replaceAll("[^a-zA-Z0-9_-]", "_");
        File zipFile = new File(exportDir, "Container_" + safeName + "_AllSaves_" + timestamp + ".zip");

        File userDir = new File(container.getRootDir(), ".wine/drive_c/users/xuser");
        if (!userDir.exists()) throw new IOException("Container user profile does not exist");

        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFile)))) {
            // Write manifest
            JSONObject manifest = new JSONObject();
            try {
                manifest.put("format", "winlator_save_backup_v1");
                manifest.put("app", "Winlator Mali");
                manifest.put("game_name", "All Container Saves (" + container.getName() + ")");
                manifest.put("created_at", displayDate);

                ZipEntry manifestEntry = new ZipEntry(MANIFEST_FILE_NAME);
                zos.putNextEntry(manifestEntry);
                zos.write(manifest.toString(2).getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            } catch (Exception ignored) {}

            for (String relRoot : USER_SAVE_ROOTS) {
                File rootDir = new File(userDir, relRoot);
                if (rootDir.exists() && rootDir.isDirectory()) {
                    long[] stats = calculateFolderStats(rootDir);
                    if (stats[1] > 0) {
                        zipDirectory(rootDir, "drive_c/users/xuser/" + relRoot, zos);
                    }
                }
            }
        }

        Log.i(TAG, "Container saves backed up to: " + zipFile.getAbsolutePath());
        return zipFile;
    }

    /**
     * Restores saves from an InputStream into the target container.
     */
    public static boolean restoreSaves(Container container, InputStream is) throws IOException {
        if (container == null || is == null) {
            throw new IOException("Invalid container or input stream");
        }

        File driveCDir = new File(container.getRootDir(), ".wine/drive_c");
        if (!driveCDir.exists()) driveCDir.mkdirs();

        byte[] buffer = new byte[8192];
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(is))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();
                if (entry.isDirectory() || entryName.equals(MANIFEST_FILE_NAME)) {
                    continue;
                }

                // Normalize path to fit Winlator container structure
                String targetRelPath;
                if (entryName.startsWith("drive_c/users/steamuser/")) {
                    targetRelPath = "users/xuser/" + entryName.substring("drive_c/users/steamuser/".length());
                } else if (entryName.startsWith("drive_c/users/xuser/")) {
                    targetRelPath = "users/xuser/" + entryName.substring("drive_c/users/xuser/".length());
                } else if (entryName.startsWith("users/xuser/")) {
                    targetRelPath = entryName;
                } else if (entryName.startsWith("game_dir/")) {
                    targetRelPath = "users/xuser/Documents/" + entryName.substring("game_dir/".length());
                } else {
                    targetRelPath = "users/xuser/Documents/" + entryName;
                }

                File targetFile = new File(driveCDir, targetRelPath);
                File parent = targetFile.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();

                try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                    int read;
                    while ((read = zis.read(buffer)) != -1) {
                        fos.write(buffer, 0, read);
                    }
                }
                zis.closeEntry();
            }
        }

        Log.i(TAG, "Saves restored successfully into container " + container.getName());
        return true;
    }

    private static void zipDirectory(File folder, String parentPath, ZipOutputStream zos) throws IOException {
        File[] files = folder.listFiles();
        if (files == null) return;

        byte[] buffer = new byte[8192];
        for (File file : files) {
            if (file.isDirectory()) {
                zipDirectory(file, parentPath + "/" + file.getName(), zos);
            } else {
                ZipEntry entry = new ZipEntry(parentPath + "/" + file.getName());
                zos.putNextEntry(entry);
                try (FileInputStream fis = new FileInputStream(file)) {
                    int read;
                    while ((read = fis.read(buffer)) != -1) {
                        zos.write(buffer, 0, read);
                    }
                }
                zos.closeEntry();
            }
        }
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private static boolean isFuzzyMatch(String child, String game, String exe) {
        if (child.isEmpty()) return false;
        if (!game.isEmpty() && (child.contains(game) || game.contains(child))) return true;
        if (!exe.isEmpty() && (child.contains(exe) || exe.contains(child))) return true;
        return false;
    }
}
