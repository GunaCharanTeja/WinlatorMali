package com.winlator.cmod.core;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

public class CommunityConfigManager {
    public static final String PROXY_URL = "https://win-mali-proxy.teja44951.workers.dev/";

    public static void fetchGameList(Callback<JSONArray> callback) {
        // Fetch through proxy to bypass GitHub Raw cache and avoid API rate limits
        String url = PROXY_URL + "?path=games.json";
        HttpUtils.download(url, data -> {
            try {
                callback.call(data != null ? new JSONArray(data) : null);
            } catch (Exception e) {
                callback.call(null);
            }
        });
    }

    public static void fetchConfigsForGame(String gameName, Callback<JSONArray> callback) {
        String url = PROXY_URL + "?path=index.json";
        HttpUtils.download(url, data -> {
            try {
                if (data != null) {
                    JSONObject index = new JSONObject(data);
                    JSONArray files = index.optJSONArray(gameName);
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
                        callback.call(configs);
                        return;
                    }
                }
                callback.call(null);
            } catch (Exception e) {
                callback.call(null);
            }
        });
    }

    public static void downloadConfig(String gameName, String filename, Callback<JSONObject> callback) {
        String url = PROXY_URL + "?path=configs/" + gameName + "/" + filename;
        HttpUtils.download(url, data -> {
            try {
                callback.call(data != null ? new JSONObject(data) : null);
            } catch (Exception e) {
                callback.call(null);
            }
        });
    }

    public static void uploadConfig(JSONObject config, Callback<Boolean> callback) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(PROXY_URL).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = config.toString().getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                int code = conn.getResponseCode();
                callback.call(code >= 200 && code < 300);
            } catch (Exception e) {
                callback.call(false);
            }
        });
    }
}
