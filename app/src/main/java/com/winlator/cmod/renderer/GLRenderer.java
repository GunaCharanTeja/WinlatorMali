package com.winlator.cmod.renderer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.Log;

import com.winlator.cmod.R;
import com.winlator.cmod.XrActivity;
import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.math.XForm;
import com.winlator.cmod.renderer.material.CursorMaterial;
import com.winlator.cmod.renderer.material.ShaderMaterial;
import com.winlator.cmod.renderer.material.WindowMaterial;
import com.winlator.cmod.renderer.lsfg.LSFGManager;
import com.winlator.cmod.renderer.lsfg.LSFGEffect;
import com.winlator.cmod.widget.XServerView;
import com.winlator.cmod.xserver.Bitmask;
import com.winlator.cmod.xserver.Cursor;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.WindowAttributes;
import com.winlator.cmod.xserver.WindowManager;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XServer;

import java.util.ArrayList;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.opengl.GLUtils;
import java.util.Locale;

public class GLRenderer implements GLSurfaceView.Renderer, WindowManager.OnWindowModificationListener, Pointer.OnPointerMotionListener, android.view.Choreographer.FrameCallback {
    public final XServerView xServerView;
    private final XServer xServer;
    public final VertexAttribute quadVertices = new VertexAttribute("position", 2);
    private final float[] tmpXForm1 = XForm.getInstance();
    private final float[] tmpXForm2 = XForm.getInstance();
    private final CursorMaterial cursorMaterial = new CursorMaterial();
    private final WindowMaterial windowMaterial = new WindowMaterial();
    public final ViewTransformation viewTransformation = new ViewTransformation();
    private final Drawable rootCursorDrawable;
    private final ArrayList<RenderableWindow> renderableWindows = new ArrayList<>();
    private boolean fullscreen = false;
    private boolean toggleFullscreen = false;
    public boolean viewportNeedsUpdate = true;
    private boolean cursorVisible = true;
    private boolean screenOffsetYRelativeToCursor = false;
    private String[] unviewableWMClasses = null;
    private float magnifierZoom = 1.0f;
    private boolean magnifierEnabled = true;
    public int surfaceWidth;
    public int surfaceHeight;
    private long lastNanos = 0;
    private int currentFpsLimit = 0;
    private final EffectComposer effectComposer;
    private final LSFGManager lsfgManager;
    private com.winlator.cmod.widget.WinlatorHUD winlatorHUD;
    private long fpsStartTime = 0;
    private float displayTotalFPS = 0;
    private boolean renderCursorEnabled = true;

    public GLRenderer(XServerView xServerView, XServer xServer) {
        this.xServerView = xServerView;
        this.xServer = xServer;
        this.lsfgManager = new LSFGManager(this);
        this.effectComposer = new EffectComposer(this);
        rootCursorDrawable = createRootCursorDrawable();

        quadVertices.put(new float[]{
            0.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 0.0f,
            1.0f, 1.0f
        });

        xServer.windowManager.addOnWindowModificationListener(this);
        xServer.pointer.addOnPointerMotionListener(this);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GPUImage.checkIsSupported();

        GLES20.glFrontFace(GLES20.GL_CCW);
        GLES20.glDisable(GLES20.GL_CULL_FACE);

        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(false);

        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);

        // Reset LSFG GL resources on EGL context recreation to prevent freezing
        if (effectComposer != null) {
            LSFGEffect lsfgEffect = effectComposer.getEffect(LSFGEffect.class);
            if (lsfgEffect != null) lsfgEffect.resetGLResources();
        }
        lastNanos = 0;
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        if (XrActivity.isEnabled(null)) {
            XrActivity activity = XrActivity.getInstance();
            activity.init();
            width = activity.getWidth();
            height = activity.getHeight();
            GLES20.glViewport(0, 0, width, height);
            magnifierEnabled = false;
        }

        surfaceWidth = width;
        surfaceHeight = height;
        viewTransformation.update(width, height, xServer.screenInfo.width, xServer.screenInfo.height);
        viewportNeedsUpdate = true;
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        // Locked Target FPS / Unlimited Pacing Rate Limiter (Direct GL Thread Pacing)
        if (lsfgManager.isActive()) {
            boolean shouldRateLimit = true;
            if (lsfgManager.getTargetFPS() == 0 && lsfgManager.isPendingRealFrame()) {
                shouldRateLimit = false;
            }

            if (shouldRateLimit) {
                long targetIntervalNanos;
                if (lsfgManager.getTargetFPS() > 0) {
                    targetIntervalNanos = 1000000000L / lsfgManager.getTargetFPS();
                } else {
                    targetIntervalNanos = lsfgManager.getTypicalDeltaNanos() / Math.max(1, lsfgManager.getMultiplier());
                }
                long elapsed = System.nanoTime() - lastNanos;
                if (elapsed < targetIntervalNanos) {
                    long waitNanos = targetIntervalNanos - elapsed;
                    try {
                        Thread.sleep(waitNanos / 1000000L, (int) (waitNanos % 1000000L));
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }
            }
        }
        lastNanos = System.nanoTime();

        // Prepare frame state once per draw cycle to avoid thread races
        lsfgManager.prepareFrame();

        if (lsfgManager.isActive() && !lsfgManager.shouldRender()) {
            return;
        }

        if (toggleFullscreen) {
            fullscreen = !fullscreen;
            toggleFullscreen = false;
            viewportNeedsUpdate = true;
        }

        if (effectComposer.hasEffects()) {
            effectComposer.render();
        } else {
            drawFrame();
        }

        updateFPS();
        lsfgManager.onPostDraw();
    }

    public void drawFrame() {
        boolean xrFrame = false;
        boolean xrImmersive = false;
        if (XrActivity.isEnabled(null)) {
            xrImmersive = XrActivity.getImmersive();
            xrFrame = XrActivity.getInstance().beginFrame(xrImmersive, XrActivity.getSBS());
        }

        // Update the viewport if necessary
        if (viewportNeedsUpdate && magnifierEnabled) {
            if (fullscreen) {
                GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
            }
            else {
                GLES20.glViewport(viewTransformation.viewOffsetX, viewTransformation.viewOffsetY, viewTransformation.viewWidth, viewTransformation.viewHeight);
            }
            viewportNeedsUpdate = false;
        }

        if (!(lsfgManager.isActive() && lsfgManager.isGeneratedFrame())) {
            // Clear the screen before drawing
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            // Apply basic transformations and draw windows
            if (magnifierEnabled) {
                // Apply magnifier transformations if enabled
                float pointerX = 0;
                float pointerY = 0;
                float magnifierZoom = !screenOffsetYRelativeToCursor ? this.magnifierZoom : 1.0f;

                if (magnifierZoom != 1.0f) {
                    pointerX = Mathf.clamp(xServer.pointer.getX() * magnifierZoom - xServer.screenInfo.width * 0.5f, 0, xServer.screenInfo.width * Math.abs(1.0f - magnifierZoom));
                }

                if (screenOffsetYRelativeToCursor || magnifierZoom != 1.0f) {
                    float scaleY = magnifierZoom != 1.0f ? Math.abs(1.0f - magnifierZoom) : 0.5f;
                    float offsetY = xServer.screenInfo.height * (screenOffsetYRelativeToCursor ? 0.25f : 0.5f);
                    pointerY = Mathf.clamp(xServer.pointer.getY() * magnifierZoom - offsetY, 0, xServer.screenInfo.height * scaleY);
                }

                XForm.makeTransform(tmpXForm2, -pointerX, -pointerY, magnifierZoom, magnifierZoom, 0);
            } else {
                if (!fullscreen) {
                    int pointerY = 0;
                    if (screenOffsetYRelativeToCursor) {
                        short halfScreenHeight = (short) (xServer.screenInfo.height / 2);
                        pointerY = Mathf.clamp(xServer.pointer.getY() - halfScreenHeight / 2, 0, halfScreenHeight);
                    }

                    XForm.makeTransform(tmpXForm2, viewTransformation.sceneOffsetX, viewTransformation.sceneOffsetY - pointerY, viewTransformation.sceneScaleX, viewTransformation.sceneScaleY, 0);

                    GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
                    GLES20.glScissor(viewTransformation.viewOffsetX, viewTransformation.viewOffsetY, viewTransformation.viewWidth, viewTransformation.viewHeight);
                } else {
                    XForm.identity(tmpXForm2);
                }
            }

            renderWindows();

            // Render cursor if enabled
            if (cursorVisible && renderCursorEnabled) renderCursor();

            // Disable scissor test if magnifier is disabled and not in fullscreen mode
            if (!magnifierEnabled && !fullscreen) {
                GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
            }
        }

        // Finalize XR frame if supported
        if (xrFrame) {
            XrActivity.getInstance().endFrame();
            XrActivity.updateControllers();
            xServerView.requestRender();
        }
    }


    @Override
    public void onMapWindow(Window window) {
        xServerView.queueEvent(this::updateScene);
        if (!lsfgManager.isActive()) xServerView.requestRender();
    }

    @Override
    public void onUnmapWindow(Window window) {
        xServerView.queueEvent(this::updateScene);
        if (!lsfgManager.isActive()) xServerView.requestRender();
    }

    @Override
    public void onChangeWindowZOrder(Window window) {
        xServerView.queueEvent(this::updateScene);
        if (!lsfgManager.isActive()) xServerView.requestRender();
    }

    @Override
    public void onUpdateWindowContent(Window window) {
        lsfgManager.notifyRealFramePending();
        if (!lsfgManager.isActive()) xServerView.requestRender();
    }

    @Override
    public void onUpdateWindowGeometry(final Window window, boolean resized) {
        if (resized) {
            xServerView.queueEvent(this::updateScene);
        }
        else {
        	xServerView.queueEvent(() -> updateWindowPosition(window));
        	xServerView.queueEvent(this::updateScene);
        }
        if (!lsfgManager.isActive()) xServerView.requestRender();
    }

    @Override
    public void onUpdateWindowAttributes(Window window, Bitmask mask) {
        if (mask.isSet(WindowAttributes.FLAG_CURSOR) && !lsfgManager.isActive()) xServerView.requestRender();
    }

    @Override
    public void onPointerMove(short x, short y) {
        if (!lsfgManager.isActive()) xServerView.requestRender();
    }


    private void renderDrawable(Drawable drawable, int x, int y, ShaderMaterial material) {
        if (drawable == null) return;
        synchronized (drawable.renderLock) {
            Texture texture = drawable.getTexture();
            texture.updateFromDrawable(drawable);

            XForm.set(tmpXForm1, x, y, drawable.width, drawable.height);

            XForm.multiply(tmpXForm1, tmpXForm1, tmpXForm2);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.getTextureId());
            GLES20.glUniform1i(material.getUniformLocation("texture"), 0);
            GLES20.glUniform1fv(material.getUniformLocation("xform"), tmpXForm1.length, tmpXForm1, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, quadVertices.count());
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }
    }

    private void renderWindows() {
        windowMaterial.use();
        GLES20.glUniform2f(windowMaterial.getUniformLocation("viewSize"), xServer.screenInfo.width, xServer.screenInfo.height);
        quadVertices.bind(windowMaterial.programId);

        try (XLock lock = xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
            for (RenderableWindow window : renderableWindows) {
                renderDrawable(window.content, window.rootX, window.rootY, windowMaterial);
            }
        }

        quadVertices.disable();

        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            Log.e("GLRenderer", "OpenGL Error: " + error);
        }

    }

    private void renderCursor() {
        cursorMaterial.use();
        GLES20.glUniform2f(cursorMaterial.getUniformLocation("viewSize"), xServer.screenInfo.width, xServer.screenInfo.height);
        quadVertices.bind(cursorMaterial.programId);

        try (XLock lock = xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
            Window pointWindow = xServer.inputDeviceManager.getPointWindow();
            Cursor cursor = pointWindow != null ? pointWindow.attributes.getCursor() : null;
            short x = xServer.pointer.getClampedX();
            short y = xServer.pointer.getClampedY();

            if (cursor != null) {
                if (cursor.isVisible()) renderDrawable(cursor.cursorImage, x - cursor.hotSpotX, y - cursor.hotSpotY, cursorMaterial);
            }
            else renderDrawable(rootCursorDrawable, x, y, cursorMaterial);
        }

        quadVertices.disable();
    }

    public void toggleFullscreen() {
        toggleFullscreen = true;
        xServerView.requestRender();
    }

    private Drawable createRootCursorDrawable() {
        Context context = xServerView.getContext();
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.cursor, options);
        return Drawable.fromBitmap(bitmap);
    }

    private void updateScene() {
        try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
            renderableWindows.clear();
            collectRenderableWindows(xServer.windowManager.rootWindow, xServer.windowManager.rootWindow.getX(), xServer.windowManager.rootWindow.getY());
        }
    }

    private void collectRenderableWindows(Window window, int x, int y) {
        if (!window.attributes.isMapped()) return;
        if (window != xServer.windowManager.rootWindow) {
            boolean viewable = true;

            if (unviewableWMClasses != null) {
                String wmClass = window.getClassName();
                for (String unviewableWMClass : unviewableWMClasses) {
                    if (wmClass.contains(unviewableWMClass)) {
                        if (window.attributes.isEnabled()) window.disableAllDescendants();
                        viewable = false;
                        break;
                    }
                }
            }

            if (viewable)
                renderableWindows.add(new RenderableWindow(window.getContent(), x, y));
        }

        for (Window child : window.getChildren()) {
            collectRenderableWindows(child, child.getX() + x, child.getY() + y);
        }
    }

    private void removeRenderableWindow(Window window) {
        for (int i = 0; i < renderableWindows.size(); i++) {
            if (renderableWindows.get(i).content == window.getContent()) {
                renderableWindows.remove(i);
                break;
            }
        }
    }

    private void updateWindowPosition(Window window) {
        for (RenderableWindow renderableWindow : renderableWindows) {
            if (renderableWindow.content == window.getContent()) {
                renderableWindow.rootX = window.getRootX();
                renderableWindow.rootY = window.getRootY();
                break;
            }
        }
    }

    public void setCursorVisible(boolean cursorVisible) {
        this.cursorVisible = cursorVisible;
        xServerView.requestRender();
    }

    public boolean isCursorVisible() {
        return cursorVisible;
    }

    public boolean isScreenOffsetYRelativeToCursor() {
        return screenOffsetYRelativeToCursor;
    }

    public void setScreenOffsetYRelativeToCursor(boolean screenOffsetYRelativeToCursor) {
        this.screenOffsetYRelativeToCursor = screenOffsetYRelativeToCursor;
        xServerView.requestRender();
    }

    public void setFullscreen(boolean fullscreen) {
        this.fullscreen = fullscreen;
        this.viewportNeedsUpdate = true;
    }

    public boolean isFullscreen() {
        return fullscreen;
    }

    public float getMagnifierZoom() {
        return magnifierZoom;
    }

    public void setMagnifierZoom(float magnifierZoom) {
        this.magnifierZoom = magnifierZoom;
        xServerView.requestRender();
    }

    public int getSurfaceWidth() {
        return surfaceWidth;
    }

    public int getSurfaceHeight() {
        return surfaceHeight;
    }

    public int getFpsLimit() {
        return currentFpsLimit;
    }

    public void setFpsLimit(int fpsLimit) {
        this.currentFpsLimit = fpsLimit;
    }

    public boolean isViewportNeedsUpdate() {
        return viewportNeedsUpdate;
    }

    public void setViewportNeedsUpdate(boolean viewportNeedsUpdate) {
        this.viewportNeedsUpdate = viewportNeedsUpdate;
    }

    public VertexAttribute getQuadVertices() {
        return quadVertices;
    }

    public EffectComposer getEffectComposer (){
        return effectComposer;
    }

    public LSFGManager getLSFGManager() {
        return lsfgManager;
    }

    public void setWinlatorHUD(com.winlator.cmod.widget.WinlatorHUD winlatorHUD) {
        this.winlatorHUD = winlatorHUD;
    }

    private void renderWindowEffect(Drawable drawable, int x, int y, ShaderMaterial material) {
        // Implement the rendering effect logic here
        synchronized (drawable.renderLock) {
            Texture texture = drawable.getTexture();
            texture.updateFromDrawable(drawable);

            XForm.set(tmpXForm1, x, y, drawable.width, drawable.height);
            XForm.multiply(tmpXForm1, tmpXForm1, tmpXForm2);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.getTextureId());
            if (GLES20.glIsTexture(texture.getTextureId()) == false) {
                Log.e("GLRenderer", "Invalid texture binding!");
            }

            GLES20.glUniform1i(material.getUniformLocation("texture"), 0);
            GLES20.glUniform1fv(material.getUniformLocation("xform"), tmpXForm1.length, tmpXForm1, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, quadVertices.count());
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }
    }

    public void setUnviewableWMClasses(String... unviewableWMNames) {
        this.unviewableWMClasses = unviewableWMNames;
    }

    void updateFPS() {
        long now = System.nanoTime();
        if (fpsStartTime == 0) fpsStartTime = now;
        
        long elapsedNanos = now - fpsStartTime;
        if (elapsedNanos >= 500000000L) {
            float delta = elapsedNanos / 1000000000.0f;
            displayTotalFPS = (lsfgManager.getActualRealFrameCount() + lsfgManager.getGeneratedFrameCount()) / delta;
            
            if (winlatorHUD != null) {
                winlatorHUD.setApexStats(displayTotalFPS, lsfgManager.getMultiplier(), lsfgManager.isActive());
            }

            lsfgManager.resetFrameCounts();
            fpsStartTime = now;
        }
    }

    private boolean choreographerRunning = false;

    public void startChoreographer() {
        if (!choreographerRunning) {
            choreographerRunning = true;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                android.view.Choreographer.getInstance().postFrameCallback(this);
            });
        }
    }

    public void stopChoreographer() {
        choreographerRunning = false;
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!choreographerRunning) return;
        if (lsfgManager.isActive()) {
            xServerView.requestRender();
        }
        android.view.Choreographer.getInstance().postFrameCallback(this);
    }

    public void setRenderCursorEnabled(boolean enabled) {
        this.renderCursorEnabled = enabled;
    }

    public void drawCursorExplicitly() {
        if (cursorVisible) {
            // Enable EGL blending for transparent cursor png drawing
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            renderCursor();
        }
    }
}
