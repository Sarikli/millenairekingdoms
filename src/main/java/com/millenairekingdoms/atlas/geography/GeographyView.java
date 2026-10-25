package com.millenairekingdoms.atlas.geography;

import com.millenairekingdoms.history.ChronicleViews;
import com.millenairekingdoms.network.ChronicleRequestPayload;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;

/** Only the connection's current dimension and observed geography. A client never supplies scan coordinates. */
public final class GeographyView {
    private GeographyView(){}
    public static CompoundTag build(ServerPlayer player,ChronicleRequestPayload request){
        var view=ChronicleViews.header(player,request);var page=GeographyService.store(player).query(request.fromDay(),request.toDay());
        view.putLong("watermark",page.watermark());view.putLong("nextRevision",page.next());view.putBoolean("more",page.more());view.putBoolean("reset",page.reset());
        var list=new ListTag();for(var tile:page.tiles()){var tag=new CompoundTag();tag.putInt("x",tile.position().x());tag.putInt("z",tile.position().z());tag.putLong("revision",tile.revision());tag.putByteArray("cells",tile.cells());list.add(tag);}view.put("geography",list);return view;
    }
}
