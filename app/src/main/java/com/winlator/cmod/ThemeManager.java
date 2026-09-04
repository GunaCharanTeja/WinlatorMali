package com.winlator.cmod;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.annotation.StyleRes;
import androidx.preference.PreferenceManager;
import com.google.android.material.tabs.TabLayout;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class ThemeManager {
    public static final String PREF_DARK_MODE = "dark_mode";
    public static final String PREF_DARK_THEME_INDEX = "dark_theme_preset_index";
    public static final String PREF_CUSTOM_ACCENT = "custom_accent_color";
    public static final String PREF_CUSTOM_BG = "custom_background_color";

    // =========================================================================
    // CURATED THEME PRESETS (Midnight Ocean is default at index 0)
    // =========================================================================
    public static final List<ThemePreset> DARK_PRESETS = Arrays.asList(
        new ThemePreset("Midnight Ocean",       0xFF021024, 0xFF052659, 0xFF1D4274, 0xFF5483B3),
        new ThemePreset("Obsidian OLED",        0xFF000000, 0xFF101010, 0xFF1A1A1A, 0xFF3D5AFE),
        new ThemePreset("Cyberpunk Neon",       0xFF0D0221, 0xFF190933, 0xFF261447, 0xFFFF0055),
        new ThemePreset("Crimson Dusk",         0xFF181A2F, 0xFF242E49, 0xFF37415C, 0xFFB4182D),
        new ThemePreset("Classic (Dark Glass)", 0xFF121212, 0xFF1E1E1E, 0xFF2A2A2A, 0xFF0288D1),
        new ThemePreset("Emerald Depth",        0xFF051F20, 0xFF0B2B26, 0xFF163832, 0xFF00E676),
        new ThemePreset("Grape Sunset",         0xFF1D1A39, 0xFF451952, 0xFF662549, 0xFFF39F5A),
        new ThemePreset("Violet Dream",         0xFF190019, 0xFF2B124C, 0xFF522B5B, 0xFF854F6C),
        new ThemePreset("Custom Palette...",    0xFF021024, 0xFF052659, 0xFF1D4274, 0xFF5483B3)
    );

    @StyleRes
    private static final int[] DARK_STYLES = {
        R.style.ThemePreset_OceanIce,
        R.style.ThemePreset_ObsidianOLED,
        R.style.ThemePreset_Cyberpunk,
        R.style.ThemePreset_CrimsonDusk,
        R.style.ThemePreset_DarkGlass,
        R.style.ThemePreset_EmeraldDepth,
        R.style.ThemePreset_GrapeSunset,
        R.style.ThemePreset_VioletCream,
        R.style.ThemePreset_Custom_Dark
    };

    private ThemeManager() {}

    public static boolean isDarkMode(Context context) {
        return true;
    }

    public static List<ThemePreset> getPresets(Context context) {
        return Collections.unmodifiableList(DARK_PRESETS);
    }

    public static String[] getPresetNames(Context context) {
        List<ThemePreset> presets = getPresets(context);
        String[] names = new String[presets.size()];
        for (int i = 0; i < presets.size(); i++) {
            names[i] = presets.get(i).name;
        }
        return names;
    }

    public static int getSelectedPresetIndex(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int max = DARK_PRESETS.size() - 1;
        int index = prefs.getInt(PREF_DARK_THEME_INDEX, 0);
        if (index < 0 || index > max) index = 0;
        return index;
    }

    public static void setSelectedPresetIndex(Context context, int index) {
        int max = DARK_PRESETS.size() - 1;
        if (index < 0 || index > max) index = 0;
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putInt(PREF_DARK_THEME_INDEX, index)
                .apply();
    }

    public static boolean isCustomPresetSelected(Context context) {
        int customIndex = DARK_PRESETS.size() - 1;
        return getSelectedPresetIndex(context) == customIndex;
    }

    public static void selectCustomPreset(Context context) {
        int customIndex = DARK_PRESETS.size() - 1;
        setSelectedPresetIndex(context, customIndex);
    }

    public static int getCustomAccentColor(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getInt(PREF_CUSTOM_ACCENT, 0xFF5483B3);
    }

    public static int getCustomBackgroundColor(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getInt(PREF_CUSTOM_BG, 0xFF021024);
    }

    public static void setCustomColors(Context context, int accent, int background) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putInt(PREF_CUSTOM_ACCENT, accent)
                .putInt(PREF_CUSTOM_BG, background)
                .apply();
    }

    public static ThemePreset getSelectedPreset(Context context) {
        if (isCustomPresetSelected(context)) {
            int accent = getCustomAccentColor(context);
            int bg = getCustomBackgroundColor(context);
            int surface = ThemePreset.lerp(bg, 0xFFFFFFFF, 0.08f);
            int surfaceVariant = ThemePreset.lerp(bg, 0xFFFFFFFF, 0.14f);
            return new ThemePreset("Custom", bg, surface, surfaceVariant, accent);
        }
        int index = getSelectedPresetIndex(context);
        return DARK_PRESETS.get(index);
    }

    public static void applyTheme(Context context) {
        Resources.Theme theme = context.getTheme();
        if (theme == null) return;
        int index = getSelectedPresetIndex(context);
        theme.applyStyle(DARK_STYLES[index], true);
    }

    public static int getAccentColor(Context context) {
        if (isCustomPresetSelected(context)) {
            return getCustomAccentColor(context);
        }
        return getSelectedPreset(context).primary;
    }

    public static int getPrimaryColor(Context context) {
        return getSelectedPreset(context).surfaceVariant;
    }

    public static int getBackgroundColor(Context context) {
        if (isCustomPresetSelected(context)) {
            return getCustomBackgroundColor(context);
        }
        return getSelectedPreset(context).background;
    }

    public static int getSurfaceColor(Context context) {
        return getSelectedPreset(context).surface;
    }

    public static int getOnSurfaceTextColor(Context context) {
        return getSelectedPreset(context).onSurface;
    }

    public static int getDividerColor(Context context) {
        return getSelectedPreset(context).divider;
    }

    public static void applyThemeToView(View view, Context context) {
        if (view == null || context == null) return;
        ThemePreset preset = getSelectedPreset(context);
        int onSurface = preset.onSurface;
        int onSurfaceVariant = preset.onSurfaceVariant;
        int accent = preset.primary;
        int surface = preset.surface;
        int divider = preset.divider;

        applyThemeRecursively(view, true, onSurface, onSurfaceVariant, accent, surface, divider);
    }

    private static void applyThemeRecursively(View view, boolean isDarkMode, int onSurface, int onSurfaceVariant, int accent, int surface, int divider) {
        if (view == null) return;

        if (view instanceof EditText) {
            EditText et = (EditText) view;
            et.setTextColor(onSurface);
            et.setHintTextColor(onSurfaceVariant);
            et.setBackgroundResource(isDarkMode ? R.drawable.edit_text_dark : R.drawable.edit_text);
        } else if (view instanceof CheckBox) {
            CheckBox cb = (CheckBox) view;
            cb.setTextColor(onSurface);
            cb.setButtonTintList(ColorStateList.valueOf(accent));
        } else if (view instanceof Spinner) {
            Spinner spinner = (Spinner) view;
            spinner.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
            spinner.setBackgroundResource(isDarkMode ? R.drawable.combo_box_dark : R.drawable.combo_box);
            int padStart = (int) (12 * spinner.getResources().getDisplayMetrics().density);
            int padEnd = (int) (42 * spinner.getResources().getDisplayMetrics().density);
            spinner.setPaddingRelative(padStart, 0, padEnd, 0);
        } else if (view instanceof TabLayout) {
            TabLayout tabLayout = (TabLayout) view;
            tabLayout.setBackgroundResource(isDarkMode ? R.drawable.tab_layout_background_dark : R.drawable.tab_layout_background);
            tabLayout.setSelectedTabIndicatorColor(accent);
            int tabColor = isDarkMode ? 0xFFFFFFFF : 0xFF121212;
            tabLayout.setTabTextColors(tabColor, tabColor);
            for (int i = 0; i < tabLayout.getTabCount(); i++) {
                TabLayout.Tab tab = tabLayout.getTabAt(i);
                if (tab != null && tab.view != null) {
                    for (int c = 0; c < tab.view.getChildCount(); c++) {
                        View child = tab.view.getChildAt(c);
                        if (child instanceof TextView) {
                            ((TextView) child).setTextColor(tabColor);
                        }
                    }
                }
            }
        } else if (view instanceof SeekBar) {
            SeekBar sb = (SeekBar) view;
            sb.setThumbTintList(ColorStateList.valueOf(accent));
            sb.setProgressTintList(ColorStateList.valueOf(accent));
        } else if (view instanceof com.google.android.material.floatingactionbutton.FloatingActionButton) {
            com.google.android.material.floatingactionbutton.FloatingActionButton fab = (com.google.android.material.floatingactionbutton.FloatingActionButton) view;
            double lum = (0.299 * android.graphics.Color.red(accent) + 0.587 * android.graphics.Color.green(accent) + 0.114 * android.graphics.Color.blue(accent)) / 255.0;
            int iconColor = lum > 0.65 ? 0xFF121212 : 0xFFFFFFFF;
            fab.setBackgroundTintList(ColorStateList.valueOf(accent));
            fab.setImageTintList(ColorStateList.valueOf(iconColor));
            fab.setSupportBackgroundTintList(ColorStateList.valueOf(accent));
            fab.setSupportImageTintList(ColorStateList.valueOf(iconColor));
        } else if (view instanceof ImageButton) {
            ImageButton ib = (ImageButton) view;
            styleImageButton(ib, isDarkMode, onSurface, onSurfaceVariant, accent, surface, divider);
        } else if (view instanceof ImageView) {
            ImageView iv = (ImageView) view;
            int id = iv.getId();
            if (id == R.id.BTHelp || id == R.id.BTHelpApiKey || id == R.id.BTHelpDXWrapper || id == R.id.BTHelpFileProvider) {
                iv.setImageTintList(ColorStateList.valueOf(onSurfaceVariant));
            } else {
                View parent = (View) iv.getParent();
                boolean isCardArt = parent != null && (parent.getId() == R.id.LLInnerArea || parent instanceof androidx.cardview.widget.CardView);
                boolean isIconOrButton = (id == R.id.BTRun || id == R.id.BTMenu || id == R.id.IVIcon || id == R.id.BTRefresh ||
                        id == R.id.BTAdd || id == R.id.BTAddDrive || id == R.id.BTAddEnvVar || id == R.id.BTAddProfile ||
                        id == R.id.BTAddWheel || id == R.id.BTBCNConfig || id == R.id.BTDisplayDriverConfig ||
                        id == R.id.BTGraphicsDriverConfig || id == R.id.BTDXWrapperConfig || id == R.id.BTDuplicateProfile ||
                        id == R.id.BTEditProfile || id == R.id.BTExportProfile || id == R.id.BTImportProfile ||
                        id == R.id.BTRemoveProfile || id == R.id.BTHide || id == R.id.BTInfo || id == R.id.BTMagnifier ||
                        id == R.id.BTMove || id == R.id.BTNewFolder || id == R.id.BTOpacity || id == R.id.BTPause ||
                        id == R.id.BTRadialWheel || id == R.id.BTRemove || id == R.id.BTReset || id == R.id.BTRestoreSaves ||
                        id == R.id.BTScreenEffects || id == R.id.BTSearch || id == R.id.BTSettings ||
                        id == R.id.BTToggleFullscreen || id == R.id.BTToggleSelectAll || id == R.id.BTUpDir ||
                        id == R.id.BTZoomMinus || id == R.id.BTZoomPlus || id == R.id.IVDriveIcon || id == R.id.IVDeviceIcon ||
                        id == R.id.BTFileMenu || id == R.id.BTCloseSearch || id == R.id.ImageView);
                if (isIconOrButton && (!isCardArt || id != R.id.ImageView)) {
                    iv.setImageTintList(ColorStateList.valueOf(accent));
                }
            }
        } else if (view instanceof android.widget.ProgressBar) {
            android.widget.ProgressBar pb = (android.widget.ProgressBar) view;
            pb.setProgressTintList(ColorStateList.valueOf(accent));
            pb.setIndeterminateTintList(ColorStateList.valueOf(accent));
        } else if (view instanceof android.widget.ToggleButton) {
            // Keep ToggleButton's dedicated toggle drawable, do NOT style as push button
            android.widget.ToggleButton tb = (android.widget.ToggleButton) view;
            tb.setTextColor(onSurface);
        } else if (view instanceof androidx.appcompat.widget.SwitchCompat) {
            androidx.appcompat.widget.SwitchCompat sc = (androidx.appcompat.widget.SwitchCompat) view;
            sc.setTextColor(onSurface);
            sc.setThumbTintList(ColorStateList.valueOf(accent));
            sc.setTrackTintList(ColorStateList.valueOf(androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 100)));
        } else if (view instanceof android.widget.Switch) {
            android.widget.Switch sw = (android.widget.Switch) view;
            sw.setTextColor(onSurface);
            sw.setThumbTintList(ColorStateList.valueOf(accent));
            sw.setTrackTintList(ColorStateList.valueOf(androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 100)));
        } else if (view instanceof android.widget.RadioButton) {
            android.widget.RadioButton rb = (android.widget.RadioButton) view;
            rb.setTextColor(onSurface);
            rb.setButtonTintList(ColorStateList.valueOf(accent));
        } else if (view instanceof android.widget.Button) {
            android.widget.Button btn = (android.widget.Button) view;
            styleButton(btn, isDarkMode, onSurface, onSurfaceVariant, accent, surface, divider);
        } else if (view instanceof TextView) {
            TextView tv = (TextView) view;
            int id = tv.getId();
            if (id == R.id.TVGraphicsDriverVersion) {
                tv.setTextColor(accent);
            } else if (isFieldSetHeader(tv)) {
                tv.setTextColor(accent);
                tv.setTypeface(null, Typeface.BOLD);
            } else {
                tv.setTextColor(onSurface);
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyThemeRecursively(group.getChildAt(i), isDarkMode, onSurface, onSurfaceVariant, accent, surface, divider);
            }
        }
    }

    public static void applyFieldSetLabelStyle(TextView textView, boolean isDarkMode) {
        if (textView == null) return;
        Context context = textView.getContext();
        int accent = getAccentColor(context);
        textView.setTextColor(accent);
        textView.setTypeface(null, Typeface.BOLD);
    }

    private static boolean isFieldSetHeader(TextView tv) {
        if (tv == null) return false;

        // 0. Check explicit tag
        if ("section_header".equals(tv.getTag())) return true;

        // 1. Check if enclosed within a FrameLayout header position
        if (tv.getParent() instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) tv.getParent();
            if (parent instanceof FrameLayout) {
                for (int i = 0; i < parent.getChildCount(); i++) {
                    View sibling = parent.getChildAt(i);
                    if (sibling != tv && sibling instanceof ViewGroup) {
                        return true;
                    }
                }
            }
        }

        // 2. Check view ID
        int id = tv.getId();
        return id == R.id.TVDesktop || id == R.id.TVDirectInput || id == R.id.TVDirectX ||
               id == R.id.TVGeneral || id == R.id.TVBox64 || id == R.id.TVFEXCore ||
               id == R.id.TVSystem || id == R.id.TVImageFs || id == R.id.TVLogs ||
               id == R.id.TVExperimental || id == R.id.TVSound || id == R.id.TVTheme ||
               id == R.id.TVShortcutSettings || id == R.id.TVCustomApiKey || id == R.id.TVXServer ||
               id == R.id.TVGeneralDisplay || id == R.id.TVGraphicsRendering || id == R.id.TVSystemAudio ||
               id == R.id.TVInputControls || id == R.id.TVLaunchArgs ||
               id == R.id.TVProcessorAffinity || id == R.id.TVXRController;
    }

    public static void styleButton(android.widget.Button btn, boolean isDarkMode, int onSurface, int onSurfaceVariant, int accent, int surface, int divider) {
        if (btn == null) return;
        int id = btn.getId();
        CharSequence text = btn.getText();
        String textStr = text != null ? text.toString().trim().toLowerCase(java.util.Locale.ROOT) : "";

        boolean isConfirmOrPrimary = (id == R.id.BTConfirm || id == R.id.BTRun ||
                id == R.id.BTInstallContent || id == R.id.BTInstallDriver || id == R.id.BTReInstallImagefs ||
                textStr.contains("install") || textStr.contains("confirm") || textStr.contains("run") ||
                textStr.contains("save") || textStr.contains("apply") || textStr.contains("download") ||
                "ok".equals(textStr) || textStr.contains("continue") || textStr.contains("done"));

        boolean isCancelOrSecondary = (id == R.id.BTCancel || id == R.id.BTReset ||
                textStr.contains("cancel") || textStr.contains("reset") || textStr.contains("close") ||
                textStr.contains("clear") || textStr.contains("no,") || "no".equals(textStr));

        float cornerRadius = com.winlator.cmod.core.UnitUtils.dpToPx(8);
        int strokeWidth = (int) Math.max(1, com.winlator.cmod.core.UnitUtils.dpToPx(1));

        if (isConfirmOrPrimary) {
            // Modern Glassmorphic Accent Button (Harmonized with Cancel glass card)
            android.graphics.drawable.StateListDrawable sld = new android.graphics.drawable.StateListDrawable();
            android.graphics.drawable.GradientDrawable normal = new android.graphics.drawable.GradientDrawable();
            int normalBg = androidx.core.graphics.ColorUtils.setAlphaComponent(accent, isDarkMode ? 50 : 35);
            int strokeColor = accent;
            int strokeWidthAccent = (int) Math.max(1, com.winlator.cmod.core.UnitUtils.dpToPx(1.5f));
            normal.setColor(normalBg);
            normal.setStroke(strokeWidthAccent, strokeColor);
            normal.setCornerRadius(cornerRadius);

            android.graphics.drawable.GradientDrawable pressed = new android.graphics.drawable.GradientDrawable();
            int pressedBg = androidx.core.graphics.ColorUtils.setAlphaComponent(accent, isDarkMode ? 120 : 80);
            pressed.setColor(pressedBg);
            pressed.setStroke(strokeWidthAccent, strokeColor);
            pressed.setCornerRadius(cornerRadius);

            sld.addState(new int[]{android.R.attr.state_pressed}, pressed);
            sld.addState(new int[]{android.R.attr.state_focused}, pressed);
            sld.addState(new int[]{}, normal);

            btn.setBackground(sld);
            btn.setBackgroundTintList(null);

            btn.setTextColor(accent);
            btn.setTypeface(null, Typeface.BOLD);
        } else if (isCancelOrSecondary) {
            // Modern Tonal/Outlined Cancel Button (1:1 identical structure/stroke/weight to OK button)
            android.graphics.drawable.StateListDrawable sld = new android.graphics.drawable.StateListDrawable();
            android.graphics.drawable.GradientDrawable normal = new android.graphics.drawable.GradientDrawable();
            int normalBg = isDarkMode ? 0x1AFFFFFF : 0x0F000000;
            int strokeColor = isDarkMode ? 0x4DFFFFFF : 0x33000000;
            int strokeWidthCancel = (int) Math.max(1, com.winlator.cmod.core.UnitUtils.dpToPx(1.5f));
            normal.setColor(normalBg);
            normal.setStroke(strokeWidthCancel, strokeColor);
            normal.setCornerRadius(cornerRadius);

            android.graphics.drawable.GradientDrawable pressed = new android.graphics.drawable.GradientDrawable();
            int pressedBg = isDarkMode ? 0x33FFFFFF : 0x1F000000;
            pressed.setColor(pressedBg);
            pressed.setStroke(strokeWidthCancel, strokeColor);
            pressed.setCornerRadius(cornerRadius);

            sld.addState(new int[]{android.R.attr.state_pressed}, pressed);
            sld.addState(new int[]{android.R.attr.state_focused}, pressed);
            sld.addState(new int[]{}, normal);

            btn.setBackground(sld);
            btn.setBackgroundTintList(null);

            btn.setTextColor(onSurface);
            btn.setTypeface(null, Typeface.BOLD);
        } else {
            // Modern Semi-Translucent Accent Outlined Button (Add, Download, Refresh, etc.)
            android.graphics.drawable.StateListDrawable sld = new android.graphics.drawable.StateListDrawable();
            android.graphics.drawable.GradientDrawable normal = new android.graphics.drawable.GradientDrawable();
            int normalBg = androidx.core.graphics.ColorUtils.setAlphaComponent(accent, isDarkMode ? 35 : 25);
            int strokeColor = androidx.core.graphics.ColorUtils.setAlphaComponent(accent, isDarkMode ? 120 : 90);
            normal.setColor(normalBg);
            normal.setStroke(strokeWidth, strokeColor);
            normal.setCornerRadius(cornerRadius);

            android.graphics.drawable.GradientDrawable pressed = new android.graphics.drawable.GradientDrawable();
            int pressedBg = androidx.core.graphics.ColorUtils.setAlphaComponent(accent, isDarkMode ? 70 : 50);
            pressed.setColor(pressedBg);
            pressed.setStroke(strokeWidth, strokeColor);
            pressed.setCornerRadius(cornerRadius);

            sld.addState(new int[]{android.R.attr.state_pressed}, pressed);
            sld.addState(new int[]{android.R.attr.state_focused}, pressed);
            sld.addState(new int[]{}, normal);

            btn.setBackground(sld);
            btn.setBackgroundTintList(null);

            btn.setTextColor(isDarkMode ? 0xFFFFFFFF : accent);
            btn.setTypeface(null, Typeface.BOLD);
        }
    }

    public static void styleImageButton(ImageButton ib, boolean isDarkMode, int onSurface, int onSurfaceVariant, int accent, int surface, int divider) {
        if (ib == null) return;
        ib.setImageTintList(ColorStateList.valueOf(accent));

        int id = ib.getId();
        if (id == R.id.BTUpDir || id == R.id.BTSearch || id == R.id.BTCloseSearch || id == R.id.BTInfo) {
            return;
        }

        float cornerRadius = com.winlator.cmod.core.UnitUtils.dpToPx(8);
        int strokeWidth = (int) Math.max(1, com.winlator.cmod.core.UnitUtils.dpToPx(1.2f));

        android.graphics.drawable.StateListDrawable sld = new android.graphics.drawable.StateListDrawable();
        android.graphics.drawable.GradientDrawable normal = new android.graphics.drawable.GradientDrawable();
        int normalBg = androidx.core.graphics.ColorUtils.setAlphaComponent(accent, isDarkMode ? 35 : 25);
        int strokeColor = androidx.core.graphics.ColorUtils.setAlphaComponent(accent, isDarkMode ? 120 : 90);
        normal.setColor(normalBg);
        normal.setStroke(strokeWidth, strokeColor);
        normal.setCornerRadius(cornerRadius);

        android.graphics.drawable.GradientDrawable pressed = new android.graphics.drawable.GradientDrawable();
        int pressedBg = androidx.core.graphics.ColorUtils.setAlphaComponent(accent, isDarkMode ? 90 : 60);
        pressed.setColor(pressedBg);
        pressed.setStroke(strokeWidth, strokeColor);
        pressed.setCornerRadius(cornerRadius);

        sld.addState(new int[]{android.R.attr.state_pressed}, pressed);
        sld.addState(new int[]{android.R.attr.state_focused}, pressed);
        sld.addState(new int[]{}, normal);

        ib.setBackground(sld);
        ib.setBackgroundTintList(null);
    }

    private static int adjustColorBrightness(int color, float factor) {
        int a = android.graphics.Color.alpha(color);
        int r = Math.round(android.graphics.Color.red(color) * factor);
        int g = Math.round(android.graphics.Color.green(color) * factor);
        int b = Math.round(android.graphics.Color.blue(color) * factor);
        return android.graphics.Color.argb(a,
                Math.min(r, 255),
                Math.min(g, 255),
                Math.min(b, 255));
    }
}
