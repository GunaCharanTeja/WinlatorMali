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
import java.util.Iterator;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

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
    private com.winlator.cmod.widget.WinlatorHUD winlatorHUD;
    private long fpsStartTime = 0;
    private float displayTotalFPS = 0;
    private boolean renderCursorEnabled = true;
    private int regularFrameCount = 0;

    public GLRenderer(XServerView xServerView, XServer xServer) {
        this.xServerView = xServerView;
        this.xServer = xServer;
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

        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(false);

        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);

        if (surfaceWidth > 0 && surfaceHeight > 0) {
            ApexNativeBridge.nativeInit(surfaceWidth, surfaceHeight);
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
        ApexNativeBridge.nativeUpdateDimensions(width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        int fpsLimit = currentFpsLimit;
        if (ApexNativeBridge.nativeIsActive()) {
            fpsLimit = ApexNativeBridge.nativeGetTargetFPS();
        }

        if (fpsLimit > 0) {
            long targetIntervalNanos = 1000000000L / fpsLimit;
            long elapsed = System.nanoTime() - lastNanos;
            if (elapsed < targetIntervalNanos) {
                long waitNanos = targetIntervalNanos - elapsed;
                if (waitNanos > 1500000L) {
                    try {
                        Thread.sleep((waitNanos - 1000000L) / 1000000L, (int) ((waitNanos - 1000000L) % 1000000L));
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }
                while (System.nanoTime() - lastNanos < targetIntervalNanos) {
                    Thread.onSpinWait();
                }
            }
        }
        lastNanos = System.nanoTime();

        if (toggleFullscreen) {
            fullscreen = !fullscreen;
            toggleFullscreen = false;
            viewportNeedsUpdate = true;
        }

        if (effectComposer.hasEffects() || ApexNativeBridge.nativeIsActive()) {
            effectComposer.render();
        } else {
            drawFrame();
        }

        regularFrameCount++;
        updateFPS();
    }

    public void drawFrame() {
        boolean xrFrame = false;
        boolean xrImmersive = false;
        if (XrActivity.isEnabled(null)) {
            xrImmersive = XrActivity.getImmersive();
            xrFrame = XrActivity.getInstance().beginFrame(xrImmersive, XrActivity.getSBS());
        }

        if (viewportNeedsUpdate && magnifierEnabled) {
            if (fullscreen) {
                GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
            } else {
                GLES20.glViewport(viewTransformation.viewOffsetX, viewTransformation.viewOffsetY, viewTransformation.viewWidth, viewTransformation.viewHeight);
            }
            viewportNeedsUpdate = false;
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        renderScene();

        if (xrFrame) {
            XrActivity.getInstance().endFrame();
        }
    }

    public void setFpsLimit(int fpsLimit) {
        this.currentFpsLimit = fpsLimit;
    }

    public int getFpsLimit() {
        return currentFpsLimit;
    }

    public void setWinlatorHUD(com.winlator.cmod.widget.WinlatorHUD hud) {
        this.winlatorHUD = hud;
    }

    private void renderScene() {
        if (magnifierEnabled) {
            float f = !screenOffsetYRelativeToCursor ? magnifierZoom : 1.0f;
            float f2 = 0.0f;
            float clamp = f != 1.0f ? Mathf.clamp((xServer.pointer.getX() * f) - (xServer.screenInfo.width * 0.5f), 0.0f, xServer.screenInfo.width * Math.abs(1.0f - f)) : 0.0f;
            if (screenOffsetYRelativeToCursor || f != 1.0f) {
                f2 = Mathf.clamp((xServer.pointer.getY() * f) - (xServer.screenInfo.height * (screenOffsetYRelativeToCursor ? 0.25f : 0.5f)), 0.0f, xServer.screenInfo.height * (f != 1.0f ? Math.abs(1.0f - f) : 0.5f));
            }
            XForm.makeTransform(tmpXForm2, -clamp, -f2, f, f, 0.0f);
        } else if (!fullscreen) {
            int i = 0;
            if (screenOffsetYRelativeToCursor) {
                short s = (short) (xServer.screenInfo.height / 2);
                i = Mathf.clamp(xServer.pointer.getY() - (s / 2), 0, (int) s);
            }
            XForm.makeTransform(tmpXForm2, viewTransformation.sceneOffsetX, viewTransformation.sceneOffsetY - i, viewTransformation.sceneScaleX, viewTransformation.sceneScaleY, 0.0f);
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
            GLES20.glScissor(viewTransformation.viewOffsetX, viewTransformation.viewOffsetY, viewTransformation.viewWidth, viewTransformation.viewHeight);
        } else {
            XForm.identity(tmpXForm2);
        }

        renderWindows();

        if (cursorVisible && renderCursorEnabled) {
            renderCursor();
        }

        if (!magnifierEnabled && !fullscreen) {
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        }
    }

    private void renderWindows() {
        windowMaterial.use();
        GLES20.glUniform2f(windowMaterial.getUniformLocation("viewSize"), xServer.screenInfo.width, xServer.screenInfo.height);
        quadVertices.bind(windowMaterial.programId);

        try (XLock lock = xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
            Iterator<RenderableWindow> it = renderableWindows.iterator();
            while (it.hasNext()) {
                RenderableWindow next = it.next();
                renderDrawable(next.content, next.rootX, next.rootY, windowMaterial);
            }
        }
        quadVertices.disable();
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

    private Drawable createRootCursorDrawable() {
        Context context = xServerView.getContext();
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        return Drawable.fromBitmap(BitmapFactory.decodeResource(context.getResources(), R.drawable.cursor, options));
    }

    public ShaderMaterial getPassthroughMaterial() {
        return windowMaterial;
    }

    public VertexAttribute getQuadVertices() {
        return quadVertices;
    }

    public int getSurfaceWidth() {
        return surfaceWidth;
    }

    public int getSurfaceHeight() {
        return surfaceHeight;
    }

    public boolean isFullscreen() {
        return fullscreen;
    }

    public void toggleFullscreen() {
        toggleFullscreen = true;
        xServerView.requestRender();
    }

    public EffectComposer getEffectComposer() {
        return effectComposer;
    }

    private void updateScene() {
        try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
            renderableWindows.clear();
            collectRenderableWindows(xServer.windowManager.rootWindow, xServer.windowManager.rootWindow.getX(), xServer.windowManager.rootWindow.getY());
        }
    }

    private void collectRenderableWindows(Window window, int x, int y) {
        if (!window.attributes.isMapped()) return;

        boolean isUnviewable = false;
        if (unviewableWMClasses != null) {
            String wmClass = window.getClassName();
            for (String unviewableWMClass : unviewableWMClasses) {
                if (wmClass.contains(unviewableWMClass)) {
                    isUnviewable = true;
                    break;
                }
            }
        }

        if (!isUnviewable) {
            renderableWindows.add(new RenderableWindow(window.getContent(), x, y));
        }

        for (Window child : window.getChildren()) {
            collectRenderableWindows(child, child.getX() + x, child.getY() + y);
        }
    }

    @Override
    public void onMapWindow(Window window) {
        xServerView.queueEvent(this::updateScene);
        xServerView.requestRender();
    }

    @Override
    public void onUnmapWindow(Window window) {
        xServerView.queueEvent(this::updateScene);
        xServerView.requestRender();
    }

    @Override
    public void onChangeWindowZOrder(Window window) {
        xServerView.queueEvent(this::updateScene);
        xServerView.requestRender();
    }

    @Override
    public void onUpdateWindowContent(Window window) {
        ApexNativeBridge.nativeOnFrameCaptured(true);
        xServerView.requestRender();
    }

    @Override
    public void onUpdateWindowGeometry(Window window, boolean resized) {
        xServerView.queueEvent(this::updateScene);
        xServerView.requestRender();
    }

    @Override
    public void onUpdateWindowAttributes(Window window, Bitmask mask) {
        if (mask.isSet(WindowAttributes.FLAG_CURSOR)) {
            xServerView.requestRender();
        }
    }

    @Override
    public void onPointerMove(short x, short y) {
        xServerView.requestRender();
    }

    private void renderCursor() {
        cursorMaterial.use();
        GLES20.glUniform2f(cursorMaterial.getUniformLocation("viewSize"), xServer.screenInfo.width, xServer.screenInfo.height);

        Window pointWindow = xServer.inputDeviceManager.getPointWindow();
        Cursor cursor = pointWindow != null ? pointWindow.attributes.getCursor() : null;
        short x = xServer.pointer.getClampedX();
        short y = xServer.pointer.getClampedY();

        if (cursor != null) {
            if (cursor.isVisible()) renderDrawable(cursor.cursorImage, x - cursor.hotSpotX, y - cursor.hotSpotY, cursorMaterial);
        } else {
            renderDrawable(rootCursorDrawable, x, y, cursorMaterial);
        }
    }

    public void setCursorVisible(boolean cursorVisible) {
        this.cursorVisible = cursorVisible;
        xServerView.requestRender();
    }

    public void setScreenOffsetYRelativeToCursor(boolean screenOffsetYRelativeToCursor) {
        this.screenOffsetYRelativeToCursor = screenOffsetYRelativeToCursor;
        xServerView.requestRender();
    }

    public float getMagnifierZoom() {
        return magnifierZoom;
    }

    public void setMagnifierZoom(float magnifierZoom) {
        this.magnifierZoom = magnifierZoom;
        xServerView.requestRender();
    }

    public void setMagnifierEnabled(boolean magnifierEnabled) {
        this.magnifierEnabled = magnifierEnabled;
        xServerView.requestRender();
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

            if (ApexNativeBridge.nativeIsActive()) {
                int realFPS = ApexNativeBridge.nativeGetRealFPS();
                int genFPS = ApexNativeBridge.nativeGetGenFPS();
                float realRate = realFPS / delta;
                float genRate = genFPS / delta;
                displayTotalFPS = realRate + genRate;
                
                float liveMultiplier = 1.0f;
                if (realRate > 0.5f) {
                    liveMultiplier = Math.max(1.0f, displayTotalFPS / realRate);
                } else if (displayTotalFPS > 0.5f) {
                    liveMultiplier = 2.0f;
                }

                if (winlatorHUD != null) {
                    winlatorHUD.setApexStats(displayTotalFPS, liveMultiplier, true);
                }
            } else {
                displayTotalFPS = regularFrameCount / delta;
                regularFrameCount = 0;
                if (winlatorHUD != null) {
                    winlatorHUD.setApexStats(displayTotalFPS, 1.0f, false);
                }
            }

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
        if (ApexNativeBridge.nativeIsActive()) {
            xServerView.requestRender();
        }
        android.view.Choreographer.getInstance().postFrameCallback(this);
    }

    public void setRenderCursorEnabled(boolean enabled) {
        this.renderCursorEnabled = enabled;
    }

    public void drawCursorExplicitly() {
        if (cursorVisible) {
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            renderCursor();
        }
    }
}
