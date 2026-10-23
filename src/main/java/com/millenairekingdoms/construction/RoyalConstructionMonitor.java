package com.millenairekingdoms.construction;

import com.millenairekingdoms.construction.RoyalConstructionStore.Blocked;
import com.millenairekingdoms.construction.RoyalConstructionStore.Entry;
import com.millenairekingdoms.construction.RoyalConstructionStore.Intent;
import com.millenairekingdoms.construction.RoyalConstructionStore.Stage;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.politics.PoliticsService;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.millenaire.building.BuildingId;
import org.millenaire.building.BuildingInstance;
import org.millenaire.culture.ModCultures;
import org.millenaire.village.Village;

/** Observe original engine records only. Never places blocks, advances tasks, chooses locations, loads chunks or spends.
 * Only acknowledged original building/task metadata can advance a royal order. Offline player authority remains UUID-bound. */
final class RoyalConstructionMonitor {
    private final ServerLevel level;
    private final RoyalConstructionStore orders;
    private final RoyalVillagePersistence persistence;
    RoyalConstructionMonitor(ServerLevel level,RoyalConstructionStore orders,RoyalVillagePersistence persistence) {
        this.level=level; this.orders=orders; this.persistence=persistence;
    }

    Entry observe(Entry entry) {
        if(entry.terminal() || entry.stage()==Stage.RECOVERY_REQUIRED || entry.stage()==Stage.CANCELLING) return entry;
        var intent=entry.intent(); var data=KingdomSavedData.get(level); var village=PoliticsService.village(level,intent.village());
        var ruler=data.ruler(intent.kingdom()); var kingdom=data.kingdom(intent.kingdom()); var capital=PoliticsService.village(level,intent.capital());
        var type=village==null ? null : ModCultures.getVillageType(village.getVillageTypeId());
        if(!data.mayDecide(intent.kingdom(),intent.actor()) || kingdom==null || !intent.capital().equals(kingdom.capital())
                || !intent.kingdom().equals(data.owner(intent.village())) || type==null || type.playerControlled() || village.getOwnerUUID()!=null
                || !intent.villageType().equals(village.getVillageTypeId())
                || (intent.actor()!=null && (ruler==null || ruler.sinceGameTime()!=intent.rulerSince() || capital==null
                    || capital.getCombinedReputation(level,intent.actor())<PoliticsService.DECISION_REPUTATION))) {
            return orders.change(intent.id(),entry.stage(),Stage.CANCELLING,Blocked.AUTHORITY,entry.building());
        }
        if(!village.isActive() || !level.isLoaded(village.getCenter())) return blocked(entry,Blocked.UNLOADED);
        var set=ModCultures.getBuildingPlanSet(intent.planSet()); var definition=set==null ? null : set.getLevel(intent.variant(),0);
        if(definition==null || !definition.planId().equals(intent.plan())) return blocked(entry,Blocked.MISSING_PLAN);
        if(entry.stage()!=Stage.QUEUED && entry.stage()!=Stage.IN_PROGRESS) return entry;

        BuildingInstance building;
        if(entry.stage()==Stage.QUEUED) {
            var fresh=village.getBuildings().stream().filter(b->!intent.originalBuildings().contains(b.getId().uuid()) && lineage(b,intent)).toList();
            if(fresh.size()>1) return ambiguous(entry,Blocked.AMBIGUOUS_ENGINE_STATE);
            if(fresh.isEmpty()) {
                var pending=village.getPendingProject();
                if(village.getPlacementCooldowns().getOrDefault(intent.planSet(),0L)>level.getGameTime() || entry.blocked()==Blocked.NO_SPACE) return blocked(entry,Blocked.NO_SPACE);
                if(!Objects.equals(pending,new Village.PendingProject(intent.planSet(),intent.variant(),0,false,null))) {
                    return pending==null ? ambiguous(entry,Blocked.AMBIGUOUS_ENGINE_STATE) : blocked(entry,Blocked.NATURAL_PROJECT);
                }
                var inventory=village.getTownhall()==null ? null : village.getTownhall().getInventory();
                if(inventory==null) return blocked(entry,Blocked.UNLOADED);
                for(var position:inventory.getChestPositions()) {
                    if(!level.isLoaded(position)) return blocked(entry,Blocked.UNLOADED);
                    var state=level.getBlockState(position);
                    if(state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE)!=ChestType.SINGLE
                            && !level.isLoaded(position.relative(ChestBlock.getConnectedDirection(state)))) return blocked(entry,Blocked.UNLOADED);
                }
                return blocked(entry,NormalGrowthBridge.resources(level,village,definition) ? Blocked.NONE : Blocked.MATERIALS);
            }
            building=fresh.getFirst();
            if(Objects.equals(village.getPendingProject(),new Village.PendingProject(intent.planSet(),intent.variant(),0,false,null))) return ambiguous(entry,Blocked.AMBIGUOUS_ENGINE_STATE);
            if(!level.isLoaded(building.getOrigin())) return blocked(entry,Blocked.UNLOADED);
            if(!credible(building)) return ambiguous(entry,Blocked.AMBIGUOUS_ENGINE_STATE);
            persistence.building(level,intent.village(),building);
            entry=orders.change(intent.id(),Stage.QUEUED,Stage.IN_PROGRESS,Blocked.NONE,building.getId().uuid());
        } else {
            building=village.getBuilding(new BuildingId(entry.building()));
            if(building==null) return ambiguous(entry,Blocked.BUILDING_MISSING);
            if(!lineage(building,intent) || !credible(building)) return ambiguous(entry,Blocked.AMBIGUOUS_ENGINE_STATE);
            if(!level.isLoaded(building.getOrigin())) return blocked(entry,Blocked.UNLOADED);
        }
        // A valid subsequent engine upgrade also proves that this same new building's first level completed.
        if(building.getLevel()>0 || building.getStatus()==BuildingInstance.Status.COMPLETE && building.getConstructionTask().isComplete()) {
            persistence.building(level,intent.village(),building);
            return orders.change(intent.id(),Stage.IN_PROGRESS,Stage.COMPLETED,Blocked.NONE,entry.building());
        }
        return blocked(entry,building.getConstructionTask().isBlocked() ? Blocked.BUILDER_BLOCKED : Blocked.NONE);
    }

    private static boolean lineage(BuildingInstance building,Intent intent) {
        var set=ModCultures.getBuildingPlanSet(intent.planSet()); var definition=set==null ? null : set.getLevel(intent.variant(),building.getLevel());
        return definition!=null && building.getLevel()>=0 && intent.planSet().equals(building.getPlanSetId())
                && intent.variant().equals(building.getVariant()) && definition.planId().equals(building.getPlanId());
    }
    private static boolean credible(BuildingInstance building) {
        return building.getLevel()>0 || building.getConstructionTask()!=null && building.getConstructionTask().totalSteps()>0
                && (building.getStatus()!=BuildingInstance.Status.COMPLETE || building.getConstructionTask().isComplete());
    }
    private Entry blocked(Entry entry,Blocked blocked) { return orders.change(entry.intent().id(),entry.stage(),entry.stage(),blocked,entry.building()); }
    private Entry ambiguous(Entry entry,Blocked reason) { return orders.change(entry.intent().id(),entry.stage(),Stage.RECOVERY_REQUIRED,reason,entry.building()); }
}
