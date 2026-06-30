package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.winlator.cmod.R;
import com.winlator.cmod.contents.AdrenotoolsManager;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.GPUInformation;
import com.winlator.cmod.core.StringUtils;
import com.winlator.cmod.widget.MultiSelectionComboBox;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

public class GraphicsDriverConfigDialog extends ContentDialog {

    private static final String TAG = "GraphicsDriverConfigDialog"; // Tag for logging
    private Spinner sVersion;
    private Spinner sVulkanVersion;
    private MultiSelectionComboBox mscAvailableExtensions;
    private Spinner sGPUName;
    private Spinner sMaxDeviceMemory;
    private Spinner sPresentMode;
    private Spinner sResourceType;
    private Spinner sBCnEmulation;
    private Spinner sBCnEmulationType;
    private Spinner sBCnEmulationCache;
    private CheckBox cbSyncFrame;
    private CheckBox cbDisablePresentWait;
    private CheckBox cbASTCTranscode;
    private CheckBox cbETC2Transcode;

    private static String selectedVulkanVersion;
    private static String selectedVersion;
    private static String blacklistedExtensions = "";
    private static String selectedGPUName;
    private static String selectedDeviceMemory;

    private static String isSyncFrame;
    private static String isDisablePresentWait;
    private static String isASTCTranscode;
    private static String isETC2Transcode;
    private static String selectedPresentMode;
    private static String selectedResourceType;
    private static String selectedBCnEmulation;
    private static String selectedBCnEmulationType;
    private static String isBCnCacheEnabled;

    private void loadGPUNameSpinner(Context context, Spinner spinner)  {
        String gpuNameList = FileUtils.readString(context, "gpu_cards.json");
        ArrayList<String> entries = new ArrayList<>();

        entries.add("Device");

        try {
            JSONArray jarray = new JSONArray(gpuNameList);
            for (int i = 0; i < jarray.length(); i++) {
                JSONObject jobj = jarray.getJSONObject(i);
                String gpuName = jobj.getString("name");
                entries.add(gpuName);
            }
            ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, entries);
            spinner.setAdapter(adapter);
        }
        catch (JSONException e) {
        }
    }

    public static HashMap<String, String> parseGraphicsDriverConfig(String graphicsDriverConfig) {
        HashMap<String, String> mappedConfig = new HashMap<>();
        String[] configElements = graphicsDriverConfig.split(";");
        for (String element : configElements) {
            String key;
            String value;
            String[] splittedElement = element.split("=");
            key = splittedElement[0];
            if (splittedElement.length > 1)
                value = element.split("=")[1];
            else
                value = "";
            mappedConfig.put(key, value);
        }
        return mappedConfig;
    }

    public static String toGraphicsDriverConfig(HashMap<String, String> config) {
        String graphicsDriverConfig = "";
        for (Map.Entry<String, String> entry : config.entrySet()) {
            graphicsDriverConfig += entry.getKey() + "=" + entry.getValue() + ";";
        }
        return graphicsDriverConfig.substring(0, graphicsDriverConfig.length() - 1);
    }

    public static String getVersion(String graphicsDriverConfig) {
        HashMap<String, String> config = parseGraphicsDriverConfig(graphicsDriverConfig);
        return config.get("version");
    }

    public static String getExtensionsBlacklist(String graphicsDriverConfig) {
        HashMap<String, String> config = parseGraphicsDriverConfig(graphicsDriverConfig);
        return config.get("blacklistedExtensions");
    }

    public static String writeGraphicsDriverConfig() {
        String graphicsDriverConfig = "vulkanVersion=" + selectedVulkanVersion + ";" +
                "version=" + selectedVersion + ";" +
                "blacklistedExtensions=" + blacklistedExtensions + ";" +
                "maxDeviceMemory=" + StringUtils.parseNumber(selectedDeviceMemory) + ";" +
                "presentMode=" + selectedPresentMode + ";" +
                "syncFrame=" + isSyncFrame + ";" +
                "disablePresentWait=" + isDisablePresentWait + ";" +
                "astcTranscode=" + isASTCTranscode + ";" +
                "etc2Transcode=" + isETC2Transcode + ";" +
                "resourceType=" + selectedResourceType + ";" +
                "bcnEmulation=" + selectedBCnEmulation + ";" +
                "bcnEmulationType=" + selectedBCnEmulationType + ";" +
                "bcnEmulationCache=" + isBCnCacheEnabled + ";" +
                "gpuName=" + selectedGPUName;
        Log.i(TAG, "Written config " + graphicsDriverConfig);
        return graphicsDriverConfig;
    }

    private String[] queryAvailableExtensions(String driver, Context context) {
        return GPUInformation.enumerateExtensions(driver, context);
    }
  
    public GraphicsDriverConfigDialog(View anchor, String graphicsDriver, TextView graphicsDriverVersionView) {
        super(anchor.getContext(), R.layout.graphics_driver_config_dialog);
        initializeDialog(anchor, graphicsDriver, graphicsDriverVersionView);
    }

    private void showTranscodeInfo() {
        ContentDialog dialog = new ContentDialog(getContext(), R.layout.lsfg_info_dialog);
        dialog.setTitle("Texture Transcoding");
        dialog.setIcon(R.drawable.ic_driver_info);

        TextView tvMessage = dialog.findViewById(R.id.TVInfoMessage);
        String message = "<b>ASTC & ETC2 Transcode</b><br/><br/>" +
                "These settings utilize a custom <b>leegao BCN layer</b> to transcode BCN (BC1-BC7) textures directly into ASTC and ETC2 formats natively supported by mobile GPUs.<br/><br/>" +
                "<b>Trade-offs:</b><br/>" +
                "Enabling this can slightly reduce visual quality due to compression. <b>ETC2</b> generally offers lower quality, while <b>ASTC</b> maintains normal to high quality closer to the original.<br/><br/>" +
                "<b>Why it matters:</b><br/>" +
                "Without this, BCN textures are typically decompressed to <b>RGBA8</b>, which consumes <b>4x more Video Memory</b> than the original texture.<br/><br/>" +
                "<b>Benefits:</b><br/>" +
                "- <b>VRAM Savings:</b> Significantly lowers VRAM usage by keeping textures compressed.<br/>" +
                "- <b>RAM Savings:</b> Reduces system RAM pressure during texture-heavy games.<br/>" +
                "- <b>Stability:</b> Prevents out-of-memory crashes on devices with limited memory.";
        tvMessage.setText(android.text.Html.fromHtml(message, android.text.Html.FROM_HTML_MODE_LEGACY));

        dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
        dialog.show();
    }

    private void initializeDialog(View anchor, String graphicsDriver, TextView graphicsDriverVersionView) {
        setIcon(R.drawable.icon_settings);
        setTitle(anchor.getContext().getString(R.string.graphics_driver_configuration));

        String graphicsDriverConfig = anchor.getTag().toString();

        sVersion = findViewById(R.id.SGraphicsDriverVersion);
        sVulkanVersion = findViewById(R.id.SGraphicsDriverVulkanVersion);
        mscAvailableExtensions = findViewById(R.id.MSCAvailableExtensions);
        sPresentMode = findViewById(R.id.SGraphicsDriverPresentMode);
        sGPUName = findViewById(R.id.SGraphicsDriverGPUName);
        sMaxDeviceMemory = findViewById(R.id.SGraphicsDriverMaxDeviceMemory);
        sResourceType = findViewById(R.id.SGraphicsDriverResourceType);
        sBCnEmulation = findViewById(R.id.SGraphicsDriverBCnEmulation);
        sBCnEmulationType = findViewById(R.id.SGraphicsDriverBCnEmulationType);
        sBCnEmulationCache = findViewById(R.id.SGraphicsDriverBCnEmulationCache);
        cbSyncFrame = findViewById(R.id.CBSyncFrame);
        cbDisablePresentWait = findViewById(R.id.CBDisablePresentWait);
        cbASTCTranscode = findViewById(R.id.CBASTCTranscode);
        cbETC2Transcode = findViewById(R.id.CBETC2Transcode);

        HashMap<String, String> config = parseGraphicsDriverConfig(graphicsDriverConfig);

        String vulkanVersion = config.get("vulkanVersion");
        String initialVersion = config.get("version");
        String blExtensions = config.get("blacklistedExtensions");
        String gpuName = config.get("gpuName");
        String maxDeviceMemory = config.get("maxDeviceMemory");
        String syncFrame = config.get("syncFrame");
        String disablePresentWait = config.get("disablePresentWait");
        String astcTranscode = config.getOrDefault("astcTranscode", "1");
        String etc2Transcode = config.getOrDefault("etc2Transcode", "0");
        String presentMode = config.get("presentMode");
        String resourceType = config.get("resourceType");
        String bcnEmulation = config.get("bcnEmulation");
        String bcnEmulationType = config.get("bcnEmulationType");
        String bcnEmulationCache = config.get("bcnEmulationCache");

        // Update the selectedVersion whenever the user selects a different version
        sVersion.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedVersion = sVersion.getSelectedItem().toString();
                String[] availableExtensions = queryAvailableExtensions(selectedVersion, anchor.getContext());
                String blacklistedExtensions = "";

                mscAvailableExtensions.setItems(availableExtensions, "Extensions");
                mscAvailableExtensions.setSelectedItems(availableExtensions);

                if(selectedVersion.equals(initialVersion))
                    blacklistedExtensions = blExtensions;

                String[] bl = blacklistedExtensions.split("\\,");

                for (String extension : bl) {
                    mscAvailableExtensions.unsetSelectedItem(extension);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                selectedVersion = sVersion.getSelectedItem().toString();
                Log.d(TAG, "User selected version: " + selectedVersion);
            }
        });

        sVulkanVersion.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedVulkanVersion = sVulkanVersion.getSelectedItem().toString();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

        sGPUName.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedGPUName = sGPUName.getSelectedItem().toString();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

        sMaxDeviceMemory.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedDeviceMemory = sMaxDeviceMemory.getSelectedItem().toString();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

        sPresentMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedPresentMode = sPresentMode.getSelectedItem().toString();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

        sResourceType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                selectedResourceType = sResourceType.getSelectedItem().toString();
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {

            }
        });

        sBCnEmulation.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                selectedBCnEmulation = sBCnEmulation.getSelectedItem().toString();
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {

            }
        });

        sBCnEmulationType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                selectedBCnEmulationType = sBCnEmulationType.getSelectedItem().toString();
                updateTranscodeCheckboxes();
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {

            }
        });

        sBCnEmulationCache.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                isBCnCacheEnabled = sBCnEmulationCache.getSelectedItem().toString();
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {

            }
        });

        isSyncFrame = syncFrame;
        cbSyncFrame.setChecked(isSyncFrame.equals("1") ? true : false);
        cbSyncFrame.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isSyncFrame = isChecked ? "1" : "0";
        });

        isDisablePresentWait = disablePresentWait;
        cbDisablePresentWait.setChecked(isDisablePresentWait.equals("1") ? true : false);
        cbDisablePresentWait.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isDisablePresentWait = isChecked ? "1" : "0";
        });

        isASTCTranscode = astcTranscode;
        cbASTCTranscode.setChecked(isASTCTranscode.equals("1"));
        cbASTCTranscode.setOnClickListener((v) -> {
            boolean isSoftware = "software".equals(selectedBCnEmulationType);
            if (isSoftware) {
                cbASTCTranscode.setChecked(false);
                AppUtils.showToast(v.getContext(), "Transcode options require BCN Emulation Type to be set to Compute");
                return;
            }

            if (cbETC2Transcode.isChecked()) {
                cbASTCTranscode.setChecked(false);
                AppUtils.showToast(v.getContext(), "ASTC and ETC2 are mutually exclusive. Uncheck ETC2 first.");
                return;
            }

            boolean isChecked = cbASTCTranscode.isChecked();
            isASTCTranscode = isChecked ? "1" : "0";
            updateTranscodeCheckboxes();
        });

        isETC2Transcode = etc2Transcode;
        cbETC2Transcode.setChecked(isETC2Transcode.equals("1"));
        cbETC2Transcode.setOnClickListener((v) -> {
            boolean isSoftware = "software".equals(selectedBCnEmulationType);
            if (isSoftware) {
                cbETC2Transcode.setChecked(false);
                AppUtils.showToast(v.getContext(), "Transcode options require BCN Emulation Type to be set to Compute");
                return;
            }

            if (cbASTCTranscode.isChecked()) {
                cbETC2Transcode.setChecked(false);
                AppUtils.showToast(v.getContext(), "ASTC and ETC2 are mutually exclusive. Uncheck ASTC first.");
                return;
            }

            boolean isChecked = cbETC2Transcode.isChecked();
            isETC2Transcode = isChecked ? "1" : "0";
            updateTranscodeCheckboxes();
        });

        findViewById(R.id.IVTranscodeInfo).setOnClickListener(v -> showTranscodeInfo());

        // Ensure ContentsManager syncContents is called
        ContentsManager contentsManager = new ContentsManager(anchor.getContext());
        contentsManager.syncContents();
        
        // Populate the spinner with available versions from ContentsManager and pre-select the initial version
        populateGraphicsDriverVersions(anchor.getContext(), contentsManager, vulkanVersion, initialVersion, blExtensions, gpuName, maxDeviceMemory, presentMode, resourceType, bcnEmulation, bcnEmulationType, bcnEmulationCache, graphicsDriver);

        setOnConfirmCallback(() -> {
            blacklistedExtensions = mscAvailableExtensions.getUnSelectedItemsAsString();

            if (graphicsDriverVersionView != null)
                graphicsDriverVersionView.setText(selectedVersion);

            anchor.setTag(writeGraphicsDriverConfig());
        });
    }

    private void populateGraphicsDriverVersions(Context context, ContentsManager contentsManager, String vulkanVersion, @Nullable String initialVersion, @Nullable String blExtensions, String gpuName, String maxDeviceMemory, String presentMode, String selectedResourceType, String bcnEmulation, String bcnEmulationType, String bcnEmulationCache, String graphicsDriver) {
        List<String> wrapperVersions = new ArrayList<>();
        String[] wrapperDefaultVersions = context.getResources().getStringArray(R.array.wrapper_graphics_driver_version_entries);

        for (String version : wrapperDefaultVersions) {
            if (GPUInformation.isDriverSupported(version, context))
                wrapperVersions.add(version);
        }
        
        // Add installed versions from AdrenotoolsManager
        AdrenotoolsManager adrenotoolsManager = new AdrenotoolsManager(context);
        wrapperVersions.addAll(adrenotoolsManager.enumarateInstalledDrivers());

        // Set the adapter and select the initial version
        ArrayAdapter<String> wrapperAdapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, wrapperVersions);
        
        sVersion.setAdapter(wrapperAdapter);
        
        // We can start logging selected graphics driver and initial version
        Log.d(TAG, "Graphics driver: " + graphicsDriver);
        Log.d(TAG, "Initial version: " + initialVersion);

        loadGPUNameSpinner(context, sGPUName);

        // Use the custom selection logic
        setSpinnerSelectionWithFallback(sVersion, initialVersion, graphicsDriver);
        AppUtils.setSpinnerSelectionFromValue(sVulkanVersion, vulkanVersion);
        AppUtils.setSpinnerSelectionFromValue(sGPUName, gpuName);
        AppUtils.setSpinnerSelectionFromNumber(sMaxDeviceMemory, maxDeviceMemory);
        AppUtils.setSpinnerSelectionFromValue(sPresentMode, presentMode);
        AppUtils.setSpinnerSelectionFromValue(sResourceType, selectedResourceType);
        AppUtils.setSpinnerSelectionFromValue(sBCnEmulation, bcnEmulation);
        AppUtils.setSpinnerSelectionFromValue(sBCnEmulationType, bcnEmulationType);
        AppUtils.setSpinnerSelectionFromValue(sBCnEmulationCache, bcnEmulationCache);
        updateTranscodeCheckboxes();

        // We can log the spinner values now
        Log.d(TAG, "Spinner selected position: " + sVersion.getSelectedItemPosition());
        Log.d(TAG, "Spinner selected value: " + sVersion.getSelectedItem());
    }

    private void setSpinnerSelectionWithFallback(Spinner spinner, String version, String graphicsDriver) {
        // First, attempt to find an exact match (case-insensitive)
        for (int i = 0; i < spinner.getCount(); i++) {
            String item = spinner.getItemAtPosition(i).toString();
            if (item.equalsIgnoreCase(version)) {
                spinner.setSelection(i);
                return;
            }
        }

        AppUtils.setSpinnerSelectionFromValue(spinner, GPUInformation.isDriverSupported(DefaultVersion.WRAPPER_ADRENO, getContext()) ? DefaultVersion.WRAPPER_ADRENO : DefaultVersion.WRAPPER);
    }

    private void updateTranscodeCheckboxes() {
        boolean isSoftware = "software".equals(selectedBCnEmulationType);

        if (isSoftware) {
            cbASTCTranscode.setChecked(false);
            isASTCTranscode = "0";
            cbASTCTranscode.setAlpha(0.5f);

            cbETC2Transcode.setChecked(false);
            isETC2Transcode = "0";
            cbETC2Transcode.setAlpha(0.5f);
        } else {
            // Compute mode: mutually exclusive
            if (cbASTCTranscode.isChecked() && cbETC2Transcode.isChecked()) {
                cbETC2Transcode.setChecked(false);
                isETC2Transcode = "0";
            }

            boolean astcChecked = cbASTCTranscode.isChecked();
            boolean etc2Checked = cbETC2Transcode.isChecked();

            if (astcChecked) {
                cbASTCTranscode.setAlpha(1.0f);
                cbETC2Transcode.setAlpha(0.5f);
            } else if (etc2Checked) {
                cbASTCTranscode.setAlpha(0.5f);
                cbETC2Transcode.setAlpha(1.0f);
            } else {
                cbASTCTranscode.setAlpha(1.0f);
                cbETC2Transcode.setAlpha(1.0f);
            }
        }
    }
}
