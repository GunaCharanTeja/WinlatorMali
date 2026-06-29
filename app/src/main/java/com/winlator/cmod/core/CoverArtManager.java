package com.winlator.cmod.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.core.steamgrid.SteamGridDBApi;
import com.winlator.cmod.core.steamgrid.SteamGridGridsResponse;
import com.winlator.cmod.core.steamgrid.SteamGridSearchResponse;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class CoverArtManager {
    private static final String BASE_URL = "https://www.steamgriddb.com/api/v2/";
    private static final String DEFAULT_API_KEY = "0324c52513634547a7b32d6d323635d0";
    private static Retrofit retrofit;
    private static final java.util.concurrent.ExecutorService executorService = Executors.newSingleThreadExecutor();

    public enum ErrorReason {
        NETWORK_UNAVAILABLE,
        NOT_FOUND,
        UNAUTHORIZED,
        RATE_LIMITED,
        UNKNOWN
    }

    public interface DownloadCallback {
        void onCompleted(Bitmap bitmap);
        void onFailed(ErrorReason reason);
    }

    public interface GridOptionsCallback {
        void onOptionsAvailable(java.util.List<SteamGridGridsResponse.GridData> options);
        void onFailed(ErrorReason reason);
    }

    public static synchronized Retrofit getRetrofit() {
        if (retrofit == null) {
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                    .writeTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                    .build();

            retrofit = new Retrofit.Builder()
                    .baseUrl(BASE_URL)
                    .client(client)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build();
        }
        return retrofit;
    }

    private static final String PCGW_BASE_URL = "https://www.pcgamingwiki.com/w/";
    private static Retrofit pcgwRetrofit;

    public static synchronized Retrofit getPCGWRetrofit() {
        if (pcgwRetrofit == null) {
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                    .addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
                            .header("User-Agent", "Winlator/7.0.0 (https://github.com/winlator; contact@winlator.com)")
                            .build()))
                    .build();

            pcgwRetrofit = new Retrofit.Builder()
                    .baseUrl(PCGW_BASE_URL)
                    .client(client)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build();
        }
        return pcgwRetrofit;
    }

    private static final String STEAM_STORE_URL = "https://store.steampowered.com/";
    private static Retrofit steamRetrofit;

    public static synchronized Retrofit getSteamRetrofit() {
        if (steamRetrofit == null) {
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                    .build();

            steamRetrofit = new Retrofit.Builder()
                    .baseUrl(STEAM_STORE_URL)
                    .client(client)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build();
        }
        return steamRetrofit;
    }

    public static void fetchCoverArtOptions(Context context, Shortcut shortcut, String searchQuery, GridOptionsCallback callback) {
        if (context == null || shortcut == null) return;

        if (!AppUtils.isNetworkAvailable(context)) {
            if (callback != null) callback.onFailed(ErrorReason.NETWORK_UNAVAILABLE);
            return;
        }

        String tempApiKey = DEFAULT_API_KEY;
        try {
            if (PreferenceManager.getDefaultSharedPreferences(context).getBoolean("enable_custom_api_key", false)) {
                String customKey = PreferenceManager.getDefaultSharedPreferences(context).getString("custom_api_key", "");
                if (!customKey.isEmpty()) tempApiKey = customKey;
            }
        } catch (Exception e) {}

        final String apiKey = tempApiKey;
        SteamGridDBApi api = getRetrofit().create(SteamGridDBApi.class);

        String searchName = (searchQuery != null && !searchQuery.isEmpty()) ? searchQuery : 
                           (shortcut.name != null ? shortcut.name.replaceAll("\\(.*?\\)", "").replaceAll("\\[.*?\\]", "").trim() : "");

        if (searchName.isEmpty()) {
            if (callback != null) callback.onFailed(ErrorReason.NOT_FOUND);
            return;
        }

        api.searchGame("Bearer " + apiKey, searchName).enqueue(new Callback<SteamGridSearchResponse>() {
            @Override
            public void onResponse(Call<SteamGridSearchResponse> call, Response<SteamGridSearchResponse> response) {
                if (response.isSuccessful() && response.body() != null && response.body().data != null && !response.body().data.isEmpty()) {
                    int gameId = response.body().data.get(0).id;
                    api.getGridsByGameId("Bearer " + apiKey, gameId, "no_logo,alternate,material,blurred", "600x900,342x482", "static").enqueue(new Callback<SteamGridGridsResponse>() {
                        @Override
                        public void onResponse(Call<SteamGridGridsResponse> call, Response<SteamGridGridsResponse> response) {
                            if (response.isSuccessful() && response.body() != null && response.body().data != null && !response.body().data.isEmpty()) {
                                if (callback != null) callback.onOptionsAvailable(response.body().data);
                            } else {
                                if (response.code() == 429) {
                                    if (callback != null) callback.onFailed(ErrorReason.RATE_LIMITED);
                                    return;
                                }
                                api.getGridsByGameId("Bearer " + apiKey, gameId, null, null, "static").enqueue(new Callback<SteamGridGridsResponse>() {
                                    @Override
                                    public void onResponse(Call<SteamGridGridsResponse> call, Response<SteamGridGridsResponse> response) {
                                        if (response.isSuccessful() && response.body() != null && response.body().data != null && !response.body().data.isEmpty()) {
                                            if (callback != null) callback.onOptionsAvailable(response.body().data);
                                        } else if (callback != null) {
                                            if (response.code() == 429) callback.onFailed(ErrorReason.RATE_LIMITED);
                                            else callback.onFailed(ErrorReason.NOT_FOUND);
                                        }
                                    }

                                    @Override
                                    public void onFailure(Call<SteamGridGridsResponse> call, Throwable t) {
                                        if (callback != null) callback.onFailed(ErrorReason.UNKNOWN);
                                    }
                                });
                            }
                        }

                        @Override
                        public void onFailure(Call<SteamGridGridsResponse> call, Throwable t) {
                            if (callback != null) callback.onFailed(ErrorReason.UNKNOWN);
                        }
                    });
                } else if (callback != null) {
                    if (response.code() == 401) callback.onFailed(ErrorReason.UNAUTHORIZED);
                    else if (response.code() == 429) callback.onFailed(ErrorReason.RATE_LIMITED);
                    else callback.onFailed(ErrorReason.NOT_FOUND);
                }
            }

            @Override
            public void onFailure(Call<SteamGridSearchResponse> call, Throwable t) {
                if (callback != null) callback.onFailed(ErrorReason.NETWORK_UNAVAILABLE);
            }
        });
    }

    public static void downloadSelectedCoverArt(String url, Shortcut shortcut, DownloadCallback callback) {
        downloadCoverArt(url, shortcut, callback);
    }

    private static void downloadCoverArt(String url, Shortcut shortcut, DownloadCallback callback) {
        executorService.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(20000);
                connection.connect();
                
                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    try (InputStream input = connection.getInputStream()) {
                        Bitmap bitmap = BitmapFactory.decodeStream(input);
                        if (bitmap != null) {
                            shortcut.saveCustomCoverArt(bitmap);
                            if (callback != null) callback.onCompleted(bitmap);
                        } else if (callback != null) callback.onFailed(ErrorReason.UNKNOWN);
                    }
                } else if (callback != null) {
                    callback.onFailed(responseCode == 429 ? ErrorReason.RATE_LIMITED : ErrorReason.UNKNOWN);
                }
            } catch (Exception e) {
                Log.e("CoverArtManager", "Failed to download cover art", e);
                if (callback != null) {
                    callback.onFailed(e instanceof java.io.IOException ? ErrorReason.NETWORK_UNAVAILABLE : ErrorReason.UNKNOWN);
                }
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }
}
