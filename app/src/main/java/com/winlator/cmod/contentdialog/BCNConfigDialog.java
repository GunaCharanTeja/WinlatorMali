package com.winlator.cmod.contentdialog;

import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.TextView;

import com.winlator.cmod.R;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

// Two builds of the leegao BCn layer, both installing as the same Vulkan layer, so one is chosen per container or
// shortcut and extracted on launch:
//  - Current: BC textures go to whichever native format fits (ETC2/EAC/ASTC). Options: keep a raw copy of every
//    texture (BCN_KEEP_RAW) and the direct upload path (BCN_NATIVE_DIRECT; off = slower staging-buffer fallback).
//  - Legacy (September build): every BC texture is transcoded to one chosen format (ASTC / ETC2 / ETC1) at a
//    quality preset, for games that render wrong with the current build.
public class BCNConfigDialog extends ContentDialog {
    private static final List<String> EMULATION_KEYS = Arrays.asList("auto", "full", "partial", "none");
    private static final String[] EMULATION_LABELS = {"Auto", "Full", "Partial", "None"};
    private static final List<String> CACHE_KEYS = Arrays.asList("1", "0");
    private static final String[] CACHE_LABELS = {"Enabled", "Disabled"};
    private static final List<String> VERSION_KEYS = Arrays.asList("current", "legacy");
    private static final String[] VERSION_LABELS = {"Current (native, faster)", "Legacy (ASTC / ETC2)"};
    private static final List<String> TRANSCODE_KEYS = Arrays.asList("astc", "etc2", "etc1");
    private static final String[] TRANSCODE_LABELS = {"ASTC", "ETC2", "ETC1"};
    private static final List<String> QUALITY_KEYS = Arrays.asList("auto", "fast", "balanced", "high");
    private static final String[] QUALITY_LABELS = {"Auto", "Fast", "Balanced", "High"};

    public BCNConfigDialog(View anchor) {
        super(anchor.getContext(), R.layout.bcn_config_dialog);
        setIcon(R.drawable.ic_driver_info);
        setTitle(R.string.bcn_layer_configuration);

        CheckBox cbEnabled = findViewById(R.id.CBBCnEnabled);
        CheckBox cbKeepRaw = findViewById(R.id.CBBCnKeepRaw);
        CheckBox cbNativeDirect = findViewById(R.id.CBBCnNativeDirect);
        Spinner sEmulation = findViewById(R.id.SGraphicsDriverBCnEmulation);
        Spinner sCache = findViewById(R.id.SGraphicsDriverBCnEmulationCache);
        Spinner sVersion = findViewById(R.id.SBCnVersion);
        Spinner sTranscode = findViewById(R.id.SBCnTranscode);
        Spinner sQuality = findViewById(R.id.SBCnQuality);
        View options = findViewById(R.id.LLBCnOptions);
        View currentOptions = findViewById(R.id.LLBCnCurrent);
        View legacyOptions = findViewById(R.id.LLBCnLegacy);

        HashMap<String, String> config = GraphicsDriverConfigDialog.parseGraphicsDriverConfig(anchor.getTag().toString());

        cbEnabled.setChecked(!"0".equals(config.getOrDefault("bcnEnabled", "1")));
        cbKeepRaw.setChecked("1".equals(config.getOrDefault("bcnKeepRaw", "0")));
        cbNativeDirect.setChecked(!"0".equals(config.getOrDefault("bcnNativeDirect", "1")));
        setUpSpinner(sEmulation, EMULATION_LABELS, EMULATION_KEYS, config.getOrDefault("bcnEmulation", "auto"));
        setUpSpinner(sCache, CACHE_LABELS, CACHE_KEYS, config.getOrDefault("bcnEmulationCache", "1"));
        setUpSpinner(sVersion, VERSION_LABELS, VERSION_KEYS, config.getOrDefault("bcnVersion", "current"));
        setUpSpinner(sTranscode, TRANSCODE_LABELS, TRANSCODE_KEYS, config.getOrDefault("bcnTranscode", "astc"));
        setUpSpinner(sQuality, QUALITY_LABELS, QUALITY_KEYS, config.getOrDefault("bcnQuality", "auto"));

        Runnable refresh = () -> {
            options.setVisibility(cbEnabled.isChecked() ? View.VISIBLE : View.GONE);
            boolean legacy = sVersion.getSelectedItemPosition() == 1;
            currentOptions.setVisibility(legacy ? View.GONE : View.VISIBLE);
            legacyOptions.setVisibility(legacy ? View.VISIBLE : View.GONE);
        };
        refresh.run();
        cbEnabled.setOnClickListener(v -> refresh.run());
        sVersion.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { refresh.run(); }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        findViewById(R.id.IVTranscodeInfo).setOnClickListener(v -> showInfo());

        setOnConfirmCallback(() -> {
            config.put("bcnEnabled", cbEnabled.isChecked() ? "1" : "0");
            config.put("bcnEmulation", EMULATION_KEYS.get(sEmulation.getSelectedItemPosition()));
            config.put("bcnEmulationCache", CACHE_KEYS.get(sCache.getSelectedItemPosition()));
            config.put("bcnKeepRaw", cbKeepRaw.isChecked() ? "1" : "0");
            config.put("bcnNativeDirect", cbNativeDirect.isChecked() ? "1" : "0");
            config.put("bcnVersion", VERSION_KEYS.get(sVersion.getSelectedItemPosition()));
            config.put("bcnTranscode", TRANSCODE_KEYS.get(sTranscode.getSelectedItemPosition()));
            config.put("bcnQuality", QUALITY_KEYS.get(sQuality.getSelectedItemPosition()));
            anchor.setTag(GraphicsDriverConfigDialog.toGraphicsDriverConfig(config));
        });
    }

    private void setUpSpinner(Spinner spinner, String[] labels, List<String> keys, String selected) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, keys.indexOf(selected)));
    }

    private void showInfo() {
        ContentDialog dialog = new ContentDialog(getContext(), R.layout.bcn_info_dialog);
        dialog.setTitle(R.string.bcn_layer_configuration);
        dialog.setIcon(R.drawable.ic_driver_info);

        TextView tvMessage = dialog.findViewById(R.id.TVInfoMessage);
        String message = "<b>BCn Layer</b><br/><br/>" +
                "The <b>leegao BCn layer</b> lets games using BC1-BC7 compressed textures (most DirectX games) run " +
                "on Mali, which has no native support for them, by converting the textures as they're created.<br/><br/>" +
                "<b>BCn Emulation Enabled:</b> off disables the layer; BC textures then won't work on stock Mali.<br/><br/>" +
                "<b>Layer version:</b><br/>" +
                "&#8226; <b>Current</b>: converts each texture to whichever native format fits it (ETC2, EAC or " +
                "ASTC). Faster, and uses the same memory as native BCn. Use this by default.<br/>" +
                "&#8226; <b>Legacy</b>: the September build. Converts every texture to the one format you pick, at a " +
                "quality preset. Try it for a game whose textures look wrong with Current.<br/><br/>" +
                "<b>Direct texture upload</b> (Current): the fast upload path. Turn it off only if textures come " +
                "out corrupted or the game crashes on load; off uses a slower staging-buffer path.<br/><br/>" +
                "<b>Keep raw texture data</b> (Current): keeps the original BC data of every texture so games that " +
                "read their textures back get exact data, at roughly 2x the BCn texture memory. Off keeps it only " +
                "for textures that may be read back.<br/><br/>" +
                "<b>Transcode to</b> (Legacy): ASTC gives the best quality; ETC2 is faster to convert; ETC1 is the " +
                "oldest and lowest quality.<br/><br/>" +
                "<b>Quality</b> (Legacy): Auto picks for your GPU; Fast converts quickest, High looks best.<br/><br/>" +
                "Changes apply the next time the game or container starts.";
        tvMessage.setText(android.text.Html.fromHtml(message, android.text.Html.FROM_HTML_MODE_LEGACY));
        dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
        dialog.show();
    }
}
