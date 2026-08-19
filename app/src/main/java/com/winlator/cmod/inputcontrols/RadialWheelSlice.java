package com.winlator.cmod.inputcontrols;

import org.json.JSONException;
import org.json.JSONObject;

public class RadialWheelSlice {
    public String label;
    public Binding binding;
    public int iconId;

    public RadialWheelSlice() {
        this.label = "";
        this.binding = Binding.NONE;
        this.iconId = 0;
    }

    public RadialWheelSlice(String label, Binding binding) {
        this.label = label;
        this.binding = binding;
        this.iconId = 0;
    }

    public RadialWheelSlice(String label, Binding binding, int iconId) {
        this.label = label;
        this.binding = binding;
        this.iconId = iconId;
    }

    public JSONObject toJSONObject() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("label", label != null ? label : "");
            obj.put("binding", binding != null ? binding.name() : Binding.NONE.name());
            obj.put("iconId", iconId);
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
            String bindingStr = obj.optString("binding", "NONE");
            try {
                slice.binding = Binding.valueOf(bindingStr);
            } catch (IllegalArgumentException e) {
                slice.binding = Binding.NONE;
            }
        } catch (Exception e) {
            // use defaults
        }
        return slice;
    }
}