package com.winlator.cmod.contentdialog;

import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.TextView;

import com.winlator.cmod.R;
import com.winlator.cmod.core.AppUtils;

import java.util.HashMap;
import java.util.Map;

public class BCNConfigDialog extends ContentDialog {
    private final Spinner sBCnEmulation;
    private final Spinner sBCnEmulationType;
    private final Spinner sBCnEmulationCache;
    private final Spinner sBCnQualityPreset;
    private final CheckBox cbASTCTranscode;
    private final CheckBox cbETC2Transcode;
    private final CheckBox cbSkipSmallTextures;
    private String selectedBCnEmulation;
    private String selectedBCnEmulationType;
    private String isBCnCacheEnabled;
    private String selectedBCnQualityPreset;
    private String isASTCTranscode;
    private String isETC2Transcode;
    private String isSkipSmallTextures;

    public BCNConfigDialog(View anchor) {
        super(anchor.getContext(), R.layout.bcn_config_dialog);
        setIcon(R.drawable.ic_driver_info);
        setTitle(R.string.bcn_layer_configuration);

        sBCnEmulation = findViewById(R.id.SGraphicsDriverBCnEmulation);
        sBCnEmulationType = findViewById(R.id.SGraphicsDriverBCnEmulationType);
        sBCnEmulationCache = findViewById(R.id.SGraphicsDriverBCnEmulationCache);
        sBCnQualityPreset = findViewById(R.id.SGraphicsDriverBCnQualityPreset);
        cbASTCTranscode = findViewById(R.id.CBASTCTranscode);
        cbETC2Transcode = findViewById(R.id.CBETC2Transcode);
        cbSkipSmallTextures = findViewById(R.id.CBSkipSmallTextures);

        String graphicsDriverConfig = anchor.getTag().toString();
        HashMap<String, String> config = GraphicsDriverConfigDialog.parseGraphicsDriverConfig(graphicsDriverConfig);

        selectedBCnEmulation = config.getOrDefault("bcnEmulation", "auto");
        selectedBCnEmulationType = config.getOrDefault("bcnEmulationType", "compute");
        isBCnCacheEnabled = config.getOrDefault("bcnEmulationCache", "1");
        selectedBCnQualityPreset = config.getOrDefault("bcnQualityPreset", "auto");
        isASTCTranscode = config.getOrDefault("astcTranscode", "1");
        isETC2Transcode = config.getOrDefault("etc2Transcode", "0");
        isSkipSmallTextures = config.getOrDefault("skipSmallTextures", "0");

        AppUtils.setSpinnerSelectionFromValue(sBCnEmulation, selectedBCnEmulation);
        AppUtils.setSpinnerSelectionFromValue(sBCnEmulationType, selectedBCnEmulationType);
        AppUtils.setSpinnerSelectionFromValue(sBCnEmulationCache, isBCnCacheEnabled);
        AppUtils.setSpinnerSelectionFromValue(sBCnQualityPreset, selectedBCnQualityPreset);
        
        cbASTCTranscode.setChecked("1".equals(isASTCTranscode));
        cbETC2Transcode.setChecked("1".equals(isETC2Transcode));
        cbSkipSmallTextures.setChecked("1".equals(isSkipSmallTextures));

        sBCnEmulation.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedBCnEmulation = sBCnEmulation.getSelectedItem().toString();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        sBCnEmulationType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedBCnEmulationType = sBCnEmulationType.getSelectedItem().toString();
                updateTranscodeCheckboxes();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        sBCnEmulationCache.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                isBCnCacheEnabled = sBCnEmulationCache.getSelectedItem().toString();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        sBCnQualityPreset.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedBCnQualityPreset = sBCnQualityPreset.getSelectedItem().toString();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        View.OnTouchListener disabledSpinListener = (v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                boolean isSoftware = "software".equals(selectedBCnEmulationType);
                if (isSoftware) {
                    AppUtils.showToast(getContext(), "Quality settings require BCN Emulation Type to be set to Compute");
                    return true;
                }
                boolean isTranscodeOn = cbASTCTranscode.isChecked() || cbETC2Transcode.isChecked();
                if (!isTranscodeOn) {
                    AppUtils.showToast(getContext(), "Quality settings require ASTC or ETC2 Transcode to be enabled");
                    return true;
                }
            }
            return false;
        };

        sBCnQualityPreset.setOnTouchListener(disabledSpinListener);

        cbASTCTranscode.setOnClickListener(v -> {
            if ("software".equals(selectedBCnEmulationType)) {
                cbASTCTranscode.setChecked(false);
                AppUtils.showToast(getContext(), "Transcode options require BCN Emulation Type to be set to Compute");
                return;
            }
            if (cbETC2Transcode.isChecked()) {
                cbASTCTranscode.setChecked(false);
                AppUtils.showToast(getContext(), "ASTC and ETC2 are mutually exclusive. Uncheck ETC2 first.");
                return;
            }
            isASTCTranscode = cbASTCTranscode.isChecked() ? "1" : "0";
            updateTranscodeCheckboxes();
        });

        cbETC2Transcode.setOnClickListener(v -> {
            if ("software".equals(selectedBCnEmulationType)) {
                cbETC2Transcode.setChecked(false);
                AppUtils.showToast(getContext(), "Transcode options require BCN Emulation Type to be set to Compute");
                return;
            }
            if (cbASTCTranscode.isChecked()) {
                cbETC2Transcode.setChecked(false);
                AppUtils.showToast(getContext(), "ASTC and ETC2 are mutually exclusive. Uncheck ASTC first.");
                return;
            }
            isETC2Transcode = cbETC2Transcode.isChecked() ? "1" : "0";
            updateTranscodeCheckboxes();
        });

        cbSkipSmallTextures.setOnClickListener(v -> {
            boolean isTranscodeOn = cbASTCTranscode.isChecked() || cbETC2Transcode.isChecked();
            if (!isTranscodeOn) {
                cbSkipSmallTextures.setChecked(false);
                AppUtils.showToast(getContext(), "Skip Compression requires ASTC or ETC2 Transcode to be enabled");
                return;
            }
            isSkipSmallTextures = cbSkipSmallTextures.isChecked() ? "1" : "0";
        });

        findViewById(R.id.IVTranscodeInfo).setOnClickListener(v -> showTranscodeInfo());
        findViewById(R.id.IVQualityPresetInfo).setOnClickListener(v -> showQualityInfo());

        updateTranscodeCheckboxes();

        setOnConfirmCallback(() -> {
            config.put("bcnEmulation", selectedBCnEmulation);
            config.put("bcnEmulationType", selectedBCnEmulationType);
            config.put("bcnEmulationCache", isBCnCacheEnabled);
            config.put("bcnQualityPreset", selectedBCnQualityPreset);
            config.put("astcTranscode", isASTCTranscode);
            config.put("etc2Transcode", isETC2Transcode);
            config.put("skipSmallTextures", isSkipSmallTextures);
            anchor.setTag(GraphicsDriverConfigDialog.toGraphicsDriverConfig(config));
        });
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
            cbSkipSmallTextures.setChecked(false);
            isSkipSmallTextures = "0";
            cbSkipSmallTextures.setAlpha(0.5f);
        } else {
            if (cbASTCTranscode.isChecked() && cbETC2Transcode.isChecked()) {
                cbETC2Transcode.setChecked(false);
                isETC2Transcode = "0";
            }
            cbASTCTranscode.setAlpha(1.0f);
            cbETC2Transcode.setAlpha(1.0f);
            if (cbASTCTranscode.isChecked()) cbETC2Transcode.setAlpha(0.5f);
            else if (cbETC2Transcode.isChecked()) cbASTCTranscode.setAlpha(0.5f);
        }

        boolean isTranscodeActive = !isSoftware && (cbASTCTranscode.isChecked() || cbETC2Transcode.isChecked());
        if (!isTranscodeActive) {
            selectedBCnQualityPreset = "auto";
            AppUtils.setSpinnerSelectionFromValue(sBCnQualityPreset, "auto");
            cbSkipSmallTextures.setChecked(false);
            isSkipSmallTextures = "0";
            cbSkipSmallTextures.setAlpha(0.5f);
        } else {
            cbSkipSmallTextures.setAlpha(1.0f);
        }
        sBCnQualityPreset.setAlpha(isTranscodeActive ? 1.0f : 0.5f);
    }

    private void showQualityInfo() {
        ContentDialog dialog = new ContentDialog(getContext(), R.layout.bcn_info_dialog);
        dialog.setTitle(R.string.graphics_driver_bcn_quality_preset);
        dialog.setIcon(R.drawable.ic_driver_info);

        TextView tvMessage = dialog.findViewById(R.id.TVInfoMessage);
        String message = "<b>BCn Shader Quality</b><br/><br/>" +
                "<b>In-Game Visual &amp; Performance Effect:</b><br/><br/>" +
                "&#8226; <b>auto:</b> Auto-selects <b>fast</b> on budget mobile GPUs, <b>high</b> on high-end GPUs.<br/><br/>" +
                "&#8226; <b>fast:</b> <b>Highest Framerate &amp; Zero Camera Pan Stutters.</b> Executes 4x faster on the GPU to eliminate frame spikes during heavy action or looking around.<br/><br/>" +
                "&#8226; <b>balanced:</b> Medium GPU processing speed with balanced color accuracy.<br/><br/>" +
                "&#8226; <b>high:</b> Maximum color precision for smooth dark shadow transitions.";
        tvMessage.setText(android.text.Html.fromHtml(message, android.text.Html.FROM_HTML_MODE_LEGACY));
        dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
        dialog.show();
    }

    private void showTranscodeInfo() {
        ContentDialog dialog = new ContentDialog(getContext(), R.layout.bcn_info_dialog);
        dialog.setTitle(R.string.bcn_layer_configuration);
        dialog.setIcon(R.drawable.ic_driver_info);

        TextView tvMessage = dialog.findViewById(R.id.TVInfoMessage);
        String message = "<b>BCN Emulation &amp; Transcoding</b><br/><br/>" +
                "These settings utilize a custom <b>leegao BCN layer</b> to handle BCN (BC1-BC7) textures on mobile GPUs.<br/><br/>" +
                "<b>Emulation:</b><br/>" +
                "Controls how the layer handles compressed textures. <b>Compute</b> mode uses GPU shaders for better performance, while <b>Software</b> mode is a fallback.<br/><br/>" +
                "<b>Transcoding:</b><br/>" +
                "Directly transcodes BCN textures into <b>ASTC</b> or <b>ETC2</b> formats natively supported by mobile GPUs. This significantly reduces VRAM usage (up to 4x) compared to raw decompression.<br/><br/>" +
                "<b>Trade-offs:</b><br/>" +
                "Transcoding can slightly reduce visual quality. <b>ETC2</b> generally offers lower quality, while <b>ASTC</b> maintains high quality closer to the original.<br/><br/>" +
                "<b>Benefits:</b><br/>" +
                "&#8226; <b>VRAM Savings:</b> Keeps textures compressed in GPU memory.<br/>" +
                "&#8226; <b>Stability:</b> Prevents out-of-memory crashes in texture-heavy games.<br/><br/>" +
                "<b>Skip Compression on Small Textures:</b><br/>" +
                "Avoids compressing low-resolution textures (like icons or UI elements) to prevent them from looking pixelated or blurry. This will slightly increase VRAM usage.<br/><br/>" +
                "<i>Note: Transcoding and Skip Compression require Compute Emulation mode.</i>";
        tvMessage.setText(android.text.Html.fromHtml(message, android.text.Html.FROM_HTML_MODE_LEGACY));
        dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
        dialog.show();
    }
}
