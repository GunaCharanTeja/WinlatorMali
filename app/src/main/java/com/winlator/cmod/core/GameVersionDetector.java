package com.winlator.cmod.core;

import android.util.Log;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.win32.PEParser;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GameVersionDetector {
    private static final String TAG = "GameVersionDetector";
    private static final ConcurrentHashMap<String, String> VERSION_CACHE = new ConcurrentHashMap<>();

    private static final Pattern VERSION_PATTERN = Pattern.compile(
        "(?:v|ver|version|build|patch|rev)?\\s*([0-9]+(?:\\.[0-9]+)+(?:[a-zA-Z0-9_.-]*)?)",
        Pattern.CASE_INSENSITIVE
    );

    public static String detect(Shortcut shortcut) {
        if (shortcut == null) return null;

        String key = (shortcut.file != null) ? shortcut.file.getAbsolutePath() : shortcut.name;
        String cached = VERSION_CACHE.get(key);
        if (cached != null) return cached;

        String savedVer = shortcut.getExtra("gameVersion", "");
        if (!savedVer.isEmpty() && isValidVersion(savedVer)) {
            VERSION_CACHE.put(key, savedVer);
            return savedVer;
        }

        File exeFile = shortcut.resolveExeFile();
        String detected = detect(exeFile, shortcut.container, shortcut.name);

        if (detected != null && isValidVersion(detected)) {
            VERSION_CACHE.put(key, detected);
            shortcut.putExtra("gameVersion", detected);
            shortcut.saveData();
            return detected;
        }

        return null;
    }

    public static String detect(File exeFile, Container container, String shortcutName) {
        if (exeFile == null || !exeFile.exists()) return null;

        File gameDir = exeFile.getParentFile();
        if (gameDir == null || !gameDir.exists()) return null;

        // Strategy 1: Direct PE Header on Primary Exe
        String peVersion = extractFromPE(exeFile);
        if (peVersion != null && !isGenericPlaceholder(peVersion)) {
            return peVersion;
        }

        // Strategy 2: Sibling Executables (e.g. AssassinsCreedIIGame.exe, Game.exe, *Shipping.exe)
        String siblingExeVer = detectSiblingExeVersion(gameDir, exeFile);
        if (siblingExeVer != null && !isGenericPlaceholder(siblingExeVer)) {
            return siblingExeVer;
        }

        // Strategy 3: GOG Game Metadata (goggame-*.info, goggame-*.json)
        String gogVer = detectGOGVersion(gameDir);
        if (gogVer != null) return gogVer;

        // Strategy 4: Unity Engine Game Metadata (*_Data/app.info, globalgamemanagers)
        String unityVer = detectUnityVersion(gameDir, exeFile);
        if (unityVer != null) return unityVer;

        // Strategy 5: Unreal Engine Shipping Binaries (Binaries/Win64/*-Shipping.exe)
        String ueVer = detectUnrealShippingVersion(gameDir);
        if (ueVer != null) return ueVer;

        // Strategy 6: Subdirectory Executables (bin/x64, bin/Win64, bin, x64, win64, release)
        String subExeVer = detectSubdirectoryExeVersion(gameDir, exeFile);
        if (subExeVer != null && !isGenericPlaceholder(subExeVer)) return subExeVer;

        // Strategy 7: Sibling Game DLLs (ubiorbitapi_r2.dll, uplay_r1_loader.dll, steam_api.dll, etc.)
        String dllVer = detectSiblingDllVersion(gameDir);
        if (dllVer != null && !isGenericPlaceholder(dllVer)) return dllVer;

        // Strategy 8: Common Game Version Files (sku.ini, system.ini, version.txt, build.txt, etc.)
        String fileVer = detectFromVersionFiles(gameDir);
        if (fileVer != null) return fileVer;

        // Strategy 9: Epic Games Store Metadata
        String epicVer = detectEpicGamesVersion(gameDir);
        if (epicVer != null) return epicVer;

        // Strategy 10: EA / Origin Installer Metadata
        String eaVer = detectEAVersion(gameDir);
        if (eaVer != null) return eaVer;

        // Strategy 11: Xbox GamePass Metadata
        String xboxVer = detectXboxVersion(gameDir);
        if (xboxVer != null) return xboxVer;

        // Strategy 12: Itch.io Metadata
        String itchVer = detectItchVersion(gameDir);
        if (itchVer != null) return itchVer;

        // Strategy 13: Steam appmanifest buildid
        String steamManifestVer = detectSteamManifestVersion(gameDir);
        if (steamManifestVer != null) return steamManifestVer;

        // Strategy 14: Emulator & Crack INI Files (steam_emu.ini, codex.ini, etc.)
        String iniVer = detectFromIniFiles(gameDir);
        if (iniVer != null) return iniVer;

        // Strategy 15: Deep raw binary search for version strings in primary and sibling exes
        String binaryVer = detectFromRawBinaryStrings(exeFile);
        if (binaryVer != null) return binaryVer;

        // Fallbacks if only generic PE version was found
        if (siblingExeVer != null && isValidVersion(siblingExeVer)) return siblingExeVer;
        if (subExeVer != null && isValidVersion(subExeVer)) return subExeVer;
        if (peVersion != null && isValidVersion(peVersion)) return peVersion;
        if (dllVer != null && isValidVersion(dllVer)) return dllVer;

        return null;
    }

    private static String detectSiblingExeVersion(File dir, File primaryExe) {
        String primaryName = primaryExe.getName().toLowerCase(Locale.US);
        String baseName = FileUtils.getBasename(primaryName);

        File[] files = dir.listFiles((d, name) -> {
            String lower = name.toLowerCase(Locale.US);
            return lower.endsWith(".exe") && !lower.equalsIgnoreCase(primaryName);
        });

        if (files == null || files.length == 0) return null;

        // Priority 1: Exact matches like AssassinsCreedIIGame.exe for AssassinsCreedII.exe
        for (File f : files) {
            String lower = f.getName().toLowerCase(Locale.US);
            if (lower.contains(baseName) || baseName.contains(FileUtils.getBasename(lower))) {
                if (lower.contains("crash") || lower.contains("reporter") || lower.contains("unins") || lower.contains("setup")) continue;
                String ver = extractFromPE(f);
                if (ver != null && !isGenericPlaceholder(ver)) return ver;
            }
        }

        // Priority 2: Contains "game", "shipping", "win64", "main", "client"
        for (File f : files) {
            String lower = f.getName().toLowerCase(Locale.US);
            if (lower.contains("game") || lower.contains("shipping") || lower.contains("win64") || lower.contains("x64") || lower.contains("client") || lower.contains("main")) {
                if (lower.contains("crash") || lower.contains("reporter") || lower.contains("unins") || lower.contains("setup")) continue;
                String ver = extractFromPE(f);
                if (ver != null && !isGenericPlaceholder(ver)) return ver;
            }
        }

        return null;
    }

    private static String detectSiblingDllVersion(File dir) {
        String[] prioritizedDlls = {
            "ubiorbitapi_r2.dll", "uplay_r1_loader.dll", "uplay_r1.dll", "uplay_r2.dll",
            "steam_api.dll", "steam_api64.dll", "galaxy.dll", "galaxy64.dll",
            "game.dll", "engine.dll", "main.dll", "unityplayer.dll"
        };

        for (String dllName : prioritizedDlls) {
            File dllFile = new File(dir, dllName);
            if (dllFile.isFile()) {
                String ver = extractFromPE(dllFile);
                if (ver != null && !isGenericPlaceholder(ver)) return ver;
            }
        }

        // Search bin / subdirectories for dlls
        File[] subDirs = dir.listFiles(File::isDirectory);
        if (subDirs != null) {
            for (File sub : subDirs) {
                for (String dllName : prioritizedDlls) {
                    File dllFile = new File(sub, dllName);
                    if (dllFile.isFile()) {
                        String ver = extractFromPE(dllFile);
                        if (ver != null && !isGenericPlaceholder(ver)) return ver;
                    }
                }
            }
        }
        return null;
    }

    private static String extractFromPE(File file) {
        if (file == null || !file.isFile()) return null;
        try {
            PEParser.FileVersionInfo info = PEParser.getFileVersionInfo(file);
            if (info != null && info.hasVersion()) {
                String best = info.getBestVersion();
                return normalizeVersion(best);
            }
        } catch (Throwable t) {
            Log.d(TAG, "PE parse failed for " + file.getName() + ": " + t.getMessage());
        }
        return null;
    }

    private static String detectGOGVersion(File dir) {
        File searchDir = dir;
        for (int depth = 0; depth < 3 && searchDir != null; depth++) {
            File[] gogFiles = searchDir.listFiles((d, name) -> {
                String lower = name.toLowerCase(Locale.US);
                return lower.startsWith("goggame-") && (lower.endsWith(".info") || lower.endsWith(".json"));
            });

            if (gogFiles != null && gogFiles.length > 0) {
                for (File gf : gogFiles) {
                    try {
                        String jsonStr = FileUtils.readString(gf);
                        if (jsonStr != null && !jsonStr.trim().isEmpty()) {
                            JSONObject json = new JSONObject(jsonStr);
                            if (json.has("versionName")) {
                                String v = json.optString("versionName", "").trim();
                                if (!v.isEmpty()) return normalizeVersion(v);
                            }
                            if (json.has("version")) {
                                String v = json.optString("version", "").trim();
                                if (!v.isEmpty()) return normalizeVersion(v);
                            }
                            if (json.has("buildId")) {
                                String b = json.optString("buildId", "").trim();
                                if (!b.isEmpty()) return normalizeVersion("b" + b);
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
            searchDir = searchDir.getParentFile();
        }
        return null;
    }

    private static String detectUnityVersion(File dir, File exeFile) {
        String exeBase = FileUtils.getBasename(exeFile.getName());
        List<File> dataDirs = new ArrayList<>();

        File exactData = new File(dir, exeBase + "_Data");
        if (exactData.isDirectory()) dataDirs.add(exactData);

        File[] allDataDirs = dir.listFiles((d, name) -> name.endsWith("_Data") && new File(d, name).isDirectory());
        if (allDataDirs != null) {
            for (File d : allDataDirs) {
                if (!dataDirs.contains(d)) dataDirs.add(d);
            }
        }

        for (File dataDir : dataDirs) {
            File appInfo = new File(dataDir, "app.info");
            if (appInfo.isFile()) {
                try {
                    List<String> lines = FileUtils.readLines(appInfo);
                    if (lines != null && lines.size() >= 3) {
                        String ver = lines.get(2).trim();
                        if (isValidVersion(ver)) return normalizeVersion(ver);
                    }
                } catch (Exception ignored) {}
            }
        }

        return null;
    }

    private static String detectUnrealShippingVersion(File dir) {
        List<File> searchDirs = new ArrayList<>();
        searchDirs.add(new File(dir, "Binaries/Win64"));
        searchDirs.add(new File(dir, "Binaries/Win32"));
        searchDirs.add(new File(dir, "Engine/Binaries/Win64"));

        File[] subDirs = dir.listFiles(File::isDirectory);
        if (subDirs != null) {
            for (File sub : subDirs) {
                searchDirs.add(new File(sub, "Binaries/Win64"));
                searchDirs.add(new File(sub, "Binaries/Win32"));
            }
        }

        for (File sDir : searchDirs) {
            if (sDir.isDirectory()) {
                File[] exes = sDir.listFiles((d, name) -> name.toLowerCase(Locale.US).endsWith(".exe"));
                if (exes != null) {
                    for (File exe : exes) {
                        String name = exe.getName().toLowerCase(Locale.US);
                        if (name.contains("shipping") || name.contains("game") || name.contains("win64")) {
                            String ver = extractFromPE(exe);
                            if (ver != null && !isGenericPlaceholder(ver)) return ver;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static String detectSubdirectoryExeVersion(File dir, File primaryExe) {
        String[] subDirNames = {"bin", "bin/x64", "bin/Win64", "bin/x86", "bin/win32", "x64", "win64", "Release", "build"};
        for (String sub : subDirNames) {
            File sDir = new File(dir, sub);
            if (sDir.isDirectory()) {
                File[] exes = sDir.listFiles((d, name) -> name.toLowerCase(Locale.US).endsWith(".exe") && !name.equalsIgnoreCase(primaryExe.getName()));
                if (exes != null) {
                    for (File exe : exes) {
                        String name = exe.getName().toLowerCase(Locale.US);
                        if (name.contains("crash") || name.contains("reporter") || name.contains("setup") || name.contains("unins")) continue;
                        String ver = extractFromPE(exe);
                        if (ver != null && !isGenericPlaceholder(ver)) return ver;
                    }
                }
            }
        }
        return null;
    }

    private static String detectFromVersionFiles(File dir) {
        String[] fileNames = {
            "sku.ini", "SKU.ini", "system.ini", "version.ini", "version.txt", "build.txt",
            "game_version.txt", "gameversion.txt", "release.txt", "buildinfo.txt",
            "version.json", "package.json", "manifest.json", "installscript.vdf",
            "version.cfg", "gameinfo.json", "gameinfo.txt", "build_info.txt", "buildversion.txt",
            "versionstamp.txt", "__version__.txt", "VersionInfo.txt", "Game.ini", "Engine.ini",
            "steam_settings/version.txt", "steam_settings/build_id.txt"
        };

        File searchDir = dir;
        for (int depth = 0; depth < 3 && searchDir != null; depth++) {
            for (String fn : fileNames) {
                File vf = new File(searchDir, fn);
                if (vf.isFile() && vf.length() < 300 * 1024) {
                    String ver = parseVersionFromFile(vf);
                    if (ver != null && isValidVersion(ver)) return normalizeVersion(ver);
                }
            }
            searchDir = searchDir.getParentFile();
        }
        return null;
    }

    private static String detectEpicGamesVersion(File dir) {
        File searchDir = dir;
        for (int depth = 0; depth < 4 && searchDir != null; depth++) {
            // Check for .mancpn or Manifests
            File[] mancpnFiles = searchDir.listFiles((d, name) -> name.toLowerCase(Locale.US).endsWith(".mancpn"));
            if (mancpnFiles != null) {
                for (File mf : mancpnFiles) {
                    String content = FileUtils.readString(mf);
                    if (content != null) {
                        try {
                            JSONObject json = new JSONObject(content);
                            if (json.has("AppVersionString")) return normalizeVersion(json.getString("AppVersionString"));
                            if (json.has("CatalogVersion")) return normalizeVersion(json.getString("CatalogVersion"));
                        } catch (Exception ignored) {}
                    }
                }
            }

            File manifestsDir = new File(searchDir, "Manifests");
            if (manifestsDir.isDirectory()) {
                File[] manifests = manifestsDir.listFiles((d, name) -> name.toLowerCase(Locale.US).endsWith(".manifest"));
                if (manifests != null) {
                    for (File mf : manifests) {
                        String content = FileUtils.readString(mf);
                        if (content != null) {
                            try {
                                JSONObject json = new JSONObject(content);
                                if (json.has("AppVersionString")) return normalizeVersion(json.getString("AppVersionString"));
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }

            searchDir = searchDir.getParentFile();
        }
        return null;
    }

    private static String detectEAVersion(File dir) {
        File searchDir = dir;
        for (int depth = 0; depth < 3 && searchDir != null; depth++) {
            File installerXml = new File(searchDir, "__Installer/installerdata.xml");
            if (installerXml.isFile()) {
                String content = FileUtils.readString(installerXml);
                if (content != null) {
                    Matcher m = Pattern.compile("(?i)<version>([^<]+)</version>").matcher(content);
                    if (m.find()) return normalizeVersion(m.group(1));
                    m = Pattern.compile("(?i)version=\"([^\"]+)\"").matcher(content);
                    if (m.find()) return normalizeVersion(m.group(1));
                }
            }

            File installJson = new File(searchDir, "__Installer/install.json");
            if (installJson.isFile()) {
                String content = FileUtils.readString(installJson);
                if (content != null) {
                    try {
                        JSONObject json = new JSONObject(content);
                        if (json.has("version")) return normalizeVersion(json.getString("version"));
                    } catch (Exception ignored) {}
                }
            }

            searchDir = searchDir.getParentFile();
        }
        return null;
    }

    private static String detectXboxVersion(File dir) {
        File searchDir = dir;
        for (int depth = 0; depth < 3 && searchDir != null; depth++) {
            File gameConfig = new File(searchDir, "MicrosoftGame.config");
            if (gameConfig.isFile()) {
                String content = FileUtils.readString(gameConfig);
                if (content != null) {
                    Matcher m = Pattern.compile("(?i)Version=\"([^\"]+)\"").matcher(content);
                    if (m.find()) return normalizeVersion(m.group(1));
                }
            }
            searchDir = searchDir.getParentFile();
        }
        return null;
    }

    private static String detectItchVersion(File dir) {
        File searchDir = dir;
        for (int depth = 0; depth < 3 && searchDir != null; depth++) {
            File itchToml = new File(searchDir, ".itch.toml");
            if (itchToml.isFile()) {
                String content = FileUtils.readString(itchToml);
                if (content != null) {
                    Matcher m = Pattern.compile("(?i)version\\s*=\\s*\"([^\"]+)\"").matcher(content);
                    if (m.find()) return normalizeVersion(m.group(1));
                }
            }

            File itchReceipt = new File(searchDir, ".itch/receipt.json");
            if (itchReceipt.isFile()) {
                String content = FileUtils.readString(itchReceipt);
                if (content != null) {
                    try {
                        JSONObject json = new JSONObject(content);
                        if (json.has("version")) return normalizeVersion(json.getString("version"));
                    } catch (Exception ignored) {}
                }
            }
            searchDir = searchDir.getParentFile();
        }
        return null;
    }

    private static String detectSteamManifestVersion(File dir) {
        File searchDir = dir;
        for (int depth = 0; depth < 4 && searchDir != null; depth++) {
            File[] acfFiles = searchDir.listFiles((d, name) -> name.toLowerCase(Locale.US).startsWith("appmanifest_") && name.toLowerCase(Locale.US).endsWith(".acf"));
            if (acfFiles != null && acfFiles.length > 0) {
                for (File acf : acfFiles) {
                    String content = FileUtils.readString(acf);
                    if (content != null) {
                        Matcher m = Pattern.compile("(?i)\"buildid\"\\s+\"(\\d+)\"").matcher(content);
                        if (m.find()) return normalizeVersion("b" + m.group(1));
                    }
                }
            }
            searchDir = searchDir.getParentFile();
        }
        return null;
    }

    private static String parseVersionFromFile(File file) {
        try {
            String name = file.getName().toLowerCase(Locale.US);
            if (name.endsWith(".json")) {
                String content = FileUtils.readString(file);
                if (content != null) {
                    JSONObject json = new JSONObject(content);
                    if (json.has("version")) return json.optString("version");
                    if (json.has("versionName")) return json.optString("versionName");
                    if (json.has("gameVersion")) return json.optString("gameVersion");
                    if (json.has("build")) return json.optString("build");
                }
            } else if (name.endsWith(".ini") || name.endsWith(".vdf") || name.endsWith(".txt") || name.endsWith(".cfg")) {
                try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        String trimmed = line.trim();
                        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) continue;

                        int eq = trimmed.indexOf('=');
                        if (eq > 0) {
                            String key = trimmed.substring(0, eq).trim().toLowerCase(Locale.US);
                            String val = trimmed.substring(eq + 1).trim();
                            if (key.contains("version") || key.contains("ver") || key.equals("number") || key.contains("build") || key.equals("patch")) {
                                Matcher m = VERSION_PATTERN.matcher(val);
                                if (m.find()) return m.group(1);
                                if (isValidVersion(val)) return val;
                            }
                        }

                        Matcher m = VERSION_PATTERN.matcher(trimmed);
                        if (m.find()) {
                            return m.group(1);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String detectFromIniFiles(File dir) {
        String[] iniNames = {
            "steam_emu.ini", "hlm.ini", "flt.ini", "codex.ini", "rld.ini",
            "CreamAPI.ini", "SmartSteamEmu.ini", "steam_api.ini", "Game.ini", "config.ini",
            "uplay_r1.ini", "ubiorbitapi_r2.ini", "SKU.ini", "sku.ini"
        };

        File searchDir = dir;
        for (int depth = 0; depth < 2 && searchDir != null; depth++) {
            for (String fn : iniNames) {
                File iniFile = new File(searchDir, fn);
                if (iniFile.isFile() && iniFile.length() < 200 * 1024) {
                    try {
                        List<String> lines = FileUtils.readLines(iniFile);
                        if (lines != null) {
                            for (String line : lines) {
                                String trimmed = line.trim();
                                if (trimmed.startsWith(";") || trimmed.startsWith("#")) continue;
                                int eq = trimmed.indexOf('=');
                                if (eq > 0) {
                                    String key = trimmed.substring(0, eq).trim().toLowerCase(Locale.US);
                                    String val = trimmed.substring(eq + 1).trim();
                                    if (key.equals("version") || key.equals("buildversion") || key.equals("gameversion") || key.equals("buildid") || key.equals("skuversion") || key.equals("number")) {
                                        if (isValidVersion(val)) return normalizeVersion(val);
                                    }
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
            searchDir = searchDir.getParentFile();
        }
        return null;
    }

    private static String detectFromRawBinaryStrings(File exeFile) {
        if (exeFile == null || !exeFile.isFile() || exeFile.length() > 60 * 1024 * 1024) return null;
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(exeFile, "r")) {
            long length = Math.min(exeFile.length(), 24 * 1024 * 1024);
            byte[] buffer = new byte[128 * 1024];
            long pos = 0;

            Pattern strPattern = Pattern.compile("(?:version|ver|patch|build|rev)[:=\\s-]*([0-9]+(?:\\.[0-9]+)+(?:[a-zA-Z0-9_.-]*)?)", Pattern.CASE_INSENSITIVE);

            String bestCandidate = null;
            int bestScore = 0;

            while (pos < length) {
                raf.seek(pos);
                int read = raf.read(buffer);
                if (read <= 0) break;

                String s = new String(buffer, 0, read, StandardCharsets.ISO_8859_1);
                Matcher m = strPattern.matcher(s);
                while (m.find()) {
                    String candidate = m.group(1);
                    if (isValidVersion(candidate) && !isGenericPlaceholder(candidate)) {
                        String norm = normalizeVersion(candidate);
                        if (norm != null) {
                            int score = norm.split("\\.").length;
                            if (score > bestScore) {
                                bestScore = score;
                                bestCandidate = norm;
                                if (score >= 3) return bestCandidate; // 3+ version components (e.g. 1.2.3) is solid
                            }
                        }
                    }
                }
                pos += read - 256; // overlap
            }

            return bestCandidate;
        } catch (Exception ignored) {}
        return null;
    }

    public static String normalizeVersion(String raw) {
        if (raw == null) return null;
        String v = raw.trim();

        // Strip leading prefixes like "Version:", "Ver:", "Build:"
        v = v.replaceAll("(?i)^(?:version|ver|build|patch|release|rev)[:=\\s]+", "").trim();

        // Normalize commas, semicolons, underscores to dots
        v = v.replace(',', '.').replace(';', '.').replace('_', '.').replaceAll("\\s+", "");

        // Collapse consecutive dots
        while (v.contains("..")) {
            v = v.replace("..", ".");
        }
        while (v.startsWith(".")) {
            v = v.substring(1);
        }
        while (v.endsWith(".")) {
            v = v.substring(0, v.length() - 1);
        }

        // Clean redundant trailing ".0.0" -> ".0" for cleaner look if length > 3 parts
        String[] parts = v.split("\\.");
        if (parts.length == 4 && "0".equals(parts[2]) && "0".equals(parts[3])) {
            v = parts[0] + "." + parts[1];
        } else if (parts.length == 4 && "0".equals(parts[3])) {
            v = parts[0] + "." + parts[1] + "." + parts[2];
        }

        return isValidVersion(v) ? v : null;
    }

    private static boolean isValidVersion(String ver) {
        if (ver == null) return false;
        String clean = ver.trim();
        if (clean.isEmpty() || clean.length() > 32) return false;

        // Must have at least one digit
        if (!clean.matches(".*\\d+.*")) return false;

        String lower = clean.toLowerCase(Locale.US);
        if (lower.equals("none") || lower.equals("null") || lower.equals("default") ||
            lower.equals("release") || lower.equals("debug") || lower.equals("unknown") ||
            lower.equals("n/a") || lower.equals("todo")) {
            return false;
        }

        // Check if all zeroes
        String digitsOnly = clean.replaceAll("[^0-9]", "");
        if (digitsOnly.matches("^0+$")) return false;

        return true;
    }

    private static boolean isGenericPlaceholder(String ver) {
        if (ver == null) return true;
        String clean = ver.trim();
        return clean.equals("1.0.0.0") || clean.equals("0.0.0.0") || clean.equals("1.0.0") || clean.equals("0.0.0");
    }
}
