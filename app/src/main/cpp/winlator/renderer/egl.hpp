#pragma once

#include <android/native_window.h>
#include <android/hardware_buffer.h>
#include <android/log.h>

#include <string>
#include <algorithm>
#include <atomic>
#include <thread>
#include <functional>
#include <mutex>
#include <queue>
#include <cmath>
#include <condition_variable>
#include <unordered_map>

#include "shader.hpp"
#include "renderer_jni.hpp"
#include "window.hpp"
#include "view_transformation.hpp"
#include "xform.hpp"
#include "postfx.hpp"
#include "frame_gen.hpp"

class EGLRenderer {
    private:
        struct RenderableWindow {
            int rootX;
            int rootY;
            Window *window;
        };

        enum class State {
            NONE,
            PAUSE,
            RESUME,
            CREATE_SURFACE,
            DESTROY_SURFACE,
            CHANGE_SURFACE
        };

        struct RenderLock {
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

        EGLDisplay display = EGL_NO_DISPLAY;
        JNIEnv *env;
        EGLConfig config;
        EGLSurface surface = EGL_NO_SURFACE;
        EGLContext context = EGL_NO_CONTEXT;
        int contextVersion = 0;
        DrawableShader *drawableShader = nullptr;
        ANativeWindow *window = nullptr;
        std::vector<std::unique_ptr<struct RenderableWindow>> renderableWindows;
        std::thread renderingThread;
        ViewTransformation viewTransformation;
        PostFX postFX;
        int surfaceWidth = 0;
        int surfaceHeight = 0;
        bool fullscreen = false;
        float tmpXForm1[6] = {1, 0, 0, 1, 0, 0};
        float tmpXForm2[6] = {1, 0, 0, 1, 0, 0};

        // Game window fitted to the X screen: X screen point = point * fitScale + fitOffset.
        std::mutex fitLock;
        bool fitActive = false;
        float fitScale = 1.0f;
        float fitOffsetX = 0.0f;
        float fitOffsetY = 0.0f;

        RenderLock renderLock;
        State state = State::NONE;
        std::queue<std::function<void()>> eventQueue;

        std::atomic_bool stopped{false};
        std::atomic_bool requestUpdate{false};
        // Something in the scene changed since the last frame (anything but a cursor move); the processed picture of the
        // last frame and where it came from, to present again for a cursor move.
        std::atomic_bool sceneDirty{true};
        bool lastSceneValid = false;
        int lastSceneX = 0, lastSceneY = 0;

        // Frame generation: a frame halfway between the last two real ones is shown before the newer one (one frame
        // of latency), the newer one half a frame later. A real frame is new window content, not a cursor move.
        FrameGen frameGen;
        std::atomic_uint32_t contentSerial{0};
        uint32_t frameGenSerial = 0;
        double fgRatio = 0;  // frames shown per real one, 0 when frame generation is not running
        double fgPhase = 0;  // where the next frame shown falls between the next pair of real frames (0 to 1)
        std::chrono::steady_clock::time_point fgNextPresent{};  // the display time of the next frame shown
        std::chrono::steady_clock::time_point realPresentAt{};  // the display time of the real frame after generated ones
        // Real frames are the content updates of the window updating the most (the game), chosen again every second.
        // Counted per window and per path (window content or direct buffers): a frame reported by both counts once.
        std::mutex contentLock;
        std::unordered_map<int64_t, int> contentCounts;
        std::atomic<int64_t> contentSource{0};
        std::chrono::steady_clock::time_point contentCountsSince{};
        void chooseContentWindow();
        // Debug capture of the presented frames (see captureFrame).
        int captureLeft = 0;
        int captureIndex = 0;
        std::chrono::steady_clock::time_point captureCheckedAt{};
        struct CapturedFrame { std::string name; std::vector<uint8_t> pixels; };
        std::vector<CapturedFrame> captured;
        void captureFrame(const char* kind, float phase);
        // Times of the game's frames, taken when they arrive (not when the renderer gets to them, which is late while it
        // is showing generated frames and would make the game look slower than it is).
        std::mutex realLock;
        double lastRealTime = 0;
        double lastRealGap = 0;
        double realIntervals[8] = {};
        int realIntervalCount = 0;
        double realInterval();

        void renderingThreadLoop();
        void renderDrawable(Drawable *drawable, int x, int y, bool isWindow);
        EGLBoolean drawFrame();
        void prepareSurfaceView(bool scissor);
        void renderWindows();
        void destroyEGLSurface();
        void destroyEGLContext();
        void renderCursor();
        void waitReadbackFence(AHardwareBuffer *ahb);
        void renderDrawable(GLTexture *texture, int length, float xform[], bool isFromWindow, bool swapColors);
        void updateTextureDrawable(GLTexture *texture, int width, int height, void *data);
        std::unique_ptr<GLTexture> allocateTexture(int width, int height);
        std::unique_ptr<GLTexture> allocateTextureDirect(AHardwareBuffer* hardwareBuffer);
        void reallocateTexture(GLTexture *texture, int width, int height);
        void reallocateTextureDirect(GLTexture *texture, AHardwareBuffer* hardwareBuffer);
        void init();
        void createEGLSurface(ANativeWindow *window);
        void collectRenderableWindows(Window *window, int x, int y);
        bool findGameWindow(int& x, int& y, int& width, int& height);
        void setFitMapping(bool active, float scale, float offsetX, float offsetY);

    public:
        bool screenOffsetYRelativeToCursor = false;
        bool toggleFullscreen = false;
        bool magnifierEnabled = true;
        float magnifierZoom = 1.0f;
        bool cursorVisible = true;
        WindowManager *windowManager;
        CursorManager *cursorManager;
        JNICache *cache;
        JNIXServer *xServer;

        EGLRenderer() {}
        EGLRenderer(WindowManager *manager, CursorManager *cm, JNICache *c) : windowManager(manager), cursorManager(cm), cache(c) {}

        void updateScene();
        void start();
        void stop();
        void pause();
        void resume();
        void updateWindowPosition(Window *window);
        void queueEvent(std::function<void()> func);
        void requestRenderer();
        void requestCursorRender();
        void markContentUpdate(int windowId, bool direct);
        int scaleMode = ViewTransformation::MODE_FIT;
        int screenAlignment = ViewTransformation::ALIGN_CENTER;
        void setScaleMode(int mode, int alignment);

        // Frame generation for the HUD: the multiplier in use, the game's frame rate, the frames shown (real and
        // generated, counted by the HUD reader) and the time to generate one.
        std::atomic<float> fgStatMultiplier{0};
        std::atomic<float> fgStatRealFps{0};
        std::atomic<float> fgStatGenerateMs{0};
        std::atomic_uint32_t fgShownFrames{0};
        void destroyTexture(GLTexture *texture);
        void destroySurface();
        void createSurface(ANativeWindow *window);
        void changeSurface(int width, int height);
        void setPostProcessing(const std::string& config);
        bool getFitMapping(float *scale, float *offsetX, float *offsetY);
};
