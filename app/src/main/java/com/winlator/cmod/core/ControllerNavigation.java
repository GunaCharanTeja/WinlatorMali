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
 * focused state, so focus was invisible), A = select, B = back, X = long-press (item menus),
 * LT/RT = page up/down. Screen-level buttons (Y, START, SELECT, LB/RB) are handled by MainActivity.
 */
public final class ControllerNavigation {
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
                if (event.getAction() == KeyEvent.ACTION_UP && window != null) {
                    View focused = window.getCurrentFocus();
                    if (focused != null) focused.performLongClick();
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
                // Used by MainActivity (jump to top bar); swallowed elsewhere so it can't fall back to BACK.
                return true;
            case KeyEvent.KEYCODE_BUTTON_L2:
            case KeyEvent.KEYCODE_BUTTON_R2:
                // Page through long lists: a burst of D-pad steps keeps focus and scrolling in sync.
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    int dpad = event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_L2 ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN;
                    for (int i = 0; i < 5; i++) {
                        fallback.dispatch(remap(event, dpad, KeyEvent.ACTION_DOWN));
                        fallback.dispatch(remap(event, dpad, KeyEvent.ACTION_UP));
                    }
                }
                return true;
            default:
                return fallback.dispatch(event);
        }
    }

    private static KeyEvent remap(KeyEvent e, int keyCode, int action) {
        return new KeyEvent(e.getDownTime(), e.getEventTime(), action, keyCode, 0,
                e.getMetaState(), e.getDeviceId(), e.getScanCode(), e.getFlags(), e.getSource());
    }

    public static boolean isControllerConnected() {
        for (int id : android.view.InputDevice.getDeviceIds()) {
            android.view.InputDevice device = android.view.InputDevice.getDevice(id);
            if (device == null || device.isVirtual()) continue;
            int sources = device.getSources();
            if ((sources & android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD ||
                (sources & android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK) return true;
        }
        return false;
    }

    /** Fills the hint bar with button chips: {glyph, label, color} triples. */
    public static void buildHints(android.widget.LinearLayout bar, String[][] hints) {
        Context context = bar.getContext();
        bar.removeAllViews();
        int size = (int) UnitUtils.dpToPx(22);
        for (String[] hint : hints) {
            android.widget.TextView glyph = new android.widget.TextView(context);
            glyph.setText(hint[0]);
            glyph.setTextColor(0xFFFFFFFF);
            glyph.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, hint[0].length() > 1 ? 9 : 11);
            glyph.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            glyph.setGravity(android.view.Gravity.CENTER);
            GradientDrawable chip = new GradientDrawable();
            chip.setColor(android.graphics.Color.parseColor(hint[2]));
            chip.setCornerRadius(size / 2f);
            glyph.setBackground(chip);
            glyph.setMinWidth(size);
            glyph.setPadding((int) UnitUtils.dpToPx(5), 0, (int) UnitUtils.dpToPx(5), 0);
            android.widget.LinearLayout.LayoutParams glyphParams = new android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, size);
            glyphParams.setMarginStart((int) UnitUtils.dpToPx(10));
            bar.addView(glyph, glyphParams);

            android.widget.TextView label = new android.widget.TextView(context);
            label.setText(hint[1]);
            label.setTextColor(0xDDFFFFFF);
            label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
            android.widget.LinearLayout.LayoutParams labelParams = new android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            labelParams.setMarginStart((int) UnitUtils.dpToPx(5));
            bar.addView(label, labelParams);
        }
    }

    private static KeyEvent remap(KeyEvent e, int keyCode) {
        return new KeyEvent(e.getDownTime(), e.getEventTime(), e.getAction(), keyCode, e.getRepeatCount(),
                e.getMetaState(), e.getDeviceId(), e.getScanCode(), e.getFlags(), e.getSource());
    }

    /**
     * Draws a focus ring around whichever view has focus in this window, while it isn't in touch mode.
     * It lives in the window's overlay rather than on the focused view itself, because some views manage
     * their own foreground layer (e.g. the drawer's menu rows) and a per-view outline never showed there.
     */
    public static void attach(Window window) {
        if (window == null || !(window.getDecorView() instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup decor = (android.view.ViewGroup) window.getDecorView();
        FocusRing ring = new FocusRing(decor);
        ViewTreeObserver observer = decor.getViewTreeObserver();
        observer.addOnGlobalFocusChangeListener((oldFocus, newFocus) ->
                ring.setTarget(newFocus != null && !newFocus.isInTouchMode() ? newFocus : null));
        observer.addOnTouchModeChangeListener(inTouchMode ->
                ring.setTarget(inTouchMode ? null : window.getCurrentFocus()));
        // Follows the target as lists scroll and the drawer slides.
        observer.addOnPreDrawListener(() -> {
            ring.update();
            return true;
        });
    }

    private static final class FocusRing {
        private final android.view.ViewGroup decor;
        private final GradientDrawable drawable = new GradientDrawable();
        private final android.graphics.Rect rect = new android.graphics.Rect();
        private View target;
        private boolean shown;

        FocusRing(android.view.ViewGroup decor) {
            this.decor = decor;
            int accent = ThemeManager.getAccentColor(decor.getContext());
            drawable.setCornerRadius(UnitUtils.dpToPx(10));
            drawable.setStroke((int) UnitUtils.dpToPx(2.5f), accent);
            drawable.setColor((accent & 0x00FFFFFF) | 0x26000000);
        }

        void setTarget(View view) {
            // Scroll containers only get focus when they have nothing focusable inside; outlining the whole
            // list would just look broken.
            if (view instanceof RecyclerView || view instanceof AbsListView || view instanceof ScrollView) view = null;
            target = view;
            update();
        }

        void update() {
            // Visible rect in window coordinates, already clipped by scrolling parents.
            if (target == null || !target.isAttachedToWindow() || !target.isShown() || !target.getGlobalVisibleRect(rect)) {
                if (shown) {
                    decor.getOverlay().remove(drawable);
                    shown = false;
                }
                return;
            }
            if (!shown) {
                decor.getOverlay().add(drawable);
                shown = true;
            }
            if (!drawable.getBounds().equals(rect)) drawable.setBounds(rect);
        }
    }
}
