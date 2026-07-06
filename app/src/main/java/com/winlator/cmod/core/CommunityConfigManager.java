package com.winlator.cmod.core;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.concurrent.Executors;

public class CommunityConfigManager {
    public static final String PROXY_URL = "https://win-mali-proxy.teja44951.workers.dev/";

    private static byte[] getInternalId() {
        byte[] a = {0x69, 0x6E, 0x73, 0x50, 0x49, 0x52, 0x49, 0x54, 0x6D, 0x4F, 0x44, 0x45, 0x12, 0x14};
        for (int i = 0; i < a.length; i++) a[i] = (byte) (a[i] ^ 32);
        return a;
    }

    private static String calculateHMAC(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(getInternalId(), "HmacSHA256");
            mac.init(secretKey);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public interface ConfigListCallback {
        void onResponse(JSONArray configs, String maintenanceMessage);
    }

    public static void fetchGameList(ConfigListCallback callback) {
        // Fetch through proxy to bypass GitHub Raw cache and avoid API rate limits
        String url = PROXY_URL + "?path=games.json";
        HttpUtils.download(url, data -> {
            try {
                if (data != null) {
                    if (data.startsWith("{")) {
                        JSONObject root = new JSONObject(data);
                        if (root.optBoolean("maintenance", false)) {
                            callback.onResponse(null, root.optString("message", "System under maintenance."));
                            return;
                        }
                    }
                    callback.onResponse(new JSONArray(data), null);
                } else callback.onResponse(null, null);
            } catch (Exception e) {
                callback.onResponse(null, null);
            }
        });
    }

    public static void fetchConfigsForGame(String gameName, ConfigListCallback callback) {
        String url = PROXY_URL + "?path=index.json";
        HttpUtils.download(url, data -> {
            try {
                if (data != null) {
                    if (data.startsWith("{")) {
                        JSONObject root = new JSONObject(data);
                        if (root.optBoolean("maintenance", false)) {
                            callback.onResponse(null, root.optString("message", "System under maintenance."));
                            return;
                        }

                        JSONArray files = root.optJSONArray(gameName);
                        if (files != null) {
                            JSONArray configs = new JSONArray();
                            for (int i = 0; i < files.length(); i++) {
                                Object item = files.get(i);
                                JSONObject configRef;
                                if (item instanceof JSONObject) {
                                    configRef = (JSONObject) item;
                                } else {
                                    configRef = new JSONObject();
                                    configRef.put("filename", files.getString(i));
                                }
                                configRef.put("game", gameName);
                                configs.put(configRef);
                            }
                            callback.onResponse(configs, null);
                            return;
                        }
                    }
                }
                callback.onResponse(null, null);
            } catch (Exception e) {
                callback.onResponse(null, null);
            }
        });
    }

    public interface ConfigCallback {
        void onResponse(JSONObject config, String maintenanceMessage);
    }

    public static void downloadConfig(String gameName, String filename, ConfigCallback callback) {
        String url = PROXY_URL + "?path=configs/" + gameName + "/" + filename;
        HttpUtils.download(url, data -> {
            try {
                if (data != null) {
                    JSONObject root = new JSONObject(data);
                    if (root.optBoolean("maintenance", false)) {
                        callback.onResponse(null, root.optString("message", "System under maintenance."));
                    } else {
                        callback.onResponse(root, null);
                    }
                } else callback.onResponse(null, null);
            } catch (Exception e) {
                callback.onResponse(null, null);
            }
        });
    }

    public static void uploadConfig(JSONObject config, Callback<String> callback) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                String payload = config.toString();
                String signature = calculateHMAC(payload);

                HttpURLConnection conn = (HttpURLConnection) new URL(PROXY_URL).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-App-Signature", signature);
                conn.setRequestProperty("X-App-Version", "1.0-bionic");
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = payload.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    callback.call(null);
                } else {
                    String error = "HTTP Error " + code;
                    try (InputStream is = conn.getErrorStream()) {
                        if (is != null) {
                            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                                StringBuilder sb = new StringBuilder();
                                String line;
                                while ((line = reader.readLine()) != null) sb.append(line).append("\n");
                                if (sb.length() > 0) error = sb.toString().trim();
                            }
                        }
                    } catch (Exception e) {}
                    callback.call(error);
                }
            } catch (Exception e) {
                callback.call(e.getMessage() != null ? e.getMessage() : "Unknown network error");
            }
        });
    }
}
