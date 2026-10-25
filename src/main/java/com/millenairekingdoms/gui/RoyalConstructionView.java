package com.millenairekingdoms.gui;

import com.millenairekingdoms.construction.RoyalConstructionCatalog;
import com.millenairekingdoms.construction.RoyalConstructionService;
import com.millenairekingdoms.construction.RoyalConstructionStore;
import com.millenairekingdoms.construction.RoyalConstructionStore.Stage;
import com.millenairekingdoms.economy.RoyalTreasurySnapshot;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.politics.PoliticsService;
import com.millenairekingdoms.util.KingdomException;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.millenaire.building.BuildingId;
import org.millenaire.config.MillenaireServerConfig;
import org.millenaire.culture.ModCultures;

/** Read-only selected-village preview and bounded ephemeral server offers. No preview can mutate a domain order. */
public final class RoyalConstructionView {
    private static final long OFFER_TICKS=1200;
    private static final int MAX_PLANS=128;
    private static final Map<ServerPlayer,Session> SESSIONS=new WeakHashMap<>();
    private record Offer(UUID id,UUID village,ResourceLocation plan,long fee,long expires) {}
    private static final class Session {
        String dimension;
        UUID kingdom,capital,village;
        long tenure;
        final Map<ResourceLocation,Offer> offers=new HashMap<>();
    }
    private RoyalConstructionView() {}
    public static void forget(UUID player) {SESSIONS.keySet().removeIf(p->p.getUUID().equals(player));}

    public static void select(ServerPlayer player,UUID village) {
        var k=PoliticsService.ruledKingdom(player); var data=KingdomSavedData.get(player.serverLevel());
        if(village==null || !k.id().equals(data.owner(village))) throw refused("foreign_village","Choose a village belonging to your active kingdom.");
        var state=session(player,k); if(!village.equals(state.village)) {state.village=village;state.offers.clear();}
    }

    public static CompoundTag build(ServerPlayer player,UUID focusedKingdom) {
        var out=new CompoundTag(); var level=player.serverLevel(); var data=KingdomSavedData.get(level);
        if(!focusedKingdom.equals(data.ruledBy(player.getUUID()))) {out.putString("reason","RULER_ONLY");return out;}
        try {
            var k=PoliticsService.ruledKingdom(player); var state=session(player,k); var villages=new ListTag();
            for(var id:data.members(k.id()).stream().sorted(Comparator.comparing(UUID::toString)).toList()) {
                var v=PoliticsService.village(level,id); if(v==null) continue;
                var tag=new CompoundTag();tag.putUUID("id",id);tag.putString("name",PoliticsService.name(v));tag.putBoolean("active",v.isActive() && level.isLoaded(v.getCenter()));villages.add(tag);
            }
            out.put("villages",villages);
            var village=PoliticsService.village(level,state.village);
            if(village==null) {out.putString("reason","MISSING_VILLAGE");return out;}
            out.putUUID("village",state.village); out.putBoolean("available",village.isActive() && level.isLoaded(village.getCenter()));
            out.putBoolean("naturalPending",RoyalConstructionCatalog.naturalWorkPending(village));
            var type=ModCultures.getVillageType(village.getVillageTypeId());
            var path=level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms/royal-construction");
            var history=Files.exists(path.resolve("orders.dat")) ? new RoyalConstructionStore(path).entries() : List.<RoyalConstructionStore.Entry>of();
            out.putBoolean("stateKnown",true);
            var usedIds=history.stream().map(e->e.intent().id()).collect(java.util.stream.Collectors.toSet());
            var current=history.stream()
                    .filter(e->e.intent().dimension().equals(level.dimension().location().toString()) && e.intent().village().equals(state.village))
                    .sorted(Comparator.comparing(RoyalConstructionStore.Entry::terminal)
                            .thenComparing(Comparator.comparingLong((RoyalConstructionStore.Entry e)->e.intent().issuedGameTime()).reversed())
                            .thenComparing(e->e.intent().id())).findFirst().orElse(null);
            boolean busy=current!=null && !current.terminal();
            if(current!=null) {
                var order=new CompoundTag();var i=current.intent();order.putUUID("id",i.id());order.putString("plan",i.planSet().toString());
                var set=ModCultures.getBuildingPlanSet(i.planSet());order.putString("name",set==null ? i.planSet().toString() : set.nativeName());
                order.putString("stage",current.stage().name());order.putString("blocked",current.blocked().name());order.putLong("fee",i.fee());
                boolean own=player.getUUID().equals(i.actor()) && k.id().equals(i.kingdom()) && k.capital().equals(i.capital()) && state.tenure==i.rulerSince()
                        && type!=null && !type.playerControlled() && village.getOwnerUUID()==null && i.villageType().equals(village.getVillageTypeId());
                order.putBoolean("canCancel",own && busy && current.stage()!=Stage.RECOVERY_REQUIRED);
                order.putBoolean("canResume",own && (current.stage()==Stage.WAITING || current.stage()==Stage.REQUESTED || current.stage()==Stage.PAID || current.stage()==Stage.QUEUING || current.stage()==Stage.CANCELLING));
                order.putBoolean("refundable",current.stage()==Stage.REQUESTED || current.stage()==Stage.PAID);
                if(current.building()!=null) {
                    var building=village.getBuilding(new BuildingId(current.building()));
                    if(building!=null && building.getConstructionTask()!=null) order.putInt("progress",Math.clamp((int)(100*building.getConstructionTask().progress()),0,100));
                }
                out.put("order",order);
            }
            String reason=type==null || type.playerControlled() || village.getOwnerUUID()!=null ? "CONTROLLED"
                    : MillenaireServerConfig.SERVER.ignoreResourceCost.get() ? "RESOURCE_COSTS_DISABLED"
                    : busy ? "ORDER_PENDING" : !out.getBoolean("available") ? "UNLOADED" : "";
            long funds=0; boolean treasury=false;
            try {funds=RoyalTreasurySnapshot.forPlayer(player).spendableValue();treasury=true;}
            catch(IllegalArgumentException|IllegalStateException unavailable) { /* The read-only preview does not load storage. */ }
            out.putBoolean("treasuryAvailable",treasury);if(treasury)out.putLong("spendable",funds);
            var plans=new ListTag();
            if(type!=null && !type.playerControlled() && village.getOwnerUUID()==null) {
                var catalog=RoyalConstructionCatalog.forPlayer(player,state.village);
                for(var choice:catalog.options().stream().limit(MAX_PLANS).toList()) {
                    var offer=state.offers.get(choice.planSet());long now=level.getGameTime();
                    if(offer==null || usedIds.contains(offer.id()) || offer.fee()!=choice.patronageFee() || now>offer.expires()) {
                        offer=new Offer(UUID.randomUUID(),state.village,choice.planSet(),choice.patronageFee(),Math.max(0,now)+OFFER_TICKS);state.offers.put(choice.planSet(),offer);
                    }
                    String denial=!reason.isEmpty() ? reason : catalog.naturalProjectPending() ? "" : !treasury ? "TREASURY_UNAVAILABLE" : funds<offer.fee() ? "INSUFFICIENT_FUNDS" : "";
                    var p=new CompoundTag();p.putUUID("offer",offer.id());p.putString("plan",choice.planSet().toString());p.putString("name",choice.nativeName());
                    p.putLong("fee",offer.fee());p.putBoolean("canOrder",denial.isEmpty());if(!denial.isEmpty())p.putString("reason",denial);plans.add(p);
                }
                var offered=catalog.options().stream().limit(MAX_PLANS).map(RoyalConstructionCatalog.Option::planSet).collect(java.util.stream.Collectors.toSet());state.offers.keySet().retainAll(offered);
            } else state.offers.clear();
            out.put("plans",plans);if(!reason.isEmpty())out.putString("reason",reason);else if(plans.isEmpty())out.putString("reason","NO_ELIGIBLE_PLAN");
        } catch(KingdomException authority) {out.putString("reason","AUTHORITY");}
        catch(RuntimeException unavailable) {
            // Do not reveal stale partial offers after an unavailable/unknown order index. Preserve files and grant nothing.
            out.remove("plans");out.putString("reason","SERVICE_UNAVAILABLE");
            if(out.contains("order")) {out.getCompound("order").putBoolean("canCancel",false);out.getCompound("order").putBoolean("canResume",false);}
            var state=SESSIONS.get(player);if(state!=null)state.offers.clear();
        }
        return out;
    }

    public static RoyalConstructionStore.Entry order(ServerPlayer player,UUID village,UUID offerId,ResourceLocation plan) {
        var level=player.serverLevel(); var k=PoliticsService.ruledKingdom(player);
        var path=level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms/royal-construction");
        var existing=Files.exists(path.resolve("orders.dat")) ? new RoyalConstructionStore(path).load(offerId) : null;
        if(existing!=null) return new RoyalConstructionService(level).requestPlayer(player,village,plan,offerId);
        var state=session(player,k);var offer=state.offers.get(plan);
        if(offer==null || !offer.id().equals(offerId) || !offer.village().equals(village) || level.getGameTime()>offer.expires())
            throw refused("offer_expired","This offer expired or changed. Refresh and select the plan again.");
        return new RoyalConstructionService(level).requestQuotedPlayer(player,village,plan,offer.id(),offer.fee());
    }
    private static Session session(ServerPlayer player,KingdomSavedData.Kingdom kingdom) {
        var level=player.serverLevel();var data=KingdomSavedData.get(level);var ruler=data.ruler(kingdom.id());
        var state=SESSIONS.computeIfAbsent(player,p->new Session());String dimension=level.dimension().location().toString();
        if(!dimension.equals(state.dimension) || !kingdom.id().equals(state.kingdom) || !kingdom.capital().equals(state.capital) || state.tenure!=ruler.sinceGameTime()) {
            state.dimension=dimension;state.kingdom=kingdom.id();state.capital=kingdom.capital();state.tenure=ruler.sinceGameTime();state.village=kingdom.capital();state.offers.clear();
        }
        if(state.village==null || !kingdom.id().equals(data.owner(state.village))) {state.village=kingdom.capital();state.offers.clear();}
        return state;
    }
    private static KingdomException refused(String key,String message) {return new KingdomException("millenairekingdoms.gui.msg.construction_"+key,message);}
}
