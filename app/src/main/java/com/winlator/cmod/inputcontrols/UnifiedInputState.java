package com.winlator.cmod.inputcontrols;

import java.util.BitSet;

/**
 * A unified state that captures all inputs.
 */
public class UnifiedInputState {
    public final GamepadState gamepad = new GamepadState();
    public float mouseDeltaX = 0;
    public float mouseDeltaY = 0;
    public float mouseWheelDelta = 0;
    public final BitSet mouseButtons = new BitSet(5);
    public final BitSet keys = new BitSet(256);
    
    public enum EmulationMode {
        GAME_CONTROLLER // Automated Controller Mode (XInput + DInput + Compatible)
    }
    
    public EmulationMode mode = EmulationMode.GAME_CONTROLLER;

    public void reset() {
        gamepad.copy(new GamepadState());
        mouseDeltaX = 0;
        mouseDeltaY = 0;
        mouseWheelDelta = 0;
        mouseButtons.clear();
        keys.clear();
    }
}
