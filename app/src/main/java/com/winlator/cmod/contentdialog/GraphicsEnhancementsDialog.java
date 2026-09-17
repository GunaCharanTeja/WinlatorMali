package com.winlator.cmod.contentdialog;

import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.preference.PreferenceManager;
import com.winlator.cmod.R;
import com.winlator.cmod.XServerDisplayActivity;
import com.winlator.cmod.renderer.GLRenderer;
import com.winlator.cmod.renderer.ApexNativeBridge;
import com.winlator.cmod.renderer.effects.HDREffect;
import com.winlator.cmod.renderer.effects.FSREffect;
import com.winlator.cmod.widget.SeekBar;
import com.winlator.cmod.widget.XServerView;
import com.winlator.cmod.xserver.XServer;

public class GraphicsEnhancementsDialog extends ContentDialog {
    private final XServerDisplayActivity activity;
    private final Spinner sFPSLimit;
    private final EditText etCustomFPSLimit;
    private final CheckBox cbEnableLSFG;
    private final Spinner sLSFGQuality;
    private final Spinner sLSFGTargetFPS;
    private final EditText etCustomTargetFPS;
    private final LinearLayout llLSFGSettings;
    private final SeekBar sbLSFGLiquidFeel;
    private final Spinner sLSFGRenderScale;
    private final CheckBox cbEnableHDR;
    private final CheckBox cbEnableSharpen;
    private final Spinner sSharpenMode;
    private final LinearLayout llSharpenSettings;
    private final SeekBar sbSharpenLevel;
    private boolean lsfgPreviouslyEnabled = false;

    public GraphicsEnhancementsDialog(XServerDisplayActivity activity) {
        super(activity, R.layout.graphics_enhancements_dialog);
        this.activity = activity;
        setIcon(R.drawable.ic_graphics_enhancements);
        setTitle(R.string.graphics_enhancements);

        sFPSLimit = findViewById(R.id.SFPSLimit);
        etCustomFPSLimit = findViewById(R.id.ETCustomFPSLimit);
        sLSFGTargetFPS = findViewById(R.id.SLSFGTargetFPS);
        etCustomTargetFPS = findViewById(R.id.ETCustomTargetFPS);

        boolean isDarkMode = PreferenceManager.getDefaultSharedPreferences(activity).getBoolean("dark_mode", true);
        applyThemeToEditText(etCustomFPSLimit, isDarkMode);
        applyThemeToEditText(etCustomTargetFPS, isDarkMode);

        XServer xServer = activity.getXServer();
        XServerView xServerView = activity.getXServerView();
        GLRenderer renderer = xServerView != null ? xServerView.getRenderer() : null;

        int currentFpsLimit = xServer != null ? xServer.getFpsLimit() : (renderer != null ? renderer.getFpsLimit() : 0);
        int fpsSelection = 0;
        if (currentFpsLimit == 0) fpsSelection = 0;
        else if (currentFpsLimit == 30) fpsSelection = 1;
        else if (currentFpsLimit == 45) fpsSelection = 2;
        else if (currentFpsLimit == 60) fpsSelection = 3;
        else if (currentFpsLimit == 90) fpsSelection = 4;
        else if (currentFpsLimit == 120) fpsSelection = 5;
        else {
            fpsSelection = 6;
            etCustomFPSLimit.setText(String.valueOf(currentFpsLimit));
            etCustomFPSLimit.setVisibility(View.VISIBLE);
        }
        sFPSLimit.setSelection(fpsSelection);

        findViewById(R.id.IVFPSLimitInfo).setOnClickListener(v -> showFPSLimitInfo());

        cbEnableLSFG = findViewById(R.id.CBEnableLSFG);
        sLSFGQuality = findViewById(R.id.SLSFGQuality);
        sLSFGRenderScale = findViewById(R.id.SLSFGRenderScale);
        llLSFGSettings = findViewById(R.id.LLLSFGSettings);
        sbLSFGLiquidFeel = findViewById(R.id.SBLSFGLiquidFeel);

        boolean lsfgEnabled = com.winlator.cmod.renderer.ApexNativeBridge.nativeIsActive();
        lsfgPreviouslyEnabled = lsfgEnabled;

        cbEnableLSFG.setChecked(lsfgEnabled);
        llLSFGSettings.setVisibility(lsfgEnabled ? View.VISIBLE : View.GONE);

        sLSFGQuality.setSelection(com.winlator.cmod.renderer.ApexNativeBridge.nativeGetQuality());
        sbLSFGLiquidFeel.setValue(com.winlator.cmod.renderer.ApexNativeBridge.nativeGetLiquidFeel());

        float currentScale = com.winlator.cmod.renderer.ApexNativeBridge.nativeGetRenderScale();
        int scaleIndex = 0;
        if (Math.abs(currentScale - 1.00f) < 0.04f) scaleIndex = 0;
        else if (Math.abs(currentScale - 0.85f) < 0.04f) scaleIndex = 1;
        else if (Math.abs(currentScale - 0.75f) < 0.04f) scaleIndex = 2;
        else if (Math.abs(currentScale - 0.67f) < 0.04f) scaleIndex = 3;
        else if (Math.abs(currentScale - 0.50f) < 0.04f) scaleIndex = 4;
        else if (Math.abs(currentScale - 0.35f) < 0.04f) scaleIndex = 5;
        else if (Math.abs(currentScale - 0.25f) < 0.04f) scaleIndex = 6;
        sLSFGRenderScale.setSelection(scaleIndex);
        
        int targetFPS = com.winlator.cmod.renderer.ApexNativeBridge.nativeGetTargetFPS();
        int targetFPSSelection = 0;
        if (targetFPS == 0) targetFPSSelection = 0;
        else if (targetFPS == 30) targetFPSSelection = 1;
        else if (targetFPS == 40) targetFPSSelection = 2;
        else if (targetFPS == 50) targetFPSSelection = 3;
        else if (targetFPS == 60) targetFPSSelection = 4;
        else if (targetFPS == 90) targetFPSSelection = 5;
        else if (targetFPS == 120) targetFPSSelection = 6;
        else {
            targetFPSSelection = 7;
            etCustomTargetFPS.setText(String.valueOf(targetFPS));
            etCustomTargetFPS.setVisibility(View.VISIBLE);
        }
        sLSFGTargetFPS.setSelection(targetFPSSelection);

        cbEnableLSFG.setOnCheckedChangeListener((buttonView, isChecked) -> {
            llLSFGSettings.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            applyEffects();
        });

        findViewById(R.id.IVLSFGInfo).setOnClickListener(v -> showLSFGInfo());

        cbEnableHDR = findViewById(R.id.CBEnableHDR);
        cbEnableSharpen = findViewById(R.id.CBEnableSharpen);
        sSharpenMode = findViewById(R.id.SSharpenMode);
        llSharpenSettings = findViewById(R.id.LLSharpenSettings);
        sbSharpenLevel = findViewById(R.id.SBSharpenLevel);

        HDREffect hdrEffect = renderer != null ? renderer.getEffectComposer().getEffect(HDREffect.class) : null;
        cbEnableHDR.setChecked(hdrEffect != null);

        FSREffect fsrEffect = renderer != null ? renderer.getEffectComposer().getEffect(FSREffect.class) : null;
        boolean sharpenEnabled = fsrEffect != null;
        cbEnableSharpen.setChecked(sharpenEnabled);
        llSharpenSettings.setVisibility(sharpenEnabled ? View.VISIBLE : View.GONE);

        if (fsrEffect != null) {
            sSharpenMode.setSelection(fsrEffect.getMode());
            sbSharpenLevel.setValue(fsrEffect.getLevel());
        } else {
            sSharpenMode.setSelection(0);
            sbSharpenLevel.setValue(3.0f);
        }

        cbEnableHDR.setOnCheckedChangeListener((buttonView, isChecked) -> applyEffects());

        cbEnableSharpen.setOnCheckedChangeListener((buttonView, isChecked) -> {
            llSharpenSettings.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            applyEffects();
        });

        sSharpenMode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                applyEffects();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        sbSharpenLevel.setOnValueChangeListener((seekBar, value) -> applyEffects());

        sLSFGQuality.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                applyEffects();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        sLSFGRenderScale.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                applyEffects();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        sLSFGTargetFPS.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                etCustomTargetFPS.setVisibility(position == 7 ? View.VISIBLE : View.GONE);
                applyEffects();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        sbLSFGLiquidFeel.setOnValueChangeListener((seekBar, value) -> applyEffects());

        sFPSLimit.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                etCustomFPSLimit.setVisibility(position == 6 ? View.VISIBLE : View.GONE);
                applyEffects();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        etCustomFPSLimit.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyEffects();
            }
            @Override
            public void afterTextChanged(android.text.Editable s) {}
        });

        etCustomTargetFPS.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyEffects();
            }
            @Override
            public void afterTextChanged(android.text.Editable s) {}
        });

        setOnConfirmCallback(this::applyEffects);
    }

    private int parseIntSafe(String text, int defaultValue) {
        try {
            if (text != null && !text.trim().isEmpty()) {
                return Math.max(0, Integer.parseInt(text.trim()));
            }
        } catch (NumberFormatException ignored) {}
        return defaultValue;
    }

    private void showLSFGInfo() {
        ContentDialog dialog = new ContentDialog(getContext(), R.layout.lsfg_info_dialog);
        dialog.setTitle("Apex Elite: 10-Pass Fluid Engine");
        dialog.setIcon(R.drawable.ic_driver_info);

        TextView tvMessage = dialog.findViewById(R.id.TVInfoMessage);
        String message = "<b>What is Apex Elite?</b><br/>" +
                "A next-generation frame generator optimized specifically for Mali-Gxxx GPUs. It uses a unified 10-pass pipeline to deliver buttery-smooth motion without affecting game performance.<br/><br/>" +
                "<b>The Pillars of Smoothness:</b><br/>" +
                "- <b>Fluid Motion:</b> Uses an elastic SOR solver to ensure the entire screen moves as a cohesive unit, eliminating the 'robotic' feel of standard generators.<br/>" +
                "- <b>Atomic Pacing:</b> Immediately processes frames upon capture, protecting your game's raw FPS while generating extra smoothness up to 120 FPS.<br/>" +
                "- <b>Elite Interpolation:</b> Employs motion-gradient weighting to eliminate halos and ghosting around characters and vehicles.<br/><br/>" +
                "<b>Tips for Best Results:</b><br/>" +
                "- <b>Liquid Feel Slider:</b> Increase this for a more cinematic, flexible flow. Decrease it for a sharper, more rigid movement lock.<br/>" +
                "- <b>Render Scale:</b> Use this to downsample the frame-gen input on high-resolution screens. Our new Atomic Reset ensures artifact-free scaling.";
        tvMessage.setText(android.text.Html.fromHtml(message, android.text.Html.FROM_HTML_MODE_LEGACY));
        
        dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
        dialog.show();
    }

    private void showFPSLimitInfo() {
        ContentDialog dialog = new ContentDialog(getContext(), R.layout.lsfg_info_dialog);
        dialog.setTitle("Universal FPS Limiter");
        dialog.setIcon(R.drawable.ic_driver_info);

        TextView tvMessage = dialog.findViewById(R.id.TVInfoMessage);
        String message = "<b>Universal FPS Limiter</b><br/>" +
                "A high-precision system-level limiter that throttles the game engine before it reaches the display.<br/><br/>" +
                "<b>How it works:</b><br/>" +
                "- It uses microsecond-precise thread sleeping and busy-waiting to ensure frames are presented at exact intervals.<br/>" +
                "- Unlike driver-level limits (like DXVK), this works across all wrappers (DXVK, WineD3D, etc.) and reduces both CPU heat and input jitter.<br/><br/>" +
                "<b>Interaction with Apex (LSFG):</b><br/>" +
                "- <b>Power Saving:</b> Limit the game to 30 FPS to significantly reduce CPU/GPU load, then use Apex to generate frames for a smooth 60 FPS output.<br/>" +
                "- <b>Consistency:</b> Provides a stable base framerate for Apex's interpolation. A steady 30 FPS base produces much better results than an uncapped framerate that fluctuates between 30 and 40.";
        tvMessage.setText(android.text.Html.fromHtml(message, android.text.Html.FROM_HTML_MODE_LEGACY));

        dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
        dialog.show();
    }

    private void applyEffects() {
        XServer xServer = activity.getXServer();
        XServerView xServerView = activity.getXServerView();
        GLRenderer renderer = xServerView != null ? xServerView.getRenderer() : null;

        int fpsLimit = 0;
        int fpsSelection = sFPSLimit.getSelectedItemPosition();
        if (fpsSelection == 1) fpsLimit = 30;
        else if (fpsSelection == 2) fpsLimit = 45;
        else if (fpsSelection == 3) fpsLimit = 60;
        else if (fpsSelection == 4) fpsLimit = 90;
        else if (fpsSelection == 5) fpsLimit = 120;
        else if (fpsSelection == 6) fpsLimit = parseIntSafe(etCustomFPSLimit.getText().toString(), 0);

        if (xServer != null) xServer.setFpsLimit(fpsLimit);
        if (renderer != null) renderer.setFpsLimit(fpsLimit);

        boolean lsfgEnabled = cbEnableLSFG.isChecked();
        
        // Pulse Reset: if Apex was just turned ON, trigger a background pause/resume
        // to build a clean motion history from zero state.
        if (lsfgEnabled && !lsfgPreviouslyEnabled) {
            activity.pulseFgReset();
        }
        lsfgPreviouslyEnabled = lsfgEnabled;

        com.winlator.cmod.renderer.ApexNativeBridge.nativeSetActive(lsfgEnabled);
        if (xServerView != null) {
            xServerView.setApexMode(lsfgEnabled);
        }

        if (lsfgEnabled) {
            com.winlator.cmod.renderer.ApexNativeBridge.nativeSetQuality(sLSFGQuality.getSelectedItemPosition());

            float renderScale = 1.0f;
            int scalePosition = sLSFGRenderScale.getSelectedItemPosition();
            switch (scalePosition) {
                case 0: renderScale = 1.00f; break;
                case 1: renderScale = 0.85f; break;
                case 2: renderScale = 0.75f; break;
                case 3: renderScale = 0.67f; break;
                case 4: renderScale = 0.50f; break;
                case 5: renderScale = 0.35f; break;
                case 6: renderScale = 0.25f; break;
                default: renderScale = 1.00f; break;
            }
            com.winlator.cmod.renderer.ApexNativeBridge.nativeSetRenderScale(renderScale);

            com.winlator.cmod.renderer.ApexNativeBridge.nativeSetShutterGain(0.0f);
            com.winlator.cmod.renderer.ApexNativeBridge.nativeSetFlowScale(1.0f);
            com.winlator.cmod.renderer.ApexNativeBridge.nativeSetLiquidFeel(sbLSFGLiquidFeel.getValue());
            com.winlator.cmod.renderer.ApexNativeBridge.nativeSetEdgeGuard(0.5f);
            com.winlator.cmod.renderer.ApexNativeBridge.nativeSetLoggingEnabled(false);

            int targetFPS = 0;
            int targetFPSSelection = sLSFGTargetFPS.getSelectedItemPosition();
            if (targetFPSSelection == 1) targetFPS = 30;
            else if (targetFPSSelection == 2) targetFPS = 40;
            else if (targetFPSSelection == 3) targetFPS = 50;
            else if (targetFPSSelection == 4) targetFPS = 60;
            else if (targetFPSSelection == 5) targetFPS = 90;
            else if (targetFPSSelection == 6) targetFPS = 120;
            else if (targetFPSSelection == 7) targetFPS = parseIntSafe(etCustomTargetFPS.getText().toString(), 0);
            com.winlator.cmod.renderer.ApexNativeBridge.nativeSetTargetFPS(targetFPS);

            int effectiveDisplayFps = targetFPS > 0 ? targetFPS : (fpsLimit > 0 ? fpsLimit * 2 : 60);
            com.winlator.cmod.core.RefreshRateUtils.applyPreferredRefreshRate(activity, 0, effectiveDisplayFps);
        } else {
            com.winlator.cmod.core.RefreshRateUtils.applyPreferredRefreshRate(activity, 0, fpsLimit);
        }

        if (renderer != null) {
            renderer.getEffectComposer().toggleHDREffect(cbEnableHDR.isChecked());
            renderer.getEffectComposer().updateFSREffect(cbEnableSharpen.isChecked(), sSharpenMode.getSelectedItemPosition(), sbSharpenLevel.getValue());
        }

        if (xServerView != null) {
            xServerView.requestRender();
        }
    }
}
