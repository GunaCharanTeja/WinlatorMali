package com.winlator.cmod.widget;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.winlator.cmod.ThemeManager;
import com.winlator.cmod.ThemePreset;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

public class WinlatorHUD extends View {
    private static final String PREFS          = "winlator_hud";
    private static final String KEY_X          = "hud_x";
    private static final String KEY_Y          = "hud_y";
    private static final String KEY_VIS        = "hud_vis";
    private static final String KEY_SHOW       = "hud_show";
    private static final String KEY_SCALE      = "hud_scale";
    private static final String KEY_ALPHA      = "hud_alpha_int";
    private static final String KEY_VERT       = "hud_vertical";
    private static final String KEY_STYLE      = "hud_style";
    private static final String KEY_POS_PRESET = "hud_pos_preset";

    public static final int STYLE_CLASSIC  = 0;
    public static final int STYLE_MONO     = 1;
    public static final int STYLE_TILES    = 2;
    public static final int STYLE_ADAPTIVE = 3;
    private int currentStyle = STYLE_ADAPTIVE;

    public static final int PRESET_CUSTOM        = -1;
    public static final int PRESET_TOP_LEFT      = 0;
    public static final int PRESET_TOP_CENTER    = 1;
    public static final int PRESET_TOP_RIGHT     = 2;
    public static final int PRESET_MIDDLE_LEFT   = 3;
    public static final int PRESET_CENTER        = 4;
    public static final int PRESET_MIDDLE_RIGHT  = 5;
    public static final int PRESET_BOTTOM_LEFT   = 6;
    public static final int PRESET_BOTTOM_CENTER = 7;
    public static final int PRESET_BOTTOM_RIGHT  = 8;
    private int activePositionPreset = PRESET_TOP_LEFT;

    public static final int SHOW_FPS      = 1;
    public static final int SHOW_GPU      = 1<<1;
    public static final int SHOW_CPU      = 1<<2;
    public static final int SHOW_BATT     = 1<<3;
    public static final int SHOW_GRAPH    = 1<<4;
    public static final int SHOW_RENDERER = 1<<5;
    public static final int SHOW_RAM      = 1<<6;
    public static final int SHOW_BATT_PCT = 1<<7;
    public static final int SHOW_MONO     = 1<<8;
    public static final int SHOW_BORDER   = 1<<9;
    public static final int SHOW_COMPACT  = 1<<10;
    public static final int SHOW_WRAPPER  = 1<<11;
    public static final int SHOW_CPU_TEMP = 1<<12;
    public static final int SHOW_LOCKED   = 1<<13;
    private static final int SHOW_DEFAULT = SHOW_FPS | SHOW_CPU | SHOW_RAM | SHOW_BORDER | SHOW_LOCKED;

    private static final int C_WHITE = Color.WHITE;
    private static final int C_GPU  = Color.rgb(0xE0,0x40,0xFB);
    private static final int C_CPU  = Color.rgb(0x00,0xE5,0xFF);
    private static final int C_BATT = Color.rgb(0xFF,0x80,0x00);
    private static final int C_CHG  = Color.rgb(0x40,0xC4,0x40);
    private static final int C_TEMP = Color.rgb(0xEF,0x53,0x50);
    private static final int C_FPS  = Color.rgb(0x76,0xFF,0x03);
    private static final int C_REND = Color.rgb(0xFF,0xEA,0x00);
    private static final int C_RAM  = Color.rgb(0xB0,0xFF,0xB0);
    private static final int C_SEP  = Color.rgb(0x60,0x60,0x60);
    private static final int C_BORDER = Color.argb(150, 255, 255, 255);

    private float TS, PAD, GRAW, CORNER, density = 1.0f;

    private static final int TEXT_FLAGS = Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG | Paint.LINEAR_TEXT_FLAG;
    private final Paint pBg         = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBorder     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pVal        = new Paint(TEXT_FLAGS);
    private final Paint pGpu        = new Paint(TEXT_FLAGS);
    private final Paint pCpu        = new Paint(TEXT_FLAGS);
    private final Paint pBat        = new Paint(TEXT_FLAGS);
    private final Paint pTmp        = new Paint(TEXT_FLAGS);
    private final Paint pFps        = new Paint(TEXT_FLAGS);
    private final Paint pRend       = new Paint(TEXT_FLAGS);
    private final Paint pRam        = new Paint(TEXT_FLAGS);
    private final Paint pSep        = new Paint(TEXT_FLAGS);
    private final Paint pChg        = new Paint(TEXT_FLAGS);
    private final Paint pGraph      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pGraphBg    = new Paint();
    private final Paint pTileBg     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTileBorder = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF bgRect = new RectF();

    private float wLabelGpu, wLabelCpu, wLabelRam, wLabelPwr, wLabelTmp, wLabelCTmp, wLabelFps, wLabelApex, wSep;
    private float wVal100pct, wValFps, wValApex, wValWatt, wValTemp, wValBInfo;

    private boolean layoutDirty = true;

    private String strGpu = "N/A", strCpu = "N/A", strRam = "N/A";
    private String strPwr = "N/A", strTmp = "", strCTmp = "", strFps = "0", strPct = "";
    private String strRend = "OpenGL", strWrapper = "WineD3D";
    private boolean snapCharging = false;

    private final SharedPreferences prefs;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private HudDataSource dataSource;

    private final AtomicInteger frameAccum = new AtomicInteger(0);
    private long lastFpsNs = 0;
    private float snapFps = 0;
    private float snapTotalFps = 0;
    private boolean apexActive = false;
    private float apexMultiplier = 2.0f;

    private int snapGpu=-1, snapCpu=-1, snapMw=-1, snapTmp=-1, snapCTmp=-1, snapPct=-1, snapRam=-1;
    private String rendererLabel = "OpenGL";
    private boolean isNative = false;

    private static final int GBUF = 40;
    private final float[] graph = new float[GBUF];
    private int gHead = 0;
    private float gMax = 60f;

    private int showMask = SHOW_DEFAULT;
    private float hudAlpha = 1f;
    private boolean userEnabled = false;
    private boolean vertical = false;

    private float touchX, touchY, startX, startY;
    private boolean dragging = false;
    private static final float DRAG_THRESH = 10f;
    private long touchDownMs = 0;

    private boolean redrawScheduled = false;
    private Path cachedPath = null;
    private int lastGHead = -1;
    private final Runnable redrawRunnable = () -> {
        redrawScheduled = false;
        try {
            snapshot();
            
            int reqW = (int) Math.ceil(vertical ? measureVertical() : measureHorizontal());
            int lineH = (int) Math.ceil(TS + PAD * 2);
            int reqH = vertical ? (int) Math.ceil(countVerticalRows() * lineH + (currentStyle == STYLE_TILES ? (countVerticalRows() - 1) * 3f * density : 0)) : lineH;

            if (reqW != getWidth() || reqH != getHeight() || layoutDirty) {
                layoutDirty = false;
                requestLayout();
            }
            invalidate();
        } catch (Exception ignored) {
        }
        if (getVisibility() == VISIBLE) scheduleRedraw();
    };

    public WinlatorHUD(Context context) { this(context, null); }

    public WinlatorHUD(Context context, AttributeSet attrs) {
        super(context, attrs);
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        float d = context.getResources().getDisplayMetrics().density;
        density = d;
        TS     = 12f * d;
        PAD    = 6f * d;
        GRAW   = 70f * d;
        CORNER = 5f * d;
        initPaints();
        loadPrefs();
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    private void initPaints() {
        Typeface mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
        pBg.setStyle(Paint.Style.FILL);
        pBg.setColor(Color.argb(180, 0, 0, 0));
        pBorder.setStyle(Paint.Style.STROKE);
        pBorder.setStrokeWidth(1.5f);
        pBorder.setColor(C_BORDER);

        pTileBg.setStyle(Paint.Style.FILL);
        pTileBg.setColor(Color.argb(170, 20, 24, 30));
        pTileBorder.setStyle(Paint.Style.STROKE);
        pTileBorder.setStrokeWidth(1.2f * density);
        pTileBorder.setColor(Color.argb(65, 255, 255, 255));

        pVal.setTextSize(TS);       pVal.setTypeface(mono);  pVal.setColor(C_WHITE);
        pGpu.setTextSize(TS);       pGpu.setTypeface(mono);  pGpu.setColor(C_GPU);
        pCpu.setTextSize(TS);       pCpu.setTypeface(mono);  pCpu.setColor(C_CPU);
        pBat.setTextSize(TS);       pBat.setTypeface(mono);  pBat.setColor(C_BATT);
        pTmp.setTextSize(TS);       pTmp.setTypeface(mono);  pTmp.setColor(C_TEMP);
        pFps.setTextSize(TS);       pFps.setTypeface(mono);  pFps.setColor(C_FPS);
        pRend.setTextSize(TS);      pRend.setTypeface(mono); pRend.setColor(C_REND);
        pRam.setTextSize(TS);       pRam.setTypeface(mono);  pRam.setColor(C_RAM);
        pSep.setTextSize(TS);       pSep.setTypeface(mono);  pSep.setColor(C_SEP);
        pChg.setTextSize(TS);       pChg.setTypeface(mono);  pChg.setColor(C_CHG);

        pGraph.setStyle(Paint.Style.STROKE);
        pGraph.setStrokeWidth(1.5f * density);
        pGraph.setStrokeCap(Paint.Cap.ROUND);
        pGraph.setStrokeJoin(Paint.Join.ROUND);
        pGraphBg.setStyle(Paint.Style.FILL);
        pGraphBg.setColor(Color.argb(70, 0, 0, 0));

        wLabelGpu  = pGpu.measureText("GPU ");
        wLabelCpu  = pCpu.measureText("CPU ");
        wLabelRam  = pRam.measureText("RAM ");
        wLabelPwr  = pBat.measureText("PWR ");
        wLabelTmp  = pTmp.measureText("TMP ");
        wLabelCTmp = pTmp.measureText("CTMP ");
        wLabelFps  = pFps.measureText("FPS ");
        wLabelApex = pFps.measureText("Apex ");
        wSep       = pSep.measureText(" | ");

        wVal100pct = pVal.measureText("100%");
        wValFps    = pFps.measureText("000");
        wValApex   = pFps.measureText("000 (0.0x)");
        wValWatt   = pVal.measureText("00.0W");
        wValTemp   = pVal.measureText("00°C");
        wValBInfo  = pVal.measureText("00.0W (100%)");
    }

    public static String formatWrapperName(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "WineD3D";
        String s = raw.trim();
        String lower = s.toLowerCase(Locale.US);
        if (lower.startsWith("dxvk")) {
            return s.replaceFirst("(?i)dxvk", "DXVK");
        } else if (lower.startsWith("vkd3d")) {
            return s.replaceFirst("(?i)vkd3d", "VKD3D");
        } else if (lower.startsWith("d8vk")) {
            return s.replaceFirst("(?i)d8vk", "D8VK");
        } else if (lower.startsWith("wined3d")) {
            return s.replaceFirst("(?i)wined3d", "WineD3D");
        } else if (lower.equalsIgnoreCase("cnc-ddraw")) {
            return "CnC-DDraw";
        } else if (lower.equalsIgnoreCase("nodraw")) {
            return "NoDraw";
        }
        if (Character.isLowerCase(s.charAt(0))) {
            return Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }
        return s;
    }

    public static String formatRendererName(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "OpenGL";
        String s = raw.trim();
        String lower = s.toLowerCase(Locale.US);
        if (lower.equals("displayx")) return "DisplayX";
        if (lower.equals("opengl")) return "OpenGL";
        if (lower.equals("vulkan")) return "Vulkan";
        if (lower.equals("virgl")) return "VirGL";
        if (lower.equals("zink")) return "Zink";
        if (lower.startsWith("turnip")) {
            return s.replaceFirst("(?i)turnip", "Turnip");
        }
        if (Character.isLowerCase(s.charAt(0))) {
            return Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }
        return s;
    }

    public void countFrame() {
        frameAccum.incrementAndGet();
    }

    public void onFrame() {
        countFrame();
    }

    public void setDataSource(HudDataSource dataSource) {
        this.dataSource = dataSource;
        if (dataSource != null && userEnabled) dataSource.start();
    }

    private void snapshot() {
        long now = System.nanoTime();
        if (lastFpsNs == 0) { lastFpsNs = now; frameAccum.set(0); }
        else {
            long dt = now - lastFpsNs;
            if (dt >= 500_000_000L) {
                int frames = frameAccum.getAndSet(0);
                snapFps = (frames * 1_000_000_000.0f) / dt;
                snapTotalFps = apexActive ? snapFps * apexMultiplier : snapFps;
                lastFpsNs = now;
                if (!apexActive) {
                    strFps = String.valueOf(Math.round(snapFps));
                }

                graph[gHead % GBUF] = snapFps;
                gHead++;
                if (snapFps > gMax) gMax = Math.max(60f, snapFps * 1.1f);
            }
        }

        if (dataSource != null) {
            int gpu = dataSource.gpuLoad.get();
            if (gpu != snapGpu) {
                snapGpu = gpu;
                strGpu = (gpu >= 0) ? gpu + "%" : "N/A";
            }
            int cpu = dataSource.cpuLoad.get();
            if (cpu != snapCpu) {
                snapCpu = cpu;
                strCpu = (cpu >= 0) ? cpu + "%" : "N/A";
            }
            int ram = dataSource.ramUsagePct.get();
            if (ram != snapRam) {
                snapRam = ram;
                strRam = (ram >= 0) ? ram + "%" : "N/A";
            }
            int tmp = dataSource.batteryTempC.get();
            if (tmp != snapTmp) {
                snapTmp = tmp;
                strTmp = (tmp > 0) ? tmp + "°C" : "";
            }
            int ctmp = dataSource.cpuTempC.get();
            if (ctmp != snapCTmp) {
                snapCTmp = ctmp;
                strCTmp = (ctmp > 0) ? ctmp + "°C" : "";
            }
            int pct = dataSource.batteryPct.get();
            if (pct != snapPct) {
                snapPct = pct;
                strPct = (pct >= 0) ? pct + "%" : "";
            }
            int mw = dataSource.batteryMw.get();
            if (mw != snapMw) {
                snapMw = mw;
                snapCharging = (mw == -2);
                if (snapCharging)   strPwr = "CHG";
                else if (mw > 0)    strPwr = String.format(Locale.US, "%.1fW", mw / 1000f);
                else                strPwr = "N/A";
            }
        }
    }

    private String getFpsDisplayText() {
        if (apexActive) {
            return String.format(Locale.US, "%s (%.1fx)", strFps, apexMultiplier);
        }
        return strFps;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (getVisibility() != VISIBLE) return;
        try {
            boolean mono = (currentStyle == STYLE_MONO) || ((showMask & SHOW_MONO) != 0);
            updatePaintColors(mono);

            if (currentStyle == STYLE_TILES) {
                if (vertical) drawTilesVertical(c);
                else          drawTilesHorizontal(c);
            } else {
                bgRect.set(0, 0, getWidth(), getHeight());
                float corner = vertical ? (currentStyle == STYLE_ADAPTIVE ? (8f * density) : CORNER) : (bgRect.height() / 2f);

                if (currentStyle == STYLE_ADAPTIVE) {
                    Context ctx = getContext();
                    int surfaceCol = ThemeManager.getSurfaceColor(ctx);
                    int accentCol = ThemeManager.getAccentColor(ctx);

                    pBg.setColor(Color.argb(220, Color.red(surfaceCol), Color.green(surfaceCol), Color.blue(surfaceCol)));
                    pBg.setShadowLayer(5f * density, 0, 1.5f * density, Color.argb(160, 0, 0, 0));
                    c.drawRoundRect(bgRect, corner, corner, pBg);
                    pBg.clearShadowLayer();

                    if ((showMask & SHOW_BORDER) != 0) {
                        pBorder.setColor(Color.argb(140, Color.red(accentCol), Color.green(accentCol), Color.blue(accentCol)));
                        pBorder.setStrokeWidth(1.5f * density);
                        c.drawRoundRect(bgRect, corner, corner, pBorder);
                    }
                } else {
                    pBg.setColor(Color.argb(180, 0, 0, 0));
                    pBg.setShadowLayer(4f, 0, 0, Color.BLACK);
                    c.drawRoundRect(bgRect, corner, corner, pBg);
                    pBg.clearShadowLayer();

                    if ((showMask & SHOW_BORDER) != 0) c.drawRoundRect(bgRect, corner, corner, pBorder);
                }

                if (vertical) drawClassicVertical(c);
                else          drawClassicHorizontal(c);
            }
        } catch (Exception ignored) {
        }
    }

    private void updatePaintColors(boolean mono) {
        if (currentStyle == STYLE_ADAPTIVE) {
            Context ctx = getContext();
            int accent = ThemeManager.getAccentColor(ctx);
            int surface = ThemeManager.getSurfaceColor(ctx);
            int onSurface = ThemeManager.getOnSurfaceTextColor(ctx);

            float fps = apexActive ? snapTotalFps : snapFps;
            int fpsColor = fps >= 55 ? 0xFF00E676 : (fps >= 25 ? accent : 0xFFFF5252);
            pFps.setColor(fpsColor);
            pGraph.setColor(accent);
            pGraphBg.setColor(Color.argb(70, Color.red(surface), Color.green(surface), Color.blue(surface)));

            pBorder.setColor(Color.argb(140, Color.red(accent), Color.green(accent), Color.blue(accent)));
            pBorder.setStrokeWidth(1.5f * density);

            pRend.setColor(accent);
            pGpu.setColor(ThemePreset.lerp(accent, 0xFFE040FB, 0.40f));
            pCpu.setColor(ThemePreset.lerp(accent, 0xFF00E5FF, 0.40f));
            pRam.setColor(ThemePreset.lerp(accent, 0xFF69F0AE, 0.35f));
            pBat.setColor(ThemePreset.lerp(accent, 0xFFFFAB40, 0.40f));
            pTmp.setColor(ThemePreset.lerp(accent, 0xFFFF5252, 0.45f));
            pSep.setColor(Color.argb(130, Color.red(accent), Color.green(accent), Color.blue(accent)));
            pChg.setColor(0xFF00E676);
            pVal.setColor(onSurface);
            return;
        }

        float fps = apexActive ? snapTotalFps : snapFps;
        int fpsColor = mono ? C_WHITE : (fps >= 55 ? C_FPS : (fps >= 25 ? C_REND : C_TEMP));
        pFps.setColor(fpsColor);
        pGraph.setColor(fpsColor);
        pGpu.setColor(mono ? C_WHITE : C_GPU);
        pCpu.setColor(mono ? C_WHITE : C_CPU);
        pBat.setColor(mono ? C_WHITE : C_BATT);
        pTmp.setColor(mono ? C_WHITE : C_TEMP);
        pRend.setColor(mono ? C_WHITE : C_REND);
        pRam.setColor(mono ? C_WHITE : C_RAM);
        pVal.setColor(C_WHITE);
        pSep.setColor(C_SEP);
        pChg.setColor(C_CHG);
        pBorder.setColor(C_BORDER);
        pBorder.setStrokeWidth(1.5f);
    }

    private void drawClassicHorizontal(Canvas c) {
        boolean compact = (showMask & SHOW_COMPACT) != 0;
        float rowH = getHeight();
        float radius = rowH / 2f;
        float x = radius;
        boolean first = true;

        if ((showMask & SHOW_RENDERER) != 0) {
            if (!first) x += drawSep(c, x, 0);
            float baseline = getBaseline(pRend, 0, rowH);
            c.drawText(strRend, x, baseline, pRend);
            x += pRend.measureText(strRend);
            first = false;
        }
        if ((showMask & SHOW_WRAPPER) != 0) {
            if (!first) x += drawSep(c, x, 0);
            float baseline = getBaseline(pRend, 0, rowH);
            c.drawText(strWrapper, x, baseline, pRend);
            x += pRend.measureText(strWrapper);
            first = false;
        }
        if ((showMask & SHOW_GPU) != 0) {
            if (!first) x += drawSep(c, x, 0);
            float baseline = getBaseline(compact ? pGpu : pVal, 0, rowH);
            if (!compact) { c.drawText("GPU ", x, baseline, pGpu); x += wLabelGpu; }
            float vw = Math.max(pVal.measureText(strGpu), wVal100pct);
            c.drawText(strGpu, x, baseline, compact ? pGpu : pVal);
            x += vw;
            first = false;
        }
        if ((showMask & SHOW_CPU) != 0) {
            if (!first) x += drawSep(c, x, 0);
            float baseline = getBaseline(compact ? pCpu : pVal, 0, rowH);
            if (!compact) { c.drawText("CPU ", x, baseline, pCpu); x += wLabelCpu; }
            float vw = Math.max(pVal.measureText(strCpu), wVal100pct);
            c.drawText(strCpu, x, baseline, compact ? pCpu : pVal);
            x += vw;
            first = false;
        }
        if ((showMask & SHOW_RAM) != 0) {
            if (!first) x += drawSep(c, x, 0);
            float baseline = getBaseline(compact ? pRam : pVal, 0, rowH);
            if (!compact) { c.drawText("RAM ", x, baseline, pRam); x += wLabelRam; }
            float vw = Math.max(pVal.measureText(strRam), wVal100pct);
            c.drawText(strRam, x, baseline, compact ? pRam : pVal);
            x += vw;
            first = false;
        }
        if ((showMask & SHOW_BATT) != 0) {
            if (!first) x += drawSep(c, x, 0);
            float baseline = getBaseline(compact ? pBat : pVal, 0, rowH);
            if (!compact) { c.drawText("PWR ", x, baseline, pBat); x += wLabelPwr; }
            float vw = Math.max((compact ? pBat : pVal).measureText(strPwr), wValWatt);
            c.drawText(strPwr, x, baseline, snapCharging ? pChg : (compact ? pBat : pVal));
            x += vw;
            first = false;
            if ((showMask & SHOW_BATT_PCT) != 0) {
                x += drawSep(c, x, 0);
                float pw = Math.max((compact ? pBat : pVal).measureText(strPct.isEmpty() ? "0%" : strPct), wVal100pct);
                c.drawText(strPct.isEmpty() ? "0%" : strPct, x, baseline, compact ? pBat : pVal);
                x += pw;
            }
            if (!strTmp.isEmpty() || !compact) {
                x += drawSep(c, x, 0);
                float tw = Math.max((compact ? pTmp : pVal).measureText(strTmp.isEmpty() ? "00°C" : strTmp), wValTemp);
                if (!compact) { c.drawText("TMP ", x, baseline, pTmp); x += wLabelTmp; }
                c.drawText(strTmp, x, baseline, compact ? pTmp : pVal);
                x += tw;
            }
        }
        if ((showMask & SHOW_CPU_TEMP) != 0 && (!strCTmp.isEmpty() || !compact)) {
            if (!first) x += drawSep(c, x, 0);
            float baseline = getBaseline(compact ? pTmp : pVal, 0, rowH);
            if (!compact) { c.drawText("CTMP ", x, baseline, pTmp); x += wLabelCTmp; }
            float vw = Math.max((compact ? pTmp : pVal).measureText(strCTmp.isEmpty() ? "00°C" : strCTmp), wValTemp);
            c.drawText(strCTmp, x, baseline, compact ? pTmp : pVal);
            x += vw;
            first = false;
        }
        if ((showMask & SHOW_FPS) != 0) {
            if (!first) x += drawSep(c, x, 0);
            float fb = getBaseline(pFps, 0, rowH);
            String label = apexActive ? "Apex " : "FPS ";
            float labelW = apexActive ? wLabelApex : wLabelFps;
            if (!compact) { c.drawText(label, x, fb, pFps); x += labelW; }
            String fpsText = getFpsDisplayText();
            float fw = Math.max(pFps.measureText(fpsText), apexActive ? wValApex : wValFps);
            c.drawText(fpsText, x, fb, pFps);
            x += fw;
            if ((showMask & SHOW_GRAPH) != 0) {
                x += PAD;
                drawInlineGraph(c, x, PAD, GRAW, rowH - PAD * 2);
            }
            first = false;
        }
    }

    private void drawTilesHorizontal(Canvas c) {
        boolean compact = (showMask & SHOW_COMPACT) != 0;
        float rowH = getHeight();
        float x = 0;
        float tileGap = 5f * density;
        float tilePad = 7f * density;
        float tileRadius = 5f * density;
        boolean drawBorder = (showMask & SHOW_BORDER) != 0;

        if ((showMask & SHOW_RENDERER) != 0) {
            float tw = pRend.measureText(strRend) + tilePad * 2;
            c.drawRoundRect(new RectF(x, 0, x + tw, rowH), tileRadius, tileRadius, pTileBg);
            if (drawBorder) c.drawRoundRect(new RectF(x, 0, x + tw, rowH), tileRadius, tileRadius, pTileBorder);
            c.drawText(strRend, x + tilePad, getBaseline(pRend, 0, rowH), pRend);
            x += tw + tileGap;
        }
        if ((showMask & SHOW_WRAPPER) != 0) {
            float tw = pRend.measureText(strWrapper) + tilePad * 2;
            c.drawRoundRect(new RectF(x, 0, x + tw, rowH), tileRadius, tileRadius, pTileBg);
            if (drawBorder) c.drawRoundRect(new RectF(x, 0, x + tw, rowH), tileRadius, tileRadius, pTileBorder);
            c.drawText(strWrapper, x + tilePad, getBaseline(pRend, 0, rowH), pRend);
            x += tw + tileGap;
        }
        if ((showMask & SHOW_GPU) != 0) {
            float labelW = compact ? 0 : wLabelGpu;
            float vw = Math.max(pVal.measureText(strGpu), wVal100pct);
            float tw = labelW + vw + tilePad * 2;
            c.drawRoundRect(new RectF(x, 0, x + tw, rowH), tileRadius, tileRadius, pTileBg);
            if (drawBorder) c.drawRoundRect(new RectF(x, 0, x + tw, rowH), tileRadius, tileRadius, pTileBorder);
            float baseline = getBaseline(compact ? pGpu : pVal, 0, rowH);
            if (!compact) c.drawText("GPU ", x + tilePad, baseline, pGpu);
            c.drawText(strGpu, x + tilePad + labelW, baseline, compact ? pGpu : pVal);
            x += tw + tileGap;
        }
        if ((showMask & SHOW_CPU) != 0) {
            float labelW = compact ? 0 : wLabelCpu;
            float vw = Math.max(pVal.measureText(strCpu), wVal100pct);
            float tw = labelW + vw + tilePad * 2;
            c.drawRoundRect(new RectF(x, 0, x + tw, rowH), tileRadius, tileRadius, pTileBg);
            if (drawBorder) c.drawRoundRect(new RectF(x, 0, x + tw, rowH), tileRadius, tileRadius, pTileBorder);
            float baseline = getBaseline(compact ? pCpu : pVal, 0, rowH);
            if (!compact) c.drawText("CPU ", x + tilePad, baseline, pCpu);
            c.drawText(strCpu, x + tilePad + labelW, baseline, compact ? pCpu : pVal);
            x += tw + tileGap;
        }
        if ((showMask & SHOW_RAM) != 0) {
            float labelW = compact ? 0 : wLabelRam;
            float vw = Math.max(pVal.measureText(strRam), wVal100pct);
            float tw = labelW + vw + tilePad * 2;
            c.drawRoundRect(new RectF(x, 0, x + tw, rowH), tileRadius, tileRadius, pTileBg);
            if (drawBorder) c.drawRoundRect(new RectF(x, 0, x + tw, rowH), tileRadius, tileRadius, pTileBorder);
            float baseline = getBaseline(compact ? pRam : pVal, 0, rowH);
            if (!compact) c.drawText("RAM ", x + tilePad, baseline, pRam);
            c.drawText(strRam, x + tilePad + labelW, baseline, compact ? pRam : pVal);
            x += tw + tileGap;
        }
        if ((showMask & SHOW_BATT) != 0) {
            float labelW = compact ? 0 : wLabelPwr;
            float vw = Math.max((compact ? pBat : pVal).measureText(strPwr), wValWatt);
            float tw = labelW + vw + tilePad * 2;
            c.drawRoundRect(new RectF(x, 0, x + tw, rowH), tileRadius, tileRadius, pTileBg);
            if (drawBorder) c.drawRoundRect(new RectF(x, 0, x + tw, rowH), tileRadius, tileRadius, pTileBorder);
            float baseline = getBaseline(compact ? pBat : pVal, 0, rowH);
            if (!compact) c.drawText("PWR ", x + tilePad, baseline, pBat);
            c.drawText(strPwr, x + tilePad + labelW, baseline, snapCharging ? pChg : (compact ? pBat : pVal));
            x += tw + tileGap;

            if ((showMask & SHOW_BATT_PCT) != 0) {
                float pw = Math.max((compact ? pBat : pVal).measureText(strPct.isEmpty() ? "0%" : strPct), wVal100pct);
                float ptw = pw + tilePad * 2;
                c.drawRoundRect(new RectF(x, 0, x + ptw, rowH), tileRadius, tileRadius, pTileBg);
                if (drawBorder) c.drawRoundRect(new RectF(x, 0, x + ptw, rowH), tileRadius, tileRadius, pTileBorder);
                c.drawText(strPct.isEmpty() ? "0%" : strPct, x + tilePad, baseline, compact ? pBat : pVal);
                x += ptw + tileGap;
            }
            if (!strTmp.isEmpty() || !compact) {
                float tLabelW = compact ? 0 : wLabelTmp;
                float twVal = Math.max((compact ? pTmp : pVal).measureText(strTmp.isEmpty() ? "00°C" : strTmp), wValTemp);
                float tmptw = tLabelW + twVal + tilePad * 2;
                c.drawRoundRect(new RectF(x, 0, x + tmptw, rowH), tileRadius, tileRadius, pTileBg);
                if (drawBorder) c.drawRoundRect(new RectF(x, 0, x + tmptw, rowH), tileRadius, tileRadius, pTileBorder);
                float tbl = getBaseline(compact ? pTmp : pVal, 0, rowH);
                if (!compact) c.drawText("TMP ", x + tilePad, tbl, pTmp);
                c.drawText(strTmp, x + tilePad + tLabelW, tbl, compact ? pTmp : pVal);
                x += tmptw + tileGap;
            }
        }
        if ((showMask & SHOW_CPU_TEMP) != 0 && (!strCTmp.isEmpty() || !compact)) {
            float tLabelW = compact ? 0 : wLabelCTmp;
            float twVal = Math.max((compact ? pTmp : pVal).measureText(strCTmp.isEmpty() ? "00°C" : strCTmp), wValTemp);
            float ctmptw = tLabelW + twVal + tilePad * 2;
            c.drawRoundRect(new RectF(x, 0, x + ctmptw, rowH), tileRadius, tileRadius, pTileBg);
            if (drawBorder) c.drawRoundRect(new RectF(x, 0, x + ctmptw, rowH), tileRadius, tileRadius, pTileBorder);
            float tbl = getBaseline(compact ? pTmp : pVal, 0, rowH);
            if (!compact) c.drawText("CTMP ", x + tilePad, tbl, pTmp);
            c.drawText(strCTmp, x + tilePad + tLabelW, tbl, compact ? pTmp : pVal);
            x += ctmptw + tileGap;
        }
        if ((showMask & SHOW_FPS) != 0) {
            String label = apexActive ? "Apex " : "FPS ";
            float labelW = compact ? 0 : (apexActive ? wLabelApex : wLabelFps);
            String fpsText = getFpsDisplayText();
            float fw = Math.max(pFps.measureText(fpsText), apexActive ? wValApex : wValFps);
            float graphW = ((showMask & SHOW_GRAPH) != 0) ? (GRAW + PAD) : 0;
            float fpstw = labelW + fw + graphW + tilePad * 2;
            c.drawRoundRect(new RectF(x, 0, x + fpstw, rowH), tileRadius, tileRadius, pTileBg);
            if (drawBorder) c.drawRoundRect(new RectF(x, 0, x + fpstw, rowH), tileRadius, tileRadius, pTileBorder);
            float fb = getBaseline(pFps, 0, rowH);
            if (!compact) c.drawText(label, x + tilePad, fb, pFps);
            c.drawText(fpsText, x + tilePad + labelW, fb, pFps);
            if ((showMask & SHOW_GRAPH) != 0) {
                drawInlineGraph(c, x + tilePad + labelW + fw + PAD, PAD, GRAW, rowH - PAD * 2);
            }
        }
    }

    private float getBaseline(Paint p, float y, float height) {
        Paint.FontMetrics fm = p.getFontMetrics();
        return y + (height - (fm.ascent + fm.descent)) / 2f;
    }

    private void drawClassicVertical(Canvas c) {
        boolean compact = (showMask & SHOW_COMPACT) != 0;
        float lineH = TS + PAD * 2;
        float y     = 0;
        float sidePad = 8f * density;

        if ((showMask & SHOW_RENDERER) != 0) {
            c.drawText(strRend, sidePad, getBaseline(pRend, y, lineH), pRend);
            y += lineH;
        }
        if ((showMask & SHOW_WRAPPER) != 0) {
            c.drawText(strWrapper, sidePad, getBaseline(pRend, y, lineH), pRend);
            y += lineH;
        }
        if ((showMask & SHOW_GPU) != 0) {
            float bl = getBaseline(compact ? pGpu : pVal, y, lineH);
            if (!compact) c.drawText("GPU ", sidePad, bl, pGpu);
            c.drawText(strGpu, sidePad + (compact ? 0 : wLabelGpu), bl, compact ? pGpu : pVal);
            y += lineH;
        }
        if ((showMask & SHOW_CPU) != 0) {
            float bl = getBaseline(compact ? pCpu : pVal, y, lineH);
            if (!compact) c.drawText("CPU ", sidePad, bl, pCpu);
            c.drawText(strCpu, sidePad + (compact ? 0 : wLabelCpu), bl, compact ? pCpu : pVal);
            y += lineH;
        }
        if ((showMask & SHOW_RAM) != 0) {
            float bl = getBaseline(compact ? pRam : pVal, y, lineH);
            if (!compact) c.drawText("RAM ", sidePad, bl, pRam);
            c.drawText(strRam, sidePad + (compact ? 0 : wLabelRam), bl, compact ? pRam : pVal);
            y += lineH;
        }
        if ((showMask & SHOW_BATT) != 0) {
            float bl = getBaseline(compact ? pBat : pVal, y, lineH);
            if (!compact) c.drawText("PWR ", sidePad, bl, pBat);
            String bInfo = strPwr + ((showMask & SHOW_BATT_PCT) != 0 ? " (" + (strPct.isEmpty() ? "0%" : strPct) + ")" : "");
            c.drawText(bInfo, sidePad + (compact ? 0 : wLabelPwr), bl, snapCharging ? pChg : (compact ? pBat : pVal));
            y += lineH;
            if (!strTmp.isEmpty() || !compact) {
                float tbl = getBaseline(compact ? pTmp : pVal, y, lineH);
                if (!compact) c.drawText("TMP ", sidePad, tbl, pTmp);
                c.drawText(strTmp, sidePad + (compact ? 0 : wLabelTmp), tbl, compact ? pTmp : pVal);
                y += lineH;
            }
        }
        if ((showMask & SHOW_CPU_TEMP) != 0 && (!strCTmp.isEmpty() || !compact)) {
            float bl = getBaseline(compact ? pTmp : pVal, y, lineH);
            if (!compact) c.drawText("CTMP ", sidePad, bl, pTmp);
            c.drawText(strCTmp, sidePad + (compact ? 0 : wLabelCTmp), bl, compact ? pTmp : pVal);
            y += lineH;
        }
        if ((showMask & SHOW_FPS) != 0) {
            float bl = getBaseline(pFps, y, lineH);
            String label = apexActive ? "Apex " : "FPS ";
            float labelW = apexActive ? wLabelApex : wLabelFps;
            if (!compact) c.drawText(label, sidePad, bl, pFps);
            String fpsText = getFpsDisplayText();
            c.drawText(fpsText, sidePad + (compact ? 0 : labelW), bl, pFps);
        }
    }

    private void drawTilesVertical(Canvas c) {
        boolean compact = (showMask & SHOW_COMPACT) != 0;
        float lineH = TS + PAD * 2;
        float y = 0;
        float w = getWidth();
        float tileGap = 3f * density;
        float tileRadius = 5f * density;
        float sidePad = 8f * density;
        boolean drawBorder = (showMask & SHOW_BORDER) != 0;

        if ((showMask & SHOW_RENDERER) != 0) {
            c.drawRoundRect(new RectF(0, y, w, y + lineH), tileRadius, tileRadius, pTileBg);
            if (drawBorder) c.drawRoundRect(new RectF(0, y, w, y + lineH), tileRadius, tileRadius, pTileBorder);
            c.drawText(strRend, sidePad, getBaseline(pRend, y, lineH), pRend);
            y += lineH + tileGap;
        }
        if ((showMask & SHOW_WRAPPER) != 0) {
            c.drawRoundRect(new RectF(0, y, w, y + lineH), tileRadius, tileRadius, pTileBg);
            if (drawBorder) c.drawRoundRect(new RectF(0, y, w, y + lineH), tileRadius, tileRadius, pTileBorder);
            c.drawText(strWrapper, sidePad, getBaseline(pRend, y, lineH), pRend);
            y += lineH + tileGap;
        }
        if ((showMask & SHOW_GPU) != 0) {
            c.drawRoundRect(new RectF(0, y, w, y + lineH), tileRadius, tileRadius, pTileBg);
            if (drawBorder) c.drawRoundRect(new RectF(0, y, w, y + lineH), tileRadius, tileRadius, pTileBorder);
            float bl = getBaseline(compact ? pGpu : pVal, y, lineH);
            if (!compact) c.drawText("GPU ", sidePad, bl, pGpu);
            c.drawText(strGpu, sidePad + (compact ? 0 : wLabelGpu), bl, compact ? pGpu : pVal);
            y += lineH + tileGap;
        }
        if ((showMask & SHOW_CPU) != 0) {
            c.drawRoundRect(new RectF(0, y, w, y + lineH), tileRadius, tileRadius, pTileBg);
            if (drawBorder) c.drawRoundRect(new RectF(0, y, w, y + lineH), tileRadius, tileRadius, pTileBorder);
            float bl = getBaseline(compact ? pCpu : pVal, y, lineH);
            if (!compact) c.drawText("CPU ", sidePad, bl, pCpu);
            c.drawText(strCpu, sidePad + (compact ? 0 : wLabelCpu), bl, compact ? pCpu : pVal);
            y += lineH + tileGap;
        }
        if ((showMask & SHOW_RAM) != 0) {
            c.drawRoundRect(new RectF(0, y, w, y + lineH), tileRadius, tileRadius, pTileBg);
            if (drawBorder) c.drawRoundRect(new RectF(0, y, w, y + lineH), tileRadius, tileRadius, pTileBorder);
            float bl = getBaseline(compact ? pRam : pVal, y, lineH);
            if (!compact) c.drawText("RAM ", sidePad, bl, pRam);
            c.drawText(strRam, sidePad + (compact ? 0 : wLabelRam), bl, compact ? pRam : pVal);
            y += lineH + tileGap;
        }
        if ((showMask & SHOW_BATT) != 0) {
            c.drawRoundRect(new RectF(0, y, w, y + lineH), tileRadius, tileRadius, pTileBg);
            if (drawBorder) c.drawRoundRect(new RectF(0, y, w, y + lineH), tileRadius, tileRadius, pTileBorder);
            float bl = getBaseline(compact ? pBat : pVal, y, lineH);
            if (!compact) c.drawText("PWR ", sidePad, bl, pBat);
            String bInfo = strPwr + ((showMask & SHOW_BATT_PCT) != 0 ? " (" + (strPct.isEmpty() ? "0%" : strPct) + ")" : "");
            c.drawText(bInfo, sidePad + (compact ? 0 : wLabelPwr), bl, snapCharging ? pChg : (compact ? pBat : pVal));
            y += lineH + tileGap;
            if (!strTmp.isEmpty() || !compact) {
                c.drawRoundRect(new RectF(0, y, w, y + lineH), tileRadius, tileRadius, pTileBg);
                if (drawBorder) c.drawRoundRect(new RectF(0, y, w, y + lineH), tileRadius, tileRadius, pTileBorder);
                float tbl = getBaseline(compact ? pTmp : pVal, y, lineH);
                if (!compact) c.drawText("TMP ", sidePad, tbl, pTmp);
                c.drawText(strTmp, sidePad + (compact ? 0 : wLabelTmp), tbl, compact ? pTmp : pVal);
                y += lineH + tileGap;
            }
        }
        if ((showMask & SHOW_CPU_TEMP) != 0 && (!strCTmp.isEmpty() || !compact)) {
            c.drawRoundRect(new RectF(0, y, w, y + lineH), tileRadius, tileRadius, pTileBg);
            if (drawBorder) c.drawRoundRect(new RectF(0, y, w, y + lineH), tileRadius, tileRadius, pTileBorder);
            float bl = getBaseline(compact ? pTmp : pVal, y, lineH);
            if (!compact) c.drawText("CTMP ", sidePad, bl, pTmp);
            c.drawText(strCTmp, sidePad + (compact ? 0 : wLabelCTmp), bl, compact ? pTmp : pVal);
            y += lineH + tileGap;
        }
        if ((showMask & SHOW_FPS) != 0) {
            c.drawRoundRect(new RectF(0, y, w, y + lineH), tileRadius, tileRadius, pTileBg);
            if (drawBorder) c.drawRoundRect(new RectF(0, y, w, y + lineH), tileRadius, tileRadius, pTileBorder);
            float bl = getBaseline(pFps, y, lineH);
            String label = apexActive ? "Apex " : "FPS ";
            float labelW = apexActive ? wLabelApex : wLabelFps;
            if (!compact) c.drawText(label, sidePad, bl, pFps);
            String fpsText = getFpsDisplayText();
            c.drawText(fpsText, sidePad + (compact ? 0 : labelW), bl, pFps);
        }
    }

    private float drawSep(Canvas c, float x, float baseline) {
        if ((showMask & SHOW_COMPACT) != 0) return PAD / 2f;
        if (c != null) {
            float rowH = getHeight();
            float bl = baseline > 0 ? baseline : getBaseline(pSep, 0, rowH);
            c.drawText(" | ", x, bl, pSep);
        }
        return wSep;
    }

    private void drawInlineGraph(Canvas c, float x, float y, float w, float h) {
        c.drawRect(x, y, x + w, y + h, pGraphBg);
        int count = Math.min(gHead, GBUF);
        if (count < 2) return;
        if (cachedPath == null || lastGHead != gHead) {
            cachedPath = new Path();
            float bw = w / (GBUF - 1);
            boolean first = true;
            for (int i = 0; i < count; i++) {
                float v  = graph[(gHead - count + i) % GBUF];
                float px = x + i * bw;
                float py = y + h - (v / gMax) * h;
                if (first) { cachedPath.moveTo(px, py); first = false; }
                else        { cachedPath.lineTo(px, py); }
            }
            lastGHead = gHead;
        }
        c.drawPath(cachedPath, pGraph);
    }

    private float measureHorizontal() {
        if (currentStyle == STYLE_TILES) return measureTilesHorizontal();
        return measureClassicHorizontal();
    }

    private float measureClassicHorizontal() {
        boolean compact = (showMask & SHOW_COMPACT) != 0;
        float rowH = TS + PAD * 2;
        float radius = rowH / 2f;
        float w = 0;
        boolean first = true;

        if ((showMask & SHOW_RENDERER) != 0) {
            if (!first) w += (compact ? PAD / 2f : wSep);
            w += pRend.measureText(strRend);
            first = false;
        }
        if ((showMask & SHOW_WRAPPER) != 0) {
            if (!first) w += (compact ? PAD / 2f : wSep);
            w += pRend.measureText(strWrapper);
            first = false;
        }
        if ((showMask & SHOW_GPU) != 0) {
            if (!first) w += (compact ? PAD / 2f : wSep);
            w += (compact ? 0 : wLabelGpu) + Math.max(pVal.measureText(strGpu), wVal100pct);
            first = false;
        }
        if ((showMask & SHOW_CPU) != 0) {
            if (!first) w += (compact ? PAD / 2f : wSep);
            w += (compact ? 0 : wLabelCpu) + Math.max(pVal.measureText(strCpu), wVal100pct);
            first = false;
        }
        if ((showMask & SHOW_RAM) != 0) {
            if (!first) w += (compact ? PAD / 2f : wSep);
            w += (compact ? 0 : wLabelRam) + Math.max(pVal.measureText(strRam), wVal100pct);
            first = false;
        }
        if ((showMask & SHOW_BATT) != 0) {
            if (!first) w += (compact ? PAD / 2f : wSep);
            w += (compact ? 0 : wLabelPwr) + Math.max((compact ? pBat : pVal).measureText(strPwr), wValWatt);
            first = false;
            if ((showMask & SHOW_BATT_PCT) != 0) {
                w += (compact ? PAD / 2f : wSep) + Math.max((compact ? pBat : pVal).measureText(strPct.isEmpty() ? "0%" : strPct), wVal100pct);
            }
            if (!strTmp.isEmpty() || !compact) {
                w += (compact ? PAD / 2f : wSep) + (compact ? 0 : wLabelTmp) + Math.max((compact ? pTmp : pVal).measureText(strTmp.isEmpty() ? "00°C" : strTmp), wValTemp);
            }
        }
        if ((showMask & SHOW_CPU_TEMP) != 0 && (!strCTmp.isEmpty() || !compact)) {
            if (!first) w += (compact ? PAD / 2f : wSep);
            w += (compact ? 0 : wLabelCTmp) + Math.max((compact ? pTmp : pVal).measureText(strCTmp.isEmpty() ? "00°C" : strCTmp), wValTemp);
            first = false;
        }
        if ((showMask & SHOW_FPS) != 0) {
            if (!first) w += (compact ? PAD / 2f : wSep);
            float labelW = apexActive ? wLabelApex : wLabelFps;
            String fpsText = getFpsDisplayText();
            float minValW = apexActive ? wValApex : wValFps;
            w += (compact ? 0 : labelW) + Math.max(pFps.measureText(fpsText), minValW);
            if ((showMask & SHOW_GRAPH) != 0) w += PAD + GRAW;
            first = false;
        }
        return w + radius * 2;
    }

    private float measureTilesHorizontal() {
        boolean compact = (showMask & SHOW_COMPACT) != 0;
        float w = 0;
        float tileGap = 5f * density;
        float tilePad = 7f * density;
        int tileCount = 0;

        if ((showMask & SHOW_RENDERER) != 0) {
            w += pRend.measureText(strRend) + tilePad * 2;
            tileCount++;
        }
        if ((showMask & SHOW_WRAPPER) != 0) {
            w += pRend.measureText(strWrapper) + tilePad * 2;
            tileCount++;
        }
        if ((showMask & SHOW_GPU) != 0) {
            float labelW = compact ? 0 : wLabelGpu;
            w += labelW + Math.max(pVal.measureText(strGpu), wVal100pct) + tilePad * 2;
            tileCount++;
        }
        if ((showMask & SHOW_CPU) != 0) {
            float labelW = compact ? 0 : wLabelCpu;
            w += labelW + Math.max(pVal.measureText(strCpu), wVal100pct) + tilePad * 2;
            tileCount++;
        }
        if ((showMask & SHOW_RAM) != 0) {
            float labelW = compact ? 0 : wLabelRam;
            w += labelW + Math.max(pVal.measureText(strRam), wVal100pct) + tilePad * 2;
            tileCount++;
        }
        if ((showMask & SHOW_BATT) != 0) {
            float labelW = compact ? 0 : wLabelPwr;
            w += labelW + Math.max((compact ? pBat : pVal).measureText(strPwr), wValWatt) + tilePad * 2;
            tileCount++;
            if ((showMask & SHOW_BATT_PCT) != 0) {
                w += Math.max((compact ? pBat : pVal).measureText(strPct.isEmpty() ? "0%" : strPct), wVal100pct) + tilePad * 2;
                tileCount++;
            }
            if (!strTmp.isEmpty() || !compact) {
                float tLabelW = compact ? 0 : wLabelTmp;
                w += tLabelW + Math.max((compact ? pTmp : pVal).measureText(strTmp.isEmpty() ? "00°C" : strTmp), wValTemp) + tilePad * 2;
                tileCount++;
            }
        }
        if ((showMask & SHOW_CPU_TEMP) != 0 && (!strCTmp.isEmpty() || !compact)) {
            float tLabelW = compact ? 0 : wLabelCTmp;
            w += tLabelW + Math.max((compact ? pTmp : pVal).measureText(strCTmp.isEmpty() ? "00°C" : strCTmp), wValTemp) + tilePad * 2;
            tileCount++;
        }
        if ((showMask & SHOW_FPS) != 0) {
            float labelW = compact ? 0 : (apexActive ? wLabelApex : wLabelFps);
            String fpsText = getFpsDisplayText();
            float minValW = apexActive ? wValApex : wValFps;
            float graphW = ((showMask & SHOW_GRAPH) != 0) ? (GRAW + PAD) : 0;
            w += labelW + Math.max(pFps.measureText(fpsText), minValW) + graphW + tilePad * 2;
            tileCount++;
        }
        if (tileCount > 1) w += (tileCount - 1) * tileGap;
        return w;
    }

    private float measureVertical() {
        if (currentStyle == STYLE_TILES) return measureTilesVertical();
        return measureClassicVertical();
    }

    private float measureClassicVertical() {
        boolean compact = (showMask & SHOW_COMPACT) != 0;
        float sidePad = 8f * density;
        float w = sidePad * 2;
        if ((showMask & SHOW_RENDERER) != 0) w = Math.max(w, sidePad * 2 + pRend.measureText(strRend));
        if ((showMask & SHOW_WRAPPER)  != 0) w = Math.max(w, sidePad * 2 + pRend.measureText(strWrapper));
        if ((showMask & SHOW_GPU)      != 0) w = Math.max(w, sidePad * 2 + (compact ? 0 : wLabelGpu) + Math.max(pVal.measureText(strGpu), wVal100pct));
        if ((showMask & SHOW_CPU)      != 0) w = Math.max(w, sidePad * 2 + (compact ? 0 : wLabelCpu) + Math.max(pVal.measureText(strCpu), wVal100pct));
        if ((showMask & SHOW_RAM)      != 0) w = Math.max(w, sidePad * 2 + (compact ? 0 : wLabelRam) + Math.max(pVal.measureText(strRam), wVal100pct));
        if ((showMask & SHOW_BATT)     != 0) {
            float bw = Math.max((compact ? pBat : pVal).measureText(strPwr + ((showMask & SHOW_BATT_PCT) != 0 ? " (" + (strPct.isEmpty() ? "0%" : strPct) + ")" : "")), wValBInfo);
            w = Math.max(w, sidePad * 2 + (compact ? 0 : wLabelPwr) + bw);
            if (!strTmp.isEmpty() || !compact) {
                w = Math.max(w, sidePad * 2 + (compact ? 0 : wLabelTmp) + Math.max((compact ? pTmp : pVal).measureText(strTmp.isEmpty() ? "00°C" : strTmp), wValTemp));
            }
        }
        if ((showMask & SHOW_CPU_TEMP) != 0 && (!strCTmp.isEmpty() || !compact)) {
            w = Math.max(w, sidePad * 2 + (compact ? 0 : wLabelCTmp) + Math.max((compact ? pTmp : pVal).measureText(strCTmp.isEmpty() ? "00°C" : strCTmp), wValTemp));
        }
        if ((showMask & SHOW_FPS)      != 0) {
            float labelW = apexActive ? wLabelApex : wLabelFps;
            String fpsText = getFpsDisplayText();
            float minValW = apexActive ? wValApex : wValFps;
            w = Math.max(w, sidePad * 2 + (compact ? 0 : labelW) + Math.max(pFps.measureText(fpsText), minValW));
        }
        return w;
    }

    private float measureTilesVertical() {
        return measureClassicVertical() + 4f * density;
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        float lineH = TS + PAD * 2;
        float w = vertical ? measureVertical() : measureHorizontal();
        float h = vertical ? (countVerticalRows() * lineH + (currentStyle == STYLE_TILES ? (countVerticalRows() - 1) * 3f * density : 0)) : lineH;
        setMeasuredDimension((int) Math.ceil(w), (int) Math.ceil(h));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (activePositionPreset >= 0 && (w != oldw || h != oldh)) {
            applyPositionPreset(activePositionPreset);
        }
    }

    private float countVerticalRows() {
        boolean compact = (showMask & SHOW_COMPACT) != 0;
        float r = 0;
        if ((showMask & SHOW_RENDERER) != 0) r++;
        if ((showMask & SHOW_WRAPPER)  != 0) r++;
        if ((showMask & SHOW_GPU)      != 0) r++;
        if ((showMask & SHOW_CPU)      != 0) r++;
        if ((showMask & SHOW_RAM)      != 0) r++;
        if ((showMask & SHOW_BATT)     != 0) {
            r++;
            if (!strTmp.isEmpty() || !compact) r++;
        }
        if ((showMask & SHOW_CPU_TEMP) != 0 && (!strCTmp.isEmpty() || !compact)) r++;
        if ((showMask & SHOW_FPS)      != 0) r++;
        return Math.max(1, r);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if ((showMask & SHOW_LOCKED) != 0) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (e.getPointerCount() > 1) return true;
                touchX = e.getRawX(); touchY = e.getRawY();
                startX = getX();      startY = getY();
                dragging = false;
                touchDownMs = System.currentTimeMillis();
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = e.getRawX() - touchX, dy = e.getRawY() - touchY;
                if (!dragging && Math.hypot(dx, dy) > DRAG_THRESH) dragging = true;
                if (dragging) {
                    float newX = startX + dx;
                    float newY = startY + dy;
                    if (getParent() != null) {
                        float parentW = ((View)getParent()).getWidth();
                        float parentH = ((View)getParent()).getHeight();
                        float w = getMeasuredWidth() * getScaleX();
                        float h = getMeasuredHeight() * getScaleY();
                        if (parentW > 0) newX = Math.max(0, Math.min(parentW - w, newX));
                        if (parentH > 0) newY = Math.max(0, Math.min(parentH - h, newY));
                    }
                    setX(newX); setY(newY);
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (e.getPointerCount() > 1) { dragging = false; return true; }
                if (dragging) {
                    activePositionPreset = PRESET_CUSTOM;
                    prefs.edit().putInt(KEY_POS_PRESET, PRESET_CUSTOM).apply();
                    savePosition();
                } else if (touchDownMs > 0 && System.currentTimeMillis() - touchDownMs < 300) {
                    vertical = !vertical;
                    prefs.edit().putBoolean(KEY_VERT, vertical).apply();
                    try { requestLayout(); invalidate(); } catch (Exception ignored) {}
                    uiHandler.postDelayed(this::ensureVisible, 250);
                    if (activePositionPreset >= 0) post(() -> applyPositionPreset(activePositionPreset));
                }
                dragging = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_UP:
                dragging = false;
                touchDownMs = 0;
                return true;
        }
        return false;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (userEnabled && rendererActive) {
            uiHandler.removeCallbacks(redrawRunnable);
            redrawScheduled = false;
            setVisibility(VISIBLE);
            scheduleRedraw();
            if (activePositionPreset >= 0) post(() -> applyPositionPreset(activePositionPreset));
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        uiHandler.removeCallbacks(redrawRunnable);
        redrawScheduled = false;
    }

    private void ensureVisible() {
        if (userEnabled && rendererActive) {
            if (getVisibility() != VISIBLE) setVisibility(VISIBLE);
            scheduleRedraw();
        }
    }

    private void savePosition() {
        prefs.edit().putFloat(KEY_X, getX()).putFloat(KEY_Y, getY()).apply();
    }

    private void scheduleRedraw() {
        if (!redrawScheduled) {
            redrawScheduled = true;
            uiHandler.postDelayed(redrawRunnable, 400);
        }
    }

    @Override
    protected void onVisibilityChanged(View v, int vis) {
        super.onVisibilityChanged(v, vis);
        if (vis == VISIBLE) {
            scheduleRedraw();
            if (activePositionPreset >= 0) post(() -> applyPositionPreset(activePositionPreset));
        } else {
            uiHandler.removeCallbacks(redrawRunnable);
            redrawScheduled = false;
            if (userEnabled) uiHandler.postDelayed(this::ensureVisible, 300);
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE && userEnabled) {
            uiHandler.removeCallbacks(redrawRunnable);
            redrawScheduled = false;
            uiHandler.postDelayed(this::ensureVisible, 150);
            if (activePositionPreset >= 0) post(() -> applyPositionPreset(activePositionPreset));
        }
    }

    private void loadPrefs() {
        showMask = prefs.getInt(KEY_SHOW, SHOW_DEFAULT);
        currentStyle = prefs.getInt(KEY_STYLE, (showMask & SHOW_MONO) != 0 ? STYLE_MONO : STYLE_ADAPTIVE);
        hudAlpha = prefs.getInt(KEY_ALPHA, 55) / 100f;
        setAlpha(hudAlpha);
        vertical = prefs.getBoolean(KEY_VERT, false);
        float scale = prefs.getFloat(KEY_SCALE, 1f);
        setScaleX(scale); setScaleY(scale);

        activePositionPreset = prefs.getInt(KEY_POS_PRESET, prefs.contains(KEY_X) ? PRESET_CUSTOM : PRESET_TOP_LEFT);
        if (activePositionPreset == PRESET_CUSTOM && prefs.contains(KEY_X)) {
            setX(prefs.getFloat(KEY_X, 16f));
            setY(prefs.getFloat(KEY_Y, 16f));
        } else {
            post(() -> applyPositionPreset(activePositionPreset >= 0 ? activePositionPreset : PRESET_TOP_LEFT));
        }

        userEnabled = prefs.getBoolean(KEY_VIS, false);
        setVisibility(userEnabled ? VISIBLE : GONE);
    }

    private boolean rendererActive = false;

    public boolean isUserEnabled() { return userEnabled; }

    public void enableByUser() {
        userEnabled = true;
        rendererActive = true;
        prefs.edit().putBoolean(KEY_VIS, true).apply();
        if (dataSource != null) dataSource.start();
        uiHandler.removeCallbacks(redrawRunnable);
        redrawScheduled = false;
        setVisibility(VISIBLE);
        scheduleRedraw();
        requestLayout();
        if (activePositionPreset >= 0) post(() -> applyPositionPreset(activePositionPreset));
    }

    public void disableByUser() {
        userEnabled = false;
        prefs.edit().putBoolean(KEY_VIS, false).apply();
        uiHandler.removeCallbacks(redrawRunnable);
        redrawScheduled = false;
        setVisibility(GONE);
    }

    private boolean isDisplayX = false;

    public void setDisplayDriver(String displayDriver) {
        this.isDisplayX = displayDriver != null && displayDriver.equalsIgnoreCase("displayx");
        this.rendererLabel = isDisplayX ? "DisplayX" : "OpenGL";
        this.strRend = (isNative ? "+" : "") + rendererLabel;
        this.layoutDirty = true;
        postInvalidate();
    }

    public void onRendererDetected(String name) {
        uiHandler.post(() -> {
            rendererActive = true;
            boolean changed = false;
            String formatted = formatRendererName(name);
            if (!isDisplayX && formatted != null && !formatted.isEmpty() && !formatted.equals(rendererLabel)) {
                rendererLabel = formatted;
                strRend = (isNative ? "+" : "") + rendererLabel;
                layoutDirty = true;
                changed = true;
            }
            if (getVisibility() != VISIBLE && userEnabled) {
                setVisibility(VISIBLE);
                changed = true;
            }
            if (changed) {
                try { requestLayout(); invalidate(); } catch (Exception ignored) {}
                if (activePositionPreset >= 0) applyPositionPreset(activePositionPreset);
            }
        });
    }

    public void onRendererGone() {
        uiHandler.post(() -> {
            rendererActive = false;
            uiHandler.removeCallbacks(redrawRunnable);
            redrawScheduled = false;
            setVisibility(GONE);
        });
    }

    public void setApexStats(float totalFps, float multiplier, boolean active) {
        boolean wasActive = this.apexActive;
        float prevMult = this.apexMultiplier;
        this.apexActive = active;
        this.apexMultiplier = multiplier;
        if (active) {
            this.snapTotalFps = totalFps;
            this.strFps = String.valueOf(Math.round(totalFps));
            if (!wasActive || Math.abs(prevMult - multiplier) > 0.05f) {
                layoutDirty = true;
                post(this::requestLayout);
            }
            postInvalidate();
        } else if (wasActive) {
            layoutDirty = true;
            post(this::requestLayout);
            postInvalidate();
        }
    }

    public void setRenderer(String name, boolean isNative) {
        this.rendererLabel = formatRendererName(name != null && !name.isEmpty() ? name : (isDisplayX ? "DisplayX" : "OpenGL"));
        this.isNative = isNative;
        this.strRend = (isNative ? "+" : "") + this.rendererLabel;
        this.layoutDirty = true;
        postInvalidate();
    }

    public void setWrapperName(String name) {
        this.strWrapper = formatWrapperName(name);
        this.layoutDirty = true;
        postInvalidate();
    }

    public void setGpuName(String name) {}

    public void setVertical(boolean v) {
        this.vertical = v;
        prefs.edit().putBoolean(KEY_VERT, v).apply();
        layoutDirty = true;
        try { requestLayout(); invalidate(); } catch (Exception ignored) {}
        if (activePositionPreset >= 0) post(() -> applyPositionPreset(activePositionPreset));
    }

    public boolean isVertical() { return vertical; }

    public float getHudScale() { return getScaleX(); }
    public float getHudAlpha() { return hudAlpha; }

    public int getHudStyle() { return currentStyle; }

    public void setHudStyle(int style) {
        this.currentStyle = Math.max(0, Math.min(3, style));
        if (this.currentStyle == STYLE_MONO) showMask |= SHOW_MONO;
        else showMask &= ~SHOW_MONO;
        prefs.edit().putInt(KEY_STYLE, this.currentStyle).putInt(KEY_SHOW, showMask).apply();
        layoutDirty = true;
        try { requestLayout(); invalidate(); } catch (Exception ignored) {}
        if (activePositionPreset >= 0) post(() -> applyPositionPreset(activePositionPreset));
    }

    public void toggleElement(int idx, boolean on) {
        int bit = idxToMask(idx);
        if (bit == 0) return;
        if (on) showMask |= bit; else showMask &= ~bit;
        if (idx == 9) {
            currentStyle = on ? STYLE_MONO : STYLE_CLASSIC;
            prefs.edit().putInt(KEY_STYLE, currentStyle).apply();
        }
        prefs.edit().putInt(KEY_SHOW, showMask).apply();
        layoutDirty = true;
        try { requestLayout(); invalidate(); } catch (Exception ignored) {}
        if (activePositionPreset >= 0) post(() -> applyPositionPreset(activePositionPreset));
    }

    public void syncCheckboxes(android.widget.CheckBox cbFps, android.widget.CheckBox cbGpu,
            android.widget.CheckBox cbCpu, android.widget.CheckBox cbBattTemp,
            android.widget.CheckBox cbGraph, android.widget.CheckBox cbRenderer,
            android.widget.CheckBox cbRam, android.widget.CheckBox cbBattPct,
            android.widget.CheckBox cbBorder, android.widget.CheckBox cbCompact,
            android.widget.CheckBox cbWrapper, android.widget.CheckBox cbLocked,
            android.widget.CheckBox cbCpuTemp) {
        if (cbFps      != null) cbFps.setChecked((showMask & SHOW_FPS)       != 0);
        if (cbGpu      != null) cbGpu.setChecked((showMask & SHOW_GPU)       != 0);
        if (cbCpu      != null) cbCpu.setChecked((showMask & SHOW_CPU)       != 0);
        if (cbBattTemp != null) cbBattTemp.setChecked((showMask & SHOW_BATT) != 0);
        if (cbGraph    != null) cbGraph.setChecked((showMask & SHOW_GRAPH)   != 0);
        if (cbRenderer != null) cbRenderer.setChecked((showMask & SHOW_RENDERER) != 0);
        if (cbRam      != null) cbRam.setChecked((showMask & SHOW_RAM)      != 0);
        if (cbBattPct  != null) cbBattPct.setChecked((showMask & SHOW_BATT_PCT) != 0);
        if (cbBorder   != null) cbBorder.setChecked((showMask & SHOW_BORDER)   != 0);
        if (cbCompact  != null) cbCompact.setChecked((showMask & SHOW_COMPACT) != 0);
        if (cbWrapper  != null) cbWrapper.setChecked((showMask & SHOW_WRAPPER) != 0);
        if (cbLocked   != null) cbLocked.setChecked((showMask & SHOW_LOCKED)  != 0);
        if (cbCpuTemp  != null) cbCpuTemp.setChecked((showMask & SHOW_CPU_TEMP) != 0);
    }

    public void setHudScale(float scale) {
        setScaleX(scale); setScaleY(scale);
        prefs.edit().putFloat(KEY_SCALE, scale).apply();
        if (activePositionPreset >= 0) applyPositionPreset(activePositionPreset);
    }

    public void setHudAlpha(float a) {
        hudAlpha = Math.max(0f, Math.min(1f, a));
        setAlpha(hudAlpha);
        prefs.edit().putInt(KEY_ALPHA, (int)(hudAlpha * 100)).apply();
        invalidate();
    }

    public void reset() {
        rendererLabel = isDisplayX ? "DisplayX" : "OpenGL"; frameAccum.set(0); snapFps = 0; gHead = 0; lastFpsNs = 0;
    }

    public void forceReset() {
        uiHandler.post(() -> {
            uiHandler.removeCallbacks(redrawRunnable);
            redrawScheduled = false;
            frameAccum.set(0);
            snapFps = 0; gHead = 0; lastFpsNs = 0;
            cachedPath = null; lastGHead = -1;
            dragging = false; touchDownMs = 0;
            rendererActive = true;
            userEnabled = true;
            
            showMask = SHOW_DEFAULT;
            currentStyle = STYLE_ADAPTIVE;
            vertical = false;
            layoutDirty = true;
            
            setScaleX(1.0f); setScaleY(1.0f);
            hudAlpha = 0.55f;
            setAlpha(hudAlpha);

            activePositionPreset = PRESET_TOP_LEFT;

            SharedPreferences.Editor ed = prefs.edit();
            ed.putBoolean(KEY_VIS, true);
            ed.putInt(KEY_SHOW, showMask);
            ed.putInt(KEY_STYLE, currentStyle);
            ed.putBoolean(KEY_VERT, vertical);
            ed.putFloat(KEY_SCALE, 1.0f);
            ed.putInt(KEY_ALPHA, 55);
            ed.putInt(KEY_POS_PRESET, PRESET_TOP_LEFT);
            ed.remove(KEY_X);
            ed.remove(KEY_Y);
            ed.apply();

            post(() -> applyPositionPreset(PRESET_TOP_LEFT));

            if (dataSource != null) dataSource.start();
            setVisibility(VISIBLE);
            scheduleRedraw();
            requestLayout();
        });
    }

    public int idxToMask(int idx) {
        switch (idx) {
            case 0: return SHOW_FPS;
            case 2: return SHOW_GPU;
            case 3: return SHOW_CPU;
            case 4: return SHOW_BATT;
            case 5: return SHOW_GRAPH;
            case 6: return SHOW_RENDERER;
            case 7: return SHOW_RAM;
            case 8: return SHOW_BATT_PCT;
            case 9: return SHOW_MONO;
            case 10: return SHOW_BORDER;
            case 11: return SHOW_COMPACT;
            case 12: return SHOW_WRAPPER;
            case 13: return SHOW_LOCKED;
            case 14: return SHOW_CPU_TEMP;
            default: return 0;
        }
    }

    public int getPositionPreset() {
        return activePositionPreset;
    }

    public void clearPositionPreset() {
        this.activePositionPreset = PRESET_CUSTOM;
        prefs.edit().putInt(KEY_POS_PRESET, PRESET_CUSTOM).apply();
        savePosition();
    }

    public void setPositionPreset(int preset) {
        this.activePositionPreset = preset;
        prefs.edit().putInt(KEY_POS_PRESET, preset).apply();
        applyPositionPreset(preset);
    }

    public void applyPositionPreset(int preset) {
        if (preset < 0) return;
        this.activePositionPreset = preset;
        Runnable r = () -> {
            if (getParent() == null) return;
            float parentW = ((View)getParent()).getWidth();
            float parentH = ((View)getParent()).getHeight();
            if (parentW <= 0 || parentH <= 0) {
                postDelayed(() -> applyPositionPreset(preset), 50);
                return;
            }

            float scaleX = getScaleX();
            float scaleY = getScaleY();
            float w = getMeasuredWidth() * scaleX;
            float h = getMeasuredHeight() * scaleY;
            if (w <= 0 || h <= 0) {
                measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
                w = getMeasuredWidth() * scaleX;
                h = getMeasuredHeight() * scaleY;
            }

            float margin = 16f * density;
            float x = margin, y = margin;
            switch (preset) {
                case PRESET_TOP_LEFT:
                    x = margin;
                    y = margin;
                    break;
                case PRESET_TOP_CENTER:
                    x = (parentW - w) / 2f;
                    y = margin;
                    break;
                case PRESET_TOP_RIGHT:
                    x = parentW - w - margin;
                    y = margin;
                    break;
                case PRESET_MIDDLE_LEFT:
                    x = margin;
                    y = (parentH - h) / 2f;
                    break;
                case PRESET_CENTER:
                    x = (parentW - w) / 2f;
                    y = (parentH - h) / 2f;
                    break;
                case PRESET_MIDDLE_RIGHT:
                    x = parentW - w - margin;
                    y = (parentH - h) / 2f;
                    break;
                case PRESET_BOTTOM_LEFT:
                    x = margin;
                    y = parentH - h - margin;
                    break;
                case PRESET_BOTTOM_CENTER:
                    x = (parentW - w) / 2f;
                    y = parentH - h - margin;
                    break;
                case PRESET_BOTTOM_RIGHT:
                    x = parentW - w - margin;
                    y = parentH - h - margin;
                    break;
            }

            if (parentW > 0 && w > 0) {
                if (x + w > parentW) x = Math.max(0, parentW - w);
                if (x < 0) x = 0;
            }
            if (parentH > 0 && h > 0) {
                if (y + h > parentH) y = Math.max(0, parentH - h);
                if (y < 0) y = 0;
            }

            setX(x);
            setY(y);
            savePosition();
        };

        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else post(r);
    }
}
