package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ToggleButton;

import com.winlator.cmod.R;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.GPUInformation;
import com.winlator.cmod.core.KeyValueSet;
import com.winlator.cmod.core.StringUtils;
import com.winlator.cmod.core.VKD3DVersionItem;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DXVKConfigDialog extends ContentDialog {
    public static final String DEFAULT_CONFIG = Container.DEFAULT_DXWRAPPERCONFIG;
    public static final int DXVK_TYPE_NONE = 0;
    public static final int DXVK_TYPE_ASYNC = 1;
    public static final int DXVK_TYPE_GPLASYNC = 2;
    private final ToggleButton swAsync;
    private final ToggleButton swDxvkConfig;
    private boolean isARM64EC = false;
    private final ToggleButton swAsyncCache;
    private final View llAsync;
    private final View llAsyncCache;
    private final Context context;
    private List<String> dxvkVersions;
    private static final Pattern SEMVER = Pattern.compile("(?i)(?:v|dxvk[-_])?(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    private static Integer tryGetMajor(String s) {
        if (s == null) return null;
        Matcher m = SEMVER.matcher(s);
        if (!m.find()) return null;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }
    
    public static final String[] VKD3D_FEATURE_LEVEL = {"12_0", "12_1", "12_2", "11_1", "11_0", "10_1", "10_0", "9_3", "9_2", "9_1"};

    private static int compareVersion(String varA, String varB) {
        final String[] levelsA = varA.split("\\.");
        final String[] levelsB = varB.split("\\.");
        int minLen = Math.min(levelsA.length, levelsB.length);
        int numA, numB;

        for (int i = 0; i < minLen; i++) {
            numA = Integer.parseInt(levelsA[i]);
            numB = Integer.parseInt(levelsB[i]);
            if (numA != numB)
                return numA - numB;
        }

        if (levelsA.length != levelsB.length)
            return levelsA.length - levelsB.length;

        return 0;
    }

    public DXVKConfigDialog(View anchor, boolean isARM64EC) {
        super(anchor.getContext(), R.layout.dxvk_config_dialog);
        context = anchor.getContext();
        setIcon(R.drawable.icon_settings);
        setTitle("DXVK "+context.getString(R.string.configuration));

        final Spinner sDXVKVersion = findViewById(R.id.SDXVKVersion);
        final Spinner sVKD3DVersion = findViewById(R.id.SVKD3DVersion);
        final Spinner sVKD3DFeatureLevel = findViewById(R.id.SVKD3DFeatureLevel);
        final Spinner sDDRAWrapper = findViewById(R.id.SDDRAWrapper);
        final Spinner sMaxDeviceMemory = findViewById(R.id.SMaxDeviceMemory);
        swAsync = findViewById(R.id.SWAsync);
        swDxvkConfig = findViewById(R.id.SWDxvkConfig);
        swAsyncCache = findViewById(R.id.SWAsyncCache);
        llAsync = findViewById(R.id.LLAsync);
        llAsyncCache = findViewById(R.id.LLAsyncCache);

        ContentsManager contentsManager = new ContentsManager(context);
        contentsManager.syncContents();

        KeyValueSet config = parseConfig(anchor.getTag());
        loadDxvkVersionSpinner(contentsManager, sDXVKVersion, isARM64EC);
        loadVkd3dVersionSpinner(contentsManager, sVKD3DVersion);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, VKD3D_FEATURE_LEVEL);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sVKD3DFeatureLevel.setAdapter(adapter);

        setDXVKSpinner(sDXVKVersion, config, contentsManager, isARM64EC);
        AppUtils.setSpinnerSelectionFromIdentifier(sVKD3DVersion, config.get("vkd3dVersion"));
        AppUtils.setSpinnerSelectionFromIdentifier(sVKD3DFeatureLevel, config.get("vkd3dLevel"));
        AppUtils.setSpinnerSelectionFromIdentifier(sDDRAWrapper, config.get("ddrawrapper"));

        try {
            sMaxDeviceMemory.setSelection(Integer.parseInt(config.get("maxDeviceMemory")));
        } catch (NumberFormatException e) {}

        swAsync.setChecked("1".equals(config.get("async", "0")));
        swDxvkConfig.setChecked("1".equals(config.get("dxvkConfig", "1")));
        swAsyncCache.setChecked("1".equals(config.get("asyncCache", "0")));

        findViewById(R.id.BTHelpDxvkConfig).setOnClickListener(v -> {
            ContentDialog dialog = new ContentDialog(getContext(), R.layout.bcn_info_dialog);
            dialog.setTitle("Pre-Configured DXVK Config");
            dialog.setIcon(R.drawable.ic_driver_info);

            TextView tvMessage = dialog.findViewById(R.id.TVInfoMessage);
            String message = "<b>Pre-Configured DXVK Optimizations:</b><br/><br/>" +
                    "&#8226; <b>memoryTrack:</b> Enables strict tracking of memory allocations. Prevents \"Out of Memory\" crashes by ensuring the heap is managed correctly on Android's shared RAM architecture.<br/><br/>" +
                    "&#8226; <b>nvapiHack:</b> Disables NVIDIA-specific spoofing. Prevents games from attempting to call proprietary NVIDIA features that cause crashes on mobile hardware.<br/><br/>" +
                    "&#8226; <b>numCompilerThreads:</b> Limits shader compilation to 4 threads. Prevents CPU cores from maxing out, reducing heat and avoiding thermal throttling for a stable framerate.<br/><br/>" +
                    "<b>Mali Specialized (Non-Adreno):</b><br/><br/>" +
                    "&#8226; <b>relaxedBarriers / ignoreGraphicsBarriers:</b> Reduces GPU \"sync points.\" Mali drivers struggle with frequent barriers; disabling non-essential ones significantly boosts FPS by letting the GPU work continuously.<br/><br/>" +
                    "&#8226; <b>useEarlyDiscard:</b> Discards hidden pixels early in the pipeline. Ideal for Mali's Tile-Based architecture, reducing \"overdraw\" to save GPU power and battery.<br/><br/>" +
                    "&#8226; <b>shrinkBindingSlots:</b> Minimizes the internal resource table size. Reduces the overall VRAM footprint, leaving more memory available for actual game assets.<br/><br/>" +
                    "&#8226; <b>maxQueuedFrames:</b> Limits the CPU to preparing only 1 frame ahead. Prevents input lag (latency) and avoids large memory backlogs that can lead to crashes.<br/><br/>" +
                    "&#8226; <b>allowMapFlagNoWait:</b> Allows the CPU to update resources without waiting for GPU confirmation. Eliminates \"CPU stalls\" and micro-stutters.";
            tvMessage.setText(android.text.Html.fromHtml(message, android.text.Html.FROM_HTML_MODE_LEGACY));
            dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
            dialog.show();
        });

        updateConfigVisibility(getDXVKType(sDXVKVersion.getSelectedItemPosition()));

        sDXVKVersion.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateConfigVisibility(getDXVKType(position));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

        sVKD3DVersion.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Object selectedObj = sVKD3DVersion.getSelectedItem();
                String selectedVersion = selectedObj != null ? selectedObj.toString() : "None";
                String currentDXVKVersion = sDXVKVersion.getSelectedItem() != null ? sDXVKVersion.getSelectedItem().toString() : config.get("version");

                loadDxvkVersionSpinner(contentsManager, sDXVKVersion, isARM64EC);

                if (!selectedVersion.equals("None") && !selectedVersion.equalsIgnoreCase("none")) {
                    List<String> filteredList = new ArrayList<>();
                    for (String ver : dxvkVersions) {
                        Integer major = tryGetMajor(ver);
                        if (major == null || major >= 2) {
                            filteredList.add(ver);
                        }
                    }

                    if (!filteredList.isEmpty()) {
                        dxvkVersions = filteredList;
                        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, dxvkVersions);
                        sDXVKVersion.setAdapter(adapter);

                        Integer curMajor = tryGetMajor(currentDXVKVersion);
                        if (curMajor != null && curMajor >= 2 && dxvkVersions.contains(currentDXVKVersion)) {
                            AppUtils.setSpinnerSelectionFromIdentifier(sDXVKVersion, currentDXVKVersion);
                        } else {
                            // Select first available 2.x version or DefaultVersion.DXVK if present
                            if (dxvkVersions.contains(DefaultVersion.DXVK)) {
                                AppUtils.setSpinnerSelectionFromIdentifier(sDXVKVersion, DefaultVersion.DXVK);
                            } else {
                                sDXVKVersion.setSelection(0, false);
                            }
                        }
                    }
                } else {
                    AppUtils.setSpinnerSelectionFromIdentifier(sDXVKVersion, currentDXVKVersion);
                }
                updateConfigVisibility(getDXVKType(sDXVKVersion.getSelectedItemPosition()));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        setOnConfirmCallback(() -> {
            if (sDXVKVersion.getSelectedItem() != null) config.put("version", sDXVKVersion.getSelectedItem().toString());
            config.put("async", ((swAsync.isChecked())&&(llAsync.getVisibility()==View.VISIBLE))?"1":"0");
            config.put("asyncCache", ((swAsyncCache.isChecked())&&(llAsyncCache.getVisibility()==View.VISIBLE))?"1":"0");
            config.put("dxvkConfig", swDxvkConfig.isChecked() ? "1" : "0");
            Object selectedItem = sVKD3DVersion.getSelectedItem();
            if (selectedItem instanceof VKD3DVersionItem) {
                config.put("vkd3dVersion", ((VKD3DVersionItem) selectedItem).getIdentifier());
            } else if (selectedItem != null) {
                config.put("vkd3dVersion", selectedItem.toString());
            }
            if (sVKD3DFeatureLevel.getSelectedItem() != null) config.put("vkd3dLevel", sVKD3DFeatureLevel.getSelectedItem().toString());
            if (sDDRAWrapper.getSelectedItem() != null) config.put("ddrawrapper", StringUtils.parseIdentifier(sDDRAWrapper.getSelectedItem().toString()));
            config.put("maxDeviceMemory", String.valueOf(sMaxDeviceMemory.getSelectedItemPosition()));
            anchor.setTag(config.toString());
        });
    }

    private void updateConfigVisibility(int dxvkType) {
        if (dxvkType == DXVK_TYPE_ASYNC) {
            llAsync.setVisibility(View.VISIBLE);
            llAsyncCache.setVisibility(View.GONE);
        } else if (dxvkType == DXVK_TYPE_GPLASYNC) {
            llAsync.setVisibility(View.VISIBLE);
            llAsyncCache.setVisibility(View.VISIBLE);
        } else {
            llAsync.setVisibility(View.GONE);
            llAsyncCache.setVisibility(View.GONE);
        }
    }

    private int getDXVKType(int pos) {
        if (dxvkVersions == null || pos < 0 || pos >= dxvkVersions.size()) return DXVK_TYPE_NONE;
        final String v = dxvkVersions.get(pos);
        int dxvkType = DXVK_TYPE_NONE;
        if (v.contains("gplasync"))
            dxvkType = DXVK_TYPE_GPLASYNC;
        else if (v.contains("async"))
            dxvkType = DXVK_TYPE_ASYNC;
        return dxvkType;
    }

    private void setDXVKSpinner(Spinner sDXVKVersion, KeyValueSet config, ContentsManager contentsManager, boolean isARM64EC) {
        String selectedVersion = config.get("vkd3dVersion");
        String currentDXVKVersion = config.get("version");
        if (selectedVersion != null && !selectedVersion.equals("None") && !selectedVersion.equalsIgnoreCase("none")) {
            List<String> filteredList = new ArrayList<>();
            for (String ver : dxvkVersions) {
                Integer major = tryGetMajor(ver);
                if (major == null || major >= 2) {
                    filteredList.add(ver);
                }
            }
            if (!filteredList.isEmpty()) {
                dxvkVersions = filteredList;
                ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, dxvkVersions);
                sDXVKVersion.setAdapter(adapter);

                Integer curMajor = tryGetMajor(currentDXVKVersion);
                if (curMajor != null && curMajor >= 2 && dxvkVersions.contains(currentDXVKVersion)) {
                    AppUtils.setSpinnerSelectionFromIdentifier(sDXVKVersion, currentDXVKVersion);
                } else {
                    if (dxvkVersions.contains(DefaultVersion.DXVK)) {
                        AppUtils.setSpinnerSelectionFromIdentifier(sDXVKVersion, DefaultVersion.DXVK);
                    } else {
                        sDXVKVersion.setSelection(0, false);
                    }
                }
            }
        } else {
            AppUtils.setSpinnerSelectionFromIdentifier(sDXVKVersion, currentDXVKVersion);
        }
    }

    public static KeyValueSet parseConfig(Object config) {
        String data = config != null && !config.toString().isEmpty() ? config.toString() :  DEFAULT_CONFIG;
        return new KeyValueSet(data);
    }

    public static void setEnvVars(Context context, KeyValueSet config, EnvVars envVars) {
        boolean dxvkConfigEnabled = "1".equals(config.get("dxvkConfig", "1"));
        File configFile = new File(context.getFilesDir(), "imagefs/home/xuser/.config/dxvk.conf");

        if (dxvkConfigEnabled) {
            String maxDeviceMemoryIndex = config.get("maxDeviceMemory");
            String maxDeviceMemoryValue = "";
            if (!maxDeviceMemoryIndex.isEmpty()) {
                switch (maxDeviceMemoryIndex) {
                    case "1": maxDeviceMemoryValue = "512"; break;
                    case "2": maxDeviceMemoryValue = "1024"; break;
                    case "3": maxDeviceMemoryValue = "2048"; break;
                    case "4": maxDeviceMemoryValue = "3072"; break;
                    case "5": maxDeviceMemoryValue = "4096"; break;
                }
            }

            // Initialize default global optimizations for DXVK
            StringBuilder content = new StringBuilder();
            content.append("dxvk.memoryTrack = True\n");
            content.append("dxgi.nvapiHack = False\n");
            content.append("dxvk.numCompilerThreads = 4\n");

            // Mali and non-Adreno specialized optimizations
            if (!GPUInformation.isAdrenoGPU(context)) {
                content.append("d3d11.relaxedBarriers = True\n");
                content.append("d3d11.ignoreGraphicsBarriers = True\n");
                content.append("dxvk.useEarlyDiscard = True\n");
                content.append("d3d11.allowMapFlagNoWait = True\n");
                content.append("dxvk.shrinkBindingSlots = True\n");
                content.append("d3d11.maxQueuedFrames = 1\n");
            }

            if (!maxDeviceMemoryValue.isEmpty()) {
                content.append("dxgi.maxDeviceMemory = ").append(maxDeviceMemoryValue).append("\n");
                content.append("dxgi.maxSharedMemory = ").append(maxDeviceMemoryValue).append("\n");
                content.append("d3d9.maxDeviceMemory = ").append(maxDeviceMemoryValue).append("\n");
                content.append("d3d9.maxAvailableMemory = ").append(maxDeviceMemoryValue).append("\n");
            }

            try {
                configFile.getParentFile().mkdirs();
                if (configFile.exists()) configFile.delete();
                try (FileOutputStream fos = new FileOutputStream(configFile);
                     OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                    osw.write(content.toString());
                }
                envVars.put("DXVK_CONFIG_FILE", configFile.getAbsolutePath());
            } catch (Exception e) {}
        } else {
            try {
                if (configFile.exists()) configFile.delete();
            } catch (Exception e) {}
        }

        String async = config.get("async");
        if (!async.isEmpty() && !async.equals("0"))
            envVars.put("DXVK_ASYNC", "1");

        String asyncCache = config.get("asyncCache");
        if (!asyncCache.isEmpty() && !asyncCache.equals("0"))
            envVars.put("DXVK_GPLASYNCCACHE", "1");

        envVars.put("VKD3D_FEATURE_LEVEL", config.get("vkd3dLevel"));
        envVars.put("DXVK_STATE_CACHE_PATH", context.getFilesDir() + "/imagefs/" + ImageFs.CACHE_PATH);
    }

    private void loadDxvkVersionSpinner(ContentsManager manager, Spinner spinner, boolean isARM64EC) {
        this.isARM64EC = isARM64EC;
        String[] originalItems = context.getResources().getStringArray(R.array.dxvk_version_entries);
        List<String> itemList = new ArrayList<>(Arrays.asList(originalItems));

        for (ContentProfile profile : manager.getInstalledProfiles(ContentProfile.ContentType.CONTENT_TYPE_DXVK)) {
            String verName = profile.verName != null ? profile.verName : "";
            if (verName.startsWith("dxvk-")) {
                verName = verName.substring("dxvk-".length());
            }
            if (!itemList.contains(verName)) {
                itemList.add(verName);
            }
        }

        for (int i = 0; i < itemList.size(); i++) {
            if (itemList.get(i).contains("arm64ec") && !isARM64EC) {
                itemList.remove(i);
                i--;
            }
        }

        spinner.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, itemList));
        dxvkVersions = itemList;
    }

    private void loadVkd3dVersionSpinner(ContentsManager manager, Spinner spinner) {
        List<VKD3DVersionItem> itemList = new ArrayList<>();

        // Add predefined versions
        String[] originalItems = context.getResources().getStringArray(R.array.vkd3d_version_entries);
        for (String version : originalItems) {
            itemList.add(new VKD3DVersionItem(version));
        }

        // Add installed content profiles
        for (ContentProfile profile : manager.getInstalledProfiles(ContentProfile.ContentType.CONTENT_TYPE_VKD3D)) {
            String displayName = profile.verName;
            int versionCode = profile.verCode;
            itemList.add(new VKD3DVersionItem(displayName, versionCode));
        }

        ArrayAdapter<VKD3DVersionItem> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, itemList);
        spinner.setAdapter(adapter);
    }
}
