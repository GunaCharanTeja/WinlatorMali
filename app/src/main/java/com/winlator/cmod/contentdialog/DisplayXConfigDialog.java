package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.Spinner;

import com.winlator.cmod.R;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.KeyValueSet;

public class DisplayXConfigDialog extends ContentDialog {
    public static String DEFAULT_CONFIG = "trueDisplayX=0" + ",performanceMode=1" + ",surfaceFormat=rgba8" + ",presentRR=1";
    private Context context;

    public DisplayXConfigDialog(View anchor) {
        super(anchor.getContext(), R.layout.displayx_config_dialog);
        context = anchor.getContext();
        setIcon(R.drawable.icon_settings);
        setTitle("DisplayX " + context.getString(R.string.configuration));

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        boolean isDarkMode = preferences.getBoolean("dark_mode", true);

        final CheckBox cbEnableTrueDisplayX = findViewById(R.id.CBEnableTrueDisplayX);
        final CheckBox cbEnablePerfMode = findViewById(R.id.CBEnablePerfMode);
        final CheckBox cbSyncRR = findViewById(R.id.CBPresentRR);
        final Spinner sSurfaceFormat = findViewById(R.id.SSurfaceFormat);

        sSurfaceFormat.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
        String[] surfaceFormats = context.getResources().getStringArray(R.array.surface_format_entries);
        sSurfaceFormat.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, surfaceFormats));

        KeyValueSet config = parseConfig(anchor.getTag());

        cbEnableTrueDisplayX.setChecked("1".equals(config.get("trueDisplayX")));
        cbEnablePerfMode.setChecked("1".equals(config.get("performanceMode")));
        cbSyncRR.setChecked("1".equals(config.get("presentRR")));
        AppUtils.setSpinnerSelectionFromIdentifier(sSurfaceFormat, config.get("surfaceFormat"));

        setOnConfirmCallback(() -> {
            config.put("trueDisplayX", cbEnableTrueDisplayX.isChecked() ? "1" : "0");
            config.put("performanceMode", cbEnablePerfMode.isChecked() ? "1" : "0");
            config.put("presentRR", cbSyncRR.isChecked() ? "1" : "0");
            config.put("surfaceFormat", sSurfaceFormat.getSelectedItem().toString());
            anchor.setTag(config.toString());
        });
    }

    public static KeyValueSet parseConfig(Object config) {
        String data = config != null && !config.toString().isEmpty() ? config.toString() : DEFAULT_CONFIG;
        return new KeyValueSet(data);
    }
}
