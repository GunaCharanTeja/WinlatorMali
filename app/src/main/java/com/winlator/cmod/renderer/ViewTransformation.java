package com.winlator.cmod.renderer;

public class ViewTransformation {
    // Fullscreen modes and screen alignments, stored on containers and shortcuts (extras "fullscreenMode", "screenAlignment").
    public static final int MODE_FIT = 0;
    public static final int MODE_STRETCH = 1;
    public static final int MODE_FILL = 2;
    public static final int MODE_INTEGER = 3;
    public static final int ALIGN_CENTER = 0;
    public static final int ALIGN_TOP = 1;
    public static final int ALIGN_BOTTOM = 2;

    public int viewOffsetX;
    public int viewOffsetY;
    public int viewWidth;
    public int viewHeight;
    public float aspect;
    public float sceneScaleX;
    public float sceneScaleY;
    public float sceneOffsetX;
    public float sceneOffsetY;

    public void update(int outerWidth, int outerHeight, int innerWidth, int innerHeight) {
        update(outerWidth, outerHeight, innerWidth, innerHeight, MODE_FIT, ALIGN_CENTER);
    }

    // Same math as the native renderer's view_transformation.cpp (from Bannerlator), with the origin top-left: the mode
    // picks the scale, the alignment the region the game occupies (whole surface, top half or bottom half).
    public void update(int outerWidth, int outerHeight, int innerWidth, int innerHeight, int mode, int alignment) {
        int halfHeight = outerHeight / 2;
        int regionHeight = alignment == ALIGN_CENTER ? outerHeight : (alignment == ALIGN_TOP ? halfHeight : outerHeight - halfHeight);
        int regionOffsetY = alignment == ALIGN_BOTTOM ? halfHeight : 0;

        float sx = (float)outerWidth / innerWidth;
        float sy = (float)regionHeight / innerHeight;
        if (mode == MODE_FILL)
            aspect = Math.max(sx, sy);
        else if (mode == MODE_INTEGER)
            // Whole multiples only; when even 1x does not fit (720p in half of a 1080p panel) it fits instead of cropping.
            aspect = Math.min(sx, sy) >= 1.0f ? (float)Math.floor(Math.min(sx, sy)) : Math.min(sx, sy);
        else
            aspect = Math.min(sx, sy);

        viewWidth = (int)Math.ceil(innerWidth * aspect);
        viewHeight = (int)Math.ceil(innerHeight * aspect);
        viewOffsetX = (int)((outerWidth - innerWidth * aspect) * 0.5f);
        viewOffsetY = regionOffsetY + (int)((regionHeight - innerHeight * aspect) * 0.5f);

        sceneScaleX = (innerWidth * aspect) / outerWidth;
        sceneScaleY = (innerHeight * aspect) / outerHeight;
        sceneOffsetX = (innerWidth - innerWidth * sceneScaleX) * 0.5f;
        sceneOffsetY = alignment == ALIGN_CENTER ? (innerHeight - innerHeight * sceneScaleY) * 0.5f : viewOffsetY * (float)innerHeight / outerHeight;
    }
}
