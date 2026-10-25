package com.millenairekingdoms.construction;

import com.millenairekingdoms.economy.RoyalEconomyConfig;
import com.millenairekingdoms.economy.RoyalPrices;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.politics.PoliticsService;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.millenaire.culture.ModCultures;
import org.millenaire.village.Village;

/** Authorized immutable previews of real normal-growth choices. This is not an order or payment receipt.
 * No placement, ownership, pending-project, resource or chunk mutation. An order service must revalidate before paying. */
public final class RoyalConstructionCatalog {
    public record Option(ResourceLocation planSet,ResourceLocation plan,String variant,String nativeName,long patronageFee,
                         Map<ResourceLocation,Integer> requiredMaterials) {
        public Option { requiredMaterials=Map.copyOf(requiredMaterials); }
    }
    public record Catalog(UUID kingdom,UUID village,boolean active,boolean naturalProjectPending,List<Option> options) {
        public Catalog { options=List.copyOf(options); }
    }
    private RoyalConstructionCatalog() {}

    /** Mirrors the original ordinary-construction slot: native walls have their own independent slot. */
    public static boolean naturalWorkPending(Village village) {
        return village.getPendingProject()!=null || village.getBuildings().stream().anyMatch(b->b.isBeingBuilt() && !b.isWallSegment());
    }

    public static Catalog forNpc(ServerLevel level,UUID kingdom,UUID village) { return read(level,kingdom,village,null); }
    public static Catalog forPlayer(ServerPlayer player,UUID village) {
        thread(player.serverLevel());
        return read(player.serverLevel(),PoliticsService.ruledKingdom(player).id(),village,player.getUUID());
    }
    /** Already-issued work only, after the monitor rechecks its original UUID-bound mandate. No new spending agency. */
    static Catalog forIssuedOrder(ServerLevel level,RoyalConstructionStore.Intent intent) {
        return read(level,intent.kingdom(),intent.village(),intent.actor());
    }

    private static Catalog read(ServerLevel level,UUID kingdom,UUID id,UUID actor) {
        thread(level); var data=KingdomSavedData.get(level);
        if(!data.mayDecide(kingdom,actor) || !kingdom.equals(data.owner(id))) {
            throw new IllegalArgumentException("Royal construction requires the current ruler and a village of their active kingdom");
        }
        var village=PoliticsService.village(level,id);
        if(village==null) throw new IllegalArgumentException("Village is absent from Millenaire's records");
        var type=ModCultures.getVillageType(village.getVillageTypeId());
        if(type==null || type.playerControlled() || village.getOwnerUUID()!=null) {
            throw new IllegalArgumentException("Royal construction is available only for recognized autonomous NPC villages");
        }
        var choices=new LinkedHashMap<ResourceLocation,Option>(); long fee=RoyalPrices.construction(RoyalEconomyConfig.current());
        for(var candidate:NormalGrowthBridge.candidates(level,village,type)) {
            var set=candidate.set(); var def=candidate.level(); var plan=ModCultures.getBuildingPlan(def.planId());
            if(candidate.upgrade() || def.level()!=0 || set.isTownHall() || set.isSubBuilding() || set.parentBuilding()!=null
                    || !set.culture().equals(village.getCultureId()) || plan==null || !plan.culture().equals(village.getCultureId())) continue;
            long count=village.getBuildings().stream().filter(b->set.id().equals(b.getPlanSetId())).count();
            if(set.maxCount()>0 && count>=set.maxCount()) continue;
            if(def.requiredResources().values().stream().anyMatch(n->n<0)) continue;
            choices.putIfAbsent(set.id(),new Option(set.id(),plan.id(),candidate.variant(),set.nativeName(),fee,def.requiredResources()));
        }
        return new Catalog(kingdom,id,village.isActive(),naturalWorkPending(village),
                choices.values().stream().sorted(Comparator.comparing(o->o.planSet.toString())).toList());
    }
    private static void thread(ServerLevel level) {
        if(!level.getServer().isSameThread()) throw new IllegalStateException("Royal construction catalog requires the server thread");
    }
}
