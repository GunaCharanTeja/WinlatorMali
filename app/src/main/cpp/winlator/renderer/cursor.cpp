#include "cursor.hpp"

void CursorManager::addCursor(int id, std::unique_ptr<struct Cursor> cursor) {
    auto lock = cursorLock.lock();
    this->cursors[id] = std::move(cursor);
}

void CursorManager::removeCursor(JNIEnv *env, Cursor *cursor) {
    env->DeleteGlobalRef(cursor->cursorObj);
    env->DeleteGlobalRef(cursor->image->drawableObj);

    {
        auto lock = cursorLock.lock();
        this->cursors.erase(cursor->id);
    }
}

Cursor* CursorManager::getCursor(int id) {
    auto lock = cursorLock.lock();
    auto it = cursors.find(id);
    if (it == cursors.end())
        return nullptr;

    return it->second.get();
}

void CursorManager::setRootCursor(std::unique_ptr<struct Cursor> cursor) {
    this->rootCursor = std::move(cursor);
}

Cursor* CursorManager::getRootCursor() {
    return this->rootCursor.get();
}
