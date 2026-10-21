package com.winlator.cmod.xserver;

import android.util.SparseArray;

import com.winlator.cmod.core.Callback;
import com.winlator.cmod.renderer.GPUImage;

public class DrawableManager extends XResourceManager implements XResourceManager.OnResourceLifecycleListener {
    private final XServer xServer;
    private final SparseArray<Drawable> drawables = new SparseArray<>();

    public DrawableManager(XServer xServer) {
        this.xServer = xServer;
        xServer.pixmapManager.addOnResourceLifecycleListener(this);
    }

    public Drawable getDrawable(int id) {
        return drawables.get(id);
    }

    public Drawable createDrawable(int id, short width, short height, byte depth) {
        return createDrawable(id, width, height, xServer.pixmapManager.getVisualForDepth(depth));
    }

    public Drawable createDrawable(int id, short width, short height, Visual visual) {
        if (id == 0) return new Drawable(id, width, height, visual);
        if (drawables.indexOfKey(id) >= 0) return null;
        Drawable drawable = new Drawable(id, width, height, visual);
        drawables.put(id, drawable);
        return drawable;
    }

    public void removeDrawable(int id) {
        Drawable drawable = drawables.get(id);
        if (drawable == null) return;

        Callback<Drawable> onDestroyListener = drawable.getOnDestroyListener();
        if (onDestroyListener != null) onDestroyListener.call(drawable);

        // The renderer holds its own EGLImage reference, so the client buffer can be released right away.
        GPUImage gpuImage = drawable.getGPUImage();
        if (gpuImage != null) gpuImage.destroy();

        drawable.setOnDrawListener(null);
        drawables.remove(id);
    }

    @Override
    public void onFreeResource(XResource resource) {
        if (resource instanceof Pixmap) {
            Pixmap pixmap = (Pixmap) resource;
            removeDrawable(pixmap.drawable.id);
        }
    }

    public Visual getVisual() {
        return xServer.pixmapManager.visual;
    }
}
