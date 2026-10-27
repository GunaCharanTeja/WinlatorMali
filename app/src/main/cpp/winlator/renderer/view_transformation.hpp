#pragma once

#include <cmath>
#include <algorithm>

class ViewTransformation {
    public:
        // Scale modes (Stretch is the renderer's fullscreen flag) and alignments, as in Container.
        static constexpr int MODE_FIT = 0;
        static constexpr int MODE_FILL = 2;
        static constexpr int MODE_INTEGER = 3;
        static constexpr int ALIGN_CENTER = 0;
        static constexpr int ALIGN_TOP = 1;
        static constexpr int ALIGN_BOTTOM = 2;

        int viewOffsetX;
        int viewOffsetY;
        int viewWidth;
        int viewHeight;
        float aspect;
        float sceneScaleX;
        float sceneScaleY;
        float sceneOffsetX;
        float sceneOffsetY;

        // yUp: offsets for GL (origin bottom-left), where the top half is the upper region.
        void update(int outerWidth, int outerHeight, int innerWidth, int innerHeight, int mode = MODE_FIT, int alignment = ALIGN_CENTER, bool yUp = true);
};
