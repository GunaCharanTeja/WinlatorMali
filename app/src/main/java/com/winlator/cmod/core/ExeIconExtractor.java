package com.winlator.cmod.core;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
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

    public static final int ICON_SIZE    = 256;
    public static final int COVER_WIDTH  = 600;
    public static final int COVER_HEIGHT = 900;


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
            return PeIconExtractor.extract(exeFile);
        } catch (Exception e) {
            Log.e(TAG, "[extractBitmap] Unexpected exception for '" + exeFile.getName() + "': " + e.getMessage(), e);
            return null;
        }
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
            // Cover fallback: generate a 32x32 solid-color placeholder so the cover
            // pipeline still produces an image (dark blurred background + no icon).
            // getDominantColor / darkenColor are unused by the caller but kept for
            // possible future use.
            Log.d(TAG, "[extractAndSave:cover] Generating fallback solid-color cover");
            raw = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
            raw.eraseColor(0xFF1A1A2E);
        }
        Log.d(TAG, "[extractAndSave:" + label + "] Raw bitmap obtained: "
                + raw.getWidth() + "x" + raw.getHeight());

        Bitmap result;
        try {
            if (isCover) {
                result = buildCover(raw);
            } else {
                result = Bitmap.createScaledBitmap(raw, ICON_SIZE, ICON_SIZE, true);
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

        Bitmap tiny   = Bitmap.createScaledBitmap(icon, 6, 6, true);
        Bitmap bgFill = Bitmap.createScaledBitmap(tiny, COVER_WIDTH, COVER_HEIGHT, true);
        tiny.recycle();
        canvas.drawBitmap(bgFill, 0, 0, null);
        bgFill.recycle();

        canvas.drawColor(0x99000000);

        android.graphics.RadialGradient vignette = new android.graphics.RadialGradient(
                COVER_WIDTH  / 2f,
                COVER_HEIGHT / 2f,
                Math.max(COVER_WIDTH, COVER_HEIGHT) * 0.72f,
                new int[]{ 0x00000000, 0x99000000 },
                new float[]{ 0.35f, 1.0f },
                android.graphics.Shader.TileMode.CLAMP);
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

        Bitmap drawIcon = icon;
        if (srcSize < iconDraw) {
            int cur = srcSize;
            Bitmap stepped = icon;
            while (cur * 2 < iconDraw) {
                cur *= 2;
                Bitmap next = Bitmap.createScaledBitmap(stepped, cur, cur, true);
                if (stepped != icon) stepped.recycle();
                stepped = next;
            }
            drawIcon = stepped;
        }

        int shadowOff = Math.max(4, iconDraw / 18);
        Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        shadowPaint.setColorFilter(new android.graphics.PorterDuffColorFilter(
                0xFF000000, android.graphics.PorterDuff.Mode.SRC_ATOP));
        shadowPaint.setAlpha(110);
        canvas.drawBitmap(drawIcon, null,
                new Rect(left + shadowOff, top + shadowOff,
                         left + iconDraw + shadowOff, top + iconDraw + shadowOff),
                shadowPaint);

        Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(drawIcon, null,
                new Rect(left, top, left + iconDraw, top + iconDraw),
                iconPaint);

        if (drawIcon != icon) drawIcon.recycle();

        return cover;
    }

    private static int getDominantColor(Bitmap bitmap) {
        Bitmap small = Bitmap.createScaledBitmap(bitmap, 16, 16, false);
        long r = 0, g = 0, b = 0;
        int count = 0;
        for (int y = 0; y < small.getHeight(); y++) {
            for (int x = 0; x < small.getWidth(); x++) {
                int pixel = small.getPixel(x, y);
                if (((pixel >> 24) & 0xFF) < 128) continue;
                r += (pixel >> 16) & 0xFF;
                g += (pixel >>  8) & 0xFF;
                b +=  pixel        & 0xFF;
                count++;
            }
        }
        small.recycle();
        if (count == 0) return 0xFF1A1A2E;
        return 0xFF000000
             | (((int)(r / count)) << 16)
             | (((int)(g / count)) <<  8)
             |  ((int)(b / count));
    }

    private static int darkenColor(int color, float factor) {
        int r = (int) (((color >> 16) & 0xFF) * factor);
        int g = (int) (((color >>  8) & 0xFF) * factor);
        int b = (int) ( (color        & 0xFF) * factor);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    static class PeIconExtractor {

        static Bitmap extract(File exeFile) {
            try (RandomAccessFile raf = new RandomAccessFile(exeFile, "r")) {

                int b0 = raf.read(), b1 = raf.read();
                if (b0 != 0x4D || b1 != 0x5A) {
                    Log.w(TAG, "[PE:step1] Not a valid DOS/PE executable (no MZ)."
                            + " Got 0x" + Integer.toHexString(b0)
                            + " 0x" + Integer.toHexString(b1)
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
                            + Integer.toHexString(peOffset)
                            + ": bytes=" + p0 + " " + p1 + " " + p2 + " " + p3);
                    return null;
                }

                raf.skipBytes(2); // Machine
                int numSections   = readLE16(raf);
                raf.skipBytes(12); // TimeDateStamp + PointerToSymbolTable + NumberOfSymbols
                int optHeaderSize = readLE16(raf);
                raf.skipBytes(2); // Characteristics

                long optHeaderStart = raf.getFilePointer();
                Log.d(TAG, "[PE:step4] numSections=" + numSections
                        + "  optHeaderSize=" + optHeaderSize
                        + "  optHeaderStart=0x" + Long.toHexString(optHeaderStart));

                int magic = readLE16(raf);
                Log.d(TAG, "[PE:step5] OptHeader magic=0x" + Integer.toHexString(magic)
                        + " (" + (magic == 0x20B ? "PE32+" : magic == 0x10B ? "PE32" : "UNKNOWN") + ")");

                if (magic != 0x10B && magic != 0x20B) {
                    Log.w(TAG, "[PE:step5] Unsupported PE magic 0x" + Integer.toHexString(magic)
                            + " — not a standard PE32 or PE32+ binary");
                    return null;
                }

                int ddOffset = (magic == 0x20B) ? 112 : 96;
                raf.seek(optHeaderStart + ddOffset);
                raf.skipBytes(16);

                long rsrcRVA  = readLE32(raf) & 0xFFFFFFFFL;
                int  rsrcSize = readLE32(raf);
                Log.d(TAG, "[PE:step6] Resource RVA=0x" + Long.toHexString(rsrcRVA)
                        + "  size=" + rsrcSize);

                if (rsrcRVA == 0) {
                    Log.w(TAG, "[PE:step6] rsrcRVA=0 — this EXE has no embedded resource section (no icon)");
                    return null;
                }

                long sectionsStart = optHeaderStart + optHeaderSize;
                raf.seek(sectionsStart);
                long rsrcOffset = 0;

                for (int i = 0; i < numSections; i++) {
                    byte[] nm = new byte[8];
                    raf.readFully(nm);
                    String secName = new String(nm).trim().replace("\0", "");
                    raf.skipBytes(4); // VirtualSize
                    long vAddr  = readLE32(raf) & 0xFFFFFFFFL;
                    raf.skipBytes(4); // SizeOfRawData
                    long rawOff = readLE32(raf) & 0xFFFFFFFFL;
                    raf.skipBytes(16);

                    Log.d(TAG, "[PE:step7] Section[" + i + "] name='" + secName
                            + "'  vAddr=0x" + Long.toHexString(vAddr)
                            + "  rawOff=0x" + Long.toHexString(rawOff));

                    if (vAddr == rsrcRVA) {
                        rsrcOffset = rawOff;
                        Log.d(TAG, "[PE:step7] Resource section matched: '" + secName
                                + "' at fileOffset=0x" + Long.toHexString(rsrcOffset));
                    }
                }

                if (rsrcOffset == 0) {
                    Log.e(TAG, "[PE:step7] No section matched rsrcRVA=0x"
                            + Long.toHexString(rsrcRVA)
                            + " — possibly a packed/protected EXE (UPX, Themida, etc.)");
                    return null;
                }

                return extractBestIcon(raf, rsrcOffset, rsrcRVA, exeFile.getName());

            } catch (Exception e) {
                Log.e(TAG, "[PE] Unexpected exception parsing '"
                        + exeFile.getName() + "': " + e.getMessage(), e);
                return null;
            }
        }


        private static Bitmap extractBestIcon(RandomAccessFile raf, long rsrcBase,
                                              long rsrcRVA, String exeName) throws Exception {
            Log.d(TAG, "[GroupIcon] Walking resource dir at fileOffset=0x"
                    + Long.toHexString(rsrcBase));

            raf.seek(rsrcBase + 12);
            int namedL1   = readLE16(raf);
            int idCountL1 = readLE16(raf);
            Log.d(TAG, "[GroupIcon] L1 dir: namedEntries=" + namedL1
                    + "  idEntries=" + idCountL1);

            raf.skipBytes(namedL1 * 8);

            boolean foundGroupIcon = false;
            for (int i = 0; i < idCountL1; i++) {
                raf.seek(rsrcBase + 16 + namedL1 * 8 + i * 8);
                int typeId = readLE16(raf);
                readLE16(raf); // high word
                int off = readLE32(raf);

                String typeName = typeId == 3  ? " (RT_ICON)"
                                : typeId == 14 ? " (RT_GROUP_ICON)"
                                : "";
                Log.d(TAG, "[GroupIcon] L1 entry[" + i + "] typeId=" + typeId + typeName);

                if (typeId != 14) continue; // RT_GROUP_ICON = 14
                foundGroupIcon = true;

                long subDir = rsrcBase + (off & 0x7FFFFFFF);
                raf.seek(subDir + 12);
                int namedL2   = readLE16(raf);
                int idCountL2 = readLE16(raf);
                Log.d(TAG, "[GroupIcon] L2 dir: namedEntries=" + namedL2
                        + "  idEntries=" + idCountL2);

                if (namedL2 + idCountL2 == 0) {
                    Log.w(TAG, "[GroupIcon] L2 is empty — no icon groups found in '" + exeName + "'");
                    return null;
                }

                raf.seek(subDir + 16);
                readLE16(raf); readLE16(raf); // entry name/id
                int off2 = readLE32(raf);

                long subDir2 = rsrcBase + (off2 & 0x7FFFFFFF);
                raf.seek(subDir2 + 12);
                int namedL3   = readLE16(raf);
                int idCountL3 = readLE16(raf);
                Log.d(TAG, "[GroupIcon] L3 dir: namedEntries=" + namedL3
                        + "  idEntries=" + idCountL3);

                if (namedL3 + idCountL3 == 0) {
                    Log.w(TAG, "[GroupIcon] L3 is empty — no language entries for icon group");
                    continue;
                }

                raf.seek(subDir2 + 16);
                readLE16(raf); readLE16(raf);
                int off3 = readLE32(raf);

                long dataEntry = rsrcBase + (off3 & 0x7FFFFFFF);
                raf.seek(dataEntry);
                long dataRVA  = readLE32(raf) & 0xFFFFFFFFL;
                int  dataSize = readLE32(raf);
                Log.d(TAG, "[GroupIcon] GROUP_ICON data: RVA=0x"
                        + Long.toHexString(dataRVA) + "  size=" + dataSize);

                long grpDataOffset = rsrcBase + (dataRVA - rsrcRVA);
                raf.seek(grpDataOffset);
                raf.skipBytes(4); // idReserved + idType
                int iconCount = readLE16(raf);
                Log.d(TAG, "[GroupIcon] GRPICONDIR iconCount=" + iconCount);

                if (iconCount == 0) {
                    Log.w(TAG, "[GroupIcon] Icon group is empty (iconCount=0) for '" + exeName + "'");
                    return null;
                }

                List<int[]> entries = new ArrayList<>();
                for (int j = 0; j < iconCount; j++) {
                    int w = raf.read() & 0xFF;
                    int h = raf.read() & 0xFF;
                    raf.skipBytes(2); // colorCount + reserved
                    raf.skipBytes(4); // planes + bitCount
                    raf.skipBytes(4); // bytesInRes
                    int iconId = readLE16(raf);
                    if (w == 0) w = 256;
                    if (h == 0) h = 256;
                    Log.d(TAG, "[GroupIcon] Entry[" + j + "] size=" + w + "x" + h
                            + "  iconId=" + iconId);
                    entries.add(new int[]{w, h, iconId});
                }

                Collections.sort(entries, (a, b) -> (b[0] * b[1]) - (a[0] * a[1]));

                for (int[] entry : entries) {
                    Log.d(TAG, "[GroupIcon] Trying iconId=" + entry[2]
                            + " (" + entry[0] + "x" + entry[1] + ")");
                    Bitmap bmp = extractRtIcon(raf, rsrcBase, rsrcRVA, entry[2]);
                    if (bmp != null) {
                        Log.d(TAG, "[GroupIcon] Successfully decoded iconId=" + entry[2]
                                + " for '" + exeName + "'");
                        return bmp;
                    }
                    Log.d(TAG, "[GroupIcon] iconId=" + entry[2]
                            + " failed, trying next smaller size");
                }

                Log.w(TAG, "[GroupIcon] All " + entries.size()
                        + " icon(s) failed to decode for '" + exeName + "'");
                return null;
            }

            if (!foundGroupIcon) {
                Log.w(TAG, "[GroupIcon] RT_GROUP_ICON (typeId=14) not found in resource dir of '"
                        + exeName + "' — EXE has no icon or uses a non-standard layout");
            }
            return null;
        }


        private static Bitmap extractRtIcon(RandomAccessFile raf, long rsrcBase,
                                            long rsrcRVA, int iconId) throws Exception {
            raf.seek(rsrcBase + 12);
            int namedL1   = readLE16(raf);
            int idCountL1 = readLE16(raf);

            for (int i = 0; i < idCountL1; i++) {
                raf.seek(rsrcBase + 16 + namedL1 * 8 + i * 8);
                int typeId = readLE16(raf);
                readLE16(raf);
                int off = readLE32(raf);

                if (typeId != 3) continue; // RT_ICON = 3

                long subDir = rsrcBase + (off & 0x7FFFFFFF);
                raf.seek(subDir + 12);
                int namedL2   = readLE16(raf);
                int idCountL2 = readLE16(raf);

                for (int j = 0; j < idCountL2; j++) {
                    raf.seek(subDir + 16 + namedL2 * 8 + j * 8);
                    int entryId = readLE16(raf);
                    readLE16(raf);
                    int off2 = readLE32(raf);

                    if (entryId != iconId) continue;

                    long subDir2 = rsrcBase + (off2 & 0x7FFFFFFF);
                    raf.seek(subDir2 + 12);
                    int namedL3   = readLE16(raf);
                    int idCountL3 = readLE16(raf);
                    if (namedL3 + idCountL3 == 0) {
                        Log.w(TAG, "[RT_ICON] iconId=" + iconId + ": L3 has no language entries");
                        continue;
                    }

                    raf.seek(subDir2 + 16 + namedL3 * 8);
                    readLE16(raf); readLE16(raf);
                    int off3 = readLE32(raf);

                    long dataEntry = rsrcBase + (off3 & 0x7FFFFFFF);
                    raf.seek(dataEntry);
                    long dataRVA  = readLE32(raf) & 0xFFFFFFFFL;
                    int  dataSize = readLE32(raf);

                    Log.d(TAG, "[RT_ICON] iconId=" + iconId
                            + "  dataRVA=0x" + Long.toHexString(dataRVA)
                            + "  dataSize=" + dataSize);

                    if (dataSize <= 0 || dataSize > 4 * 1024 * 1024) {
                        Log.e(TAG, "[RT_ICON] iconId=" + iconId
                                + ": suspicious dataSize=" + dataSize + " bytes — skipping");
                        continue;
                    }

                    long iconDataOffset = rsrcBase + (dataRVA - rsrcRVA);
                    Log.d(TAG, "[RT_ICON] Reading " + dataSize + " bytes at fileOffset=0x"
                            + Long.toHexString(iconDataOffset));

                    raf.seek(iconDataOffset);
                    byte[] iconData = new byte[dataSize];
                    raf.readFully(iconData);

                    Bitmap bmp = BitmapFactory.decodeByteArray(iconData, 0, iconData.length);
                    if (bmp != null) {
                        Log.d(TAG, "[RT_ICON] iconId=" + iconId + ": decoded as embedded PNG ("
                                + bmp.getWidth() + "x" + bmp.getHeight() + ")");
                        return bmp;
                    }

                    Log.d(TAG, "[RT_ICON] iconId=" + iconId
                            + ": not a PNG, falling back to DIB decode");

                    bmp = decodeDIB(iconData, iconId);
                    if (bmp != null) {
                        Log.d(TAG, "[RT_ICON] iconId=" + iconId + ": decoded as DIB ("
                                + bmp.getWidth() + "x" + bmp.getHeight() + ")");
                        return bmp;
                    }

                    Log.w(TAG, "[RT_ICON] iconId=" + iconId
                            + ": both PNG and DIB decoders failed for this entry");
                }
            }

            Log.w(TAG, "[RT_ICON] iconId=" + iconId
                    + " not found in the RT_ICON section of the resource directory");
            return null;
        }


        /**
         * Decode a raw DIB (Device-Independent Bitmap) as stored inside ICO/RT_ICON.
         *
         * Differences from a standalone BMP:
         *  - No BITMAPFILEHEADER prefix
         *  - Height = 2x real height (XOR mask + AND mask stacked)
         *  - 32bpp pre-Vista icons often have alpha=0 everywhere; fall back to AND mask
         */
        private static Bitmap decodeDIB(byte[] data, int iconId) {
            try {
                if (data.length < 40) {
                    Log.w(TAG, "[DIB] iconId=" + iconId + ": data too short ("
                            + data.length + " bytes, need >=40)");
                    return null;
                }

                int headerSize  = readLE32(data, 0);
                int width       = readLE32(data, 4);
                int rawHeight   = readLE32(data, 8);
                int height      = rawHeight / 2; // stored as 2x (XOR mask + AND mask)
                int bpp         = readLE16(data, 14);
                int compression = readLE32(data, 16);

                Log.d(TAG, "[DIB] iconId=" + iconId
                        + ": headerSize=" + headerSize
                        + "  size=" + width + "x" + height
                        + "  bpp=" + bpp
                        + "  compression=" + compression
                        + "  rawHeight=" + rawHeight
                        + "  dataLen=" + data.length);

                if (width <= 0 || height <= 0) {
                    Log.w(TAG, "[DIB] iconId=" + iconId + ": invalid dimensions "
                            + width + "x" + height);
                    return null;
                }
                if (width > 1024 || height > 1024) {
                    Log.w(TAG, "[DIB] iconId=" + iconId + ": dimensions too large "
                            + width + "x" + height + " (limit=1024)");
                    return null;
                }
                if (compression != 0) {
                    Log.w(TAG, "[DIB] iconId=" + iconId
                            + ": compressed DIB not supported (compression=" + compression
                            + ") — only BI_RGB=0 is handled");
                    return null;
                }

                Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

                if (bpp == 32) {
                    Log.d(TAG, "[DIB] iconId=" + iconId + ": path=32bpp BGRA");

                    int    pixelDataOffset = headerSize;
                    int[]  pixels          = new int[width * height];
                    boolean hasAlpha       = false;

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
                        Log.d(TAG, "[DIB] iconId=" + iconId
                                + ": all alpha=0 (pre-Vista format) — forcing opaque + applying AND mask");
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
                        } else {
                            Log.w(TAG, "[DIB] iconId=" + iconId
                                    + ": AND mask out of bounds (offset=" + andMaskOffset
                                    + " dataLen=" + data.length + ") — rendered fully opaque");
                        }
                    }

                    bmp.setPixels(pixels, 0, width, 0, 0, width, height);

                } else if (bpp == 24) {
                    Log.d(TAG, "[DIB] iconId=" + iconId + ": path=24bpp BGR");

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
                    } else {
                        Log.w(TAG, "[DIB] iconId=" + iconId + ": 24bpp AND mask out of bounds");
                    }
                    bmp.setPixels(pixels, 0, width, 0, 0, width, height);

                } else if (bpp == 8) {
                    Log.d(TAG, "[DIB] iconId=" + iconId + ": path=8bpp (256-color palette)");

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
                    } else {
                        Log.w(TAG, "[DIB] iconId=" + iconId + ": 8bpp AND mask out of bounds");
                    }
                    bmp.setPixels(pixels, 0, width, 0, 0, width, height);

                } else if (bpp == 4) {
                    Log.d(TAG, "[DIB] iconId=" + iconId + ": path=4bpp (16-color palette)");

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
                    } else {
                        Log.w(TAG, "[DIB] iconId=" + iconId + ": 4bpp AND mask out of bounds");
                    }
                    bmp.setPixels(pixels, 0, width, 0, 0, width, height);

                } else {
                    Log.w(TAG, "[DIB] iconId=" + iconId + ": unsupported bpp=" + bpp
                            + " — only 4/8/24/32 are handled");
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
