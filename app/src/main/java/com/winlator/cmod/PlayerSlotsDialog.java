package com.winlator.cmod;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.inputcontrols.ExternalController;
import com.winlator.cmod.winhandler.WinHandler;

import java.util.ArrayList;

/**
 * Phase 2: 4-Player Multi-Controller Slots dialog (Styled with ContentDialog).
 */
public class PlayerSlotsDialog {
    static final int[] PLAYER_COLORS = { 0xFF2196F3, 0xFFF44336, 0xFF4CAF50, 0xFFFFEB3B };

    public static void show(Context context, WinHandler winHandler) {
        if (winHandler == null) return;

        ContentDialog dialog = new ContentDialog(context, R.layout.player_slots_dialog);
        dialog.setTitle("Player Slots (XInput 1–4)");
        dialog.setIcon(R.drawable.icon_controller);

        // --- Section 1: Connected Controllers ---
        LinearLayout controllersSection = dialog.findViewById(R.id.ControllersSection);
        ArrayList<ExternalController> controllers = ExternalController.getControllers();

        if (controllers.isEmpty()) {
            TextView empty = dialog.findViewById(R.id.TVNoControllers);
            if (empty != null) empty.setVisibility(View.VISIBLE);
        } else {
            TextView empty = dialog.findViewById(R.id.TVNoControllers);
            if (empty != null) empty.setVisibility(View.GONE);

            int maxSlots = winHandler.getMaxControllers();
            String[] slotLabels = new String[maxSlots + 1];
            slotLabels[0] = "Auto";
            for (int i = 1; i <= maxSlots; i++) slotLabels[i] = "Player " + i;

            for (ExternalController controller : controllers) {
                int deviceId = controller.getDeviceId();
                View row = LayoutInflater.from(context)
                    .inflate(R.layout.player_slot_row, controllersSection, false);

                // LED badge color
                View badge = row.findViewById(R.id.LedDot);
                int currentSlot = winHandler.getSlotForDevice(deviceId);
                badge.setBackgroundColor(currentSlot >= 0 && currentSlot < PLAYER_COLORS.length
                    ? PLAYER_COLORS[currentSlot] : 0xFF666666);

                // Controller name
                TextView tvName = row.findViewById(R.id.TVControllerName);
                String name = controller.getName();
                tvName.setText((name != null && !name.isEmpty()) ? name : "Gamepad");

                // Slot spinner
                Spinner spinner = row.findViewById(R.id.SlotSpinner);
                ArrayAdapter<String> adapter = new ArrayAdapter<>(context,
                    android.R.layout.simple_spinner_item, slotLabels);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                spinner.setAdapter(adapter);
                int manual = winHandler.getManualSlotForDevice(deviceId);
                spinner.setSelection(manual < 0 ? 0 : manual + 1, false);

                spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                        int chosen = pos - 1; // -1 = auto
                        winHandler.pinDeviceToSlot(deviceId, chosen);
                        badge.setBackgroundColor(chosen >= 0 && chosen < PLAYER_COLORS.length
                            ? PLAYER_COLORS[chosen] : 0xFF666666);
                    }
                    @Override public void onNothingSelected(AdapterView<?> p) {}
                });

                controllersSection.addView(row);
            }
        }

        // --- Section 2: Per-Slot Settings ---
        LinearLayout slotsSection = dialog.findViewById(R.id.SlotSettingsSection);
        int maxSlots = winHandler.getMaxControllers();
        String[] slotNames = {"Player 1", "Player 2", "Player 3", "Player 4"};

        for (int slot = 0; slot < maxSlots; slot++) {
            final int s = slot;
            View row = LayoutInflater.from(context)
                .inflate(R.layout.player_slot_settings_row, slotsSection, false);

            // Color badge
            row.findViewById(R.id.SlotBadge).setBackgroundColor(PLAYER_COLORS[slot]);
            ((TextView) row.findViewById(R.id.TVSlotLabel)).setText(slotNames[slot]);

            // Vibration
            CheckBox cbVib = row.findViewById(R.id.CBVibration);
            cbVib.setChecked(winHandler.isVibrationEnabledForSlot(slot));
            cbVib.setOnCheckedChangeListener((btn, checked) ->
                winHandler.setVibrationEnabledForSlot(s, checked));

            // Left deadzone
            int leftPct  = Math.round(winHandler.getLeftDeadzoneForSlot(slot)  * 100);
            int rightPct = Math.round(winHandler.getRightDeadzoneForSlot(slot) * 100);

            TextView tvL = row.findViewById(R.id.TVLeftDeadzone);
            SeekBar sbL  = row.findViewById(R.id.SBLeftDeadzone);
            tvL.setText(leftPct + "%");
            sbL.setProgress(leftPct);
            sbL.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                    tvL.setText(p + "%");
                    if (user) winHandler.setLeftDeadzoneForSlot(s, p / 100f);
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {}
            });

            // Right deadzone
            TextView tvR = row.findViewById(R.id.TVRightDeadzone);
            SeekBar sbR  = row.findViewById(R.id.SBRightDeadzone);
            tvR.setText(rightPct + "%");
            sbR.setProgress(rightPct);
            sbR.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                    tvR.setText(p + "%");
                    if (user) winHandler.setRightDeadzoneForSlot(s, p / 100f);
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {}
            });

            slotsSection.addView(row);
        }

        dialog.show();
    }
}