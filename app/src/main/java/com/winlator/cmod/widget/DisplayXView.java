package com.winlator.cmod.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.winlator.cmod.R;
import com.winlator.cmod.renderer.GPUImage;
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

public class DisplayXView extends SurfaceView implements SurfaceHolder.Callback, WindowManager.OnWindowModificationListener, Pointer.OnPointerMotionListener, CursorManager.OnCursorModificationListener {
    private final XServer xServer;
    private final Context context;
    private boolean fullscreen = false;
    private String unviewableWMClass = null;
    private boolean screenOffsetYRelativeToCursor = false;
    private float magnifierZoom = 1.0f;
    private boolean cursorVisible = true;
    private final HashSet<Long> registeredDirectContents = new HashSet<>();

    public DisplayXView(Context context, XServer xServer) {
        super(context);
        this.xServer = xServer;
        this.context = context;
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        getHolder().addCallback(this);
        xServer.windowManager.addOnWindowModificationListener(this);
        xServer.pointer.addOnPointerMotionListener(this);
        xServer.cursorManager.addOnCursorModificationListener(this);
        nativeInit(this.context, xServer);
    }

    public void presentWindow(Window window, Drawable drawable) {
        if (window != null && drawable != null) {
            addDirectContent(window, drawable);
            nativeUpdateDirectContent(window.id, drawable.id);
        }
    }

    public void addDirectContent(Window window, Drawable drawable) {
        if (window == null || drawable == null) return;
        long key = directContentKey(window.id, drawable.id);
        if (registeredDirectContents.add(key)) {
            nativeAddDirectContent(window.id, drawable);
        }
    }

    public void removeDirectContent(Window window, int drawableId) {
        if (window == null) return;
        if (registeredDirectContents.remove(directContentKey(window.id, drawableId))) {
            nativeRemoveDirectContent(window.id, drawableId);
        }
    }

    private static long directContentKey(int windowId, int drawableId) {
        return ((long) windowId << 32) | (drawableId & 0xffffffffL);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        nativeCreateSurface(getHolder().getSurface());
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

    public void toggleFullscreen() {
        fullscreen = !fullscreen;
        nativeToggleFullscreen();
    }

    public void setCursorVisible(boolean cursorVisible) {
        this.cursorVisible = cursorVisible;
        nativeSetCursorVisible(cursorVisible);
    }

    public void setScreenOffsetYRelativeToCursor(boolean screenOffsetYRelativeToCursor) {
        this.screenOffsetYRelativeToCursor = screenOffsetYRelativeToCursor;
        nativeSetScreenOffsetYRelativeToCursor(screenOffsetYRelativeToCursor);
    }

    public boolean isFullscreen() {
        return fullscreen;
    }

    public float getMagnifierZoom() {
        return magnifierZoom;
    }

    public void setMagnifierZoom(float magnifierZoom) {
        this.magnifierZoom = magnifierZoom;
        nativeSetMagnifierZoom(magnifierZoom);
    }

    public void setUnviewableWMClass(String unviewableWMName) {
        this.unviewableWMClass = unviewableWMName;
        nativeSetUnviewableWMClass(this.unviewableWMClass);
    }

    @Override
    public void onCreateWindow(Window window, Window parent) {
        nativeCreateWindow(window, parent != null ? parent.id : -1);
    }

    @Override
    public void onDestroyWindow(Window window) {
        registeredDirectContents.removeIf(key -> (int) (key >> 32) == window.id);
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
        presentWindow(window, drawable);
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
        nativeReparentWindow(window.id, newParent != null ? newParent.id : -1);
    }

    @Override
    public void onPointerMove(short x, short y) {
        nativePointerMove(x, y);
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
    public native void nativeCreateSurface(Surface surface);
    @FastNative
    public native void nativeDestroySurface();
    @FastNative
    public native void nativeInit(Context context, XServer xserver);
    @FastNative
    public native void nativeChangeSurface(int width, int height);
    @FastNative
    public native void nativeCreateWindow(Window window, int parentId);
    @FastNative
    public native void nativeDestroyWindow(int id);
    @FastNative
    public native void nativeCreateCursor(Cursor cursor);
    @FastNative
    public native void nativeFreeCursor(int id);
    @FastNative
    public native void nativeBindCursor(int windowId, int cursorId, boolean visible);
    @FastNative
    public native void nativeMapWindow(int id);
    @FastNative
    public native void nativeUnmapWindow(int id);
    @FastNative
    public native void nativeChangeWindowZOrder(int stackMode, int id, int siblingId);
    @FastNative
    public native void nativeUpdateWindowGeometry(int id, int width, int height, int x, int y, boolean resized);
    @FastNative
    public native void nativePointerMove(int x, int y);
    @FastNative
    public native void nativeToggleFullscreen();
    @FastNative
    public native void nativeSetCursorVisible(boolean visible);
    @FastNative
    public native void nativeSetScreenOffsetYRelativeToCursor(boolean cond);
    @FastNative
    public native void nativeSetMagnifierZoom(float magnifierZoom);
    @FastNative
    public native void nativeSetUnviewableWMClass(String unviewableWMName);
    @FastNative
    public native void nativeSetWindowClassName(int id, String className);
    @FastNative
    public native void nativeUpdatePointWindow(int id);
    @FastNative
    public native void nativeUpdateWindowContent(int id);
    @FastNative
    public native void nativeReparentWindow(int id, int parentId);
    @FastNative
    public native void nativePause();
    @FastNative
    public native void nativeResume();
    @FastNative
    public native void nativeStop();
    @FastNative
    public native void nativeAddDirectContent(int windowId, Drawable drawable);
    @FastNative
    public native void nativeUpdateDirectContent(int windowId, int drawableId);
    @FastNative
    public native void nativeRemoveDirectContent(int windowId, int pixmapId);
}
