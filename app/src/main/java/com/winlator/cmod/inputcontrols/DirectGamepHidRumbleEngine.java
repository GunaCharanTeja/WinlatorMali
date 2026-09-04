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
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.winlator.cmod.winhandler.WinHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Winlator Direct USB HID Hardware Force-Feedback and Input Engine.
 * 
 * Provides:
 * 1. Zero-latency raw USB hardware input reading (bypassing Android framework).
 * 2. Direct hardware force-feedback rumble to physical controller motors (Redgear Elite, Xbox, etc.).
 * 3. Never freezes or disconnects controls during force-feedback.
 */
public class DirectGamepHidRumbleEngine {
    private static final String TAG = "DirectHidRumbleEngine";
    public static final String ACTION_USB_PERMISSION = "com.winlator.cmod.USB_RUMBLE_PERMISSION";

    private static DirectGamepHidRumbleEngine instance;
    private final Context context;
    private final UsbManager usbManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Map<String, GamepadUsbSession> activeSessions = new HashMap<>();
    private WinHandler winHandler;
    private Runnable onPermissionGrantedCallback;
    private Runnable autoStopRunnable;

    public static class GamepadUsbSession {
        public UsbDevice device;
        public UsbDeviceConnection connection;
        public UsbInterface usbInterface;
        public UsbEndpoint inEndpoint;
        public UsbEndpoint outEndpoint;
        public ControllerProtocol protocol;
        public int slot = 0;
        public volatile boolean readerRunning = false;
        public Thread readerThread;
    }

    public static class UsbDeviceInfo {
        public UsbDevice device;
        public String name;
        public int vid;
        public int pid;
        public boolean hasPermission;
        public boolean isConnected;
        public ControllerProtocol protocol;
    }

    public enum ControllerProtocol {
        XINPUT_XBOX360,
        SHANWAN_BETOP,
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

    public void setWinHandler(WinHandler winHandler) {
        this.winHandler = winHandler;
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
                        if (device != null) {
                            openGamepadConnection(device);
                            if (onPermissionGrantedCallback != null) {
                                mainHandler.post(onPermissionGrantedCallback);
                            }
                        }
                    } else {
                        Log.w(TAG, "USB Permission denied for: " + (device != null ? device.getDeviceName() : "Unknown"));
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null && isSupportedGamepad(device)) {
                    requestPermissionOrOpen(device);
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null) closeSession(device.getDeviceName());
            }
        }
    };

    public void scanAndConnectGamepads() {
        if (usbManager == null) return;
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        for (UsbDevice device : deviceList.values()) {
            if (isSupportedGamepad(device)) {
                requestPermissionOrOpen(device);
            }
        }
    }

    public boolean isSupportedGamepad(UsbDevice device) {
        if (device == null) return false;
        int vid = device.getVendorId();

        // 1. Redgear / ShanWan / Betop / DragonRise / Generic PC Gamepads
        if (vid == 0x2563 || vid == 0x11ff || vid == 0x20d6 || vid == 0x12ab || vid == 0x0e8f ||
            vid == 0x0079 || vid == 0x0810 || vid == 0x1345 || vid == 0x1e3d || vid == 0x2838 ||
            vid == 0x0583 || vid == 0x04b4 || vid == 0x1c6b || vid == 0x146b || vid == 0x20bc) {
            return true;
        }

        // 2. Microsoft Xbox 360 / Xbox One / Series X/S / Dongles (0x045e)
        if (vid == 0x045e) return true;

        // 3. Sony PlayStation 3 / 4 / 5 (0x054c)
        if (vid == 0x054c) return true;

        // 4. Nintendo Switch Pro / Joy-Cons (0x057e)
        if (vid == 0x057e) return true;

        // 5. 8BitDo / PowerA / PDP / Mad Catz / Hori / Razer / Logitech / Flydigi / GameSir
        if (vid == 0x2dc8 || vid == 0x24c6 || vid == 0x0e6f || vid == 0x1bad || vid == 0x0f0d ||
            vid == 0x1532 || vid == 0x1689 || vid == 0x046d || vid == 0x044f || vid == 0x2c22 ||
            vid == 0x2f24 || vid == 0x3285) {
            return true;
        }

        // 6. Inspect USB Interface Class
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            int cls = iface.getInterfaceClass();
            if (cls == UsbConstants.USB_CLASS_HID || cls == 0xFF) {
                return true;
            }
        }

        // 7. Check device / product name keywords
        String name = getDeviceDisplayName(device).toLowerCase(Locale.ROOT);
        if (name.contains("gamepad") || name.contains("controller") || name.contains("joystick") ||
            name.contains("redgear") || name.contains("shanwan") || name.contains("betop") ||
            name.contains("xbox") || name.contains("dualshock") || name.contains("dualsense") ||
            name.contains("wireless") || name.contains("receiver") || name.contains("pad") ||
            name.contains("elite") || name.contains("8bitdo") || name.contains("gamesir") ||
            name.contains("flydigi") || name.contains("logitech") || name.contains("speedlink")) {
            return true;
        }

        return false;
    }

    public String getDeviceDisplayName(UsbDevice device) {
        if (device == null) return "Unknown Device";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            String prod = device.getProductName();
            String mfg = device.getManufacturerName();
            if (prod != null && !prod.isEmpty()) {
                if (mfg != null && !mfg.isEmpty() && !prod.contains(mfg)) {
                    return mfg + " " + prod;
                }
                return prod;
            }
        }
        return String.format(Locale.US, "USB Controller [VID:0x%04X PID:0x%04X]", device.getVendorId(), device.getProductId());
    }

    public void requestPermissionOrOpen(UsbDevice device) {
        if (device == null || usbManager == null) return;
        if (usbManager.hasPermission(device)) {
            openGamepadConnection(device);
        } else {
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
            PendingIntent pi = PendingIntent.getBroadcast(context, 0, new Intent(ACTION_USB_PERMISSION), flags);
            usbManager.requestPermission(device, pi);
        }
    }

    public void requestPermission(UsbDevice device, Runnable onGranted) {
        this.onPermissionGrantedCallback = onGranted;
        requestPermissionOrOpen(device);
    }

    public boolean hasPermission(UsbDevice device) {
        return usbManager != null && device != null && usbManager.hasPermission(device);
    }

    public boolean openGamepadConnection(UsbDevice device) {
        if (device == null || usbManager == null) return false;
        closeSession(device.getDeviceName());

        if (!usbManager.hasPermission(device)) {
            Log.w(TAG, "Cannot open USB connection: Missing permission for " + device.getDeviceName());
            return false;
        }

        UsbDeviceConnection conn = usbManager.openDevice(device);
        if (conn == null) {
            Log.w(TAG, "Failed to open USB device connection: " + getDeviceDisplayName(device));
            return false;
        }

        UsbInterface targetIface = null;
        UsbEndpoint inEp = null;
        UsbEndpoint outEp = null;

        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            for (int e = 0; e < iface.getEndpointCount(); e++) {
                UsbEndpoint ep = iface.getEndpoint(e);
                if (ep.getDirection() == UsbConstants.USB_DIR_OUT && outEp == null) {
                    outEp = ep;
                } else if (ep.getDirection() == UsbConstants.USB_DIR_IN && inEp == null) {
                    inEp = ep;
                }
            }
            if (outEp != null && inEp != null) {
                targetIface = iface;
                break;
            } else if (targetIface == null && (outEp != null || inEp != null)) {
                targetIface = iface;
            }
        }

        if (targetIface == null && device.getInterfaceCount() > 0) {
            targetIface = device.getInterface(0);
        }

        if (targetIface != null) {
            try {
                conn.claimInterface(targetIface, true);
            } catch (Exception e) {
                Log.w(TAG, "Interface claim notice: " + e.getMessage());
            }
        }

        GamepadUsbSession session = new GamepadUsbSession();
        session.device = device;
        session.connection = conn;
        session.usbInterface = targetIface;
        session.inEndpoint = inEp;
        session.outEndpoint = outEp;
        session.protocol = detectProtocol(device);
        session.slot = activeSessions.size();

        activeSessions.put(device.getDeviceName(), session);
        Log.i(TAG, "Direct USB Gamepad Connected: " + getDeviceDisplayName(device) + " (Protocol: " + session.protocol + ", IN EP: " + (inEp != null ? inEp.getAddress() : "None") + ", OUT EP: " + (outEp != null ? outEp.getAddress() : "None") + ")");

        // Start dedicated raw USB input reader thread
        if (inEp != null) {
            startInputReaderThread(session);
        }

        return true;
    }

    private void startInputReaderThread(GamepadUsbSession session) {
        session.readerRunning = true;
        session.readerThread = new Thread(() -> {
            byte[] buffer = new byte[64];
            GamepadState state = new GamepadState();
            while (session.readerRunning && session.connection != null && session.inEndpoint != null) {
                int read = session.connection.bulkTransfer(session.inEndpoint, buffer, buffer.length, 50);
                if (read >= 8) {
                    parseUsbReport(buffer, read, session.protocol, state);
                    if (winHandler != null) {
                        winHandler.sendDirectGamepadState(session.slot, state);
                    }
                }
            }
        }, "USB-Gamepad-Reader-" + session.device.getDeviceName());
        session.readerThread.start();
    }

    private void parseUsbReport(byte[] b, int len, ControllerProtocol proto, GamepadState s) {
        switch (proto) {
            case XINPUT_XBOX360:
                parseXInputReport(b, len, s);
                break;
            case SHANWAN_BETOP:
            case GENERIC_HID:
            default:
                if (len >= 14 && b[0] == 0x00 && b[1] == 0x14) {
                    parseXInputReport(b, len, s);
                } else {
                    parseDInputReport(b, len, s);
                }
                break;
        }
    }

    private void parseXInputReport(byte[] b, int len, GamepadState s) {
        if (len < 14) return;
        int b2 = b[2] & 0xFF;
        int b3 = b[3] & 0xFF;

        // D-Pad
        s.dpad[0] = (b2 & 0x01) != 0; // Up
        s.dpad[2] = (b2 & 0x02) != 0; // Down
        s.dpad[3] = (b2 & 0x04) != 0; // Left
        s.dpad[1] = (b2 & 0x08) != 0; // Right

        // Buttons
        s.setPressed((byte) 7, (b2 & 0x10) != 0); // Start
        s.setPressed((byte) 6, (b2 & 0x20) != 0); // Select / Back
        s.setPressed((byte) 8, (b2 & 0x40) != 0); // L3 (Left thumb)
        s.setPressed((byte) 9, (b2 & 0x80) != 0); // R3 (Right thumb)

        s.setPressed((byte) 4, (b3 & 0x01) != 0); // LB (L1)
        s.setPressed((byte) 5, (b3 & 0x02) != 0); // RB (R1)
        s.setPressed((byte) 0, (b3 & 0x10) != 0); // A
        s.setPressed((byte) 1, (b3 & 0x20) != 0); // B
        s.setPressed((byte) 2, (b3 & 0x40) != 0); // X
        s.setPressed((byte) 3, (b3 & 0x80) != 0); // Y

        // Triggers
        s.triggerL = (b[4] & 0xFF) / 255.0f;
        s.triggerR = (b[5] & 0xFF) / 255.0f;

        // Sticks (signed 16-bit little endian)
        short lx = (short) ((b[6] & 0xFF) | ((b[7] & 0xFF) << 8));
        short ly = (short) ((b[8] & 0xFF) | ((b[9] & 0xFF) << 8));
        short rx = (short) ((b[10] & 0xFF) | ((b[11] & 0xFF) << 8));
        short ry = (short) ((b[12] & 0xFF) | ((b[13] & 0xFF) << 8));

        s.thumbLX = lx / 32768.0f;
        s.thumbLY = -ly / 32768.0f; // Invert Y
        s.thumbRX = rx / 32768.0f;
        s.thumbRY = -ry / 32768.0f;
    }

    private void parseDInputReport(byte[] b, int len, GamepadState s) {
        if (len < 6) return;
        int lx = b[0] & 0xFF;
        int ly = b[1] & 0xFF;
        int rx = b[2] & 0xFF;
        int ry = b[3] & 0xFF;

        s.thumbLX = (lx - 128) / 128.0f;
        s.thumbLY = -(ly - 128) / 128.0f;
        s.thumbRX = (rx - 128) / 128.0f;
        s.thumbRY = -(ry - 128) / 128.0f;

        int b4 = b[4] & 0xFF;
        int b5 = b[5] & 0xFF;

        int hat = b4 & 0x0F;
        s.dpad[0] = (hat == 0 || hat == 1 || hat == 7);
        s.dpad[1] = (hat == 1 || hat == 2 || hat == 3);
        s.dpad[2] = (hat == 3 || hat == 4 || hat == 5);
        s.dpad[3] = (hat == 5 || hat == 6 || hat == 7);

        s.setPressed((byte) 0, (b4 & 0x10) != 0); // A
        s.setPressed((byte) 1, (b4 & 0x20) != 0); // B
        s.setPressed((byte) 2, (b4 & 0x40) != 0); // X
        s.setPressed((byte) 3, (b4 & 0x80) != 0); // Y

        s.setPressed((byte) 4, (b5 & 0x01) != 0); // L1
        s.setPressed((byte) 5, (b5 & 0x02) != 0); // R1
        s.triggerL = (b5 & 0x04) != 0 ? 1.0f : 0.0f; // L2
        s.triggerR = (b5 & 0x08) != 0 ? 1.0f : 0.0f; // R2
        s.setPressed((byte) 6, (b5 & 0x10) != 0); // Select
        s.setPressed((byte) 7, (b5 & 0x20) != 0); // Start
        s.setPressed((byte) 8, (b5 & 0x40) != 0); // L3
        s.setPressed((byte) 9, (b5 & 0x80) != 0); // R3
    }

    private ControllerProtocol detectProtocol(UsbDevice device) {
        int vid = device.getVendorId();
        int pid = device.getProductId();

        if (vid == 0x054c) {
            if (pid == 0x0ce6 || pid == 0x0df2) return ControllerProtocol.SONY_DUALSENSE;
            return ControllerProtocol.SONY_DS4;
        } else if (vid == 0x057e) {
            return ControllerProtocol.NINTENDO_SWITCH;
        } else if (vid == 0x2563 && (pid == 0x0523 || pid == 0x0555 || pid == 0x0571)) {
            return ControllerProtocol.SHANWAN_BETOP;
        } else if (vid == 0x11ff || vid == 0x20d6 || vid == 0x0e8f || vid == 0x0079) {
            return ControllerProtocol.SHANWAN_BETOP;
        } else {
            return ControllerProtocol.XINPUT_XBOX360;
        }
    }

    public boolean sendRumble(int strong, int weak) {
        return sendRumble(strong, weak, 0);
    }

    public boolean sendRumble(int strong, int weak, int durationMs) {
        if (activeSessions.isEmpty()) {
            scanAndConnectGamepads();
            if (activeSessions.isEmpty()) return false;
        }

        if (autoStopRunnable != null) {
            mainHandler.removeCallbacks(autoStopRunnable);
            autoStopRunnable = null;
        }

        int s8 = strong > 255 ? (int) ((strong / 65535.0f) * 255) : strong;
        int w8 = weak > 255 ? (int) ((weak / 65535.0f) * 255) : weak;
        s8 = Math.max(0, Math.min(255, s8));
        w8 = Math.max(0, Math.min(255, w8));

        // Ensure physical motor duty threshold when active so high-frequency motors spin
        if (s8 > 0 && s8 < 50) s8 = 50;
        if (w8 > 0 && w8 < 60) w8 = 60;

        boolean isStopping = (s8 == 0 && w8 == 0);

        boolean dispatched = false;
        for (GamepadUsbSession session : new ArrayList<>(activeSessions.values())) {
            if (session.connection == null) continue;

            try {
                int ifaceIndex = session.usbInterface != null ? session.usbInterface.getId() : 0;

                // XInput 8-byte report: [0x00, 0x08, 0x00, Heavy_Left, Light_Right, 0x00, 0x00, 0x00]
                byte[] xinput8 = new byte[]{
                    0x00, 0x08, 0x00,
                    (byte) (s8 & 0xFF),
                    (byte) (w8 & 0xFF),
                    0x00, 0x00, 0x00
                };

                // ShanWan / Betop / PS2 adapter 5-byte report (Linux hid-betopff.c): [0x00, 0x51, 0x00, Light_Right, Heavy_Left]
                byte[] shanwanWS = new byte[]{
                    0x00, 0x51, 0x00,
                    (byte) (w8 & 0xFF),
                    (byte) (s8 & 0xFF)
                };

                byte[] shanwan4WS = new byte[]{
                    0x51, 0x00,
                    (byte) (w8 & 0xFF),
                    (byte) (s8 & 0xFF)
                };

                switch (session.protocol) {
                    case XINPUT_XBOX360: {
                        if (session.outEndpoint != null) {
                            session.connection.bulkTransfer(session.outEndpoint, xinput8, xinput8.length, 30);
                        }
                        session.connection.controlTransfer(0x21, 0x09, 0x0200, ifaceIndex, xinput8, xinput8.length, 30);
                        dispatched = true;
                        break;
                    }
                    case SHANWAN_BETOP:
                    case GENERIC_HID: {
                        if (session.outEndpoint != null) {
                            session.connection.bulkTransfer(session.outEndpoint, shanwanWS, shanwanWS.length, 30);
                            session.connection.bulkTransfer(session.outEndpoint, xinput8, xinput8.length, 30);
                        }
                        session.connection.controlTransfer(0x21, 0x09, 0x0200, ifaceIndex, shanwanWS, shanwanWS.length, 30);
                        session.connection.controlTransfer(0x21, 0x09, 0x0251, ifaceIndex, shanwan4WS, shanwan4WS.length, 30);
                        session.connection.controlTransfer(0x21, 0x09, 0x0300, ifaceIndex, shanwanWS, shanwanWS.length, 30);
                        session.connection.controlTransfer(0x21, 0x09, 0x0200, ifaceIndex, xinput8, xinput8.length, 30);
                        dispatched = true;
                        break;
                    }
                    case SONY_DS4: {
                        byte[] report = new byte[32];
                        report[0] = 0x05;
                        report[1] = (byte) 0xFF;
                        report[4] = (byte) (w8 & 0xFF);
                        report[5] = (byte) (s8 & 0xFF);
                        if (session.outEndpoint != null) {
                            session.connection.bulkTransfer(session.outEndpoint, report, report.length, 30);
                        }
                        session.connection.controlTransfer(0x21, 0x09, 0x0205, ifaceIndex, report, report.length, 30);
                        dispatched = true;
                        break;
                    }
                    case SONY_DUALSENSE: {
                        byte[] report = new byte[48];
                        report[0] = 0x02;
                        report[1] = 0x02;
                        report[2] = 0x03;
                        report[3] = (byte) (w8 & 0xFF);
                        report[4] = (byte) (s8 & 0xFF);
                        if (session.outEndpoint != null) {
                            session.connection.bulkTransfer(session.outEndpoint, report, report.length, 30);
                        }
                        session.connection.controlTransfer(0x21, 0x09, 0x0202, ifaceIndex, report, report.length, 30);
                        dispatched = true;
                        break;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error sending USB rumble packet: " + e.getMessage());
            }
        }

        if (!isStopping) {
            autoStopRunnable = () -> sendRumble(0, 0);
            if (durationMs > 0 && durationMs < 30000) {
                // Finite test/effect duration (0.5s, 1.0s, 2.0s, 3.0s)
                mainHandler.postDelayed(autoStopRunnable, durationMs);
            } else {
                // Continuous in-game XInput stream (durationMs == 0 or >= 30000).
                // Do NOT prematurely stop after 250ms; keep running until game sends (0,0) or 10s safety watchdog.
                mainHandler.postDelayed(autoStopRunnable, 10000);
            }
        }

        return dispatched;
    }

    public List<UsbDeviceInfo> getDetectedDeviceInfos() {
        List<UsbDeviceInfo> list = new ArrayList<>();
        if (usbManager == null) return list;

        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        for (UsbDevice device : deviceList.values()) {
            if (isSupportedGamepad(device)) {
                UsbDeviceInfo info = new UsbDeviceInfo();
                info.device = device;
                info.name = getDeviceDisplayName(device);
                info.vid = device.getVendorId();
                info.pid = device.getProductId();
                info.hasPermission = usbManager.hasPermission(device);
                info.isConnected = activeSessions.containsKey(device.getDeviceName());
                info.protocol = detectProtocol(device);
                list.add(info);
            }
        }
        return list;
    }

    public int getActiveSessionCount() {
        return activeSessions.size();
    }

    public void closeSession(String deviceName) {
        GamepadUsbSession session = activeSessions.remove(deviceName);
        if (session != null) {
            session.readerRunning = false;
            if (session.readerThread != null) {
                session.readerThread.interrupt();
            }
            try {
                if (session.connection != null) {
                    if (session.usbInterface != null) session.connection.releaseInterface(session.usbInterface);
                    session.connection.close();
                }
            } catch (Exception ignored) {}
        }
    }

    public void release() {
        if (autoStopRunnable != null) {
            mainHandler.removeCallbacks(autoStopRunnable);
            autoStopRunnable = null;
        }
        sendRumble(0, 0);
        for (String devName : new HashMap<>(activeSessions).keySet()) {
            closeSession(devName);
        }
        try {
            context.unregisterReceiver(usbReceiver);
        } catch (Exception ignored) {}
    }
}
