package com.winlator.cmod.renderer;

public class ApexNativeBridge {
    static {
        System.loadLibrary("winlator");
    }

    public static native void nativeInit(int width, int height);
    public static native void nativeSetActive(boolean active);
    public static native boolean nativeIsActive();
    public static native void nativeSetQuality(int quality);
    public static native int nativeGetQuality();
    public static native void nativeSetTargetFPS(int fps);
    public static native int nativeGetTargetFPS();
    public static native void nativeSetShutterGain(float gain);
    public static native float nativeGetShutterGain();
    public static native void nativeUpdateDimensions(int width, int height);
    public static native void nativeDestroy();

    // Pacing & Timing Hooks
    public static native void nativeOnFrameCaptured(boolean isActualNewFrame);
    public static native boolean nativeIsGeneratedFrame();
    public static native float nativeGetInterpolationFactor();

    // Telemetry & Stats
    public static native int nativeGetRealFPS();
    public static native int nativeGetGenFPS();
    public static native int nativeGetAutoMultiplier();

    // Direct GPU Frame Processing Hook on Render Thread
    public static native void nativeProcessFrame(int inputTextureId, int outputFboId, int width, int height);
}
