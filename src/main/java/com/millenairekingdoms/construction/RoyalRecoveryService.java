package com.millenairekingdoms.construction;

import com.millenairekingdoms.construction.RoyalConstructionStore.Stage;
import com.millenairekingdoms.construction.RoyalConstructionStore.Blocked;
import com.millenairekingdoms.construction.RoyalRecoveryStore.Mode;
import com.millenairekingdoms.construction.RoyalRecoveryStore.State;
import com.millenairekingdoms.economy.RoyalPaymentService;
import com.millenairekingdoms.economy.RoyalPaymentJournal;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.politics.PoliticsService;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import org.millenaire.building.BuildingId;
import org.millenaire.building.BuildingInstance;
import org.millenaire.config.MillenaireServerConfig;
import org.millenaire.culture.ModCultures;
import org.millenaire.village.Village;

/** OP-authorized existing-work recovery. No payment reset, new debit, refund, new project, placement or NPC creation. */
public final class RoyalRecoveryService {
    private final ServerLevel level;
    private final RoyalConstructionStore orders;
    private final RoyalRecoveryStore audit;
    private final RoyalPaymentService payments;
    private final RoyalVillagePersistence persistence;
    public RoyalRecoveryService(ServerLevel level) {this(level,new RoyalVillagePersistence());}
    RoyalRecoveryService(ServerLevel level,RoyalVillagePersistence persistence) {
        this.level=level; this.persistence=persistence;
        var root=level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms");
        orders=new RoyalConstructionStore(root.resolve("royal-construction")); audit=new RoyalRecoveryStore(directory(level)); payments=new RoyalPaymentService(level);
    }
    public List<RoyalRecoveryStore.Entry> entries(CommandSourceStack source) {
        operator(source); return audit.entries().stream().filter(e->e.intent().order().dimension().equals(level.dimension().location().toString())).toList();
    }
    public RoyalConstructionStore.Entry inspectOrder(CommandSourceStack source,UUID id) {operator(source);return order(id);}

    /** Durable reviewed intent BEFORE finance or order resolution. Caller can resume this ID after any failed ack. */
    public RoyalRecoveryStore.Entry authorize(CommandSourceStack source,UUID decision,UUID id,Mode mode,UUID building,String reason) {
        operator(source); var order=order(id); var prior=audit.load(decision);
        var request=new RoyalRecoveryStore.Intent(decision,order.intent(),mode,building,actor(source),source.getTextName(),reason,
                prior==null ? Math.max(0,level.getGameTime()) : prior.intent().issued());
        if(prior!=null) {
            if(!prior.intent().equals(request)) throw new IllegalArgumentException("Recovery ID is already bound to another operator/reason/order/evidence"); return prior;
        }
        recoverable(order,mode); receipt(order.intent());
        if(mode!=Mode.FORFEIT_FEE) {mandate(order);evidence(order,mode,building);}
        return audit.authorize(request);
    }
    public RoyalRecoveryStore.Entry resume(CommandSourceStack source,UUID decision) {
        operator(source); return apply(decision,actor(source),source.getTextName());
    }
    /** Scheduler may only resume a decision already authorized and persisted by an OP; it cannot create one. */
    @org.jetbrains.annotations.ApiStatus.Internal
    public RoyalRecoveryStore.Entry resumeAuthorized(UUID decision) {thread();return apply(decision,null,"recovery-runtime");}

    public RoyalRecoveryStore.Entry abandon(CommandSourceStack source,UUID decision,String reason) {
        operator(source); var entry=decision(decision); if(entry.state()!=State.AUTHORIZED) return entry;
        var order=order(entry.intent().order().id()); binding(entry,order);
        if(entry.intent().id().equals(order.recoveryDecision())) throw new IllegalStateException("Decision already applied to the order; resume its audit instead of abandoning it");
        return audit.finish(decision,State.ABANDONED,new RoyalRecoveryStore.Result(actor(source),source.getTextName(),reason,Math.max(0,level.getGameTime())));
    }
    private RoyalRecoveryStore.Entry apply(UUID id,UUID executor,String source) {
        var entry=decision(id); if(entry.state()!=State.AUTHORIZED) return entry;
        var intent=entry.intent(); var order=order(intent.order().id()); binding(entry,order);
        if(!id.equals(order.recoveryDecision())) {
            recoverable(order,intent.mode());
            if(intent.mode()!=Mode.FORFEIT_FEE) {
                mandate(order); var building=evidence(order,intent.mode(),intent.building());
                if(building==null) persistence.pending(level,order.intent().village(),pending(order.intent()));
                else {
                    if(!level.isLoaded(building.getOrigin())) throw new IllegalStateException("Existing recovery building chunk unloaded; wait without forcing it");
                    persistence.building(level,order.intent().village(),building);
                }
                mandate(order(entry.intent().order().id()));
            }
            receipt(order.intent()); // The immutable exact receipt is checked again after original evidence acknowledgement.
            var paid=payments.acknowledgeConstruction(order.intent().id(),order.intent().kingdom(),order.intent().capital(),order.intent().actor(),order.intent().fee());
            if(paid==null || paid.state()!=RoyalPaymentJournal.State.COMMITTED) throw new IllegalStateException("Existing patronage could not be acknowledged; retain recovery decision");
            Stage next=intent.mode()==Mode.FORFEIT_FEE ? Stage.CANCELLED : intent.mode()==Mode.ADOPT_PENDING ? Stage.QUEUED : Stage.IN_PROGRESS;
            orders.resolve(order.intent().id(),id,next,intent.mode()==Mode.FORFEIT_FEE ? order.building() : intent.building());
        } else {
            // Later progress/retirement may have changed stage. Atomic provenance still proves this decision applied.
            var paid=receipt(order.intent());
            if(paid.state()!=RoyalPaymentJournal.State.COMMITTED) throw new IllegalStateException("Applied recovery lacks its exact committed receipt; preserve audit");
        }
        return audit.finish(id,State.DONE,new RoyalRecoveryStore.Result(executor,source,"Applied "+intent.mode()+"; original work preserved; fee retained",Math.max(0,level.getGameTime())));
    }
    private void mandate(RoyalConstructionStore.Entry entry) {
        var i=entry.intent(); var data=KingdomSavedData.get(level); var kingdom=data.kingdom(i.kingdom()); var ruler=data.ruler(i.kingdom());
        var village=PoliticsService.village(level,i.village()); var type=village==null ? null : ModCultures.getVillageType(village.getVillageTypeId());
        var capital=PoliticsService.village(level,i.capital());
        if(entry.cancellationFrom()!=null || !data.mayDecide(i.kingdom(),i.actor()) || kingdom==null || !i.capital().equals(kingdom.capital())
                || !i.kingdom().equals(data.owner(i.village())) || type==null || type.playerControlled() || village.getOwnerUUID()!=null
                || !i.villageType().equals(village.getVillageTypeId()) || MillenaireServerConfig.SERVER.ignoreResourceCost.get()
                || i.actor()!=null && (ruler==null || ruler.sinceGameTime()!=i.rulerSince() || capital==null
                    || capital.getCombinedReputation(level,i.actor())<PoliticsService.DECISION_REPUTATION)) {
            throw new IllegalArgumentException("Recovery cannot adopt a cancelled, retired, foreign or invalid mandate");
        }
    }
    private BuildingInstance evidence(RoyalConstructionStore.Entry entry,Mode mode,UUID selected) {
        var i=entry.intent(); var village=PoliticsService.village(level,i.village());
        if(village==null) throw new IllegalArgumentException("Original village is missing; recovery never creates a replacement");
        if(mode==Mode.ADOPT_PENDING) {
            if(entry.building()!=null || !pending(i).equals(village.getPendingProject())
                    || village.getBuildings().stream().anyMatch(b->RoyalConstructionMonitor.fulfillment(b,i))) {
                throw new IllegalArgumentException("No unambiguous exact original pending project exists; inspect or choose a genuine building UUID");
            }
            return null;
        }
        var building=selected==null ? null : village.getBuilding(new BuildingId(selected));
        if(building==null || (!i.upgrade() && i.originalBuildings().contains(selected)) || entry.building()!=null && !entry.building().equals(selected)
                || !RoyalConstructionMonitor.lineage(building,i) || !RoyalConstructionMonitor.credible(building,i)
                || village.getBuildings().stream().filter(b->RoyalConstructionMonitor.fulfillment(b,i)).count()!=1
                || pending(i).equals(village.getPendingProject())) throw new IllegalArgumentException("Selected building is not unambiguous genuine new work for this immutable order");
        return building;
    }
    private RoyalPaymentService.Receipt receipt(RoyalConstructionStore.Intent i) {
        var receipt=payments.inspectConstruction(i.id(),i.kingdom(),i.capital(),i.actor(),i.fee());
        if(receipt==null || receipt.state()!=RoyalPaymentJournal.State.PAID && receipt.state()!=RoyalPaymentJournal.State.COMMITTED) {
            throw new IllegalArgumentException("Recovery requires an exact durably PAID/COMMITTED construction receipt; ambiguous physical journals are never reset");
        }
        return receipt;
    }
    private static void recoverable(RoyalConstructionStore.Entry entry,Mode mode) {
        if(entry.stage()!=Stage.RECOVERY_REQUIRED && !(mode==Mode.FORFEIT_FEE && entry.stage()==Stage.CANCELLING && entry.blocked()==Blocked.AUTHORITY)) {
            throw new IllegalArgumentException("Only quarantined work or an authority-retired pending cancellation can be reviewed");
        }
        if(mode!=Mode.FORFEIT_FEE && entry.cancellationFrom()!=null) throw new IllegalArgumentException("A cancelled/retired mandate cannot be revived");
    }
    private RoyalConstructionStore.Entry order(UUID id) {
        var entry=orders.load(id);
        if(entry==null || !entry.intent().dimension().equals(level.dimension().location().toString())) throw new IllegalArgumentException("Unknown order in this dimension"); return entry;
    }
    private RoyalRecoveryStore.Entry decision(UUID id) {
        var entry=audit.load(id);
        if(entry==null || !entry.intent().order().dimension().equals(level.dimension().location().toString())) throw new IllegalArgumentException("Unknown recovery decision in this dimension"); return entry;
    }
    private static void binding(RoyalRecoveryStore.Entry decision,RoyalConstructionStore.Entry order) {
        if(!decision.intent().order().equals(order.intent())) throw new IllegalArgumentException("Recovery audit no longer matches the full immutable order intent");
    }
    private static Village.PendingProject pending(RoyalConstructionStore.Intent i) {return i.pending();}
    private void operator(CommandSourceStack source) {
        thread(); if(source.getLevel()!=level || !source.hasPermission(2)) throw new IllegalArgumentException("Operator permission level 2 in this dimension is required");
    }
    private static UUID actor(CommandSourceStack source) {return source.getEntity()==null ? null : source.getEntity().getUUID();}
    private void thread() {if(!level.getServer().isSameThread()) throw new IllegalStateException("Recovery requires the server thread");}
    public static Path directory(ServerLevel level) {return level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms/royal-recovery");}
}
