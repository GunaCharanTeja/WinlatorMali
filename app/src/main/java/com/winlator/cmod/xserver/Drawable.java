package com.winlator.cmod.xserver;

import android.graphics.Bitmap;

import com.winlator.cmod.core.Callback;
import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.renderer.GPUImage;
import com.winlator.cmod.renderer.Texture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class Drawable extends XResource {
    public final short width;
    public final short height;
    public final Visual visual;
    public long backingAHB = 0;
    public short stride = 0;
    public int format = 5;
    private Texture texture = new Texture();
    private ByteBuffer data;
    private Runnable onDrawListener;
    private Callback<Drawable> onDestroyListener;
    public final Object renderLock = new Object();
    private boolean directScanout = false;

    public boolean isDirectScanout() {
        return directScanout;
    }

    public void setDirectScanout(boolean directScanout) {
        this.directScanout = directScanout;
    }

    static {
        System.loadLibrary("winlator");
    }

    public Drawable(int id, int width, int height, Visual visual) {
        this(id, width, height, visual, 5);
    }

    public Drawable(int id, int width, int height, Visual visual, int format) {
        super(id);
        this.width = (short)width;
        this.height = (short)height;
        this.visual = visual;
        this.format = format;
        this.backingAHB = allocate(width, height, format);
        if (this.backingAHB != 0) {
            this.data = lockBuffer(this.backingAHB);
        }
        if (this.data == null) {
            this.stride = (short)width;
            this.data = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN);
        }
    }

    public static Drawable fromBitmap(Bitmap bitmap) {
        Drawable drawable = new Drawable(0, bitmap.getWidth(), bitmap.getHeight(), null);
        fromBitmap(bitmap, drawable.data);
        return drawable;
    }

    public Texture getTexture() {
        return texture;
    }

    public void setTexture(Texture texture) {
        if (texture instanceof GPUImage) {
            GPUImage gpuImage = (GPUImage) texture;
            data = gpuImage.getVirtualData();
            backingAHB = gpuImage.hardwareBufferPtr;
            stride = gpuImage.getStride();
            format = gpuImage.format;
        }
        this.texture = texture;
    }

    public void setGPUImage(GPUImage texture) {
        setTexture(texture);
    }

    public GPUImage getGPUImage() {
        return texture instanceof GPUImage ? (GPUImage) texture : null;
    }

    public ByteBuffer getData() {
        return data;
    }

    public void setData(ByteBuffer data) {
        if (data == null) {
            throw new IllegalArgumentException("Attempting to set Drawable.data to null!");
        }
        this.data = data;
    }

    public short getStride() {
        return texture instanceof GPUImage ? ((GPUImage)texture).getStride() : (stride != 0 ? stride : width);
    }

    public Runnable getOnDrawListener() {
        return onDrawListener;
    }

    public void setOnDrawListener(Runnable onDrawListener) {
        this.onDrawListener = onDrawListener;
    }

    public void updateDirect() {
        if (onDrawListener != null) onDrawListener.run();
    }

    public Callback<Drawable> getOnDestroyListener() {
        return onDestroyListener;
    }

    public void setOnDestroyListener(Callback<Drawable> onDestroyListener) {
        this.onDestroyListener = onDestroyListener;
    }

    public void drawImage(short srcX, short srcY, short dstX, short dstY, short width, short height, byte depth, ByteBuffer data, short totalWidth, short totalHeight) {
        if (depth == 1) {
            drawBitmap(width, height, data, this.getStride(), this.data);
        }
        else if (depth == 24 || depth == 32) {
            dstX = (short)Mathf.clamp(dstX, 0, this.width-1);
            dstY = (short)Mathf.clamp(dstY, 0, this.height-1);
            if ((dstX + width) > this.width) width = (short)((this.width - dstX));
            if ((dstY + height) > this.height) height = (short)((this.height - dstY));

            copyArea(srcX, srcY, dstX, dstY, width, height, totalWidth, this.getStride(), data, this.data);
        }

        data.rewind();
        this.data.rewind();

        texture.setNeedsUpdate(true);
        if (onDrawListener != null) onDrawListener.run();
    }

    public ByteBuffer getImage(short x, short y, short width, short height) {
        ByteBuffer dstData = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN);

        x = (short)Mathf.clamp(x, 0, this.width-1);
        y = (short)Mathf.clamp(y, 0, this.height-1);
        if ((x + width) > this.width) width = (short)(this.width - x);
        if ((y + height) > this.height) height = (short)(this.height - y);

        copyArea(x, y, (short)0, (short)0, width, height, this.getStride(), width, this.data, dstData);

        dstData.rewind();
        this.data.rewind();
        return dstData;
    }

    public void copyArea(short srcX, short srcY, short dstX, short dstY, short width, short height, Drawable drawable) {
        copyArea(srcX, srcY, dstX, dstY, width, height, drawable, GraphicsContext.Function.COPY);
    }

    public void copyArea(short srcX, short srcY, short dstX, short dstY, short width, short height, Drawable drawable, GraphicsContext.Function gcFunction) {
        dstX = (short)Mathf.clamp(dstX, 0, this.width-1);
        dstY = (short)Mathf.clamp(dstY, 0, this.height-1);
        if ((dstX + width) > this.width) width = (short)(this.width - dstX);
        if ((dstY + height) > this.height) height = (short)(this.height - dstY);

        if (gcFunction == GraphicsContext.Function.COPY) {
            copyArea(srcX, srcY, dstX, dstY, width, height, drawable.getStride(), this.getStride(), drawable.data, this.data);
        }
        else copyAreaOp(srcX, srcY, dstX, dstY, width, height, drawable.getStride(), this.getStride(), drawable.data, this.data, gcFunction.ordinal());

        drawable.data.rewind();
        this.data.rewind();

        texture.setNeedsUpdate(true);
        if (onDrawListener != null) onDrawListener.run();
    }

    public void fillColor(int color) {
        fillRect(0, 0, width, height, color);
    }

    public void fillRect(int x, int y, int width, int height, int color) {
        x = (short)Mathf.clamp(x, 0, this.width-1);
        y = (short)Mathf.clamp(y, 0, this.height-1);
        if ((x + width) > this.width) width = (short)((this.width - x));
        if ((y + height) > this.height) height = (short)((this.height - y));

        fillRect((short)x, (short)y, (short)width, (short)height, color, this.getStride(), this.data);
        this.data.rewind();

        texture.setNeedsUpdate(true);
        if (onDrawListener != null) onDrawListener.run();
    }

    public void drawLines(int color, int lineWidth, short... points) {
        for (int i = 2; i < points.length; i += 2) {
            drawLine(points[i-2], points[i-1], points[i+0], points[i+1], color, (short)lineWidth);
        }
    }

    public void drawLine(int x0, int y0, int x1, int y1, int color, int lineWidth) {
        x0 = Mathf.clamp(x0, 0, width-lineWidth);
        y0 = Mathf.clamp(y0, 0, height-lineWidth);
        x1 = Mathf.clamp(x1, 0, width-lineWidth);
        y1 = Mathf.clamp(y1, 0, height-lineWidth);

        drawLine((short)x0, (short)y0, (short)x1, (short)y1, color, (short)lineWidth, this.getStride(), this.data);

        this.data.rewind();

        texture.setNeedsUpdate(true);
        if (onDrawListener != null) onDrawListener.run();
    }

    public void drawAlphaMaskedBitmap(byte foreRed, byte foreGreen, byte foreBlue, byte backRed, byte backGreen, byte backBlue, Drawable srcDrawable, Drawable maskDrawable) {
        drawAlphaMaskedBitmap(foreRed, foreGreen, foreBlue, backRed, backGreen, backBlue, srcDrawable.data, srcDrawable.getStride(), maskDrawable.data, maskDrawable.getStride(), this.width, this.height, this.getStride(), this.data);
        this.data.rewind();

        texture.setNeedsUpdate(true);
        if (onDrawListener != null) onDrawListener.run();
    }

    private static native void drawBitmap(short width, short height, ByteBuffer srcData, short dstStride, ByteBuffer dstData);

    private static native void drawAlphaMaskedBitmap(byte foreRed, byte foreGreen, byte foreBlue, byte backRed, byte backGreen, byte backBlue, ByteBuffer srcData, short srcStride, ByteBuffer maskData, short maskStride, short width, short height, short dstStride, ByteBuffer dstData);

    private static native void copyArea(short srcX, short srcY, short dstX, short dstY, short width, short height, short srcStride, short dstStride, ByteBuffer srcData, ByteBuffer dstData);

    private static native void copyAreaOp(short srcX, short srcY, short dstX, short dstY, short width, short height, short srcStride, short dstStride, ByteBuffer srcData, ByteBuffer dstData, int gcFunction);

    private static native void fillRect(short x, short y, short width, short height, int color, short stride, ByteBuffer data);

    private static native void drawLine(short x0, short y0, short x1, short y1, int color, short lineWidth, short stride, ByteBuffer data);

    private static native void fromBitmap(Bitmap bitmap, ByteBuffer data);

    private native long allocate(int width, int height, int format);

    public native ByteBuffer lockBuffer(long ahb);

    public native void unlockBuffer(long ahb);
}
