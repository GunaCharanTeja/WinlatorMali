package com.winlator.cmod.inputcontrols;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class RadialWheelConfig {
    public static final int MAX_SLICES = 8;

    public int id;
    public boolean enabled = true;
    public String name;
    public Binding triggerBinding;
    public Binding triggerBinding2;
    public float iconScale;
    public List<RadialWheelSlice> slices;

    public RadialWheelConfig() {
        this.enabled = true;
        this.name = "Wheel";
        this.triggerBinding = Binding.NONE;
        this.triggerBinding2 = Binding.NONE;
        this.iconScale = 1.0f;
        this.slices = new ArrayList<>();
        for (int i = 0; i < MAX_SLICES; i++) {
            slices.add(new RadialWheelSlice());
        }
    }

    public RadialWheelConfig(int id) {
        this();
        this.id = id;
    }

    public JSONObject toJSONObject() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("id", id);
            obj.put("enabled", enabled);
            obj.put("name", name != null ? name : "Wheel");
            obj.put("triggerBinding", triggerBinding != null ? triggerBinding.name() : Binding.NONE.name());
            if (triggerBinding2 != null && triggerBinding2 != Binding.NONE) obj.put("triggerBinding2", triggerBinding2.name());
            if (iconScale != 1.0f && iconScale > 0) obj.put("iconScale", Float.valueOf(iconScale));
            JSONArray slicesArray = new JSONArray();
            for (RadialWheelSlice slice : slices) {
                JSONObject sj = slice.toJSONObject();
                if (sj != null) slicesArray.put(sj);
            }
            obj.put("slices", slicesArray);
            return obj;
        } catch (JSONException e) {
            return null;
        }
    }

    public static RadialWheelConfig fromJSON(JSONObject obj) {
        RadialWheelConfig cfg = new RadialWheelConfig();
        try {
            cfg.id = obj.optInt("id", 0);
            cfg.enabled = obj.optBoolean("enabled", true);
            cfg.name = obj.optString("name", "Wheel");
            cfg.iconScale = (float) obj.optDouble("iconScale", 1.0);
            
            cfg.triggerBinding = parseBinding(obj.optString("triggerBinding", "NONE"));
            cfg.triggerBinding2 = parseBinding(obj.optString("triggerBinding2", "NONE"));
            
            cfg.slices.clear();
            if (obj.has("slices")) {
                JSONArray slicesArray = obj.getJSONArray("slices");
                for (int i = 0; i < slicesArray.length() && i < MAX_SLICES; i++) {
                    cfg.slices.add(RadialWheelSlice.fromJSON(slicesArray.getJSONObject(i)));
                }
            }
            // Pad to MAX_SLICES if needed
            while (cfg.slices.size() < MAX_SLICES) {
                cfg.slices.add(new RadialWheelSlice());
            }
        } catch (JSONException e) {
            // use defaults
        }
        return cfg;
    }

    private static Binding parseBinding(String name) {
        if (name == null || name.isEmpty() || name.equals("NONE")) return Binding.NONE;
        try {
            return Binding.valueOf(name);
        } catch (IllegalArgumentException e) {
            return Binding.NONE;
        }
    }

    public static ArrayList<RadialWheelConfig> loadGlobal(Context context) {
        ArrayList<RadialWheelConfig> list = new ArrayList<>();
        if (context == null) return list;
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        String json = sp.getString("global_radial_wheels", null);
        if (json != null && !json.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    RadialWheelConfig cfg = fromJSON(arr.getJSONObject(i));
                    if (cfg != null) list.add(cfg);
                }
            } catch (Exception e) {}
        }
        if (list.isEmpty()) {
            RadialWheelConfig defaultWheel = new RadialWheelConfig(1);
            defaultWheel.name = "Quick Actions";
            defaultWheel.triggerBinding = Binding.GAMEPAD_BUTTON_L2;
            list.add(defaultWheel);
        }
        return list;
    }

    public static void saveGlobal(Context context, List<RadialWheelConfig> wheels) {
        if (context == null || wheels == null) return;
        JSONArray arr = new JSONArray();
        for (RadialWheelConfig cfg : wheels) {
            JSONObject obj = cfg.toJSONObject();
            if (obj != null) arr.put(obj);
        }
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        sp.edit().putString("global_radial_wheels", arr.toString()).apply();
    }
}