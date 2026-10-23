package com.millenairekingdoms.construction;

import com.millenairekingdoms.construction.RoyalConstructionStore.Blocked;
import com.millenairekingdoms.construction.RoyalConstructionStore.Entry;
import com.millenairekingdoms.construction.RoyalConstructionStore.Intent;
import com.millenairekingdoms.construction.RoyalConstructionStore.Stage;
import com.millenairekingdoms.economy.RoyalPaymentJournal.State;
import com.millenairekingdoms.economy.RoyalPaymentService;
import com.millenairekingdoms.economy.RoyalPaymentService.Purpose;
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

/** Internal paid request/retry/cancellation. No GUI/scheduler consumer until governance/builders gates pass.
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
    public Entry requestNpc(UUID kingdom,UUID village,ResourceLocation planSet,UUID id) { return request(null,kingdom,village,planSet,id); }
    public Entry requestPlayer(ServerPlayer player,UUID village,ResourceLocation planSet,UUID id) {
        thread(); dimension(player); return request(player,PoliticsService.ruledKingdom(player).id(),village,planSet,id);
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
        return exclusive(entry.intent().village(),()->new RoyalConstructionMonitor(level,orders,persistence).observe(orders.load(id)));
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

    private Entry request(ServerPlayer player,UUID kingdom,UUID villageId,ResourceLocation selected,UUID id) {
        thread(); var catalog=catalog(player,kingdom,villageId);
        return exclusive(villageId,()->{
            var old=orders.load(id);
            if(old!=null) {
                bind(old,player,kingdom);
                if(!old.intent().village().equals(villageId) || !old.intent().planSet().equals(selected)) throw new IllegalArgumentException("Order ID is bound to another village or building");
                return advance(old,player);
            }
            if(!catalog.active() || !level.isLoaded(PoliticsService.village(level,villageId).getCenter())) throw new IllegalStateException("Village is unloaded; postpone royal construction");
            if(catalog.naturalProjectPending()) throw new IllegalStateException("Village already has natural pending work; wait without replacing it");
            var choice=catalog.options().stream().filter(o->o.planSet().equals(selected)).findFirst().orElseThrow(()->new IllegalArgumentException("Building is not a current normal-growth choice"));
            template(choice.plan());
            var data=KingdomSavedData.get(level); var village=PoliticsService.village(level,villageId);
            var intent=new Intent(id,level.dimension().location().toString(),kingdom,data.kingdom(kingdom).capital(),villageId,
                    player==null ? null : player.getUUID(),player==null ? 0 : data.ruler(kingdom).sinceGameTime(),village.getVillageTypeId(),
                    choice.planSet(),choice.plan(),choice.variant(),choice.patronageFee(),Math.max(0,level.getGameTime()),
                    village.getBuildings().stream().map(b->b.getId().uuid()).collect(Collectors.toSet()));
            return advance(orders.reserve(intent),player);
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
            entry=orders.change(intent.id(),Stage.PAID,Stage.QUEUING,Blocked.NONE,null);
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
        if(origin==Stage.REQUESTED || origin==Stage.PAID) {
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
        var set=ModCultures.getBuildingPlanSet(intent.planSet()); var def=set==null ? null : set.getLevel(intent.variant(),0);
        return catalog.options().stream().anyMatch(o->o.planSet().equals(intent.planSet())) && def!=null && def.planId().equals(intent.plan())
                && NormalGrowthBridge.conditions(PoliticsService.village(level,intent.village()),def);
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
    private static Village.PendingProject pending(Intent intent) { return new Village.PendingProject(intent.planSet(),intent.variant(),0,false,null); }
    private static java.util.List<BuildingInstance> newBuildings(Village village,Intent intent) {
        return village.getBuildings().stream().filter(b->!intent.originalBuildings().contains(b.getId().uuid()) && intent.planSet().equals(b.getPlanSetId())
                && intent.plan().equals(b.getPlanId()) && intent.variant().equals(b.getVariant()) && b.getLevel()==0).toList();
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
