package com.millenairekingdoms.history;

import com.millenairekingdoms.construction.RoyalConstructionStore;
import com.millenairekingdoms.economy.RoyalNpcDecisionStore;
import com.millenairekingdoms.economy.RoyalPaymentJournal;
import com.millenairekingdoms.economy.RoyalPaymentService;
import com.millenairekingdoms.shipping.RoyalShipmentStore;
import com.millenairekingdoms.shipping.RoyalShipmentService;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

/** Separate verified history outbox. Prepare BEFORE the terminal domain write; reveal only AFTER exact durable
 * native/domain/payment acknowledgement. Recovery reads proofs, never replays gameplay or touches coin/cargo files. */
@org.jetbrains.annotations.ApiStatus.Internal
public final class HistoryDomain {
    private static final org.slf4j.Logger LOG=LogUtils.getLogger();
    private HistoryDomain(){}
    private static Path root(ServerLevel level){return HistoryHooks.directory(level).resolve("candidates");}
    public static void prepareConstruction(ServerLevel level,RoyalConstructionStore.Entry entry,boolean complete){
        try{
            var i=entry.intent();var kind=complete?HistoryEvent.Kind.CONSTRUCTION_COMPLETE:HistoryEvent.Kind.CONSTRUCTION_ORDER;
            UUID id=HistoryEvent.identity(i.dimension(),kind,i.id().toString());if(HistoryHooks.store(level).contains(id))return;
            var facts=new HashMap<String,String>();facts.put("plan",i.planSet().toString());facts.put("fee",Long.toString(i.fee()));facts.put("upgrade",Boolean.toString(i.upgrade()));facts.put("level",Integer.toString(i.targetLevel()+1));if(entry.building()!=null)facts.put("building",entry.building().toString());
            var set=org.millenaire.culture.ModCultures.getBuildingPlanSet(i.planSet());if(set!=null && set.nativeName()!=null)facts.put("buildingName",set.nativeName().substring(0,Math.min(128,set.nativeName().length())));
            String actorName="";var ruler=com.millenairekingdoms.kingdom.KingdomSavedData.get(level).ruler(i.kingdom());if(ruler!=null && ruler.player().equals(i.actor()))actorName=ruler.lastKnownName();
            var event=HistoryHooks.event(level,kind,i.id().toString(),List.of(HistoryHooks.realm(level,i.kingdom())),List.of(HistoryHooks.place(level,i.village())),i.actor(),actorName,complete?"NATIVE_COMPLETION_OBSERVED":"NATIVE_ACCEPTANCE",complete?"COMPLETED":"ACCEPTED",facts);
            // The accepted/completed labels are pending facts until confirm() proves the corresponding durable phase.
            var events=new ArrayList<HistoryEvent>();events.add(event);
            if(!complete && i.actor()==null && policyInvestment(level,i))events.add(HistoryHooks.event(level,HistoryEvent.Kind.INVESTMENT,i.id().toString(),event.realms(),event.places(),null,"","AUTONOMOUS_INVESTMENT","COMMITTED",facts));
            prepare(level,kind.name(),RoyalConstructionStore.saveIntent(i),events);
        }catch(RuntimeException failure){LOG.error("[Kingdoms] Construction history preparation deferred for {}",entry.intent().id(),failure);}
    }
    private static boolean policyInvestment(ServerLevel level,RoyalConstructionStore.Intent i){
        var directory=RoyalNpcDecisionStore.directory(level);if(!Files.exists(directory.resolve("decisions.dat")))return false;
        try{return new RoyalNpcDecisionStore(directory).entries().stream().anyMatch(d->d.id().equals(i.id()) && d.kingdom().equals(i.kingdom()) && d.choice().kind()==RoyalNpcDecisionStore.Kind.CONSTRUCTION && d.choice().source().equals(i.village()) && d.choice().selection().equals(i.planSet()) && d.choice().fee()==i.fee());}
        catch(RuntimeException unavailable){LOG.warn("[Kingdoms] Automatic investment provenance unavailable; no autonomous investment claim for {}",i.id());return false;}
    }
    public static void prepareShipment(ServerLevel level,RoyalShipmentStore.Entry entry){
        var i=entry.intent();if(i.quantity()<64 && i.fee()<8)return;
        try{var event=HistoryHooks.event(level,HistoryEvent.Kind.SHIPMENT,i.id().toString(),List.of(HistoryHooks.realm(level,i.kingdom())),List.of(HistoryHooks.place(level,i.source().village()),HistoryHooks.place(level,i.destination().village())),i.actor(),"","DURABLE_DELIVERY","DELIVERED",Map.of("product",i.product().toString(),"quantity",Integer.toString(i.quantity()),"fee",Long.toString(i.fee())));prepare(level,"SHIPMENT",RoyalShipmentStore.saveIntent(i),List.of(event));}
        catch(RuntimeException failure){LOG.error("[Kingdoms] Shipment history preparation deferred for {}",i.id(),failure);}
    }
    private static void prepare(ServerLevel level,String kind,CompoundTag intent,List<HistoryEvent> events){
        var file=root(level).resolve(events.getFirst().id()+".dat");
        if(Files.exists(file)){var old=read(file);if(!old.getString("kind").equals(kind)||!old.getCompound("intent").equals(intent))throw new IllegalArgumentException("History candidate is bound to another domain intent");return;}
        try{if(Files.exists(root(level)))try(var files=Files.list(root(level))){if(files.filter(p->p.toString().endsWith(".dat")).limit(257).count()>=256)throw new IllegalStateException("Domain history pending limit; preserve candidates for review");}}
        catch(IOException e){throw new IllegalStateException("History candidate directory unavailable",e);}
        var t=new CompoundTag();t.putInt("format",1);t.putString("kind",kind);t.put("intent",intent);t.putBoolean("confirmed",false);var list=new ListTag();events.forEach(e->list.add(e.save()));t.put("events",list);HistoryFiles.write(file,t);
    }
    public static void finishConstruction(ServerLevel level,UUID operation,boolean complete){finish(level,operation,complete?HistoryEvent.Kind.CONSTRUCTION_COMPLETE:HistoryEvent.Kind.CONSTRUCTION_ORDER);}
    public static void finishShipment(ServerLevel level,UUID operation){finish(level,operation,HistoryEvent.Kind.SHIPMENT);}
    private static void finish(ServerLevel level,UUID operation,HistoryEvent.Kind kind){
        var file=root(level).resolve(HistoryEvent.identity(level.dimension().location().toString(),kind,operation.toString())+".dat");if(!Files.exists(file))return;
        try{confirm(level,file);}catch(RuntimeException failure){LOG.error("[Kingdoms] Verified domain history remains pending for {}",operation,failure);}
    }
    private static CompoundTag read(Path file){
        var t=HistoryFiles.read(file);HistoryFiles.keys(t,Set.of("format","kind","intent","confirmed","events"));if(!t.contains("format",Tag.TAG_INT) || t.getInt("format")!=1 || !t.contains("kind",Tag.TAG_STRING) || !t.contains("intent",Tag.TAG_COMPOUND) || !t.contains("confirmed",Tag.TAG_BYTE) || t.getByte("confirmed")<0 || t.getByte("confirmed")>1 || !t.contains("events",Tag.TAG_LIST))throw new IllegalStateException("Unsupported domain history candidate; bytes preserved");
        var list=(ListTag)t.get("events");if(list.isEmpty() || list.size()>2 || list.getElementType()!=Tag.TAG_COMPOUND)throw new IllegalArgumentException("Invalid domain history events");return t;
    }
    public static boolean confirm(ServerLevel level,Path file){
        var t=read(file);var kind=HistoryEvent.Kind.valueOf(t.getString("kind"));if(!Set.of(HistoryEvent.Kind.CONSTRUCTION_ORDER,HistoryEvent.Kind.CONSTRUCTION_COMPLETE,HistoryEvent.Kind.SHIPMENT).contains(kind))throw new IllegalArgumentException("Unknown history domain");
        var intent=t.getCompound("intent");var events=t.getList("events",Tag.TAG_COMPOUND).stream().map(raw->HistoryEvent.load((CompoundTag)raw)).toList();var event=events.getFirst();
        if(event.kind()!=kind || !file.getFileName().toString().equals(event.id()+".dat") || !event.source().equals(intent.getUUID("id").toString()) || !event.dimension().equals(level.dimension().location().toString()) || !event.realms().getFirst().id().equals(intent.getUUID("kingdom")) || events.size()==2 && (kind!=HistoryEvent.Kind.CONSTRUCTION_ORDER || events.get(1).kind()!=HistoryEvent.Kind.INVESTMENT || !events.get(1).source().equals(event.source())))throw new IllegalArgumentException("Historical candidate identity/source mismatch");
        boolean verified;
        if(kind==HistoryEvent.Kind.SHIPMENT){var i=RoyalShipmentStore.loadIntent(intent);verified=t.getBoolean("confirmed") || shipmentCommitted(level,i);}
        else{var i=RoyalConstructionStore.loadIntent(intent);verified=t.getBoolean("confirmed") || constructionCommitted(level,i,kind==HistoryEvent.Kind.CONSTRUCTION_COMPLETE);}
        if(!verified)return false;
        if(!t.getBoolean("confirmed")){t.putBoolean("confirmed",true);HistoryFiles.write(file,t);}
        for(var e:events)HistoryHooks.store(level).append(e);
        try{Files.delete(file);}catch(IOException e){throw new IllegalStateException("History is committed; candidate cleanup will retry",e);}return true;
    }
    private static boolean constructionCommitted(ServerLevel level,RoyalConstructionStore.Intent i,boolean complete){
        var store=new RoyalConstructionStore(level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms/royal-construction"));var e=store.load(i.id());if(e==null)return false;if(!e.intent().equals(i))throw new IllegalArgumentException("Construction history original intent changed");
        boolean accepted=Set.of(RoyalConstructionStore.Stage.QUEUED,RoyalConstructionStore.Stage.IN_PROGRESS,RoyalConstructionStore.Stage.COMPLETED).contains(e.stage())
                || e.cancellationFrom()==RoyalConstructionStore.Stage.QUEUED || e.cancellationFrom()==RoyalConstructionStore.Stage.IN_PROGRESS;
        if(complete?e.stage()!=RoyalConstructionStore.Stage.COMPLETED:!accepted)return false;
        var receipt=new RoyalPaymentService(level).inspectConstruction(i.id(),i.kingdom(),i.capital(),i.actor(),i.fee());return receipt!=null && receipt.state()==RoyalPaymentJournal.State.COMMITTED;
    }
    private static boolean shipmentCommitted(ServerLevel level,RoyalShipmentStore.Intent i){
        var e=new RoyalShipmentStore(RoyalShipmentService.directory(level)).load(i.id());if(e==null)return false;if(!e.intent().equals(i))throw new IllegalArgumentException("Shipment history original intent changed");if(e.stage()!=RoyalShipmentStore.Stage.DELIVERED || e.delivered()!=i.quantity())return false;
        var receipt=new RoyalPaymentService(level).inspectShipment(i.id(),i.kingdom(),i.capital(),i.actor(),i.fee());return receipt!=null && receipt.state()==RoyalPaymentJournal.State.COMMITTED;
    }
    static boolean confirmLegacy(ServerLevel level,Path file){
        var event=HistoryEvent.load(HistoryFiles.read(file));if(!event.dimension().equals(level.dimension().location().toString()))throw new IllegalArgumentException("Legacy history dimension mismatch");UUID id=UUID.fromString(event.source());boolean verified;
        if(event.kind()==HistoryEvent.Kind.SHIPMENT){var e=new RoyalShipmentStore(RoyalShipmentService.directory(level)).load(id);verified=e!=null && e.intent().kingdom().equals(event.realms().getFirst().id()) && e.intent().source().village().equals(event.places().getFirst().id()) && shipmentCommitted(level,e.intent());}
        else if(Set.of(HistoryEvent.Kind.CONSTRUCTION_ORDER,HistoryEvent.Kind.CONSTRUCTION_COMPLETE,HistoryEvent.Kind.INVESTMENT).contains(event.kind())){var e=new RoyalConstructionStore(level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms/royal-construction")).load(id);verified=e!=null && e.intent().kingdom().equals(event.realms().getFirst().id()) && e.intent().village().equals(event.places().getFirst().id()) && constructionCommitted(level,e.intent(),event.kind()==HistoryEvent.Kind.CONSTRUCTION_COMPLETE);}
        else throw new IllegalArgumentException("Legacy pending event lacks domain proof");
        if(!verified)return false;HistoryHooks.store(level).append(event);try{Files.delete(file);}catch(IOException e){throw new IllegalStateException("Legacy history cleanup deferred",e);}return true;
    }
}
