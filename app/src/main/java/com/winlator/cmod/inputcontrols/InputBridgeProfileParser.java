package com.winlator.cmod.inputcontrols;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.core.FileUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Phase 5: Universal Profile Translator & Importer.
 * Supports on-the-fly translation of InputBridge (.ibp), Winlator Vanilla (.icp),
 * and raw layout JSON files into native Winlator profiles with embedded custom icons.
 */
public class InputBridgeProfileParser {
    private static final String TAG = "InputBridgeParser";

    // Map common Windows Virtual Key Codes to Winlator Bindings
    private static final Map<Integer, Binding> VK_MAP = new HashMap<>();
    static {
        VK_MAP.put(0x01, Binding.MOUSE_LEFT_BUTTON);
        VK_MAP.put(0x02, Binding.MOUSE_RIGHT_BUTTON);
        VK_MAP.put(0x04, Binding.MOUSE_MIDDLE_BUTTON);
        VK_MAP.put(0x08, Binding.KEY_BKSP);
        VK_MAP.put(0x09, Binding.KEY_TAB);
        VK_MAP.put(0x0D, Binding.KEY_ENTER);
        VK_MAP.put(0x10, Binding.KEY_SHIFT_L);
        VK_MAP.put(0x11, Binding.KEY_CTRL_L);
        VK_MAP.put(0x12, Binding.KEY_ALT_L);
        VK_MAP.put(0x14, Binding.KEY_CAPS_LOCK);
        VK_MAP.put(0x1B, Binding.KEY_ESC);
        VK_MAP.put(0x20, Binding.KEY_SPACE);
        VK_MAP.put(0x21, Binding.KEY_PG_UP);
        VK_MAP.put(0x22, Binding.KEY_PG_DOWN);
        VK_MAP.put(0x23, Binding.KEY_END);
        VK_MAP.put(0x24, Binding.KEY_HOME);
        VK_MAP.put(0x25, Binding.KEY_LEFT);
        VK_MAP.put(0x26, Binding.KEY_UP);
        VK_MAP.put(0x27, Binding.KEY_RIGHT);
        VK_MAP.put(0x28, Binding.KEY_DOWN);
        VK_MAP.put(0x2D, Binding.KEY_INSERT);
        VK_MAP.put(0x2E, Binding.KEY_DEL);

        // Digits 0-9 (0x30 - 0x39)
        VK_MAP.put(0x30, Binding.KEY_0); VK_MAP.put(0x31, Binding.KEY_1);
        VK_MAP.put(0x32, Binding.KEY_2); VK_MAP.put(0x33, Binding.KEY_3);
        VK_MAP.put(0x34, Binding.KEY_4); VK_MAP.put(0x35, Binding.KEY_5);
        VK_MAP.put(0x36, Binding.KEY_6); VK_MAP.put(0x37, Binding.KEY_7);
        VK_MAP.put(0x38, Binding.KEY_8); VK_MAP.put(0x39, Binding.KEY_9);

        // Letters A-Z (0x41 - 0x5A)
        VK_MAP.put(0x41, Binding.KEY_A); VK_MAP.put(0x42, Binding.KEY_B);
        VK_MAP.put(0x43, Binding.KEY_C); VK_MAP.put(0x44, Binding.KEY_D);
        VK_MAP.put(0x45, Binding.KEY_E); VK_MAP.put(0x46, Binding.KEY_F);
        VK_MAP.put(0x47, Binding.KEY_G); VK_MAP.put(0x48, Binding.KEY_H);
        VK_MAP.put(0x49, Binding.KEY_I); VK_MAP.put(0x4A, Binding.KEY_J);
        VK_MAP.put(0x4B, Binding.KEY_K); VK_MAP.put(0x4C, Binding.KEY_L);
        VK_MAP.put(0x4D, Binding.KEY_M); VK_MAP.put(0x4E, Binding.KEY_N);
        VK_MAP.put(0x4F, Binding.KEY_O); VK_MAP.put(0x50, Binding.KEY_P);
        VK_MAP.put(0x51, Binding.KEY_Q); VK_MAP.put(0x52, Binding.KEY_R);
        VK_MAP.put(0x53, Binding.KEY_S); VK_MAP.put(0x54, Binding.KEY_T);
        VK_MAP.put(0x55, Binding.KEY_U); VK_MAP.put(0x56, Binding.KEY_V);
        VK_MAP.put(0x57, Binding.KEY_W); VK_MAP.put(0x58, Binding.KEY_X);
        VK_MAP.put(0x59, Binding.KEY_Y); VK_MAP.put(0x5A, Binding.KEY_Z);

        // Function Keys F1-F12 (0x70 - 0x7B)
        VK_MAP.put(0x70, Binding.KEY_F1); VK_MAP.put(0x71, Binding.KEY_F2);
        VK_MAP.put(0x72, Binding.KEY_F3); VK_MAP.put(0x73, Binding.KEY_F4);
        VK_MAP.put(0x74, Binding.KEY_F5); VK_MAP.put(0x75, Binding.KEY_F6);
        VK_MAP.put(0x76, Binding.KEY_F7); VK_MAP.put(0x77, Binding.KEY_F8);
        VK_MAP.put(0x78, Binding.KEY_F9); VK_MAP.put(0x79, Binding.KEY_F10);
        VK_MAP.put(0x7A, Binding.KEY_F11); VK_MAP.put(0x7B, Binding.KEY_F12);
    }

    /**
     * Import any supported profile file (.icp, .ibp, .json).
     * @return Transformed native Winlator profile JSONObject.
     */
    public static JSONObject parseProfile(Context context, String jsonContent, String fallbackName) {
        if (jsonContent == null || jsonContent.isEmpty()) return null;

        try {
            JSONObject root = new JSONObject(jsonContent);

            // Check if it's already a native Winlator profile
            if (root.has("elements")) {
                // Extract any embedded custom icons
                extractEmbeddedIcons(context, root);
                return root;
            }

            // Check if it's an InputBridge (.ibp) profile
            if (root.has("buttons") || root.has("sticks") || root.has("crosses")) {
                return translateInputBridge(root, fallbackName);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse profile JSON", e);
        }
        return null;
    }

    /**
     * Translates an InputBridge profile into Winlator ControlsProfile JSON.
     */
    private static JSONObject translateInputBridge(JSONObject ibRoot, String profileName) {
        try {
            JSONObject out = new JSONObject();
            out.put("id", 0);
            out.put("name", profileName != null ? profileName : "Imported Profile");
            out.put("cursorSpeed", 1.0);

            JSONArray elements = new JSONArray();

            // ── 1. Buttons ──
            if (ibRoot.has("buttons")) {
                JSONArray buttons = ibRoot.getJSONArray("buttons");
                for (int i = 0; i < buttons.length(); i++) {
                    JSONObject btn = buttons.getJSONObject(i);
                    JSONObject el = new JSONObject();
                    el.put("type", "BUTTON");
                    el.put("shape", mapShape(btn.optString("shape", "circle")));
                    el.put("toggleSwitch", btn.optBoolean("toggle", false));

                    // Normalize coordinates (InputBridge uses 0.0-1.0 or pixel coordinates)
                    double x = btn.optDouble("x", 0.5);
                    double y = btn.optDouble("y", 0.5);
                    if (x > 1.0) x /= 100.0; // convert percentage if needed
                    if (y > 1.0) y /= 100.0;
                    el.put("x", Math.max(0.0, Math.min(1.0, x)));
                    el.put("y", Math.max(0.0, Math.min(1.0, y)));
                    el.put("scale", Math.max(0.5, Math.min(2.5, btn.optDouble("size", 1.0))));
                    el.put("text", btn.optString("name", ""));
                    el.put("iconId", 0);

                    // Map bindings
                    JSONArray bindings = new JSONArray();
                    int code = btn.optInt("code", 0);
                    Binding b = mapKeycode(code);
                    bindings.put(b.name());
                    bindings.put(Binding.NONE.name());
                    bindings.put(Binding.NONE.name());
                    bindings.put(Binding.NONE.name());
                    el.put("binding", bindings);

                    elements.put(el);
                }
            }

            // ── 2. Sticks / Joysticks ──
            if (ibRoot.has("sticks")) {
                JSONArray sticks = ibRoot.getJSONArray("sticks");
                for (int i = 0; i < sticks.length(); i++) {
                    JSONObject stick = sticks.getJSONObject(i);
                    JSONObject el = new JSONObject();
                    el.put("type", "STICK");
                    el.put("shape", "CIRCLE");
                    el.put("toggleSwitch", false);

                    double x = stick.optDouble("x", 0.2);
                    double y = stick.optDouble("y", 0.7);
                    if (x > 1.0) x /= 100.0;
                    if (y > 1.0) y /= 100.0;
                    el.put("x", x);
                    el.put("y", y);
                    el.put("scale", 1.2);
                    el.put("text", "");
                    el.put("iconId", 0);

                    JSONArray bindings = new JSONArray();
                    bindings.put(Binding.KEY_W.name());
                    bindings.put(Binding.KEY_D.name());
                    bindings.put(Binding.KEY_S.name());
                    bindings.put(Binding.KEY_A.name());
                    el.put("binding", bindings);

                    elements.put(el);
                }
            }

            // ── 3. D-Pads (Crosses) ──
            if (ibRoot.has("crosses")) {
                JSONArray crosses = ibRoot.getJSONArray("crosses");
                for (int i = 0; i < crosses.length(); i++) {
                    JSONObject cross = crosses.getJSONObject(i);
                    JSONObject el = new JSONObject();
                    el.put("type", "D_PAD");
                    el.put("shape", "CIRCLE");
                    el.put("toggleSwitch", false);

                    double x = cross.optDouble("x", 0.2);
                    double y = cross.optDouble("y", 0.5);
                    if (x > 1.0) x /= 100.0;
                    if (y > 1.0) y /= 100.0;
                    el.put("x", x);
                    el.put("y", y);
                    el.put("scale", 1.0);
                    el.put("text", "");
                    el.put("iconId", 0);

                    JSONArray bindings = new JSONArray();
                    bindings.put(Binding.GAMEPAD_DPAD_UP.name());
                    bindings.put(Binding.GAMEPAD_DPAD_RIGHT.name());
                    bindings.put(Binding.GAMEPAD_DPAD_DOWN.name());
                    bindings.put(Binding.GAMEPAD_DPAD_LEFT.name());
                    el.put("binding", bindings);

                    elements.put(el);
                }
            }

            out.put("elements", elements);
            Log.d(TAG, "Translated InputBridge profile with " + elements.length() + " elements");
            return out;
        } catch (JSONException e) {
            Log.e(TAG, "Error translating InputBridge profile", e);
            return null;
        }
    }

    private static String mapShape(String shape) {
        if (shape == null) return "CIRCLE";
        return switch (shape.toLowerCase()) {
            case "rect", "rectangle" -> "RECT";
            case "round_rect", "roundrect" -> "ROUND_RECT";
            case "square" -> "SQUARE";
            default -> "CIRCLE";
        };
    }

    private static Binding mapKeycode(int vkCode) {
        Binding b = VK_MAP.get(vkCode);
        return b != null ? b : Binding.NONE;
    }

    /**
     * Extract any custom icons embedded as Base64 in the profile JSON.
     */
    private static void extractEmbeddedIcons(Context context, JSONObject profileJSON) {
        if (!profileJSON.has("embeddedIcons")) return;
        CustomIconManager iconManager = CustomIconManager.getInstance(context);
        try {
            JSONArray icons = profileJSON.getJSONArray("embeddedIcons");
            for (int i = 0; i < icons.length(); i++) {
                String base64 = icons.getString(i);
                iconManager.decodeAndSaveBase64(base64);
            }
            Log.d(TAG, "Extracted " + icons.length() + " embedded icons from profile");
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract embedded icons", e);
        }
    }
}