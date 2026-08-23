#pragma once

#include <unordered_map>

#include "drawable.hpp"
#include "renderer_jni.hpp"

struct Cursor {
    int id;
    std::unique_ptr<struct Drawable> image;
    int hotspotX;
    int hotspotY;
    bool visible;
    jobject cursorObj;
};

struct Pointer {
    int posX;
    int posY;
};

class CursorManager {
    private:
        std::unordered_map<int, std::unique_ptr<struct Cursor>> cursors;
        std::unique_ptr<struct Cursor> rootCursor;
        
    public: 
        Pointer pointer{0, 0};
        ASurfaceControl *control;
        
        void setRootCursor(std::unique_ptr<struct Cursor> cursor);
        Cursor *getRootCursor();
        void addCursor(int id, std::unique_ptr<struct Cursor> cursor);
        void removeCursor(JNIEnv *env, Cursor *cursor);
        Cursor *getCursor(int id);
};