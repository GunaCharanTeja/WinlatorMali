package com.winlator.cmod.win32;

import android.graphics.Bitmap;
import android.graphics.Color;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public abstract class MSBitmap {
    public static Bitmap decodeBuffer(int width, int height, int bitCount, ByteBuffer data) {
        int height2;
        ByteBuffer byteBuffer = data;
        if (width == 0 || height == 0) {
            return null;
        }
        boolean invertY = true;
        if (height >= 0) {
            height2 = height;
        } else {
            height2 = height * (-1);
            invertY = false;
        }
        ByteBuffer pixels = ByteBuffer.allocate(width * height2 * 4);
        if (bitCount == 32) {
            int i = data.position();
            for (int y = height2 - 1; y >= 0; y--) {
                int line = invertY ? y : (height2 - 1) - y;
                int x = 0;
                while (x < width) {
                    int i2 = i + 1;
                    byte b = byteBuffer.get(i);
                    int i3 = i2 + 1;
                    byte g = byteBuffer.get(i2);
                    int i4 = i3 + 1;
                    byte r = byteBuffer.get(i3);
                    int i5 = i4 + 1;
                    byte a = byteBuffer.get(i4);
                    int j = (line * width * 4) + (x * 4);
                    pixels.put(j + 2, b);
                    pixels.put(j + 1, g);
                    pixels.put(j + 0, r);
                    pixels.put(j + 3, a);
                    x++;
                    i = i5;
                }
                i += width % 4;
            }
        } else {
            byte r2 = -1;
            if (bitCount == 24) {
                int i6 = data.position();
                for (int y2 = height2 - 1; y2 >= 0; y2--) {
                    int line2 = invertY ? y2 : (height2 - 1) - y2;
                    int x2 = 0;
                    while (x2 < width) {
                        int i7 = i6 + 1;
                        byte b2 = byteBuffer.get(i6);
                        int i8 = i7 + 1;
                        byte g2 = byteBuffer.get(i7);
                        int i9 = i8 + 1;
                        byte r3 = byteBuffer.get(i8);
                        int j2 = (line2 * width * 4) + (x2 * 4);
                        pixels.put(j2 + 2, b2);
                        pixels.put(j2 + 1, g2);
                        pixels.put(j2 + 0, r3);
                        pixels.put(j2 + 3, (byte) -1);
                        x2++;
                        i6 = i9;
                    }
                    i6 += width % 4;
                }
            } else if (bitCount <= 8) {
                int colorTableOffset = data.position();
                int colorTableSize = (int) (Math.pow(2.0d, bitCount) * 4.0d);
                int y3 = height2 - 1;
                int colorIndex = data.position() + colorTableSize;
                while (y3 >= 0) {
                    int line3 = invertY ? y3 : (height2 - 1) - y3;
                    int x3 = 0;
                    while (x3 < width) {
                        int i10 = colorIndex + 1;
                        int colorIndex2 = Byte.toUnsignedInt(byteBuffer.get(colorIndex)) * 4;
                        byte b3 = byteBuffer.get(colorTableOffset + colorIndex2 + 0);
                        byte g3 = byteBuffer.get(colorTableOffset + colorIndex2 + 1);
                        byte r4 = byteBuffer.get(colorTableOffset + colorIndex2 + 2);
                        int j3 = (line3 * width * 4) + (x3 * 4);
                        pixels.put(j3 + 2, b3);
                        pixels.put(j3 + 1, g3);
                        pixels.put(j3 + 0, r4);
                        pixels.put(j3 + 3, (byte) -1);
                        x3++;
                        r2 = -1;
                        colorIndex = i10;
                        byteBuffer = data;
                    }
                    colorIndex += width % 4;
                    y3--;
                    byteBuffer = data;
                }
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height2, Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(pixels);
        return bitmap;
    }

    public static boolean create(Bitmap bitmap, File outputFile) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        int extraBytes = width % 4;
        int imageSize = height * ((width * 3) + extraBytes);
        int infoHeaderSize = 40;
        ByteBuffer buffer = ByteBuffer.allocate(54 + imageSize).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort((short) 19778);
        buffer.putInt(54 + imageSize);
        buffer.putInt(0);
        buffer.putInt(54);
        buffer.putInt(40);
        buffer.putInt(width);
        buffer.putInt(height);
        buffer.putShort((short) 1);
        buffer.putShort((short) 24);
        buffer.putInt(0);
        buffer.putInt(imageSize);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        int rowBytes = (width * 3) + extraBytes;
        int y = height - 1;
        int i = 0;
        while (y >= 0) {
            int x = 0;
            while (x < width) {
                int j = 54 + (y * rowBytes) + (x * 3);
                int i2 = i + 1;
                int pixel = pixels[i];
                int infoHeaderSize2 = infoHeaderSize;
                int infoHeaderSize3 = Color.blue(pixel);
                buffer.put(j + 0, (byte) infoHeaderSize3);
                buffer.put(j + 1, (byte) Color.green(pixel));
                buffer.put(j + 2, (byte) Color.red(pixel));
                x++;
                i = i2;
                imageSize = imageSize;
                infoHeaderSize = infoHeaderSize2;
            }
            int imageSize2 = imageSize;
            int infoHeaderSize4 = infoHeaderSize;
            if (extraBytes > 0) {
                int fillOffset = (y * rowBytes) + 54 + (width * 3);
                for (int j2 = fillOffset; j2 < fillOffset + extraBytes; j2++) {
                    buffer.put(j2, (byte) -1);
                }
            }
            y--;
            imageSize = imageSize2;
            infoHeaderSize = infoHeaderSize4;
        }
        try {
            FileOutputStream fos = new FileOutputStream(outputFile);
            try {
                fos.write(buffer.array());
                try {
                    fos.close();
                    return true;
                } catch (IOException e) {
                    return false;
                }
            } catch (Throwable th) {
                try {
                    try {
                        fos.close();
                        throw th;
                    } catch (Throwable th2) {
                        th.addSuppressed(th2);
                        throw th;
                    }
                } catch (IOException e2) {
                    return false;
                }
            }
        } catch (IOException e3) {
            return false;
        }
    }
}
