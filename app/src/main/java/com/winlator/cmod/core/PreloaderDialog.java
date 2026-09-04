package com.winlator.cmod.core;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.view.animation.RotateAnimation;
import android.widget.TextView;

import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.winlator.cmod.R;
import com.winlator.cmod.ThemeManager;

public class PreloaderDialog {
    private final Activity activity;
    private Dialog dialog;

    public PreloaderDialog(Activity activity) {
        this.activity = activity;
    }

    private void create() {
        if (dialog != null) return;
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;

        dialog = new Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setContentView(R.layout.preloader_dialog);

        View bgView = dialog.findViewById(R.id.LLPreloaderBackground);
        int surfaceColor = ThemeManager.getSurfaceColor(activity);
        int accentColor = ThemeManager.getAccentColor(activity);
        int onSurfaceColor = ThemeManager.getOnSurfaceTextColor(activity);

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(surfaceColor);
        gd.setCornerRadius(UnitUtils.dpToPx(14));
        gd.setStroke((int)UnitUtils.dpToPx(1), 0x33FFFFFF);
        if (bgView != null) bgView.setBackground(gd);

        TextView tv = dialog.findViewById(R.id.TextView);
        if (tv != null) {
            tv.setTextColor(onSurfaceColor);
        }

        CircularProgressIndicator cpi = dialog.findViewById(R.id.CircularProgressIndicator);
        if (cpi != null) {
            cpi.setIndicatorColor(accentColor);
            cpi.setTrackColor(0x33888888);
            cpi.setIndeterminate(true);
        }

        Window window = dialog.getWindow();
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
    }

    private void startSpinnerAnimation() {
        if (dialog == null) return;
        CircularProgressIndicator cpi = dialog.findViewById(R.id.CircularProgressIndicator);
        if (cpi != null) {
            cpi.setVisibility(View.VISIBLE);
            cpi.setIndeterminate(true);
            int accentColor = ThemeManager.getAccentColor(activity);
            cpi.setIndicatorColor(accentColor);

            cpi.clearAnimation();
            RotateAnimation rotate = new RotateAnimation(
                0f, 360f,
                Animation.RELATIVE_TO_SELF, 0.5f,
                Animation.RELATIVE_TO_SELF, 0.5f
            );
            rotate.setDuration(900);
            rotate.setRepeatCount(Animation.INFINITE);
            rotate.setInterpolator(new LinearInterpolator());
            cpi.startAnimation(rotate);
        }
    }

    private void showInternal(String text) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (isShowing()) {
            TextView tv = dialog.findViewById(R.id.TextView);
            if (tv != null && text != null) tv.setText(text);
            return;
        }
        closeInternal();
        create();
        if (dialog == null) return;

        TextView tv = dialog.findViewById(R.id.TextView);
        if (tv != null && text != null) {
            tv.setText(text);
        }
        startSpinnerAnimation();
        try {
            dialog.show();
        } catch (Exception ignored) {}
    }

    private void closeInternal() {
        try {
            if (dialog != null) {
                CircularProgressIndicator cpi = dialog.findViewById(R.id.CircularProgressIndicator);
                if (cpi != null) {
                    cpi.clearAnimation();
                }
                dialog.dismiss();
            }
        } catch (Exception ignored) {}
        dialog = null;
    }

    public void show(int textResId) {
        String text = (activity != null && textResId > 0) ? activity.getString(textResId) : "";
        show(text);
    }

    public void show(String text) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            showInternal(text);
        } else if (activity != null) {
            activity.runOnUiThread(() -> showInternal(text));
        }
    }

    public void showOnUiThread(final int textResId) {
        show(textResId);
    }

    public void showOnUiThread(final String text) {
        show(text);
    }

    public void close() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            closeInternal();
        } else if (activity != null) {
            activity.runOnUiThread(this::closeInternal);
        }
    }

    public void closeOnUiThread() {
        close();
    }

    public boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }
}
