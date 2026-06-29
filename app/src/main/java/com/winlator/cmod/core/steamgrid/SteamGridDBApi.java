package com.winlator.cmod.core.steamgrid;

import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.Path;
import retrofit2.http.Query;

public interface SteamGridDBApi {
    @GET("search/autocomplete/{term}")
    Call<SteamGridSearchResponse> searchGame(
            @Header("Authorization") String auth,
            @Path("term") String term
    );

    @GET("games/id/{id}")
    Call<SteamGridGameDetailsResponse> getGameDetails(
            @Header("Authorization") String auth,
            @Path("id") int id
    );

    @GET("grids/game/{id}")
    Call<SteamGridGridsResponse> getGridsByGameId(
            @Header("Authorization") String auth,
            @Path("id") int id,
            @Query("styles") String styles,
            @Query("dimensions") String dimensions,
            @Query("types") String types
    );
}
