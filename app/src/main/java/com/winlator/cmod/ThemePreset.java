package com.winlator.cmod;

import android.graphics.Color;

/**
 * ThemePreset with WCAG 2.x contrast enforcement.
 *
 * CONTRAST IS ENFORCED MATHEMATICALLY:
 * Every foreground and divider color is validated against its background surface using
 * linearized sRGB relative luminance and the WCAG 2.x contrast formula.
 * Failing colors are nudged channel-by-channel until they meet standard accessibility targets:
 * - Text: >= 4.5:1 (WCAG AA normal text)
 * - Interactive components: >= 3.0:1 (WCAG AA non-text UI)
 * - Dividers: >= 1.6:1 (Visible structural separation)
 */
public final class ThemePreset {
    public final String name;

    // Core palette colors
    public final int background;
    public final int surface;
    public final int surfaceVariant;
    public final int primary;

    // Enforced readable foreground colors
    public final int onBackground;
    public final int onSurface;
    public final int onSurfaceVariant;
    public final int onPrimary;
    public final int divider;

    // Derived contrast roles
    public final int accentDim;
    public final int accentOnSurface;
    public final int surfaceContainer;
    public final int surfaceContainerHigh;
    public final int surfaceContainerHighest;

    private static final int DEFAULT_ON_SURFACE = 0xFFE0E0E0;
    private static final int DEFAULT_ON_SURFACE_VARIANT = 0xFFAAAAAA;
    private static final int DEFAULT_ON_BACKGROUND = 0xFFFFFFFF;
    private static final int DEFAULT_ON_PRIMARY = 0xFFFFFFFF;
    private static final int DEFAULT_DIVIDER = 0xFF404040;

    private static final double TARGET_TEXT = 4.5d;
    private static final double TARGET_COMPONENT = 3.0d;
    private static final double TARGET_DIVIDER = 1.6d;

    public ThemePreset(String name, int background, int surface, int surfaceVariant, int primary) {
        this(name, background, surface, surfaceVariant, primary,
             DEFAULT_ON_BACKGROUND, DEFAULT_ON_SURFACE, DEFAULT_ON_SURFACE_VARIANT,
             DEFAULT_ON_PRIMARY, DEFAULT_DIVIDER);
    }

    public ThemePreset(String name, int background, int surface, int surfaceVariant, int primary,
                       int onBackground, int onSurface, int onSurfaceVariant, int onPrimary, int divider) {
        this.name = name;
        this.background = background;
        this.surface = surface;
        this.surfaceVariant = surfaceVariant;
        this.primary = primary;

        this.onBackground = ensureReadable(onBackground, background, TARGET_TEXT);
        this.onSurface = ensureReadable(onSurface, surface, TARGET_TEXT);
        this.onSurfaceVariant = ensureReadable(onSurfaceVariant, surfaceVariant, TARGET_TEXT);
        this.onPrimary = ensureReadable(onPrimary, primary, TARGET_TEXT);
        this.divider = ensureReadable(divider, surface, TARGET_DIVIDER);

        this.accentDim = lerp(primary, 0xFF000000, 0.55f);
        this.accentOnSurface = ensureReadable(primary, surface, TARGET_COMPONENT);
        this.surfaceContainer = lerp(surface, this.onSurface, 0.05f);
        this.surfaceContainerHigh = lerp(surface, this.onSurface, 0.09f);
        this.surfaceContainerHighest = lerp(surface, this.onSurface, 0.14f);
    }

    public static int lerp(int colorA, int colorB, float t) {
        int a = Math.round(Color.alpha(colorA) + (Color.alpha(colorB) - Color.alpha(colorA)) * t);
        int r = Math.round(Color.red(colorA) + (Color.red(colorB) - Color.red(colorA)) * t);
        int g = Math.round(Color.green(colorA) + (Color.green(colorB) - Color.green(colorA)) * t);
        int b = Math.round(Color.blue(colorA) + (Color.blue(colorB) - Color.blue(colorA)) * t);
        return Color.argb(a, r, g, b);
    }

    private static double linearize(int channel8bit) {
        double c = channel8bit / 255.0d;
        return c <= 0.03928d ? c / 12.92d : Math.pow((c + 0.055d) / 1.055d, 2.4d);
    }

    public static double relativeLuminance(int color) {
        return 0.2126d * linearize(Color.red(color))
             + 0.7152d * linearize(Color.green(color))
             + 0.0722d * linearize(Color.blue(color));
    }

    public static double contrastRatio(int colorA, int colorB) {
        double lumA = relativeLuminance(colorA);
        double lumB = relativeLuminance(colorB);
        double lighter = Math.max(lumA, lumB);
        double darker = Math.min(lumA, lumB);
        return (lighter + 0.05d) / (darker + 0.05d);
    }

    public static int bestOnColor(int backgroundColor) {
        return contrastRatio(0xFF000000, backgroundColor) >= contrastRatio(0xFFFFFFFF, backgroundColor)
                ? 0xFF000000
                : 0xFFFFFFFF;
    }

    public static int ensureReadable(int proposed, int onTopOf, double target) {
        if (contrastRatio(proposed, onTopOf) >= target) return proposed;

        int pole = bestOnColor(onTopOf);
        for (int step = 1; step <= 50; step++) {
            int candidate = lerp(proposed, pole, step / 50.0f);
            if (contrastRatio(candidate, onTopOf) >= target) return candidate;
        }
        return pole;
    }

    public static int onAccentFor(int accentColor) {
        return bestOnColor(accentColor);
    }
}
