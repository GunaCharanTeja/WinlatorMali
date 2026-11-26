package com.winlator.cmod;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.util.Log;
import android.widget.Toast;

public class ShortcutBroadcastReceiver extends BroadcastReceiver {
    private static final String LOG_TAG = "ShortcutBroadcastReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action != null && action.equals("com.winlator.SHORTCUT_ADDED")) {
            boolean success = intent.getBooleanExtra("shortcut_added", false);
            if (success) {
                Log.d(LOG_TAG, "Shortcut added successfully!");
                Toast.makeText(context, "Shortcut added successfully!", Toast.LENGTH_SHORT).show();
            } else {
                Log.d(LOG_TAG, "Shortcut addition failed or needing manual intervention.");
                addShortcutToHomeScreen(context, intent);
            }
        }
    }

    private void addShortcutToHomeScreen(Context context, Intent intent) {
        String name = intent.getStringExtra("shortcut_name");
        Bitmap icon = intent.getParcelableExtra("shortcut_icon");
        Intent shortcutIntent = intent.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT);

        if (name != null && icon != null && shortcutIntent != null) {
            ShortcutManager shortcutManager = context.getSystemService(ShortcutManager.class);
            if (shortcutManager != null && shortcutManager.isRequestPinShortcutSupported()) {
                ShortcutInfo shortcutInfo = new ShortcutInfo.Builder(context, name)
                        .setShortLabel(name)
                        .setIcon(Icon.createWithBitmap(icon))
                        .setIntent(shortcutIntent)
                        .build();

                Log.d(LOG_TAG, "Requesting pin shortcut from the BroadcastReceiver...");
                boolean result = shortcutManager.requestPinShortcut(shortcutInfo, null);
                Log.d(LOG_TAG, "Pin shortcut requested with result: " + result);

                if (result) {
                    Toast.makeText(context, "Shortcut added successfully from BroadcastReceiver!", Toast.LENGTH_SHORT).show();
                } else {
                    Log.e(LOG_TAG, "Failed to add shortcut from BroadcastReceiver.");
                }
            }
        } else {
            Log.e(LOG_TAG, "Missing shortcut data, cannot add to home screen.");
        }
    }
}
