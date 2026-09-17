package com.winlator.cmod.core;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.WeakHashMap;

public final class RefreshRateUtils {
  private static final String TAG = "RefreshRateUtils";
  private static final float DEFAULT_REFRESH_RATE = 60f;
  private static final float FRAME_CADENCE_EPSILON = 0.01f;
  private static final Map<Activity, ViewTreeObserver.OnWindowFocusChangeListener>
      WINDOW_FOCUS_LISTENERS = new WeakHashMap<>();

  private RefreshRateUtils() {}

  public static int getSavedGlobalRefreshRateOverride(Context context) {
    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
    return Math.max(0, prefs.getInt("refresh_rate_override", 0));
  }

  public static List<Integer> getSupportedRefreshRates(Activity activity) {
    TreeSet<Integer> rates = new TreeSet<>();
    Display display = getDisplay(activity);
    if (display == null) {
      rates.add(Math.round(DEFAULT_REFRESH_RATE));
      return new ArrayList<>(rates);
    }

    for (Display.Mode mode : display.getSupportedModes()) {
      float refreshRate = mode.getRefreshRate();
      if (refreshRate > 0f) {
        rates.add(Math.round(refreshRate));
      }
    }
    if (rates.isEmpty()) {
      rates.add(Math.round(DEFAULT_REFRESH_RATE));
    }
    return new ArrayList<>(rates);
  }

  public static int getMaxSupportedRefreshRate(Activity activity) {
    List<Integer> rates = getSupportedRefreshRates(activity);
    return rates.isEmpty() ? Math.round(DEFAULT_REFRESH_RATE) : rates.get(rates.size() - 1);
  }

  public static List<String> buildRefreshRateEntryLabels(
      Activity activity, @Nullable String leadingEntry) {
    List<String> entries = new ArrayList<>();
    if (leadingEntry != null && !leadingEntry.isEmpty()) {
      entries.add(leadingEntry);
    }
    for (int rate : getSupportedRefreshRates(activity)) {
      entries.add(rate + " Hz");
    }
    return entries;
  }

  public static int parseRefreshRateLabel(@Nullable String value) {
    if (value == null) return 0;
    String trimmed = value.trim();
    if (trimmed.endsWith(" Hz")) {
      trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
    }
    try {
      return Integer.parseInt(trimmed);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  public static float resolvePreferredRefreshRate(Activity activity, int requestedHz) {
    Display display = getDisplay(activity);
    if (display == null) {
      return requestedHz > 0 ? (float) requestedHz : DEFAULT_REFRESH_RATE;
    }

    Display.Mode currentMode = display.getMode();
    Display.Mode[] modes = display.getSupportedModes();
    if (modes == null || modes.length == 0) {
      return requestedHz > 0 ? (float) requestedHz : DEFAULT_REFRESH_RATE;
    }

    if (requestedHz <= 0) {
      float highest = 0f;
      for (Display.Mode mode : modes) {
        if (!isSameModeGroup(currentMode, mode)) continue;
        float rate = mode.getRefreshRate();
        if (rate > highest) highest = rate;
      }
      return highest > 0f ? highest : currentMode.getRefreshRate();
    }

    float bestRate = 0f;
    float minDelta = Float.MAX_VALUE;
    for (Display.Mode mode : modes) {
      if (!isSameModeGroup(currentMode, mode)) continue;
      float rate = mode.getRefreshRate();
      if (Math.round(rate) == requestedHz) {
        return rate;
      }
      float delta = Math.abs(rate - requestedHz);
      if (delta < minDelta) {
        minDelta = delta;
        bestRate = rate;
      }
    }
    return bestRate > 0f ? bestRate : (float) requestedHz;
  }

  public static int resolvePreferredDisplayModeId(Activity activity, int requestedHz) {
    Display display = getDisplay(activity);
    if (display == null) {
      return 0;
    }

    Display.Mode currentMode = display.getMode();
    Display.Mode[] modes = display.getSupportedModes();
    if (modes == null || modes.length == 0) {
      return 0;
    }

    if (requestedHz <= 0) {
      Display.Mode highestMode = null;
      float highest = 0f;
      for (Display.Mode mode : modes) {
        if (!isSameModeGroup(currentMode, mode)) continue;
        float rate = mode.getRefreshRate();
        if (rate > highest) {
          highest = rate;
          highestMode = mode;
        }
      }
      return highestMode != null ? highestMode.getModeId() : 0;
    }

    Display.Mode exactMode = null;
    Display.Mode closestMode = null;
    float minDelta = Float.MAX_VALUE;
    for (Display.Mode mode : modes) {
      if (!isSameModeGroup(currentMode, mode)) continue;
      float rate = mode.getRefreshRate();
      if (Math.round(rate) == requestedHz) {
        exactMode = mode;
        break;
      }
      float delta = Math.abs(rate - requestedHz);
      if (delta < minDelta) {
        minDelta = delta;
        closestMode = mode;
      }
    }

    if (exactMode != null) return exactMode.getModeId();
    if (closestMode != null) return closestMode.getModeId();
    return 0;
  }

  public static int resolveFramePacedRefreshRate(Activity activity, int requestedHz, int fpsLimit) {
    if (fpsLimit <= 0 || requestedHz > 0) {
      return requestedHz;
    }

    float preferredRefreshRate = resolvePreferredRefreshRate(activity, requestedHz);
    if (isFrameCadenceCompatible(preferredRefreshRate, fpsLimit)) {
      return Math.round(preferredRefreshRate);
    }

    Display display = getDisplay(activity);
    if (display == null) {
      return fpsLimit;
    }

    Display.Mode currentMode = display.getMode();
    Display.Mode bestMode = null;
    float bestModeRate = 0f;

    for (Display.Mode mode : display.getSupportedModes()) {
      if (!isSameModeGroup(currentMode, mode)) continue;

      float refreshRate = mode.getRefreshRate();
      if (refreshRate <= 0f || refreshRate < fpsLimit) continue;
      if (!isFrameCadenceCompatible(refreshRate, fpsLimit)) continue;

      if (bestMode == null
          || Math.round(refreshRate) == fpsLimit
          || refreshRate < bestModeRate) {
        bestMode = mode;
        bestModeRate = refreshRate;
      }
    }

    if (bestMode != null) {
      return Math.round(bestModeRate);
    }
    return fpsLimit;
  }

  public static boolean isFrameCadenceCompatible(float refreshRate, int fpsLimit) {
    if (refreshRate <= 0f || fpsLimit <= 0 || refreshRate < fpsLimit) {
      return false;
    }

    float ratio = refreshRate / fpsLimit;
    int nearestMultiple = Math.round(ratio);
    return nearestMultiple >= 1 && Math.abs(ratio - nearestMultiple) <= FRAME_CADENCE_EPSILON;
  }

  private static boolean isSameModeGroup(Display.Mode currentMode, Display.Mode candidateMode) {
    return currentMode.getPhysicalWidth() == candidateMode.getPhysicalWidth()
        && currentMode.getPhysicalHeight() == candidateMode.getPhysicalHeight();
  }

  @Nullable private static Display getDisplay(Activity activity) {
    return activity.getWindow().getDecorView().getDisplay();
  }

  public static void onActivityCreated(Activity activity) {
    attachWindowFocusListener(activity);
    applyPreferredRefreshRate(activity);
  }

  public static void onActivityResumed(Activity activity) {
    applyPreferredRefreshRate(activity);
  }

  public static void onActivityDestroyed(Activity activity) {
    detachWindowFocusListener(activity);
  }

  private static void attachWindowFocusListener(Activity activity) {
    if (WINDOW_FOCUS_LISTENERS.containsKey(activity)) return;

    View decorView = activity.getWindow().getDecorView();
    if (decorView == null) return;

    ViewTreeObserver observer = decorView.getViewTreeObserver();
    if (!observer.isAlive()) return;

    ViewTreeObserver.OnWindowFocusChangeListener listener =
        hasFocus -> {
          if (hasFocus) {
            applyPreferredRefreshRate(activity);
          }
        };
    observer.addOnWindowFocusChangeListener(listener);
    WINDOW_FOCUS_LISTENERS.put(activity, listener);
  }

  private static void detachWindowFocusListener(Activity activity) {
    ViewTreeObserver.OnWindowFocusChangeListener listener = WINDOW_FOCUS_LISTENERS.remove(activity);
    if (listener == null) return;

    View decorView = activity.getWindow().getDecorView();
    if (decorView == null) return;

    ViewTreeObserver observer = decorView.getViewTreeObserver();
    if (!observer.isAlive()) return;
    observer.removeOnWindowFocusChangeListener(listener);
  }

  public static void applyPreferredRefreshRate(Activity activity) {
    applyPreferredRefreshRate(activity, getSavedGlobalRefreshRateOverride(activity));
  }

  public static void applyPreferredRefreshRate(Activity activity, int requestedHz) {
    applyPreferredRefreshRate(activity, requestedHz, 0);
  }

  public static void applyPreferredRefreshRate(Activity activity, int requestedHz, int fpsLimit) {
    if (activity.isFinishing() || activity.isDestroyed()) return;

    if (getDisplay(activity) == null) return;

    int effectiveRequestedHz = resolveFramePacedRefreshRate(activity, requestedHz, fpsLimit);
    WindowManager.LayoutParams params = activity.getWindow().getAttributes();
    int modeId = resolvePreferredDisplayModeId(activity, effectiveRequestedHz);
    float refreshRate = resolvePreferredRefreshRate(activity, effectiveRequestedHz);
    params.preferredDisplayModeId = modeId;
    params.preferredRefreshRate = modeId != 0 ? 0f : refreshRate;
    activity.getWindow().setAttributes(params);
    Log.d(
        TAG,
        activity.getClass().getSimpleName()
            + " applyPreferredRefreshRate requestedHz="
            + requestedHz
            + " fpsLimit="
            + fpsLimit
            + " effectiveRequestedHz="
            + effectiveRequestedHz
            + " modeId="
            + modeId
            + " refreshRate="
            + refreshRate);
  }

  public static float getActiveRefreshRate(Activity activity) {
    Display display = getDisplay(activity);
    if (display == null) return 0f;
    Display.Mode mode = display.getMode();
    return mode != null ? mode.getRefreshRate() : 0f;
  }
}
