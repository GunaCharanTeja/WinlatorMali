package com.winlator.cmod.container;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.Log;

import androidx.palette.graphics.Palette;

import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.ImageUtils;
import com.winlator.cmod.core.StringUtils;
import com.winlator.cmod.core.ExeIconExtractor;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Shortcut {
    public final Container container;
    public final String name;
    public final String path;
    public Bitmap icon;
    public final File file;
    public File iconFile;
    public final String wmClass;
    private final JSONObject extraData = new JSONObject();
    private Bitmap coverArt;
    private Bitmap fallbackCoverArt;
    private String customCoverArtPath;

    private static final String COVER_ART_DIR = "app_data/cover_arts/";
    private static final String FALLBACK_COVER_ART_DIR = "app_data/fallback_cover_arts/";

    private static final ExecutorService backgroundExecutor = Executors.newFixedThreadPool(4);

    public interface OnShortcutLoadedListener {
        void onShortcutLoaded(Shortcut shortcut);
    }

    private static OnShortcutLoadedListener onShortcutLoadedListener;

    public static void setOnShortcutLoadedListener(OnShortcutLoadedListener listener) {
        onShortcutLoadedListener = listener;
    }

    public Shortcut(Container container, File file) {
        this.container = container;
        this.file = file;

        if (file.getName().toLowerCase().endsWith(".exe")) {
            this.name = FileUtils.getBasename(file.getPath());
            this.path = file.getPath();
            this.icon = null;
            this.iconFile = null;
            this.wmClass = "";
            runBackgroundInitialization();
            return;
        }

        String execArgs = "";
        File iconFile = null;
        String wmClass = "";

        File[] iconDirs = {
            container.getIconsDir(256), container.getIconsDir(128), container.getIconsDir(96),
            container.getIconsDir(64), container.getIconsDir(48), container.getIconsDir(32),
            container.getIconsDir(16)
        };
        String section = "";

        int index;
        for (String line : FileUtils.readLines(file)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("[")) {
                section = line.substring(1, line.indexOf("]"));
            }
            else {
                index = line.indexOf("=");
                if (index == -1) continue;
                String key = line.substring(0, index);
                String value = line.substring(index+1);

                if (section.equals("Desktop Entry")) {
                    if (key.equals("Exec")) execArgs = value;
                    if (key.equals("Icon")) {
                        for (File iconDir : iconDirs) {
                            File f = new File(iconDir, value+".png");
                            if (f.isFile()){
                                iconFile = f;
                                break;
                            }
                        }
                    }
                    if (key.equals("StartupWMClass")) wmClass = value;
                }
                else if (section.equals("Extra Data")) {
                    try {
                        extraData.put(key, value);
                    }
                    catch (JSONException e) {}
                }
            }
        }

        this.name = FileUtils.getBasename(file.getPath());
        this.icon = null;
        this.iconFile = iconFile;
        this.path = execArgs.lastIndexOf("wine ") != -1 ? StringUtils.unescape(execArgs.substring(execArgs.lastIndexOf("wine ") + 4)) : "";
        this.wmClass = wmClass;

        this.customCoverArtPath = getExtra("customCoverArtPath");

        runBackgroundInitialization();

        Container.checkObsoleteOrMissingProperties(extraData);
    }

    public Bitmap getCoverArt() {
        if (coverArt != null) return coverArt;
        return fallbackCoverArt;
    }

    public Bitmap getCoverArt(int width, int height) {
        if (coverArt != null) return coverArt;
        return fallbackCoverArt;
    }

    private static final int[][] AAA_PALETTES = {
        {0xFF0D47A1, 0xFF00E5FF, 0xFF040810}, // Cyber Ocean (Deep Blue -> Cyan -> Obsidian)
        {0xFF4A148C, 0xFFFF007F, 0xFF080410}, // Neon Synthwave (Purple -> Hot Pink -> Dark)
        {0xFF880E4F, 0xFFFF1744, 0xFF0A0306}, // Crimson Shadow (Wine -> Vivid Red -> Obsidian)
        {0xFF004D40, 0xFF00E676, 0xFF030A06}, // Emerald Cyber (Dark Teal -> Bright Mint -> Dark)
        {0xFFE65100, 0xFFFFB300, 0xFF0C0702}, // Amber Gold (Deep Orange -> Radiant Gold -> Dark)
        {0xFF311B92, 0xFF7C4DFF, 0xFF06030E}, // Royal Velvet (Cosmic Indigo -> Violet -> Dark)
        {0xFF01579B, 0xFF00B0FF, 0xFF03070C}, // Electric Azure (Navy -> Bright Azure -> Dark)
        {0xFFBF360C, 0xFFFF6D00, 0xFF0A0502}  // Solar Sunset (Dark Rust -> Vivid Flame -> Dark)
    };

    private Bitmap generateFallbackCoverArt(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        int dominantColor = Color.parseColor("#121212");
        int darkColor = Color.parseColor("#050505");
        int vibrantColor = Color.parseColor("#1A237E");

        if (icon != null) {
            try {
                Palette palette = Palette.from(icon).generate();
                vibrantColor = palette.getVibrantColor(vibrantColor);
                vibrantColor = palette.getLightVibrantColor(vibrantColor);
                vibrantColor = palette.getDominantColor(vibrantColor);

                float[] hsv = new float[3];
                Color.colorToHSV(vibrantColor, hsv);
                hsv[1] = Math.min(hsv[1], 0.6f);
                hsv[2] *= 0.15f;
                dominantColor = Color.HSVToColor(hsv);
            } catch (Exception ignored) {}
        } else {
            int paletteIdx = Math.abs(this.name.hashCode()) % AAA_PALETTES.length;
            vibrantColor = AAA_PALETTES[paletteIdx][1];
            dominantColor = AAA_PALETTES[paletteIdx][0];
            float[] hsv = new float[3];
            Color.colorToHSV(vibrantColor, hsv);
            hsv[1] = Math.min(hsv[1], 0.6f);
            hsv[2] *= 0.15f;
            dominantColor = Color.HSVToColor(hsv);
        }

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        Shader gradient = new LinearGradient(0, 0, 0, height, dominantColor, darkColor, Shader.TileMode.CLAMP);
        paint.setShader(gradient);
        canvas.drawRect(0, 0, width, height, paint);

        if (icon != null) {
            Bitmap blurredIcon = Bitmap.createScaledBitmap(icon, 256, 256, true);
            Matrix matrix = new Matrix();
            float scale = Math.max((float)width / 256, (float)height / 256) * 1.5f;
            matrix.postScale(scale, scale);
            matrix.postTranslate((width - 256 * scale) * 0.4f, (height - 256 * scale) * 0.3f);

            Paint iconPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
            iconPaint.setAlpha(100);
            canvas.drawBitmap(blurredIcon, matrix, iconPaint);

            Paint overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            overlayPaint.setColor(vibrantColor);
            overlayPaint.setAlpha(20);
            canvas.drawRect(0, 0, width, height, overlayPaint);
        }

        if (icon != null) {
            float minDim = Math.min(width, height);
            int iconSize = (int)(minDim * 0.72f);
            int originalWidth = icon.getWidth();
            int originalHeight = icon.getHeight();
            float iconScale = Math.min((float)iconSize / originalWidth, (float)iconSize / originalHeight);
            int scaledWidth = (int)(originalWidth * iconScale);
            int scaledHeight = (int)(originalHeight * iconScale);

            Bitmap scaledIcon = Bitmap.createScaledBitmap(icon, scaledWidth, scaledHeight, true);
            int left = (width - scaledWidth) / 2;
            int top = (height - scaledHeight) / 2 - (int)(0.12f * height);

            Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadowPaint.setColor(vibrantColor);
            shadowPaint.setAlpha(90);
            shadowPaint.setMaskFilter(new BlurMaskFilter(width * 0.16f, BlurMaskFilter.Blur.NORMAL));
            canvas.drawCircle(width / 2.0f, top + scaledHeight / 2.0f, scaledWidth * 0.62f, shadowPaint);

            Paint blackShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            blackShadowPaint.setColor(Color.BLACK);
            blackShadowPaint.setAlpha(160);
            blackShadowPaint.setMaskFilter(new BlurMaskFilter(width * 0.05f, BlurMaskFilter.Blur.NORMAL));
            canvas.drawCircle(width / 2.0f, top + scaledHeight / 2.0f + 20, scaledWidth * 0.55f, blackShadowPaint);

            canvas.drawBitmap(scaledIcon, left, top, new Paint(Paint.FILTER_BITMAP_FLAG));
        } else {
            float centerX = width / 2.0f;
            float centerY = height * 0.42f;
            float badgeRadius = width * 0.22f;

            Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadowPaint.setColor(vibrantColor);
            shadowPaint.setAlpha(90);
            shadowPaint.setMaskFilter(new BlurMaskFilter(width * 0.16f, BlurMaskFilter.Blur.NORMAL));
            canvas.drawCircle(centerX, centerY, badgeRadius * 1.25f, shadowPaint);

            Paint blackShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            blackShadowPaint.setColor(Color.BLACK);
            blackShadowPaint.setAlpha(160);
            blackShadowPaint.setMaskFilter(new BlurMaskFilter(width * 0.05f, BlurMaskFilter.Blur.NORMAL));
            canvas.drawCircle(centerX, centerY + 20, badgeRadius * 1.1f, blackShadowPaint);

            Paint corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            corePaint.setColor(0xEE141A26);
            canvas.drawCircle(centerX, centerY, badgeRadius, corePaint);

            Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(4.0f);
            ringPaint.setColor(vibrantColor);
            canvas.drawCircle(centerX, centerY, badgeRadius, ringPaint);

            String initials = extractInitials(this.name);
            Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(badgeRadius * 0.85f);
            textPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setShadowLayer(10f, 0, 4f, 0xCC000000);
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float textY = centerY - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(initials, centerX, textY, textPaint);
        }

        Random random = new Random(this.name != null ? this.name.hashCode() : 42);
        Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        starPaint.setColor(Color.WHITE);
        for (int i = 0; i < 5000; i++) {
            starPaint.setAlpha(random.nextInt(15) + 3);
            canvas.drawPoint(random.nextFloat() * width, random.nextFloat() * height, starPaint);
        }

        Paint vignettePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        vignettePaint.setShader(new RadialGradient(width / 2.0f, height * 0.4f, height * 0.9f, Color.TRANSPARENT, Color.parseColor("#A6000000"), Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, vignettePaint);

        float[] hsv = new float[3];
        Color.colorToHSV(vibrantColor, hsv);
        hsv[2] *= 0.04f;
        int bottomColor = Color.HSVToColor(255, hsv);

        Paint bottomGradientPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bottomGradientPaint.setShader(new LinearGradient(0, height * 0.5f, 0, height, Color.TRANSPARENT, bottomColor, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, bottomGradientPaint);

        return bitmap;
    }

    private String extractInitials(String title) {
        if (title == null || title.trim().isEmpty()) return "G";
        String clean = title.replaceAll("(?i)\\b(the|a|an|of|in|and|for)\\b", " ")
                            .replaceAll("[^a-zA-Z0-9\\s]", " ")
                            .replaceAll("\\s+", " ").trim();
        String[] words = clean.split(" ");
        if (words.length == 0 || words[0].isEmpty()) return "G";
        if (words.length == 1) {
            String w = words[0];
            return w.length() >= 2 ? w.substring(0, 2).toUpperCase(java.util.Locale.ROOT) : w.toUpperCase(java.util.Locale.ROOT);
        } else if (words.length == 2) {
            return ("" + words[0].charAt(0) + words[1].charAt(0)).toUpperCase(java.util.Locale.ROOT);
        } else {
            return ("" + words[0].charAt(0) + words[1].charAt(0) + words[2].charAt(0)).toUpperCase(java.util.Locale.ROOT);
        }
    }

    private void saveFallbackCoverArt(Bitmap bitmap) {
        try {
            File dir = new File(container.getRootDir(), FALLBACK_COVER_ART_DIR);
            if (!dir.exists() && !dir.mkdirs()) return;
            File file = new File(dir, this.name + ".png");
            ImageUtils.save(bitmap, file, Bitmap.CompressFormat.PNG, 100);
        } catch (Exception e) {}
    }

    private void runBackgroundInitialization() {
        backgroundExecutor.submit(() -> {
            boolean changed = false;

            if (this.icon == null && this.iconFile != null && this.iconFile.isFile()) {
                this.icon = BitmapFactory.decodeFile(this.iconFile.getPath());
                changed = true;
            }

            File exeFile = resolveExeFile();
            if (exeFile != null && (this.icon == null || this.icon.getWidth() < 256)) {
                File iconDir = container.getIconsDir(256);
                if (!iconDir.exists()) iconDir.mkdirs();
                File iconDest = new File(iconDir, this.name + ".png");

                if (!iconDest.exists() || iconDest.length() == 0) {
                    ExeIconExtractor.extractIcon(exeFile, iconDest);
                }

                if (iconDest.isFile()) {
                    Bitmap highRes = BitmapFactory.decodeFile(iconDest.getPath());
                    if (highRes != null) {
                        this.icon = highRes;
                        this.iconFile = iconDest;
                        changed = true;
                    }
                }
            }

            if (this.icon != null && this.icon.getWidth() < 512) {
                this.icon = ExeIconExtractor.upscaleIfNecessary(this.icon, 512);
            }

            String path = null;
            if (customCoverArtPath != null && !customCoverArtPath.isEmpty()) {
                File file = new File(customCoverArtPath);
                if (file.isFile()) path = file.getPath();
            }

            if (path == null) {
                File file = new File(new File(container.getRootDir(), COVER_ART_DIR), this.name + ".png");
                if (file.isFile()) path = file.getPath();
            }

            if (path != null) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(path, options);
                options.inSampleSize = ImageUtils.calculateInSampleSize(options, 800, 800);
                options.inJustDecodeBounds = false;
                this.coverArt = BitmapFactory.decodeFile(path, options);
                changed = true;
            } else {
                File fallbackFile = new File(new File(container.getRootDir(), FALLBACK_COVER_ART_DIR), this.name + ".png");
                if (!fallbackFile.exists()) {
                    this.fallbackCoverArt = generateFallbackCoverArt(600, 900);
                    saveFallbackCoverArt(this.fallbackCoverArt);
                    changed = true;
                } else if (this.fallbackCoverArt == null) {
                    this.fallbackCoverArt = BitmapFactory.decodeFile(fallbackFile.getPath());
                    changed = true;
                }
            }

            String detectedVer = com.winlator.cmod.core.GameVersionDetector.detect(this);
            if (detectedVer != null && !detectedVer.isEmpty()) {
                changed = true;
            }

            if (changed && onShortcutLoadedListener != null) {
                onShortcutLoadedListener.onShortcutLoaded(this);
            }
        });
    }

    public File resolveExeFile() {
        if (this.path == null || this.path.isEmpty()) return null;

        String path = this.path.replace("\\", "/").trim();

        if (path.startsWith("\"") && path.endsWith("\""))
            path = path.substring(1, path.length() - 1);

        if (path.startsWith("/")) {
            File f = new File(path);
            if (f.exists()) return f;
        }

        if (path.length() >= 3 && path.charAt(1) == ':' && path.charAt(2) == '/') {
            String drive = path.substring(0, 1).toLowerCase();
            String relative = path.substring(3);

            if (this.container != null) {
                for (String[] entry : this.container.drivesIterator()) {
                    if (entry == null || entry.length < 2 || entry[0] == null || entry[1] == null) continue;
                    if (entry[0].replace(":", "").trim().equalsIgnoreCase(drive)) {
                        File f = new File(entry[1], relative);
                        if (f.exists()) return f;
                    }
                }
            }

            switch (drive) {
                case "c": {
                    File root = this.container != null ? this.container.getRootDir() : null;
                    if (root != null) {
                        File f = new File(root, ".wine/drive_c/" + relative);
                        if (f.exists()) return f;
                    }
                    break;
                }
                case "d": {
                    File f = new File(android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS), relative);
                    if (f.exists()) return f;
                    f = new File(android.os.Environment.getExternalStorageDirectory(), relative);
                    if (f.exists()) return f;
                    break;
                }
                case "z": {
                    File f = new File("/" + relative);
                    if (f.exists()) return f;
                    break;
                }
            }
        } else {
            File root = this.container != null ? this.container.getRootDir() : null;
            if (root != null) {
                File f = new File(root, path);
                if (f.exists()) return f;
            }
            File f = new File(path);
            if (f.exists()) return f;
        }

        return null;
    }

    public void setCoverArt(Bitmap coverArt) {
        this.coverArt = coverArt;
    }

    public String getCustomCoverArtPath() {
        return customCoverArtPath;
    }

    public void setCustomCoverArtPath(String path) {
        this.customCoverArtPath = path;
        putExtra("customCoverArtPath", path);
        saveData();
        Log.d("Shortcut", "Set and saved custom cover art path: " + path);
    }

    public JSONObject getExtraData() {
        return extraData;
    }

    public String getExtra(String name) {
        return getExtra(name, "");
    }

    public String getExtra(String name, String fallback) {
        try {
            return extraData.has(name) ? extraData.getString(name) : fallback;
        }
        catch (JSONException e) {
            return fallback;
        }
    }

    public void putExtra(String name, String value) {
        try {
            if (value != null) {
                extraData.put(name, value);
            }
            else extraData.remove(name);
        }
        catch (JSONException e) {}
    }

    public String getSteamId() {
        return getExtra("steam_id");
    }

    public String getCommunityImageUrl() {
        return getExtra("community_image");
    }

    public String getCoverArtUrl() {
        String communityUrl = getCommunityImageUrl();
        if (!communityUrl.isEmpty()) return communityUrl;

        String steamId = getSteamId();
        if (!steamId.isEmpty()) return "https://cdn.akamai.steamstatic.com/steam/apps/" + steamId + "/library_600x900.jpg";

        return null;
    }

    public void saveData() {
        StringBuilder content = new StringBuilder("[Desktop Entry]\n");
        for (String line : FileUtils.readLines(file)) {
            if (line.contains("[Extra Data]")) break;
            if (!line.contains("[Desktop Entry]") && !line.isEmpty()) content.append(line).append("\n");
        }

        if (extraData.length() > 0) {
            content.append("\n[Extra Data]\n");
            Iterator<String> keys = extraData.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                try {
                    content.append(key).append("=").append(extraData.getString(key)).append("\n");
                } catch (JSONException e) {}
            }
        }

        if (!file.getName().endsWith(".desktop")) {
            Log.e("Shortcut", "Incorrect file reference before saving: " + file.getPath());
            return;
        }

        FileUtils.writeString(file, content.toString());
    }

    public void genUUID() {
        if (getExtra("uuid").equals("")) {
            putExtra("uuid", UUID.randomUUID().toString());
            saveData();
        }
    }

    public void saveCustomCoverArt(Bitmap coverArt) {
        try {
            File dir = new File(container.getRootDir(), COVER_ART_DIR);
            if (!dir.exists() && !dir.mkdirs()) {
                Log.e("Shortcut", "Failed to create cover art directory: " + dir.getAbsolutePath());
            }

            File file = new File(dir, this.name + ".png");
            if (FileUtils.saveBitmapToFile(coverArt, file)) {
                this.coverArt = coverArt;
                setCustomCoverArtPath(file.getPath());
                Log.d("Shortcut", "Custom cover art saved at: " + file.getPath());
            } else {
                Log.e("Shortcut", "Failed to save custom cover art.");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void removeCustomCoverArt() {
        if (customCoverArtPath != null && !customCoverArtPath.isEmpty()) {
            File file = new File(customCoverArtPath);
            if (file.exists()) file.delete();
        }

        File coverArtFile = new File(new File(container.getRootDir(), COVER_ART_DIR), this.name + ".png");
        if (coverArtFile.exists()) coverArtFile.delete();

        File fallbackFile = new File(new File(container.getRootDir(), FALLBACK_COVER_ART_DIR), this.name + ".png");
        if (fallbackFile.exists()) fallbackFile.delete();

        this.customCoverArtPath = null;
        this.coverArt = null;
        this.fallbackCoverArt = null;
        putExtra("customCoverArtPath", null);
        putExtra("steam_id", null);
        putExtra("community_image", null);
        saveData();

        runBackgroundInitialization();
    }

    public boolean cloneToContainer(Container newContainer) {
        try {
            File newFile = new File(newContainer.getDesktopDir(), this.file.getName());
            ArrayList<String> lines = FileUtils.readLines(this.file);
            StringBuilder content = new StringBuilder();
            boolean found = false;

            for (String line : lines) {
                if (line.startsWith("container_id:")) {
                    content.append("container_id:").append(newContainer.id).append("\n");
                    found = true;
                } else {
                    content.append(line).append("\n");
                }
            }

            if (!found) {
                content.append("container_id:").append(newContainer.id).append("\n");
            }

            FileUtils.writeString(newFile, content.toString());

            if (this.iconFile != null && this.iconFile.isFile()) {
                File newIconFile = new File(newContainer.getIconsDir(64), this.iconFile.getName());
                FileUtils.copy(this.iconFile, newIconFile);
            }

            return true;
        } catch (Exception e) {
            Log.e("Shortcut", "Failed to clone shortcut to new container", e);
            return false;
        }
    }

    public int getContainerId() {
        return container.id;
    }

    public String getParentFolderName() {
        try {
            String p = path.replace("\\", "/");
            if (p.endsWith("/")) p = p.substring(0, p.length() - 1);
            String[] parts = p.split("/");
            
            // Start from the folder containing the file (length - 2)
            // and move up the tree until we find a non-generic folder name
            for (int i = parts.length - 2; i >= 0; i--) {
                String folder = parts[i];
                if (folder.isEmpty()) continue;
                
                String lower = folder.toLowerCase();
                if (lower.equals("bin") || lower.equals("binaries") || lower.equals("win32") || 
                    lower.equals("win64") || lower.equals("x86") || lower.equals("x64") || 
                    lower.equals("system") || lower.equals("system32") || lower.equals("engine") ||
                    lower.equals("release") || lower.equals("build") || lower.equals("dist") ||
                    lower.equals("game") || lower.equals("games") || lower.equals("setup") ||
                    lower.equals("common") || lower.equals("steamapps") || lower.equals("retail") ||
                    lower.equals("client") || lower.equals("core")) {
                    continue;
                }
                
                // Ignore drive letters or single characters
                if (folder.length() <= 2 && folder.endsWith(":")) continue;

                return folder;
            }
        } catch (Exception e) {}
        return "";
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, com.winlator.cmod.win32.PEParser.FileVersionInfo> VERSION_INFO_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    public String getExecutable() {
        String exe = "";
        try {
            List<String> lines = FileUtils.readLines(file);
            for (String line : lines) {
                String trimmedLine = line.trim();
                if (trimmedLine.toLowerCase().startsWith("exec=")) {
                    String exec = trimmedLine.substring(trimmedLine.indexOf("=") + 1).trim();
                    int lastBackslash = exec.lastIndexOf("\\");
                    if (lastBackslash != -1) {
                        exe = exec.substring(lastBackslash + 1);
                    } else {
                        int lastSlash = exec.lastIndexOf("/");
                        exe = (lastSlash != -1) ? exec.substring(lastSlash + 1) : exec;
                    }

                    // Remove quotes and arguments
                    if (exe.contains("\"")) exe = exe.split("\"")[0];
                    if (exe.contains(" ")) exe = exe.split(" ")[0];
                    exe = exe.trim();
                    break;
                }
            }
        }
        catch (Exception e) {
            Log.e("Shortcut", "Error reading shortcut file", e);
        }
        return exe;
    }

    public com.winlator.cmod.win32.PEParser.FileVersionInfo getFileVersionInfo() {
        String key = (this.file != null) ? this.file.getAbsolutePath() : this.name;
        com.winlator.cmod.win32.PEParser.FileVersionInfo cached = VERSION_INFO_CACHE.get(key);
        if (cached != null) return cached;

        try {
            File exe = resolveExeFile();
            if (exe != null && exe.isFile()) {
                com.winlator.cmod.win32.PEParser.FileVersionInfo info = com.winlator.cmod.win32.PEParser.getFileVersionInfo(exe);
                if (info != null) {
                    VERSION_INFO_CACHE.put(key, info);
                    return info;
                }
            }
        } catch (Throwable t) {
            Log.d("Shortcut", "Could not parse version info for " + name + ": " + t.getMessage());
        }
        return null;
    }

    public String getGameVersion() {
        String saved = getExtra("gameVersion");
        if (!saved.isEmpty()) return saved;
        return null;
    }
}
