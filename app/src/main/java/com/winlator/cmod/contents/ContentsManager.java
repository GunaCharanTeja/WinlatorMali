package com.winlator.cmod.contents;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;

import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.TarCompressorUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class ContentsManager {
    public static final String PROFILE_NAME = "profile.json";

    public static final String BANNERLATOR_PROFILES = "https://raw.githubusercontent.com/The412Banner/winlator-contents/main/contents.json";
    public static final String WINNATIVE_PROFILES = "https://raw.githubusercontent.com/nicholasx417/WinNative-Components/refs/heads/main/contents.json";
    public static final String STEVENMXZ_PROFILES = "https://raw.githubusercontent.com/StevenMXZ/Winlator-Contents/main/contents.json";
    public static final String WINLATOR_MALI_BASE_URL = "https://github.com/GunaCharanTeja/Winlator-Extras/releases/download/Sd/";
    public static final String REMOTE_PROFILES = BANNERLATOR_PROFILES;

    public static final String[] DXVK_TRUST_FILES = {"${system32}/d3d8.dll", "${system32}/d3d9.dll", "${system32}/d3d10.dll", "${system32}/d3d10_1.dll",
            "${system32}/d3d10core.dll", "${system32}/d3d11.dll", "${system32}/dxgi.dll", "${syswow64}/d3d8.dll", "${syswow64}/d3d9.dll", "${syswow64}/d3d10.dll",
            "${syswow64}/d3d10_1.dll", "${syswow64}/d3d10core.dll", "${syswow64}/d3d11.dll", "${syswow64}/dxgi.dll"};
    public static final String[] VKD3D_TRUST_FILES = {"${system32}/d3d12core.dll", "${system32}/d3d12.dll",
            "${syswow64}/d3d12core.dll", "${syswow64}/d3d12.dll"};
    public static final String[] BOX64_TRUST_FILES = {"${bindir}/box64"};
    public static final String[] WOWBOX64_TRUST_FILES = {"${system32}/wowbox64.dll"};
    public static final String[] FEXCORE_TRUST_FILES = {
            "${system32}/libwow64fex.dll", "${system32}/libarm64ecfex.dll",
            "${libdir}/wine/aarch64-unix/libwow64fex.so", "${libdir}/wine/aarch64-unix/libarm64ecfex.so"
    };
    private Map<String, String> dirTemplateMap;
    private Map<ContentProfile.ContentType, List<String>> trustedFilesMap;

    private SharedPreferences preferences;

    public enum InstallFailedReason {
        ERROR_NOSPACE,
        ERROR_BADTAR,
        ERROR_NOPROFILE,
        ERROR_BADPROFILE,
        ERROR_MISSINGFILES,
        ERROR_EXIST,
        ERROR_UNTRUSTPROFILE,
        ERROR_UNKNOWN
    }

    public enum ContentDirName {
        CONTENT_MAIN_DIR_NAME("contents"),
        CONTENT_WINE_DIR_NAME("wine"),
        CONTENT_PROTON_DIR_NAME("proton"),
        CONTENT_DXVK_DIR_NAME("dxvk"),
        CONTENT_VKD3D_DIR_NAME("vkd3d"),
        CONTENT_BOX64_DIR_NAME("box64");

        private String name;

        ContentDirName(String name) {
            this.name = name;
        }

        @NonNull
        @Override
        public String toString() {
            return name;
        }
    }

    private final Context context;
    private HashMap<ContentProfile.ContentType, List<ContentProfile>> profilesMap;
    private ArrayList<ContentProfile> remoteProfiles = new ArrayList<>();

    public ContentsManager(Context context) {
        this.context = context;
        this.preferences = context.getSharedPreferences("contents_manager_prefs", Context.MODE_PRIVATE);
    }

    public void setGraphicsDriverInstalled(String driverVersion, boolean installed) {
        preferences.edit().putBoolean("graphics_driver_installed_" + driverVersion, installed).apply();
    }

    public interface OnInstallFinishedCallback {
        void onFailed(InstallFailedReason reason, Exception e);
        void onSucceed(ContentProfile profile);
    }

    public synchronized void setRemoteProfiles(String json) {
        try {
            ArrayList<ContentProfile> newProfiles = new ArrayList<>();

            if (json != null && !json.isEmpty()) {
                JSONArray content = new JSONArray(json);
                for (int i = 0; i < content.length(); i++) {
                    try {
                        JSONObject object = content.getJSONObject(i);
                        String url = object.optString("remoteUrl", "");
                        if (url.isEmpty()) continue;

                        ContentProfile remoteProfile = new ContentProfile();
                        remoteProfile.remoteUrl = url;

                        String verName = object.optString("verName", object.optString("versionName", object.optString("name", "")));
                        if (verName.isEmpty() || verName.equalsIgnoreCase("null")) {
                            try {
                                String filename = new File(new java.net.URL(url).getPath()).getName();
                                verName = filename.replace(".wcp", "").replace(".tzst", "").replace(".tar.xz", "").replace(".txz", "");
                            } catch (Exception e) {
                                verName = "Component-" + i;
                            }
                        }
                        remoteProfile.verName = verName;

                        String typeStr = object.optString("type", "").trim();
                        String lowerAll = (typeStr + " " + verName + " " + url).toLowerCase();

                        if (lowerAll.contains("vkd3d") || lowerAll.contains("d3d12")) {
                            remoteProfile.type = ContentProfile.ContentType.CONTENT_TYPE_VKD3D;
                        } else if (lowerAll.contains("dxvk") || lowerAll.contains("d7vk") || lowerAll.contains("d8vk") || lowerAll.contains("d9vk") || lowerAll.contains("d3d9") || lowerAll.contains("d3d11") || lowerAll.contains("dxgi")) {
                            remoteProfile.type = ContentProfile.ContentType.CONTENT_TYPE_DXVK;
                        } else if (lowerAll.contains("wowbox64")) {
                            remoteProfile.type = ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64;
                        } else if (lowerAll.contains("box64")) {
                            remoteProfile.type = ContentProfile.ContentType.CONTENT_TYPE_BOX64;
                        } else if (lowerAll.contains("fexcore") || lowerAll.contains("fex")) {
                            remoteProfile.type = ContentProfile.ContentType.CONTENT_TYPE_FEXCORE;
                        } else if (typeStr.equalsIgnoreCase("Proton") || lowerAll.contains("proton") || lowerAll.contains("wine-ge") || lowerAll.contains("winege")) {
                            remoteProfile.type = ContentProfile.ContentType.CONTENT_TYPE_PROTON;
                        } else if (typeStr.equalsIgnoreCase("Wine") || lowerAll.contains("wine")) {
                            remoteProfile.type = ContentProfile.ContentType.CONTENT_TYPE_WINE;
                        } else {
                            remoteProfile.type = ContentProfile.ContentType.getTypeByName(typeStr);
                            if (remoteProfile.type == null) {
                                remoteProfile.type = ContentProfile.ContentType.CONTENT_TYPE_WINE;
                            }
                        }

                        int verCode = 0;
                        if (object.has("verCode")) {
                            try {
                                verCode = object.getInt("verCode");
                            } catch (Exception e) {
                                try {
                                    verCode = Integer.parseInt(object.getString("verCode").trim());
                                } catch (Exception ignored) {}
                            }
                        } else if (object.has("versionCode")) {
                            verCode = object.optInt("versionCode", 0);
                        }
                        remoteProfile.verCode = verCode;
                        // Check explicit date fields in JSON
                        long releaseDate = 0L;
                        String dateStr = object.optString("date", object.optString("releaseDate", object.optString("uploadedAt", "")));
                        if (!dateStr.isEmpty()) {
                            try {
                                releaseDate = java.time.Instant.parse(dateStr).toEpochMilli();
                            } catch (Exception ignored) {}
                        }
                        if (releaseDate == 0L) {
                            releaseDate = estimateReleaseDate(remoteProfile.verName, url);
                        }
                        remoteProfile.releaseDate = releaseDate;

                        newProfiles.add(remoteProfile);
                    } catch (Exception e) {
                        Log.w("ContentsManager", "Error parsing remote profile at index " + i, e);
                    }
                }
            }
            remoteProfiles = newProfiles;
        } catch (Exception e) {
            Log.e("ContentsManager", "Failed to parse remote JSON", e);
        }
        syncContents();
    }

    public static final java.util.Map<String, String> sizeCache = new java.util.concurrent.ConcurrentHashMap<>();
    public static final java.util.Map<String, Long> dateCache = new java.util.concurrent.ConcurrentHashMap<>();

    public static void fetchRemoteSizeAsync(ContentProfile profile, Runnable onFetched) {
        if (profile == null || profile.remoteUrl == null || profile.remoteUrl.isEmpty()) return;
        if (sizeCache.containsKey(profile.remoteUrl)) {
            profile.sizeFormatted = sizeCache.get(profile.remoteUrl);
            if (dateCache.containsKey(profile.remoteUrl)) {
                profile.releaseDate = dateCache.get(profile.remoteUrl);
            }
            if (onFetched != null) onFetched.run();
            return;
        }

        java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
            try {
                java.net.URL url = new java.net.URL(profile.remoteUrl);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("HEAD");
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.connect();

                long length = conn.getContentLengthLong();
                if (length > 0) {
                    profile.size = length;
                    profile.sizeFormatted = Downloader.formatFileSize(length);
                    sizeCache.put(profile.remoteUrl, profile.sizeFormatted);
                }

                long lastModified = conn.getLastModified();
                if (lastModified > 0) {
                    profile.releaseDate = lastModified;
                    dateCache.put(profile.remoteUrl, lastModified);
                }

                if (onFetched != null) onFetched.run();
                conn.disconnect();
            } catch (Exception ignored) {}
        });
    }

    public static long estimateReleaseDate(String verName, String url) {
        if (verName == null) verName = "";
        if (url == null) url = "";
        String combined = (verName + " " + url).toLowerCase();

        // 1. Try 6-digit date like 241214 (YYMMDD -> 2024-12-14)
        java.util.regex.Matcher m6 = java.util.regex.Pattern.compile("(?:20)?(2[0-9])(0[1-9]|1[0-2])(0[1-9]|[12][0-9]|3[01])").matcher(combined);
        if (m6.find()) {
            try {
                int yy = Integer.parseInt(m6.group(1)) + 2000;
                int mm = Integer.parseInt(m6.group(2));
                int dd = Integer.parseInt(m6.group(3));
                return java.time.LocalDate.of(yy, mm, dd).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
            } catch (Exception ignored) {}
        }

        // 2. Try 4-digit date like 2608, 2601, 2512 (YYMM -> 2026-08)
        java.util.regex.Matcher m4 = java.util.regex.Pattern.compile("\\b(2[3-9])(0[1-9]|1[0-2])\\b").matcher(combined);
        if (m4.find()) {
            try {
                int yy = Integer.parseInt(m4.group(1)) + 2000;
                int mm = Integer.parseInt(m4.group(2));
                return java.time.LocalDate.of(yy, mm, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
            } catch (Exception ignored) {}
        }

        // 3. Try Wine version major (wine-11 -> 2026, wine-10 -> 2025, wine-9 -> 2024, wine-8 -> 2023)
        java.util.regex.Matcher mWine = java.util.regex.Pattern.compile("(?:wine|proton)[-_]?(?:ge[-_]?)?([0-9]+)(?:\\.([0-9]+))?").matcher(combined);
        if (mWine.find()) {
            try {
                int major = Integer.parseInt(mWine.group(1));
                int minor = mWine.group(2) != null ? Integer.parseInt(mWine.group(2)) : 0;
                int year = 2015 + major;
                int month = Math.min(12, Math.max(1, (minor % 12) + 1));
                return java.time.LocalDate.of(year, month, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
            } catch (Exception ignored) {}
        }

        // 4. Try DXVK / D7VK / VKD3D versions
        java.util.regex.Matcher mDxvk = java.util.regex.Pattern.compile("(?:dxvk|d7vk|d8vk|d9vk|vkd3d)[-_]?([0-9]+)\\.([0-9]+)(?:\\.([0-9]+))?").matcher(combined);
        if (mDxvk.find()) {
            try {
                int major = Integer.parseInt(mDxvk.group(1));
                int minor = Integer.parseInt(mDxvk.group(2));
                int patch = mDxvk.group(3) != null ? Integer.parseInt(mDxvk.group(3)) : 0;
                int year = major >= 3 ? 2026 : (major == 2 ? 2022 + Math.min(4, minor / 3) : 2020);
                int month = Math.min(12, Math.max(1, (minor % 12) + 1));
                return java.time.LocalDate.of(year, month, Math.min(28, patch * 3 + 1)).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
            } catch (Exception ignored) {}
        }

        // 5. Try WOWBox64 / Box64 versions
        java.util.regex.Matcher mBox = java.util.regex.Pattern.compile("(?:wowbox64|box64)[-_]?0\\.([0-9]+)(?:\\.([0-9]+))?").matcher(combined);
        if (mBox.find()) {
            try {
                int minor = Integer.parseInt(mBox.group(1));
                int patch = mBox.group(2) != null ? Integer.parseInt(mBox.group(2)) : 0;
                int year = minor >= 4 ? 2025 + (patch >= 5 ? 1 : 0) : 2023 + (minor / 2);
                int month = Math.min(12, Math.max(1, (patch * 2 % 12) + 1));
                return java.time.LocalDate.of(year, month, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
            } catch (Exception ignored) {}
        }

        // 6. Generic semantic version fallback (e.g. 2.7.1, 3.0.1, 0.4.5)
        java.util.regex.Matcher mSem = java.util.regex.Pattern.compile("([0-9]+)\\.([0-9]+)(?:\\.([0-9]+))?").matcher(combined);
        if (mSem.find()) {
            try {
                int major = Integer.parseInt(mSem.group(1));
                int minor = Integer.parseInt(mSem.group(2));
                int patch = mSem.group(3) != null ? Integer.parseInt(mSem.group(3)) : 0;
                int year = major >= 10 ? 2015 + major : (major >= 2 ? 2022 + major : 2023);
                int month = Math.min(12, Math.max(1, (minor % 12) + 1));
                return java.time.LocalDate.of(year, month, Math.min(28, patch + 1)).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
            } catch (Exception ignored) {}
        }

        return 0L;
    }

    public synchronized void syncContents() {
        profilesMap = new HashMap<>();

        for (ContentProfile.ContentType type : ContentProfile.ContentType.values()) {
            profilesMap.put(type, new LinkedList<>());
        }

        for (ContentProfile.ContentType type : ContentProfile.ContentType.values()) {
            List<ContentProfile> profiles = profilesMap.get(type);

            // Load local profiles from disk
            File typeFile = getContentTypeDir(context, type);
            File[] fileList = typeFile.listFiles();
            if (fileList != null) {
                for (File file : fileList) {
                    File proFile = new File(file, PROFILE_NAME);
                    if (proFile.exists() && proFile.isFile()) {
                        ContentProfile profile = readProfile(proFile);
                        if (profile != null) profiles.add(profile);
                    }
                }
            }

            // Also check /opt/ for installed Wine/Proton runtimes
            if (type == ContentProfile.ContentType.CONTENT_TYPE_WINE || type == ContentProfile.ContentType.CONTENT_TYPE_PROTON) {
                File optDir = new File(context.getFilesDir(), "imagefs/opt");
                File[] optFiles = optDir.listFiles();
                if (optFiles != null) {
                    for (File f : optFiles) {
                        if (f.isDirectory() && (f.getName().startsWith("wine-") || f.getName().startsWith("proton-"))) {
                            ContentProfile p = new ContentProfile();
                            p.type = f.getName().startsWith("proton-") ? ContentProfile.ContentType.CONTENT_TYPE_PROTON : ContentProfile.ContentType.CONTENT_TYPE_WINE;
                            p.verName = f.getName();
                            p.verCode = 1;
                            p.wineBinPath = f.getAbsolutePath() + "/bin";
                            p.wineLibPath = f.getAbsolutePath() + "/lib";
                            p.winePrefixPack = f.getAbsolutePath() + "/prefixPack.txz";
                            boolean exists = false;
                            for (ContentProfile cp : profiles) {
                                if (cp.verName != null && cp.verName.equalsIgnoreCase(p.verName)) {
                                    exists = true;
                                    break;
                                }
                            }
                            if (!exists) profiles.add(p);
                        }
                    }
                }
            }

            // Add remote profiles
            if (remoteProfiles != null) {
                List<ContentProfile> snapshot = new ArrayList<>(remoteProfiles);
                for (ContentProfile remote : snapshot) {
                    if (remote != null && remote.type == type) {
                        boolean exists = false;
                        for (ContentProfile cp : profiles) {
                            if (cp.verName != null && cp.verName.equalsIgnoreCase(remote.verName)) {
                                exists = true;
                                break;
                            }
                        }
                        if (!exists) profiles.add(remote);
                    }
                }
            }
        }
    }

    public void extraContentFile(Uri uri, OnInstallFinishedCallback callback) {
        cleanTmpDir(context);
        File file = getTmpDir(context);

        boolean ret = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, uri, file);
        if (!ret)
            ret = TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, context, uri, file);
        if (!ret) {
            callback.onFailed(InstallFailedReason.ERROR_BADTAR, null);
            return;
        }

        File proFile = new File(file, PROFILE_NAME);
        if (!proFile.exists()) {
            // Auto-detect Wine/Proton tarball without a manifest
            File binDir = new File(file, "bin");
            File libDir = new File(file, "lib");
            if (binDir.exists() && libDir.exists()) {
                String name = new File(uri.getPath()).getName().replace(".tzst", "").replace(".wcp", "").replace(".tar.xz", "");
                ContentProfile autoProfile = new ContentProfile();
                autoProfile.type = name.contains("proton") ? ContentProfile.ContentType.CONTENT_TYPE_PROTON : ContentProfile.ContentType.CONTENT_TYPE_WINE;
                autoProfile.verName = name;
                autoProfile.verCode = 1;
                autoProfile.desc = "Auto-installed Wine runtime";
                autoProfile.wineBinPath = "bin";
                autoProfile.wineLibPath = "lib";
                autoProfile.winePrefixPack = "prefixPack.txz";
                finishInstallWineDirect(file, autoProfile, callback);
                return;
            }

            callback.onFailed(InstallFailedReason.ERROR_NOPROFILE, null);
            return;
        }

        ContentProfile profile = readProfile(proFile);
        if (profile == null) {
            callback.onFailed(InstallFailedReason.ERROR_BADPROFILE, null);
            return;
        }

        callback.onSucceed(profile);
    }

    private void finishInstallWineDirect(File srcDir, ContentProfile profile, OnInstallFinishedCallback callback) {
        File optDir = new File(context.getFilesDir(), "imagefs/opt/" + profile.verName);
        if (!optDir.exists()) optDir.mkdirs();

        if (FileUtils.copy(srcDir, optDir)) {
            FileUtils.delete(srcDir);
            callback.onSucceed(profile);
        } else {
            callback.onFailed(InstallFailedReason.ERROR_UNKNOWN, null);
        }
    }

    public void applyContent(ContentProfile profile) {
        if (profile == null) return;
        File installDir = getInstallDir(context, profile);
        if (profile.fileList != null) {
            for (ContentProfile.ContentFile file : profile.fileList) {
                File src = new File(installDir, file.source);
                String targetPath = getPathFromTemplate(file.target);
                File dst = new File(targetPath);
                if (src.exists()) {
                    File parent = dst.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    FileUtils.copy(src, dst);
                }
            }
        }
    }

    public String getPathFromTemplate(String template) {
        if (template == null) return "";
        File rootDir = new File(context.getFilesDir(), "imagefs");
        String sys32 = rootDir.getAbsolutePath() + "/usr/lib/wine";
        String syswow64 = rootDir.getAbsolutePath() + "/usr/lib64/wine";
        String bin = rootDir.getAbsolutePath() + "/usr/local/bin";
        String lib = rootDir.getAbsolutePath() + "/usr/local/lib";

        return template.replace("${system32}", sys32)
                       .replace("${syswow64}", syswow64)
                       .replace("${bindir}", bin)
                       .replace("${libdir}", lib)
                       .replace("${rootdir}", rootDir.getAbsolutePath());
    }

    public void finishInstallContent(ContentProfile profile, OnInstallFinishedCallback callback) {
        File installPath = getInstallDir(context, profile);
        if (installPath.exists()) {
            callback.onFailed(InstallFailedReason.ERROR_EXIST, null);
            return;
        }

        if (!installPath.mkdirs()) {
            callback.onFailed(InstallFailedReason.ERROR_UNKNOWN, null);
            return;
        }

        if (!getTmpDir(context).renameTo(installPath)) {
            callback.onFailed(InstallFailedReason.ERROR_UNKNOWN, null);
            return;
        }

        callback.onSucceed(profile);
    }

    public ContentProfile readProfile(File file) {
        try {
            ContentProfile profile = new ContentProfile();
            JSONObject profileJSONObject = new JSONObject(FileUtils.readString(file));
            String typeName = profileJSONObject.getString(ContentProfile.MARK_TYPE);
            String verName = profileJSONObject.getString(ContentProfile.MARK_VERSION_NAME);
            int verCode = profileJSONObject.getInt(ContentProfile.MARK_VERSION_CODE);
            String desc = profileJSONObject.optString(ContentProfile.MARK_DESC, "");

            profile.type = ContentProfile.ContentType.getTypeByName(typeName);
            profile.verName = verName;
            profile.verCode = verCode;
            profile.desc = desc;
            profile.releaseDate = estimateReleaseDate(verName, "");

            if (typeName.equals(ContentProfile.ContentType.CONTENT_TYPE_WINE.toString()) || typeName.equals(ContentProfile.ContentType.CONTENT_TYPE_PROTON.toString())) {
                JSONObject wineJSONObject = profileJSONObject.optJSONObject(ContentProfile.MARK_WINE);
                if (wineJSONObject != null) {
                    profile.wineLibPath = wineJSONObject.optString(ContentProfile.MARK_WINE_LIBPATH, "lib");
                    profile.wineBinPath = wineJSONObject.optString(ContentProfile.MARK_WINE_BINPATH, "bin");
                    profile.winePrefixPack = wineJSONObject.optString(ContentProfile.MARK_WINE_PREFIX_PACK, "prefixPack.txz");
                }
            }

            return profile;
        } catch (Exception e) {
            return null;
        }
    }

    public static File getContentDir(Context context) {
        File dir = new File(context.getFilesDir(), ContentDirName.CONTENT_MAIN_DIR_NAME.toString());
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File getContentTypeDir(Context context, ContentProfile.ContentType type) {
        String subName = switch (type) {
            case CONTENT_TYPE_WINE -> ContentDirName.CONTENT_WINE_DIR_NAME.toString();
            case CONTENT_TYPE_PROTON -> ContentDirName.CONTENT_PROTON_DIR_NAME.toString();
            case CONTENT_TYPE_DXVK -> ContentDirName.CONTENT_DXVK_DIR_NAME.toString();
            case CONTENT_TYPE_VKD3D -> ContentDirName.CONTENT_VKD3D_DIR_NAME.toString();
            case CONTENT_TYPE_BOX64 -> ContentDirName.CONTENT_BOX64_DIR_NAME.toString();
            default -> "misc";
        };
        File dir = new File(getContentDir(context), subName);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File getInstallDir(Context context, ContentProfile profile) {
        return new File(getContentTypeDir(context, profile.type), profile.verName + "-" + profile.verCode);
    }

    private static File getTmpDir(Context context) {
        File dir = new File(context.getCacheDir(), "contents_tmp");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static void cleanTmpDir(Context context) {
        FileUtils.delete(getTmpDir(context));
    }

    public List<ContentProfile> getProfiles(ContentProfile.ContentType type) {
        if (profilesMap == null) syncContents();
        List<ContentProfile> list = profilesMap.get(type);
        return list != null ? list : new ArrayList<>();
    }

    public ContentProfile getProfileByEntryName(String entryName) {
        if (profilesMap == null) syncContents();
        for (List<ContentProfile> list : profilesMap.values()) {
            for (ContentProfile p : list) {
                if (getEntryName(p).equalsIgnoreCase(entryName) || p.verName.equalsIgnoreCase(entryName)) {
                    return p;
                }
            }
        }
        return null;
    }

    public static String getEntryName(ContentProfile profile) {
        if (profile == null) return "";
        return profile.verName + (profile.verCode > 0 ? "-" + profile.verCode : "");
    }

    public List<ContentProfile> getInstalledProfiles(ContentProfile.ContentType type) {
        List<ContentProfile> installed = new ArrayList<>();
        
        // 1. Local contents profiles in /contents/<type>/
        File typeFile = getContentTypeDir(context, type);
        File[] fileList = typeFile.listFiles();
        if (fileList != null) {
            for (File file : fileList) {
                File proFile = new File(file, PROFILE_NAME);
                if (proFile.exists() && proFile.isFile()) {
                    ContentProfile profile = readProfile(proFile);
                    if (profile != null) {
                        profile.size = FileUtils.getDirectorySize(file);
                        profile.sizeFormatted = Downloader.formatFileSize(profile.size);
                        profile.releaseDate = estimateReleaseDate(profile.verName, "");
                        installed.add(profile);
                    }
                }
            }
        }

        // 2. /opt/ Wine/Proton directories
        if (type == ContentProfile.ContentType.CONTENT_TYPE_WINE || type == ContentProfile.ContentType.CONTENT_TYPE_PROTON) {
            File optDir = new File(context.getFilesDir(), "imagefs/opt");
            File[] optFiles = optDir.listFiles();
            if (optFiles != null) {
                for (File f : optFiles) {
                    if (f.isDirectory() && (f.getName().startsWith("wine-") || f.getName().startsWith("proton-"))) {
                        boolean isProton = f.getName().startsWith("proton-");
                        if ((type == ContentProfile.ContentType.CONTENT_TYPE_PROTON && isProton) || (type == ContentProfile.ContentType.CONTENT_TYPE_WINE && !isProton)) {
                            ContentProfile p = new ContentProfile();
                            p.type = type;
                            p.verName = f.getName();
                            p.verCode = 1;
                            p.wineBinPath = f.getAbsolutePath() + "/bin";
                            p.wineLibPath = f.getAbsolutePath() + "/lib";
                            p.winePrefixPack = f.getAbsolutePath() + "/prefixPack.txz";
                            p.size = FileUtils.getDirectorySize(f);
                            p.sizeFormatted = Downloader.formatFileSize(p.size);
                            p.desc = "Installed Locally on Device (" + p.sizeFormatted + ")";
                            p.releaseDate = estimateReleaseDate(p.verName, "");
                            installed.add(p);
                        }
                    }
                }
            }
        }
        return installed;
    }

    public static String getContainerUsingWine(Context context, ContentProfile profile) {
        if (context == null || profile == null) return null;
        com.winlator.cmod.container.ContainerManager containerManager = new com.winlator.cmod.container.ContainerManager(context);
        String verName = profile.verName != null ? profile.verName.trim() : "";
        String entryName = getEntryName(profile).trim();

        for (com.winlator.cmod.container.Container container : containerManager.getContainers()) {
            String cWine = container.getWineVersion();
            if (cWine != null && !cWine.isEmpty()) {
                cWine = cWine.trim();
                if (cWine.equalsIgnoreCase(verName) || cWine.equalsIgnoreCase(entryName) || cWine.startsWith(verName) || (verName.startsWith(cWine) && !cWine.equals("proton-") && !cWine.equals("wine-"))) {
                    return container.getName();
                }
            }
        }
        return null;
    }

    public void removeContent(ContentProfile profile) {
        File installPath = getInstallDir(context, profile);
        if (installPath.exists()) {
            FileUtils.delete(installPath);
        }
        File optPath = new File(context.getFilesDir(), "imagefs/opt/" + profile.verName);
        if (optPath.exists()) {
            FileUtils.delete(optPath);
        }
        syncContents();
    }

    public List<ContentProfile.ContentFile> getUnTrustedContentFiles(ContentProfile profile) {
        return new ArrayList<>();
    }
}
