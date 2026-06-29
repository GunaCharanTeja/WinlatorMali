package com.winlator.cmod.core.pcgw;

import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Query;

public interface PCGamingWikiAPI {
    @GET("api.php")
    Call<PCGWResponse> searchByExecutable(
            @Query("action") String action,
            @Query("tables") String tables,
            @Query("fields") String fields,
            @Query("where") String where,
            @Query("format") String format
    );
}
