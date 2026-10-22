package com.winlator.cmod.widget;

import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;

import androidx.core.graphics.ColorUtils;

/**
 * The glass look after Madeira's touch controls (iOS Liquid Glass), shared by the WinMali Default controls and
 * the Glass HUD: a soft shadow, a dark tinted base, lighter toward the rim like a lens, a highlight near the top
 * left, a faint glow inside the rim, and a rim that catches the light (bright at the upper left, softer at the
 * lower right). Callers draw it into cached bitmaps, never per frame.
 */
public final class GlassPainter {
    private static Typeface typeface;

    private GlassPainter() {}

    /** Inter SemiBold, the closest free match for Madeira's San Francisco semibold (SIL OFL, assets/fonts). */
    public static Typeface typeface(android.content.res.AssetManager assets) {
        if (typeface == null) {
            try {
                typeface = new Typeface.Builder(assets, "fonts/Inter.ttf").setFontVariationSettings("'wght' 600").build();
            } catch (Exception e) {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL);
            }
        }
        return typeface;
    }

    /** Paints the glass shape into rect. alpha is 0..255; tint 0 means the neutral dark glass. */
    public static void paint(Canvas c, RectF rect, float radius, int tint, float alpha, boolean pressed, boolean selected) {
        float w = rect.width(), h = rect.height();
        float cx = rect.centerX(), cy = rect.centerY();
        float min = Math.min(w, h);
        float rim = Math.max(1.5f, min * 0.022f);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float a = alpha / 255f;
        Path shapePath = new Path();
        shapePath.addRoundRect(rect, radius, radius, Path.Direction.CW);

        // Soft shadow under the glass.
        p.setColor(Color.argb((int) (255 * a * 0.28f), 0, 0, 0));
        p.setMaskFilter(new BlurMaskFilter(Math.max(1f, min * 0.07f), BlurMaskFilter.Blur.NORMAL));
        c.save();
        c.translate(0, min * 0.035f);
        c.drawRoundRect(rect, radius, radius, p);
        c.restore();
        p.setMaskFilter(null);

        // The glass: its tint, then lighter toward the rim (a lens), a little more when pressed.
        c.save();
        c.clipPath(shapePath);
        // A dark base keeps the glass readable over bright scenes, the lens light over dark ones.
        if (tint != 0) {
            p.setColor(ColorUtils.setAlphaComponent(tint, (int) (255 * a * (pressed ? 0.75f : 0.60f))));
            c.drawRect(rect, p);
        } else {
            p.setColor(Color.argb((int) (255 * a * 0.36f), 20, 22, 28));
            c.drawRect(rect, p);
        }
        float lens = pressed ? 1.45f : 1.0f;
        p.setShader(new RadialGradient(cx, cy, Math.max(w, h) * 0.62f,
            new int[]{Color.argb((int) (255 * a * 0.07f * lens), 255, 255, 255), Color.argb((int) (255 * a * 0.13f * lens), 255, 255, 255),
                      Color.argb((int) (255 * a * 0.32f * lens), 255, 255, 255)},
            new float[]{0f, 0.72f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(rect, p);
        // The highlight near the top, toward the left.
        p.setShader(new RadialGradient(cx - w * 0.14f, rect.top + h * 0.16f, min * 0.55f,
            new int[]{Color.argb((int) (255 * a * 0.26f), 255, 255, 255), Color.argb(0, 255, 255, 255)},
            null, Shader.TileMode.CLAMP));
        c.drawRect(rect, p);
        p.setShader(null);
        // A faint glow just inside the rim.
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(rim * 3f);
        p.setColor(Color.argb((int) (255 * a * 0.16f), 255, 255, 255));
        p.setMaskFilter(new BlurMaskFilter(rim * 2f, BlurMaskFilter.Blur.NORMAL));
        c.drawRoundRect(rect, radius, radius, p);
        p.setMaskFilter(null);
        c.restore();

        // The rim, catching the light: bright at the upper left, softer at the lower right, dim at the sides.
        float[] rimAlpha = {0.26f, 0.60f, 0.30f, 0.40f, 1.0f, 0.70f, 0.26f};  // right, lower right, bottom, left, upper left, top, right
        float[] rimStops = {0f, 0.125f, 0.25f, 0.5f, 0.625f, 0.75f, 1f};
        int[] rimColors = new int[rimAlpha.length];
        for (int i = 0; i < rimAlpha.length; i++)
            rimColors[i] = Color.argb((int) (255 * a * Math.min(1f, rimAlpha[i] * (selected ? 1.6f : (pressed ? 1.2f : 1f)))), 255, 255, 255);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(selected ? rim * 1.8f : rim);
        p.setShader(new SweepGradient(cx, cy, rimColors, rimStops));
        RectF rimRect = new RectF(rect.left + rim * 0.5f, rect.top + rim * 0.5f, rect.right - rim * 0.5f, rect.bottom - rim * 0.5f);
        float rimRadius = Math.max(0, radius - rim * 0.5f);
        c.drawRoundRect(rimRect, rimRadius, rimRadius, p);
        p.setShader(null);
    }
}
