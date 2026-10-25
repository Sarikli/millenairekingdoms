package com.millenairekingdoms.atlas;

import com.millenairekingdoms.history.*;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.network.ChronicleRequestPayload;
import com.millenairekingdoms.politics.PoliticsService;
import com.millenairekingdoms.personality.KingdomPersonality;
import com.millenairekingdoms.economy.EconomyService;
import com.millenairekingdoms.economy.TreasuryService;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import org.millenaire.village.VillageSavedData;

/** Registered current-dimension metadata, visible coordinates and separately authorized economic information only. */
public final class AtlasViews {
    public static final int PAGE_SIZE=128;
    private AtlasViews(){}
    public static CompoundTag build(ServerPlayer player,ChronicleRequestPayload request){
        var level=player.serverLevel();var data=KingdomSavedData.get(level);var view=ChronicleViews.header(player,request);var known=AtlasDiscovery.known(player);
        if(request.mode().equals("INFO")){var id=request.kingdom().orElseThrow(()->new IllegalArgumentException("Select an atlas kingdom"));view.putUUID("focus",id);view.put("info",info(player,id,known,request.cursor().orElse(null)));return view;}
        if(!request.mode().equals("ATLAS"))throw new IllegalArgumentException("Invalid atlas request");
        var sites=VillageSavedData.get(level).getVillageManager().getAllVillages().stream().filter(v->known.contains(v.getId().uuid())).sorted(Comparator.comparing(v->v.getId().uuid())).toList();
        if(sites.size()>AtlasGeometry.MAX_SITES)throw new IllegalStateException("Visible atlas exceeds supported snapshot bound");
        var fingerprint=new StringBuilder(level.dimension().location().toString()).append('/').append(AtlasConfig.INFLUENCE.get());
        for(var v:sites){var owner=data.owner(v.getId().uuid());fingerprint.append('|').append(v.getId().uuid()).append('/').append(owner).append('/').append(v.getCenter().getX()).append('/').append(v.getCenter().getZ()).append('/').append(data.isCapital(v.getId().uuid())).append('/').append(PoliticsService.name(v)).append('/').append(v.getVillageTypeId());}
        data.wars().stream().filter(w->w.status()==KingdomSavedData.WarStatus.ACTIVE).sorted(Comparator.comparing(KingdomSavedData.War::id)).forEach(w->fingerprint.append('/').append(w.id()));
        view.putString("revision",UUID.nameUUIDFromBytes(fingerprint.toString().getBytes(StandardCharsets.UTF_8)).toString());view.putInt("radius",AtlasConfig.INFLUENCE.get());view.putInt("total",sites.size());view.putLong("observedTime",Math.max(0,level.getGameTime()));view.putInt("playerX",player.blockPosition().getX());view.putInt("playerZ",player.blockPosition().getZ());
        int start=0;if(request.cursor().isPresent()){while(start<sites.size() && !sites.get(start).getId().uuid().equals(request.cursor().get()))start++;if(start==sites.size())throw new IllegalArgumentException("Stale atlas cursor");start++;}
        var list=new ListTag();var ids=new HashSet<UUID>();
        for(int n=start;n<Math.min(start+PAGE_SIZE,sites.size());n++){
            var v=sites.get(n);var tag=new CompoundTag();var id=v.getId().uuid();tag.putUUID("id",id);tag.putString("name",PoliticsService.name(v));tag.putInt("x",v.getCenter().getX());tag.putInt("z",v.getCenter().getZ());tag.putString("type",v.getVillageTypeId().toString());tag.putString("culture",v.getCultureId().toString());var type=org.millenaire.culture.ModCultures.getVillageType(v.getVillageTypeId());tag.putString("typeName",type==null?"?":type.name());var icon=type==null?null:net.minecraft.resources.ResourceLocation.tryParse(type.icon()==null?"":type.icon());tag.putString("icon",icon!=null && net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(icon)?icon.toString():"minecraft:map");
            tag.putString("ethos",KingdomPersonality.forType(id,v.getVillageTypeId()).category().name());tag.putBoolean("capital",data.isCapital(id));var owner=data.owner(id);if(owner!=null){tag.putUUID("kingdom",owner);ids.add(owner);}list.add(tag);
        }
        view.put("settlements",list);if(start+PAGE_SIZE<sites.size())view.putUUID("next",sites.get(start+PAGE_SIZE-1).getId().uuid());
        var realms=new ListTag();for(var id:ids.stream().sorted().toList()){var k=data.kingdom(id);if(k==null)continue;var t=new CompoundTag();t.putUUID("id",id);t.putString("name",k.name());t.putInt("color",AtlasGeometry.color(id));t.putUUID("capital",k.capital());var r=HistoryHooks.realm(level,id);t.putString("culture",r.culture());t.putString("ethos",r.ethos());realms.add(t);}view.put("kingdoms",realms);
        var wars=new ListTag();for(var w:data.wars())if(w.status()==KingdomSavedData.WarStatus.ACTIVE && (ids.contains(w.attackerKingdom()) || ids.contains(w.defenderKingdom()))){var t=new CompoundTag();t.putUUID("id",w.id());t.putUUID("attacker",w.attackerKingdom());t.putUUID("defender",w.defenderKingdom());wars.add(t);}view.put("wars",wars);
        request.event().ifPresent(id->{var event=HistoryHooks.store(level).get(id);if(event==null || !event.dimension().equals(level.dimension().location().toString()))return;var filtered=HistoryVisibility.redact(event,known,false);var location=filtered.places().stream().filter(p->p.located() && (request.village().isEmpty() || request.village().get().equals(p.id()))).findFirst();location.ifPresent(p->{var pin=new CompoundTag();pin.putUUID("event",event.id());pin.putUUID("village",p.id());pin.putString("name",p.name());pin.putLong("day",event.day());pin.putInt("x",p.x());pin.putInt("z",p.z());view.put("historicalPin",pin);});});
        return view;
    }
    /** Native container resolution may inspect a connected chest outside the declared storage positions.
     * Defer this read until both halves are already loaded; the atlas must never load a chunk. */
    private static boolean treasuryLoaded(net.minecraft.server.level.ServerLevel level,KingdomSavedData.Kingdom kingdom){
        var storage=TreasuryService.storage(level,kingdom);
        if(storage==null || !storage.getChestPositions().stream().allMatch(level::isLoaded))return false;
        for(var position:storage.getChestPositions()){
            var state=level.getBlockState(position);
            if(state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock
                    && state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE)!=net.minecraft.world.level.block.state.properties.ChestType.SINGLE
                    && !level.isLoaded(position.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(state))))return false;
        }
        return true;
    }
    private static net.minecraft.resources.ResourceLocation resourceLocation(String id){var parsed=net.minecraft.resources.ResourceLocation.tryParse(id);return parsed==null?net.minecraft.resources.ResourceLocation.parse("millenaire:unknown"):parsed;}
    private static CompoundTag info(ServerPlayer player,UUID id,Set<UUID> known,UUID cursor){
        var level=player.serverLevel();var data=KingdomSavedData.get(level);var k=data.kingdom(id);var out=new CompoundTag();out.putUUID("id",id);
        var historical=HistoryHooks.store(level).archives().stream().filter(r->r.id().equals(id)).findFirst().orElse(null);if(k==null && historical==null)throw new IllegalArgumentException("Unknown kingdom or archive");
        var r=k==null?historical:HistoryHooks.realm(level,id);out.putString("name",r.name());out.putString("culture",r.culture());var culture=org.millenaire.culture.ModCultures.getCulture(resourceLocation(r.culture()));out.putString("cultureName",culture==null?"?":culture.displayName());out.putString("ethos",r.ethos());out.putBoolean("fallen",k==null || k.fallen());
        var foundation=HistoryHooks.store(level).query(new HistoryStore.Query(id,HistoryEvent.Kind.FOUNDING,0,Long.MAX_VALUE,null,1));if(!foundation.events().isEmpty())out.putLong("founded",foundation.events().getFirst().day());
        var members=new ListTag();out.put("members",members);
        if(k!=null){out.putBoolean("active",k.active());var capital=PoliticsService.village(level,k.capital());out.putString("capital",capital==null?"?":PoliticsService.name(capital));out.putUUID("capitalId",k.capital());var ruler=data.ruler(id);out.putString("ruler",ruler==null?"":ruler.lastKnownName());var war=data.activeWar(id);out.putBoolean("war",war!=null);if(war!=null){var other=war.attackerKingdom().equals(id)?war.defenderKingdom():war.attackerKingdom();out.putString("opponent",data.kingdom(other).name());}
            out.putInt("memberCount",data.members(id).size());var visible=data.members(id).stream().filter(known::contains).map(v->PoliticsService.village(level,v)).filter(Objects::nonNull).sorted(Comparator.comparing(v->v.getId().uuid())).toList();var memberKey=new StringBuilder().append(data.members(id).size());for(var v:visible)memberKey.append('/').append(v.getId().uuid()).append('/').append(v.getCenter()).append('/').append(PoliticsService.name(v)).append('/').append(v.getVillageTypeId());out.putString("memberRevision",UUID.nameUUIDFromBytes(memberKey.toString().getBytes(StandardCharsets.UTF_8)).toString());int start=0;
            if(cursor!=null){while(start<visible.size() && !visible.get(start).getId().uuid().equals(cursor))start++;if(start==visible.size())throw new IllegalArgumentException("Stale member cursor");start++;}
            for(int n=start;n<Math.min(start+PAGE_SIZE,visible.size());n++){var v=visible.get(n);var t=new CompoundTag();t.putUUID("id",v.getId().uuid());t.putString("name",PoliticsService.name(v));t.putString("type",v.getVillageTypeId().toString());var type=org.millenaire.culture.ModCultures.getVillageType(v.getVillageTypeId());t.putString("typeName",type==null?"?":type.name());t.putInt("x",v.getCenter().getX());t.putInt("z",v.getCenter().getZ());members.add(t);}if(start+PAGE_SIZE<visible.size())out.putUUID("next",visible.get(start+PAGE_SIZE-1).getId().uuid());
            if(id.equals(data.ruledBy(player.getUUID()))){out.putBoolean("economicRights",true);out.putLong("income",EconomyService.dailyIncome(level,data,id));out.putLong("owed",data.owed(id));out.putBoolean("storageLoaded",treasuryLoaded(level,k));if(out.getBoolean("storageLoaded"))out.putLong("treasury",TreasuryService.stored(level,k).value());}
        }
        return out;
    }
}
