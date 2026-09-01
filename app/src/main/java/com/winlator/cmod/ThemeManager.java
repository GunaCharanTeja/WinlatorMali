package com.winlator.cmod;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import androidx.annotation.StyleRes;
import androidx.preference.PreferenceManager;

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
}
