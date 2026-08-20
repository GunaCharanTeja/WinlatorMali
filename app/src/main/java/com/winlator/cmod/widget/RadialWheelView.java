package com.winlator.cmod.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import com.winlator.cmod.inputcontrols.Binding;
import com.winlator.cmod.inputcontrols.RadialWheelConfig;
import com.winlator.cmod.inputcontrols.RadialWheelSlice;

public class RadialWheelView extends View {
    private final RadialWheelConfig config;
    private final InputControlsView inputControlsView;
    private boolean visible = false;
    private int selectedSlice = -1;
    private float touchX, touchY;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private static final float INNER_RADIUS_DP = 36f;
    private static final float OUTER_RADIUS_DP = 100f;
    private static final int COLOR_IDLE     = 0x66000000;
    private static final int COLOR_SELECTED = 0xCC2196F3;
    private static final int COLOR_CENTER   = 0x88000000;
    private static final int COLOR_LABEL    = 0xFFFFFFFF;

    public RadialWheelView(Context context, RadialWheelConfig config, InputControlsView icv) {
        super(context);
        this.config = config;
        this.inputControlsView = icv;
        setClickable(false);
        setFocusable(false);
    }

    public void openAt(float cx, float cy) {
        touchX = cx;
        touchY = cy;
        visible = true;
        selectedSlice = -1;
        invalidate();
    }

    public void close() {
        visible = false;
        selectedSlice = -1;
        invalidate();
    }

    public boolean isVisible() {
        return visible;
    }

    public int getSelectedSlice() {
        return selectedSlice;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!visible) return false;

        float density = getContext().getResources().getDisplayMetrics().density;
        float innerRadius = INNER_RADIUS_DP * density;

        switch (event.getAction()) {
            case MotionEvent.ACTION_MOVE: {
                float x = event.getX();
                float y = event.getY();
                float dx = x - touchX;
                float dy = y - touchY;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (dist < innerRadius) {
                    selectedSlice = -1;
                } else {
                    int sliceCount = config.slices.size();
                    if (sliceCount > 0) {
                        // atan2 in degrees, 0=right, going clockwise; offset by -90 so 0=top
                        double angleDeg = Math.toDegrees(Math.atan2(dy, dx));
                        // Shift so that 0 degrees corresponds to top (slice 0 at top)
                        angleDeg = (angleDeg + 90 + 360) % 360;
                        double sliceAngle = 360.0 / sliceCount;
                        selectedSlice = (int) (angleDeg / sliceAngle);
                        if (selectedSlice >= sliceCount) selectedSlice = sliceCount - 1;
                    }
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (selectedSlice >= 0 && selectedSlice < config.slices.size()) {
                    RadialWheelSlice slice = config.slices.get(selectedSlice);
                    if (slice != null) {
                        if (slice.binding != null && slice.binding != Binding.NONE) inputControlsView.handleInputEvent(slice.binding, true);
                        if (slice.binding2 != null && slice.binding2 != Binding.NONE) inputControlsView.handleInputEvent(slice.binding2, true);
                        if (slice.binding3 != null && slice.binding3 != Binding.NONE) inputControlsView.handleInputEvent(slice.binding3, true);

                        if (slice.binding3 != null && slice.binding3 != Binding.NONE) inputControlsView.handleInputEvent(slice.binding3, false);
                        if (slice.binding2 != null && slice.binding2 != Binding.NONE) inputControlsView.handleInputEvent(slice.binding2, false);
                        if (slice.binding != null && slice.binding != Binding.NONE) inputControlsView.handleInputEvent(slice.binding, false);
                    }
                }
                close();
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                close();
                return true;
            }
        }
        return false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!visible) return;

        float density = getContext().getResources().getDisplayMetrics().density;
        float innerRadius = INNER_RADIUS_DP * density;
        float outerRadius = OUTER_RADIUS_DP * density;

        int sliceCount = config.slices.size();
        if (sliceCount == 0) return;

        float sliceAngleDeg = 360f / sliceCount;
        // offset so slice 0 is at top
        float startOffset = -90f;

        // Draw each slice
        for (int i = 0; i < sliceCount; i++) {
            float startAngle = startOffset + i * sliceAngleDeg;
            float sweepAngle = sliceAngleDeg;

            path.reset();
            // Move to inner arc start
            double startRad = Math.toRadians(startAngle);
            float ix = touchX + (float)(Math.cos(startRad) * innerRadius);
            float iy = touchY + (float)(Math.sin(startRad) * innerRadius);
            path.moveTo(ix, iy);

            // Outer arc
            RectF outerRect = new RectF(touchX - outerRadius, touchY - outerRadius,
                    touchX + outerRadius, touchY + outerRadius);
            path.arcTo(outerRect, startAngle, sweepAngle);

            // Inner arc (reverse)
            RectF innerRect = new RectF(touchX - innerRadius, touchY - innerRadius,
                    touchX + innerRadius, touchY + innerRadius);
            path.arcTo(innerRect, startAngle + sweepAngle, -sweepAngle);
            path.close();

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(i == selectedSlice ? COLOR_SELECTED : COLOR_IDLE);
            canvas.drawPath(path, paint);

            // Stroke between slices
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(0x44FFFFFF);
            paint.setStrokeWidth(1f * density);
            canvas.drawPath(path, paint);
        }

        // Draw outer ring stroke
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(0x88FFFFFF);
        paint.setStrokeWidth(2f * density);
        canvas.drawCircle(touchX, touchY, outerRadius, paint);

        // Draw cancel center dot
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(COLOR_CENTER);
        canvas.drawCircle(touchX, touchY, innerRadius, paint);

        // Draw cancel "✕" in center
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xAAFFFFFF);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(16f * density);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText("\u2715", touchX, touchY + (paint.getTextSize() / 3f), paint);

        // Draw slice contents (Icon or label)
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setStyle(Paint.Style.FILL);

        float labelRadius = outerRadius * 0.72f;
        for (int i = 0; i < sliceCount; i++) {
            RadialWheelSlice slice = config.slices.get(i);
            float midAngle = startOffset + i * sliceAngleDeg + sliceAngleDeg / 2f;
            double midRad = Math.toRadians(midAngle);
            float lx = touchX + (float)(Math.cos(midRad) * labelRadius);
            float ly = touchY + (float)(Math.sin(midRad) * labelRadius);

            if (slice.iconId > 0) {
                android.graphics.Bitmap iconBmp = inputControlsView.getIcon(slice.iconId);
                if (iconBmp != null) {
                    float baseSize = 24f * density;
                    float effectiveScale = (slice.iconScale > 0 ? slice.iconScale : 1.0f) * (config.iconScale > 0 ? config.iconScale : 1.0f);
                    float iconSize = baseSize * effectiveScale;

                    paint.setFilterBitmap(true);
                    if (slice.iconId <= com.winlator.cmod.inputcontrols.CustomIconManager.BUILTIN_ICON_MAX) {
                        paint.setColorFilter(new android.graphics.PorterDuffColorFilter(i == selectedSlice ? 0xFFFFFFFF : 0xFF81D4FA, android.graphics.PorterDuff.Mode.SRC_IN));
                    } else {
                        paint.setColorFilter(null);
                        paint.setAlpha(255);
                    }

                    android.graphics.Rect dst = new android.graphics.Rect((int)(lx - iconSize / 2), (int)(ly - iconSize / 2), (int)(lx + iconSize / 2), (int)(ly + iconSize / 2));
                    canvas.drawBitmap(iconBmp, null, dst, paint);
                    paint.setColorFilter(null);
                    continue;
                }
            }

            String label = (slice.label != null && !slice.label.isEmpty())
                    ? slice.label : (slice.binding != null && slice.binding != Binding.NONE ? slice.binding.toString() : "");
            if (label.isEmpty()) continue;

            paint.setColor(i == selectedSlice ? 0xFFFFFFFF : COLOR_LABEL);
            paint.setTextSize((i == selectedSlice ? 13f : 11f) * density);
            canvas.drawText(label, lx, ly + (paint.getTextSize() / 3f), paint);
        }
    }
}