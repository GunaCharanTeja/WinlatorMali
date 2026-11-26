package com.winlator.cmod.fexcore;

import com.winlator.cmod.R;

import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.KeyValueSet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public abstract class FEXCoreManager {
    public static void loadFEXCoreVersion(Context context, ContentsManager contentsManager, Spinner spinner, String fexcoreVersion) {
        String[] originalItems = context.getResources().getStringArray(R.array.fexcore_version_entries);
        List<String> itemList = new ArrayList<>(Arrays.asList(originalItems));
        for (ContentProfile profile : contentsManager.getInstalledProfiles(ContentProfile.ContentType.CONTENT_TYPE_FEXCORE)) {
            String ver = profile.verName != null ? profile.verName : "";
            if (ver.startsWith("fexcore-")) ver = ver.substring("fexcore-".length());
            else if (ver.startsWith("fex-")) ver = ver.substring("fex-".length());
            if (!itemList.contains(ver)) itemList.add(ver);
        }
        spinner.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, itemList));
        AppUtils.setSpinnerSelectionFromValue(spinner, fexcoreVersion);
    }
}
