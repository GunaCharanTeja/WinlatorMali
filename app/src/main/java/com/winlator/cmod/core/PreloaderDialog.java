package com.winlator.cmod.core;

import android.app.Activity;
import android.app.Dialog;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import com.winlator.cmod.R;

public class PreloaderDialog {
    private final Activity activity;
    private Dialog dialog;

    public PreloaderDialog(Activity activity) {
        this.activity = activity;
    }

    private void create() {
        if (dialog != null) return;
        dialog = new Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setContentView(R.layout.preloader_dialog);

        android.view.View bgView = dialog.findViewById(R.id.LLPreloaderBackground);
        int surfaceColor = com.winlator.cmod.ThemeManager.getSurfaceColor(activity);
        int accentColor = com.winlator.cmod.ThemeManager.getAccentColor(activity);
        int onSurfaceColor = com.winlator.cmod.ThemeManager.getOnSurfaceTextColor(activity);

        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(surfaceColor);
        gd.setCornerRadius(UnitUtils.dpToPx(14));
        gd.setStroke((int)UnitUtils.dpToPx(1), 0x33FFFFFF);
        bgView.setBackground(gd);

        TextView tv = dialog.findViewById(R.id.TextView);
        if (tv != null) {
            tv.setTextColor(onSurfaceColor);
        }

        android.widget.ProgressBar pb = dialog.findViewById(R.id.ProgressBar);
        if (pb != null) {
            pb.setIndeterminate(true);
            pb.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(accentColor));
            pb.setIndeterminateTintMode(android.graphics.PorterDuff.Mode.SRC_IN);
        }

        Window window = dialog.getWindow();
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
    }

    private void updateProgressBarAnimation() {
        if (dialog == null) return;
        android.widget.ProgressBar pb = dialog.findViewById(R.id.ProgressBar);
        if (pb != null) {
            pb.setVisibility(android.view.View.VISIBLE);
            pb.setIndeterminate(true);
            int accentColor = com.winlator.cmod.ThemeManager.getAccentColor(activity);
            pb.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(accentColor));
            pb.setIndeterminateTintMode(android.graphics.PorterDuff.Mode.SRC_IN);
            android.graphics.drawable.Drawable d = pb.getIndeterminateDrawable();
            if (d instanceof android.graphics.drawable.Animatable) {
                ((android.graphics.drawable.Animatable) d).start();
            }
        }
    }

    public synchronized void show(int textResId) {
        if (isShowing()) return;
        close();
        if (dialog == null) create();
        ((TextView)dialog.findViewById(R.id.TextView)).setText(textResId);
        updateProgressBarAnimation();
        dialog.show();
    }

    public synchronized void show(String text) {
        if (isShowing()) return;
        close();
        if (dialog == null) create();
        ((TextView)dialog.findViewById(R.id.TextView)).setText(text);
        updateProgressBarAnimation();
        dialog.show();
    }

    public void showOnUiThread(final int textResId) {
        activity.runOnUiThread(() -> show(textResId));
    }

    public void showOnUiThread(final String text) {
        activity.runOnUiThread(() -> show(text));
    }

    public synchronized void close() {
        try {
            if (dialog != null) {
                dialog.dismiss();
            }
        }
        catch (Exception e) {}
    }

    public void closeOnUiThread() {
        activity.runOnUiThread(this::close);
    }

    public boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }
}
