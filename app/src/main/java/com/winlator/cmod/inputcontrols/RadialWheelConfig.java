package com.winlator.cmod.inputcontrols;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class RadialWheelConfig {
    public static final int MAX_SLICES = 8;

    public int id;
    public String name;
    public Binding triggerBinding;
    public List<RadialWheelSlice> slices;

    public RadialWheelConfig() {
        this.name = "Wheel";
        this.triggerBinding = Binding.NONE;
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
            obj.put("name", name != null ? name : "Wheel");
            obj.put("triggerBinding", triggerBinding != null ? triggerBinding.name() : Binding.NONE.name());
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
            cfg.name = obj.optString("name", "Wheel");
            String triggerStr = obj.optString("triggerBinding", "NONE");
            try {
                cfg.triggerBinding = Binding.valueOf(triggerStr);
            } catch (IllegalArgumentException e) {
                cfg.triggerBinding = Binding.NONE;
            }
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
}