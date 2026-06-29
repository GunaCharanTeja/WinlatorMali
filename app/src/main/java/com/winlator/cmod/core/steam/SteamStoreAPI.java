package com.winlator.cmod.core.steam;

import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Query;

public interface SteamStoreAPI {
    @GET("api/storesearch/")
    Call<SteamSearchResponse> search(
            @Query("term") String term,
            @Query("l") String language,
            @Query("cc") String country
    );
}
