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
    public static final String PREF_LIGHT_THEME_INDEX = "light_theme_preset_index";
    public static final String PREF_CUSTOM_ACCENT = "custom_accent_color";
    public static final String PREF_CUSTOM_BG = "custom_background_color";

    // =========================================================================
    // CURATED DARK MODE PRESETS (Midnight Ocean is default at index 0)
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

    // =========================================================================
    // CURATED LIGHT MODE PRESETS (Ocean Breeze is default at index 0)
    // =========================================================================
    public static final List<ThemePreset> LIGHT_PRESETS = Arrays.asList(
        new ThemePreset("Ocean Breeze",          0xFFFAFAFA, 0xFFFFFFFF, 0xFFF0F0F0, 0xFF0288D1, 0xFF121212, 0xFF121212, 0xFF424242, 0xFFFFFFFF, 0xFFE0E0E0),
        new ThemePreset("Crimson Glow",          0xFFFAFAFA, 0xFFFFFFFF, 0xFFF0F0F0, 0xFFB4182D, 0xFF121212, 0xFF121212, 0xFF424242, 0xFFFFFFFF, 0xFFE0E0E0),
        new ThemePreset("Emerald Forest",        0xFFFAFAFA, 0xFFFFFFFF, 0xFFF0F0F0, 0xFF2E7D32, 0xFF121212, 0xFF121212, 0xFF424242, 0xFFFFFFFF, 0xFFE0E0E0),
        new ThemePreset("Classic (Light Slate)", 0xFFFAFAFA, 0xFFFFFFFF, 0xFFF0F0F0, 0xFF607D8B, 0xFF121212, 0xFF121212, 0xFF424242, 0xFFFFFFFF, 0xFFE0E0E0),
        new ThemePreset("Sunset Coral",          0xFFFAFAFA, 0xFFFFFFFF, 0xFFF0F0F0, 0xFFE05364, 0xFF121212, 0xFF121212, 0xFF424242, 0xFFFFFFFF, 0xFFE0E0E0),
        new ThemePreset("Royal Amethyst",        0xFFFAFAFA, 0xFFFFFFFF, 0xFFF0F0F0, 0xFF6A1B9A, 0xFF121212, 0xFF121212, 0xFF424242, 0xFFFFFFFF, 0xFFE0E0E0),
        new ThemePreset("Monochrome Minimal",    0xFFFAFAFA, 0xFFFFFFFF, 0xFFF0F0F0, 0xFF212121, 0xFF121212, 0xFF121212, 0xFF424242, 0xFFFFFFFF, 0xFFE0E0E0),
        new ThemePreset("Custom Palette...",     0xFFFAFAFA, 0xFFFFFFFF, 0xFFF0F0F0, 0xFF0288D1, 0xFF121212, 0xFF121212, 0xFF424242, 0xFFFFFFFF, 0xFFE0E0E0)
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

    @StyleRes
    private static final int[] LIGHT_STYLES = {
        R.style.ThemePreset_OceanIce_Light,
        R.style.ThemePreset_CrimsonDusk_Light,
        R.style.ThemePreset_EmeraldDepth_Light,
        R.style.ThemePreset_DarkGlass_Light,
        R.style.ThemePreset_DuskCoral_Light,
        R.style.ThemePreset_VioletCream_Light,
        R.style.ThemePreset_ObsidianOLED_Light,
        R.style.ThemePreset_Custom_Light
    };

    private ThemeManager() {}

    public static boolean isDarkMode(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_DARK_MODE, true);
    }

    public static List<ThemePreset> getPresets(Context context) {
        return isDarkMode(context) ? Collections.unmodifiableList(DARK_PRESETS) : Collections.unmodifiableList(LIGHT_PRESETS);
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
        boolean darkMode = isDarkMode(context);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String key = darkMode ? PREF_DARK_THEME_INDEX : PREF_LIGHT_THEME_INDEX;
        int max = (darkMode ? DARK_PRESETS.size() : LIGHT_PRESETS.size()) - 1;
        int index = prefs.getInt(key, 0);
        if (index < 0 || index > max) index = 0;
        return index;
    }

    public static void setSelectedPresetIndex(Context context, int index) {
        boolean darkMode = isDarkMode(context);
        int max = (darkMode ? DARK_PRESETS.size() : LIGHT_PRESETS.size()) - 1;
        if (index < 0 || index > max) index = 0;
        String key = darkMode ? PREF_DARK_THEME_INDEX : PREF_LIGHT_THEME_INDEX;
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putInt(key, index)
                .apply();
    }

    public static boolean isCustomPresetSelected(Context context) {
        boolean darkMode = isDarkMode(context);
        int customIndex = (darkMode ? DARK_PRESETS.size() : LIGHT_PRESETS.size()) - 1;
        return getSelectedPresetIndex(context) == customIndex;
    }

    public static void selectCustomPreset(Context context) {
        boolean darkMode = isDarkMode(context);
        int customIndex = (darkMode ? DARK_PRESETS.size() : LIGHT_PRESETS.size()) - 1;
        setSelectedPresetIndex(context, customIndex);
    }

    public static int getCustomAccentColor(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getInt(PREF_CUSTOM_ACCENT, isDarkMode(context) ? 0xFF5483B3 : 0xFF0288D1);
    }

    public static int getCustomBackgroundColor(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getInt(PREF_CUSTOM_BG, isDarkMode(context) ? 0xFF021024 : 0xFFFAFAFA);
    }

    public static void setCustomColors(Context context, int accent, int background) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putInt(PREF_CUSTOM_ACCENT, accent)
                .putInt(PREF_CUSTOM_BG, background)
                .apply();
    }

    public static ThemePreset getSelectedPreset(Context context) {
        boolean darkMode = isDarkMode(context);
        if (isCustomPresetSelected(context)) {
            int accent = getCustomAccentColor(context);
            int bg = getCustomBackgroundColor(context);
            int surface = darkMode ? ThemePreset.lerp(bg, 0xFFFFFFFF, 0.08f) : 0xFFFFFFFF;
            int surfaceVariant = darkMode ? ThemePreset.lerp(bg, 0xFFFFFFFF, 0.14f) : 0xFFF0F0F0;
            return new ThemePreset("Custom", bg, surface, surfaceVariant, accent);
        }
        int index = getSelectedPresetIndex(context);
        return darkMode ? DARK_PRESETS.get(index) : LIGHT_PRESETS.get(index);
    }

    public static void applyTheme(Context context) {
        Resources.Theme theme = context.getTheme();
        if (theme == null) return;
        boolean darkMode = isDarkMode(context);
        int index = getSelectedPresetIndex(context);
        if (darkMode) {
            theme.applyStyle(DARK_STYLES[index], true);
        } else {
            theme.applyStyle(LIGHT_STYLES[index], true);
        }
    }

    public static int getAccentColor(Context context) {
        if (isCustomPresetSelected(context)) {
            return getCustomAccentColor(context);
        }
        return getSelectedPreset(context).primary;
    }

    public static int getPrimaryColor(Context context) {
        if (isCustomPresetSelected(context)) {
            return isDarkMode(context) ? getSelectedPreset(context).surfaceVariant : getCustomAccentColor(context);
        }
        return isDarkMode(context) ? getSelectedPreset(context).surfaceVariant : getSelectedPreset(context).primary;
    }

    public static int getBackgroundColor(Context context) {
        if (isCustomPresetSelected(context)) {
            return getCustomBackgroundColor(context);
        }
        return isDarkMode(context) ? getSelectedPreset(context).background : 0xFFFAFAFA;
    }

    public static int getSurfaceColor(Context context) {
        if (isCustomPresetSelected(context)) {
            return getSelectedPreset(context).surface;
        }
        return isDarkMode(context) ? getSelectedPreset(context).surface : 0xFFFFFFFF;
    }

    public static int getOnSurfaceTextColor(Context context) {
        return isDarkMode(context) ? getSelectedPreset(context).onSurface : 0xFF121212;
    }

    public static int getDividerColor(Context context) {
        return isDarkMode(context) ? getSelectedPreset(context).divider : 0xFFE0E0E0;
    }

    public static void applyThemeToView(View view, Context context) {
        if (view == null || context == null) return;
        boolean isDarkMode = isDarkMode(context);
        ThemePreset preset = getSelectedPreset(context);
        int onSurface = preset.onSurface;
        int onSurfaceVariant = preset.onSurfaceVariant;
        int accent = preset.primary;
        int surface = preset.surface;
        int divider = preset.divider;

        applyThemeRecursively(view, isDarkMode, onSurface, onSurfaceVariant, accent, surface, divider);
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
        } else if (view instanceof TabLayout) {
            TabLayout tabLayout = (TabLayout) view;
            tabLayout.setBackgroundResource(isDarkMode ? R.drawable.tab_layout_background_dark : R.drawable.tab_layout_background);
            tabLayout.setSelectedTabIndicatorColor(accent);
            tabLayout.setTabTextColors(onSurfaceVariant, accent);
        } else if (view instanceof SeekBar) {
            SeekBar sb = (SeekBar) view;
            sb.setThumbTintList(ColorStateList.valueOf(accent));
            sb.setProgressTintList(ColorStateList.valueOf(accent));
        } else if (view instanceof ImageButton) {
            ImageButton ib = (ImageButton) view;
            ib.setImageTintList(ColorStateList.valueOf(accent));
        } else if (view instanceof ImageView) {
            ImageView iv = (ImageView) view;
            int id = iv.getId();
            if (id == R.id.BTHelpDXWrapper || id == R.id.BTHelpGtaOptimization) {
                iv.setImageTintList(ColorStateList.valueOf(onSurfaceVariant));
            }
        } else if (view instanceof TextView) {
            TextView tv = (TextView) view;
            int id = tv.getId();
            if (id == R.id.TVGraphicsDriverVersion || id == R.id.TVSharpnessLevel || id == R.id.TVSharpnessDenoise) {
                tv.setTextColor(accent);
            } else {
                CharSequence text = tv.getText();
                if (text != null && isFieldSetHeader(text.toString())) {
                    tv.setTextColor(accent);
                    tv.setTypeface(null, Typeface.BOLD);
                } else {
                    tv.setTextColor(onSurface);
                }
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyThemeRecursively(group.getChildAt(i), isDarkMode, onSurface, onSurfaceVariant, accent, surface, divider);
            }
        }
    }

    private static boolean isFieldSetHeader(String text) {
        if (text == null) return false;
        String t = text.trim();
        return t.equalsIgnoreCase("DirectX") ||
               t.equalsIgnoreCase("General") ||
               t.equalsIgnoreCase("Box64") ||
               t.equalsIgnoreCase("FEX-Core") ||
               t.equalsIgnoreCase("FEXCore") ||
               t.equalsIgnoreCase("Input Controls") ||
               t.equalsIgnoreCase("Unified Control System") ||
               t.equalsIgnoreCase("System") ||
               t.equalsIgnoreCase("vkBasalt") ||
               t.equalsIgnoreCase("Desktop") ||
               t.equalsIgnoreCase("Win Components") ||
               t.equalsIgnoreCase("Advanced") ||
               t.equalsIgnoreCase("Environment Variables") ||
               t.equalsIgnoreCase("Audio");
    }
}
