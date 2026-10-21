package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;
import com.winlator.cmod.XServerDisplayActivity;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.renderer.PostProcessing;
import com.winlator.cmod.widget.SeekBar;
import com.winlator.cmod.widget.XServerView;
import com.winlator.cmod.xserver.XServer;

import android.widget.ImageView;

/** In-game graphics settings for the EGL renderer: color presets, upscaler, sharpening, effects and FPS limit. */
public class GraphicsEnhancementsDialog extends ContentDialog {
    private static final int[] FPS_LIMITS = {0, 30, 45, 60, 90, 120};

    private final XServerDisplayActivity activity;
    private final SharedPreferences preferences;
    private final boolean isDarkMode;
    private final String initialConfig;
    private final int initialFpsLimit;
    private PostProcessing settings;
    private boolean updatingUI = false;

    private final Spinner sUpscaler;
    private final View llUpscalerSharpness;
    private final SeekBar sbUpscalerSharpness;
    private final CheckBox cbFitGameWindow;
    private final CheckBox cbFrameGen;
    private final Spinner sColorPreset;
    private final Spinner sFrameGenTarget;
    private final Spinner sFrameGenQuality;
    private static final String[] FG_QUALITY_NAMES = {"Balanced (faster)", "Quality (better motion, ~40% more GPU)"};
    private static final String[] FG_TARGET_NAMES = {"2x (fixed)", "Auto - target 60 fps", "Auto - target 90 fps", "Auto - target 120 fps"};
    private static final int[] FG_TARGETS = {0, 60, 90, 120};
    private final View llPresetStrength;
    private final SeekBar sbPresetStrength;
    private final Spinner sSharpenMode;
    private final View llSharpenSettings;
    private final SeekBar sbSharpenLevel;
    private final SeekBar sbBrightness;
    private final SeekBar sbContrast;
    private final SeekBar sbGamma;
    private final CheckBox cbHDR;
    private final CheckBox cbFXAA;
    private final CheckBox cbToon;
    private final CheckBox cbCRT;
    private final CheckBox cbNTSC;
    private final Spinner sFPSLimit;
    private final EditText etCustomFPSLimit;
    private final View llAdvanced;
    private final ImageView ivAdvancedArrow;

    public GraphicsEnhancementsDialog(XServerDisplayActivity activity) {
        super(activity, R.layout.graphics_enhancements_dialog);
        this.activity = activity;
        setIcon(R.drawable.ic_graphics_enhancements);
        setTitle(R.string.graphics_enhancements);

        preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        isDarkMode = preferences.getBoolean("dark_mode", true);

        XServerView xServerView = activity.getXServerView();
        XServer xServer = activity.getXServer();
        initialConfig = xServerView != null ? xServerView.getPostProcessing() : "";
        initialFpsLimit = xServer != null ? xServer.getFpsLimit() : 0;
        settings = new PostProcessing(initialConfig);

        sUpscaler = findViewById(R.id.SUpscaler);
        llUpscalerSharpness = findViewById(R.id.LLUpscalerSharpness);
        sbUpscalerSharpness = findViewById(R.id.SBUpscalerSharpness);
        cbFitGameWindow = findViewById(R.id.CBFitGameWindow);
        cbFrameGen = findViewById(R.id.CBFrameGen);
        sColorPreset = findViewById(R.id.SColorPreset);
        sFrameGenTarget = findViewById(R.id.SFrameGenTarget);
        sFrameGenQuality = findViewById(R.id.SFrameGenQuality);
        llPresetStrength = findViewById(R.id.LLPresetStrength);
        sbPresetStrength = findViewById(R.id.SBPresetStrength);
        sSharpenMode = findViewById(R.id.SSharpenMode);
        llSharpenSettings = findViewById(R.id.LLSharpenSettings);
        sbSharpenLevel = findViewById(R.id.SBSharpenLevel);
        sbBrightness = findViewById(R.id.SBBrightness);
        sbContrast = findViewById(R.id.SBContrast);
        sbGamma = findViewById(R.id.SBGamma);
        cbHDR = findViewById(R.id.CBEnableHDR);
        cbFXAA = findViewById(R.id.CBEnableFXAA);
        cbToon = findViewById(R.id.CBEnableToon);
        cbCRT = findViewById(R.id.CBEnableCRT);
        cbNTSC = findViewById(R.id.CBEnableNTSC);
        sFPSLimit = findViewById(R.id.SFPSLimit);
        etCustomFPSLimit = findViewById(R.id.ETCustomFPSLimit);
        llAdvanced = findViewById(R.id.LLAdvanced);
        ivAdvancedArrow = findViewById(R.id.IVAdvancedArrow);

        fitScrollToDialog();
        applyThemeToEditText(etCustomFPSLimit, isDarkMode);
        for (Spinner spinner : new Spinner[]{sUpscaler, sColorPreset, sSharpenMode, sFPSLimit, sFrameGenTarget, sFrameGenQuality}) {
            spinner.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
        }
        sUpscaler.setAdapter(createAdapter(activity, PostProcessing.UPSCALERS));
        sColorPreset.setAdapter(createAdapter(activity, PostProcessing.PRESET_NAMES));
        sFrameGenTarget.setAdapter(createAdapter(activity, FG_TARGET_NAMES));
        sFrameGenQuality.setAdapter(createAdapter(activity, FG_QUALITY_NAMES));
        sSharpenMode.setAdapter(createAdapter(activity, PostProcessing.SHARPEN_MODES));
        sbUpscalerSharpness.setSuffix("%");
        sbPresetStrength.setSuffix("%");

        loadFpsLimit(initialFpsLimit);
        settings.fpsLimit = initialFpsLimit;
        updateUI();
        setupListeners();

        // Advanced starts collapsed unless something in it is already in use.
        setAdvancedExpanded(settings.hasAdvancedOptions());
        findViewById(R.id.LLAdvancedHeader).setOnClickListener(v -> setAdvancedExpanded(llAdvanced.getVisibility() != View.VISIBLE));

        findViewById(R.id.IVFPSLimitInfo).setOnClickListener(v -> showFPSLimitInfo());
        findViewById(R.id.IVFrameGenInfo).setOnClickListener(v -> showFrameGenInfo());

        View btSaveForGame = findViewById(R.id.BTSaveForGame);
        btSaveForGame.setVisibility(activity.hasShortcut() ? View.VISIBLE : View.GONE);
        btSaveForGame.setOnClickListener(v -> {
            activity.savePostProcessing(settings.toString(), true);
            AppUtils.showToast(activity, "Saved for this game");
        });

        findViewById(R.id.BTSaveAsDefault).setOnClickListener(v -> {
            activity.savePostProcessing(settings.toString(), false);
            AppUtils.showToast(activity, "Saved as container default");
        });

        findViewById(R.id.BTResetEnhancements).setOnClickListener(v -> {
            settings = new PostProcessing();
            updateUI();
            applySettings();
        });

        setOnConfirmCallback(() -> {
            applySettings();
            applyFpsLimit();
        });

        setOnCancelCallback(() -> {
            if (xServerView != null) xServerView.setPostProcessing(initialConfig);
            if (xServer != null) xServer.setFpsLimit(initialFpsLimit);
        });
    }

    private void setAdvancedExpanded(boolean expanded) {
        llAdvanced.setVisibility(expanded ? View.VISIBLE : View.GONE);
        ivAdvancedArrow.setImageResource(expanded ? R.drawable.icon_list_item_expanded : R.drawable.icon_list_item_folded);
    }

    /**
     * Keeps the layout's fixed scroll height, but on short (landscape) screens the dialog squeezes its content
     * area below that height, hiding the bottom of the list. Shrink the ScrollView to the visible area so the
     * last options can still be scrolled into view. Only this dialog is affected.
     */
    private void fitScrollToDialog() {
        ViewGroup frame = getContentView().findViewById(R.id.FrameLayout);
        if (frame == null || frame.getChildCount() == 0) return;
        View scrollView = frame.getChildAt(0);
        frame.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int available = bottom - top;
            ViewGroup.LayoutParams params = scrollView.getLayoutParams();
            if (available > 0 && available < scrollView.getHeight() && params.height != available) {
                params.height = available;
                scrollView.post(() -> scrollView.setLayoutParams(params));
            }
        });
    }

    private void setupListeners() {
        setOnItemSelected(sUpscaler, position -> settings.upscaler = position);
        setOnItemSelected(sColorPreset, position -> settings.setPresetIndex(position));
        setOnItemSelected(sFrameGenTarget, position -> settings.frameGenTarget = FG_TARGETS[position]);
        setOnItemSelected(sFrameGenQuality, position -> settings.frameGenQuality = position == 1);
        setOnItemSelected(sSharpenMode, position -> settings.sharpenMode = position - 1);
        setOnItemSelected(sFPSLimit, position -> {
            etCustomFPSLimit.setVisibility(position == FPS_LIMITS.length ? View.VISIBLE : View.GONE);
            applyFpsLimit();
        });

        sbUpscalerSharpness.setOnValueChangeListener((sb, value) -> onSettingChanged(() -> settings.upscalerSharpness = value / 100f));
        sbPresetStrength.setOnValueChangeListener((sb, value) -> onSettingChanged(() -> settings.presetStrength = value / 100f));
        sbSharpenLevel.setOnValueChangeListener((sb, value) -> onSettingChanged(() -> settings.sharpenLevel = value));
        sbBrightness.setOnValueChangeListener((sb, value) -> onSettingChanged(() -> settings.brightness = value));
        sbContrast.setOnValueChangeListener((sb, value) -> onSettingChanged(() -> settings.contrast = value));
        sbGamma.setOnValueChangeListener((sb, value) -> onSettingChanged(() -> settings.gamma = value));

        cbFitGameWindow.setOnCheckedChangeListener((v, checked) -> onSettingChanged(() -> settings.fitWindow = checked));
        cbFrameGen.setOnCheckedChangeListener((v, checked) -> onSettingChanged(() -> settings.frameGen = checked));
        cbHDR.setOnCheckedChangeListener((v, checked) -> onSettingChanged(() -> settings.hdr = checked));
        cbFXAA.setOnCheckedChangeListener((v, checked) -> onSettingChanged(() -> settings.fxaa = checked));
        cbToon.setOnCheckedChangeListener((v, checked) -> onSettingChanged(() -> settings.toon = checked));
        cbCRT.setOnCheckedChangeListener((v, checked) -> onSettingChanged(() -> settings.crt = checked));
        cbNTSC.setOnCheckedChangeListener((v, checked) -> onSettingChanged(() -> settings.ntsc = checked));

        etCustomFPSLimit.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyFpsLimit();
            }
            @Override
            public void afterTextChanged(android.text.Editable s) {}
        });
    }

    private interface OnPositionSelected {
        void call(int position);
    }

    private void setOnItemSelected(Spinner spinner, OnPositionSelected callback) {
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                onSettingChanged(() -> callback.call(position));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void onSettingChanged(Runnable change) {
        if (updatingUI) return;
        change.run();
        updateVisibility();
        applySettings();
    }

    private void updateUI() {
        updatingUI = true;
        sUpscaler.setSelection(settings.upscaler);
        sbUpscalerSharpness.setValue(settings.upscalerSharpness * 100f);
        cbFitGameWindow.setChecked(settings.fitWindow);
        cbFrameGen.setChecked(settings.frameGen);
        sColorPreset.setSelection(settings.getPresetIndex());
        int fgTargetIndex = 0;
        for (int i = 0; i < FG_TARGETS.length; i++) if (FG_TARGETS[i] == settings.frameGenTarget) fgTargetIndex = i;
        sFrameGenTarget.setSelection(fgTargetIndex);
        sFrameGenQuality.setSelection(settings.frameGenQuality ? 1 : 0);
        sbPresetStrength.setValue(settings.presetStrength * 100f);
        sSharpenMode.setSelection(settings.sharpenMode + 1);
        sbSharpenLevel.setValue(settings.sharpenLevel);
        sbBrightness.setValue(settings.brightness);
        sbContrast.setValue(settings.contrast);
        sbGamma.setValue(settings.gamma);
        cbHDR.setChecked(settings.hdr);
        cbFXAA.setChecked(settings.fxaa);
        cbToon.setChecked(settings.toon);
        cbCRT.setChecked(settings.crt);
        cbNTSC.setChecked(settings.ntsc);
        updateVisibility();
        // Spinner selections are delivered asynchronously; keep listeners muted until they settle.
        sUpscaler.post(() -> updatingUI = false);
    }

    private void updateVisibility() {
        boolean edgeUpscaler = settings.upscaler == PostProcessing.UPSCALER_SGSR || settings.upscaler == PostProcessing.UPSCALER_FSR;
        llUpscalerSharpness.setVisibility(edgeUpscaler ? View.VISIBLE : View.GONE);
        llPresetStrength.setVisibility(settings.preset != 0 ? View.VISIBLE : View.GONE);
        llSharpenSettings.setVisibility(settings.sharpenMode != PostProcessing.SHARPEN_OFF ? View.VISIBLE : View.GONE);
    }

    private void applySettings() {
        XServerView xServerView = activity.getXServerView();
        if (xServerView != null) xServerView.setPostProcessing(settings.toString());
    }

    private void loadFpsLimit(int fpsLimit) {
        int selection = FPS_LIMITS.length;
        for (int i = 0; i < FPS_LIMITS.length; i++) {
            if (FPS_LIMITS[i] == fpsLimit) selection = i;
        }
        if (selection == FPS_LIMITS.length) {
            etCustomFPSLimit.setText(String.valueOf(fpsLimit));
            etCustomFPSLimit.setVisibility(View.VISIBLE);
        }
        sFPSLimit.setSelection(selection);
    }

    private void applyFpsLimit() {
        if (updatingUI) return;
        int position = sFPSLimit.getSelectedItemPosition();
        int fpsLimit = position < FPS_LIMITS.length ? FPS_LIMITS[position] : parseIntSafe(etCustomFPSLimit.getText().toString());
        settings.fpsLimit = fpsLimit;

        XServer xServer = activity.getXServer();
        if (xServer != null && xServer.getFpsLimit() != fpsLimit) xServer.setFpsLimit(fpsLimit);
        com.winlator.cmod.core.RefreshRateUtils.applyPreferredRefreshRate(activity, 0, fpsLimit);
    }

    private static int parseIntSafe(String text) {
        try {
            if (text != null && !text.trim().isEmpty()) return Math.max(0, Integer.parseInt(text.trim()));
        } catch (NumberFormatException ignored) {}
        return 0;
    }

    private void showFrameGenInfo() {
        ContentDialog dialog = new ContentDialog(getContext(), R.layout.info_message_dialog);
        dialog.setTitle("Frame Generation");
        dialog.setIcon(R.drawable.ic_driver_info);

        TextView tvMessage = dialog.findViewById(R.id.TVInfoMessage);
        String message = "<b>Frame Generation (ZeroFG)</b><br/>" +
                "Builds new frames between the game's own frames from the motion between them, so the game looks smoother than it renders.<br/><br/>" +
                "<b>Modes:</b><br/>" +
                "- <b>2x (fixed)</b>: one generated frame per game frame (30 becomes 60). Works while the game runs at 10-50 fps.<br/>" +
                "- <b>Auto - target N fps</b>: as many generated frames per game frame (up to 3) as fit under the target, never above it. " +
                "A game at 30 with a target of 60 runs 2x; at 20 it runs 3x; at 45 it is left alone.<br/><br/>" +
                "<b>Best results:</b><br/>" +
                "- Cap the game with the FPS Limiter above (e.g. 30 with a target of 60): a steady game rate gives even motion.<br/>" +
                "- The HUD shows <i>shown fps \u00b7 game fps \u00d7 multiplier \u00b7 ms per generated frame</i>.<br/><br/>" +
                "<b>Cost:</b><br/>" +
                "- About 3 ms (800x600) to 5 ms (1280x720) of GPU time per generated frame, run at high GPU priority so frames are on time.<br/>" +
                "- One frame of added input latency: a frame between two others can only be made once the newer one exists.<br/>" +
                "- Needs Vulkan 1.3. Menus, loading screens and stalls (under 10 fps) are not interpolated.<br/><br/>" +
                "<b>Quality:</b><br/>" +
                "- <b>Balanced</b>: two motion refinement passes (the default).<br/>" +
                "- <b>Quality</b>: four passes: more accurate motion on moving objects (fewer warps and trails, most visible at 800x600 and below), about 40% more GPU time. Best for games limited by the CPU.";
        tvMessage.setText(android.text.Html.fromHtml(message, android.text.Html.FROM_HTML_MODE_LEGACY));

        dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
        dialog.show();
    }

    private void showFPSLimitInfo() {
        ContentDialog dialog = new ContentDialog(getContext(), R.layout.info_message_dialog);
        dialog.setTitle("Universal FPS Limiter");
        dialog.setIcon(R.drawable.ic_driver_info);

        TextView tvMessage = dialog.findViewById(R.id.TVInfoMessage);
        String message = "<b>Universal FPS Limiter</b><br/>" +
                "A high-precision system-level limiter that throttles the game engine before it reaches the display.<br/><br/>" +
                "<b>How it works:</b><br/>" +
                "- It uses microsecond-precise thread sleeping and busy-waiting to ensure frames are presented at exact intervals.<br/>" +
                "- Unlike driver-level limits (like DXVK), this works across all wrappers (DXVK, WineD3D, etc.) and reduces both CPU heat and input jitter.";
        tvMessage.setText(android.text.Html.fromHtml(message, android.text.Html.FROM_HTML_MODE_LEGACY));

        dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
        dialog.show();
    }

    private ArrayAdapter<String> createAdapter(Context context, String[] items) {
        int textColor = isDarkMode ? Color.WHITE : Color.BLACK;
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(context, android.R.layout.simple_spinner_dropdown_item, items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                if (v instanceof TextView) ((TextView) v).setTextColor(textColor);
                return v;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                View v = super.getDropDownView(position, convertView, parent);
                if (v instanceof TextView) ((TextView) v).setTextColor(textColor);
                return v;
            }
        };
        return adapter;
    }
}
