package com.winlator.cmod.contentdialog;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.cardview.widget.CardView;

import com.winlator.cmod.R;
import com.winlator.cmod.ThemeManager;
import com.winlator.cmod.ThemePreset;
import com.winlator.cmod.core.UnitUtils;

import java.util.Locale;

public class CustomThemeDialog extends ContentDialog {
    private static final int[] ACCENT_SWATCHES = {
        0xFFB4182D, 0xFFE65100, 0xFFF57F17, 0xFF00C853,
        0xFF00E5FF, 0xFF00B0FF, 0xFF2979FF, 0xFF651FFF,
        0xFFAA00FF, 0xFFFF0055, 0xFFE040FB, 0xFF607D8B
    };

    private static final int[] DARK_BG_SWATCHES = {
        0xFF121212, 0xFF000000, 0xFF181A2F, 0xFF051F20, 0xFF0D0221
    };

    private static final int[] LIGHT_BG_SWATCHES = {
        0xFFFAFAFA, 0xFFFFFFFF, 0xFFF5F0EB, 0xFFEEF2F6
    };

    private int selectedAccent;
    private int selectedBackground;
    private final boolean isDarkMode;
    private boolean isUpdatingFromCode = false;

    private CardView cardPreview;
    private LinearLayout llPreviewToolbar;
    private LinearLayout llPreviewBody;
    private TextView tvPreviewToolbarTitle;
    private TextView tvPreviewText;
    private TextView tvPreviewAccentButton;
    private TextView tvPreviewContrastBadge;
    private View vColorPreviewBox;
    private EditText etHexColor;

    private SeekBar sbRed;
    private SeekBar sbGreen;
    private SeekBar sbBlue;
    private TextView tvRedLabel;
    private TextView tvGreenLabel;
    private TextView tvBlueLabel;

    public CustomThemeDialog(Context context) {
        super(context, R.layout.custom_theme_dialog);
        setTitle("Customize Theme");
        setIcon(R.drawable.icon_popup_menu_settings);

        isDarkMode = ThemeManager.isDarkMode(context);
        selectedAccent = ThemeManager.getCustomAccentColor(context);
        selectedBackground = ThemeManager.getCustomBackgroundColor(context);

        initViews();
        buildSwatches();
        syncSlidersWithColor(selectedAccent);
        updatePreview();

        setOnConfirmCallback(() -> {
            ThemeManager.setCustomColors(context, selectedAccent, selectedBackground);
            ThemeManager.selectCustomPreset(context);
            if (context instanceof Activity) {
                ((Activity) context).recreate();
            }
        });
    }

    private void initViews() {
        View root = getContentView();
        cardPreview = root.findViewById(R.id.CardPreview);
        llPreviewToolbar = root.findViewById(R.id.LLPreviewToolbar);
        llPreviewBody = root.findViewById(R.id.LLPreviewBody);
        tvPreviewToolbarTitle = root.findViewById(R.id.TVPreviewToolbarTitle);
        tvPreviewText = root.findViewById(R.id.TVPreviewText);
        tvPreviewAccentButton = root.findViewById(R.id.TVPreviewAccentButton);
        tvPreviewContrastBadge = root.findViewById(R.id.TVPreviewContrastBadge);
        vColorPreviewBox = root.findViewById(R.id.VColorPreviewBox);
        etHexColor = root.findViewById(R.id.ETHexColor);

        sbRed = root.findViewById(R.id.SBRed);
        sbGreen = root.findViewById(R.id.SBGreen);
        sbBlue = root.findViewById(R.id.SBBlue);
        tvRedLabel = root.findViewById(R.id.TVRedLabel);
        tvGreenLabel = root.findViewById(R.id.TVGreenLabel);
        tvBlueLabel = root.findViewById(R.id.TVBlueLabel);

        // Ensure visible, high-contrast styling on EditText in both light and dark modes
        etHexColor.setTextColor(isDarkMode ? 0xFFFFFFFF : 0xFF121212);
        etHexColor.setHintTextColor(isDarkMode ? 0xFF888888 : 0xFF757575);
        etHexColor.setBackgroundResource(isDarkMode ? R.drawable.edit_text_dark : R.drawable.edit_text);

        etHexColor.setText(String.format(Locale.ENGLISH, "#%06X", (0xFFFFFF & selectedAccent)));
        etHexColor.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (isUpdatingFromCode) return;
                try {
                    String hex = s.toString().trim();
                    if (!hex.startsWith("#")) hex = "#" + hex;
                    if (hex.length() == 7) {
                        int parsed = Color.parseColor(hex);
                        selectedAccent = parsed;
                        syncSlidersWithColor(parsed);
                        updatePreview();
                    }
                } catch (Exception ignored) {}
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        SeekBar.OnSeekBarChangeListener rgbListener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || isUpdatingFromCode) return;
                int r = sbRed.getProgress();
                int g = sbGreen.getProgress();
                int b = sbBlue.getProgress();
                selectedAccent = Color.rgb(r, g, b);

                tvRedLabel.setText(String.format(Locale.ENGLISH, "R: %d", r));
                tvGreenLabel.setText(String.format(Locale.ENGLISH, "G: %d", g));
                tvBlueLabel.setText(String.format(Locale.ENGLISH, "B: %d", b));

                isUpdatingFromCode = true;
                etHexColor.setText(String.format(Locale.ENGLISH, "#%06X", (0xFFFFFF & selectedAccent)));
                isUpdatingFromCode = false;

                updatePreview();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        };

        sbRed.setOnSeekBarChangeListener(rgbListener);
        sbGreen.setOnSeekBarChangeListener(rgbListener);
        sbBlue.setOnSeekBarChangeListener(rgbListener);
    }

    private void syncSlidersWithColor(int color) {
        isUpdatingFromCode = true;
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);

        sbRed.setProgress(r);
        sbGreen.setProgress(g);
        sbBlue.setProgress(b);

        tvRedLabel.setText(String.format(Locale.ENGLISH, "R: %d", r));
        tvGreenLabel.setText(String.format(Locale.ENGLISH, "G: %d", g));
        tvBlueLabel.setText(String.format(Locale.ENGLISH, "B: %d", b));
        isUpdatingFromCode = false;
    }

    private void buildSwatches() {
        View root = getContentView();
        LinearLayout llAccents = root.findViewById(R.id.LLColorSwatches);
        LinearLayout llBgs = root.findViewById(R.id.LLBackgroundSwatches);

        int sizePx = (int) UnitUtils.dpToPx(36);
        int marginPx = (int) UnitUtils.dpToPx(6);

        // Build Accent Swatches
        for (final int color : ACCENT_SWATCHES) {
            View swatch = createSwatchView(color, sizePx, marginPx);
            swatch.setOnClickListener(v -> {
                selectedAccent = color;
                isUpdatingFromCode = true;
                etHexColor.setText(String.format(Locale.ENGLISH, "#%06X", (0xFFFFFF & color)));
                isUpdatingFromCode = false;
                syncSlidersWithColor(color);
                updatePreview();
            });
            llAccents.addView(swatch);
        }

        // Build Background Swatches
        int[] bgList = isDarkMode ? DARK_BG_SWATCHES : LIGHT_BG_SWATCHES;
        for (final int color : bgList) {
            View swatch = createSwatchView(color, sizePx, marginPx);
            swatch.setOnClickListener(v -> {
                selectedBackground = color;
                updatePreview();
            });
            llBgs.addView(swatch);
        }
    }

    private View createSwatchView(int color, int size, int margin) {
        View view = new View(getContext());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.setMargins(margin, margin, margin, margin);
        view.setLayoutParams(params);

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setShape(GradientDrawable.OVAL);
        gd.setStroke(2, 0x44888888);
        view.setBackground(gd);
        return view;
    }

    private void updatePreview() {
        // Update Hex color box
        GradientDrawable previewBoxDrawable = new GradientDrawable();
        previewBoxDrawable.setColor(selectedAccent);
        previewBoxDrawable.setCornerRadius(UnitUtils.dpToPx(6));
        previewBoxDrawable.setStroke(1, 0x44888888);
        vColorPreviewBox.setBackground(previewBoxDrawable);

        // Update fake toolbar
        llPreviewToolbar.setBackgroundColor(selectedAccent);

        // Update card surface & background
        int surfaceColor = isDarkMode ? ThemePreset.lerp(selectedBackground, 0xFFFFFFFF, 0.08f) : 0xFFFFFFFF;
        cardPreview.setCardBackgroundColor(surfaceColor);
        llPreviewBody.setBackgroundColor(surfaceColor);

        // Derive high-contrast text color
        int textColor = isDarkMode ? 0xFFE0E0E0 : 0xFF121212;
        tvPreviewText.setTextColor(textColor);

        // Update button
        GradientDrawable btnDrawable = new GradientDrawable();
        btnDrawable.setColor(selectedAccent);
        btnDrawable.setCornerRadius(UnitUtils.dpToPx(14));
        tvPreviewAccentButton.setBackground(btnDrawable);

        int onAccentTextColor = ThemePreset.contrastRatio(0xFFFFFFFF, selectedAccent) >= 3.0 ? 0xFFFFFFFF : 0xFF121212;
        tvPreviewAccentButton.setTextColor(onAccentTextColor);

        // Calculate contrast
        double contrast = ThemePreset.contrastRatio(selectedAccent, surfaceColor);
        String contrastText = String.format(Locale.ENGLISH, "Contrast: %.1f:1 (%s)", contrast, contrast >= 3.0 ? "Pass" : "Low");
        tvPreviewContrastBadge.setText(contrastText);

        GradientDrawable badgeDrawable = new GradientDrawable();
        badgeDrawable.setColor(contrast >= 4.5 ? 0xFF2E7D32 : (contrast >= 3.0 ? 0xFFE65100 : 0xFFC62828));
        badgeDrawable.setCornerRadius(UnitUtils.dpToPx(10));
        tvPreviewContrastBadge.setBackground(badgeDrawable);
        tvPreviewContrastBadge.setTextColor(0xFFFFFFFF);
    }
}
