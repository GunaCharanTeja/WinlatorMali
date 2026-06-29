package com.winlator.cmod.core.steamgrid;
import com.google.gson.annotations.SerializedName;
import java.util.List;

public class SteamGridGridsResponse {
    @SerializedName("success")
    public boolean success;
    @SerializedName("data")
    public List<GridData> data;

    public static class GridData {
        @SerializedName("id")
        public int id;
        @SerializedName("url")
        public String url;
        @SerializedName("thumb")
        public String thumb;
    }
}
