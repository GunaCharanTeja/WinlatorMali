package com.winlator.cmod.core;

public class RamBooster {
    public native static boolean pressure(long targetBytes, int chunkSleepMs);

    static {
        System.loadLibrary("winlator");
    }
}
