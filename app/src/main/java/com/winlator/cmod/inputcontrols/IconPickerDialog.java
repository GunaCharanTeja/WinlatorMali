package com.winlator.cmod.inputcontrols;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;

import com.winlator.cmod.R;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.UnitUtils;

import java.io.File;
import java.util.List;

/**
 * Phase 5: Universal Icon Picker with Custom Image Import, .icpx Support, and Built-in Icons.
 */
public class IconPickerDialog {
    public interface OnIconSelectedCallback {
        void onIconSelected(int iconId);
    }

    public static void show(Context context, int currentIconId, OnIconSelectedCallback callback) {
        ContentDialog dialog = new ContentDialog(context);
        dialog.setTitle("Select Icon");
        dialog.setIcon(R.drawable.icon_radial_wheel);

        FrameLayout frameLayout = dialog.findViewById(R.id.FrameLayout);
        if (frameLayout == null) return;
        frameLayout.setVisibility(View.VISIBLE);

        ScrollView scrollView = new ScrollView(context);
        LinearLayout rootLayout = new LinearLayout(context);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setPadding(16, 16, 16, 16);
        scrollView.addView(rootLayout);
        frameLayout.addView(scrollView);

        CustomIconManager iconManager = CustomIconManager.getInstance(context);

        // ── Toolbar Buttons ──
        LinearLayout toolbar = new LinearLayout(context);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setPadding(0, 0, 0, 12);

        android.content.SharedPreferences preferences = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
        boolean isDarkMode = preferences.getBoolean("dark_mode", true);

        Button btNoIcon = new Button(context);
        btNoIcon.setText("None (Text Only)");
        btNoIcon.setTextSize(11);
        btNoIcon.setTextColor(isDarkMode ? android.graphics.Color.WHITE : android.graphics.Color.BLACK);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        btnParams.setMargins(0, 0, 4, 0);
        btNoIcon.setLayoutParams(btnParams);
        btNoIcon.setOnClickListener(v -> {
            if (callback != null) callback.onIconSelected(0);
            dialog.dismiss();
        });
        toolbar.addView(btNoIcon);

        rootLayout.addView(toolbar);

        // Helper to refresh grid
        final Runnable refreshGrids = () -> {
            // Remove previous content below toolbar
            while (rootLayout.getChildCount() > 1) {
                rootLayout.removeViewAt(1);
            }

            int size = (int) UnitUtils.dpToPx(44);
            int margin = (int) UnitUtils.dpToPx(4);
            int padding = (int) UnitUtils.dpToPx(6);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(size, size);
            iconParams.setMargins(margin, margin, margin, margin);

            // ── Section 1: Custom Icons ──
            List<Integer> customIds = iconManager.getCustomIconIds();
            if (!customIds.isEmpty()) {
                TextView tvCustomHeader = new TextView(context);
                tvCustomHeader.setText("Custom Imported Icons (" + customIds.size() + ")");
                tvCustomHeader.setTextSize(13);
                tvCustomHeader.setPadding(4, 8, 4, 4);
                tvCustomHeader.setTextColor(context.getResources().getColor(R.color.colorAccent));
                rootLayout.addView(tvCustomHeader);

                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                int count = 0;

                for (final int id : customIds) {
                    ImageView iv = new ImageView(context);
                    iv.setLayoutParams(iconParams);
                    iv.setPadding(padding, padding, padding, padding);
                    iv.setBackgroundResource(R.drawable.icon_background);
                    iv.setSelected(currentIconId == id);

                    Bitmap bmp = iconManager.getIcon(id);
                    if (bmp != null) iv.setImageBitmap(bmp);
                    iv.setColorFilter(null);

                    iv.setOnClickListener(v -> {
                        if (callback != null) callback.onIconSelected(id);
                        dialog.dismiss();
                    });

                    iv.setOnLongClickListener(v -> {
                        ContentDialog.confirm(context, "Delete custom icon?", () -> {
                            iconManager.deleteCustomIcon(id);
                            AppUtils.showToast(context, "Icon deleted");
                            dialog.dismiss();
                            show(context, 0, callback);
                        });
                        return true;
                    });

                    row.addView(iv);
                    count++;
                    if (count % 5 == 0) {
                        rootLayout.addView(row);
                        row = new LinearLayout(context);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                    }
                }
                if (row.getChildCount() > 0) {
                    rootLayout.addView(row);
                }
            }

            // ── Section 2: Built-in Icons ──
            TextView tvBuiltinHeader = new TextView(context);
            tvBuiltinHeader.setText("Built-in Icons");
            tvBuiltinHeader.setTextSize(13);
            tvBuiltinHeader.setPadding(4, 12, 4, 4);
            tvBuiltinHeader.setTextColor(context.getResources().getColor(R.color.colorAccent));
            rootLayout.addView(tvBuiltinHeader);

            LinearLayout rowBuiltin = new LinearLayout(context);
            rowBuiltin.setOrientation(LinearLayout.HORIZONTAL);
            int countBuiltin = 0;

            for (final int id : iconManager.getBuiltInIconIds()) {
                ImageView iv = new ImageView(context);
                iv.setLayoutParams(iconParams);
                iv.setPadding(padding, padding, padding, padding);
                iv.setBackgroundResource(R.drawable.icon_background);
                iv.setSelected(currentIconId == id);

                Bitmap bmp = iconManager.getIcon(id);
                if (bmp != null) iv.setImageBitmap(bmp);
                iv.setColorFilter(context.getResources().getColor(R.color.colorAccent));

                iv.setOnClickListener(v -> {
                    if (callback != null) callback.onIconSelected(id);
                    dialog.dismiss();
                });

                rowBuiltin.addView(iv);
                countBuiltin++;
                if (countBuiltin % 5 == 0) {
                    rootLayout.addView(rowBuiltin);
                    rowBuiltin = new LinearLayout(context);
                    rowBuiltin.setOrientation(LinearLayout.HORIZONTAL);
                }
            }
            if (rowBuiltin.getChildCount() > 0) {
                rootLayout.addView(rowBuiltin);
            }
        };

        refreshGrids.run();
        dialog.show();
    }
}