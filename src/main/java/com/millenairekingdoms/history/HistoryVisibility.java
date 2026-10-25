package com.millenairekingdoms.history;

import com.millenairekingdoms.kingdom.KingdomSavedData;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import org.millenaire.village.VillageSavedData;

/** Server-controlled proximity/current-ruler knowledge. Never trusts client-supplied known coordinates. */
public final class HistoryVisibility {
    private HistoryVisibility(){}
    public static Set<UUID> known(ServerPlayer player){
        return com.millenairekingdoms.atlas.AtlasDiscovery.known(player);
    }
    public static HistoryEvent redact(HistoryEvent event,Set<UUID> known,boolean economicRights){
        var places=event.places().stream().map(p->known.contains(p.id())?p:new HistoryEvent.Place(p.id(),p.name(),false,0,0)).toList();
        var facts=new java.util.HashMap<>(event.facts());if(!economicRights){facts.remove("fee");facts.remove("quantity");}
        return new HistoryEvent(event.id(),event.kind(),event.day(),event.gameTime(),event.dimension(),event.source(),event.realms(),places,event.actor(),event.actorName(),event.reason(),event.outcome(),facts);
    }
}
