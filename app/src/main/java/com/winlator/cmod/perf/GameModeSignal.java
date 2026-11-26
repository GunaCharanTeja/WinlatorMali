package com.winlator.cmod.perf;

import android.app.GameManager;
import android.app.GameState;
import android.content.Context;
import android.os.Build;
import android.util.Log;

/**
 * Non-root Android 13+ (API 33) GameManager game-mode signaling.
 * Tells Android and OEM game boosters (OnePlus HyperBoost, Xiaomi Game Turbo,
 * Samsung Game Booster, RedMagic, Pixel Game Dashboard) that an active uninterruptible
 * game is running, triggering performance profiles automatically without root.
 */
public class GameModeSignal {
    private static final String TAG = "GameModeSignal";
    private static boolean inGameplay = false;

    public static void enterGameplay(Context context) {
        if (context == null) return;
        setState(context, true);
    }

    public static void exitGameplay(Context context) {
        if (context == null) return;
        setState(context, false);
    }

    public static boolean isInGameplay() {
        return inGameplay;
    }

    private static void setState(Context context, boolean playing) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        try {
            GameManager gameManager = context.getSystemService(GameManager.class);
            if (gameManager == null) return;
            int mode = playing ? GameState.MODE_GAMEPLAY_UNINTERRUPTIBLE : GameState.MODE_NONE;
            gameManager.setGameState(new GameState(false, mode));
            inGameplay = playing;
            Log.d(TAG, "GameState set: playing=" + playing + " (mode=" + mode + ")");
        } catch (Throwable e) {
            Log.w(TAG, "Failed to set GameState: " + e.getMessage());
        }
    }
}
