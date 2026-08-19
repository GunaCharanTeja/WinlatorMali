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
    public static final int BUILTIN_ICON_MAX = 16;
    public static final int CUSTOM_ICON_START_ID = 17;
    public static final int ICON_RESOLUTION = 128; // Standardized icon render resolution (128x128 px)

    private static CustomIconManager instance;
    private final Context context;
    private final File customIconsDir;
    private final LruCache<Integer, Bitmap> memoryCache;

    private CustomIconManager(Context context) {
        this.context = context.getApplicationContext();
        this.customIconsDir = new File(this.context.getFilesDir(), "custom_icons");
        if (!customIconsDir.exists()) {
            customIconsDir.mkdirs();
        }

        // Cache up to 100 icons in memory (~2-4 MB RAM)
        final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        final int cacheSize = Math.min(maxMemory / 16, 100);
        this.memoryCache = new LruCache<Integer, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(Integer key, Bitmap bitmap) {
                return 1;
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

        synchronized (memoryCache) {
            Bitmap cached = memoryCache.get(id);
            if (cached != null && !cached.isRecycled()) {
                return cached;
            }
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
            synchronized (memoryCache) {
                memoryCache.put(id, loaded);
            }
        }
        return loaded;
    }

    /**
     * Rescales and centers any bitmap to 128x128 square with transparent background.
     */
    public Bitmap normalizeBitmap(Bitmap src) {
        if (src == null) return null;
        Bitmap output = Bitmap.createBitmap(ICON_RESOLUTION, ICON_RESOLUTION, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        int srcW = src.getWidth();
        int srcH = src.getHeight();
        float scale = Math.min((float) ICON_RESOLUTION / srcW, (float) ICON_RESOLUTION / srcH);
        int destW = (int) (srcW * scale);
        int destH = (int) (srcH * scale);
        int left = (ICON_RESOLUTION - destW) / 2;
        int top = (ICON_RESOLUTION - destH) / 2;

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

    /**
     * Import a custom icon from a content URI (Gallery, Photos, Files).
     */
    public int importCustomIcon(Uri imageUri) {
        if (imageUri == null) return 0;
        try (InputStream is = context.getContentResolver().openInputStream(imageUri)) {
            Bitmap bmp = BitmapFactory.decodeStream(is);
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
    private int getNextAvailableCustomId() {
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
        Bitmap bmp = getIcon(id);
        if (bmp == null) return null;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, baos);
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
    }

    /**
     * Decode a Base64 PNG string and save as a custom icon with optional preferred ID.
     */
    public int decodeAndSaveBase64(String base64Str, int preferredId) {
        if (base64Str == null || base64Str.isEmpty()) return 0;
        try {
            byte[] bytes = Base64.decode(base64Str, Base64.DEFAULT);
            Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bmp == null) return 0;
            Bitmap normalized = normalizeBitmap(bmp);
            bmp.recycle();
            if (normalized == null) return 0;

            int targetId = (preferredId >= CUSTOM_ICON_START_ID) ? preferredId : getNextAvailableCustomId();
            File file = new File(customIconsDir, targetId + ".png");

            try (FileOutputStream fos = new FileOutputStream(file)) {
                normalized.compress(Bitmap.CompressFormat.PNG, 100, fos);
                fos.flush();
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