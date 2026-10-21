package com.winlator.cmod.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.winlator.cmod.xserver.Bitmask;
import com.winlator.cmod.xserver.Cursor;
import com.winlator.cmod.xserver.CursorManager;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.Property;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.WindowAttributes;
import com.winlator.cmod.xserver.WindowManager;
import com.winlator.cmod.xserver.XServer;

import java.util.HashSet;

import dalvik.annotation.optimization.FastNative;

/** Surface for the native EGL renderer: composites X windows and applies the post-processing chain. */
@SuppressLint("ViewConstructor")
public class XServerView extends SurfaceView implements SurfaceHolder.Callback, WindowManager.OnWindowModificationListener, Pointer.OnPointerMotionListener, CursorManager.OnCursorModificationListener {
    private static final long MIN_POINTER_MOVE_INTERVAL_NS = 8_000_000L; // ~125 Hz max pointer update rate
    private final XServer xServer;
    private boolean fullscreen = false;
    private String unviewableWMClass = null;
    private float magnifierZoom = 1.0f;
    private boolean cursorVisible = true;
    private String postProcessing = "";
    private final HashSet<Long> registeredDirectContents = new HashSet<>();
    private long lastPointerMoveTimeNs = 0;

    public XServerView(Context context, XServer xServer) {
        super(context);
        this.xServer = xServer;
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        getHolder().addCallback(this);
        xServer.windowManager.addOnWindowModificationListener(this);
        xServer.pointer.addOnPointerMotionListener(this);
        xServer.cursorManager.addOnCursorModificationListener(this);
        nativeInit(context, xServer);
    }

    public void addDirectContent(Window window, Drawable drawable) {
        if (window == null || drawable == null) return;
        long key = directContentKey(window.id, drawable.id);
        synchronized (registeredDirectContents) {
            if (!registeredDirectContents.add(key)) return;
        }
        nativeAddDirectContent(window.id, drawable);
    }

    public void removeDirectContent(Window window, int drawableId) {
        if (window == null) return;
        synchronized (registeredDirectContents) {
            if (!registeredDirectContents.remove(directContentKey(window.id, drawableId))) return;
        }
        nativeRemoveDirectContent(window.id, drawableId);
    }

    private static long directContentKey(int windowId, int drawableId) {
        return ((long) windowId << 32) | (drawableId & 0xffffffffL);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        nativeCreateSurface(holder.getSurface());
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        nativeDestroySurface();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        nativeChangeSurface(width, height);
    }

    public void onDestroy() {
        xServer.windowManager.removeOnWindowModificationListener(this);
        xServer.pointer.removeOnPointerMotionListener(this);
        xServer.cursorManager.removeOnCursorModificationListener(this);
        nativeStop();
    }

    public void onPause() {
        nativePause();
    }

    public void onResume() {
        nativeResume();
    }

    public void requestRender() {
        nativeRequestRender();
    }

    public void toggleFullscreen() {
        fullscreen = !fullscreen;
        nativeToggleFullscreen();
    }

    public boolean isFullscreen() {
        return fullscreen;
    }

    public void setCursorVisible(boolean cursorVisible) {
        this.cursorVisible = cursorVisible;
        nativeSetCursorVisible(cursorVisible);
    }

    public void setScreenOffsetYRelativeToCursor(boolean screenOffsetYRelativeToCursor) {
        nativeSetScreenOffsetYRelativeToCursor(screenOffsetYRelativeToCursor);
    }

    public float getMagnifierZoom() {
        return magnifierZoom;
    }

    public void setMagnifierZoom(float magnifierZoom) {
        this.magnifierZoom = magnifierZoom;
        nativeSetMagnifierZoom(magnifierZoom);
    }

    public void setUnviewableWMClass(String unviewableWMClass) {
        this.unviewableWMClass = unviewableWMClass;
        nativeSetUnviewableWMClass(unviewableWMClass);
    }

    /** Post-processing config as a KeyValueSet string, see {@link com.winlator.cmod.renderer.PostProcessing}. */
    public void setPostProcessing(String config) {
        postProcessing = config != null ? config : "";
        nativeSetPostProcessing(postProcessing);
    }

    public String getPostProcessing() {
        return postProcessing;
    }

    /**
     * Where the game window is drawn when it is fitted to the screen: out = {scale, offsetX, offsetY},
     * X screen point = game point * scale + offset. Returns false while no window is fitted.
     */
    public boolean getFitMapping(float[] out) {
        return nativeGetFitMapping(out);
    }

    @Override
    public void onCreateWindow(Window window, Window parent) {
        nativeCreateWindow(window, parent != null ? parent.id : -1);
    }

    @Override
    public void onDestroyWindow(Window window) {
        synchronized (registeredDirectContents) {
            registeredDirectContents.removeIf(key -> (int) (key >> 32) == window.id);
        }
        nativeDestroyWindow(window.id);
    }

    @Override
    public void onMapWindow(Window window) {
        if (unviewableWMClass != null) {
            String wmClass = window.getClassName();
            if (wmClass.contains(unviewableWMClass)) {
                if (window.attributes.isEnabled()) {
                    window.disableAllDescendants();
                }
            }
        }
        nativeMapWindow(window.id);
    }

    @Override
    public void onUnmapWindow(Window window) {
        nativeUnmapWindow(window.id);
    }

    @Override
    public void onChangeWindowZOrder(Window.StackMode stackMode, Window window, Window sibling) {
        nativeChangeWindowZOrder(stackMode == Window.StackMode.ABOVE ? 1 : 0, window.id, (sibling != null) ? sibling.id : -1);
    }

    @Override
    public void onUpdateWindowContent(Window window) {
        nativeUpdateWindowContent(window.id);
    }

    @Override
    public void onUpdateWindowContentDirect(Window window, Drawable drawable) {
        if (window == null || drawable == null) return;
        addDirectContent(window, drawable);
        nativeUpdateDirectContent(window.id, drawable.id);
    }

    @Override
    public void onUpdateWindowGeometry(final Window window, boolean resized) {
        nativeUpdateWindowGeometry(window.id, window.getWidth(), window.getHeight(), window.getX(), window.getY(), resized);
    }

    @Override
    public void onUpdateWindowAttributes(Window window, Bitmask mask) {
        if (mask.isSet(WindowAttributes.FLAG_CURSOR)) {
            Cursor cursor = window.attributes.getCursor();
            if (cursor != null)
                nativeBindCursor(window.id, cursor.id, cursor.isVisible());
        }
    }

    @Override
    public void onModifyWindowProperty(Window window, Property property) {
        if (property.nameAsString().equals("WM_CLASS"))
            nativeSetWindowClassName(window.id, property.toString());
    }

    @Override
    public void onReparentWindow(Window window, Window newParent) {
        if (newParent != null) nativeReparentWindow(window.id, newParent.id, window.getX(), window.getY());
    }

    @Override
    public void onPointerMove(short x, short y) {
        // In relative mouse mode (games) or when the cursor is hidden, skip pointer updates so
        // high-polling-rate mice (500-1000 Hz) don't flood the JNI and render threads.
        if (xServer.isRelativeMouseMovement() || !cursorVisible) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastPointerMoveTimeNs >= MIN_POINTER_MOVE_INTERVAL_NS) {
            lastPointerMoveTimeNs = now;
            nativePointerMove(x, y);
        }
    }

    @Override
    public void onCreateCursor(Cursor cursor) {
        nativeCreateCursor(cursor);
    }

    @Override
    public void onFreeCursor(Cursor cursor) {
        nativeFreeCursor(cursor.id);
    }

    static {
        System.loadLibrary("winlator");
    }

    @FastNative
    private native void nativeCreateSurface(Surface surface);
    @FastNative
    private native void nativeDestroySurface();
    @FastNative
    private native void nativeInit(Context context, XServer xserver);
    @FastNative
    private native void nativeChangeSurface(int width, int height);
    @FastNative
    private native void nativeCreateWindow(Window window, int parentId);
    @FastNative
    private native void nativeDestroyWindow(int id);
    @FastNative
    private native void nativeCreateCursor(Cursor cursor);
    @FastNative
    private native void nativeFreeCursor(int id);
    @FastNative
    private native void nativeBindCursor(int windowId, int cursorId, boolean visible);
    @FastNative
    private native void nativeMapWindow(int id);
    @FastNative
    private native void nativeUnmapWindow(int id);
    @FastNative
    private native void nativeChangeWindowZOrder(int stackMode, int id, int siblingId);
    @FastNative
    private native void nativeUpdateWindowGeometry(int id, int width, int height, int x, int y, boolean resized);
    @FastNative
    private native void nativePointerMove(int x, int y);
    @FastNative
    private native void nativeToggleFullscreen();
    @FastNative
    private native void nativeSetCursorVisible(boolean visible);
    @FastNative
    private native void nativeSetScreenOffsetYRelativeToCursor(boolean cond);
    @FastNative
    private native void nativeSetMagnifierZoom(float magnifierZoom);
    @FastNative
    private native void nativeSetUnviewableWMClass(String unviewableWMName);
    @FastNative
    private native void nativeSetWindowClassName(int id, String className);
    @FastNative
    private native void nativeSetPostProcessing(String config);
    @FastNative
    private native void nativeRequestRender();

    /** Frame generation: {multiplier (0 = off), game fps, shown fps, ms per generated frame}. */
    public static native float[] nativeGetFrameGenStats();

    private native boolean nativeGetFitMapping(float[] out);
    @FastNative
    private native void nativeUpdateWindowContent(int id);
    @FastNative
    private native void nativeReparentWindow(int id, int parentId, short x, short y);
    @FastNative
    private native void nativePause();
    @FastNative
    private native void nativeResume();
    @FastNative
    private native void nativeStop();
    @FastNative
    private native void nativeAddDirectContent(int windowId, Drawable drawable);
    @FastNative
    private native void nativeUpdateDirectContent(int windowId, int drawableId);
    @FastNative
    private native void nativeRemoveDirectContent(int windowId, int pixmapId);
}
