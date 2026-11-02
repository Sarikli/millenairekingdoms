package com.millenairekingdoms.autonomy;

import com.millenairekingdoms.kingdom.KingdomSavedData;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.millenaire.Millenaire;
import org.millenaire.building.AnywoodHelper;
import org.millenaire.culture.ModCultures;
import org.millenaire.entity.MillVillager;
import org.millenaire.goal.*;
import org.millenaire.goal.impl.DeliverResourcesToShopGoal;

/** Native9.0.3 shop delivery scores ten points per carried item, so163 construction logs outrank build's1500.
 * Use the original public goal registry extension point to preserve assigned construction cargo. No inventory moves,
 * forced tasks, altered native building steps, extra currency or replacement NPCs. All other eligibility is delegated. */
public final class RoyalBuilderCargo {
    private RoyalBuilderCargo() {}
    public static boolean reservedConstructionCargo(GoalContext context) {
        var npc=context.villager();var village=context.village();if(village==null || npc.getConstructionBuildingId()==null)return false;
        var data=KingdomSavedData.get(context.level());var owner=data.owner(village.getId().uuid());
        if(owner==null || !data.npcGoverned(owner))return false;
        var building=village.getBuilding(npc.getConstructionBuildingId());
        if(building==null || !building.isBeingBuilt() || building.getConstructionTask()==null || building.getConstructionTask().isComplete()
                || building.getPlanSetId()==null || building.getVariant()==null)return false;
        var reserved=building.getConstructionTask().getReservedBuilder();if(reserved!=null && !reserved.equals(npc.getUUID()))return false;
        var set=ModCultures.getBuildingPlanSet(building.getPlanSetId());var definition=set==null?null:set.getLevel(building.getVariant(),building.getLevel());
        if(definition==null)return false;
        for(var need:definition.requiredResources().entrySet()) {
            if(need.getValue()<1)continue;
            if(AnywoodHelper.isAnywood(need.getKey())) {
                if(npc.getInventory().getAll().entrySet().stream().anyMatch(e->e.getValue()>0 && new net.minecraft.world.item.ItemStack(e.getKey()).is(AnywoodHelper.LOGS_TAG)))return true;
            } else {
                var item=net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(need.getKey());
                if(item.isPresent() && npc.getInventory().getCount(item.get())>0)return true;
            }
        }
        return false;
    }
    private record ProtectedDelivery(VillagerGoal original) implements VillagerGoal {
        public ResourceLocation id(){return original.id();}
        public int computePriority(GoalContext c){return reservedConstructionCargo(c)?0:original.computePriority(c);}
        public boolean canStart(GoalContext c){return !reservedConstructionCargo(c) && original.canStart(c);}
        public VillagerTask start(GoalContext c){return original.start(c);}
        public boolean isLeisure(){return original.isLeisure();}
        public boolean showInTravelBook(){return original.showInTravelBook();}
        public boolean canBeDoneAtNight(){return original.canBeDoneAtNight();}
        public boolean canBeDoneInDayTime(){return original.canBeDoneInDayTime();}
        public long reoccurDelayTicks(){return original.reoccurDelayTicks();}
        public boolean isCombatUrgent(){return original.isCombatUrgent();}
    }
    private static boolean install() {
        var registry=Millenaire.getGoalRegistry();if(registry==null)return false;
        var goal=registry.get(DeliverResourcesToShopGoal.ID);
        if(goal==null || goal.getClass()!=DeliverResourcesToShopGoal.class)return false; // Retain other mods' custom replacements.
        registry.replace(new ProtectedDelivery(goal));return true;
    }
    private static void refreshIdleResidents(net.minecraft.server.MinecraftServer server) {
        // Native startup/content reload also initializes schedulers. Only refresh idle schedulers; never discard work.
        for(var level:server.getAllLevels())for(var entity:level.getAllEntities())if(entity instanceof MillVillager npc
                && npc.getVillagerTypeId()!=null && (npc.getGoalScheduler()==null || npc.getGoalScheduler().getCurrentTask()==null)) {
            var type=ModCultures.getVillagerType(npc.getVillagerTypeId());if(type!=null)npc.initGoals(Millenaire.getGoalRegistry(),type);
        }
    }
    public static void onStarted(ServerStartedEvent event){if(install())refreshIdleResidents(event.getServer());}
    public static void onPreTick(LevelTickEvent.Pre event){if(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level && install())refreshIdleResidents(level.getServer());}
}
