package com.winlator.cmod.contentdialog;

import android.view.View;
import android.widget.CheckBox;
import android.widget.TextView;

import com.winlator.cmod.R;

import java.util.HashMap;
import java.util.Map;

// The leegao BCn layer's own options (see bcn_layer_env.txt): BCN_NATIVE (transcode to ETC2/EAC/ASTC) is always on --
// off is debug-only and breaks BC textures on stock Mali -- and BCN_NATIVE_DIRECT (the fast write path) is always on
// too, with no user-facing reason to prefer the slower staging-buffer path. That leaves two real choices: whether the
// layer runs at all, and whether to keep a second, raw copy of every BC texture (BCN_KEEP_RAW) for the few games that
// read their own compressed textures back.
public class BCNConfigDialog extends ContentDialog {
    private final CheckBox cbBCnEnabled;
    private final CheckBox cbBCnKeepRaw;
    private String isBCnEnabled;
    private String isBCnKeepRaw;

    public BCNConfigDialog(View anchor) {
        super(anchor.getContext(), R.layout.bcn_config_dialog);
        setIcon(R.drawable.ic_driver_info);
        setTitle(R.string.bcn_layer_configuration);

        cbBCnEnabled = findViewById(R.id.CBBCnEnabled);
        cbBCnKeepRaw = findViewById(R.id.CBBCnKeepRaw);

        String graphicsDriverConfig = anchor.getTag().toString();
        HashMap<String, String> config = GraphicsDriverConfigDialog.parseGraphicsDriverConfig(graphicsDriverConfig);

        isBCnEnabled = config.getOrDefault("bcnEnabled", "1");
        isBCnKeepRaw = config.getOrDefault("bcnKeepRaw", "0");

        cbBCnEnabled.setChecked("1".equals(isBCnEnabled));
        cbBCnKeepRaw.setChecked("1".equals(isBCnKeepRaw));
        cbBCnKeepRaw.setEnabled(cbBCnEnabled.isChecked());
        cbBCnKeepRaw.setAlpha(cbBCnEnabled.isChecked() ? 1.0f : 0.5f);

        cbBCnEnabled.setOnClickListener(v -> {
            isBCnEnabled = cbBCnEnabled.isChecked() ? "1" : "0";
            cbBCnKeepRaw.setEnabled(cbBCnEnabled.isChecked());
            cbBCnKeepRaw.setAlpha(cbBCnEnabled.isChecked() ? 1.0f : 0.5f);
        });

        cbBCnKeepRaw.setOnClickListener(v -> isBCnKeepRaw = cbBCnKeepRaw.isChecked() ? "1" : "0");

        findViewById(R.id.IVTranscodeInfo).setOnClickListener(v -> showInfo());

        setOnConfirmCallback(() -> {
            config.put("bcnEnabled", isBCnEnabled);
            config.put("bcnKeepRaw", isBCnKeepRaw);
            anchor.setTag(GraphicsDriverConfigDialog.toGraphicsDriverConfig(config));
        });
    }

    private void showInfo() {
        ContentDialog dialog = new ContentDialog(getContext(), R.layout.bcn_info_dialog);
        dialog.setTitle(R.string.bcn_layer_configuration);
        dialog.setIcon(R.drawable.ic_driver_info);

        TextView tvMessage = dialog.findViewById(R.id.TVInfoMessage);
        String message = "<b>BCn Layer</b><br/><br/>" +
                "A custom <b>leegao BCn layer</b> that lets games using BC1-BC7 compressed textures (most DirectX " +
                "games) run on Mali, which has no native support for them: it transcodes them to a format Mali does " +
                "support (ETC2, EAC or ASTC, whichever fits the texture) as they're created.<br/><br/>" +
                "<b>BCn Emulation Enabled:</b><br/>" +
                "Off disables the layer entirely. BC textures then go to the driver untouched and will not work on " +
                "stock Mali -- leave this on unless you're specifically testing that.<br/><br/>" +
                "<b>Keep raw texture data:</b><br/>" +
                "A few games read their own compressed textures back (screenshot tools, some post-processing). " +
                "Off, the layer only keeps the original BC data for textures that may be read back; on, it keeps " +
                "it for every texture so readbacks always return exact data, at roughly 2x the BCn texture memory. " +
                "Leave this off unless a game specifically needs it.";
        tvMessage.setText(android.text.Html.fromHtml(message, android.text.Html.FROM_HTML_MODE_LEGACY));
        dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
        dialog.show();
    }
}
