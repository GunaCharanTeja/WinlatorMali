#include "cursor.hpp"

void CursorManager::addCursor(int id, std::unique_ptr<struct Cursor> cursor) {
    this->cursors[id] = std::move(cursor);
}

void CursorManager::removeCursor(JNIEnv *env, Cursor *cursor) {
    env->DeleteGlobalRef(cursor->cursorObj);
    env->DeleteGlobalRef(cursor->image->drawableObj);
    this->cursors.erase(cursor->id);
}

Cursor* CursorManager::getCursor(int id) {
    return this->cursors[id].get();
}

void CursorManager::setRootCursor(std::unique_ptr<struct Cursor> cursor) {
    this->rootCursor = std::move(cursor);
}

Cursor* CursorManager::getRootCursor() {
    return this->rootCursor.get();
}
