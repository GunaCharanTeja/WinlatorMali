package com.winlator.cmod.inputcontrols;

import org.json.JSONException;
import org.json.JSONObject;

public class RadialWheelSlice {
    public String label;
    public Binding binding;
    public Binding binding2;
    public Binding binding3;
    public int iconId;
    public float iconScale;

    public RadialWheelSlice() {
        this.label = "";
        this.binding = Binding.NONE;
        this.binding2 = Binding.NONE;
        this.binding3 = Binding.NONE;
        this.iconId = 0;
        this.iconScale = 1.0f;
    }

    public RadialWheelSlice(String label, Binding binding) {
        this.label = label;
        this.binding = binding;
        this.binding2 = Binding.NONE;
        this.binding3 = Binding.NONE;
        this.iconId = 0;
        this.iconScale = 1.0f;
    }

    public RadialWheelSlice(String label, Binding binding, int iconId) {
        this.label = label;
        this.binding = binding;
        this.binding2 = Binding.NONE;
        this.binding3 = Binding.NONE;
        this.iconId = iconId;
        this.iconScale = 1.0f;
    }

    public JSONObject toJSONObject() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("label", label != null ? label : "");
            obj.put("binding", binding != null ? binding.name() : Binding.NONE.name());
            if (binding2 != null && binding2 != Binding.NONE) obj.put("binding2", binding2.name());
            if (binding3 != null && binding3 != Binding.NONE) obj.put("binding3", binding3.name());
            obj.put("iconId", iconId);
            if (iconScale != 1.0f && iconScale > 0) obj.put("iconScale", Float.valueOf(iconScale));
            return obj;
        } catch (JSONException e) {
            return null;
        }
    }

    public static RadialWheelSlice fromJSON(JSONObject obj) {
        RadialWheelSlice slice = new RadialWheelSlice();
        try {
            slice.label = obj.optString("label", "");
            slice.iconId = obj.optInt("iconId", 0);
            slice.iconScale = (float) obj.optDouble("iconScale", 1.0);
            
            slice.binding = parseBinding(obj.optString("binding", "NONE"));
            slice.binding2 = parseBinding(obj.optString("binding2", "NONE"));
            slice.binding3 = parseBinding(obj.optString("binding3", "NONE"));
        } catch (Exception e) {
            // use defaults
        }
        return slice;
    }

    private static Binding parseBinding(String name) {
        if (name == null || name.isEmpty() || name.equals("NONE")) return Binding.NONE;
        try {
            return Binding.valueOf(name);
        } catch (IllegalArgumentException e) {
            return Binding.NONE;
        }
    }
}