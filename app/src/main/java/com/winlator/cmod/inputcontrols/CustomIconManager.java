package com.winlator.cmod.inputcontrols;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;
import android.util.LruCache;

import com.winlator.cmod.core.FileUtils;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Phase 5: Dynamic Custom Icon & Icon Pack Manager.
 * Handles built-in assets (1-16), user-imported custom icons (17+),
 * .icpx archive bundling/import/export, and Base64 embedded profile portability.
 */
public class CustomIconManager {
    private static final String TAG = "CustomIconManager";
    public static final int BUILTIN_ICON_MAX = 39;
    public static final int CUSTOM_ICON_START_ID = 1000;
    public static final int ICON_RESOLUTION = 256; // Standardized high-performance 256x256 resolution (razor-sharp at up to 600% scale with 4x reduced GPU fill-rate)

    private static CustomIconManager instance;
    private final Context context;
    private final File customIconsDir;
    private final LruCache<Integer, Bitmap> memoryCache;
    private final java.util.concurrent.ConcurrentHashMap<Integer, Bitmap> fastCache = new java.util.concurrent.ConcurrentHashMap<>(128);
    private final java.util.concurrent.ConcurrentHashMap<Integer, String> base64Cache = new java.util.concurrent.ConcurrentHashMap<>(128);

    private CustomIconManager(Context context) {
        this.context = context.getApplicationContext();
        this.customIconsDir = new File(this.context.getFilesDir(), "custom_icons");
        if (!customIconsDir.exists()) {
            customIconsDir.mkdirs();
        }

        // Cache up to 16 MB of icon bitmaps in memory (byte-accurate)
        final int maxMemoryKb = (int) (Runtime.getRuntime().maxMemory() / 1024);
        final int cacheSizeKb = Math.min(maxMemoryKb / 12, 16 * 1024);
        this.memoryCache = new LruCache<Integer, Bitmap>(cacheSizeKb) {
            @Override
            protected int sizeOf(Integer key, Bitmap bitmap) {
                return (bitmap != null && !bitmap.isRecycled()) ? (bitmap.getByteCount() / 1024) : 1;
            }
        };
    }

    public static synchronized CustomIconManager getInstance(Context context) {
        if (instance == null) {
            instance = new CustomIconManager(context);
        }
        return instance;
    }

    /**
     * Retrieves an icon bitmap by ID (built-in or custom).
     */
    public Bitmap getIcon(int id) {
        if (id <= 0) return null;

        Bitmap cached = fastCache.get(id);
        if (cached != null && !cached.isRecycled()) {
            return cached;
        }

        Bitmap loaded = null;
        if (id <= BUILTIN_ICON_MAX) {
            // Load built-in icon from APK assets
            try (InputStream is = context.getAssets().open("inputcontrols/icons/" + id + ".png")) {
                loaded = BitmapFactory.decodeStream(is);
            } catch (IOException e) {
                Log.w(TAG, "Failed to load built-in icon " + id);
            }
        } else {
            // Load custom icon from internal storage
            File file = new File(customIconsDir, id + ".png");
            if (file.exists() && file.isFile()) {
                try (FileInputStream fis = new FileInputStream(file)) {
                    loaded = BitmapFactory.decodeStream(fis);
                } catch (IOException e) {
                    Log.w(TAG, "Failed to load custom icon " + id);
                }
            }
        }

        if (loaded != null) {
            fastCache.put(id, loaded);
            synchronized (memoryCache) {
                memoryCache.put(id, loaded);
            }
        }
        return loaded;
    }

    /**
     * Rescales and centers any bitmap to ultra-sharp square with transparent background.
     */
    public Bitmap normalizeBitmap(Bitmap src) {
        if (src == null) return null;
        int srcW = src.getWidth();
        int srcH = src.getHeight();
        if (srcW <= 0 || srcH <= 0) return null;

        int targetDim = Math.min(ICON_RESOLUTION, Math.max(srcW, srcH));
        if (targetDim < 128) targetDim = Math.max(srcW, srcH);

        Bitmap output = Bitmap.createBitmap(targetDim, targetDim, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        paint.setAlpha(255);
        paint.setColorFilter(null);

        float scale = Math.min((float) targetDim / srcW, (float) targetDim / srcH);
        int destW = (int) (srcW * scale);
        int destH = (int) (srcH * scale);
        int left = (targetDim - destW) / 2;
        int top = (targetDim - destH) / 2;

        Rect srcRect = new Rect(0, 0, srcW, srcH);
        Rect destRect = new Rect(left, top, left + destW, top + destH);
        canvas.drawBitmap(src, srcRect, destRect, paint);
        return output;
    }

    /**
     * Import a custom bitmap image and assign a new unique ID.
     */
    public int importCustomIcon(Bitmap srcBitmap) {
        if (srcBitmap == null) return 0;
        Bitmap normalized = normalizeBitmap(srcBitmap);
        if (normalized == null) return 0;

        int newId = getNextAvailableCustomId();
        File file = new File(customIconsDir, newId + ".png");

        try (FileOutputStream fos = new FileOutputStream(file)) {
            normalized.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush();
            fastCache.put(newId, normalized);
            synchronized (memoryCache) {
                memoryCache.put(newId, normalized);
            }
            Log.d(TAG, "Imported custom icon " + newId);
            return newId;
        } catch (IOException e) {
            Log.e(TAG, "Failed to save custom icon " + newId, e);
            return 0;
        }
    }

    public static Bitmap decodeIcon(byte[] data) {
        if (data == null || data.length == 0) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            return BitmapFactory.decodeByteArray(data, 0, data.length);
        }

        try {
            if (data.length >= 2 && (data[0] & 0xff) == 0x1f && (data[1] & 0xff) == 0x8b) return null;
            com.caverock.androidsvg.SVG.setInternalEntitiesEnabled(false);
            com.caverock.androidsvg.SVG svg = com.caverock.androidsvg.SVG.getFromInputStream(new java.io.ByteArrayInputStream(data));
            android.graphics.RectF viewBox = svg.getDocumentViewBox();
            float width = svg.getDocumentWidth();
            float height = svg.getDocumentHeight();
            boolean validViewBox = viewBox != null && viewBox.width() > 0 && viewBox.height() > 0;
            boolean validWidth = width > 0 && !Float.isInfinite(width) && !Float.isNaN(width);
            boolean validHeight = height > 0 && !Float.isInfinite(height) && !Float.isNaN(height);
            if (!validWidth && validViewBox) {
                width = validHeight ? height * viewBox.width() / viewBox.height() : viewBox.width();
            }
            if (!validHeight && validViewBox) {
                height = validWidth ? width * viewBox.height() / viewBox.width() : viewBox.height();
            }
            if (width <= 0 || Float.isInfinite(width) || Float.isNaN(width)) width = 256;
            if (height <= 0 || Float.isInfinite(height) || Float.isNaN(height)) height = 256;

            int bitmapWidth = 256;
            int bitmapHeight = 256;
            if (width > 0 && height > 0) {
                float maxD = Math.max(width, height);
                float svgScale = 256f / maxD;
                bitmapWidth = Math.max(32, Math.min(512, (int)(width * svgScale)));
                bitmapHeight = Math.max(32, Math.min(512, (int)(height * svgScale)));
            }

            Bitmap bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
            svg.setDocumentWidth(bitmapWidth);
            svg.setDocumentHeight(bitmapHeight);
            svg.renderToCanvas(new Canvas(bitmap));
            return bitmap;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * Import a custom icon from a content URI (Gallery, Photos, Files).
     */
    public int importCustomIcon(Uri imageUri) {
        if (imageUri == null) return 0;
        try (InputStream is = context.getContentResolver().openInputStream(imageUri)) {
            if (is == null) return 0;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = is.read(buf)) != -1) {
                baos.write(buf, 0, r);
            }
            byte[] data = baos.toByteArray();
            Bitmap bmp = decodeIcon(data);
            return importCustomIcon(bmp);
        } catch (Exception e) {
            Log.e(TAG, "Failed to import image from URI: " + imageUri, e);
            return 0;
        }
    }

    /**
     * Delete a custom icon.
     */
    public boolean deleteCustomIcon(int id) {
        if (id <= BUILTIN_ICON_MAX) return false;
        File file = new File(customIconsDir, id + ".png");
        fastCache.remove(id);
        base64Cache.remove(id);
        synchronized (memoryCache) {
            memoryCache.remove(id);
        }
        if (file.exists()) {
            return file.delete();
        }
        return false;
    }

    /**
     * Find next available ID for custom icons (starting at 17).
     */
    public int getNextAvailableCustomId() {
        List<Integer> existing = getCustomIconIds();
        int candidate = CUSTOM_ICON_START_ID;
        while (existing.contains(candidate)) {
            candidate++;
        }
        return candidate;
    }

    /**
     * Returns list of built-in icon IDs (1-16).
     */
    public List<Integer> getBuiltInIconIds() {
        List<Integer> list = new ArrayList<>();
        for (int i = 1; i <= BUILTIN_ICON_MAX; i++) {
            list.add(i);
        }
        return list;
    }

    /**
     * Returns list of sorted custom icon IDs.
     */
    public List<Integer> getCustomIconIds() {
        List<Integer> list = new ArrayList<>();
        File[] files = customIconsDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.getName().endsWith(".png")) {
                    String base = FileUtils.getBasename(f.getName());
                    try {
                        int id = Integer.parseInt(base);
                        if (id >= CUSTOM_ICON_START_ID) {
                            list.add(id);
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        Collections.sort(list);
        return list;
    }

    /**
     * Returns combined list of all icon IDs (built-in + custom).
     */
    public List<Integer> getAllIconIds() {
        List<Integer> all = new ArrayList<>(getBuiltInIconIds());
        all.addAll(getCustomIconIds());
        return all;
    }

    // -----------------------------------------------------------------------
    // Base64 Embedded Profile Portability
    // -----------------------------------------------------------------------

    /**
     * Encode an icon bitmap into a compact Base64 PNG string.
     */
    public String encodeIconBase64(int id) {
        String cached = base64Cache.get(id);
        if (cached != null) return cached;
        Bitmap bmp = getIcon(id);
        if (bmp == null) return null;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, baos);
        String encoded = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
        base64Cache.put(id, encoded);
        return encoded;
    }

    /**
     * Decode a Base64 PNG/SVG string and save as a custom icon with optional preferred ID.
     */
    public int decodeAndSaveBase64(String base64Str, int preferredId) {
        if (base64Str == null || base64Str.isEmpty()) return 0;
        try {
            byte[] bytes = Base64.decode(base64Str, Base64.DEFAULT);
            Bitmap bmp = decodeIcon(bytes);
            if (bmp == null) return 0;
            Bitmap normalized = normalizeBitmap(bmp);
            bmp.recycle();
            if (normalized == null) return 0;

            int targetId = (preferredId >= CUSTOM_ICON_START_ID) ? preferredId : getNextAvailableCustomId();
            File file = new File(customIconsDir, targetId + ".png");

            try (FileOutputStream fos = new FileOutputStream(file)) {
                normalized.compress(Bitmap.CompressFormat.PNG, 100, fos);
                fos.flush();
                fastCache.put(targetId, normalized);
                synchronized (memoryCache) {
                    memoryCache.put(targetId, normalized);
                }
                return targetId;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to decode base64 icon", e);
            return 0;
        }
    }

    public int decodeAndSaveBase64(String base64Str) {
        return decodeAndSaveBase64(base64Str, -1);
    }

    // -----------------------------------------------------------------------
    // .icpx (Icon Pack Archive) Import & Export
    // -----------------------------------------------------------------------

    /**
     * Export custom icons into an .icpx ZIP bundle.
     */
    public File exportIconPack(String packName, List<Integer> iconIds) {
        if (packName == null || packName.isEmpty()) packName = "IconPack";
        File exportDir = new File(context.getExternalFilesDir(null), "IconPacks");
        if (!exportDir.exists()) exportDir.mkdirs();

        File zipFile = new File(exportDir, packName + ".icpx");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            JSONObject manifest = new JSONObject();
            manifest.put("name", packName);
            manifest.put("version", 1);
            manifest.put("iconCount", iconIds.size());

            // Write manifest.json
            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write(manifest.toString(2).getBytes());
            zos.closeEntry();

            // Write icons
            int index = 1;
            for (int id : iconIds) {
                Bitmap bmp = getIcon(id);
                if (bmp != null) {
                    zos.putNextEntry(new ZipEntry("icon_" + index + ".png"));
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, baos);
                    zos.write(baos.toByteArray());
                    zos.closeEntry();
                    index++;
                }
            }
            zos.flush();
            Log.d(TAG, "Exported .icpx to " + zipFile.getAbsolutePath());
            return zipFile;
        } catch (Exception e) {
            Log.e(TAG, "Failed to export icon pack", e);
            return null;
        }
    }

    public static class ImportResult {
        public int importedIconsCount = 0;
        public JSONObject profileJSON = null;
        public String profileName = null;
    }

    /**
     * Universal importer for .icpx (Bannerlator / Winlator ZIP packs & JSON layouts), .ibp, and .icp files.
     */
    public ImportResult importUniversalPackage(Uri uri, String fallbackName) {
        ImportResult result = new ImportResult();
        if (uri == null) return result;

        byte[] fileBytes;
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is == null) return result;
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] data = new byte[8192];
            int nRead;
            while ((nRead = is.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }
            fileBytes = buffer.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "Failed to read URI bytes: " + uri, e);
            return result;
        }

        if (fileBytes.length == 0) return result;

        // Check if file is a ZIP archive (starts with PK / 0x50, 0x4B)
        boolean isZip = fileBytes.length >= 4 && fileBytes[0] == 0x50 && fileBytes[1] == 0x4B;

        if (isZip) {
            try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(fileBytes))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (entry.isDirectory()) {
                        zis.closeEntry();
                        continue;
                    }

                    ByteArrayOutputStream entryBaos = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int len;
                    while ((len = zis.read(buf)) > 0) {
                        entryBaos.write(buf, 0, len);
                    }
                    byte[] entryBytes = entryBaos.toByteArray();

                    String lowerName = name.toLowerCase();
                    if (lowerName.endsWith(".png") || lowerName.endsWith(".webp") || lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")) {
                        Bitmap bmp = BitmapFactory.decodeByteArray(entryBytes, 0, entryBytes.length);
                        if (bmp != null) {
                            int newId = importCustomIcon(bmp);
                            if (newId > 0) result.importedIconsCount++;
                        }
                    } else if (lowerName.endsWith(".icp") || lowerName.endsWith(".json") || lowerName.endsWith(".ibp") || lowerName.contains("profile") || lowerName.contains("control")) {
                        String jsonStr = new String(entryBytes, java.nio.charset.StandardCharsets.UTF_8);
                        JSONObject parsed = InputBridgeProfileParser.parseProfile(context, jsonStr, fallbackName);
                        if (parsed != null) {
                            result.profileJSON = parsed;
                            result.profileName = parsed.optString("name", fallbackName);
                        }
                    }
                    zis.closeEntry();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error unpacking ZIP package", e);
            }
        } else {
            // Plain text / JSON file (.icp, .ibp, or JSON-formatted .icpx)
            try {
                String content = new String(fileBytes, java.nio.charset.StandardCharsets.UTF_8);
                JSONObject parsed = InputBridgeProfileParser.parseProfile(context, content, fallbackName);
                if (parsed != null) {
                    result.profileJSON = parsed;
                    result.profileName = parsed.optString("name", fallbackName);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error parsing plain JSON profile", e);
            }
        }

        return result;
    }

    /**
     * Import an .icpx archive and register all contained PNGs as custom icons.
     * @return count of successfully imported icons
     */
    public int importIconPack(Uri uri) {
        ImportResult res = importUniversalPackage(uri, "IconPack");
        return res.importedIconsCount;
    }
}