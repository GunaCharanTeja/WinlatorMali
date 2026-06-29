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

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

public class WinlatorHUD extends View {
    private static final String PREFS    = "winlator_hud";
    private static final String KEY_X    = "hud_x";
    private static final String KEY_Y    = "hud_y";
    private static final String KEY_VIS  = "hud_vis";
    private static final String KEY_SHOW = "hud_show";
    private static final String KEY_SCALE= "hud_scale";
    private static final String KEY_ALPHA= "hud_alpha_int";
    private static final String KEY_VERT = "hud_vertical";

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
    private static final int SHOW_DEFAULT = SHOW_FPS | SHOW_RENDERER | SHOW_WRAPPER | SHOW_CPU | SHOW_RAM | SHOW_BATT | SHOW_MONO | SHOW_BORDER;

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

    private float TS, TSR, PAD, GRAW, CORNER;

    private static final int TEXT_FLAGS = Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG | Paint.LINEAR_TEXT_FLAG;
    private final Paint pBg      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBorder  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pVal     = new Paint(TEXT_FLAGS);
    private final Paint pGpu     = new Paint(TEXT_FLAGS);
    private final Paint pCpu     = new Paint(TEXT_FLAGS);
    private final Paint pBat     = new Paint(TEXT_FLAGS);
    private final Paint pTmp     = new Paint(TEXT_FLAGS);
    private final Paint pFps     = new Paint(TEXT_FLAGS);
    private final Paint pRend    = new Paint(TEXT_FLAGS);
    private final Paint pRam     = new Paint(TEXT_FLAGS);
    private final Paint pSep     = new Paint(TEXT_FLAGS);
    private final Paint pChg     = new Paint(TEXT_FLAGS);
    private final Paint pGraph   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pGraphBg = new Paint();

    private final RectF bgRect = new RectF();

    private float wLabelGpu, wLabelCpu, wLabelRam, wLabelPwr, wLabelTmp, wLabelCTmp, wLabelFps, wLabelApex, wSep;
    private float wVal100pct, wValFps, wValWatt, wValTemp, wValBInfo;

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
    private int apexMultiplier = 2;

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
        TS     = 12f * d;
        TSR    = 11f * d;
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

        pVal.setTextSize(TS);       pVal.setTypeface(mono);  pVal.setColor(C_WHITE);
        pGpu.setTextSize(TS);       pGpu.setTypeface(mono);  pGpu.setColor(C_GPU);
        pCpu.setTextSize(TS);       pCpu.setTypeface(mono);  pCpu.setColor(C_CPU);
        pBat.setTextSize(TS);       pBat.setTypeface(mono);  pBat.setColor(C_BATT);
        pTmp.setTextSize(TS);       pTmp.setTypeface(mono);  pTmp.setColor(C_TEMP);
        pFps.setTextSize(TS * 1.25f);
        pFps.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        pFps.setColor(C_FPS);
        pRend.setTextSize(TSR);     pRend.setTypeface(mono); pRend.setColor(C_REND);
        pRam.setTextSize(TS);       pRam.setTypeface(mono);  pRam.setColor(C_RAM);
        pSep.setTextSize(TS);       pSep.setTypeface(mono);  pSep.setColor(C_SEP);
        pChg.setTextSize(TS);       pChg.setTypeface(mono);  pChg.setColor(C_CHG);

        pVal.setShadowLayer(2f, 1f, 1f, Color.BLACK);
        pGpu.setShadowLayer(2f, 1f, 1f, Color.BLACK);
        pCpu.setShadowLayer(2f, 1f, 1f, Color.BLACK);
        pBat.setShadowLayer(2f, 1f, 1f, Color.BLACK);
        pTmp.setShadowLayer(2f, 1f, 1f, Color.BLACK);
        pFps.setShadowLayer(2f, 1f, 1f, Color.BLACK);
        pRend.setShadowLayer(2f, 1f, 1f, Color.BLACK);
        pRam.setShadowLayer(2f, 1f, 1f, Color.BLACK);
        pSep.setShadowLayer(2f, 1f, 1f, Color.BLACK);
        pChg.setShadowLayer(2f, 1f, 1f, Color.BLACK);

        pGraph.setStyle(Paint.Style.STROKE); pGraph.setStrokeWidth(1.5f); pGraph.setColor(C_FPS);
        pGraphBg.setStyle(Paint.Style.FILL); pGraphBg.setColor(Color.argb(80,20,20,20));

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
        wValFps    = pFps.measureText("999");
        wValWatt   = pVal.measureText("9.9W");
        wValTemp   = pVal.measureText("99°C");
        wValBInfo  = pVal.measureText("9.9W (100%)");
    }

    public void setDataSource(HudDataSource ds) { this.dataSource = ds; }

    public void onFrame() { frameAccum.incrementAndGet(); }

    public void update() {}

    private void snapshot() {
        long now = System.nanoTime();
        if (lastFpsNs == 0) lastFpsNs = now;
        long dt = now - lastFpsNs;
        if (dt >= 350_000_000L) {
            int f = frameAccum.getAndSet(0);
            snapFps = f * 1_000_000_000f / dt;
            lastFpsNs = now;

            float displayFps = apexActive ? snapTotalFps : snapFps;
            graph[gHead % GBUF] = displayFps;
            gHead++;
            cachedPath = null;
            float targetMax = Math.max(30f, displayFps * 1.2f);
            gMax = gMax + (targetMax - gMax) * 0.15f;
            
            if (apexActive) {
                strFps = String.format(Locale.US, "%.0f (%dx)", displayFps, apexMultiplier);
            } else {
                strFps = String.format(Locale.US, "%.0f", displayFps);
            }
        }
        if (dataSource != null) {
            int g  = dataSource.gpuLoad.get();
            int cp = dataSource.cpuLoad.get();
            int mw = dataSource.batteryMw.get();
            int tm = dataSource.batteryTempC.get();
            int ctm = dataSource.cpuTempC.get();
            int pc = dataSource.batteryPct.get();
            int rm = dataSource.ramUsagePct.get();

            if (g != snapGpu)   { snapGpu = g;  strGpu = g  >= 0 ? g  + "%" : "N/A"; }
            if (cp != snapCpu)  { snapCpu = cp; strCpu = cp >= 0 ? cp + "%" : "N/A"; }
            if (rm != snapRam)  { snapRam = rm; strRam = rm >= 0 ? rm + "%" : "N/A"; }
            if (tm != snapTmp)  { snapTmp = tm; strTmp = tm > 0 ? tm + "°C" : ""; }
            if (ctm != snapCTmp) { snapCTmp = ctm; strCTmp = ctm > 0 ? ctm + "°C" : ""; }
            if (pc != snapPct)  { snapPct = pc; strPct = pc >= 0 ? pc + "%" : ""; }
            if (mw != snapMw) {
                snapMw = mw;
                snapCharging = (mw == -2);
                if (snapCharging)   strPwr = "CHG";
                else if (mw > 0)    strPwr = String.format(Locale.US, "%.1fW", mw / 1000f);
                else                strPwr = "N/A";
            }
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        if (getVisibility() != VISIBLE) return;
        try {
            boolean mono = (showMask & SHOW_MONO) != 0;
            updatePaintColors(mono);

            bgRect.set(0, 0, getWidth(), getHeight());
            float corner = vertical ? CORNER : (bgRect.height() / 2f);
            
            pBg.setShadowLayer(4f, 0, 0, Color.BLACK);
            c.drawRoundRect(bgRect, corner, corner, pBg);
            pBg.clearShadowLayer();

            if ((showMask & SHOW_BORDER) != 0) c.drawRoundRect(bgRect, corner, corner, pBorder);

            if (vertical) drawVertical(c);
            else          drawHorizontal(c);
        } catch (Exception e) {
        }
    }

    private void updatePaintColors(boolean mono) {
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
    }

    private void drawHorizontal(Canvas c) {
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
            if ((showMask & SHOW_BATT_PCT) != 0) {
                x += drawSep(c, x, 0);
                float pw = Math.max((compact ? pBat : pVal).measureText(strPct), wVal100pct);
                c.drawText(strPct.isEmpty() ? "0%" : strPct, x, baseline, compact ? pBat : pVal);
                x += pw;
            }
            if (!strTmp.isEmpty() || (showMask & SHOW_COMPACT) == 0) {
                x += drawSep(c, x, 0);
                float tw = Math.max((compact ? pTmp : pVal).measureText(strTmp), wValTemp);
                if (!compact) { c.drawText("TMP ", x, baseline, pTmp); x += wLabelTmp; }
                c.drawText(strTmp, x, baseline, compact ? pTmp : pVal);
                x += tw;
            }
            first = false;
        }
        if ((showMask & SHOW_CPU_TEMP) != 0) {
            if (!first) x += drawSep(c, x, 0);
            float baseline = getBaseline(compact ? pTmp : pVal, 0, rowH);
            if (!strCTmp.isEmpty() || !compact) {
                if (!compact) { c.drawText("CTMP ", x, baseline, pTmp); x += wLabelCTmp; }
                float vw = Math.max((compact ? pTmp : pVal).measureText(strCTmp), wValTemp);
                c.drawText(strCTmp, x, baseline, compact ? pTmp : pVal);
                x += vw;
            }
            first = false;
        }
        if ((showMask & SHOW_FPS) != 0) {
            if (!first) x += drawSep(c, x, 0);
            float fb = getBaseline(pFps, 0, rowH);
            String label = apexActive ? "Apex " : "FPS ";
            float labelW = apexActive ? wLabelApex : wLabelFps;
            if (!compact) { c.drawText(label, x, fb, pFps); x += labelW; }
            float fw = Math.max(pFps.measureText(strFps), wValFps);
            c.drawText(strFps, x, fb, pFps);
            x += fw;
            if ((showMask & SHOW_GRAPH) != 0) {
                x += PAD;
                drawInlineGraph(c, x, PAD, GRAW, rowH - PAD * 2);
            }
            first = false;
        }
    }

    private float getBaseline(Paint p, float y, float height) {
        Paint.FontMetrics fm = p.getFontMetrics();
        return y + (height - (fm.ascent + fm.descent)) / 2f;
    }

    private void drawVertical(Canvas c) {
        boolean compact = (showMask & SHOW_COMPACT) != 0;
        float lineH = TS + PAD * 2;
        float y     = 0;
        if ((showMask & SHOW_RENDERER) != 0) {
            c.drawText(strRend, PAD, getBaseline(pRend, y, lineH), pRend);
            y += lineH;
        }
        if ((showMask & SHOW_WRAPPER) != 0) {
            c.drawText(strWrapper, PAD, getBaseline(pRend, y, lineH), pRend);
            y += lineH;
        }
        if ((showMask & SHOW_GPU) != 0) {
            float bl = getBaseline(compact ? pGpu : pVal, y, lineH);
            if (!compact) c.drawText("GPU ", PAD, bl, pGpu);
            c.drawText(strGpu, PAD + (compact ? 0 : wLabelGpu), bl, compact ? pGpu : pVal);
            y += lineH;
        }
        if ((showMask & SHOW_CPU) != 0) {
            float bl = getBaseline(compact ? pCpu : pVal, y, lineH);
            if (!compact) c.drawText("CPU ", PAD, bl, pCpu);
            c.drawText(strCpu, PAD + (compact ? 0 : wLabelCpu), bl, compact ? pCpu : pVal);
            y += lineH;
        }
        if ((showMask & SHOW_RAM) != 0) {
            float bl = getBaseline(compact ? pRam : pVal, y, lineH);
            if (!compact) c.drawText("RAM ", PAD, bl, pRam);
            c.drawText(strRam, PAD + (compact ? 0 : wLabelRam), bl, compact ? pRam : pVal);
            y += lineH;
        }
        if ((showMask & SHOW_BATT) != 0) {
            float bl = getBaseline(compact ? pBat : pVal, y, lineH);
            if (!compact) c.drawText("PWR ", PAD, bl, pBat);
            String bInfo = strPwr + ((showMask & SHOW_BATT_PCT) != 0 ? " ("+(strPct.isEmpty() ? "0%" : strPct)+")" : "");
            c.drawText(bInfo, PAD + (compact ? 0 : wLabelPwr), bl, snapCharging ? pChg : (compact ? pBat : pVal));
            y += lineH;
            if (!strTmp.isEmpty() || (showMask & SHOW_COMPACT) == 0) {
                float tbl = getBaseline(compact ? pTmp : pVal, y, lineH);
                if (!compact) c.drawText("TMP ", PAD, tbl, pTmp);
                c.drawText(strTmp, PAD + (compact ? 0 : wLabelTmp), tbl, compact ? pTmp : pVal);
                y += lineH;
            }
        }
        if ((showMask & SHOW_CPU_TEMP) != 0) {
            if (!strCTmp.isEmpty() || !compact) {
                float bl = getBaseline(compact ? pTmp : pVal, y, lineH);
                if (!compact) c.drawText("CTMP ", PAD, bl, pTmp);
                c.drawText(strCTmp, PAD + (compact ? 0 : wLabelCTmp), bl, compact ? pTmp : pVal);
                y += lineH;
            }
        }
        if ((showMask & SHOW_FPS) != 0) {
            float bl = getBaseline(pFps, y, lineH);
            String label = apexActive ? "Apex " : "FPS ";
            float labelW = apexActive ? wLabelApex : wLabelFps;
            if (!compact) c.drawText(label, PAD, bl, pFps);
            c.drawText(strFps, PAD + (compact ? 0 : labelW), bl, pFps);
        }
    }

    private float drawSep(Canvas c, float x, float baseline) {
        if ((showMask & SHOW_COMPACT) != 0) return PAD / 2f;
        if (c != null) {
            float rowH = TS + PAD * 2;
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
        boolean compact = (showMask & SHOW_COMPACT) != 0;
        float rowH = TS + PAD * 2;
        float radius = rowH / 2f;
        float w = 0;
        boolean first = true;
        if ((showMask & SHOW_RENDERER) != 0) {
            if (!first) w += drawSep(null, 0, 0);
            w += pRend.measureText(strRend);
            first = false;
        }
        if ((showMask & SHOW_WRAPPER) != 0) {
            if (!first) w += drawSep(null, 0, 0);
            w += pRend.measureText(strWrapper);
            first = false;
        }
        if ((showMask & SHOW_GPU) != 0) {
            if (!first) w += drawSep(null, 0, 0);
            w += (compact ? 0 : wLabelGpu) + Math.max(pVal.measureText(strGpu), wVal100pct);
            first = false;
        }
        if ((showMask & SHOW_CPU) != 0) {
            if (!first) w += drawSep(null, 0, 0);
            w += (compact ? 0 : wLabelCpu) + Math.max(pVal.measureText(strCpu), wVal100pct);
            first = false;
        }
        if ((showMask & SHOW_RAM) != 0) {
            if (!first) w += drawSep(null, 0, 0);
            w += (compact ? 0 : wLabelRam) + Math.max(pVal.measureText(strRam), wVal100pct);
            first = false;
        }
        if ((showMask & SHOW_BATT) != 0) {
            if (!first) w += drawSep(null, 0, 0);
            w += (compact ? 0 : wLabelPwr) + Math.max((compact ? pBat : pVal).measureText(strPwr), wValWatt);
            if ((showMask & SHOW_BATT_PCT) != 0) {
                w += drawSep(null, 0, 0) + Math.max((compact ? pBat : pVal).measureText(strPct), wVal100pct);
            }
            if (!strTmp.isEmpty() || (showMask & SHOW_COMPACT) == 0) {
                w += drawSep(null, 0, 0) + (compact ? 0 : wLabelTmp) + Math.max((compact ? pTmp : pVal).measureText(strTmp), wValTemp);
            }
            first = false;
        }
        if ((showMask & SHOW_CPU_TEMP) != 0) {
            if (!first) w += drawSep(null, 0, 0);
            w += (compact ? 0 : wLabelCTmp) + Math.max((compact ? pTmp : pVal).measureText(strCTmp), wValTemp);
            first = false;
        }
        if ((showMask & SHOW_FPS) != 0) {
            if (!first) w += drawSep(null, 0, 0);
            float labelW = apexActive ? wLabelApex : wLabelFps;
            float minValW = apexActive ? pFps.measureText("000 (0x)") : wValFps;
            w += (compact ? 0 : labelW) + Math.max(pFps.measureText(strFps), minValW);
            if ((showMask & SHOW_GRAPH) != 0) w += PAD + GRAW;
            first = false;
        }
        return w + radius * 2;
    }

    private float measureVertical() {
        boolean compact = (showMask & SHOW_COMPACT) != 0;
        float w = PAD * 2;
        if ((showMask & SHOW_RENDERER) != 0) w = Math.max(w, PAD * 2 + pRend.measureText(strRend));
        if ((showMask & SHOW_WRAPPER)  != 0) w = Math.max(w, PAD * 2 + pRend.measureText(strWrapper));
        if ((showMask & SHOW_GPU)      != 0) w = Math.max(w, PAD * 2 + (compact ? 0 : wLabelGpu) + Math.max(pVal.measureText(strGpu), wVal100pct));
        if ((showMask & SHOW_CPU)      != 0) w = Math.max(w, PAD * 2 + (compact ? 0 : wLabelCpu) + Math.max(pVal.measureText(strCpu), wVal100pct));
        if ((showMask & SHOW_RAM)      != 0) w = Math.max(w, PAD * 2 + (compact ? 0 : wLabelRam) + Math.max(pVal.measureText(strRam), wVal100pct));
        if ((showMask & SHOW_BATT)     != 0) {
            float bw = Math.max((compact ? pBat : pVal).measureText(strPwr + ( (showMask & SHOW_BATT_PCT) != 0 ? " (100%)" : "" )), wValBInfo);
            w = Math.max(w, PAD * 2 + (compact ? 0 : wLabelPwr) + bw);
            if (!strTmp.isEmpty() || (showMask & SHOW_COMPACT) == 0) {
                w = Math.max(w, PAD * 2 + (compact ? 0 : wLabelTmp) + Math.max((compact ? pTmp : pVal).measureText(strTmp), wValTemp));
            }
        }
        if ((showMask & SHOW_CPU_TEMP) != 0) {
            w = Math.max(w, PAD * 2 + (compact ? 0 : wLabelCTmp) + Math.max((compact ? pTmp : pVal).measureText(strCTmp), wValTemp));
        }
        if ((showMask & SHOW_FPS)      != 0) {
            float labelW = apexActive ? wLabelApex : wLabelFps;
            float minValW = apexActive ? pFps.measureText("000 (0x)") : wValFps;
            w = Math.max(w, PAD * 2 + (compact ? 0 : labelW) + Math.max(pFps.measureText(strFps), minValW));
        }
        return w;
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        float lineH = TS + PAD * 2;
        float w = vertical ? measureVertical() : measureHorizontal();
        float h = vertical ? (countVerticalRows() * lineH) : lineH;
        setMeasuredDimension((int) Math.ceil(w), (int) Math.ceil(h));
    }

    private float countVerticalRows() {
        float r = 0;
        if ((showMask & SHOW_RENDERER) != 0) r++;
        if ((showMask & SHOW_WRAPPER)  != 0) r++;
        if ((showMask & SHOW_GPU)      != 0) r++;
        if ((showMask & SHOW_CPU)      != 0) r++;
        if ((showMask & SHOW_RAM)      != 0) r++;
        if ((showMask & SHOW_BATT)     != 0) { r++; if (!strTmp.isEmpty()) r++; }
        if ((showMask & SHOW_CPU_TEMP) != 0) r++;
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
                if (dragging) { setX(startX + dx); setY(startY + dy); }
                return true;
            case MotionEvent.ACTION_UP:
                if (e.getPointerCount() > 1) { dragging = false; return true; }
                if (dragging) { savePosition(); } 
                else if (touchDownMs > 0 && System.currentTimeMillis() - touchDownMs < 300) {
                    vertical = !vertical;
                    prefs.edit().putBoolean(KEY_VERT, vertical).apply();
                    try { requestLayout(); invalidate(); } catch (Exception ignored) {}
                    uiHandler.postDelayed(this::ensureVisible, 250);
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
        if (vis == VISIBLE) scheduleRedraw();
        else {
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
        }
    }

    private void loadPrefs() {
        showMask = prefs.getInt(KEY_SHOW, SHOW_DEFAULT);
        hudAlpha = prefs.getInt(KEY_ALPHA, 100) / 100f;
        setAlpha(hudAlpha);
        vertical = prefs.getBoolean(KEY_VERT, false);
        float scale = prefs.getFloat(KEY_SCALE, 1f);
        setScaleX(scale); setScaleY(scale);
        setX(prefs.getFloat(KEY_X, 16f));
        setY(prefs.getFloat(KEY_Y, 16f));
        userEnabled = false;
        setVisibility(GONE);
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
    }

    public void disableByUser() {
        userEnabled = false;
        prefs.edit().putBoolean(KEY_VIS, false).apply();
        uiHandler.removeCallbacks(redrawRunnable);
        redrawScheduled = false;
        setVisibility(GONE);
    }

    public void onRendererDetected(String name) {
        uiHandler.post(() -> {
            rendererActive = true;
            boolean changed = false;
            if (name != null && !name.isEmpty() && !name.equals(rendererLabel)) {
                rendererLabel = name;
                strRend = (isNative ? "+" : "") + rendererLabel;
                layoutDirty = true;
                changed = true;
            }
            if (userEnabled) {
                if (getVisibility() != VISIBLE) setVisibility(VISIBLE);
                scheduleRedraw();
            }
            if (changed) requestLayout();
        });
    }

    public void onRendererGone() {
        uiHandler.post(() -> {
            rendererActive = false;
            if (!userEnabled) {
                uiHandler.removeCallbacks(redrawRunnable);
                redrawScheduled = false;
                setVisibility(GONE);
            }
            // If userEnabled, we keep redrawing to show last stats/0 FPS during swaps
        });
    }

    public void setApexStats(float totalFps, int multiplier, boolean active) {
        uiHandler.post(() -> {
            this.snapTotalFps = totalFps;
            this.apexMultiplier = multiplier;
            if (this.apexActive != active) {
                this.apexActive = active;
                layoutDirty = true;
                requestLayout();
            }
            invalidate();
        });
    }

    public void setRenderer(String name) {
        if (name != null && !name.isEmpty()) rendererLabel = name;
    }
    public void setWrapperName(String name) {
        if (name != null && !name.isEmpty()) {
            this.strWrapper = name.substring(0, 1).toUpperCase() + name.substring(1);
            layoutDirty = true;
            invalidate();
        }
    }
    public void setGpuName(String name) {}

    public void setVertical(boolean v) {
        vertical = v;
        prefs.edit().putBoolean(KEY_VERT, vertical).apply();
        layoutDirty = true;
        try { requestLayout(); invalidate(); } catch (Exception ignored) {}
    }

    public boolean isVertical() { return vertical; }

    public float getHudScale() { return getScaleX(); }
    public float getHudAlpha() { return hudAlpha; }

    public void toggleElement(int idx, boolean on) {
        int bit = idxToMask(idx);
        if (bit == 0) return;
        if (on) showMask |= bit; else showMask &= ~bit;
        prefs.edit().putInt(KEY_SHOW, showMask).apply();
        layoutDirty = true;
        try { requestLayout(); invalidate(); } catch (Exception ignored) {}
    }

    public void syncCheckboxes(android.widget.CheckBox cbFps, android.widget.CheckBox cbGpu,
            android.widget.CheckBox cbCpu, android.widget.CheckBox cbBattTemp,
            android.widget.CheckBox cbGraph, android.widget.CheckBox cbRenderer,
            android.widget.CheckBox cbRam, android.widget.CheckBox cbBattPct,
            android.widget.CheckBox cbMono, android.widget.CheckBox cbBorder,
            android.widget.CheckBox cbCompact, android.widget.CheckBox cbWrapper,
            android.widget.CheckBox cbLocked, android.widget.CheckBox cbCpuTemp) {
        if (cbFps      != null) cbFps.setChecked((showMask & SHOW_FPS)       != 0);
        if (cbGpu      != null) cbGpu.setChecked((showMask & SHOW_GPU)       != 0);
        if (cbCpu      != null) cbCpu.setChecked((showMask & SHOW_CPU)       != 0);
        if (cbBattTemp != null) cbBattTemp.setChecked((showMask & SHOW_BATT) != 0);
        if (cbGraph    != null) cbGraph.setChecked((showMask & SHOW_GRAPH)   != 0);
        if (cbRenderer != null) cbRenderer.setChecked((showMask & SHOW_RENDERER) != 0);
        if (cbRam      != null) cbRam.setChecked((showMask & SHOW_RAM)      != 0);
        if (cbBattPct  != null) cbBattPct.setChecked((showMask & SHOW_BATT_PCT) != 0);
        if (cbMono     != null) cbMono.setChecked((showMask & SHOW_MONO)     != 0);
        if (cbBorder   != null) cbBorder.setChecked((showMask & SHOW_BORDER)   != 0);
        if (cbCompact  != null) cbCompact.setChecked((showMask & SHOW_COMPACT) != 0);
        if (cbWrapper  != null) cbWrapper.setChecked((showMask & SHOW_WRAPPER) != 0);
        if (cbLocked   != null) cbLocked.setChecked((showMask & SHOW_LOCKED)  != 0);
        if (cbCpuTemp  != null) cbCpuTemp.setChecked((showMask & SHOW_CPU_TEMP) != 0);
    }

    public void setHudScale(float scale) {
        setScaleX(scale); setScaleY(scale);
        prefs.edit().putFloat(KEY_SCALE, scale).apply();
    }

    public void setHudAlpha(float a) {
        hudAlpha = Math.max(0f, Math.min(1f, a));
        setAlpha(hudAlpha);
        prefs.edit().putInt(KEY_ALPHA, (int)(hudAlpha * 100)).apply();
        invalidate();
    }

    public void reset() {
        rendererLabel = "OpenGL"; frameAccum.set(0); snapFps = 0; gHead = 0; lastFpsNs = 0;
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
            vertical = false;
            layoutDirty = true;
            setHudScale(1.0f);
            setHudAlpha(1.0f);
            setX(16f);
            setY(16f);

            SharedPreferences.Editor ed = prefs.edit();
            ed.putBoolean(KEY_VIS, true);
            ed.putInt(KEY_SHOW, showMask);
            ed.putBoolean(KEY_VERT, vertical);
            ed.putFloat(KEY_X, 16f);
            ed.putFloat(KEY_Y, 16f);
            ed.apply();

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

    public void setPositionPreset(int preset) {
        post(() -> {
            float w = getWidth() * getScaleX();
            float h = getHeight() * getScaleY();
            float parentW = ((View)getParent()).getWidth();
            float parentH = ((View)getParent()).getHeight();
            float x = 16f, y = 16f;

            switch (preset) {
                case 1: x = (parentW - w) / 2f; break;
                case 2: x = parentW - w - 16f; break;
                case 3: y = (parentH - h) / 2f; break;
                case 4: x = (parentW - w) / 2f; y = (parentH - h) / 2f; break;
                case 5: x = parentW - w - 16f; y = (parentH - h) / 2f; break;
                case 6: y = parentH - h - 16f; break;
                case 7: x = (parentW - w) / 2f; y = parentH - h - 16f; break;
                case 8: x = parentW - w - 16f; y = parentH - h - 16f; break;
            }
            setX(x); setY(y);
            savePosition();
        });
    }
}
