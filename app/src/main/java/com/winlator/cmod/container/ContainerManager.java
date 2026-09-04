package com.winlator.cmod.container;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.winlator.cmod.R;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.MSLink;
import com.winlator.cmod.core.OnExtractFileListener;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.xenvironment.ImageFs;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;
import java.io.FileOutputStream;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.Executors;

public class ContainerManager {
    private final ArrayList<Container> containers = new ArrayList<>();
    private final File homeDir;
    private final Context context;

    private boolean isInitialized = false; // New flag to track initialization

    public ContainerManager(Context context) {
        this.context = context;
        File rootDir = ImageFs.find(context).getRootDir();
        homeDir = new File(rootDir, "home");
        loadContainers();
        isInitialized = true;
    }

    // Check if the ContainerManager is fully initialized
    public boolean isInitialized() {
        return isInitialized;
    }

    public ArrayList<Container> getContainers() {
        return containers;
    }

    public void loadContainers() {
        containers.clear();

        File[] files = homeDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory() && file.getName().startsWith(ImageFs.USER + "-")) {
                    int id = -1;
                    try {
                        id = Integer.parseInt(file.getName().replace(ImageFs.USER + "-", ""));

                        Container container = new Container(id, this);
                        container.setRootDir(new File(homeDir, ImageFs.USER + "-" + container.id));

                        File configFile = container.getConfigFile();
                        if (configFile.exists()) {
                            String configStr = FileUtils.readString(configFile);
                            if (configStr != null && !configStr.isEmpty()) {
                                JSONObject data = new JSONObject(configStr);
                                container.loadData(data);
                                containers.add(container);
                            }
                        } else {
                            // Clean up directory if it's empty or has no config
                            File[] contents = file.listFiles();
                            if (contents == null || contents.length == 0) {
                                file.delete();
                            }
                        }
                    } catch (Exception e) {}
                }
            }
        }
        containers.sort(Comparator.comparingInt(c -> c.id));
    }


    public Context getContext() {
        return context;
    }


    public void activateContainer(Container container) {
        container.setRootDir(new File(homeDir, ImageFs.USER+"-"+container.id));
        File file = new File(homeDir, ImageFs.USER);
        file.delete();
        FileUtils.symlink("./"+ImageFs.USER+"-"+container.id, file.getPath());
    }

    public void createContainerAsync(final JSONObject data, ContentsManager contentsManager, Callback<Container> callback) {
        final Handler handler = new Handler();
        Executors.newSingleThreadExecutor().execute(() -> {
            final Container container = createContainer(data, contentsManager);
            handler.post(() -> callback.call(container));
        });
    }

    public void duplicateContainerAsync(Container container, Runnable callback) {
        final Handler handler = new Handler();
        Executors.newSingleThreadExecutor().execute(() -> {
            duplicateContainer(container);
            handler.post(callback);
        });
    }

    public void removeContainerAsync(Container container, Runnable callback) {
        final Handler handler = new Handler();
        Executors.newSingleThreadExecutor().execute(() -> {
            removeContainer(container);
            handler.post(callback);
        });
    }

    private Container createContainer(JSONObject data, ContentsManager contentsManager) {
        try {
            int id = getNextContainerId();
            data.put("id", id);

            if (!data.has("name")) {
                data.put("name", getNextDefaultContainerName());
            }

            File containerDir = new File(homeDir, ImageFs.USER+"-"+id);
            if (!containerDir.exists() && !containerDir.mkdirs()) return null;

            Container container = new Container(id, this);
            container.setRootDir(containerDir);
            container.loadData(data);

            container.setWineVersion(data.getString("wineVersion"));

            if (!extractContainerPatternFile(container, container.getWineVersion(), contentsManager, containerDir, null)) {
                FileUtils.delete(containerDir);
                return null;
            }

//            // Extract the selected graphics driver files
//            String driverVersion = container.getGraphicsDriverVersion();
//            if (!extractGraphicsDriverFiles(driverVersion, containerDir, null)) {
//                FileUtils.delete(containerDir);
//                return null;
//            }

            container.saveData();
            loadContainers();
            return container;
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return null;
    }


    private void duplicateContainer(Container srcContainer) {
        int id = getNextContainerId();

        File dstDir = new File(homeDir, ImageFs.USER + "-" + id);
        if (!dstDir.exists() && !dstDir.mkdirs()) return;

        // Use the refactored copy method that doesn't require a Context for File operations
        if (!FileUtils.copy(srcContainer.getRootDir(), dstDir, file -> FileUtils.chmod(file, 0771))) {
            FileUtils.delete(dstDir);
            return;
        }

        Container dstContainer = new Container(id, this);
        dstContainer.setRootDir(dstDir);
        dstContainer.setName(srcContainer.getName() + " (" + context.getString(R.string._copy) + ")");
        dstContainer.setScreenSize(srcContainer.getScreenSize());
        dstContainer.setEnvVars(srcContainer.getEnvVars());
        dstContainer.setCPUList(srcContainer.getCPUList());
        dstContainer.setCPUListWoW64(srcContainer.getCPUListWoW64());
        dstContainer.setGraphicsDriver(srcContainer.getGraphicsDriver());
        dstContainer.setDXWrapper(srcContainer.getDXWrapper());
        dstContainer.setDXWrapperConfig(srcContainer.getDXWrapperConfig());
        dstContainer.setAudioDriver(srcContainer.getAudioDriver());
        dstContainer.setWinComponents(srcContainer.getWinComponents());
        dstContainer.setDrives(srcContainer.getDrives());
        dstContainer.setShowFPS(srcContainer.isShowFPS());
        dstContainer.setStartupSelection(srcContainer.getStartupSelection());
        dstContainer.setBox64Preset(srcContainer.getBox64Preset());
        dstContainer.setDesktopTheme(srcContainer.getDesktopTheme());
        dstContainer.setWineVersion(srcContainer.getWineVersion());
        dstContainer.saveData();

        loadContainers();
    }


    private void removeContainer(Container container) {
        if (FileUtils.delete(container.getRootDir())) {
            loadContainers();
        }
    }

    public ArrayList<Shortcut> loadShortcuts() {
        ArrayList<Shortcut> shortcuts = new ArrayList<>();
        for (Container container : containers) {
            File desktopDir = container.getDesktopDir();
            ArrayList<File> files = new ArrayList<>();
            if (desktopDir.exists())
                files.addAll(Arrays.asList(desktopDir.listFiles()));
            if (files != null) {
                for (File file : files) {
                    String fileName = file.getName();
                    if (fileName.endsWith(".lnk")) {
                        String filePath = file.getPath();
                        File desktopFile = new File(filePath.substring(0, filePath.lastIndexOf(".")) + ".desktop");
                        if (!desktopFile.exists()) {
                            MSLink.createDesktopFile(file, context);
                            shortcuts.add(new Shortcut(container, desktopFile));
                        }
                    }
                    else if (fileName.endsWith(".desktop")) shortcuts.add(new Shortcut(container, file));
                }
            }
        }

        shortcuts.sort(Comparator.comparing(a -> a.name));
        return shortcuts;
    }

    public String getNextDefaultContainerName() {
        String baseName = context.getString(R.string.container) + "-";
        int number = 1;
        while (true) {
            String name = baseName + number;
            boolean exists = false;
            for (Container c : containers) {
                if (c.getName().equalsIgnoreCase(name)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) return name;
            number++;
        }
    }

    public int getNextContainerId() {
        int id = 1;
        while (true) {
            File file = new File(homeDir, ImageFs.USER + "-" + id);
            if (!file.exists()) return id;
            
            // Check if it's a "ghost" directory (exists but no config)
            if (file.isDirectory()) {
                File configFile = new File(file, ".container");
                if (!configFile.exists()) {
                    // Only delete if it's truly empty. If it has files, we skip it to be safe.
                    File[] contents = file.listFiles();
                    if (contents == null || contents.length == 0) {
                        file.delete();
                        return id;
                    }
                }
            }
            id++;
        }
    }

    public Container getContainerById(int id) {
        for (Container container : containers) if (container.id == id) return container;
        return null;
    }

    private void extractCommonDlls(WineInfo wineInfo, String srcName, String dstName, File containerDir, OnExtractFileListener onExtractFileListener) {
        if (wineInfo == null || wineInfo.path == null || wineInfo.path.isEmpty()) return;

        File srcDir = new File(wineInfo.path + "/lib/wine/" + srcName);
        if (!srcDir.exists()) {
            srcDir = new File(wineInfo.path + "/lib64/wine/" + srcName);
        }
        if (!srcDir.exists()) {
            srcDir = new File(wineInfo.path + "/lib/" + srcName);
        }

        File[] srcfiles = srcDir.listFiles(file -> file.isFile());
        if (srcfiles == null) {
            Log.w("ContainerManager", "Common DLL directory does not exist or has no files: " + srcDir.getAbsolutePath());
            return;
        }

        for (File file : srcfiles) {
            String dllName = file.getName();
            if (dllName.equals("iexplore.exe") && wineInfo.isArm64EC() && srcName.equals("aarch64-windows")) {
                File iexploreFile = new File(wineInfo.path + "/lib/wine/i386-windows/iexplore.exe");
                if (iexploreFile.exists()) file = iexploreFile;
            }
            if (dllName.equals("tabtip.exe") || dllName.equals("icu.dll"))
                continue;
            File dstFile = new File(containerDir, ".wine/drive_c/windows/" + dstName + "/" + dllName);
            if (dstFile.exists()) continue;
            if (onExtractFileListener != null) {
                dstFile = onExtractFileListener.onExtractFile(dstFile, 0);
                if (dstFile == null) continue;
            }
            FileUtils.copy(file, dstFile);
        }
    }

    public static String getPatternUrlForWineVersion(String wineVersion) {
        if (wineVersion == null) return null;
        String lower = wineVersion.toLowerCase(Locale.US);
        if (lower.contains("proton-9.0-arm64ec") || lower.contains("proton-9-arm64ec")) {
            return "https://github.com/GunaCharanTeja/Winlator-Extras/releases/download/proton-9-arm64ec/proton-9.0-arm64ec_container_pattern.tzst";
        } else if (lower.contains("proton-10-arm64ec") || lower.contains("proton-10.0-4-arm64ec") || lower.contains("proton-10")) {
            return "https://github.com/GunaCharanTeja/Winlator-Extras/releases/download/Sd/proton-10-arm64ec_container_pattern.tzst";
        } else if (lower.contains("proton-9.0-x86_64") || lower.contains("proton-9-x86_64")) {
            return "https://github.com/GunaCharanTeja/Winlator-Extras/releases/download/Sd/proton-9.0-x86_64_container_pattern.tzst";
        }
        return null;
    }

    private static File downloadContainerPatternSync(Context context, String urlStr, String versionName) {
        try {
            File patternsDir = new File(context.getFilesDir(), "contents/patterns");
            if (!patternsDir.exists()) patternsDir.mkdirs();
            File dest = new File(patternsDir, versionName + "_container_pattern.tzst");

            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 WinlatorMali");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);

            int status = conn.getResponseCode();
            if (status == HttpURLConnection.HTTP_MOVED_TEMP || status == HttpURLConnection.HTTP_MOVED_PERM || status == 307 || status == 308) {
                String newUrl = conn.getHeaderField("Location");
                conn = (HttpURLConnection) new URL(newUrl).openConnection();
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 WinlatorMali");
            }

            try (InputStream is = conn.getInputStream(); FileOutputStream fos = new FileOutputStream(dest)) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, read);
                }
            }
            if (dest.exists() && dest.length() > 0) {
                return dest;
            }
        } catch (Exception e) {
            Log.e("ContainerManager", "Failed to download pattern for " + versionName, e);
        }
        return null;
    }

    private void ensureWineRegistryExists(File containerDir) {
        File wineDir = new File(containerDir, ".wine");
        if (!wineDir.exists()) wineDir.mkdirs();

        File systemReg = new File(wineDir, "system.reg");
        if (!systemReg.exists() || systemReg.length() == 0) {
            FileUtils.writeString(systemReg, "WINE REGISTRY Version 2\n;; All keys relative to \\\\Machine\n\n#arch=win64\n\n");
        }

        File userReg = new File(wineDir, "user.reg");
        if (!userReg.exists() || userReg.length() == 0) {
            FileUtils.writeString(userReg, "WINE REGISTRY Version 2\n;; All keys relative to \\\\User\n\n#arch=win64\n\n");
        }

        File userDefReg = new File(wineDir, "userdef.reg");
        if (!userDefReg.exists() || userDefReg.length() == 0) {
            FileUtils.writeString(userDefReg, "WINE REGISTRY Version 2\n;; All keys relative to \\\\User\n\n#arch=win64\n\n");
        }

        File dosdevices = new File(wineDir, "dosdevices");
        if (!dosdevices.exists()) dosdevices.mkdirs();

        File driveC = new File(wineDir, "drive_c");
        if (!driveC.exists()) driveC.mkdirs();

        File windows = new File(driveC, "windows");
        if (!windows.exists()) windows.mkdirs();

        File system32 = new File(windows, "system32");
        if (!system32.exists()) system32.mkdirs();

        File syswow64 = new File(windows, "syswow64");
        if (!syswow64.exists()) syswow64.mkdirs();
    }

    public boolean extractContainerPatternFile(Container container, String wineVersion, ContentsManager contentsManager, File containerDir, OnExtractFileListener onExtractFileListener) {
        WineInfo wineInfo = WineInfo.fromIdentifier(context, contentsManager, wineVersion);
        Log.d("ContainerManager", "Extracting pattern for wine: " + wineVersion + " at path: " + wineInfo.path);

        boolean patternExtracted = false;

        // 1. Check if specific container pattern exists on disk
        List<File> candidatePatterns = new ArrayList<>();
        if (wineInfo.path != null && !wineInfo.path.isEmpty()) {
            candidatePatterns.add(new File(wineInfo.path, "prefixPack.tzst"));
            candidatePatterns.add(new File(wineInfo.path, "prefixPack.txz"));
            candidatePatterns.add(new File(wineInfo.path, "container_pattern.tzst"));
            candidatePatterns.add(new File(wineInfo.path, wineVersion + "_container_pattern.tzst"));
            candidatePatterns.add(new File(wineInfo.path, "proton-9.0-arm64ec_container_pattern.tzst"));
            candidatePatterns.add(new File(wineInfo.path, "proton-10-arm64ec_container_pattern.tzst"));
            candidatePatterns.add(new File(wineInfo.path, "proton-9.0-x86_64_container_pattern.tzst"));
        }
        File patternsDir = new File(context.getFilesDir(), "contents/patterns");
        candidatePatterns.add(new File(patternsDir, wineVersion + "_container_pattern.tzst"));
        candidatePatterns.add(new File(patternsDir, wineVersion.replace("-", ".") + "_container_pattern.tzst"));
        candidatePatterns.add(new File(patternsDir, "proton-9.0-arm64ec_container_pattern.tzst"));
        candidatePatterns.add(new File(patternsDir, "proton-10-arm64ec_container_pattern.tzst"));
        candidatePatterns.add(new File(patternsDir, "proton-9.0-x86_64_container_pattern.tzst"));
        candidatePatterns.add(new File(context.getFilesDir(), "imagefs/opt/" + wineVersion + "/prefixPack.tzst"));
        candidatePatterns.add(new File(context.getFilesDir(), "imagefs/opt/" + wineVersion + "/container_pattern.tzst"));

        for (File pFile : candidatePatterns) {
            if (pFile != null && pFile.exists() && pFile.length() > 0) {
                Log.d("ContainerManager", "Found specific container pattern on disk: " + pFile.getAbsolutePath());
                patternExtracted = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, pFile, containerDir, onExtractFileListener);
                if (!patternExtracted) {
                    patternExtracted = TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, pFile, containerDir, onExtractFileListener);
                }
                if (patternExtracted) break;
            }
        }

        // 2. Try extracting specific version container pattern from assets
        if (!patternExtracted) {
            String containerPattern = wineVersion + "_container_pattern.tzst";
            patternExtracted = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, containerPattern, containerDir, onExtractFileListener);
        }

        // 3. If STILL not found, check if it's a known official Winlator Mali Proton version and download the specific container pattern on the fly!
        if (!patternExtracted) {
            String patternUrl = getPatternUrlForWineVersion(wineVersion);
            if (patternUrl != null) {
                Log.d("ContainerManager", "Downloading specific container pattern on-the-fly from: " + patternUrl);
                File downloadedPattern = downloadContainerPatternSync(context, patternUrl, wineVersion);
                if (downloadedPattern != null && downloadedPattern.exists() && downloadedPattern.length() > 0) {
                    patternExtracted = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, downloadedPattern, containerDir, onExtractFileListener);
                    // Also cache to wineInfo.path so subsequent creations are instant
                    if (wineInfo.path != null && new File(wineInfo.path).exists()) {
                        FileUtils.copy(downloadedPattern, new File(wineInfo.path, "prefixPack.tzst"));
                        FileUtils.copy(downloadedPattern, new File(wineInfo.path, "container_pattern.tzst"));
                    }
                }
            }
        }

        // 4. Extract container_pattern_common.tzst from assets as baseline
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "container_pattern_common.tzst", containerDir, onExtractFileListener);

        // 5. Extract common DLLs (system32 / syswow64) from Wine runtime
        try {
            if (wineInfo.isArm64EC())
                extractCommonDlls(wineInfo, "aarch64-windows", "system32", containerDir, onExtractFileListener);
            else
                extractCommonDlls(wineInfo, "x86_64-windows", "system32", containerDir, onExtractFileListener);

            extractCommonDlls(wineInfo, "i386-windows", "syswow64", containerDir, onExtractFileListener);
        } catch (Exception e) {
            Log.e("ContainerManager", "Error extracting common DLLs", e);
        }

        // 6. Ensure Wine registry files are initialized and valid
        ensureWineRegistryExists(containerDir);

        return true;
    }

    public Container getContainerForShortcut(Shortcut shortcut) {
        // Search for the container by its ID
        for (Container container : containers) {
            if (container.id == shortcut.getContainerId()) {
                return container;
            }
        }
        return null;  // Return null if no matching container is found
    }

    // Utility method to run on UI thread
    private void runOnUiThread(Runnable action) {
        new Handler(Looper.getMainLooper()).post(action);
    }



}
