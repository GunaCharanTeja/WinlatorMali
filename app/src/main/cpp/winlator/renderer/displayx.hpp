#pragma once

#include <string>
#include <algorithm>
#include <thread>
#include <functional>
#include <queue>
#include <cmath>
#include <dlfcn.h>
#include <unordered_set>
#include <condition_variable>
#include <android/choreographer.h>
#include <android/performance_hint.h>

#include "renderer_jni.hpp"
#include "view_transformation.hpp"
#include "window.hpp"
#include "cursor.hpp"

#include "blit_converter.h"

class DisplayX {
    private:
        enum class State {
            NONE,
            PAUSE,
            RESUME,
            CREATE_SURFACE,
            DESTROY_SURFACE,
            CHANGE_SURFACE
        };
        
        struct DisplayXLock {
           std::condition_variable cv;
           std::mutex mutex;
           
           std::unique_lock<std::mutex> lock() {
               return std::unique_lock<std::mutex>(mutex);
           }
           
           template<typename Predicate>
           void wait(std::unique_lock<std::mutex>& lock, Predicate pred) {
               cv.wait(lock, pred);
           }
           
           void notify() {
               cv.notify_all();
           }
        };
        
        struct PresentRequest {
            Drawable *drawable;
            int sync_fence;
            uint64_t presentId;
            uint8_t swapchainId;
            int clientFd;
            Window *window;
        };
        
        class PresentQueue {
            private:
                std::queue<std::unique_ptr<PresentRequest>> mQueue;

            public:
                void push(std::unique_ptr<PresentRequest> request) {
                    if (!request)
                        return;

                    mQueue.push(std::move(request));
                }

                std::unique_ptr<PresentRequest> pop() {
                    if (mQueue.empty())
                        return nullptr;

                    auto val = std::move(mQueue.front());
                    mQueue.pop();
                    return val;
                }

                bool empty() const {
                    return mQueue.empty();
                }
        };
        
        struct DisplayXSwapchain {
            uint8_t id;
            Window *window;
            std::vector<std::unique_ptr<Drawable>> images;
        };
        
        struct OnCompleteContext {
            std::vector<std::unique_ptr<PresentRequest>> requests;
        };
        
        struct ConvertedBufferSlot {
            AHardwareBuffer* buffer = nullptr;
            uint32_t width = 0;
            uint32_t height = 0;
            bool inUse = false;
            int releaseFenceFd = -1;
            
            ~ConvertedBufferSlot() {
                if (releaseFenceFd >= 0) close(releaseFenceFd);
                if (buffer) AHardwareBuffer_release(buffer);
            }
        };
        
        std::unique_ptr<BlitConverter> blitConverter;
        std::vector<std::unique_ptr<ConvertedBufferSlot>> convertedSlots;
        std::mutex convertedSlotsMutex;
        
        ConvertedBufferSlot* acquireConvertedSlot(uint32_t width, uint32_t height);
        
        JNIEnv *env;
        int surfaceWidth;
        int surfaceHeight;
        AChoreographer *choreographer;
        ViewTransformation viewTransformation;
        ANativeWindow *native_window;
        
        APerformanceHintManager *performanceHintManager = nullptr;
        APerformanceHintSession *performanceHintSession = nullptr;
        
        DisplayXLock eventLock;
        DisplayXLock presentLock;
        
        ASurfaceTransaction *windowTransaction;
        ASurfaceTransaction *cursorTransaction;
        PresentQueue presentRequests;
        std::queue<std::function<void()>> eventQueue;
        
        std::thread eventThread;
        std::thread networkThread;
        std::thread presentThread;
        
        State state = State::NONE;
        std::atomic_bool paused{false};
        std::atomic_bool stopped{false};
        std::atomic_bool hasSurface{false};
        std::atomic_bool cursorUpdate{false};
        std::atomic_bool surfaceChanged{false};
        std::atomic_bool perfMode{true};
        std::atomic_bool presentRR{true};
        
        bool requestUpdate = false;
        
        bool fullscreen = false;
        int eventsPending = 0;
        int64_t previousReportedWorkTime = 0;
        
        void eventThreadLoop();
        void networkThreadLoop();
        void presentThreadLoop();
        static void onFrameCallback64(int64_t frameTimeNanos, void *data);
        static void onCommitCallback(void *context, ASurfaceTransactionStats *stats);
        static void onCompleteCallback(void *context, ASurfaceTransactionStats *stats);
        int64_t getCurrentTimeNanos();
        bool isPerformanceHintAPIAvailable();
        
        void createRootWindowControl();
        void createRootCursorControl();
        void resizeRootWindow();
        void destroyRootWindowControl();
        void destroyRootCursorControl();
        void restoreControlState();
        
    public:
        WindowManager *windowManager;
        CursorManager *cursorManager;
        JNIXServer *xServer;
        JNICache *cache;
        
        bool cursorVisible = false;
        
        void start();
        void createSurface(ANativeWindow *window);
        void changeSurface(int width, int height);
        void destroySurface();
        void stop();
        void pause();
        void resume();
        
        void queueEvent(std::function<void()> func);
        void requestWindowUpdate(Window *window);
        void requestCursorUpdate();
        void updateCursorPosition();
        
        void createWindowControl(Window *window);
        void destroyWindowControl(Window *window);
        void mapWindow(Window *window);
        void unmapWindow(Window *window);
        void changeGeometry(Window *window, bool resized);
        void changeZOrder(Window *window, Window *sibling, int stackMode);
        void reparentWindow(Window *window, Window *parent);
        void updateCursor(Cursor *cursor);
        void showCursor();
        void toggleFullscreen();
        void setPerformanceMode(bool perfMode);
        void setPresentRR(bool presentRR);
};