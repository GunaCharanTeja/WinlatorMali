package com.winlator.cmod.core;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.view.animation.RotateAnimation;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.winlator.cmod.R;
import com.winlator.cmod.ThemeManager;

public class PreloaderDialog {
    private final Activity activity;
    private Dialog dialog;
    // true while `dialog` holds the fullscreen hero layout instead of the small card -- close()/isShowing() work
    // either way, but showInternal's "already showing, just update the text" path only applies to the small card.
    private boolean fullscreen;

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
        fullscreen = false;

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

    // The fullscreen launch screen: the shortcut's hero art full-bleed behind a bottom-anchored title/status block.
    // icon/heroArt may each be null (a direct container launch with no shortcut, or a shortcut whose art hasn't
    // loaded yet) -- the layout already hides the icon and the scrim alone still reads fine over a null/black image.
    private void createFullscreen(String title, Bitmap icon, Bitmap heroArt) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;

        dialog = new Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setContentView(R.layout.preloader_fullscreen_dialog);
        fullscreen = true;

        ImageView ivBg = dialog.findViewById(R.id.IVHeroBackground);
        if (ivBg != null) {
            if (heroArt != null) ivBg.setImageBitmap(heroArt);
            else ivBg.setBackgroundColor(ThemeManager.getSurfaceColor(activity));
        }

        ImageView ivIcon = dialog.findViewById(R.id.IVHeroIcon);
        if (ivIcon != null) {
            if (icon != null) {
                ivIcon.setImageBitmap(icon);
                ivIcon.setVisibility(View.VISIBLE);
            } else {
                ivIcon.setVisibility(View.GONE);
            }
        }

        TextView tvTitle = dialog.findViewById(R.id.TVHeroTitle);
        if (tvTitle != null) tvTitle.setText(title != null ? title : "");

        int accentColor = ThemeManager.getAccentColor(activity);
        CircularProgressIndicator cpi = dialog.findViewById(R.id.CircularProgressIndicatorHero);
        if (cpi != null) {
            cpi.setIndicatorColor(accentColor);
            cpi.setIndeterminate(true);
        }

        Window window = dialog.getWindow();
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    private void showFullscreenInternal(String title, Bitmap icon, Bitmap heroArt, String status) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        closeInternal();
        createFullscreen(title, icon, heroArt);
        if (dialog == null) return;

        TextView tvStatus = dialog.findViewById(R.id.TVHeroStatus);
        if (tvStatus != null) tvStatus.setText(status != null ? status : "");

        try {
            dialog.show();
        } catch (Exception ignored) {}
    }

    /** The fullscreen launch screen for a game/shortcut launch: hero art behind title + status. */
    public void showFullscreen(String title, Bitmap icon, Bitmap heroArt, String status) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            showFullscreenInternal(title, icon, heroArt, status);
        } else if (activity != null) {
            activity.runOnUiThread(() -> showFullscreenInternal(title, icon, heroArt, status));
        }
    }

    /**
     * Refresh the hero background/icon in place, for the fullscreen screen only -- the shortcut's art loads on a
     * background thread (see Shortcut.runBackgroundInitialization), usually finishing a beat after the dialog
     * first shows with whatever was ready synchronously (often the generated fallback, sometimes nothing yet).
     * A no-op when the fullscreen dialog isn't up (closed already, or showFullscreen was never called).
     */
    public void updateHero(Bitmap heroArt, Bitmap icon) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            if (activity != null) activity.runOnUiThread(() -> updateHero(heroArt, icon));
            return;
        }
        if (dialog == null || !fullscreen) return;
        if (heroArt != null) {
            ImageView ivBg = dialog.findViewById(R.id.IVHeroBackground);
            if (ivBg != null) ivBg.setImageBitmap(heroArt);
        }
        if (icon != null) {
            ImageView ivIcon = dialog.findViewById(R.id.IVHeroIcon);
            if (ivIcon != null) {
                ivIcon.setImageBitmap(icon);
                ivIcon.setVisibility(View.VISIBLE);
            }
        }
    }

    /** Update the status line under the title, without rebuilding the dialog (fullscreen) or the whole card. */
    public void setStatus(String status) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            if (activity != null) activity.runOnUiThread(() -> setStatus(status));
            return;
        }
        if (dialog == null) return;
        TextView tv = dialog.findViewById(fullscreen ? R.id.TVHeroStatus : R.id.TextView);
        if (tv != null && status != null) tv.setText(status);
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
        if (isShowing() && !fullscreen) {
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
