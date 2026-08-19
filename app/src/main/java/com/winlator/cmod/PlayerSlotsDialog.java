package com.winlator.cmod;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import com.winlator.cmod.inputcontrols.ExternalController;
import com.winlator.cmod.winhandler.WinHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Phase 2: 4-Player Multi-Controller Slots dialog.
 * Lets the user:
 *  1. See all connected physical gamepads
 *  2. Manually pin each controller to a specific XInput player slot (1–4)
 *  3. Set per-slot stick deadzone (left & right independently)
 *  4. Toggle vibration per slot
 */
public class PlayerSlotsDialog {

    private static final String TAG = "PlayerSlotsDialog";

    /** Player LED colors: P1=blue, P2=red, P3=green, P4=yellow */
    private static final int[] PLAYER_COLORS = {
        0xFF2196F3, // Player 1 – Blue
        0xFFF44336, // Player 2 – Red
        0xFF4CAF50, // Player 3 – Green
        0xFFFFEB3B  // Player 4 – Yellow
    };

    public static void show(Context context, WinHandler winHandler) {
        if (winHandler == null) return;

        // Build the list of connected controllers
        Map<Integer, ExternalController> controllerMap = winHandler.getControllers();
        List<ExternalController> controllers = new ArrayList<>(controllerMap.values());
        int maxSlots = winHandler.getMaxControllers();

        // Build a root view containing rows for each connected controller
        // plus a per-slot section for deadzone / vibration
        View rootView = LayoutInflater.from(context).inflate(R.layout.player_slots_dialog, null);

        // Populate slot rows
        ViewGroup slotsContainer = rootView.findViewById(R.id.SlotsContainer);

        // ---- Connected-controllers header ----
        setupConnectedControllers(context, slotsContainer, controllers, winHandler, maxSlots);

        // ---- Per-slot deadzone / vibration ----
        setupSlotSettings(context, rootView, winHandler, maxSlots);

        new AlertDialog.Builder(context)
            .setTitle("Player Slots & Settings")
            .setView(rootView)
            .setPositiveButton("Done", (d, w) -> {
                // Changes are applied live via WinHandler setters
                Log.d(TAG, "PlayerSlotsDialog closed");
            })
            .show();
    }

    // -----------------------------------------------------------------------
    // Connected Controllers Section
    // -----------------------------------------------------------------------
    private static void setupConnectedControllers(
        Context context,
        ViewGroup container,
        List<ExternalController> controllers,
        WinHandler winHandler,
        int maxSlots
    ) {
        container.removeAllViews();

        if (controllers.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText("No physical controllers connected.\nConnect a gamepad via USB or Bluetooth.");
            empty.setPadding(16, 16, 16, 16);
            empty.setTextColor(Color.LTGRAY);
            container.addView(empty);
            return;
        }

        String[] slotLabels = new String[maxSlots + 1];
        slotLabels[0] = "Auto (FCFS)";
        for (int i = 1; i <= maxSlots; i++) {
            slotLabels[i] = "Player " + i;
        }

        for (ExternalController controller : controllers) {
            int deviceId = controller.getDeviceId();
            View row = LayoutInflater.from(context).inflate(R.layout.player_slot_row, container, false);

            // LED color dot
            View ledDot = row.findViewById(R.id.LedDot);
            int currentSlot = winHandler.getSlotForDevice(deviceId);
            int dotColor = (currentSlot >= 0 && currentSlot < PLAYER_COLORS.length)
                ? PLAYER_COLORS[currentSlot] : Color.DKGRAY;
            ledDot.setBackgroundColor(dotColor);

            // Controller name
            TextView tvName = row.findViewById(R.id.TVControllerName);
            String name = controller.getName();
            tvName.setText(name != null && !name.isEmpty() ? name : "Gamepad #" + deviceId);

            // Slot spinner
            Spinner slotSpinner = row.findViewById(R.id.SlotSpinner);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(context,
                android.R.layout.simple_spinner_item, slotLabels);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            slotSpinner.setAdapter(adapter);

            // Set current selection
            int manualSlot = winHandler.getManualSlotForDevice(deviceId);
            slotSpinner.setSelection(manualSlot < 0 ? 0 : manualSlot + 1);

            slotSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                    int chosen = position - 1; // -1 = auto
                    winHandler.pinDeviceToSlot(deviceId, chosen);
                    // Update LED color
                    int color = (chosen >= 0 && chosen < PLAYER_COLORS.length)
                        ? PLAYER_COLORS[chosen] : Color.DKGRAY;
                    ledDot.setBackgroundColor(color);
                }
                @Override
                public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            });

            container.addView(row);
        }
    }

    // -----------------------------------------------------------------------
    // Per-Slot Settings Section (deadzone + vibration)
    // -----------------------------------------------------------------------
    private static void setupSlotSettings(
        Context context,
        View rootView,
        WinHandler winHandler,
        int maxSlots
    ) {
        ViewGroup settingsContainer = rootView.findViewById(R.id.SlotSettingsContainer);
        if (settingsContainer == null) return;
        settingsContainer.removeAllViews();

        for (int slot = 0; slot < maxSlots; slot++) {
            final int s = slot;
            View row = LayoutInflater.from(context).inflate(R.layout.player_slot_settings_row, settingsContainer, false);

            // Color badge
            View badge = row.findViewById(R.id.SlotBadge);
            badge.setBackgroundColor(PLAYER_COLORS[slot]);

            // Label "Player X"
            TextView label = row.findViewById(R.id.TVSlotLabel);
            label.setText("Player " + (slot + 1));

            // Vibration toggle
            CheckBox cbVibration = row.findViewById(R.id.CBVibration);
            cbVibration.setChecked(winHandler.isVibrationEnabledForSlot(slot));
            cbVibration.setOnCheckedChangeListener((btn, checked) ->
                winHandler.setVibrationEnabledForSlot(s, checked));

            // Left stick deadzone
            SeekBar sbLeftDZ = row.findViewById(R.id.SBLeftDeadzone);
            TextView tvLeftDZ = row.findViewById(R.id.TVLeftDeadzone);
            int leftDZ = (int)(winHandler.getLeftDeadzoneForSlot(slot) * 100);
            sbLeftDZ.setProgress(leftDZ);
            tvLeftDZ.setText("L-Stick: " + leftDZ + "%");
            sbLeftDZ.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                    tvLeftDZ.setText("L-Stick: " + p + "%");
                    if (user) winHandler.setLeftDeadzoneForSlot(s, p / 100f);
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {}
            });

            // Right stick deadzone
            SeekBar sbRightDZ = row.findViewById(R.id.SBRightDeadzone);
            TextView tvRightDZ = row.findViewById(R.id.TVRightDeadzone);
            int rightDZ = (int)(winHandler.getRightDeadzoneForSlot(slot) * 100);
            sbRightDZ.setProgress(rightDZ);
            tvRightDZ.setText("R-Stick: " + rightDZ + "%");
            sbRightDZ.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                    tvRightDZ.setText("R-Stick: " + p + "%");
                    if (user) winHandler.setRightDeadzoneForSlot(s, p / 100f);
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {}
            });

            settingsContainer.addView(row);
        }
    }
}