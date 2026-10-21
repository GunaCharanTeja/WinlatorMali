package com.winlator.cmod.renderer;

import com.winlator.cmod.core.KeyValueSet;

import java.util.Locale;

/**
 * Settings for the native EGL post-processing chain (see cpp/winlator/renderer/postfx.cpp).
 * Serialized as a KeyValueSet string so it can be stored in containers, shortcuts and profiles.
 */
public class PostProcessing {
    public static final String EXTRA_KEY = "postProcessing";

    public static final int UPSCALER_LINEAR = 0;
    public static final int UPSCALER_NEAREST = 1;
    public static final int UPSCALER_LANCZOS = 2;
    public static final int UPSCALER_SGSR = 3;
    public static final int UPSCALER_FSR = 4;

    public static final int SHARPEN_OFF = -1;
    public static final int SHARPEN_RCAS = 0;
    public static final int SHARPEN_RCAS_VIBRANT = 1;
    public static final int SHARPEN_CAS_LITE = 2;
    public static final int SHARPEN_ADAPTIVE = 3;

    /** Upscaler names, indexed by the UPSCALER_* constants. */
    public static final String[] UPSCALERS = {"Off (Default)", "Nearest (Pixel Perfect)", "Lanczos2", "SGSR", "FSR"};

    /** Sharpen mode names, indexed by SHARPEN_* + 1. */
    public static final String[] SHARPEN_MODES = {"Off", "RCAS", "RCAS + Vibrance", "CAS Lite", "Adaptive Sharpen"};

    /** Color presets shown in the UI: display name and native effect id. */
    public static final String[] PRESET_NAMES = {
        "Off",
        "Vivid", "Cinematic", "Game Clarity", "Competitive", "Filmic", "Arcade", "Color Boost",
        "Retro CRT", "Anime Edge", "Upscale Sharp", "Pixel Clean",
        "Natural", "Vibrance", "Curves", "Technicolor", "Levels", "DLS"
    };
    public static final int[] PRESET_IDS = {
        0,
        12, 11, 10, 13, 15, 16, 21,
        17, 20, 18, 19,
        4, 5, 6, 8, 9, 1
    };

    public int upscaler = UPSCALER_LINEAR;
    public float upscalerSharpness = 0.5f;
    public int preset = 0;
    public float presetStrength = 0.65f;
    public float brightness = 0;
    public float contrast = 0;
    public float gamma = 1.0f;
    public boolean hdr = false;
    public boolean fxaa = false;
    public boolean toon = false;
    public boolean crt = false;
    public boolean ntsc = false;
    public int sharpenMode = SHARPEN_OFF;
    public float sharpenLevel = 3.0f;
    /** Scale a game window smaller than the X screen (e.g. 800x600 on 1280x720) straight to the display. */
    public boolean fitWindow = false;
    /** Show a generated frame between every two game frames (ZeroFG on Vulkan). */
    public boolean frameGen = false;
    /** The output frame rate the multiplier stays at or under (0: always 2x). */
    public int frameGenTarget = 0;
    /** The game's frame rate cap (0 = none), saved with the rest so it holds on the next launch. */
    public int fpsLimit = 0;

    public PostProcessing() {}

    public PostProcessing(String config) {
        if (config == null || config.isEmpty()) return;
        KeyValueSet kv = new KeyValueSet(config);
        upscaler = clamp(Math.round(kv.getFloat("upscaler", upscaler)), 0, UPSCALERS.length - 1);
        upscalerSharpness = clamp(kv.getFloat("upscalerSharpness", upscalerSharpness), 0, 1);
        preset = Math.round(kv.getFloat("preset", preset));
        presetStrength = clamp(kv.getFloat("presetStrength", presetStrength), 0, 1);
        brightness = clamp(kv.getFloat("brightness", brightness), -100, 100);
        contrast = clamp(kv.getFloat("contrast", contrast), -100, 100);
        gamma = clamp(kv.getFloat("gamma", gamma), 0.1f, 5.0f);
        hdr = flag(kv, "hdr", hdr);
        fxaa = flag(kv, "fxaa", fxaa);
        toon = flag(kv, "toon", toon);
        crt = flag(kv, "crt", crt);
        ntsc = flag(kv, "ntsc", ntsc);
        sharpenMode = clamp(Math.round(kv.getFloat("sharpenMode", sharpenMode)), SHARPEN_OFF, SHARPEN_ADAPTIVE);
        sharpenLevel = clamp(kv.getFloat("sharpenLevel", sharpenLevel), 1, 5);
        fitWindow = flag(kv, "fitWindow", fitWindow);
        frameGen = flag(kv, "frameGen", frameGen);
        frameGenTarget = Math.round(kv.getFloat("fgTarget", frameGenTarget));
        fpsLimit = Math.max(0, Math.round(kv.getFloat("fpsLimit", fpsLimit)));

        // Profiles saved by the old Screen Effects dialog
        crt = flag(kv, "crt_shader", crt);
        toon = flag(kv, "toon_shader", toon);
        ntsc = flag(kv, "ntsc_effect", ntsc);
    }

    public boolean isDefault() {
        return toString().equals(new PostProcessing().toString());
    }

    /** Color adjustment or any of the effects is in use. */
    public boolean hasAdvancedOptions() {
        return brightness != 0 || contrast != 0 || gamma != 1.0f || hdr || fxaa || toon || crt || ntsc;
    }

    public int getPresetIndex() {
        for (int i = 0; i < PRESET_IDS.length; i++) if (PRESET_IDS[i] == preset) return i;
        return 0;
    }

    public void setPresetIndex(int index) {
        preset = PRESET_IDS[clamp(index, 0, PRESET_IDS.length - 1)];
    }

    @Override
    public String toString() {
        // Values are passed as strings: KeyValueSet.put(String, int) would pick the float overload and write "1.0".
        KeyValueSet kv = new KeyValueSet();
        kv.put("upscaler", String.valueOf(upscaler));
        kv.put("upscalerSharpness", format(upscalerSharpness));
        kv.put("preset", String.valueOf(preset));
        kv.put("presetStrength", format(presetStrength));
        kv.put("brightness", format(brightness));
        kv.put("contrast", format(contrast));
        kv.put("gamma", format(gamma));
        kv.put("hdr", hdr ? "1" : "0");
        kv.put("fxaa", fxaa ? "1" : "0");
        kv.put("toon", toon ? "1" : "0");
        kv.put("crt", crt ? "1" : "0");
        kv.put("ntsc", ntsc ? "1" : "0");
        kv.put("sharpenMode", String.valueOf(sharpenMode));
        kv.put("sharpenLevel", format(sharpenLevel));
        kv.put("fitWindow", fitWindow ? "1" : "0");
        kv.put("frameGen", frameGen ? "1" : "0");
        kv.put("fgTarget", String.valueOf(frameGenTarget));
        kv.put("fpsLimit", String.valueOf(fpsLimit));
        return kv.toString();
    }

    // Accepts 1, 1.0 and true; older builds saved flags as 1.0.
    private static boolean flag(KeyValueSet kv, String key, boolean fallback) {
        String value = kv.get(key);
        if (value.isEmpty()) return fallback;
        return value.equalsIgnoreCase("true") || kv.getFloat(key, 0) != 0;
    }

    private static String format(float value) {
        return String.format(Locale.US, "%.3f", value);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
