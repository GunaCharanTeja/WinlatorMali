package com.winlator.cmod.core.steamgrid;
import com.google.gson.annotations.SerializedName;
import java.util.List;

public class SteamGridHeroesResponse {
    @SerializedName("success")
    public boolean success;
    @SerializedName("data")
    public List<HeroData> data;

    public static class HeroData {
        @SerializedName("id")
        public int id;
        @SerializedName("url")
        public String url;
        @SerializedName("thumb")
        public String thumb;
    }
}
