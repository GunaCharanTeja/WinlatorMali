package com.winlator.cmod.contentdialog;

import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import com.winlator.cmod.R;
import com.winlator.cmod.XServerDisplayActivity;
import com.winlator.cmod.renderer.GLRenderer;
import com.winlator.cmod.renderer.lsfg.LSFGEffect;
import com.winlator.cmod.widget.SeekBar;

public class GraphicsEnhancementsDialog extends ContentDialog {
    private final XServerDisplayActivity activity;
    private final CheckBox cbEnableLSFG;
    private final Spinner sLSFGQuality;
    private final Spinner sLSFGTargetFPS;
    private final LinearLayout llLSFGSettings;
    private final SeekBar sbLSFGMotionBlur;

    public GraphicsEnhancementsDialog(XServerDisplayActivity activity) {
        super(activity, R.layout.graphics_enhancements_dialog);
        this.activity = activity;
        setIcon(R.drawable.ic_graphics_enhancements);
        setTitle(R.string.graphics_enhancements);

        cbEnableLSFG = findViewById(R.id.CBEnableLSFG);
        sLSFGQuality = findViewById(R.id.SLSFGQuality);
        sLSFGTargetFPS = findViewById(R.id.SLSFGTargetFPS);
        llLSFGSettings = findViewById(R.id.LLLSFGSettings);
        sbLSFGMotionBlur = findViewById(R.id.SBLSFGMotionBlur);

        GLRenderer renderer = activity.getXServerView().getRenderer();
        LSFGEffect lsfgEffect = renderer.getEffectComposer().getEffect(LSFGEffect.class);
        boolean lsfgEnabled = lsfgEffect != null && lsfgEffect.getManager().isActive();

        cbEnableLSFG.setChecked(lsfgEnabled);
        llLSFGSettings.setVisibility(lsfgEnabled ? View.VISIBLE : View.GONE);

        if (lsfgEffect != null) {
            sLSFGQuality.setSelection(lsfgEffect.getQuality());
            sbLSFGMotionBlur.setValue(lsfgEffect.getSharpenAmount());
            
            int targetFPS = lsfgEffect.getManager().getTargetFPS();
            int targetFPSSelection = 0;
            if (targetFPS == 30) targetFPSSelection = 1;
            else if (targetFPS == 40) targetFPSSelection = 2;
            else if (targetFPS == 50) targetFPSSelection = 3;
            else if (targetFPS == 60) targetFPSSelection = 4;
            else if (targetFPS == 90) targetFPSSelection = 5;
            else if (targetFPS == 120) targetFPSSelection = 6;
            sLSFGTargetFPS.setSelection(targetFPSSelection);
        } else {
            sLSFGQuality.setSelection(1);
            sbLSFGMotionBlur.setValue(0.5f);
            sLSFGTargetFPS.setSelection(4);
        }

        cbEnableLSFG.setOnCheckedChangeListener((buttonView, isChecked) -> {
            llLSFGSettings.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            applyEffects();
        });

        findViewById(R.id.IVLSFGInfo).setOnClickListener(v -> showLSFGInfo());

        setOnConfirmCallback(this::applyEffects);
    }

    private void showLSFGInfo() {
        ContentDialog dialog = new ContentDialog(getContext(), R.layout.lsfg_info_dialog);
        dialog.setTitle("Apex Frame Generation");
        dialog.setIcon(R.drawable.ic_driver_info);

        TextView tvMessage = dialog.findViewById(R.id.TVInfoMessage);
        String message = "<b>What is Apex?</b><br/>" +
                "Apex is a frame-generator born in Winlator Mali. It creates extra frames to turn low FPS (like 20-30) into a smooth 60-120 FPS experience.<br/><br/>" +
                "<b>How it works:</b><br/>" +
                "- <b>Direct Pacing:</b> Selecting a Target FPS (e.g. 60 FPS) forces the rendering calls to align directly with Android Choreographer VSYNC. It uses microsecond-precise thread sleeping to pace frame generation to your target.<br/>" +
                "- <b>Dynamic Fake Frames:</b> If your game runs at a lower framerate (e.g. 15-20 FPS), Apex automatically generates more interpolated frames in a row (e.g. 3x or 4x interpolation) to bridge the gap and reach your target FPS.<br/><br/>" +
                "<b>Important Warnings:</b><br/>" +
                "- <b>Visual Artifacts:</b> If the base game runs extremely slow (below 20 FPS), generating too many fake frames in a row can cause input latency (sluggish controls) and visual ghosting or warping.<br/>" +
                "- <b>GPU Workload:</b> Locking high Target FPS (e.g. 90/120 FPS) increases GPU workload. If the image stutters or vibrates, reduce the Target FPS or use the Performance preset to avoid GPU saturation.";
        tvMessage.setText(android.text.Html.fromHtml(message, android.text.Html.FROM_HTML_MODE_LEGACY));
        
        dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
        dialog.show();
    }

    private void applyEffects() {
        GLRenderer renderer = activity.getXServerView().getRenderer();
        boolean lsfgEnabled = cbEnableLSFG.isChecked();
        renderer.getEffectComposer().toggleLSFGEffect(lsfgEnabled);

        LSFGEffect lsfgEffect = renderer.getEffectComposer().getEffect(LSFGEffect.class);
        if (lsfgEffect != null && lsfgEnabled) {
            lsfgEffect.setQuality(sLSFGQuality.getSelectedItemPosition());
            lsfgEffect.setSharpenAmount(sbLSFGMotionBlur.getValue());

            int targetFPS = 0;
            int targetFPSSelection = sLSFGTargetFPS.getSelectedItemPosition();
            if (targetFPSSelection == 1) targetFPS = 30;
            else if (targetFPSSelection == 2) targetFPS = 40;
            else if (targetFPSSelection == 3) targetFPS = 50;
            else if (targetFPSSelection == 4) targetFPS = 60;
            else if (targetFPSSelection == 5) targetFPS = 90;
            else if (targetFPSSelection == 6) targetFPS = 120;
            lsfgEffect.getManager().setTargetFPS(targetFPS);
        }
        activity.getXServerView().requestRender();
    }
}
