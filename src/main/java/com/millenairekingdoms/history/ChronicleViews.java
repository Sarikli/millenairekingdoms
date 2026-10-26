package com.millenairekingdoms.history;

import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.network.ChronicleRequestPayload;
import com.millenairekingdoms.network.ChronicleViewPayload;
import com.mojang.logging.LogUtils;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/** Bounded current-dimension authoritative views. Read-only history never grants gameplay authority. */
public final class ChronicleViews {
    private static final Map<UUID,Long> LAST=new HashMap<>();
    private ChronicleViews(){}
    public static void handle(ServerPlayer player,ChronicleRequestPayload request){
        PacketDistributor.sendToPlayer(player,new ChronicleViewPayload(respond(player,request)));
    }
    public static CompoundTag respond(ServerPlayer player,ChronicleRequestPayload request){
        long now=player.serverLevel().getGameTime();var last=LAST.get(player.getUUID());
        if(last!=null && now>=last && now-last<4){var retry=header(player,request);retry.putInt("retryAfter",4);return retry;}LAST.put(player.getUUID(),now);
        CompoundTag view;
        try{view=build(player,request);}catch(RuntimeException failure){LogUtils.getLogger().warn("[Kingdoms] Read-only chronicle request unavailable: {}",failure.getMessage());view=header(player,request);view.putString("error","unavailable");}
        return view;
    }
    public static void forget(UUID player){LAST.remove(player);}
    public static CompoundTag header(ServerPlayer player,ChronicleRequestPayload request){var view=new CompoundTag();view.putUUID("session",request.session());view.putLong("serial",request.serial());view.putString("mode",request.mode());view.putString("dimension",player.serverLevel().dimension().location().toString());return view;}
    public static CompoundTag build(ServerPlayer player,ChronicleRequestPayload request){
        if(!player.serverLevel().getServer().isSameThread())throw new IllegalStateException("Chronicles require server thread");
        var view=header(player,request);var level=player.serverLevel();var data=KingdomSavedData.get(level);var store=HistoryHooks.store(level);
        if(request.mode().equals("ATLAS") || request.mode().equals("INFO"))return com.millenairekingdoms.atlas.AtlasViews.build(player,request);
        if(request.mode().equals("REALMS")){
            var names=new HashMap<UUID,HistoryEvent.Realm>();for(var r:store.archives())names.put(r.id(),r);for(var k:data.kingdoms())names.put(k.id(),HistoryHooks.realm(level,k.id()));
            var realms=names.values().stream().filter(r->r.name().toLowerCase(Locale.ROOT).contains(request.search().strip().toLowerCase(Locale.ROOT))).sorted(Comparator.comparing(HistoryEvent.Realm::name).thenComparing(HistoryEvent.Realm::id)).toList();
            int start=0;if(request.cursor().isPresent()){while(start<realms.size() && !realms.get(start).id().equals(request.cursor().get()))start++;if(start==realms.size())throw new IllegalArgumentException("Unknown archive cursor");start++;}
            var list=new ListTag();for(int n=start;n<Math.min(start+64,realms.size());n++){var r=realms.get(n);var tag=new CompoundTag();tag.putUUID("id",r.id());tag.putString("name",r.name());tag.putBoolean("fallen",data.kingdom(r.id())==null || data.kingdom(r.id()).fallen());list.add(tag);}
            view.put("realms",list);if(start+64<realms.size())view.putUUID("next",realms.get(start+63).id());return view;
        }
        if(!request.mode().equals("CHRONICLE"))throw new IllegalArgumentException("Unsupported archive mode");
        var known=HistoryVisibility.known(player);UUID ruled=data.ruledBy(player.getUUID());
        var page=store.query(new HistoryStore.Query(request.kingdom().orElse(null),request.kind().isEmpty()?null:HistoryEvent.Kind.valueOf(request.kind()),request.fromDay(),request.toDay(),request.cursor().orElse(null),HistoryStore.PAGE_SIZE,request.village().orElse(null)));
        var list=new ListTag();for(var e:page.events())list.add(HistoryVisibility.redact(e,known,ruled!=null && e.realms().stream().anyMatch(r->r.id().equals(ruled))).save());view.put("events",list);if(page.next()!=null)view.putUUID("next",page.next());
        request.kingdom().ifPresent(id->{view.putUUID("focus",id);var k=data.kingdom(id);if(k!=null){view.putString("name",k.name());view.putBoolean("fallen",k.fallen());}else store.archives().stream().filter(r->r.id().equals(id)).findFirst().ifPresent(r->{view.putString("name",r.name());view.putBoolean("fallen",true);});
            var foundation=store.query(new HistoryStore.Query(id,HistoryEvent.Kind.FOUNDING,0,Long.MAX_VALUE,null,1));if(!foundation.events().isEmpty())view.putLong("founded",foundation.events().getFirst().day());});
        request.village().ifPresent(id->{var v=com.millenairekingdoms.politics.PoliticsService.village(level,id);if(v!=null)view.putString("villageName",com.millenairekingdoms.politics.PoliticsService.name(v));else page.events().stream().flatMap(e->e.places().stream()).filter(p->p.id().equals(id)).findFirst().ifPresent(p->view.putString("villageName",p.name()));});
        view.putLong("today",Math.max(0,level.getDayTime()/24000));return view;
    }
}
