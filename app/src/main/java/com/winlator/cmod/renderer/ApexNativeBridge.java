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
    public static native void nativeSetFlowScale(float scale);
    public static native float nativeGetFlowScale();
    public static native void nativeSetLiquidFeel(float feel);
    public static native float nativeGetLiquidFeel();
    public static native void nativeSetEdgeGuard(float guard);
    public static native float nativeGetEdgeGuard();
    public static native void nativeSetRenderScale(float scale);
    public static native float nativeGetRenderScale();
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
    public static native void nativeProcessFrame(int inputTextureId, int outputFboId, int width, int height,
                                                int viewX, int viewY, int viewWidth, int viewHeight, boolean isNewRealFrame);
    public static void nativeProcessFrame(int inputTextureId, int outputFboId, int width, int height, boolean isNewRealFrame) {
        nativeProcessFrame(inputTextureId, outputFboId, width, height, 0, 0, width, height, isNewRealFrame);
    }
    public static void nativeProcessFrame(int inputTextureId, int outputFboId, int width, int height) {
        nativeProcessFrame(inputTextureId, outputFboId, width, height, 0, 0, width, height, true);
    }
    public static native void nativeProcessFrameWithData(int inputTextureId, int depthTextureId, int hudTextureId, int outputFboId, int width, int height);

    // Diagnostics & Verification
    public static native String nativeGetDiagnostics();
    public static native int nativeGetCompiledShaderCount();
    public static native boolean nativeIsHealthy();
    public static native void nativeSetDebugOverlay(boolean enabled);
    public static native boolean nativeIsDebugOverlay();
    public static native void nativeSetLoggingEnabled(boolean enabled);
    public static native boolean nativeIsLoggingEnabled();
}
