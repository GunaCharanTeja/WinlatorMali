package com.winlator.cmod.core.steamgrid;
import com.google.gson.annotations.SerializedName;
import java.util.List;

public class SteamGridSearchResponse {
    @SerializedName("success")
    public boolean success;
    @SerializedName("data")
    public List<GameData> data;

    public static class GameData {
        @SerializedName("id")
        public int id;
        @SerializedName("name")
        public String name;
    }
}
