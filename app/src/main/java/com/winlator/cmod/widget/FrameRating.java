package com.winlator.cmod.widget;

import android.app.ActivityManager;
import android.content.Context;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.winlator.cmod.R;
import com.winlator.cmod.XServerDisplayActivity;
import com.winlator.cmod.core.CPUStatus;

import java.util.HashMap;
import java.util.Locale;

public class FrameRating extends FrameLayout implements Runnable {
    private final Context context;
    private long lastTime = 0;
    private int frameCount = 0;
    private float lastFPS = 0;
    private final TextView tvFPS;
    private final TextView tvRenderer;
    private final TextView tvCPU;
    private final TextView tvRAM;
    private boolean editMode = false;
    private float dX, dY;

    public FrameRating(Context context, HashMap<String, String> graphicsDriverConfig) {
        this(context, graphicsDriverConfig, null);
    }

    public FrameRating(Context context, HashMap<String, String> graphicsDriverConfig, AttributeSet attrs) {
        this(context, graphicsDriverConfig, attrs, 0);
    }

    public FrameRating(Context context, HashMap<String, String> graphicsDriverConfig, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        this.context = context;
        View view = LayoutInflater.from(context).inflate(R.layout.frame_rating, this, false);
        tvFPS = view.findViewById(R.id.TVFPS);
        tvRenderer = view.findViewById(R.id.TVRenderer);
        tvRenderer.setText("OpenGL");
        tvCPU = view.findViewById(R.id.TVCPU);
        tvRAM = view.findViewById(R.id.TVRAM);
        addView(view);

        setClickable(false);
        setFocusable(false);
    }

    public void setRenderer(String renderer) {
        if (renderer.contains("VirGL")) renderer = "VirGL";
        else if (renderer.contains("Turnip") || renderer.contains("Adreno")) renderer = "Turnip";
        else if (renderer.contains("DXVK")) renderer = "DXVK";
        else if (renderer.contains("Zink")) renderer = "Zink";
        tvRenderer.setText(renderer);
    }

    public void setGpuName(String gpuName) {
        // GPU name is not shown in this compact style
    }

    public void setGPULoad(String gpuLoad) {
        // GPU load is no longer displayed in the custom HUD
    }

    public void setEditMode(boolean editMode) {
        this.editMode = editMode;
        if (editMode) {
            setAlpha(0.8f);
            setClickable(true);
            setFocusable(true);
        } else {
            setAlpha(1.0f);
            setClickable(false);
            setFocusable(false);
        }
        
        View hudContainer = getChildAt(0);
        if (hudContainer != null) {
            hudContainer.setBackgroundResource(editMode ? R.drawable.bg_transparent_dark_editing : R.drawable.bg_transparent_dark);
        }
    }

    public boolean isEditMode() {
        return editMode;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!editMode) return false;

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                performClick();
                dX = getX() - event.getRawX();
                dY = getY() - event.getRawY();
                return true;
            case MotionEvent.ACTION_MOVE:
                setX(event.getRawX() + dX);
                setY(event.getRawY() + dY);
                return true;
            case MotionEvent.ACTION_UP:
                if (context instanceof XServerDisplayActivity) {
                    ((XServerDisplayActivity) context).saveHUDPosition(getX(), getY());
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    public void reset() {
        tvRenderer.setText("OpenGL");
    }

    public void update() {
        if (lastTime == 0) lastTime = SystemClock.elapsedRealtime();
        long time = SystemClock.elapsedRealtime();
        if (time >= lastTime + 500) {
            lastFPS = ((float) (frameCount * 1000) / (time - lastTime));
            post(this);
            lastTime = time;
            frameCount = 0;
        }
        frameCount++;
    }

    @Override
    public void run() {
        if (getVisibility() == GONE) setVisibility(View.VISIBLE);

        // FPS
        tvFPS.setText(String.format(Locale.ENGLISH, "%.1f FPS", lastFPS));

        // RAM
        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        long usedMem = memoryInfo.totalMem - memoryInfo.availMem;
        tvRAM.setText(String.format(Locale.ENGLISH, "RAM %.1fG", usedMem / (1024.0 * 1024.0 * 1024.0)));

        // CPU Usage (existing Task Manager logic)
        short[] clockSpeeds = CPUStatus.getCurrentClockSpeeds();
        int totalClockSpeed = 0;
        short maxClockSpeed = 0;
        for (int i = 0; i < clockSpeeds.length; i++) {
            totalClockSpeed += clockSpeeds[i];
            maxClockSpeed = (short) Math.max(maxClockSpeed, CPUStatus.getMaxClockSpeed(i));
        }
        int avgClockSpeed = clockSpeeds.length > 0 ? totalClockSpeed / clockSpeeds.length : 0;
        int cpuUsagePercent = maxClockSpeed > 0 ? (int) (((float) avgClockSpeed / maxClockSpeed) * 100.0f) : 0;
        tvCPU.setText("CPU " + cpuUsagePercent + "%");
    }
}
