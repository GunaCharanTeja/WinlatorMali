package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.InputDevice;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import com.winlator.cmod.R;
import com.winlator.cmod.inputcontrols.DirectGamepHidRumbleEngine;
import com.winlator.cmod.inputcontrols.DirectGamepHidRumbleEngine.UsbDeviceInfo;
import com.winlator.cmod.inputcontrols.ExternalController;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Interactive Controller Vibration Test Dialog.
 * Allows instant hardware motor testing on external USB/Bluetooth gamepads (Redgear Elite, Xbox, PS, etc.)
 * and internal phone vibrator with live diagnostic feedback!
 */
public class VibrationTestDialog {
    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("HH:mm:ss", Locale.US);

    public static void show(Context context) {
        show(context, null);
    }

    // SOURCE_GAMEPAD shares the generic "has buttons" bit with keyboards, so the old "any bit set" check
    // listed the phone's own power keys, fingerprint sensor and headset jack as gamepads.
    private static boolean isGameController(InputDevice dev) {
        if (dev == null || dev.isVirtual()) return false;
        int sources = dev.getSources();
        return (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
               (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    public static void show(Context context, ExternalController targetController) {
        if (context == null) return;

        final ContentDialog dialog = new ContentDialog(context, R.layout.vibration_test_dialog);
        dialog.setTitle(R.string.controller_vibration_test);
        dialog.setIcon(R.drawable.icon_vibration);

        final DirectGamepHidRumbleEngine usbRumble = DirectGamepHidRumbleEngine.getInstance(context);
        usbRumble.acquire();
        final Vibrator phoneVibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        final Handler handler = new Handler(Looper.getMainLooper());

        final TextView tvDeviceStatus = dialog.findViewById(R.id.TVDeviceStatus);
        final Button btGrantUsb = dialog.findViewById(R.id.BTGrantUsbPermission);
        final Button btRescan = dialog.findViewById(R.id.BTRescanDevices);
        final Spinner sMotorMode = dialog.findViewById(R.id.SMotorMode);
        final SeekBar sbIntensity = dialog.findViewById(R.id.SBIntensity);
        final TextView tvIntensityPercent = dialog.findViewById(R.id.TVIntensityPercent);
        final Spinner sDuration = dialog.findViewById(R.id.SDuration);
        final CheckBox cbUsbDirect = dialog.findViewById(R.id.CBUsbDirect);
        final CheckBox cbAndroidInputDevice = dialog.findViewById(R.id.CBAndroidInputDevice);
        final CheckBox cbPhoneFallback = dialog.findViewById(R.id.CBPhoneFallback);
        final Button btStartTest = dialog.findViewById(R.id.BTStartTest);
        final Button btStopTest = dialog.findViewById(R.id.BTStopTest);
        final TextView tvLog = dialog.findViewById(R.id.TVLog);
        final ScrollView svLog = dialog.findViewById(R.id.SVLog);

        final StringBuilder logBuffer = new StringBuilder();
        final Runnable[] activeTestTask = new Runnable[1];

        final java.util.function.Consumer<String> appendLog = (msg) -> {
            String timestamp = TIME_FMT.format(new Date());
            logBuffer.append("[").append(timestamp).append("] ").append(msg).append("\n");
            tvLog.setText(logBuffer.toString());
            svLog.post(() -> svLog.fullScroll(View.FOCUS_DOWN));
        };

        // --- Setup Spinners ---
        String[] motorModes = {
            "Both motors",
            "Strong motor (left)",
            "Light motor (right)",
            "Pulse: left, right, both"
        };
        ArrayAdapter<String> motorAdapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, motorModes);
        motorAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sMotorMode.setAdapter(motorAdapter);

        String[] durationOptions = {
            "0.5 seconds",
            "1 second",
            "2 seconds",
            "3 seconds",
            "Until I press Stop"
        };
        ArrayAdapter<String> durAdapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, durationOptions);
        durAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sDuration.setAdapter(durAdapter);
        sDuration.setSelection(1); // Default: 1.0s

        // --- Intensity Listener ---
        sbIntensity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int val = Math.max(10, progress);
                tvIntensityPercent.setText(val + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // --- Refresh Devices Logic ---
        final UsbDeviceInfo[] pendingPermissionDevice = new UsbDeviceInfo[1];
        Runnable refreshDevices = () -> {
            usbRumble.scanAndConnectGamepads();
            List<UsbDeviceInfo> usbList = usbRumble.getDetectedDeviceInfos();
            int[] inputDeviceIds = InputDevice.getDeviceIds();

            StringBuilder sb = new StringBuilder();
            pendingPermissionDevice[0] = null;

            for (UsbDeviceInfo info : usbList) {
                sb.append("🎮 ").append(info.name).append("\n    USB • ");
                if (info.hasPermission) {
                    sb.append("ready\n");
                } else {
                    sb.append("needs permission\n");
                    pendingPermissionDevice[0] = info;
                }
            }

            for (int id : inputDeviceIds) {
                InputDevice dev = InputDevice.getDevice(id);
                if (!isGameController(dev)) continue;
                // A USB pad also shows up here as a system device; it's already listed above.
                boolean alreadyListed = false;
                for (UsbDeviceInfo info : usbList) {
                    if (info.vid == dev.getVendorId() && info.pid == dev.getProductId()) alreadyListed = true;
                }
                if (alreadyListed) continue;
                Vibrator v = dev.getVibrator();
                boolean hasVib = v != null && v.hasVibrator();
                sb.append("🎮 ").append(dev.getName()).append("\n    ")
                  .append(hasVib ? "can vibrate" : "no vibration support reported").append("\n");
            }

            if (sb.length() == 0) {
                sb.append("No controller found.\nPlug one in over USB (OTG) or pair it over Bluetooth, then tap Rescan.");
            }

            tvDeviceStatus.setText(sb.toString().trim());

            if (pendingPermissionDevice[0] != null) {
                btGrantUsb.setVisibility(View.VISIBLE);
                btGrantUsb.setText("Grant Permission for " + pendingPermissionDevice[0].name);
            } else {
                btGrantUsb.setVisibility(View.GONE);
            }
        };

        refreshDevices.run();
        appendLog.accept("Vibration Test initialized.");

        // --- Permission Request Button ---
        btGrantUsb.setOnClickListener((v) -> {
            if (pendingPermissionDevice[0] != null) {
                appendLog.accept("Requesting USB Host Permission for: " + pendingPermissionDevice[0].name);
                usbRumble.requestPermission(pendingPermissionDevice[0].device, () -> {
                    appendLog.accept("USB Host Permission GRANTED for: " + pendingPermissionDevice[0].name);
                    refreshDevices.run();
                });
            }
        });

        btRescan.setOnClickListener((v) -> {
            appendLog.accept("Re-scanning USB and connected input devices...");
            refreshDevices.run();
            appendLog.accept("Re-scan complete. " + usbRumble.getActiveSessionCount() + " active USB rumble session(s).");
        });

        // --- Stop Action ---
        Runnable stopAllVibrations = () -> {
            if (activeTestTask[0] != null) {
                handler.removeCallbacks(activeTestTask[0]);
                activeTestTask[0] = null;
            }

            // Stop USB Direct
            usbRumble.sendRumble(0, 0);

            // Stop Android InputDevices
            for (int id : InputDevice.getDeviceIds()) {
                InputDevice dev = InputDevice.getDevice(id);
                if (dev != null) {
                    Vibrator v = dev.getVibrator();
                    if (v != null) v.cancel();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        VibratorManager vm = dev.getVibratorManager();
                        if (vm != null) {
                            for (int vid : vm.getVibratorIds()) {
                                vm.getVibrator(vid).cancel();
                            }
                        }
                    }
                }
            }

            // Stop Phone Vibrator
            if (phoneVibrator != null) phoneVibrator.cancel();
        };

        btStopTest.setOnClickListener((v) -> {
            stopAllVibrations.run();
            appendLog.accept("STOP: All vibration motors cancelled.");
        });

        // --- Helper for safe InputDevice / phone vibrate ---
        java.util.function.BiConsumer<Vibrator, Integer> safeVibrateDevice = (v, duration) -> {
            if (v == null || !v.hasVibrator()) return;
            try {
                int pct = Math.max(10, sbIntensity.getProgress());
                int amp = Math.min(255, Math.max(1, (int) ((pct / 100.0f) * 255)));
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (v.hasAmplitudeControl()) {
                        v.vibrate(VibrationEffect.createOneShot(duration, amp));
                    } else {
                        v.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE));
                    }
                } else {
                    v.vibrate(duration);
                }
            } catch (Exception e) {
                try { v.vibrate(duration); } catch (Exception ignored) {}
            }
        };

        // --- Start Test Action ---
        btStartTest.setOnClickListener((v) -> {
            stopAllVibrations.run();

            int modePos = sMotorMode.getSelectedItemPosition();
            int durPos = sDuration.getSelectedItemPosition();
            int intensityPct = Math.max(10, sbIntensity.getProgress());
            int maxVal = (int) ((intensityPct / 100.0f) * 65535);

            int strong = 0;
            int weak = 0;

            switch (modePos) {
                case 0: // Both Motors
                    strong = maxVal;
                    weak = maxVal;
                    break;
                case 1: // Heavy Left
                    strong = maxVal;
                    weak = 0;
                    break;
                case 2: // Light Right
                    strong = 0;
                    weak = maxVal;
                    break;
                case 3: // Pulse pattern
                    strong = maxVal;
                    weak = maxVal;
                    break;
            }

            int testDurationMs = 1000;
            boolean isContinuous = false;
            switch (durPos) {
                case 0: testDurationMs = 500; break;
                case 1: testDurationMs = 1000; break;
                case 2: testDurationMs = 2000; break;
                case 3: testDurationMs = 3000; break;
                case 4: isContinuous = true; testDurationMs = 30000; break;
            }

            final int finalStrong = strong;
            final int finalWeak = weak;
            final int finalDur = testDurationMs;

            if (modePos == 3) {
                // Pulse Pattern Mode
                appendLog.accept("Starting Pulse Pattern Test (" + intensityPct + "% intensity)...");

                Runnable pulseStep1 = () -> {
                    appendLog.accept("Pulse 1/3: Left Heavy Motor (300ms)");
                    if (cbUsbDirect.isChecked()) usbRumble.sendRumble(finalStrong, 0, 300);
                    if (cbPhoneFallback.isChecked()) safeVibrateDevice.accept(phoneVibrator, 300);
                };

                Runnable pulseStep2 = () -> {
                    appendLog.accept("Pulse 2/3: Right Light Motor (300ms)");
                    if (cbUsbDirect.isChecked()) usbRumble.sendRumble(0, finalWeak, 300);
                    if (cbPhoneFallback.isChecked()) safeVibrateDevice.accept(phoneVibrator, 300);
                };

                Runnable pulseStep3 = () -> {
                    appendLog.accept("Pulse 3/3: Dual Motors Full Rumble (500ms)");
                    if (cbUsbDirect.isChecked()) usbRumble.sendRumble(finalStrong, finalWeak, 500);
                    if (cbPhoneFallback.isChecked()) safeVibrateDevice.accept(phoneVibrator, 500);
                };

                Runnable pulseEnd = () -> {
                    stopAllVibrations.run();
                    appendLog.accept("Pulse Pattern Test Completed.");
                };

                pulseStep1.run();
                handler.postDelayed(pulseStep2, 400);
                handler.postDelayed(pulseStep3, 800);
                handler.postDelayed(pulseEnd, 1400);
                return;
            }

            // Standard Test
            appendLog.accept(String.format(Locale.US, "TEST: Motors: Strong=%d, Weak=%d, Intensity=%d%%, Duration=%s",
                finalStrong, finalWeak, intensityPct, isContinuous ? "Continuous" : (finalDur + "ms")));

            boolean usbOk = false;
            if (cbUsbDirect.isChecked()) {
                usbOk = usbRumble.sendRumble(finalStrong, finalWeak, isContinuous ? 0 : finalDur);
                appendLog.accept("USB Direct Engine: Dispatched (" + (usbOk ? "SUCCESS - Packet Sent to Controller" : "No active USB session") + ")");
            }

            if (cbAndroidInputDevice.isChecked()) {
                int dispatchedCount = 0;
                for (int id : InputDevice.getDeviceIds()) {
                    InputDevice dev = InputDevice.getDevice(id);
                    if (isGameController(dev)) {
                        Vibrator vib = dev.getVibrator();
                        if (vib != null && vib.hasVibrator()) {
                            safeVibrateDevice.accept(vib, finalDur);
                            dispatchedCount++;
                        }
                    }
                }
                appendLog.accept("Android Framework Vibrator: Dispatched to " + dispatchedCount + " gamepad vibrator(s).");
            }

            if (cbPhoneFallback.isChecked()) {
                safeVibrateDevice.accept(phoneVibrator, finalDur);
                appendLog.accept("Phone Internal Vibrator: Triggered (" + finalDur + "ms).");
            }

            if (!isContinuous) {
                activeTestTask[0] = () -> {
                    stopAllVibrations.run();
                    appendLog.accept("Test duration elapsed. Motors stopped.");
                };
                handler.postDelayed(activeTestTask[0], finalDur);
            }
        });

        // The dialog's generic Cancel/OK do nothing here; one Close button is clearer.
        View btCancel = dialog.findViewById(R.id.BTCancel);
        if (btCancel != null) btCancel.setVisibility(View.GONE);
        TextView btConfirm = dialog.findViewById(R.id.BTConfirm);
        if (btConfirm != null) btConfirm.setText("Close");

        dialog.setOnDismissListener((d) -> {
            stopAllVibrations.run();
            usbRumble.releaseClaim();
        });
        dialog.show();
    }
}
