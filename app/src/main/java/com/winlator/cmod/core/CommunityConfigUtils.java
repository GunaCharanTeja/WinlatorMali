package com.winlator.cmod.core;

import android.content.Context;
import android.os.Build;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.box64.Box64PresetManager;
import com.winlator.cmod.fexcore.FEXCorePresetManager;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.util.Iterator;

public class CommunityConfigUtils {
    public static JSONObject exportConfig(Context context, Shortcut shortcut) {
        return exportConfig(context, shortcut, null, null, null, null, null);
    }

    public static JSONObject exportConfig(Context context, Shortcut shortcut, String overrideName, String overrideSteamId, String overrideImage, String notes, String configTitle) {
        try {
            String wineVersion = shortcut.container.getWineVersion();
            // Only allow standard versions for sharing
            if (!wineVersion.equals("proton-9.0-x86_64") && !wineVersion.equals("proton-9.0-arm64ec") && !wineVersion.equals("proton-10-arm64ec")) {
                return null;
            }

            JSONObject root = new JSONObject();
            JSONObject meta = new JSONObject();
            meta.put("version", "1.0");
            meta.put("app_source", "winlator-mali");
            
            String gameName = overrideName != null ? overrideName : shortcut.name;
            meta.put("game_name", gameName);

            if (configTitle != null && !configTitle.isEmpty()) meta.put("config_title", configTitle);
            
            String steamId = overrideSteamId != null ? overrideSteamId : shortcut.getExtra("steam_id");
            if (!steamId.isEmpty()) meta.put("steam_id", steamId);

            String communityImage = overrideImage != null ? overrideImage : shortcut.getExtra("community_image");
            if (!communityImage.isEmpty()) meta.put("community_image", communityImage);

            if (notes != null && !notes.isEmpty()) meta.put("notes", notes);
            
            JSONObject device = new JSONObject();
            device.put("manufacturer", Build.MANUFACTURER);
            device.put("model", Build.MODEL);
            device.put("soc", Build.BOARD);
            device.put("gpu", GPUInformation.getRenderer(null, context));

            // RAM and Storage Specs
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            ((android.app.ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
            long totalRamGb = (long) Math.ceil(mi.totalMem / (1024.0 * 1024.0 * 1024.0));
            device.put("ram", totalRamGb + "GB");

            java.io.File dataDir = android.os.Environment.getDataDirectory();
            android.os.StatFs stat = new android.os.StatFs(dataDir.getPath());
            long totalStorageGb = (long) Math.ceil((stat.getBlockCountLong() * stat.getBlockSizeLong()) / (1024.0 * 1024.0 * 1024.0));
            device.put("storage", totalStorageGb + "GB");
            
            meta.put("device", device);
            meta.put("timestamp", System.currentTimeMillis() / 1000);
            root.put("meta", meta);

            JSONObject containerJson = new JSONObject();
            com.winlator.cmod.container.Container c = shortcut.container;
            
            // Whitelist of optimized settings (Skip sensitive things like drives, theme, etc.)
            containerJson.put("wineVersion", wineVersion);
            containerJson.put("graphicsDriver", c.getGraphicsDriver());
            containerJson.put("graphicsDriverConfig", c.getGraphicsDriverConfig());
            containerJson.put("dxwrapper", c.getDXWrapper());
            containerJson.put("dxwrapperConfig", c.getDXWrapperConfig());
            containerJson.put("audioDriver", c.getAudioDriver());
            containerJson.put("emulator", c.getEmulator());
            containerJson.put("box64Version", c.getBox64Version());
            String box64Preset = c.getBox64Preset();
            if (box64Preset != null && box64Preset.startsWith("custom")) {
                com.winlator.cmod.box64.Box64Preset preset = Box64PresetManager.getPreset("box64", context, box64Preset);
                if (preset != null) {
                    containerJson.put("box64Preset", "custom");
                    containerJson.put("box64PresetName", preset.name);
                    containerJson.put("box64PresetVars", Box64PresetManager.getEnvVars("box64", context, box64Preset).toString());
                } else {
                    containerJson.put("box64Preset", "compatibility");
                }
            } else {
                containerJson.put("box64Preset", box64Preset);
            }

            containerJson.put("fexcoreVersion", c.getFEXCoreVersion());
            String fexcorePreset = c.getFEXCorePreset();
            if (fexcorePreset != null && fexcorePreset.startsWith("custom")) {
                com.winlator.cmod.fexcore.FEXCorePreset preset = FEXCorePresetManager.getPreset(context, fexcorePreset);
                if (preset != null) {
                    containerJson.put("fexcorePreset", "custom");
                    containerJson.put("fexcorePresetName", preset.name);
                    containerJson.put("fexcorePresetVars", FEXCorePresetManager.getEnvVars(context, fexcorePreset).toString());
                } else {
                    containerJson.put("fexcorePreset", "compatibility");
                }
            } else {
                containerJson.put("fexcorePreset", fexcorePreset);
            }

            containerJson.put("cpuList", c.getCPUList());
            containerJson.put("cpuListWoW64", c.getCPUListWoW64());
            containerJson.put("screenSize", c.getScreenSize());
            containerJson.put("wincomponents", c.getWinComponents());
            containerJson.put("showFPS", c.isShowFPS());
            containerJson.put("fullscreenStretched", c.isFullscreenStretched());
            containerJson.put("startupSelection", c.getStartupSelection());

            // Only export envVars if different from default
            String envVarsStr = c.getEnvVars();
            if (!envVarsStr.equals(com.winlator.cmod.container.Container.DEFAULT_ENV_VARS)) {
                containerJson.put("envVars", envVarsStr);
            }

            root.put("container", containerJson);

            JSONObject shortcutJson = new JSONObject();
            shortcutJson.put("name", shortcut.name);
            shortcutJson.put("path", shortcut.path);
            
            // Only export relevant extraData (Exclude UUIDs and paths)
            JSONObject shortcutExtra = new JSONObject();
            JSONObject originalExtra = shortcut.getExtraData();
            Iterator<String> keys = originalExtra.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!key.equals("uuid") && !key.equals("customCoverArtPath") && !key.equals("id") && !key.equals("wineVersion")) {
                    shortcutExtra.put(key, originalExtra.get(key));
                }
            }
            shortcutJson.put("extraData", shortcutExtra);

            root.put("shortcut", shortcutJson);

            return root;
        } catch (JSONException e) { return null; }
    }

    public static void importConfig(Context context, JSONObject root, ContainerManager containerManager, Callback<Boolean> callback) {
        importConfig(context, root, containerManager, null, callback);
    }

    public static void importConfig(Context context, JSONObject root, ContainerManager containerManager, File exeFile, Callback<Boolean> callback) {
        try {
            JSONObject containerJson = root.getJSONObject("container");
            JSONObject shortcutJson = root.getJSONObject("shortcut");
            JSONObject meta = root.getJSONObject("meta");
            JSONObject shortcutExtra = shortcutJson.optJSONObject("extraData");

            ContentsManager contentsManager = new ContentsManager(context);
            
            // Ensure wineVersion is present and installed
            String wineVersion = containerJson.optString("wineVersion", WineInfo.MAIN_WINE_VERSION.identifier());
            if (contentsManager.getProfileByEntryName(wineVersion) == null && !wineVersion.equals(WineInfo.MAIN_WINE_VERSION.identifier())) {
                wineVersion = WineInfo.MAIN_WINE_VERSION.identifier();
            }
            containerJson.put("wineVersion", wineVersion);

            // Reconstruct custom Box64 preset if included
            String box64Preset = containerJson.optString("box64Preset", "compatibility");
            if (box64Preset.equals("custom")) {
                String presetName = containerJson.optString("box64PresetName", "Imported Box64 Preset");
                String presetVars = containerJson.optString("box64PresetVars", "");
                if (!presetVars.isEmpty()) {
                    int nextId = Box64PresetManager.getNextPresetId(context, "box64");
                    Box64PresetManager.editPreset("box64", context, null, presetName, new EnvVars(presetVars));
                    box64Preset = "custom-" + nextId;
                } else {
                    box64Preset = "compatibility";
                }
            }
            containerJson.put("box64Preset", box64Preset);

            // Reconstruct custom FEXCore preset if included
            String fexcorePreset = containerJson.optString("fexcorePreset", "compatibility");
            if (fexcorePreset.equals("custom")) {
                String presetName = containerJson.optString("fexcorePresetName", "Imported FEX Preset");
                String presetVars = containerJson.optString("fexcorePresetVars", "");
                if (!presetVars.isEmpty()) {
                    int nextId = FEXCorePresetManager.getNextPresetId(context);
                    FEXCorePresetManager.editPreset(context, null, presetName, new EnvVars(presetVars));
                    fexcorePreset = "custom-" + nextId;
                } else {
                    fexcorePreset = "compatibility";
                }
            }
            containerJson.put("fexcorePreset", fexcorePreset);

            // Replicate the exporter's exact environment variable state (including deletions)
            // Fall back to local defaults only if the exporter used default configuration (empty envVars in JSON)
            String importedEnvVars = containerJson.optString("envVars", "");
            EnvVars containerEnvVars = new EnvVars(!importedEnvVars.isEmpty() ? importedEnvVars : com.winlator.cmod.container.Container.DEFAULT_ENV_VARS);
            containerJson.put("envVars", containerEnvVars.toString());

            if (shortcutExtra != null) {
                Iterator<String> keys = shortcutExtra.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    // Merge shortcut overrides into the container creation data
                    if (!key.equals("wineVersion") && !key.equals("id") && !key.equals("drives") && !key.equals("name")) {
                        if (key.equals("envVars")) {
                            String shortcutEnvVarsStr = shortcutExtra.optString("envVars", "");
                            if (!shortcutEnvVarsStr.isEmpty()) {
                                containerEnvVars.putAll(new EnvVars(shortcutEnvVarsStr));
                                containerJson.put("envVars", containerEnvVars.toString());
                            }
                        } else {
                            containerJson.put(key, shortcutExtra.get(key));
                        }
                    }
                }
            }

            containerJson.put("name", "[Community] " + meta.getString("game_name"));
            
            // Safety: Ensure we don't accidentally import drive paths from other users
            containerJson.remove("drives");
            containerJson.remove("id");

            containerManager.createContainerAsync(containerJson, contentsManager, container -> {
                if (container != null) {
                    if (exeFile != null) {
                        try {
                            File desktopDir = container.getDesktopDir();
                            if (!desktopDir.exists()) desktopDir.mkdirs();

                            String shortcutName = shortcutJson.getString("name");
                            File desktopFile = new File(desktopDir, shortcutName + ".desktop");

                            StringBuilder content = new StringBuilder("[Desktop Entry]\n");
                            content.append("Type=Application\nName=").append(shortcutName).append("\n");
                            content.append("Exec=wine \"").append(exeFile.getPath()).append("\"\nIcon=icon\n");

                            if (shortcutExtra != null && shortcutExtra.length() > 0) {
                                content.append("\n[Extra Data]\n");
                                Iterator<String> keys = shortcutExtra.keys();
                                while (keys.hasNext()) {
                                    String key = keys.next();
                                    if (!key.equals("drives") && !key.equals("name")) {
                                        content.append(key).append("=").append(shortcutExtra.getString(key)).append("\n");
                                    }
                                }
                            }
                            FileUtils.writeString(desktopFile, content.toString());
                        } catch (Exception e) {}
                    }
                    callback.call(true);
                } else {
                    callback.call(false);
                }
            });
        } catch (Exception e) { callback.call(false); }
    }
}
