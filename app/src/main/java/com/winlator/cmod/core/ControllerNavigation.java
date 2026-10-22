package com.winlator.cmod.core;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.AbsListView;
import android.widget.ScrollView;

import androidx.recyclerview.widget.RecyclerView;

import com.winlator.cmod.ExternalControllerBindingsActivity;
import com.winlator.cmod.ThemeManager;
import com.winlator.cmod.XServerDisplayActivity;

import java.util.WeakHashMap;

/**
 * Makes the regular touch UI usable with a gamepad. The D-pad / left stick already move focus natively;
 * this adds what's missing: a visible highlight on the focused view (our custom backgrounds have no
 * focused state, so focus was invisible), A = select, B = back, X/Y = long-press (item menus).
 */
public final class ControllerNavigation {
    private static final WeakHashMap<View, Drawable> savedForegrounds = new WeakHashMap<>();

    public interface Dispatcher {
        boolean dispatch(KeyEvent event);
    }

    private ControllerNavigation() {}

    /** Off for screens that need raw gamepad buttons: the binding editor, and the game itself. */
    public static boolean isEnabledFor(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof ExternalControllerBindingsActivity || context instanceof XServerDisplayActivity) return false;
            if (context instanceof android.app.Activity) return true;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return true;
    }

    public static boolean dispatch(KeyEvent event, Window window, Dispatcher fallback) {
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_BUTTON_A:
                return fallback.dispatch(remap(event, KeyEvent.KEYCODE_DPAD_CENTER));
            case KeyEvent.KEYCODE_BUTTON_B:
                return fallback.dispatch(remap(event, KeyEvent.KEYCODE_BACK));
            case KeyEvent.KEYCODE_BUTTON_X:
            case KeyEvent.KEYCODE_BUTTON_Y:
                if (event.getAction() == KeyEvent.ACTION_UP && window != null) {
                    View focused = window.getCurrentFocus();
                    if (focused != null) focused.performLongClick();
                }
                return true;
            default:
                return fallback.dispatch(event);
        }
    }

    private static KeyEvent remap(KeyEvent e, int keyCode) {
        return new KeyEvent(e.getDownTime(), e.getEventTime(), e.getAction(), keyCode, e.getRepeatCount(),
                e.getMetaState(), e.getDeviceId(), e.getScanCode(), e.getFlags(), e.getSource());
    }

    /** Draws the focus highlight for every view in this window while it isn't in touch mode. */
    public static void attach(Window window) {
        if (window == null) return;
        View decor = window.getDecorView();
        ViewTreeObserver observer = decor.getViewTreeObserver();
        observer.addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
            clearHighlight(oldFocus);
            if (newFocus != null && !newFocus.isInTouchMode()) highlight(newFocus);
        });
        observer.addOnTouchModeChangeListener(inTouchMode -> {
            View focused = window.getCurrentFocus();
            if (focused == null) return;
            if (inTouchMode) clearHighlight(focused);
            else highlight(focused);
        });
    }

    private static void highlight(View view) {
        // Scroll containers only get focus when they have nothing focusable inside; outlining the
        // whole list would just look broken.
        if (view instanceof RecyclerView || view instanceof AbsListView || view instanceof ScrollView) return;
        if (!savedForegrounds.containsKey(view)) savedForegrounds.put(view, view.getForeground());

        Context context = view.getContext();
        int accent = ThemeManager.getAccentColor(context);
        GradientDrawable outline = new GradientDrawable();
        outline.setCornerRadius(UnitUtils.dpToPx(10));
        outline.setStroke((int) UnitUtils.dpToPx(2.5f), accent);
        outline.setColor((accent & 0x00FFFFFF) | 0x26000000);
        view.setForeground(outline);
    }

    private static void clearHighlight(View view) {
        if (view == null || !savedForegrounds.containsKey(view)) return;
        view.setForeground(savedForegrounds.remove(view));
    }
}
