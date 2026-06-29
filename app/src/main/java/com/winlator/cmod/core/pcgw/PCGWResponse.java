package com.winlator.cmod.core.pcgw;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class PCGWResponse {
    @SerializedName("cargoquery")
    public List<CargoItem> cargoquery;

    public static class CargoItem {
        @SerializedName("title")
        public GameTitle title;
    }

    public static class GameTitle {
        @SerializedName("GameTitle")
        public String gameTitle;
    }
}
