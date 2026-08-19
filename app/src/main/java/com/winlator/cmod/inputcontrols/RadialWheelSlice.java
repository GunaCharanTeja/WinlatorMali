package com.winlator.cmod.inputcontrols;

import org.json.JSONException;
import org.json.JSONObject;

public class RadialWheelSlice {
    public String label;
    public Binding binding;

    public RadialWheelSlice() {
        this.label = "";
        this.binding = Binding.NONE;
    }

    public RadialWheelSlice(String label, Binding binding) {
        this.label = label;
        this.binding = binding;
    }

    public JSONObject toJSONObject() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("label", label != null ? label : "");
            obj.put("binding", binding != null ? binding.name() : Binding.NONE.name());
            return obj;
        } catch (JSONException e) {
            return null;
        }
    }

    public static RadialWheelSlice fromJSON(JSONObject obj) {
        RadialWheelSlice slice = new RadialWheelSlice();
        try {
            slice.label = obj.optString("label", "");
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