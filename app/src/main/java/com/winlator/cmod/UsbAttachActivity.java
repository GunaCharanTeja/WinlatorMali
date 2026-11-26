package com.winlator.cmod;

import android.app.Activity;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;

import com.winlator.cmod.inputcontrols.DirectGamepHidRumbleEngine;

// Invisible target for the USB_DEVICE_ATTACHED filter: being launched for a matching controller is what
// makes Android auto-grant (and remember) USB permission. It only takes the device if a game is running.
public class UsbAttachActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UsbDevice device = getIntent().getParcelableExtra(UsbManager.EXTRA_DEVICE);
        DirectGamepHidRumbleEngine engine = DirectGamepHidRumbleEngine.peekInstance();
        if (device != null && engine != null) engine.requestPermissionOrOpen(device);
        finish();
    }
}
