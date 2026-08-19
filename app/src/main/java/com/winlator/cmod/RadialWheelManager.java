package com.winlator.cmod;

import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.winlator.cmod.inputcontrols.Binding;
import com.winlator.cmod.inputcontrols.RadialWheelConfig;
import com.winlator.cmod.widget.InputControlsView;
import com.winlator.cmod.widget.RadialWheelView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages active Radial Wheels for in-game input overlay.
 */
public class RadialWheelManager {
    private final ViewGroup container;
    private final InputControlsView inputControlsView;
    private final List<RadialWheelConfig> configs = new ArrayList<>();
    private final Map<Integer, RadialWheelView> activeViews = new HashMap<>();

    public RadialWheelManager(ViewGroup container, InputControlsView icv, List<RadialWheelConfig> configs) {
        this.container = container;
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

    public boolean onBindingHeld(Binding binding, float touchX, float touchY) {
        if (binding == null || binding == Binding.NONE) return false;
        for (RadialWheelConfig cfg : configs) {
            if (cfg.triggerBinding == binding) {
                return openWheel(cfg, touchX, touchY);
            }
        }
        return false;
    }

    public boolean openWheel(RadialWheelConfig config, float touchX, float touchY) {
        if (config == null || container == null) return false;
        RadialWheelView existing = activeViews.get(config.id);
        if (existing == null) {
            RadialWheelView wheelView = new RadialWheelView(container.getContext(), config, inputControlsView);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            );
            container.addView(wheelView, lp);
            activeViews.put(config.id, wheelView);
            wheelView.openAt(touchX, touchY);
            return true;
        } else {
            existing.openAt(touchX, touchY);
            return true;
        }
    }

    public boolean onBindingReleased(Binding binding) {
        if (binding == null || binding == Binding.NONE) return false;
        boolean handled = false;
        for (RadialWheelConfig cfg : configs) {
            if (cfg.triggerBinding == binding) {
                RadialWheelView wv = activeViews.remove(cfg.id);
                if (wv != null) {
                    // synthesize UP to trigger selected slice
                    MotionEvent upEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 0, 0, 0);
                    wv.onTouchEvent(upEvent);
                    upEvent.recycle();
                    container.removeView(wv);
                    handled = true;
                }
            }
        }
        return handled;
    }

    public boolean handleTouchEvent(MotionEvent event) {
        if (activeViews.isEmpty()) return false;
        for (RadialWheelView wv : activeViews.values()) {
            if (wv.isVisible()) {
                boolean res = wv.onTouchEvent(event);
                if (!wv.isVisible()) {
                    container.removeView(wv);
                }
                return res;
            }
        }
        return false;
    }

    public boolean hasActiveWheel() {
        for (RadialWheelView wv : activeViews.values()) {
            if (wv.isVisible()) return true;
        }
        return false;
    }

    public void dismissAll() {
        for (RadialWheelView wv : activeViews.values()) {
            wv.close();
            if (container != null) container.removeView(wv);
        }
        activeViews.clear();
    }
}