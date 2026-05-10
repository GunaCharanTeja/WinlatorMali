package com.winlator.cmod.box64;

import androidx.annotation.NonNull;

public class Box64Preset {
    public static final String STABILITY = "STABILITY";
    public static final String COMPATIBILITY = "COMPATIBILITY";
    public static final String INTERMEDIATE = "INTERMEDIATE";
    public static final String PERFORMANCE = "PERFORMANCE";
    public static final String MAX_PERFORMANCE = "MAX_PERFORMANCE";
    public static final String BATTERY_SAVER = "BATTERY_SAVER";
    public static final String UNITY_ENGINE = "UNITY_ENGINE";
    public static final String SOURCE_ENGINE = "SOURCE_ENGINE";
    public static final String CLASSIC_GAMES = "CLASSIC_GAMES";
    public static final String ULTRA_STABILITY = "ULTRA_STABILITY";
    public static final String UNREAL_ENGINE_3 = "UNREAL_ENGINE_3";
    public static final String CUSTOM = "CUSTOM";
    public final String id;
    public final String name;

    public Box64Preset(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public boolean isCustom() {
        return id.startsWith(CUSTOM);
    }

    @NonNull
    @Override
    public String toString() {
        return name;
    }
}