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
        var level=player.serverLevel();var data=KingdomSavedData.get(level);var known=new HashSet<UUID>();var ruled=data.ruledBy(player.getUUID());if(ruled!=null)known.addAll(data.members(ruled));
        for(var village:VillageSavedData.get(level).getVillageManager().getAllVillages())if(player.getUUID().equals(village.getOwnerUUID())
                || level.isLoaded(village.getCenter()) && Math.hypot(player.getX()-village.getCenter().getX(),player.getZ()-village.getCenter().getZ())<=512)known.add(village.getId().uuid());
        return Set.copyOf(known);
    }
    public static HistoryEvent redact(HistoryEvent event,Set<UUID> known,boolean economicRights){
        var places=event.places().stream().map(p->known.contains(p.id())?p:new HistoryEvent.Place(p.id(),p.name(),false,0,0)).toList();
        var facts=new java.util.HashMap<>(event.facts());if(!economicRights){facts.remove("fee");facts.remove("quantity");}
        return new HistoryEvent(event.id(),event.kind(),event.day(),event.gameTime(),event.dimension(),event.source(),event.realms(),places,event.actor(),event.actorName(),event.reason(),event.outcome(),facts);
    }
}
