package com.winlator.cmod.core.steamgrid;

import com.google.gson.annotations.SerializedName;

public class SteamGridGameDetailsResponse {
    @SerializedName("success")
    public boolean success;
    @SerializedName("data")
    public GameDetails data;

    public static class GameDetails {
        @SerializedName("id")
        public int id;
        @SerializedName("name")
        public String name;
        @SerializedName("external_ids")
        public ExternalIds externalIds;
    }

    public static class ExternalIds {
        @SerializedName("steam")
        public SteamInfo steam;
    }

    public static class SteamInfo {
        @SerializedName("id")
        public String id;
    }
}