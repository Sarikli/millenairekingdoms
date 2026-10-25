package com.millenairekingdoms.history;

import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.personality.KingdomPersonality;
import com.millenairekingdoms.politics.PoliticsService;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.IOUtilities;

/** Commit observers. History failures are diagnosed separately and never refund, replay or edit gameplay. */
public final class HistoryHooks {
    private static final Map<ServerLevel,HistoryStore> STORES=new WeakHashMap<>();
    private static final org.slf4j.Logger LOG=LogUtils.getLogger();
    private HistoryHooks(){}
    public static Path directory(ServerLevel level){return DimensionType.getStorageFolder(level.dimension(),level.getServer().getWorldPath(LevelResource.ROOT)).resolve("data/millenairekingdoms/history");}
    public static HistoryStore store(ServerLevel level){if(!level.getServer().isSameThread())throw new IllegalStateException("History requires server thread");return STORES.computeIfAbsent(level,l->new HistoryStore(directory(l)));}
    public static void clear(net.minecraft.server.MinecraftServer server){STORES.keySet().removeIf(level->level.getServer()==server);}
    public static Path stagePolitical(ServerLevel level,CompoundTag before,CompoundTag after){
        try {
            recover(level);
            var events=politicalEvents(level,before,after);if(events.isEmpty())return null;
            var pending=directory(level).resolve("pending");if(Files.exists(pending))try(var files=Files.list(pending)){if(files.filter(p->p.toString().endsWith(".dat")).count()>=128)throw new IllegalStateException("History pending limit; inspect unconfirmed transitions");}
            var tag=new CompoundTag();tag.putInt("format",1);tag.put("expected",projection(after));var list=new ListTag();events.forEach(e->list.add(e.save()));tag.put("events",list);
            var file=pending.resolve(UUID.randomUUID()+".dat");HistoryFiles.write(file,tag);return file;
        }catch(RuntimeException|IOException failure){LOG.error("[Kingdoms] History preparation unavailable; political simulation remains authoritative; no success invented",failure);return null;}
    }
    public static void afterPolitical(ServerLevel level,KingdomSavedData data,Path pending){
        if(pending==null)return;
        try {
            // Uses the existing political serializer and worker; original Millenaire/coin/cargo mechanisms are untouched.
            var file=politicalFile(level);
            if(Files.exists(file))KingdomSavedData.load(readPolitical(file),level.registryAccess());
            data.setDirty();level.getDataStorage().save();IOUtilities.waitUntilIOWorkerComplete();
            confirm(level,pending,readPolitical(file));
        }catch(RuntimeException failure){LOG.error("[Kingdoms] Historical transition remains pending until its political disk evidence can be verified",failure);}
    }
    public static void recover(ServerLevel level){
        var domain=directory(level).resolve("domain-pending");
        if(Files.exists(domain))try(var files=Files.list(domain)){for(var file:files.filter(p->p.toString().endsWith(".dat")).limit(129).sorted().toList()){
            var event=HistoryEvent.load(HistoryFiles.read(file));store(level).append(event);Files.delete(file);
        }}catch(IOException e){throw new IllegalStateException("Domain history recovery deferred",e);}
        var path=directory(level).resolve("pending");if(!Files.exists(path) || !Files.exists(politicalFile(level)))return;
        var disk=readPolitical(politicalFile(level));KingdomSavedData.load(disk,level.registryAccess());
        try(var files=Files.list(path)){for(var file:files.filter(p->p.toString().endsWith(".dat")).limit(129).sorted().toList())confirm(level,file,disk);}
        catch(IOException failure){throw new IllegalStateException("Cannot recover historical observations",failure);}
    }
    private static void confirm(ServerLevel level,Path pending,CompoundTag disk){
        var tag=HistoryFiles.read(pending);HistoryFiles.keys(tag,Set.of("format","expected","events"));
        if(!tag.contains("format",Tag.TAG_INT)||tag.getInt("format")!=1 || !tag.contains("expected",Tag.TAG_COMPOUND)||!tag.contains("events",Tag.TAG_LIST))throw new IllegalArgumentException("Unsupported pending history; bytes preserved");
        var list=(ListTag)tag.get("events");if(list.isEmpty() || list.size()>16 || list.getElementType()!=Tag.TAG_COMPOUND)throw new IllegalArgumentException("Invalid pending history batch");
        var events=new ArrayList<HistoryEvent>();for(var raw:list)events.add(HistoryEvent.load((CompoundTag)raw));
        if(!tag.getCompound("expected").equals(projection(disk)))return; // A prepared but unsaved transition is not history.
        for(var event:events)store(level).append(event);
        try{Files.delete(pending);}catch(IOException e){throw new IllegalStateException("History committed; pending cleanup deferred, replay is idempotent",e);}
    }
    private static CompoundTag projection(CompoundTag tag){var p=new CompoundTag();for(var k:List.of("format","kingdoms","membership","revisions","wars","rulers","receipts")){if(tag.contains(k))p.put(k,tag.get(k).copy());}return p;}
    private static Path politicalFile(ServerLevel level){return DimensionType.getStorageFolder(level.dimension(),level.getServer().getWorldPath(LevelResource.ROOT)).resolve("data/"+KingdomSavedData.NAME+".dat");}
    private static CompoundTag readPolitical(Path file){try{var root=NbtIo.readCompressed(file,NbtAccounter.create(64L*1024*1024));if(!root.contains("data",Tag.TAG_COMPOUND))throw new IllegalStateException("Invalid political history evidence");return root.getCompound("data");}catch(IOException e){throw new IllegalStateException("Political commit cannot be confirmed",e);}}
    public static HistoryEvent.Realm realm(ServerLevel level,UUID id){
        var k=KingdomSavedData.get(level).kingdom(id);if(k==null)throw new IllegalArgumentException("Unknown historical kingdom");return realm(level,k.id(),k.name(),k.capital());
    }
    private static HistoryEvent.Realm realm(ServerLevel level,UUID id,String name,UUID capital){
        var village=PoliticsService.village(level,capital);var profile=village==null?null:KingdomPersonality.forType(capital,village.getVillageTypeId());
        return new HistoryEvent.Realm(id,name,village==null?"":village.getCultureId().toString(),profile==null?"GENERAL":profile.category().name());
    }
    public static HistoryEvent.Place place(ServerLevel level,UUID id){var v=PoliticsService.village(level,id);return new HistoryEvent.Place(id,v==null?"?":bounded(PoliticsService.name(v),128),v!=null,v==null?0:v.getCenter().getX(),v==null?0:v.getCenter().getZ());}
    public static HistoryEvent event(ServerLevel level,HistoryEvent.Kind kind,String source,List<HistoryEvent.Realm> realms,List<HistoryEvent.Place> places,UUID actor,String actorName,String reason,String outcome,Map<String,String> facts){
        return HistoryEvent.create(kind,Math.max(0,level.getDayTime()/24000),Math.max(0,level.getGameTime()),level.dimension().location().toString(),source,realms,places,actor,bounded(actorName,64),bounded(reason,256),outcome,facts);
    }
    /** Durable domain stores/receipts must have acknowledged the factual outcome before calling this. */
    public static void record(ServerLevel level,HistoryEvent event){
        try{
            if(store(level).contains(event.id()))return;
            var file=directory(level).resolve("domain-pending").resolve(event.id()+".dat");
            if(Files.exists(file))event=HistoryEvent.load(HistoryFiles.read(file));else HistoryFiles.write(file,event.save());
            store(level).append(event);Files.delete(file);
        }catch(RuntimeException|IOException failure){LOG.error("[Kingdoms] History append failed for original operation {}; gameplay will not be replayed",event.source(),failure);}
    }
    /** New mandates only; an old accepted order must not be backfilled on an ordinary retry. */
    public static void trackConstruction(ServerLevel level,UUID id){
        try{var t=new CompoundTag();t.putInt("format",1);t.putUUID("id",id);HistoryFiles.write(directory(level).resolve("tracked-orders").resolve(id+".dat"),t);}
        catch(RuntimeException failure){LOG.error("[Kingdoms] New order history enrollment failed for {}",id,failure);}
    }
    public static void construction(ServerLevel level,com.millenairekingdoms.construction.RoyalConstructionStore.Entry entry,boolean complete){
        try{
            var i=entry.intent();
            var kind=complete?HistoryEvent.Kind.CONSTRUCTION_COMPLETE:HistoryEvent.Kind.CONSTRUCTION_ORDER;
            if(store(level).contains(HistoryEvent.identity(i.dimension(),kind,i.id().toString())))return;
            if(!complete && !Files.exists(directory(level).resolve("tracked-orders").resolve(i.id()+".dat")))return;
            var facts=new HashMap<String,String>();facts.put("plan",i.planSet().toString());facts.put("fee",Long.toString(i.fee()));facts.put("upgrade",Boolean.toString(i.upgrade()));facts.put("level",Integer.toString(i.targetLevel()+1));if(entry.building()!=null)facts.put("building",entry.building().toString());
            var event=event(level,complete?HistoryEvent.Kind.CONSTRUCTION_COMPLETE:HistoryEvent.Kind.CONSTRUCTION_ORDER,i.id().toString(),List.of(realm(level,i.kingdom())),List.of(place(level,i.village())),i.actor(),"","NATIVE_ACKNOWLEDGED",complete?"COMPLETED":"ACCEPTED",facts);record(level,event);
            if(!complete && i.actor()==null)record(level,event(level,HistoryEvent.Kind.INVESTMENT,i.id().toString(),event.realms(),event.places(),null,"","AUTONOMOUS_INVESTMENT","COMMITTED",facts));
        }catch(RuntimeException failure){LOG.error("[Kingdoms] Construction history unavailable for {}",entry.intent().id(),failure);}
    }
    public static void shipment(ServerLevel level,com.millenairekingdoms.shipping.RoyalShipmentStore.Entry entry){
        var i=entry.intent();if(i.quantity()<64 && i.fee()<8)return;
        try{record(level,event(level,HistoryEvent.Kind.SHIPMENT,i.id().toString(),List.of(realm(level,i.kingdom())),List.of(place(level,i.source().village()),place(level,i.destination().village())),i.actor(),"","DURABLE_DELIVERY","DELIVERED",Map.of("product",i.product().toString(),"quantity",Integer.toString(i.quantity()),"fee",Long.toString(i.fee()))));}
        catch(RuntimeException failure){LOG.error("[Kingdoms] Shipment history unavailable for {}",i.id(),failure);}
    }
    public static List<HistoryEvent> politicalEvents(ServerLevel level,CompoundTag before,CompoundTag after){
        var oldKingdoms=index(before,"kingdoms","id");var newKingdoms=index(after,"kingdoms","id");
        var oldOwners=index(before,"membership","village");var owners=index(after,"membership","village");var revisions=index(after,"revisions","village");
        var oldWars=index(before,"wars","id");var wars=index(after,"wars","id");var oldRulers=index(before,"rulers","kingdom");var rulers=index(after,"rulers","kingdom");
        var out=new ArrayList<HistoryEvent>();var conquered=new java.util.HashSet<UUID>();var receipts=index(before,"receipts","id");
        java.util.function.Function<UUID,HistoryEvent.Realm> describe=id->{var k=newKingdoms.get(id);if(k==null)k=oldKingdoms.get(id);return realm(level,id,k.getString("name"),k.getUUID("capital"));};
        for(var k:newKingdoms.values())if(!oldKingdoms.containsKey(k.getUUID("id")))out.add(event(level,HistoryEvent.Kind.FOUNDING,k.getUUID("id").toString(),List.of(describe.apply(k.getUUID("id"))),List.of(place(level,k.getUUID("capital"))),null,"","RECORDED_FOUNDING","FOUNDED",Map.of()));
        for(var r:index(after,"receipts","id").values())if(r.getBoolean("transferred") && !receipts.containsKey(r.getUUID("id"))){
            var village=r.getUUID("target");conquered.add(village);var owner=owners.get(village);if(owner==null)continue;UUID winner=owner.getUUID("kingdom");var previous=oldOwners.get(village);var rs=new ArrayList<HistoryEvent.Realm>();rs.add(describe.apply(winner));boolean capital=false;
            if(previous!=null){var loser=previous.getUUID("kingdom");if(!loser.equals(winner)){rs.add(describe.apply(loser));capital=oldKingdoms.get(loser).getUUID("capital").equals(village);}}
            out.add(event(level,capital?HistoryEvent.Kind.CAPITAL_CONQUEST:HistoryEvent.Kind.VILLAGE_CONQUEST,r.getUUID("id").toString(),rs,List.of(place(level,village)),null,"","RECORDED_CONQUEST",r.getString("outcome"),Map.of()));
        }
        for(var entry:owners.entrySet())if(!oldOwners.containsKey(entry.getKey()) && !conquered.contains(entry.getKey())){
            var id=entry.getValue().getUUID("kingdom");var k=newKingdoms.get(id);if(!oldKingdoms.containsKey(id) && k.getUUID("capital").equals(entry.getKey()))continue;
            out.add(event(level,HistoryEvent.Kind.INCORPORATION,entry.getKey()+"/"+revisions.get(entry.getKey()).getLong("revision"),List.of(describe.apply(id)),List.of(place(level,entry.getKey())),null,"","RECORDED_ATTACHMENT","INCORPORATED",Map.of()));
        }
        for(var w:wars.values()){
            var id=w.getUUID("id");var old=oldWars.get(id);HistoryEvent.Kind kind=old==null && w.getString("status").equals("ACTIVE")?HistoryEvent.Kind.WAR:old!=null && old.getString("status").equals("ACTIVE") && w.getString("status").equals("PEACE")?HistoryEvent.Kind.PEACE:null;
            if(kind!=null)out.add(event(level,kind,id.toString(),List.of(describe.apply(w.getUUID("attackerKingdom")),describe.apply(w.getUUID("defenderKingdom"))),List.of(),null,"",w.getString("endReason"),w.getString("status"),Map.of()));
        }
        for(var k:newKingdoms.values())if(k.hasUUID("fallenInWar") && oldKingdoms.containsKey(k.getUUID("id")) && !oldKingdoms.get(k.getUUID("id")).hasUUID("fallenInWar"))out.add(event(level,HistoryEvent.Kind.COLLAPSE,k.getUUID("fallenInWar")+"/"+k.getUUID("id"),List.of(describe.apply(k.getUUID("id"))),List.of(place(level,k.getUUID("capital"))),null,"","CAPITAL_CONQUERED","FALLEN",Map.of()));
        var allRulers=new java.util.HashSet<>(oldRulers.keySet());allRulers.addAll(rulers.keySet());
        for(var id:allRulers)if(!java.util.Objects.equals(oldRulers.get(id),rulers.get(id))){
            var old=oldRulers.get(id);var next=rulers.get(id);String operation=UUID.randomUUID().toString();
            if(old!=null)out.add(event(level,HistoryEvent.Kind.RULER_RELEASE,operation+"/"+id,List.of(describe.apply(id)),List.of(),old.getUUID("player"),old.getString("lastKnownName"),"RECORDED_AUTHORITY_CHANGE","RELEASED",Map.of()));
            if(next!=null)out.add(event(level,HistoryEvent.Kind.RULER_CLAIM,operation+"/"+id,List.of(describe.apply(id)),List.of(),next.getUUID("player"),next.getString("lastKnownName"),"RECORDED_AUTHORITY_CHANGE","CLAIMED",Map.of()));
        }
        return List.copyOf(out);
    }
    private static Map<UUID,CompoundTag> index(CompoundTag tag,String list,String key){var out=new HashMap<UUID,CompoundTag>();for(var raw:tag.getList(list,Tag.TAG_COMPOUND)){var e=(CompoundTag)raw;if(e.hasUUID(key))out.put(e.getUUID(key),e);}return out;}
    private static String bounded(String s,int max){return s.length()<=max?s:s.substring(0,max);}
}
