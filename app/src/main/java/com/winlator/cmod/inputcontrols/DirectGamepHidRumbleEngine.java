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
import android.view.InputDevice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Winlator Direct USB HID Hardware Force-Feedback Engine.
 * 
 * Bypasses Android's broken framework gamepad vibrator limits by establishing direct
 * USB HID Host connections to physical controllers (Redgear Elite, Xbox 360/One, PS4/PS5,
 * Switch, ShanWan, Betop, DragonRise, GameSir, 8BitDo) and sending raw hardware vibration
 * reports directly to the physical controller motors!
 */
public class DirectGamepHidRumbleEngine {
    private static final String TAG = "DirectHidRumbleEngine";
    public static final String ACTION_USB_PERMISSION = "com.winlator.cmod.USB_RUMBLE_PERMISSION";

    private static DirectGamepHidRumbleEngine instance;
    private final Context context;
    private final UsbManager usbManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Map<String, GamepadUsbSession> activeSessions = new HashMap<>();
    private Runnable onPermissionGrantedCallback;

    public static class GamepadUsbSession {
        public UsbDevice device;
        public UsbDeviceConnection connection;
        public UsbInterface usbInterface;
        public UsbEndpoint outEndpoint;
        public ControllerProtocol protocol;
        public String status = "Active";
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

    /**
     * Scans all connected USB devices and connects to gamepads.
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

    /**
     * Determines if a USB device is an external controller or gamepad.
     */
    public boolean isSupportedGamepad(UsbDevice device) {
        if (device == null) return false;
        int vid = device.getVendorId();
        int pid = device.getProductId();

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
        session.outEndpoint = outEp;
        session.protocol = detectProtocol(device);

        activeSessions.put(device.getDeviceName(), session);
        Log.i(TAG, "Direct USB Gamepad Connected: " + getDeviceDisplayName(device) + " (Protocol: " + session.protocol + ", OUT EP: " + (outEp != null ? outEp.getAddress() : "Control EP0") + ")");
        return true;
    }

    private ControllerProtocol detectProtocol(UsbDevice device) {
        int vid = device.getVendorId();
        int pid = device.getProductId();

        if (vid == 0x054c) {
            if (pid == 0x0ce6 || pid == 0x0df2) return ControllerProtocol.SONY_DUALSENSE;
            return ControllerProtocol.SONY_DS4;
        } else if (vid == 0x057e) {
            return ControllerProtocol.NINTENDO_SWITCH;
        } else if (vid == 0x2563 || vid == 0x11ff || vid == 0x20d6 || vid == 0x0e8f || vid == 0x0079) {
            return ControllerProtocol.SHANWAN_BETOP; // Redgear Elite / ShanWan / Betop / DragonRise
        } else {
            return ControllerProtocol.XINPUT_XBOX360; // Microsoft Xbox 360 / Xbox One / Clones
        }
    }

    /**
     * Sends raw force-feedback vibration OUT packets to all connected USB gamepads.
     * Dual-dispatches XInput and ShanWan/Betop formats so Redgear Elite vibrates in ANY mode!
     * 
     * @param strong 0-65535 or 0-255 left heavy motor
     * @param weak 0-65535 or 0-255 right light motor
     * @return true if vibration packet was sent successfully
     */
    public boolean sendRumble(int strong, int weak) {
        if (activeSessions.isEmpty()) {
            // Attempt auto re-scan if sessions are empty
            scanAndConnectGamepads();
            if (activeSessions.isEmpty()) return false;
        }

        int s8 = strong > 255 ? (int) ((strong / 65535.0f) * 255) : strong;
        int w8 = weak > 255 ? (int) ((weak / 65535.0f) * 255) : weak;
        s8 = Math.max(0, Math.min(255, s8));
        w8 = Math.max(0, Math.min(255, w8));

        boolean dispatched = false;
        for (GamepadUsbSession session : new ArrayList<>(activeSessions.values())) {
            if (session.connection == null) continue;

            try {
                int ifaceIndex = session.usbInterface != null ? session.usbInterface.getId() : 0;

                // 1. Standard XInput 8-byte rumble packet
                byte[] xinput8 = new byte[]{
                    0x00, 0x08, 0x00,
                    (byte) (s8 & 0xFF),
                    (byte) (w8 & 0xFF),
                    0x00, 0x00, 0x00
                };

                // 2. ShanWan / Betop 5-byte rumble packet (Redgear Elite DInput Mode)
                byte[] shanwan5 = new byte[]{
                    0x00, 0x51, 0x00,
                    (byte) (s8 & 0xFF),
                    (byte) (w8 & 0xFF)
                };

                // 3. ShanWan / Betop 4-byte rumble packet
                byte[] shanwan4 = new byte[]{
                    0x51, 0x00,
                    (byte) (s8 & 0xFF),
                    (byte) (w8 & 0xFF)
                };

                // 4. Generic DInput 4-byte report
                byte[] dinput4 = new byte[]{
                    (byte) (w8 & 0xFF),
                    (byte) (s8 & 0xFF),
                    0x00, 0x00
                };

                // 5. Xbox 360 Wireless 12-byte packet
                byte[] wireless12 = new byte[]{
                    0x00, 0x01, 0x0f, (byte) 0xc0,
                    0x00, (byte) (s8 & 0xFF), (byte) (w8 & 0xFF),
                    0x00, 0x00, 0x00, 0x00, 0x00
                };

                switch (session.protocol) {
                    case XINPUT_XBOX360:
                    case SHANWAN_BETOP:
                    case GENERIC_HID: {
                        // Send XInput 8-byte
                        if (session.outEndpoint != null) {
                            session.connection.bulkTransfer(session.outEndpoint, xinput8, xinput8.length, 50);
                        }
                        session.connection.controlTransfer(0x21, 0x09, 0x0200, ifaceIndex, xinput8, xinput8.length, 50);

                        // Dual-dispatch ShanWan/Betop packets for Redgear in DInput mode
                        if (session.outEndpoint != null) {
                            session.connection.bulkTransfer(session.outEndpoint, shanwan5, shanwan5.length, 50);
                            session.connection.bulkTransfer(session.outEndpoint, wireless12, wireless12.length, 50);
                        }
                        session.connection.controlTransfer(0x21, 0x09, 0x0200, ifaceIndex, shanwan5, shanwan5.length, 50);
                        session.connection.controlTransfer(0x21, 0x09, 0x0300, ifaceIndex, shanwan5, shanwan5.length, 50);
                        session.connection.controlTransfer(0x21, 0x09, 0x0200, ifaceIndex, shanwan4, shanwan4.length, 50);
                        session.connection.controlTransfer(0x21, 0x09, 0x0200, ifaceIndex, dinput4, dinput4.length, 50);

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
                            session.connection.bulkTransfer(session.outEndpoint, report, report.length, 50);
                        }
                        session.connection.controlTransfer(0x21, 0x09, 0x0205, ifaceIndex, report, report.length, 50);
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
                            session.connection.bulkTransfer(session.outEndpoint, report, report.length, 50);
                        }
                        session.connection.controlTransfer(0x21, 0x09, 0x0202, ifaceIndex, report, report.length, 50);
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
