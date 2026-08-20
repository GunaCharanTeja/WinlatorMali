package com.winlator.cmod;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
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
 * High-performance, screen-centered manager and canvas renderer for Radial Action Wheels.
 * Supports multi-touch dual-finger interaction, icon rendering, and physical gamepad stick navigation.
 */
public class RadialWheelManager {
    private final InputControlsView inputControlsView;
    private final List<RadialWheelConfig> configs = new ArrayList<>();
    private final java.util.Set<Binding> heldBindings = new java.util.HashSet<>();

    private RadialWheelConfig activeConfig = null;
    private boolean open = false;
    private float centerX = 0;
    private float centerY = 0;
    private int selectedSlice = -1;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private static final float INNER_RADIUS_DP = 48f;
    private static final float OUTER_RADIUS_DP = 140f;
    private static final int COLOR_DIM_BACKGROUND = 0x77000000;
    private static final int COLOR_SLICE_IDLE = 0x99182232;
    private static final int COLOR_SLICE_SELECTED = 0xEE0288D1;
    private static final int COLOR_CENTER_IDLE = 0xBB0D131C;
    private static final int COLOR_CENTER_SELECTED = 0xEE0D131C;

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

    public List<RadialWheelConfig> getConfigs() {
        return configs;
    }

    /**
     * Check if a binding press/hold should activate a wheel (single trigger or 2-button combo).
     */
    public boolean onBindingHeld(Binding binding, float x, float y) {
        if (binding == null || binding == Binding.NONE || configs.isEmpty()) return false;
        heldBindings.add(binding);

        // If the wheel is ALREADY open, check if held bindings still satisfy its trigger
        if (isOpen() && activeConfig != null) {
            boolean hasTrigger1 = activeConfig.triggerBinding != null && activeConfig.triggerBinding != Binding.NONE && heldBindings.contains(activeConfig.triggerBinding);
            boolean hasTrigger2 = activeConfig.triggerBinding2 == null || activeConfig.triggerBinding2 == Binding.NONE || heldBindings.contains(activeConfig.triggerBinding2);
            if (hasTrigger1 && hasTrigger2) {
                return true; // Preserve active wheel and selection
            }
        }

        // Check for 2-button combo triggers first, then single triggers
        for (RadialWheelConfig cfg : configs) {
            if (!cfg.enabled) continue;
            if (cfg.triggerBinding != null && cfg.triggerBinding != Binding.NONE && cfg.triggerBinding2 != null && cfg.triggerBinding2 != Binding.NONE) {
                if (heldBindings.contains(cfg.triggerBinding) && heldBindings.contains(cfg.triggerBinding2)) {
                    return openWheel(cfg);
                }
            }
        }
        for (RadialWheelConfig cfg : configs) {
            if (!cfg.enabled) continue;
            if (cfg.triggerBinding != null && cfg.triggerBinding != Binding.NONE && (cfg.triggerBinding2 == null || cfg.triggerBinding2 == Binding.NONE)) {
                if (heldBindings.contains(cfg.triggerBinding)) {
                    return openWheel(cfg);
                }
            }
        }
        return false;
    }

    /**
     * Open a specific wheel centered on screen.
     */
    public boolean openWheel(RadialWheelConfig config) {
        if (config == null) return false;
        if (isOpen() && this.activeConfig == config) {
            return true; // Already open, preserve selection
        }
        this.activeConfig = config;
        updateCenterCoordinates();
        this.selectedSlice = -1;
        this.open = true;
        if (inputControlsView != null) {
            inputControlsView.invalidate();
        }
        return true;
    }

    public boolean openWheel(RadialWheelConfig config, float x, float y) {
        return openWheel(config);
    }

    private void updateCenterCoordinates() {
        if (inputControlsView != null) {
            int vw = inputControlsView.getWidth();
            int vh = inputControlsView.getHeight();
            if (vw > 0 && vh > 0) {
                this.centerX = vw / 2.0f;
                this.centerY = vh / 2.0f;
            }
        }
    }

    /**
     * Called when a trigger binding is released.
     */
    public boolean onBindingReleased(Binding binding) {
        heldBindings.remove(binding);
        if (!isOpen() || activeConfig == null) return false;
        boolean matchesTrigger = (activeConfig.triggerBinding == binding) ||
                (activeConfig.triggerBinding2 != null && activeConfig.triggerBinding2 != Binding.NONE && activeConfig.triggerBinding2 == binding);
        if (matchesTrigger) {
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
        if (inputControlsView != null) {
            inputControlsView.invalidate();
        }
    }

    /**
     * Multi-touch aware touch handler for the radial wheel.
     * Evaluates all active touch pointers so holding with one finger and selecting with another works seamlessly!
     */
    public boolean handleTouchEvent(MotionEvent event) {
        if (!isOpen() || activeConfig == null) return false;

        updateCenterCoordinates();
        float density = inputControlsView.getContext().getResources().getDisplayMetrics().density;
        float innerRadius = INNER_RADIUS_DP * density;
        float outerRadius = OUTER_RADIUS_DP * density;
        float maxInteractiveRadius = outerRadius * 1.6f;

        int actionMasked = event.getActionMasked();

        switch (actionMasked) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_MOVE: {
                int targetPointerIndex = -1;
                float minDistanceToCenter = Float.MAX_VALUE;

                // Find the finger that is specifically interacting with the radial wheel
                for (int i = 0; i < event.getPointerCount(); i++) {
                    float px = event.getX(i);
                    float py = event.getY(i);
                    float dx = px - centerX;
                    float dy = py - centerY;
                    float dist = (float) Math.sqrt(dx * dx + dy * dy);

                    if (dist <= maxInteractiveRadius) {
                        if (dist < minDistanceToCenter) {
                            minDistanceToCenter = dist;
                            targetPointerIndex = i;
                        }
                    }
                }

                if (targetPointerIndex >= 0) {
                    float px = event.getX(targetPointerIndex);
                    float py = event.getY(targetPointerIndex);
                    float dx = px - centerX;
                    float dy = py - centerY;
                    float dist = (float) Math.sqrt(dx * dx + dy * dy);

                    if (dist < innerRadius) {
                        // Explicitly in the center cancel zone
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
                } else {
                    // No finger is touching the wheel area
                    selectedSlice = -1;
                }

                if (inputControlsView != null) {
                    inputControlsView.invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                int pointerIndex = event.getActionIndex();
                float px = event.getX(pointerIndex);
                float py = event.getY(pointerIndex);
                float dx = px - centerX;
                float dy = py - centerY;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);

                if (dist <= maxInteractiveRadius) {
                    if (selectedSlice >= 0) {
                        fireSelectedAndClose();
                    } else {
                        dismissAll();
                    }
                    return true;
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
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
            if (slice != null) {
                // Multi-button combo press
                if (slice.binding != null && slice.binding != Binding.NONE) inputControlsView.handleInputEvent(slice.binding, true);
                if (slice.binding2 != null && slice.binding2 != Binding.NONE) inputControlsView.handleInputEvent(slice.binding2, true);
                if (slice.binding3 != null && slice.binding3 != Binding.NONE) inputControlsView.handleInputEvent(slice.binding3, true);

                // Multi-button combo release
                if (slice.binding3 != null && slice.binding3 != Binding.NONE) inputControlsView.handleInputEvent(slice.binding3, false);
                if (slice.binding2 != null && slice.binding2 != Binding.NONE) inputControlsView.handleInputEvent(slice.binding2, false);
                if (slice.binding != null && slice.binding != Binding.NONE) inputControlsView.handleInputEvent(slice.binding, false);
            }
        }
        dismissAll();
    }

    public void dismissAll() {
        open = false;
        activeConfig = null;
        selectedSlice = -1;
        heldBindings.clear();
        if (inputControlsView != null) {
            inputControlsView.invalidate();
        }
    }

    /**
     * Draw the screen-centered radial wheel overlay.
     */
    public void draw(Canvas canvas, int viewWidth, int viewHeight, float opacity) {
        if (!isOpen() || activeConfig == null) return;

        this.centerX = viewWidth / 2.0f;
        this.centerY = viewHeight / 2.0f;

        float density = inputControlsView.getContext().getResources().getDisplayMetrics().density;
        float innerRadius = INNER_RADIUS_DP * density;
        float outerRadius = OUTER_RADIUS_DP * density;

        // Dim background
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(COLOR_DIM_BACKGROUND);
        canvas.drawRect(0, 0, viewWidth, viewHeight, paint);

        int sliceCount = activeConfig.slices.size();
        if (sliceCount == 0) return;

        float sliceAngleDeg = 360f / sliceCount;
        float startOffset = -90f; // 12 o'clock

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

            // Fill
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(i == selectedSlice ? COLOR_SLICE_SELECTED : COLOR_SLICE_IDLE);
            canvas.drawPath(path, paint);

            // Border
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(i == selectedSlice ? 0xFF81D4FA : 0x33FFFFFF);
            paint.setStrokeWidth(1.5f * density);
            canvas.drawPath(path, paint);
        }

        // Draw Outer Ring
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(0x88FFFFFF);
        paint.setStrokeWidth(2.5f * density);
        canvas.drawCircle(centerX, centerY, outerRadius, paint);

        // Draw Center Cancel Circle
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(selectedSlice == -1 ? COLOR_CENTER_SELECTED : COLOR_CENTER_IDLE);
        canvas.drawCircle(centerX, centerY, innerRadius, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(selectedSlice == -1 ? 0xFFFF5252 : 0x55FFFFFF);
        paint.setStrokeWidth(2f * density);
        canvas.drawCircle(centerX, centerY, innerRadius, paint);

        // Center Cancel '✕'
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(selectedSlice == -1 ? 0xFFFF5252 : 0x99FFFFFF);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(20f * density);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText("\u2715", centerX, centerY + (paint.getTextSize() / 3.2f), paint);

        // Wheel Title Header
        if (activeConfig.name != null && !activeConfig.name.isEmpty()) {
            paint.setColor(0xFF81D4FA);
            paint.setTextSize(14f * density);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            canvas.drawText(activeConfig.name.toUpperCase(), centerX, centerY - outerRadius - (14f * density), paint);
        }

        // Draw Slice Content (Icon or Label)
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setStyle(Paint.Style.FILL);

        float contentRadius = outerRadius * 0.72f;
        for (int i = 0; i < sliceCount; i++) {
            RadialWheelSlice slice = activeConfig.slices.get(i);
            float midAngle = startOffset + i * sliceAngleDeg + sliceAngleDeg / 2f;
            double midRad = Math.toRadians(midAngle);
            float cx = centerX + (float)(Math.cos(midRad) * contentRadius);
            float cy = centerY + (float)(Math.sin(midRad) * contentRadius);

            if (slice.iconId > 0) {
                Bitmap iconBmp = inputControlsView.getIcon(slice.iconId);
                if (iconBmp != null) {
                    float baseIconSize = 28f * density;
                    float effectiveScale = (slice.iconScale > 0 ? slice.iconScale : 1.0f) * (activeConfig.iconScale > 0 ? activeConfig.iconScale : 1.0f);
                    float iconSize = baseIconSize * effectiveScale;

                    paint.setFilterBitmap(true);
                    if (slice.iconId <= com.winlator.cmod.inputcontrols.CustomIconManager.BUILTIN_ICON_MAX) {
                        paint.setColorFilter(new android.graphics.PorterDuffColorFilter(i == selectedSlice ? 0xFFFFFFFF : 0xFF81D4FA, android.graphics.PorterDuff.Mode.SRC_IN));
                    } else {
                        paint.setColorFilter(null);
                        paint.setAlpha(255);
                    }

                    Rect dst = new Rect((int)(cx - iconSize / 2), (int)(cy - iconSize / 2), (int)(cx + iconSize / 2), (int)(cy + iconSize / 2));
                    canvas.drawBitmap(iconBmp, null, dst, paint);
                    paint.setColorFilter(null);
                    continue;
                }
            }

            String label = (slice.label != null && !slice.label.isEmpty())
                    ? slice.label : (slice.binding != null && slice.binding != Binding.NONE ? slice.binding.toString() : "");
            if (label.isEmpty()) continue;

            if (i == selectedSlice) {
                paint.setColor(0xFFFFFFFF);
                paint.setTextSize(14f * density);
            } else {
                paint.setColor(0xDDFFFFFF);
                paint.setTextSize(12f * density);
            }
            canvas.drawText(label, cx, cy + (paint.getTextSize() / 3f), paint);
        }
    }
}