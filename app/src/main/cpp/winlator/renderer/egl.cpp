#include "egl.hpp"
#include "readback.hpp"

#include <chrono>
#include <unistd.h>

#define LOG_TAG "EGLRenderer"
#define printf(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

static constexpr int64_t PENDING_BUFFER_TIMEOUT_NS = 150000000; // 150 ms

// Smallest window that counts as a game when fitting it to the screen (skips dialogs and tooltips).
static constexpr int MIN_GAME_WINDOW_WIDTH = 320;
static constexpr int MIN_GAME_WINDOW_HEIGHT = 200;
static constexpr int MIN_GAME_WINDOW_AREA_DIVISOR = 5; // at least 1/5 of the X screen

void EGLRenderer::start() {
    renderingThread = std::thread(&EGLRenderer::renderingThreadLoop, this);
    renderingThread.detach();
}

void EGLRenderer::queueEvent(std::function<void()> func) {
    auto lock = renderLock.lock();
    eventQueue.push(func);
    renderLock.notify();
}

void EGLRenderer::requestRenderer() {
    auto lock = renderLock.lock();
    requestUpdate = true;
    renderLock.notify();
}

void EGLRenderer::createSurface(ANativeWindow *window) {
    auto lock = renderLock.lock();
    this->window = window;
    state = State::CREATE_SURFACE;
    renderLock.notify();
    renderLock.wait(lock, [&]{ return state == State::NONE; });
}

void EGLRenderer::destroySurface() {
    auto lock = renderLock.lock();
    this->window = nullptr;
    state = State::DESTROY_SURFACE;
    renderLock.notify();
    renderLock.wait(lock, [&]{ return state == State::NONE; });
}

void EGLRenderer::changeSurface(int width, int height) {
    auto lock = renderLock.lock();
    this->surfaceWidth = width;
    this->surfaceHeight = height;
    state = State::CHANGE_SURFACE;
    renderLock.notify();
    renderLock.wait(lock, [&]{ return state == State::NONE; });
}

void EGLRenderer::stop() {
    stopped = true;
    renderLock.notify();
}

void EGLRenderer::pause() {
    auto lock = renderLock.lock();
    state = State::PAUSE;
    renderLock.notify();
    renderLock.wait(lock, [&]{ return state == State::NONE; });
}

void EGLRenderer::resume() {
    auto lock = renderLock.lock();
    state = State::RESUME;
    renderLock.notify();
    renderLock.wait(lock, [&]{ return state == State::NONE; });
}

void EGLRenderer::setPostProcessing(const std::string& config) {
    postFX.setConfig(config);
    requestRenderer();
}

void EGLRenderer::renderingThreadLoop() {
    bool createEGLSurface = false;
    bool surfaceChanged = false;
    bool hasSurface = false;
    bool paused = false;
    bool badSurface = false;
    bool badContext = false;

    this->env = cache->getEnv();

    while (true) {
        std::function<void()> func = nullptr;
        bool requestRender = false;
        bool sizeChanged = false;

        auto lock = renderLock.lock();
        renderLock.wait(lock, [&]{
            if (paused) {
                return stopped || state != State::NONE || !eventQueue.empty();
            } else {
                return stopped || state != State::NONE || !eventQueue.empty() || requestUpdate;
            }
        });

        State currState = state;
        state = State::NONE;

        if (stopped) {
            printf("Stopping renderingThread");
            lock.unlock();
            if (context != EGL_NO_CONTEXT) {
                eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
                destroyEGLContext();
            }
            cache->detachEnv();
            return;
        }

        if (!eventQueue.empty()) {
            func = eventQueue.front();
            eventQueue.pop();
            state = currState;
            lock.unlock();
            func();
            continue;
        }

        if (currState == State::PAUSE) {
            printf("Received state PAUSE");
            paused = true;
            renderLock.notify();
        }

        if (currState == State::RESUME) {
            printf("Received state RESUME");
            paused = false;
            requestUpdate = true;
            renderLock.notify();
        }

        if (badSurface || badContext) {
            printf("Invalidating current context and surface");
            eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        }

        if (badContext) {
            printf("Destroying invalid context");
            destroyEGLContext();
            badContext = false;
        }

        if (badSurface) {
            printf("Destroying invalid surface");
            destroyEGLSurface();
            badSurface = false;
        }

        if (currState == State::CREATE_SURFACE) {
            printf("Received state CREATE_SURFACE");
            hasSurface = true;
            renderLock.notify();
        }

        if (currState == State::CHANGE_SURFACE) {
            printf("Received state CHANGE_SURFACE");
            viewTransformation.update(surfaceWidth, surfaceHeight, windowManager->getRootWindow()->width, windowManager->getRootWindow()->height);
            surfaceChanged = true;
            sizeChanged = true;
            requestUpdate = true;
            renderLock.notify();
        }

        if (currState == State::DESTROY_SURFACE) {
            printf("Received state DESTROY_SURFACE");
            hasSurface = false;
            surfaceChanged = false;
            badSurface = surface != EGL_NO_SURFACE;
            renderLock.notify();
        }

        if (requestUpdate && hasSurface && surfaceChanged && !badSurface && !badContext && !paused) {
            if (context == EGL_NO_CONTEXT)
                init();

            if (surface == EGL_NO_SURFACE)
                createEGLSurface = true;

            requestRender = requestUpdate;
            requestUpdate = false;
        }

        lock.unlock();

        if (createEGLSurface) {
            this->createEGLSurface(this->window);
            createEGLSurface = false;
        }

        if (context == EGL_NO_CONTEXT || surface == EGL_NO_SURFACE)
            continue;

        if (sizeChanged || requestRender) {
            EGLBoolean ret = drawFrame();
            if (ret == EGL_FALSE) {
                printf("Failed to draw frame");

                int error = eglGetError();
                if (error == EGL_BAD_SURFACE || error == EGL_BAD_NATIVE_WINDOW)
                    badSurface = true;
                else if (error == EGL_CONTEXT_LOST)
                    badContext = true;
            }
        }
    }
}

// The game's frame interval: the mean of the last eight without the longest and the shortest (0 until there are eight).
double EGLRenderer::realInterval() {
    if (realIntervalCount < 8)
        return 0;
    double sum = 0, low = realIntervals[0], high = realIntervals[0];
    for (double v : realIntervals) {
        sum += v;
        low = std::min(low, v);
        high = std::max(high, v);
    }
    return (sum - low - high) / 6.0;
}

void EGLRenderer::prepareSurfaceView(bool scissor) {
    if (fullscreen) {
        glViewport(0, 0, surfaceWidth, surfaceHeight);
    }
    else {
        glViewport(viewTransformation.viewOffsetX, viewTransformation.viewOffsetY, viewTransformation.viewWidth, viewTransformation.viewHeight);
    }

    glClear(GL_COLOR_BUFFER_BIT);

    if (magnifierEnabled) {
        float pointerX = 0;
        float pointerY = 0;
        float magnifierZoom = !screenOffsetYRelativeToCursor ? this->magnifierZoom : 1.0f;

        if (magnifierZoom != 1.0f) {
            pointerX = std::clamp(cursorManager->pointer.posX * magnifierZoom - windowManager->getRootWindow()->width * 0.5f, 0.0f, windowManager->getRootWindow()->width * std::abs(1.0f - magnifierZoom));
        }

        if (screenOffsetYRelativeToCursor || magnifierZoom != 1.0f) {
            float scaleY = magnifierZoom != 1.0f ? std::abs(1.0f - magnifierZoom) : 0.5f;
            float offsetY = windowManager->getRootWindow()->height * (screenOffsetYRelativeToCursor ? 0.25f : 0.5f);
            pointerY = std::clamp(cursorManager->pointer.posY * magnifierZoom - offsetY, 0.0f, windowManager->getRootWindow()->height * scaleY);
        }

        XForm::makeTransform(tmpXForm2, -pointerX, -pointerY, magnifierZoom, magnifierZoom, 0.0f);
    } else {
        if (!fullscreen) {
            int pointerY = 0;
            if (screenOffsetYRelativeToCursor) {
                uint16_t halfScreenHeight = (uint16_t)(windowManager->getRootWindow()->height / 2);
                pointerY = std::clamp<int>(cursorManager->pointer.posY - halfScreenHeight / 2, 0, halfScreenHeight);
            }

            XForm::makeTransform(tmpXForm2, viewTransformation.sceneOffsetX, viewTransformation.sceneOffsetY - pointerY, viewTransformation.sceneScaleX, viewTransformation.sceneScaleY, 0.0f);
            if (scissor) {
                glEnable(GL_SCISSOR_TEST);
                glScissor(viewTransformation.viewOffsetX, viewTransformation.viewOffsetY, viewTransformation.viewWidth, viewTransformation.viewHeight);
            }
        } else {
            XForm::identity(tmpXForm2);
        }
    }
}

EGLBoolean EGLRenderer::drawFrame() {
    if (toggleFullscreen) {
        fullscreen = !fullscreen;
        toggleFullscreen = false;
    }

    auto rootWindow = windowManager->getRootWindow();
    float rootWidth = rootWindow->width;
    float rootHeight = rootWindow->height;

    // The scene is the whole X screen, or only the game window when it is fitted to the screen.
    int sceneX = 0, sceneY = 0, sceneWidth = rootWindow->width, sceneHeight = rootWindow->height;
    bool postFXActive = contextVersion >= 3 && postFX.isActive();
    bool frameGenOn = contextVersion >= 3 && postFX.frameGeneration();
    if (!frameGenOn)
        fgStatMultiplier = 0;

    bool fit = contextVersion >= 3 && postFX.fitGameWindow() && findGameWindow(sceneX, sceneY, sceneWidth, sceneHeight);
    bool postProcess = (postFXActive || fit || frameGenOn) && postFX.beginScene(sceneWidth, sceneHeight);
    fit = fit && postProcess;

    // Fitted area keeps the game's aspect ratio and is centered on the X screen.
    float scale = fit ? std::min(rootWidth / sceneWidth, rootHeight / sceneHeight) : 1.0f;
    float dstWidth = fit ? sceneWidth * scale : rootWidth;
    float dstHeight = fit ? sceneHeight * scale : rootHeight;
    float dstX = (rootWidth - dstWidth) * 0.5f;
    float dstY = (rootHeight - dstHeight) * 0.5f;
    setFitMapping(fit, scale, dstX - sceneX * scale, dstY - sceneY * scale);

    if (postProcess) {
        XForm::set(tmpXForm2, -sceneX, -sceneY, 1, 1);
        drawableShader->use();
        glUniform2f(drawableShader->getUniformLoc("viewSize"), sceneWidth, sceneHeight);
        renderWindows();
        drawableShader->disable();

        postFX.applyEffects();

        // The scene (or a generated picture of it) on the surface, with the cursor over it.
        auto presentScene = [&](GLuint generated) {
            prepareSurfaceView(true);
            XForm::set(tmpXForm1, dstX, dstY, dstWidth, dstHeight);
            XForm::multiply(tmpXForm1, tmpXForm1, tmpXForm2);
            if (generated)
                postFX.presentTexture(generated, tmpXForm1, rootWidth, rootHeight);
            else
                postFX.present(tmpXForm1, rootWidth, rootHeight);

            drawableShader->use();
            glUniform2f(drawableShader->getUniformLoc("viewSize"), rootWidth, rootHeight);

            // The cursor follows the fitted game window.
            if (fit) {
                XForm::set(tmpXForm1, dstX - sceneX * scale, dstY - sceneY * scale, scale, scale);
                XForm::multiply(tmpXForm2, tmpXForm1, tmpXForm2);
            }
        };

        uint32_t serial = contentSerial.load();
        if (frameGenOn && serial != frameGenSerial) {
            frameGenSerial = serial;
            double now = std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch()).count();
            if (lastRealTime > 0) {
                realIntervals[realIntervalCount % 8] = now - lastRealTime;
                realIntervalCount++;
            }
            lastRealTime = now;
            // The multiplier: 2x, or with a target the most frames per real one that stay at or under it (a game capped at 30
            // with a target of 60 runs 2x, at 20 runs 3x; at 45 none). Generated only between frames of a running game: over
            // 10 fps (a stall, a menu or a loading screen is not motion to interpolate).
            double interval = realInterval();
            int target = postFX.frameGenerationTarget();
            int multiplier = 1;
            if (interval > 0 && interval <= 0.100) {
                if (target > 0) {
                    // A game held at 30 by a limiter wanders 29-31: the multiplier in use is kept while it stays within about
                    // 10 % of the target (2x of 31 is 62, not a reason to stop), and changes only past that.
                    double ratio = target * interval;
                    multiplier = fgCurrentMultiplier >= 2 && ratio >= fgCurrentMultiplier * 0.9 && ratio < fgCurrentMultiplier + 1.0
                        ? fgCurrentMultiplier
                        : std::clamp(int(ratio + 0.1), 1, 4);
                } else {
                    multiplier = interval < 0.020 ? 1 : 2;
                }
            }
            fgCurrentMultiplier = multiplier;
            if (multiplier < 2)
                frameGen.reset();
            fgStatMultiplier = multiplier;
            fgStatRealFps = interval > 0 ? float(1.0 / interval) : 0.0f;
            if (frameGen.pushReal(display, postFX.sceneTexture(), postFX.sceneWidth(), postFX.sceneHeight()) && multiplier >= 2) {
                // The generated frames 1/M, 2/M ... of the way from the previous real frame to this one, each 1/M of a real
                // frame after the last (the swap returns once the buffer is queued), then this frame.
                // Even steps of interval / M from the moment the first generated frame is shown (generating it takes a few ms
                // after the real frame arrived; timing from the arrival would show the next frame early and leave a long gap).
                const auto step = std::chrono::duration_cast<std::chrono::steady_clock::duration>(
                    std::chrono::duration<double>(interval / multiplier));
                const auto margin = std::chrono::microseconds(1000);  // the present itself
                std::chrono::steady_clock::time_point first;
                float gpuMs = 0;
                int shown = 0;
                // Each frame also carries the time it is meant for (EGL_ANDROID_presentation_time): the compositor shows it at
                // the first refresh at or after it, so the steps stay even whatever the scheduler does to the sleeps. The first
                // generated frame is meant for when its GPU work should be done (the last generation's GPU time ahead).
                static auto presentationTime = (PFNEGLPRESENTATIONTIMEANDROIDPROC)eglGetProcAddress("eglPresentationTimeANDROID");
                auto presentAt = [&](std::chrono::steady_clock::time_point when) {
                    if (presentationTime)
                        presentationTime(display, surface, (EGLnsecsANDROID)std::chrono::duration_cast<std::chrono::nanoseconds>(when.time_since_epoch()).count());
                };
                std::chrono::steady_clock::time_point base;
                for (int k = 1; k < multiplier; k++) {
                    if (!frameGen.generate(float(k) / float(multiplier)))
                        break;
                    gpuMs += frameGen.generateMs();
                    if (shown > 0)
                        std::this_thread::sleep_until(first + step * shown - margin);
                    if (shown == 0)
                        base = std::chrono::steady_clock::now() + std::chrono::microseconds(int64_t((frameGen.generateMs() + 2.0f) * 1000.0f));
                    presentScene(frameGen.generatedTexture());
                    if (cursorVisible)
                        renderCursor();
                    drawableShader->disable();
                    glDisable(GL_SCISSOR_TEST);
                    presentAt(base + step * shown);
                    eglSwapBuffers(display, surface);
                    if (shown == 0)
                        first = std::chrono::steady_clock::now();
                    shown++;
                    fgShownFrames++;
                }
                if (shown > 0) {
                    std::this_thread::sleep_until(first + step * shown - margin);
                    fgStatGenerateMs = gpuMs / float(shown);
                    realPresentAt = base + step * shown;
                }
            }
        }
        presentScene(0);
        fgShownFrames++;
    }
    else {
        prepareSurfaceView(true);
        drawableShader->use();
        glUniform2f(drawableShader->getUniformLoc("viewSize"), rootWidth, rootHeight);
        renderWindows();
    }

    if (cursorVisible)
        renderCursor();

    drawableShader->disable();

    glDisable(GL_SCISSOR_TEST);

    if (realPresentAt.time_since_epoch().count() != 0) {
        static auto presentationTime = (PFNEGLPRESENTATIONTIMEANDROIDPROC)eglGetProcAddress("eglPresentationTimeANDROID");
        if (presentationTime)
            presentationTime(display, surface, (EGLnsecsANDROID)std::chrono::duration_cast<std::chrono::nanoseconds>(realPresentAt.time_since_epoch()).count());
        realPresentAt = {};
    }
    return eglSwapBuffers(display, surface);
}

void EGLRenderer::renderWindows() {
    for (const auto& renderableWindow : renderableWindows) {
        if (renderableWindow == nullptr)
            continue;

        auto window = renderableWindow->window;
        if (!window)
            continue;

        if (!window->hasContent && !window->hasExternalContents())
            continue;

        if (window->isHidden())
            continue;

        if (window->hasExternalContents())
            renderDrawable(window->externalContent, renderableWindow->rootX, renderableWindow->rootY, true);
        else
            renderDrawable(window->drawable.get(), renderableWindow->rootX, renderableWindow->rootY, true);
    }
}

void EGLRenderer::renderCursor() {
    Cursor *cursor = nullptr;
    jobject pointWindowObj = env->CallObjectMethod(xServer->inputDeviceManager, cache->getPointWindow);
    if (pointWindowObj != nullptr) {
        jint id = env->GetIntField(pointWindowObj, cache->windowID);
        env->DeleteLocalRef(pointWindowObj);
        auto pointWindow = windowManager->getWindow(id);
        cursor = (pointWindow != nullptr) ? pointWindow->cursor : nullptr;
    }

    int x = std::clamp(cursorManager->pointer.posX, 0, windowManager->getRootWindow()->width - 1);
    int y = std::clamp(cursorManager->pointer.posY, 0, windowManager->getRootWindow()->height - 1);

    if (cursor != nullptr) {
        if (cursor->visible)
            renderDrawable(cursor->image.get(), x - cursor->hotspotX, y - cursor->hotspotY, false);
    }
    else if (cursorManager->getRootCursor() != nullptr) {
        renderDrawable(cursorManager->getRootCursor()->image.get(), x, y, false);
    }
}

void EGLRenderer::renderDrawable(Drawable *drawable, int x, int y, bool isWindow) {
    if (drawable == nullptr)
        return;

    // A resized window keeps showing its previous frame until the client redraws,
    // with a short timeout for clients that never do.
    if (drawable->pendingAhb != nullptr) {
        int64_t now = std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
        if (now - drawable->pendingSince > PENDING_BUFFER_TIMEOUT_NS)
            applyPendingBuffer(drawable);
    }

    if (drawable->glTexture == nullptr) {
        if (drawable->ahb != nullptr)
            drawable->glTexture = allocateTextureDirect(drawable->ahb);
        else if (drawable->data != nullptr) {
            drawable->glTexture = allocateTexture(drawable->width, drawable->height);
            drawable->glTexture->isDirty = true;
        }
    }
    else if (drawable->glTexture->sizeChanged) {
        if (drawable->ahb != nullptr)
            reallocateTextureDirect(drawable->glTexture.get(), drawable->ahb);
        else {
            reallocateTexture(drawable->glTexture.get(), drawable->width, drawable->height);
            drawable->glTexture->isDirty = true;
        }

        drawable->glTexture->sizeChanged = false;
    }

    if (drawable->glTexture == nullptr || drawable->glTexture->id == 0)
        return;

    if (drawable->glTexture->isDirty) {
        updateTextureDrawable(drawable->glTexture.get(), drawable->width, drawable->height, drawable->data);
        drawable->glTexture->isDirty = false;
    }

    if (drawable->ahb != nullptr)
        waitReadbackFence(drawable->ahb);

    XForm::set(tmpXForm1, x, y, drawable->width, drawable->height);
    XForm::multiply(tmpXForm1, tmpXForm1, tmpXForm2);
    renderDrawable(drawable->glTexture.get(), 6, tmpXForm1, isWindow, drawable->swapRB);
}

// Makes this GPU queue wait (without blocking the CPU) for a pending GLX/Gladio copy into the buffer.
void EGLRenderer::waitReadbackFence(AHardwareBuffer *ahb) {
    int fd = takeReadbackFence(ahb);
    if (fd < 0)
        return;

    const EGLint attribs[] = {EGL_SYNC_NATIVE_FENCE_FD_ANDROID, fd, EGL_NONE};
    EGLSyncKHR sync = eglCreateSyncKHR(display, EGL_SYNC_NATIVE_FENCE_ANDROID, attribs);
    if (sync == EGL_NO_SYNC_KHR) {
        close(fd);
        return;
    }

    eglWaitSyncKHR(display, sync, 0);
    eglDestroySyncKHR(display, sync);
}

void EGLRenderer::updateScene() {
    renderableWindows.clear();
    collectRenderableWindows(windowManager->getRootWindow(), windowManager->getRootWindow()->x, windowManager->getRootWindow()->y);
}

void EGLRenderer::collectRenderableWindows(Window *window, int x, int y) {
    if (!window->mapped || !window->inputOutput)
        return;

    if (window != windowManager->getRootWindow()) {
        bool viewable = env->CallBooleanMethod(window->attributes, cache->windowAttributesIsEnabled);
        bool renderable = window->width > 1 && window->height > 1;

        if (viewable && renderable) {
            auto renderableWindow = std::make_unique<struct RenderableWindow>();
            renderableWindow->rootX = x;
            renderableWindow->rootY = y;
            renderableWindow->window = window;
            renderableWindows.push_back(std::move(renderableWindow));
        }
    }

    for (const auto& child : window->children) {
        collectRenderableWindows(child, child->x + x, child->y + y);
    }
}

// Largest drawn window that is smaller than the X screen, e.g. an 800x600 game on a 1280x720 screen.
// Screen-sized windows (the Wine desktop, fullscreen games) and small dialogs are ignored.
bool EGLRenderer::findGameWindow(int& x, int& y, int& width, int& height) {
    auto rootWindow = windowManager->getRootWindow();
    int rootWidth = rootWindow->width;
    int rootHeight = rootWindow->height;
    int bestArea = 0;

    for (const auto& renderableWindow : renderableWindows) {
        if (renderableWindow == nullptr || renderableWindow->window == nullptr)
            continue;

        auto window = renderableWindow->window;
        if ((!window->hasContent && !window->hasExternalContents()) || window->isHidden())
            continue;

        int x0 = std::max(renderableWindow->rootX, 0);
        int y0 = std::max(renderableWindow->rootY, 0);
        int x1 = std::min(renderableWindow->rootX + window->width, rootWidth);
        int y1 = std::min(renderableWindow->rootY + window->height, rootHeight);
        int w = x1 - x0;
        int h = y1 - y0;

        if (w >= rootWidth && h >= rootHeight)
            continue;

        if (w < MIN_GAME_WINDOW_WIDTH || h < MIN_GAME_WINDOW_HEIGHT || w * h * MIN_GAME_WINDOW_AREA_DIVISOR < rootWidth * rootHeight)
            continue;

        if (w * h > bestArea) {
            bestArea = w * h;
            x = x0;
            y = y0;
            width = w;
            height = h;
        }
    }

    return bestArea > 0;
}

void EGLRenderer::setFitMapping(bool active, float scale, float offsetX, float offsetY) {
    std::lock_guard<std::mutex> lock(fitLock);
    fitActive = active;
    fitScale = scale;
    fitOffsetX = offsetX;
    fitOffsetY = offsetY;
}

bool EGLRenderer::getFitMapping(float *scale, float *offsetX, float *offsetY) {
    std::lock_guard<std::mutex> lock(fitLock);
    *scale = fitScale;
    *offsetX = fitOffsetX;
    *offsetY = fitOffsetY;
    return fitActive;
}

void EGLRenderer::updateWindowPosition(Window* window) {
    for (auto& renderableWindow : renderableWindows) {
        if (renderableWindow->window == window) {
            renderableWindow->rootX = window->getRootX();
            renderableWindow->rootY = window->getRootY();
            break;
        }
    }
}

void EGLRenderer::init() {
    EGLBoolean result;

    display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display == EGL_NO_DISPLAY) {
        printf("Failed to get default display");
        return;
    }

    EGLint major, minor;
    result = eglInitialize(display, &major, &minor);
    if (result != EGL_TRUE) {
        printf("Failed to initialize egl");
        return;
    }

    printf("Initialized egl major %d minor %d", major, minor);

    eglBindAPI(EGL_OPENGL_ES_API);

    int num_configs = 0;

    for (int version = 3; version >= 2 && context == EGL_NO_CONTEXT; version--) {
        const EGLint attrib_list[] = {
            EGL_RENDERABLE_TYPE, version == 3 ? EGL_OPENGL_ES3_BIT_KHR : EGL_OPENGL_ES2_BIT,
            EGL_RED_SIZE, 8,
            EGL_BLUE_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_ALPHA_SIZE, 8,
            EGL_DEPTH_SIZE, 0,
            EGL_STENCIL_SIZE, 0,
            EGL_NONE
        };

        result = eglChooseConfig(display, attrib_list, &config, 1, &num_configs);
        if (result != EGL_TRUE || num_configs < 1)
            continue;

        const EGLint ctx_attrib_list[] = {
            EGL_CONTEXT_CLIENT_VERSION, version,
            EGL_NONE
        };

        context = eglCreateContext(display, config, EGL_NO_CONTEXT, ctx_attrib_list);
        if (context != EGL_NO_CONTEXT)
            contextVersion = version;
    }

    if (context == EGL_NO_CONTEXT) {
        printf("Failed to create egl context");
        return;
    }

    printf("Created GLES %d context", contextVersion);

    eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, context);

    drawableShader = new DrawableShader();
}

void EGLRenderer::createEGLSurface(ANativeWindow *window) {
    EGLBoolean result;

    if (window == nullptr || context == EGL_NO_CONTEXT)
        return;

    surface = eglCreateWindowSurface(display, config, window, nullptr);
    if (surface == EGL_NO_SURFACE) {
        printf("Failed to create window surface");
        return;
    }

    result = eglMakeCurrent(display, surface, surface, context);
    if (result != EGL_TRUE) {
        printf("Failed to make context current");
        return;
    }

    glFrontFace(GL_CCW);
    glDisable(GL_CULL_FACE);

    glDisable(GL_DEPTH_TEST);
    glDepthMask(false);

    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
}

void EGLRenderer::renderDrawable(GLTexture *texture, int length, float xform[], bool isFromWindow, bool swapColors) {
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, texture->id);
    glUniform1i(drawableShader->getUniformLoc("texture"), 0);
    glUniform1fv(drawableShader->getUniformLoc("xform"), length, xform);
    glUniform1i(drawableShader->getUniformLoc("is_cursor"), isFromWindow ? 0 : 1);
    glUniform1i(drawableShader->getUniformLoc("swap_colors"), swapColors ? 1 : 0);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindTexture(GL_TEXTURE_2D, 0);
}

std::unique_ptr<GLTexture> EGLRenderer::allocateTextureDirect(AHardwareBuffer* hardwareBuffer) {
    if (!hardwareBuffer || display == EGL_NO_DISPLAY)
        return nullptr;

    std::unique_ptr<GLTexture> texture = std::make_unique<GLTexture>();

    const EGLint attribList[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};

    EGLClientBuffer clientBuffer = eglGetNativeClientBufferANDROID(hardwareBuffer);
    if (!clientBuffer)
        return nullptr;

    texture->eglImage = eglCreateImageKHR(display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, clientBuffer, attribList);
    if (!texture->eglImage)
        return nullptr;

    glGenTextures(1, (GLuint *)&texture->id);
    glActiveTexture(GL_TEXTURE0);

    glBindTexture(GL_TEXTURE_2D, texture->id);
    if (glGetError() != GL_NO_ERROR) {
        eglDestroyImageKHR(display, texture->eglImage);
        return nullptr;
    }

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);

    glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, texture->eglImage);
    if (glGetError() != GL_NO_ERROR) {
        glDeleteTextures(1, (GLuint *)&texture->id);
        eglDestroyImageKHR(display, texture->eglImage);
        return nullptr;
    }

    glBindTexture(GL_TEXTURE_2D, 0);

    return texture;
}

std::unique_ptr<GLTexture> EGLRenderer::allocateTexture(int width, int height) {
    std::unique_ptr<GLTexture> texture = std::make_unique<GLTexture>();

    glGenTextures(1, (GLuint *)&texture->id);

    glActiveTexture(GL_TEXTURE0);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glBindTexture(GL_TEXTURE_2D, texture->id);

    glTexImage2D(GL_TEXTURE_2D, 0, GL_BGRA_EXT, width, height, 0, GL_BGRA_EXT, GL_UNSIGNED_BYTE, nullptr);

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);

    glBindTexture(GL_TEXTURE_2D, 0);

    return texture;
}

void EGLRenderer::reallocateTexture(GLTexture *texture, int width, int height) {
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, texture->id);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_BGRA_EXT, width, height, 0, GL_BGRA_EXT, GL_UNSIGNED_BYTE, nullptr);
    glBindTexture(GL_TEXTURE_2D, 0);
}

void EGLRenderer::reallocateTextureDirect(GLTexture *texture, AHardwareBuffer* hardwareBuffer) {
    if (!hardwareBuffer || display == EGL_NO_DISPLAY) {
        destroyTexture(texture);
        return;
    }

    const EGLint attribList[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};

    EGLClientBuffer clientBuffer = eglGetNativeClientBufferANDROID(hardwareBuffer);
    if (!clientBuffer) {
        destroyTexture(texture);
        return;
    }

    if (texture->eglImage)
        eglDestroyImageKHR(display, texture->eglImage);

    texture->eglImage = eglCreateImageKHR(display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, clientBuffer, attribList);
    if (!texture->eglImage) {
        destroyTexture(texture);
        return;
    }

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, texture->id);

    glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, texture->eglImage);
    if (glGetError() != GL_NO_ERROR) {
        destroyTexture(texture);
        return;
    }

    glBindTexture(GL_TEXTURE_2D, 0);
}

void EGLRenderer::destroyTexture(GLTexture* texture) {
    if (!texture)
        return;

    if (texture->id != 0)
        glDeleteTextures(1, (GLuint *)&texture->id);

    if (texture->eglImage)
        eglDestroyImageKHR(display, texture->eglImage);

    texture->id = 0;
    texture->eglImage = nullptr;
}

void EGLRenderer::updateTextureDrawable(GLTexture *texture, int width, int height, void *data) {
    glBindTexture(GL_TEXTURE_2D, texture->id);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_BGRA_EXT, GL_UNSIGNED_BYTE, data);
    glBindTexture(GL_TEXTURE_2D, 0);
}

void EGLRenderer::destroyEGLSurface() {
    if (surface != EGL_NO_SURFACE)
        eglDestroySurface(display, surface);
    surface = EGL_NO_SURFACE;
}

void EGLRenderer::destroyEGLContext() {
    postFX.destroy();
    frameGen.destroy();

    delete drawableShader;
    drawableShader = nullptr;

    {
        auto lock = windowManager->windowLock.lock();
        for (auto& [id, window] : windowManager->getWindowTree()) {
            if (window->drawable)
                window->drawable->glTexture = nullptr;
            for (auto& [contentId, content] : window->directContents)
                content->glTexture = nullptr;
        }
    }

    eglDestroyContext(display, context);
    context = EGL_NO_CONTEXT;
    contextVersion = 0;
}
