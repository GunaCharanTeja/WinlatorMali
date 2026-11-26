package com.winlator.cmod.widget;

/**
 * GPU usage and clock from Mali hardware counters (/dev/mali0). Works without root on phones that block
 * every sysfs GPU path from apps. Not thread-safe: use from one thread (the HUD poller).
 */
final class MaliGpuCounters {
    private long handle;
    private boolean unavailable;
    private final int[] sample = new int[2];

    /** Fills out[0] = usage %, out[1] = clock MHz (each -1 when unknown). Returns false if counters can't be read. */
    boolean read(int[] out) {
        if (unavailable) return false;
        try {
            if (handle == 0) {
                handle = nativeOpen();
                if (handle == 0) {
                    unavailable = true;
                    return false;
                }
            }
            if (!nativeSample(handle, sample)) {
                close();
                unavailable = true;
                return false;
            }
            out[0] = sample[0];
            out[1] = sample[1];
            return true;
        } catch (UnsatisfiedLinkError e) {
            unavailable = true;
            return false;
        }
    }

    void close() {
        if (handle != 0) {
            nativeClose(handle);
            handle = 0;
        }
    }

    private static native long nativeOpen();
    private static native boolean nativeSample(long handle, int[] out);
    private static native void nativeClose(long handle);
}
