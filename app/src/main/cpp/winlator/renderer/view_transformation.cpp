#include "view_transformation.hpp"

// Same math as ViewTransformation.java (touch mapping), from Bannerlator: the mode picks the scale, the alignment the
// region of the surface the game occupies (the whole surface, or its top or bottom half to leave room for on-screen
// controls). Fit and Center give the historical letterbox.
void ViewTransformation::update(int outerWidth, int outerHeight, int innerWidth, int innerHeight, int mode, int alignment, bool yUp) {
    int halfHeight = outerHeight / 2;
    int regionHeight = alignment == ALIGN_CENTER ? outerHeight : (alignment == ALIGN_TOP ? halfHeight : outerHeight - halfHeight);
    bool upper = alignment == ALIGN_TOP;
    int regionOffsetY = 0;
    if (alignment != ALIGN_CENTER)
        regionOffsetY = (upper != yUp) ? 0 : outerHeight - regionHeight;

    float sx = (float)outerWidth / innerWidth;
    float sy = (float)regionHeight / innerHeight;
    if (mode == MODE_FILL)
        aspect = std::max(sx, sy);
    else if (mode == MODE_INTEGER)
        // Whole multiples only; when even 1x does not fit (720p in half of a 1080p panel) it fits instead of cropping.
        aspect = std::min(sx, sy) >= 1.0f ? std::floor(std::min(sx, sy)) : std::min(sx, sy);
    else
        aspect = std::min(sx, sy);

    viewWidth = (int)std::ceil(innerWidth * aspect);
    viewHeight = (int)std::ceil(innerHeight * aspect);
    viewOffsetX = (int)((outerWidth - innerWidth * aspect) * 0.5f);
    viewOffsetY = regionOffsetY + (int)((regionHeight - innerHeight * aspect) * 0.5f);
    sceneScaleX = (innerWidth * aspect) / outerWidth;
    sceneScaleY = (innerHeight * aspect) / outerHeight;
    sceneOffsetX = (innerWidth - innerWidth * sceneScaleX) * 0.5f;
    sceneOffsetY = alignment == ALIGN_CENTER ? (innerHeight - innerHeight * sceneScaleY) * 0.5f : viewOffsetY * (float)innerHeight / outerHeight;
}
