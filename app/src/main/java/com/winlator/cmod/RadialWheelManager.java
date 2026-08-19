package com.winlator.cmod;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;

import com.winlator.cmod.inputcontrols.Binding;
import com.winlator.cmod.inputcontrols.RadialWheelConfig;
import com.winlator.cmod.inputcontrols.RadialWheelSlice;
import com.winlator.cmod.widget.InputControlsView;

import java.util.ArrayList;
import java.util.List;

/**
 * High-performance, self-contained manager and canvas renderer for Radial Action Wheels.
 * Supports both touch-drag interaction and physical gamepad stick navigation.
 */
public class RadialWheelManager {
    private final InputControlsView inputControlsView;
    private final List<RadialWheelConfig> configs = new ArrayList<>();

    private RadialWheelConfig activeConfig = null;
    private boolean open = false;
    private float centerX = 0;
    private float centerY = 0;
    private int selectedSlice = -1;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private static final float INNER_RADIUS_DP = 42f;
    private static final float OUTER_RADIUS_DP = 120f;
    private static final int COLOR_SLICE_IDLE = 0x881A2030;
    private static final int COLOR_SLICE_SELECTED = 0xEE0288D1;
    private static final int COLOR_CENTER_IDLE = 0xAA0F141C;
    private static final int COLOR_CENTER_SELECTED = 0xDD0F141C;

    public RadialWheelManager(InputControlsView icv, List<RadialWheelConfig> configs) {
        this.inputControlsView = icv;
        if (configs != null) {
            this.configs.addAll(configs);
        }
    }

    public void updateConfigs(List<RadialWheelConfig> newConfigs) {
        dismissAll();
        this.configs.clear();
        if (newConfigs != null) {
            this.configs.addAll(newConfigs);
        }
    }

    public boolean isOpen() {
        return open && activeConfig != null;
    }

    public boolean hasActiveWheel() {
        return isOpen();
    }

    /**
     * Check if a binding press/hold should activate a wheel.
     */
    public boolean onBindingHeld(Binding binding, float x, float y) {
        if (binding == null || binding == Binding.NONE) return false;
        for (RadialWheelConfig cfg : configs) {
            if (cfg.triggerBinding == binding) {
                return openWheel(cfg, x, y);
            }
        }
        return false;
    }

    /**
     * Open a specific wheel at the given screen coordinates.
     */
    public boolean openWheel(RadialWheelConfig config, float x, float y) {
        if (config == null) return false;
        this.activeConfig = config;
        this.centerX = x > 0 ? x : inputControlsView.getWidth() / 2f;
        this.centerY = y > 0 ? y : inputControlsView.getHeight() / 2f;
        this.selectedSlice = -1;
        this.open = true;
        inputControlsView.invalidate();
        return true;
    }

    /**
     * Called when the trigger binding is released (e.g. key up or controller button released).
     */
    public boolean onBindingReleased(Binding binding) {
        if (!isOpen()) return false;
        if (activeConfig.triggerBinding == binding) {
            fireSelectedAndClose();
            return true;
        }
        return false;
    }

    /**
     * Update selected slice from gamepad thumbstick coordinates (-1.0 to 1.0).
     */
    public void onStickMoved(float stickX, float stickY) {
        if (!isOpen() || activeConfig == null) return;
        float magnitude = (float) Math.sqrt(stickX * stickX + stickY * stickY);
        if (magnitude < 0.35f) {
            selectedSlice = -1;
        } else {
            int sliceCount = activeConfig.slices.size();
            if (sliceCount > 0) {
                double angleDeg = Math.toDegrees(Math.atan2(stickY, stickX));
                angleDeg = (angleDeg + 90 + 360) % 360;
                double sliceAngle = 360.0 / sliceCount;
                selectedSlice = (int) (angleDeg / sliceAngle);
                if (selectedSlice >= sliceCount) selectedSlice = sliceCount - 1;
            }
        }
        inputControlsView.invalidate();
    }

    /**
     * Handle touch events when wheel is active.
     */
    public boolean handleTouchEvent(MotionEvent event) {
        if (!isOpen() || activeConfig == null) return false;

        float density = inputControlsView.getContext().getResources().getDisplayMetrics().density;
        float innerRadius = INNER_RADIUS_DP * density;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                float x = event.getX();
                float y = event.getY();
                float dx = x - centerX;
                float dy = y - centerY;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);

                if (dist < innerRadius) {
                    selectedSlice = -1;
                } else {
                    int sliceCount = activeConfig.slices.size();
                    if (sliceCount > 0) {
                        double angleDeg = Math.toDegrees(Math.atan2(dy, dx));
                        angleDeg = (angleDeg + 90 + 360) % 360;
                        double sliceAngle = 360.0 / sliceCount;
                        selectedSlice = (int) (angleDeg / sliceAngle);
                        if (selectedSlice >= sliceCount) selectedSlice = sliceCount - 1;
                    }
                }
                inputControlsView.invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                fireSelectedAndClose();
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                dismissAll();
                return true;
            }
        }
        return true;
    }

    private void fireSelectedAndClose() {
        if (isOpen() && activeConfig != null && selectedSlice >= 0 && selectedSlice < activeConfig.slices.size()) {
            RadialWheelSlice slice = activeConfig.slices.get(selectedSlice);
            if (slice != null && slice.binding != null && slice.binding != Binding.NONE) {
                inputControlsView.handleInputEvent(slice.binding, true);
                inputControlsView.handleInputEvent(slice.binding, false);
            }
        }
        dismissAll();
    }

    public void dismissAll() {
        open = false;
        activeConfig = null;
        selectedSlice = -1;
        if (inputControlsView != null) {
            inputControlsView.invalidate();
        }
    }

    /**
     * Draw the radial wheel overlay directly onto the InputControlsView canvas.
     */
    public void draw(Canvas canvas, int viewWidth, int viewHeight, float opacity) {
        if (!isOpen() || activeConfig == null) return;

        float density = inputControlsView.getContext().getResources().getDisplayMetrics().density;
        float innerRadius = INNER_RADIUS_DP * density;
        float outerRadius = OUTER_RADIUS_DP * density;

        // Dim background outside the wheel
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x66000000);
        canvas.drawRect(0, 0, viewWidth, viewHeight, paint);

        int sliceCount = activeConfig.slices.size();
        if (sliceCount == 0) return;

        float sliceAngleDeg = 360f / sliceCount;
        float startOffset = -90f; // Top is 12 o'clock

        // Draw Slices
        for (int i = 0; i < sliceCount; i++) {
            float startAngle = startOffset + i * sliceAngleDeg;
            float sweepAngle = sliceAngleDeg;

            path.reset();
            double startRad = Math.toRadians(startAngle);
            float ix = centerX + (float)(Math.cos(startRad) * innerRadius);
            float iy = centerY + (float)(Math.sin(startRad) * innerRadius);
            path.moveTo(ix, iy);

            RectF outerRect = new RectF(centerX - outerRadius, centerY - outerRadius, centerX + outerRadius, centerY + outerRadius);
            path.arcTo(outerRect, startAngle, sweepAngle);

            RectF innerRect = new RectF(centerX - innerRadius, centerY - innerRadius, centerX + innerRadius, centerY + innerRadius);
            path.arcTo(innerRect, startAngle + sweepAngle, -sweepAngle);
            path.close();

            // Fill slice
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(i == selectedSlice ? COLOR_SLICE_SELECTED : COLOR_SLICE_IDLE);
            canvas.drawPath(path, paint);

            // Slice border
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(i == selectedSlice ? 0xFF81D4FA : 0x40FFFFFF);
            paint.setStrokeWidth(1.5f * density);
            canvas.drawPath(path, paint);
        }

        // Draw Outer Rim Glow
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(0x80FFFFFF);
        paint.setStrokeWidth(2f * density);
        canvas.drawCircle(centerX, centerY, outerRadius, paint);

        // Draw Center Cancel Circle
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(selectedSlice == -1 ? COLOR_CENTER_SELECTED : COLOR_CENTER_IDLE);
        canvas.drawCircle(centerX, centerY, innerRadius, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(selectedSlice == -1 ? 0xFFFF5252 : 0x60FFFFFF);
        paint.setStrokeWidth(1.5f * density);
        canvas.drawCircle(centerX, centerY, innerRadius, paint);

        // Center Cancel '✕'
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(selectedSlice == -1 ? 0xFFFF5252 : 0xAAFFFFFF);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(18f * density);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText("\u2715", centerX, centerY + (paint.getTextSize() / 3.2f), paint);

        // Draw Wheel Name Header above wheel
        if (activeConfig.name != null && !activeConfig.name.isEmpty()) {
            paint.setColor(0xFF81D4FA);
            paint.setTextSize(13f * density);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            canvas.drawText(activeConfig.name.toUpperCase(), centerX, centerY - outerRadius - (12f * density), paint);
        }

        // Draw Slice Labels
        paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(12f * density);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setStyle(Paint.Style.FILL);

        float labelRadius = outerRadius * 0.72f;
        for (int i = 0; i < sliceCount; i++) {
            RadialWheelSlice slice = activeConfig.slices.get(i);
            String label = (slice.label != null && !slice.label.isEmpty())
                    ? slice.label : (slice.binding != null && slice.binding != Binding.NONE ? slice.binding.toString() : "");
            if (label.isEmpty()) continue;

            float midAngle = startOffset + i * sliceAngleDeg + sliceAngleDeg / 2f;
            double midRad = Math.toRadians(midAngle);
            float lx = centerX + (float)(Math.cos(midRad) * labelRadius);
            float ly = centerY + (float)(Math.sin(midRad) * labelRadius) + (paint.getTextSize() / 3f);

            if (i == selectedSlice) {
                paint.setColor(0xFFFFFFFF);
                paint.setTextSize(13.5f * density);
            } else {
                paint.setColor(0xDDFFFFFF);
                paint.setTextSize(11.5f * density);
            }
            canvas.drawText(label, lx, ly, paint);
        }
    }
}