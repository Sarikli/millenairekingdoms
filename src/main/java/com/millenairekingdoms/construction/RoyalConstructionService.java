package com.millenairekingdoms.construction;

import com.millenairekingdoms.construction.RoyalConstructionStore.Blocked;
import com.millenairekingdoms.construction.RoyalConstructionStore.Entry;
import com.millenairekingdoms.construction.RoyalConstructionStore.Intent;
import com.millenairekingdoms.construction.RoyalConstructionStore.Stage;
import com.millenairekingdoms.economy.RoyalPaymentJournal.State;
import com.millenairekingdoms.economy.RoyalPaymentService;
import com.millenairekingdoms.economy.RoyalPaymentService.Purpose;
import com.millenairekingdoms.economy.RoyalTreasurySnapshot;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.politics.PoliticsService;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.millenaire.building.BuildingInstance;
import org.millenaire.config.MillenaireServerConfig;
import org.millenaire.culture.ModCultures;
import org.millenaire.village.Village;
import org.millenaire.world.BuildingPlacer;

/** Server-owned paid request/retry/cancellation shared by player actions and bounded existing-order tracking.
 * Engine placement/builders/resources remain responsible for construction; this service only fills an empty pending slot. */
@org.jetbrains.annotations.ApiStatus.Internal
public final class RoyalConstructionService {
    private static final Map<MinecraftServer,Set<UUID>> BUSY=new WeakHashMap<>();
    private record Publication(Village village,Village.PendingProject pending) {}
    private static final Map<MinecraftServer,Map<UUID,Publication>> PUBLICATIONS=new WeakHashMap<>();
    private final ServerLevel level;
    private final RoyalConstructionStore orders;
    private final RoyalPaymentService payments;
    private final RoyalVillagePersistence persistence;

    public RoyalConstructionService(ServerLevel level) { this(level,new RoyalVillagePersistence()); }
    RoyalConstructionService(ServerLevel level,RoyalVillagePersistence persistence) {
        this(level,persistence,new RoyalPaymentService(level));
    }
    // Real fault probes inject persistence only through package-private constructors, never runtime crash flags.
    RoyalConstructionService(ServerLevel level,RoyalVillagePersistence persistence,RoyalPaymentService payments) {
        this.level=level; this.persistence=persistence; this.payments=payments;
        this.orders=new RoyalConstructionStore(level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms/royal-construction"));
    }
    public Entry requestNpc(UUID kingdom,UUID village,ResourceLocation planSet,UUID id) { return request(null,kingdom,village,planSet,id,null); }
    /** Immutable server policy approval; reject changed prices before reserving a new domain intent. */
    public Entry requestQuotedNpc(UUID kingdom,UUID village,ResourceLocation planSet,UUID id,long expectedFee) {return request(null,kingdom,village,planSet,id,expectedFee);}
    public Entry requestPlayer(ServerPlayer player,UUID village,ResourceLocation planSet,UUID id) {
        thread(); dimension(player); return request(player,PoliticsService.ruledKingdom(player).id(),village,planSet,id,null);
    }
    /** The expected fee comes from a server-issued GUI offer. Current catalog pricing is still authoritative. */
    public Entry requestQuotedPlayer(ServerPlayer player,UUID village,ResourceLocation planSet,UUID id,long expectedFee) {
        thread(); dimension(player); return request(player,PoliticsService.ruledKingdom(player).id(),village,planSet,id,expectedFee);
    }
    public Entry retryNpc(UUID kingdom,UUID id) { return retry(null,kingdom,id); }
    public Entry retryPlayer(ServerPlayer player,UUID id) {
        thread(); dimension(player); return retry(player,PoliticsService.ruledKingdom(player).id(),id);
    }
    public Entry cancelNpc(UUID kingdom,UUID id) { return cancel(null,kingdom,id); }
    public Entry cancelPlayer(ServerPlayer player,UUID id) {
        thread(); dimension(player); return cancel(player,PoliticsService.ruledKingdom(player).id(),id);
    }
    /** Internal bounded scheduler/diagnostic operation; does not impersonate an offline actor or initiate spending. */
    public Entry observe(UUID id) {
        thread(); var entry=orders.load(id); if(entry==null) throw new IllegalArgumentException("Unknown royal order");
        if(!entry.intent().dimension().equals(level.dimension().location().toString())) throw new IllegalArgumentException("Order belongs to another dimension");
        return exclusive(entry.intent().village(),()->{
            var current=orders.load(id);
            if(current.stage()==Stage.QUEUING && current.blocked()==Blocked.NO_SPACE) return retrySite(current);
            current=new RoyalConstructionMonitor(level,orders,persistence).observe(current);
            if(current.stage()==Stage.WAITING) {
                var waitingVillage=PoliticsService.village(level,current.intent().village());
                if(!waitingVillage.isActive() || !level.isLoaded(waitingVillage.getCenter())) return blocked(current,Blocked.UNLOADED);
                if(RoyalConstructionCatalog.naturalWorkPending(waitingVillage)) return blocked(current,Blocked.NATURAL_PROJECT);
                var actor=level.getServer().getPlayerList().getPlayer(current.intent().actor());
                if(actor==null || actor.serverLevel()!=level) return blocked(current,Blocked.RULER_UNAVAILABLE);
            }
            return current.stage()==Stage.QUEUED && current.blocked()==Blocked.NO_SPACE ? retrySite(current) : current;
        });
    }

    /** Reoffer only a known native placement failure. No location search/choice, resource grant or new payment. */
    private Entry retrySite(Entry entry) {
        var intent=entry.intent(); var village=PoliticsService.village(level,intent.village());
        var publications=PUBLICATIONS.computeIfAbsent(level.getServer(),key->new java.util.HashMap<>());
        if(entry.stage()==Stage.QUEUING) {
            // Recheck mandate before identity checks, including village removal or authority loss during failed I/O.
            var checked=new RoyalConstructionMonitor(level,orders,persistence).observe(entry);
            if(checked.stage()!=Stage.QUEUING) return checked;
            if(!village.isActive() || !level.isLoaded(village.getCenter())) return entry;
            var publication=publications.get(intent.id());
            if(publication==null || publication.village()!=village) return ambiguous(entry);
            var fresh=village.getBuildings().stream().filter(b->RoyalConstructionMonitor.fulfillment(b,intent)).toList();
            if(fresh.size()>1) return ambiguous(entry);
            if(!fresh.isEmpty()) {
                var building=fresh.getFirst();
                if(!RoyalConstructionMonitor.credible(building,intent) || pending(intent).equals(village.getPendingProject())) return ambiguous(entry);
                if(!level.isLoaded(building.getOrigin())) return entry;
                var receipt=payments.inspectConstruction(intent.id(),intent.kingdom(),intent.capital(),intent.actor(),intent.fee());
                if(receipt==null || receipt.state()!=State.COMMITTED) return ambiguous(entry);
                persistence.building(level,intent.village(),building);
                entry=orders.change(intent.id(),Stage.QUEUING,Stage.IN_PROGRESS,Blocked.NONE,building.getId().uuid()); publications.remove(intent.id());
                return new RoyalConstructionMonitor(level,orders,persistence).observe(entry);
            }
            if(publication.pending()!=village.getPendingProject() || !pending(intent).equals(village.getPendingProject())) return ambiguous(entry);
        } else {
            if(!village.isActive() || !level.isLoaded(village.getCenter())) return entry;
            // A native pending project always keeps its slot, including an equal-field natural project.
            if(village.getPendingProject()!=null || village.getPlacementCooldowns().getOrDefault(intent.planSet(),0L)>level.getGameTime()) return entry;
            if(!eligible(RoyalConstructionCatalog.forIssuedOrder(level,intent),intent)) return entry;
            template(intent.plan());
        }
        var receipt=payments.inspectConstruction(intent.id(),intent.kingdom(),intent.capital(),intent.actor(),intent.fee());
        if(receipt==null || receipt.state()!=State.COMMITTED) return ambiguous(entry);
        if(entry.stage()==Stage.QUEUED) {
            // Existing accepted receipt remains committed. A crash at this publication boundary is quarantined on startup.
            entry=orders.change(intent.id(),Stage.QUEUED,Stage.QUEUING,Blocked.NO_SPACE,null);
            var pending=pending(intent); village.setPendingProject(pending); village.setNoProjectsLeftUntil(0);
            publications.put(intent.id(),new Publication(village,pending));
        }
        persistence.pending(level,intent.village(),pending(intent));
        entry=orders.change(intent.id(),Stage.QUEUING,Stage.QUEUED,Blocked.NONE,null); publications.remove(intent.id());
        return new RoyalConstructionMonitor(level,orders,persistence).observe(entry);
    }
    /** Before engine ticks on a new JVM: field equality cannot establish interrupted publication ownership. */
    public int quarantineInterruptedPublications() {
        thread(); int count=0;
        for(var entry:orders.entries()) if(entry.intent().dimension().equals(level.dimension().location().toString()) && entry.stage()==Stage.QUEUING) {
            ambiguous(entry); count++;
        }
        return count;
    }
    public static void clearRuntime(MinecraftServer server) { PUBLICATIONS.remove(server); BUSY.remove(server); }

    /** Resume only a durable governance retirement. Offline actors are not routed through the NPC request path. */
    public Entry settleRetired(UUID id) {
        thread(); var entry=orders.load(id); if(entry==null) throw new IllegalArgumentException("Unknown retired order");
        if(!entry.intent().dimension().equals(level.dimension().location().toString())) throw new IllegalArgumentException("Retired order belongs to another dimension");
        return exclusive(entry.intent().village(),()->{
            var current=orders.load(id);
            if(current.terminal() || current.stage()==Stage.RECOVERY_REQUIRED) return current;
            if(current.stage()!=Stage.CANCELLING || current.blocked()!=Blocked.AUTHORITY) throw new IllegalArgumentException("Only a durable authority retirement can use historical settlement");
            var origin=current.cancellationFrom();
            if(origin==Stage.WAITING) {
                var i=current.intent();
                try { if(payments.inspectConstruction(id,i.kingdom(),i.capital(),i.actor(),i.fee())!=null) return ambiguous(current); }
                catch(IllegalArgumentException conflict) { return ambiguous(current); }
                return orders.change(id,Stage.CANCELLING,Stage.CANCELLED,Blocked.AUTHORITY,null);
            }
            boolean refund=origin==Stage.REQUESTED || origin==Stage.PAID;
            if(!refund && origin!=Stage.QUEUED && origin!=Stage.IN_PROGRESS) return ambiguous(current);
            var intent=current.intent(); RoyalPaymentService.Receipt receipt;
            try { receipt=payments.settleRetiredConstruction(id,intent.kingdom(),intent.capital(),intent.actor(),intent.fee(),refund); }
            catch(IllegalArgumentException conflict) { return ambiguous(current); }
            if(receipt==null ? origin!=Stage.REQUESTED : receipt.state()!=(refund ? State.REFUNDED : State.COMMITTED)) return ambiguous(current);
            return orders.change(id,Stage.CANCELLING,Stage.CANCELLED,Blocked.AUTHORITY,current.building());
        });
    }

    private Entry cancel(ServerPlayer player,UUID kingdom,UUID id) {
        thread(); var entry=orders.load(id); if(entry==null) throw new IllegalArgumentException("Unknown royal order");
        bind(entry,player,kingdom);
        return exclusive(entry.intent().village(),()->{
            var current=orders.load(id);
            if(current.terminal() || current.stage()==Stage.RECOVERY_REQUIRED) return current;
            if(current.stage()!=Stage.CANCELLING) current=orders.change(id,current.stage(),Stage.CANCELLING,Blocked.NONE,current.building());
            // This durable decision must precede any financial operation, including interrupted PREPARED refunds.
            return finishCancellation(current,player);
        });
    }

    public Entry requestQuotedUpgrade(ServerPlayer player,UUID villageId,UUID buildingId,UUID id,long expectedFee){
        thread();dimension(player);var k=PoliticsService.ruledKingdom(player);var village=PoliticsService.village(level,villageId);
        var building=village==null?null:village.getBuilding(new org.millenaire.building.BuildingId(buildingId));
        if(building==null)throw new IllegalArgumentException("Upgrade building is missing");
        return requestQuotedUpgrade(player,villageId,buildingId,id,expectedFee,building.getLevel()+1);
    }
    public Entry requestQuotedUpgrade(ServerPlayer player,UUID villageId,UUID buildingId,UUID id,long expectedFee,int expectedLevel){
        thread();dimension(player);var k=PoliticsService.ruledKingdom(player);var village=PoliticsService.village(level,villageId);
        var building=village==null?null:village.getBuilding(new org.millenaire.building.BuildingId(buildingId));
        if(building==null)throw new IllegalArgumentException("Upgrade building is missing");
        return request(player,k.id(),villageId,building.getPlanSetId(),id,expectedFee,buildingId,expectedLevel);
    }
    private Entry request(ServerPlayer player,UUID kingdom,UUID villageId,ResourceLocation selected,UUID id,Long expectedFee) {
        return request(player,kingdom,villageId,selected,id,expectedFee,null,null);
    }
    private Entry request(ServerPlayer player,UUID kingdom,UUID villageId,ResourceLocation selected,UUID id,Long expectedFee,UUID upgradeBuilding,Integer expectedTargetLevel) {
        thread(); var catalog=catalog(player,kingdom,villageId);
        return exclusive(villageId,()->{
            var old=orders.load(id);
            if(old!=null) {
                bind(old,player,kingdom);
                if(upgradeBuilding!=null && (!upgradeBuilding.equals(old.intent().upgradeBuilding()) || expectedTargetLevel!=old.intent().targetLevel()))throw new IllegalArgumentException("Upgrade ID is bound to another target or level");
                if(!old.intent().village().equals(villageId) || !old.intent().planSet().equals(selected)) throw new IllegalArgumentException("Order ID is bound to another village or building");
                return advance(old,player);
            }
            if(!catalog.active() || !level.isLoaded(PoliticsService.village(level,villageId).getCenter())) throw new IllegalStateException("Village is unloaded; postpone royal construction");
            if(catalog.naturalProjectPending() && player==null) throw new IllegalStateException("Village already has natural pending work; wait without replacing it");
            var choice=(upgradeBuilding==null?catalog.options():catalog.upgrades()).stream().filter(o->o.planSet().equals(selected) && java.util.Objects.equals(o.upgradeBuilding(),upgradeBuilding) && (expectedTargetLevel==null || expectedTargetLevel==o.targetLevel())).findFirst().orElseThrow(()->new IllegalArgumentException("Building is not a current normal-growth choice"));
            if(expectedFee!=null && choice.patronageFee()!=expectedFee) throw new com.millenairekingdoms.util.KingdomException(
                    "millenairekingdoms.gui.msg.construction_price_changed","The patronage price changed. Refresh and review the new offer before ordering.");
            template(choice.plan());
            var data=KingdomSavedData.get(level); var village=PoliticsService.village(level,villageId);
            var intent=new Intent(id,level.dimension().location().toString(),kingdom,data.kingdom(kingdom).capital(),villageId,
                    player==null ? null : player.getUUID(),player==null ? 0 : data.ruler(kingdom).sinceGameTime(),village.getVillageTypeId(),
                    choice.planSet(),choice.plan(),choice.variant(),choice.patronageFee(),Math.max(0,level.getGameTime()),
                    village.getBuildings().stream().map(b->b.getId().uuid()).collect(Collectors.toSet()),choice.upgradeBuilding(),choice.targetLevel());
            var reserved=catalog.naturalProjectPending() ? orders.reserveWaiting(intent) : orders.reserve(intent);
            if(reserved.stage()==Stage.WAITING)RoyalConstructionRuntime.watchWaiting(level,reserved);
            return advance(reserved,player);
        });
    }
    private Entry retry(ServerPlayer player,UUID kingdom,UUID id) {
        thread(); var entry=orders.load(id); if(entry==null) throw new IllegalArgumentException("Unknown royal order");
        bind(entry,player,kingdom);
        return exclusive(entry.intent().village(),()->advance(orders.load(id),player));
    }

    private Entry advance(Entry entry,ServerPlayer player) {
        var intent=entry.intent(); bind(entry,player,intent.kingdom());
        var village=PoliticsService.village(level,intent.village());
        if(entry.terminal() || entry.stage()==Stage.RECOVERY_REQUIRED) return entry;
        if(entry.stage()==Stage.CANCELLING) return finishCancellation(entry,player);
        if(entry.stage()==Stage.WAITING) {
            var current=catalog(player,intent.kingdom(),intent.village());
            if(!current.active() || !level.isLoaded(village.getCenter())) return blocked(entry,Blocked.UNLOADED);
            if(current.naturalProjectPending()) return blocked(entry,Blocked.NATURAL_PROJECT);
            if(!eligible(current,intent)) return blocked(entry,Blocked.MISSING_PLAN);
            template(intent.plan());
            // WAITING has never authorized a debit. Preserve an unexpected colliding receipt for explicit recovery.
            if(payments.inspectPlayer(player,intent.id())!=null) return ambiguous(entry);
            try {
                if(RoyalTreasurySnapshot.forPlayer(player).spendableValue()<intent.fee()) return blocked(entry,Blocked.INSUFFICIENT_FUNDS);
            } catch(IllegalStateException unavailable) { return blocked(entry,Blocked.TREASURY_UNAVAILABLE); }
            entry=orders.change(intent.id(),Stage.WAITING,Stage.REQUESTED,Blocked.NONE,null);
        }
        if(entry.stage()==Stage.REQUESTED) {
            var current=catalog(player,intent.kingdom(),intent.village());
            if(!current.active() || !level.isLoaded(village.getCenter())) return blocked(entry,Blocked.UNLOADED);
            if(current.naturalProjectPending()) return blocked(entry,Blocked.NATURAL_PROJECT);
            if(!eligible(current,intent)) return blocked(entry,Blocked.MISSING_PLAN);
            template(intent.plan());
            var receipt=player==null ? payments.payNpc(intent.kingdom(),intent.id(),Purpose.CONSTRUCTION,intent.id(),intent.fee())
                    : payments.payPlayer(player,intent.id(),Purpose.CONSTRUCTION,intent.id(),intent.fee());
            if(receipt.state()!=State.PAID) return orders.change(intent.id(),entry.stage(),Stage.RECOVERY_REQUIRED,Blocked.AMBIGUOUS_ENGINE_STATE,null);
            entry=orders.change(intent.id(),Stage.REQUESTED,Stage.PAID,Blocked.NONE,null);
        }
        boolean freshPublication=false;
        if(entry.stage()==Stage.PAID) {
            var current=catalog(player,intent.kingdom(),intent.village());
            if(!current.active() || !level.isLoaded(village.getCenter())) return blocked(entry,Blocked.UNLOADED);
            if(current.naturalProjectPending()) return blocked(entry,Blocked.NATURAL_PROJECT);
            if(!eligible(current,intent)) return blocked(entry,Blocked.MISSING_PLAN);
            entry=orders.beginPublication(intent.id(),village.getBuildings().stream().map(b->b.getId().uuid()).collect(Collectors.toSet()));
            intent=entry.intent();
            freshPublication=true;
        }
        if(entry.stage()==Stage.QUEUING) {
            var publications=PUBLICATIONS.computeIfAbsent(level.getServer(),key->new java.util.HashMap<>());
            var publication=publications.get(intent.id());
            if(!freshPublication && (publication==null || publication.village()!=village)) return ambiguous(entry);
            var fresh=newBuildings(village,intent);
            if(fresh.size()>1) return ambiguous(entry);
            if(!fresh.isEmpty()) {
                persistence.building(level,intent.village(),fresh.getFirst());
                entry=orders.change(intent.id(),Stage.QUEUING,Stage.IN_PROGRESS,Blocked.NONE,fresh.getFirst().getId().uuid());
            } else {
                var pending=pending(intent);
                if(village.getPendingProject()==null) {
                    if(!freshPublication) return ambiguous(entry);
                    var current=catalog(player,intent.kingdom(),intent.village());
                    if(!current.active() || !level.isLoaded(village.getCenter())) return blocked(entry,Blocked.UNLOADED);
                    if(!eligible(current,intent)) return blocked(entry,Blocked.MISSING_PLAN);
                    template(intent.plan()); village.setPendingProject(pending); village.setNoProjectsLeftUntil(0);
                    publications.put(intent.id(),new Publication(village,pending));
                } else if(publication==null || publication.pending()!=village.getPendingProject() || !pending.equals(village.getPendingProject())) return ambiguous(entry);
                // Original serializer, asynchronous worker acknowledgement and exact disk read-back before acceptance.
                persistence.pending(level,intent.village(),pending);
                entry=orders.change(intent.id(),Stage.QUEUING,Stage.QUEUED,Blocked.NONE,null);
            }
            publications.remove(intent.id());
        }
        if(entry.stage()==Stage.QUEUED || entry.stage()==Stage.IN_PROGRESS) {
            // A queued record follows proven durable acceptance, even if a later engine placement attempt is blocked.
            var receipt=player==null ? payments.inspectNpc(intent.kingdom(),intent.id()) : payments.inspectPlayer(player,intent.id());
            if(receipt==null || receipt.amount()!=intent.fee() || receipt.purpose()!=Purpose.CONSTRUCTION || !receipt.reference().equals(intent.id())
                    || (receipt.state()!=State.PAID && receipt.state()!=State.COMMITTED)) {
                return orders.change(intent.id(),entry.stage(),Stage.RECOVERY_REQUIRED,Blocked.AMBIGUOUS_ENGINE_STATE,entry.building());
            }
            if(receipt.state()==State.PAID) {
                if(player==null) payments.commitNpc(intent.kingdom(),intent.id()); else payments.commitPlayer(player,intent.id());
            }
            entry=new RoyalConstructionMonitor(level,orders,persistence).observe(entry);
        }
        return entry;
    }

    private Entry finishCancellation(Entry entry,ServerPlayer player) {
        var intent=entry.intent(); var origin=entry.cancellationFrom();
        // QUEUING can straddle original SavedData publication. Equality with a natural project cannot prove ownership.
        // Legacy v1 CANCELLING also lost its origin. Preserve both for explicit recovery; never speculate a refund.
        if(origin==Stage.QUEUING || origin==Stage.CANCELLING) return ambiguous(entry);
        RoyalPaymentService.Receipt receipt;
        try { receipt=player==null ? payments.inspectNpc(intent.kingdom(),intent.id()) : payments.inspectPlayer(player,intent.id()); }
        catch(IllegalArgumentException conflict) { return ambiguous(entry); }
        if(receipt!=null && !matches(receipt,intent)) return ambiguous(entry);
        if(origin==Stage.WAITING) {
            if(receipt!=null) return ambiguous(entry);
        } else if(origin==Stage.REQUESTED || origin==Stage.PAID) {
            if(receipt==null) {
                if(origin!=Stage.REQUESTED) return ambiguous(entry);
            } else {
                if(receipt.state()!=State.PREPARED && receipt.state()!=State.PAID && receipt.state()!=State.REFUND_PREPARED && receipt.state()!=State.REFUNDED) return ambiguous(entry);
                receipt=player==null ? payments.refundNpc(intent.kingdom(),intent.id()) : payments.refundPlayer(player,intent.id());
                if(receipt.state()!=State.REFUNDED) return ambiguous(entry);
            }
        } else if(origin==Stage.QUEUED || origin==Stage.IN_PROGRESS) {
            if(receipt==null || (receipt.state()!=State.PAID && receipt.state()!=State.COMMITTED)) return ambiguous(entry);
            if(receipt.state()==State.PAID) {
                if(player==null) payments.commitNpc(intent.kingdom(),intent.id()); else payments.commitPlayer(player,intent.id());
            }
            // Accepted patronage is earned. Stop tracking only: do not clear pending work or touch autonomous builders.
        } else return ambiguous(entry);
        return orders.change(intent.id(),Stage.CANCELLING,Stage.CANCELLED,Blocked.NONE,entry.building());
    }

    private static boolean matches(RoyalPaymentService.Receipt receipt,Intent intent) {
        return receipt.amount()==intent.fee() && receipt.purpose()==Purpose.CONSTRUCTION && receipt.reference().equals(intent.id());
    }
    private Entry ambiguous(Entry entry) {
        var publications=PUBLICATIONS.get(level.getServer()); if(publications!=null) publications.remove(entry.intent().id());
        return orders.change(entry.intent().id(),entry.stage(),Stage.RECOVERY_REQUIRED,Blocked.AMBIGUOUS_ENGINE_STATE,entry.building());
    }

    private boolean eligible(RoyalConstructionCatalog.Catalog catalog,Intent intent) {
        var set=ModCultures.getBuildingPlanSet(intent.planSet()); var def=set==null ? null : set.getLevel(intent.variant(),intent.targetLevel());
        return (intent.upgrade()?catalog.upgrades():catalog.options()).stream().anyMatch(o->o.planSet().equals(intent.planSet())
                && java.util.Objects.equals(o.upgradeBuilding(),intent.upgradeBuilding()) && o.targetLevel()==intent.targetLevel())
                && def!=null && def.planId().equals(intent.plan()) && (intent.upgrade() || NormalGrowthBridge.conditions(PoliticsService.village(level,intent.village()),def));
    }
    private void template(ResourceLocation id) {
        var plan=ModCultures.getBuildingPlan(id);
        if(plan==null || MillenaireServerConfig.SERVER.ignoreResourceCost.get()
                || BuildingPlacer.compilePreviewSteps(level,plan).stream().noneMatch(s->!s.blockState().isAir())) {
            throw new IllegalStateException("Royal construction needs a real nonempty template and enabled resource costs");
        }
    }
    private RoyalConstructionCatalog.Catalog catalog(ServerPlayer player,UUID kingdom,UUID village) {
        if(player!=null) dimension(player);
        var result=player==null ? RoyalConstructionCatalog.forNpc(level,kingdom,village) : RoyalConstructionCatalog.forPlayer(player,village);
        if(!result.kingdom().equals(kingdom)) throw new IllegalArgumentException("Ruler's kingdom changed"); return result;
    }
    private void bind(Entry entry,ServerPlayer player,UUID kingdom) {
        var intent=entry.intent();
        if(!intent.dimension().equals(level.dimension().location().toString()) || !intent.kingdom().equals(kingdom)
                || !java.util.Objects.equals(intent.actor(),player==null ? null : player.getUUID())) throw new IllegalArgumentException("Order belongs to another dimension, kingdom or ruler");
        catalog(player,kingdom,intent.village()); var data=KingdomSavedData.get(level);
        if(!data.kingdom(kingdom).capital().equals(intent.capital()) || !PoliticsService.village(level,intent.village()).getVillageTypeId().equals(intent.villageType())
                || (player!=null && data.ruler(kingdom).sinceGameTime()!=intent.rulerSince())) throw new IllegalArgumentException("Order authority, capital or village type changed");
    }
    private static Village.PendingProject pending(Intent intent) { return intent.pending(); }
    private static java.util.List<BuildingInstance> newBuildings(Village village,Intent intent) {
        return village.getBuildings().stream().filter(b->RoyalConstructionMonitor.fulfillment(b,intent)).toList();
    }
    private Entry blocked(Entry entry,Blocked reason) { return orders.change(entry.intent().id(),entry.stage(),entry.stage(),reason,entry.building()); }
    private <T> T exclusive(UUID village,Supplier<T> action) {
        Set<UUID> busy=BUSY.computeIfAbsent(level.getServer(),key->new HashSet<>());
        if(!busy.add(village)) throw new IllegalStateException("Village order is already being processed");
        try { return action.get(); } finally { busy.remove(village); }
    }
    private void dimension(ServerPlayer player) { if(player.serverLevel()!=level) throw new IllegalArgumentException("Ruler moved to another dimension"); }
    private void thread() { if(!level.getServer().isSameThread()) throw new IllegalStateException("Royal construction requires the server thread"); }
}
