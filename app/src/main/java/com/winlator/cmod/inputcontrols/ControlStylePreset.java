package com.winlator.cmod.inputcontrols;

public enum ControlStylePreset {
    // The default: glass after Madeira's touch controls. Profiles saved with a removed style (the former default
    // "WINLATOR_MALI", Cyberpunk, Retro Arcade, Stealth) load as this one (see parse).
    GLASS("WinMali Default", 0),
    XBOX("Xbox Edition", 1),
    PLAYSTATION("PlayStation Edition", 2);

    public final String title;
    public final int id;

    ControlStylePreset(String title, int id) {
        this.title = title;
        this.id = id;
    }

    public static String[] titles() {
        ControlStylePreset[] presets = values();
        String[] titles = new String[presets.length];
        for (int i = 0; i < presets.length; i++) titles[i] = presets[i].title;
        return titles;
    }

    public static ControlStylePreset parse(String name) {
        if (name == null || name.isEmpty()) return GLASS;
        try {
            return valueOf(name.trim().toUpperCase().replace(" ", "_").replace("-", "_"));
        } catch (IllegalArgumentException e) {
            for (ControlStylePreset p : values()) {
                if (p.name().equalsIgnoreCase(name) || p.title.equalsIgnoreCase(name)) return p;
            }
            return GLASS;
        }
    }
}
