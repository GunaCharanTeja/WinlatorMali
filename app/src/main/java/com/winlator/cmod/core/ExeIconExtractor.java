package com.winlator.cmod.core;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ExeIconExtractor {

    private static final String TAG = "ExeIconExtractor";

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    public static final int ICON_SIZE    = 512;
    public static final int COVER_WIDTH  = 800;
    public static final int COVER_HEIGHT = 1200;

    public static boolean extractIcon(File exeFile, File destinationFile) {
        return extractAndSave(exeFile, destinationFile, false);
    }

    public static boolean extractCover(File exeFile, File destinationFile) {
        return extractAndSave(exeFile, destinationFile, true);
    }

    public static void extractAsync(File exeFile, File destinationFile, boolean isCover, Runnable onComplete) {
        executor.submit(() -> {
            boolean ok = extractAndSave(exeFile, destinationFile, isCover);
            if (ok && onComplete != null) onComplete.run();
        });
    }

    public static Bitmap extractBitmap(File exeFile) {
        try {
            Bitmap raw = PeIconExtractor.extract(exeFile);
            if (raw != null) {
                return upscaleIfNecessary(raw, ICON_SIZE);
            }
        } catch (Exception e) {
            Log.e(TAG, "[extractBitmap] Unexpected exception for '" + exeFile.getName() + "': " + e.getMessage(), e);
        }
        return null;
    }

    /**
     * High-quality progressive step-doubling upscaler.
     * Uses bilinear interpolation across successive 2x steps, maintaining clean edges.
     */
    public static Bitmap upscaleIfNecessary(Bitmap src, int targetSize) {
        if (src == null) return null;
        int w = src.getWidth();
        int h = src.getHeight();
        if (w >= targetSize && h >= targetSize) {
            return src;
        }

        int curW = w;
        int curH = h;
        Bitmap current = src;

        // Progressive 2x stepping
        while (curW * 2 <= targetSize && curH * 2 <= targetSize) {
            curW *= 2;
            curH *= 2;
            Bitmap next = Bitmap.createBitmap(curW, curH, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(next);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
            canvas.drawBitmap(current, null, new Rect(0, 0, curW, curH), paint);
            if (current != src) current.recycle();
            current = next;
        }

        // Final scale to exact targetSize
        if (curW != targetSize || curH != targetSize) {
            Bitmap finalBmp = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(finalBmp);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
            canvas.drawBitmap(current, null, new Rect(0, 0, targetSize, targetSize), paint);
            if (current != src) current.recycle();
            return finalBmp;
        }

        return current;
    }

    private static boolean extractAndSave(File exeFile, File destinationFile, boolean isCover) {
        String label = isCover ? "cover" : "icon";

        if (exeFile == null) {
            Log.e(TAG, "[extractAndSave:" + label + "] exeFile is null");
            return false;
        }
        if (!exeFile.exists()) {
            Log.e(TAG, "[extractAndSave:" + label + "] File not found: " + exeFile.getAbsolutePath());
            return false;
        }
        if (!exeFile.canRead()) {
            Log.e(TAG, "[extractAndSave:" + label + "] No read permission: " + exeFile.getAbsolutePath());
            return false;
        }

        Log.d(TAG, "[extractAndSave:" + label + "] Starting for: " + exeFile.getName()
                + "  (" + exeFile.length() + " bytes)");

        Bitmap raw;
        try {
            raw = PeIconExtractor.extract(exeFile);
        } catch (Exception e) {
            Log.e(TAG, "[extractAndSave:" + label + "] Exception during PE extraction: " + e.getMessage(), e);
            return false;
        }

        if (raw == null) {
            Log.w(TAG, "[extractAndSave:" + label + "] PE extraction returned null for: " + exeFile.getName()
                    + " — no icon found or format not supported");
            if (!isCover) return false;
            raw = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
            raw.eraseColor(0xFF1A1A2E);
        }

        Log.d(TAG, "[extractAndSave:" + label + "] Raw bitmap obtained: "
                + raw.getWidth() + "x" + raw.getHeight());

        Bitmap result;
        try {
            if (isCover) {
                result = buildCover(raw);
            } else {
                result = upscaleIfNecessary(raw, ICON_SIZE);
            }
        } catch (Exception e) {
            Log.e(TAG, "[extractAndSave:" + label + "] Failed to build final bitmap: " + e.getMessage(), e);
            if (!raw.isRecycled()) raw.recycle();
            return false;
        }

        File parentDir = destinationFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            boolean made = parentDir.mkdirs();
            if (!made) {
                Log.e(TAG, "[extractAndSave:" + label + "] Could not create output dir: "
                        + parentDir.getAbsolutePath());
                if (!raw.isRecycled()) raw.recycle();
                if (result != raw && !result.isRecycled()) result.recycle();
                return false;
            }
        }

        try (FileOutputStream out = new FileOutputStream(destinationFile)) {
            boolean compressed = result.compress(Bitmap.CompressFormat.PNG, 100, out);
            if (!compressed) {
                Log.e(TAG, "[extractAndSave:" + label + "] Bitmap.compress() returned false for: "
                        + destinationFile.getAbsolutePath());
                return false;
            }
            Log.d(TAG, "[extractAndSave:" + label + "] Saved OK -> "
                    + destinationFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "[extractAndSave:" + label + "] Failed to write PNG to: "
                    + destinationFile.getAbsolutePath() + " — " + e.getMessage(), e);
            return false;
        } finally {
            if (!raw.isRecycled()) raw.recycle();
            if (result != raw && !result.isRecycled()) result.recycle();
        }

        return true;
    }

    private static Bitmap buildCover(Bitmap icon) {
        Bitmap cover = Bitmap.createBitmap(COVER_WIDTH, COVER_HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(cover);

        // Painterly background from icon
        Bitmap tiny = Bitmap.createScaledBitmap(icon, 16, 16, true);
        Bitmap bgFill = Bitmap.createScaledBitmap(tiny, COVER_WIDTH, COVER_HEIGHT, true);
        tiny.recycle();

        Paint bgPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        canvas.drawBitmap(bgFill, 0, 0, bgPaint);
        bgFill.recycle();

        canvas.drawColor(0xAA000000);

        RadialGradient vignette = new RadialGradient(
                COVER_WIDTH  / 2f,
                COVER_HEIGHT / 2f,
                Math.max(COVER_WIDTH, COVER_HEIGHT) * 0.72f,
                new int[]{ 0x00000000, 0xAA000000 },
                new float[]{ 0.35f, 1.0f },
                Shader.TileMode.CLAMP);
        Paint vignettePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        vignettePaint.setShader(vignette);
        canvas.drawRect(0, 0, COVER_WIDTH, COVER_HEIGHT, vignettePaint);

        int idealDraw = (int) (Math.min(COVER_WIDTH, COVER_HEIGHT) * 0.72f);
        int srcSize   = Math.max(icon.getWidth(), icon.getHeight());
        int iconDraw  = (srcSize <= 32)
                ? Math.max(idealDraw / 2, (int) (Math.min(COVER_WIDTH, COVER_HEIGHT) * 0.40f))
                : idealDraw;

        int left = (COVER_WIDTH  - iconDraw) / 2;
        int top  = (COVER_HEIGHT - iconDraw) / 2;

        Bitmap drawIcon = upscaleIfNecessary(icon, iconDraw);

        int shadowOff = Math.max(6, iconDraw / 18);
        Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        shadowPaint.setColorFilter(new PorterDuffColorFilter(0xFF000000, PorterDuff.Mode.SRC_ATOP));
        shadowPaint.setAlpha(120);
        canvas.drawBitmap(drawIcon, null,
                new Rect(left + shadowOff, top + shadowOff,
                         left + iconDraw + shadowOff, top + iconDraw + shadowOff),
                shadowPaint);

        Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(drawIcon, null,
                new Rect(left, top, left + iconDraw, top + iconDraw),
                iconPaint);

        if (drawIcon != icon && !drawIcon.isRecycled()) drawIcon.recycle();

        return cover;
    }

    public static class PeIconExtractor {

        public static Bitmap extract(File exeFile) {
            try (RandomAccessFile raf = new RandomAccessFile(exeFile, "r")) {

                int b0 = raf.read(), b1 = raf.read();
                if (b0 != 0x4D || b1 != 0x5A) {
                    Log.w(TAG, "[PE:step1] Not a valid DOS/PE executable (no MZ). Got 0x"
                            + Integer.toHexString(b0) + " 0x" + Integer.toHexString(b1)
                            + " — file: " + exeFile.getName());
                    return null;
                }

                raf.seek(0x3C);
                int peOffset = readLE32(raf);
                Log.d(TAG, "[PE:step2] e_lfanew=0x" + Integer.toHexString(peOffset));

                if (peOffset <= 0 || peOffset >= exeFile.length()) {
                    Log.e(TAG, "[PE:step2] e_lfanew out of bounds: " + peOffset
                            + " (file size=" + exeFile.length() + ")");
                    return null;
                }

                raf.seek(peOffset);
                int p0 = raf.read(), p1 = raf.read(), p2 = raf.read(), p3 = raf.read();
                if (p0 != 0x50 || p1 != 0x45 || p2 != 0 || p3 != 0) {
                    Log.w(TAG, "[PE:step3] Invalid PE signature at 0x"
                            + Integer.toHexString(peOffset));
                    return null;
                }

                raf.skipBytes(2); // Machine
                int numSections   = readLE16(raf);
                raf.skipBytes(12); // TimeDateStamp + PointerToSymbolTable + NumberOfSymbols
                int optHeaderSize = readLE16(raf);
                raf.skipBytes(2); // Characteristics

                long optHeaderStart = raf.getFilePointer();

                int magic = readLE16(raf);
                if (magic != 0x10B && magic != 0x20B) {
                    Log.w(TAG, "[PE:step5] Unsupported PE magic 0x" + Integer.toHexString(magic));
                    return null;
                }

                int ddOffset = (magic == 0x20B) ? 112 : 96;
                raf.seek(optHeaderStart + ddOffset);
                raf.skipBytes(16); // Skip Export & Import directories

                long rsrcRVA  = readLE32(raf) & 0xFFFFFFFFL;
                int  rsrcSize = readLE32(raf);
                Log.d(TAG, "[PE:step6] Resource RVA=0x" + Long.toHexString(rsrcRVA) + " size=" + rsrcSize);

                if (rsrcRVA == 0) {
                    Log.w(TAG, "[PE:step6] rsrcRVA=0 — no resource section");
                    return null;
                }

                long sectionsStart = optHeaderStart + optHeaderSize;
                raf.seek(sectionsStart);
                long rsrcOffset = 0;
                long closestSectionOffset = 0;
                long minDiff = Long.MAX_VALUE;

                for (int i = 0; i < numSections; i++) {
                    byte[] nm = new byte[8];
                    raf.readFully(nm);
                    String secName = new String(nm).trim().replace("\0", "").toLowerCase(java.util.Locale.US);
                    int vSize = readLE32(raf);
                    long vAddr  = readLE32(raf) & 0xFFFFFFFFL;
                    int rawSize = readLE32(raf);
                    long rawOff = readLE32(raf) & 0xFFFFFFFFL;
                    raf.skipBytes(16);

                    long span = Math.max(vSize, rawSize);
                    if (rsrcRVA >= vAddr && rsrcRVA < vAddr + span) {
                        rsrcOffset = rawOff + (rsrcRVA - vAddr);
                        Log.d(TAG, "[PE:step7] Resource section matched by RVA: '" + secName
                                + "' at fileOffset=0x" + Long.toHexString(rsrcOffset));
                        break;
                    } else if (secName.equals(".rsrc") || secName.equals("rsrc") || secName.contains("rsrc")) {
                        closestSectionOffset = rawOff;
                    }

                    if (rsrcRVA >= vAddr && (rsrcRVA - vAddr) < minDiff) {
                        minDiff = rsrcRVA - vAddr;
                        closestSectionOffset = rawOff + (rsrcRVA - vAddr);
                    }
                }

                if (rsrcOffset == 0 && closestSectionOffset > 0) {
                    rsrcOffset = closestSectionOffset;
                    Log.d(TAG, "[PE:step7] Falling back to closest section at offset=0x" + Long.toHexString(rsrcOffset));
                }

                if (rsrcOffset == 0 || rsrcOffset >= exeFile.length()) {
                    Log.e(TAG, "[PE:step7] No valid section for rsrcRVA=0x" + Long.toHexString(rsrcRVA));
                    return null;
                }

                return extractBestIcon(raf, rsrcOffset, rsrcRVA, exeFile.getName());

            } catch (Exception e) {
                Log.e(TAG, "[PE] Unexpected exception parsing '" + exeFile.getName() + "': " + e.getMessage(), e);
                return null;
            }
        }

        private static Bitmap extractBestIcon(RandomAccessFile raf, long rsrcBase,
                                              long rsrcRVA, String exeName) throws Exception {
            raf.seek(rsrcBase + 12);
            int namedL1   = readLE16(raf);
            int idCountL1 = readLE16(raf);

            List<Long> groupIconSubDirs = new ArrayList<>();

            // Scan L1 for RT_GROUP_ICON (typeId = 14)
            for (int i = 0; i < namedL1 + idCountL1; i++) {
                raf.seek(rsrcBase + 16 + i * 8);
                int typeId = readLE16(raf);
                readLE16(raf);
                int off = readLE32(raf);

                if (typeId == 14) {
                    groupIconSubDirs.add(rsrcBase + (off & 0x7FFFFFFF));
                }
            }

            if (groupIconSubDirs.isEmpty()) {
                Log.w(TAG, "[GroupIcon] RT_GROUP_ICON (14) not found in '" + exeName + "'");
                return null;
            }

            // Collect all icon entries across all icon groups
            List<int[]> allEntries = new ArrayList<>();

            for (long subDir : groupIconSubDirs) {
                raf.seek(subDir + 12);
                int namedL2   = readLE16(raf);
                int idCountL2 = readLE16(raf);

                for (int g = 0; g < namedL2 + idCountL2; g++) {
                    raf.seek(subDir + 16 + g * 8);
                    readLE16(raf); readLE16(raf);
                    int off2 = readLE32(raf);

                    long subDir2 = rsrcBase + (off2 & 0x7FFFFFFF);
                    raf.seek(subDir2 + 12);
                    int namedL3   = readLE16(raf);
                    int idCountL3 = readLE16(raf);
                    if (namedL3 + idCountL3 == 0) continue;

                    raf.seek(subDir2 + 16);
                    readLE16(raf); readLE16(raf);
                    int off3 = readLE32(raf);

                    long dataEntry = rsrcBase + (off3 & 0x7FFFFFFF);
                    raf.seek(dataEntry);
                    long dataRVA  = readLE32(raf) & 0xFFFFFFFFL;
                    readLE32(raf); // dataSize

                    long grpDataOffset = rsrcBase + (dataRVA - rsrcRVA);
                    if (grpDataOffset < 0 || grpDataOffset >= raf.length()) continue;

                    raf.seek(grpDataOffset);
                    raf.skipBytes(4); // idReserved + idType
                    int iconCount = readLE16(raf);

                    for (int j = 0; j < iconCount; j++) {
                        int w = raf.read() & 0xFF;
                        int h = raf.read() & 0xFF;
                        raf.skipBytes(2); // colorCount + reserved
                        raf.skipBytes(4); // planes + bitCount
                        raf.skipBytes(4); // bytesInRes
                        int iconId = readLE16(raf);
                        if (w == 0) w = 256;
                        if (h == 0) h = 256;
                        allEntries.add(new int[]{w, h, iconId});
                    }
                }
            }

            if (allEntries.isEmpty()) {
                Log.w(TAG, "[GroupIcon] No icon entries found in icon groups of '" + exeName + "'");
                return null;
            }

            // Sort by resolution descending (largest first)
            Collections.sort(allEntries, (a, b) -> (b[0] * b[1]) - (a[0] * a[1]));

            for (int[] entry : allEntries) {
                Log.d(TAG, "[GroupIcon] Attempting iconId=" + entry[2] + " (" + entry[0] + "x" + entry[1] + ")");
                Bitmap bmp = extractRtIcon(raf, rsrcBase, rsrcRVA, entry[2]);
                if (bmp != null) {
                    Log.d(TAG, "[GroupIcon] Decoded best iconId=" + entry[2] + " (" + bmp.getWidth() + "x" + bmp.getHeight() + ") for '" + exeName + "'");
                    return bmp;
                }
            }

            return null;
        }

        private static Bitmap extractRtIcon(RandomAccessFile raf, long rsrcBase,
                                            long rsrcRVA, int iconId) throws Exception {
            raf.seek(rsrcBase + 12);
            int namedL1   = readLE16(raf);
            int idCountL1 = readLE16(raf);

            for (int i = 0; i < namedL1 + idCountL1; i++) {
                raf.seek(rsrcBase + 16 + i * 8);
                int typeId = readLE16(raf);
                readLE16(raf);
                int off = readLE32(raf);

                if (typeId != 3) continue; // RT_ICON = 3

                long subDir = rsrcBase + (off & 0x7FFFFFFF);
                raf.seek(subDir + 12);
                int namedL2   = readLE16(raf);
                int idCountL2 = readLE16(raf);

                for (int j = 0; j < namedL2 + idCountL2; j++) {
                    raf.seek(subDir + 16 + j * 8);
                    int entryId = readLE16(raf);
                    readLE16(raf);
                    int off2 = readLE32(raf);

                    if (entryId != iconId) continue;

                    long subDir2 = rsrcBase + (off2 & 0x7FFFFFFF);
                    raf.seek(subDir2 + 12);
                    int namedL3   = readLE16(raf);
                    int idCountL3 = readLE16(raf);
                    if (namedL3 + idCountL3 == 0) continue;

                    raf.seek(subDir2 + 16);
                    readLE16(raf); readLE16(raf);
                    int off3 = readLE32(raf);

                    long dataEntry = rsrcBase + (off3 & 0x7FFFFFFF);
                    raf.seek(dataEntry);
                    long dataRVA  = readLE32(raf) & 0xFFFFFFFFL;
                    int  dataSize = readLE32(raf);

                    if (dataSize <= 0 || dataSize > 16 * 1024 * 1024) continue;

                    long iconDataOffset = rsrcBase + (dataRVA - rsrcRVA);
                    if (iconDataOffset < 0 || iconDataOffset >= raf.length()) continue;

                    raf.seek(iconDataOffset);
                    byte[] iconData = new byte[dataSize];
                    raf.readFully(iconData);

                    // 1. Try PNG decode (standard for modern Vista/7/10/11 256x256+ icons)
                    Bitmap bmp = BitmapFactory.decodeByteArray(iconData, 0, iconData.length);
                    if (bmp != null) {
                        return bmp;
                    }

                    // 2. Fall back to raw DIB decode
                    bmp = decodeDIB(iconData, iconId);
                    if (bmp != null) {
                        return bmp;
                    }
                }
            }

            return null;
        }

        private static Bitmap decodeDIB(byte[] data, int iconId) {
            try {
                if (data.length < 40) return null;

                int headerSize  = readLE32(data, 0);
                int width       = readLE32(data, 4);
                int rawHeight   = readLE32(data, 8);
                int height      = rawHeight / 2; // stored as 2x (XOR mask + AND mask)
                int bpp         = readLE16(data, 14);
                int compression = readLE32(data, 16);

                if (width <= 0 || height <= 0 || width > 2048 || height > 2048) return null;
                if (compression != 0) return null;

                Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

                if (bpp == 32) {
                    int pixelDataOffset = headerSize;
                    int[] pixels = new int[width * height];
                    boolean hasAlpha = false;

                    for (int y = height - 1; y >= 0; y--) {
                        for (int x = 0; x < width; x++) {
                            int idx = pixelDataOffset + ((height - 1 - y) * width + x) * 4;
                            if (idx + 3 >= data.length) break;
                            int b = data[idx]     & 0xFF;
                            int g = data[idx + 1] & 0xFF;
                            int r = data[idx + 2] & 0xFF;
                            int a = data[idx + 3] & 0xFF;
                            if (a > 0) hasAlpha = true;
                            pixels[y * width + x] = (a << 24) | (r << 16) | (g << 8) | b;
                        }
                    }

                    if (!hasAlpha) {
                        for (int idx2 = 0; idx2 < pixels.length; idx2++) {
                            pixels[idx2] |= 0xFF000000;
                        }
                        int andMaskOffset = headerSize + width * height * 4;
                        int maskRowBytes  = ((width + 31) / 32) * 4;
                        if (andMaskOffset + maskRowBytes * height <= data.length) {
                            for (int y = height - 1; y >= 0; y--) {
                                int maskRow = andMaskOffset + (height - 1 - y) * maskRowBytes;
                                for (int x = 0; x < width; x++) {
                                    int byteIdx = maskRow + x / 8;
                                    int bit     = 7 - (x % 8);
                                    if (byteIdx < data.length && ((data[byteIdx] >> bit) & 1) == 1) {
                                        pixels[y * width + x] = 0x00000000;
                                    }
                                }
                            }
                        }
                    }

                    bmp.setPixels(pixels, 0, width, 0, 0, width, height);

                } else if (bpp == 24) {
                    int rowBytes = ((width * 3 + 3) / 4) * 4;
                    int[] pixels = new int[width * height];
                    for (int y = height - 1; y >= 0; y--) {
                        int rowStart = headerSize + (height - 1 - y) * rowBytes;
                        for (int x = 0; x < width; x++) {
                            int idx = rowStart + x * 3;
                            if (idx + 2 >= data.length) break;
                            int b = data[idx]     & 0xFF;
                            int g = data[idx + 1] & 0xFF;
                            int r = data[idx + 2] & 0xFF;
                            pixels[y * width + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
                        }
                    }
                    int maskOffset   = headerSize + rowBytes * height;
                    int maskRowBytes = ((width + 31) / 32) * 4;
                    if (maskOffset + maskRowBytes * height <= data.length) {
                        for (int y = height - 1; y >= 0; y--) {
                            int maskRow = maskOffset + (height - 1 - y) * maskRowBytes;
                            for (int x = 0; x < width; x++) {
                                int byteIdx = maskRow + x / 8;
                                int bit     = 7 - (x % 8);
                                if (byteIdx < data.length && ((data[byteIdx] >> bit) & 1) == 1) {
                                    pixels[y * width + x] = 0x00000000;
                                }
                            }
                        }
                    }
                    bmp.setPixels(pixels, 0, width, 0, 0, width, height);

                } else if (bpp == 8) {
                    int paletteSize = 256;
                    int[] palette   = new int[paletteSize];
                    int palOffset   = headerSize;
                    for (int i = 0; i < paletteSize; i++) {
                        if (palOffset + 3 >= data.length) break;
                        int b = data[palOffset++] & 0xFF;
                        int g = data[palOffset++] & 0xFF;
                        int r = data[palOffset++] & 0xFF;
                        palOffset++;
                        palette[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
                    }
                    int pixelOffset = headerSize + paletteSize * 4;
                    int rowBytes8   = ((width + 3) / 4) * 4;
                    int[] pixels    = new int[width * height];
                    for (int y = height - 1; y >= 0; y--) {
                        int rowStart = pixelOffset + (height - 1 - y) * rowBytes8;
                        for (int x = 0; x < width; x++) {
                            int idx = rowStart + x;
                            if (idx >= data.length) break;
                            pixels[y * width + x] = palette[data[idx] & 0xFF];
                        }
                    }
                    int maskOffset   = pixelOffset + rowBytes8 * height;
                    int maskRowBytes = ((width + 31) / 32) * 4;
                    if (maskOffset + maskRowBytes * height <= data.length) {
                        for (int y = height - 1; y >= 0; y--) {
                            int maskRow = maskOffset + (height - 1 - y) * maskRowBytes;
                            for (int x = 0; x < width; x++) {
                                int byteIdx = maskRow + x / 8;
                                int bit     = 7 - (x % 8);
                                if (byteIdx < data.length && ((data[byteIdx] >> bit) & 1) == 1) {
                                    pixels[y * width + x] = 0x00000000;
                                }
                            }
                        }
                    }
                    bmp.setPixels(pixels, 0, width, 0, 0, width, height);

                } else if (bpp == 4) {
                    int paletteSize = 16;
                    int[] palette   = new int[paletteSize];
                    int palOffset   = headerSize;
                    for (int i = 0; i < paletteSize; i++) {
                        if (palOffset + 3 >= data.length) break;
                        int b = data[palOffset++] & 0xFF;
                        int g = data[palOffset++] & 0xFF;
                        int r = data[palOffset++] & 0xFF;
                        palOffset++;
                        palette[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
                    }
                    int pixelOffset = headerSize + paletteSize * 4;
                    int rowBytes4   = ((width + 7) / 8) * 4;
                    int[] pixels    = new int[width * height];
                    for (int y = height - 1; y >= 0; y--) {
                        int rowStart = pixelOffset + (height - 1 - y) * rowBytes4;
                        for (int x = 0; x < width; x++) {
                            int idx = rowStart + x / 2;
                            if (idx >= data.length) break;
                            int nibble = (x % 2 == 0)
                                    ? ((data[idx] >> 4) & 0x0F)
                                    :  (data[idx]       & 0x0F);
                            pixels[y * width + x] = palette[nibble];
                        }
                    }
                    int maskOffset   = pixelOffset + rowBytes4 * height;
                    int maskRowBytes = ((width + 31) / 32) * 4;
                    if (maskOffset + maskRowBytes * height <= data.length) {
                        for (int y = height - 1; y >= 0; y--) {
                            int maskRow = maskOffset + (height - 1 - y) * maskRowBytes;
                            for (int x = 0; x < width; x++) {
                                int byteIdx = maskRow + x / 8;
                                int bit     = 7 - (x % 8);
                                if (byteIdx < data.length && ((data[byteIdx] >> bit) & 1) == 1) {
                                    pixels[y * width + x] = 0x00000000;
                                }
                            }
                        }
                    }
                    bmp.setPixels(pixels, 0, width, 0, 0, width, height);

                } else {
                    bmp.recycle();
                    return null;
                }

                return bmp;

            } catch (Exception e) {
                Log.e(TAG, "[DIB] iconId=" + iconId + ": exception — " + e.getMessage(), e);
                return null;
            }
        }

        private static int readLE16(RandomAccessFile r) throws Exception {
            return (r.read() & 0xFF) | ((r.read() & 0xFF) << 8);
        }

        private static int readLE32(RandomAccessFile r) throws Exception {
            return  (r.read() & 0xFF)
                 | ((r.read() & 0xFF) <<  8)
                 | ((r.read() & 0xFF) << 16)
                 | ((r.read() & 0xFF) << 24);
        }

        private static int readLE16(byte[] data, int offset) {
            return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
        }

        private static int readLE32(byte[] data, int offset) {
            return  (data[offset]     & 0xFF)
                 | ((data[offset + 1] & 0xFF) <<  8)
                 | ((data[offset + 2] & 0xFF) << 16)
                 | ((data[offset + 3] & 0xFF) << 24);
        }
    }
}
