package com.winlator.cmod.inputcontrols;

public enum ControlStylePreset {
    WINLATOR_MALI("Winlator Mali (Dark Glass)", 0),
    XBOX("Xbox Edition", 1),
    PLAYSTATION("PlayStation Edition", 2),
    CYBERPUNK("Cyberpunk Neon", 3),
    RETRO_ARCADE("Retro Arcade (90s)", 4),
    STEALTH("Stealth Minimal", 5);

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
        if (name == null || name.isEmpty()) return WINLATOR_MALI;
        try {
            return valueOf(name.trim().toUpperCase().replace(" ", "_").replace("-", "_"));
        } catch (IllegalArgumentException e) {
            for (ControlStylePreset p : values()) {
                if (p.name().equalsIgnoreCase(name) || p.title.equalsIgnoreCase(name)) return p;
            }
            return WINLATOR_MALI;
        }
    }
}