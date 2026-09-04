package com.winlator.cmod.inputcontrols;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * Winlator Direct USB HID Hardware Force-Feedback Engine.
 * 
 * Bypasses Android's broken framework gamepad vibrator limits by establishing direct
 * USB HID Host connections to physical controllers (Redgear Elite, Xbox 360/One, PS4/PS5,
 * Switch, ShanWan, GameSir, 8BitDo) and sending raw hardware vibration OUT reports directly
 * to the physical controller motors!
 */
public class DirectGamepHidRumbleEngine {
    private static final String TAG = "DirectHidRumbleEngine";
    private static final String ACTION_USB_PERMISSION = "com.winlator.cmod.USB_RUMBLE_PERMISSION";

    private static DirectGamepHidRumbleEngine instance;
    private final Context context;
    private final UsbManager usbManager;

    private final Map<String, GamepadUsbSession> activeSessions = new HashMap<>();

    private static class GamepadUsbSession {
        UsbDevice device;
        UsbDeviceConnection connection;
        UsbInterface usbInterface;
        UsbEndpoint outEndpoint;
        ControllerProtocol protocol;
    }

    public enum ControllerProtocol {
        XINPUT_XBOX360,
        SONY_DS4,
        SONY_DUALSENSE,
        NINTENDO_SWITCH,
        GENERIC_HID
    }

    private DirectGamepHidRumbleEngine(Context context) {
        this.context = context.getApplicationContext();
        this.usbManager = (UsbManager) this.context.getSystemService(Context.USB_SERVICE);
        registerUsbReceiver();
        scanAndConnectGamepads();
    }

    public static synchronized DirectGamepHidRumbleEngine getInstance(Context context) {
        if (instance == null) {
            instance = new DirectGamepHidRumbleEngine(context);
        }
        return instance;
    }

    private void registerUsbReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(usbReceiver, filter);
        }
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) openGamepadConnection(device);
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null) requestPermissionOrOpen(device);
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null) closeSession(device.getDeviceName());
            }
        }
    };

    /**
     * Scans all connected USB devices and identifies gamepads.
     */
    public void scanAndConnectGamepads() {
        if (usbManager == null) return;
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        for (UsbDevice device : deviceList.values()) {
            if (isSupportedGamepad(device)) {
                requestPermissionOrOpen(device);
            }
        }
    }

    private boolean isSupportedGamepad(UsbDevice device) {
        int vid = device.getVendorId();
        int pid = device.getProductId();

        // 1. Redgear / ShanWan / Generic PC Gamepads (0x2563, 0x11ff, 0x20d6)
        if (vid == 0x2563 || vid == 0x11ff || vid == 0x20d6 || vid == 0x12ab || vid == 0x0e8f) return true;

        // 2. Microsoft Xbox 360 / Xbox One / Series X (0x045e)
        if (vid == 0x045e) return true;

        // 3. Sony PlayStation 4 / 5 (0x054c)
        if (vid == 0x054c) return true;

        // 4. Nintendo Switch (0x057e)
        if (vid == 0x057e) return true;

        // 5. 8BitDo / PowerA / PDP / Mad Catz / Hori / Razer / Logitech / Flydigi / GameSir
        if (vid == 0x2dc8 || vid == 0x24c6 || vid == 0x0e6f || vid == 0x1bad || vid == 0x0f0d ||
            vid == 0x1532 || vid == 0x046d || vid == 0x044f || vid == 0x2c22) return true;

        // Check HID Gamepad class interface
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_HID || iface.getInterfaceClass() == 0xFF) {
                if (iface.getInterfaceSubclass() == 0x5D || iface.getInterfaceProtocol() == 0x01 || iface.getInterfaceProtocol() == 0x05) {
                    return true;
                }
            }
        }

        return false;
    }

    private void requestPermissionOrOpen(UsbDevice device) {
        if (usbManager.hasPermission(device)) {
            openGamepadConnection(device);
        } else {
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
            PendingIntent pi = PendingIntent.getBroadcast(context, 0, new Intent(ACTION_USB_PERMISSION), flags);
            usbManager.requestPermission(device, pi);
        }
    }

    private void openGamepadConnection(UsbDevice device) {
        closeSession(device.getDeviceName());

        UsbDeviceConnection conn = usbManager.openDevice(device);
        if (conn == null) {
            Log.w(TAG, "Failed to open USB connection to: " + device.getProductName());
            return;
        }

        UsbInterface targetIface = null;
        UsbEndpoint outEp = null;

        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            for (int e = 0; e < iface.getEndpointCount(); e++) {
                UsbEndpoint ep = iface.getEndpoint(e);
                if (ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                    targetIface = iface;
                    outEp = ep;
                    break;
                }
            }
            if (targetIface != null) break;
        }

        if (targetIface != null) {
            conn.claimInterface(targetIface, true);
        }

        GamepadUsbSession session = new GamepadUsbSession();
        session.device = device;
        session.connection = conn;
        session.usbInterface = targetIface;
        session.outEndpoint = outEp;
        session.protocol = detectProtocol(device);

        activeSessions.put(device.getDeviceName(), session);
        Log.i(TAG, "Physical USB Gamepad Rumble Hook ACTIVE for: " + device.getProductName() + " (Protocol: " + session.protocol + ")");
    }

    private ControllerProtocol detectProtocol(UsbDevice device) {
        int vid = device.getVendorId();
        if (vid == 0x054c) {
            int pid = device.getProductId();
            if (pid == 0x0ce6 || pid == 0x0df2) return ControllerProtocol.SONY_DUALSENSE;
            return ControllerProtocol.SONY_DS4;
        } else if (vid == 0x057e) {
            return ControllerProtocol.NINTENDO_SWITCH;
        } else {
            return ControllerProtocol.XINPUT_XBOX360; // Redgear, Xbox, ShanWan, Generic PC
        }
    }

    /**
     * Sends hardware force-feedback rumble to all connected USB gamepads.
     * @param strong 0-65535 or 0-255 strong low-frequency motor (left heavy rumble)
     * @param weak 0-65535 or 0-255 weak high-frequency motor (right buzz rumble)
     * @return true if hardware vibration was dispatched to at least one physical USB controller
     */
    public boolean sendRumble(int strong, int weak) {
        if (activeSessions.isEmpty()) return false;

        // Normalize 16-bit XInput (0-65535) down to 8-bit USB HID (0-255)
        int s8 = strong > 255 ? (int) ((strong / 65535.0f) * 255) : strong;
        int w8 = weak > 255 ? (int) ((weak / 65535.0f) * 255) : weak;

        boolean dispatched = false;
        for (GamepadUsbSession session : activeSessions.values()) {
            if (session.connection == null) continue;

            try {
                switch (session.protocol) {
                    case XINPUT_XBOX360:
                    case GENERIC_HID: {
                        // Standard Xbox 360 / Redgear / ShanWan Force-Feedback OUT report
                        byte[] report = new byte[]{
                            0x00, 0x08, 0x00,
                            (byte) (s8 & 0xFF),
                            (byte) (w8 & 0xFF),
                            0x00, 0x00, 0x00
                        };
                        if (session.outEndpoint != null) {
                            session.connection.bulkTransfer(session.outEndpoint, report, report.length, 50);
                        } else {
                            session.connection.controlTransfer(0x21, 0x09, 0x0200, 0, report, report.length, 50);
                        }
                        dispatched = true;
                        break;
                    }
                    case SONY_DS4: {
                        // Sony DualShock 4 USB Rumble Report
                        byte[] report = new byte[32];
                        report[0] = 0x05;
                        report[1] = (byte) 0xFF;
                        report[4] = (byte) (w8 & 0xFF);
                        report[5] = (byte) (s8 & 0xFF);
                        if (session.outEndpoint != null) {
                            session.connection.bulkTransfer(session.outEndpoint, report, report.length, 50);
                        }
                        dispatched = true;
                        break;
                    }
                    case SONY_DUALSENSE: {
                        // Sony DualSense PS5 USB Rumble Report
                        byte[] report = new byte[48];
                        report[0] = 0x02;
                        report[1] = 0x02;
                        report[2] = 0x03;
                        report[3] = (byte) (w8 & 0xFF);
                        report[4] = (byte) (s8 & 0xFF);
                        if (session.outEndpoint != null) {
                            session.connection.bulkTransfer(session.outEndpoint, report, report.length, 50);
                        }
                        dispatched = true;
                        break;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error sending USB rumble packet: " + e.getMessage());
            }
        }
        return dispatched;
    }

    private void closeSession(String deviceName) {
        GamepadUsbSession session = activeSessions.remove(deviceName);
        if (session != null) {
            try {
                if (session.connection != null) {
                    if (session.usbInterface != null) session.connection.releaseInterface(session.usbInterface);
                    session.connection.close();
                }
            } catch (Exception ignored) {}
        }
    }

    public void release() {
        for (String devName : new HashMap<>(activeSessions).keySet()) {
            closeSession(devName);
        }
        try {
            context.unregisterReceiver(usbReceiver);
        } catch (Exception ignored) {}
    }
}
