package com.winlator.cmod.core.steam;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class SteamSearchResponse {
    @SerializedName("total")
    public int total;

    @SerializedName("items")
    public List<SteamItem> items;

    public static class SteamItem {
        @SerializedName("name")
        public String name;
    }
}
