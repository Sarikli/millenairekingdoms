package com.millenairekingdoms.atlas.geography;

import com.millenairekingdoms.history.ChronicleViews;
import com.millenairekingdoms.network.ChronicleRequestPayload;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;

/** Compatibility response for legacy clients. Ocean surveying/cache access is disabled. */
public final class GeographyView {
    private GeographyView(){}
    public static CompoundTag build(ServerPlayer player,ChronicleRequestPayload request){
        var view=ChronicleViews.header(player,request);view.putString("error","disabled");view.putBoolean("disabled",true);return view;
    }
}
