package com.millenairekingdoms.roads;

import com.millenairekingdoms.politics.PoliticsService;
import com.millenairekingdoms.shipping.NativeStockBridge;
import com.millenairekingdoms.shipping.RoyalShipmentConfig;
import com.millenairekingdoms.shipping.RoyalVillageStock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.millenaire.building.AnywoodHelper;
import org.millenaire.building.BuildingInstance;
import org.millenaire.building.BuildingInventory;
import org.millenaire.building.BuildingPlanSet;
import org.millenaire.building.GoodAvailabilityHelper;
import org.millenaire.commerce.TradeGoodsLoader;
import org.millenaire.culture.ModCultures;
import org.millenaire.village.Village;

/**
 * Inspects and consumes surplus road construction materials (cobblestone, gravel, planks) from loaded
 * endpoint townhall chests without ever loading chunks or deadlocking native village building upgrades.
 */
public final class RoadMaterialStock {
    private RoadMaterialStock() {}

    /** Returns true only when both endpoint villages are currently active and have loaded townhall chests. */
    public static boolean areBothEndpointsLoaded(ServerLevel level, UUID villageA, UUID villageB) {
        return resolveLoaded(level, villageA) != null && resolveLoaded(level, villageB) != null;
    }

    /** Counts total safe surplus units (cobblestone, gravel, planks) across loaded endpoint villages. */
    public static int availableSurplus(ServerLevel level, UUID villageA, UUID villageB) {
        int total = Math.max(0, availableForVillage(level, villageA));
        total += Math.max(0, availableForVillage(level, villageB));
        return total;
    }

    /**
     * Consumes up to {@code maxUnits} of surplus cobblestone/gravel/planks from loaded endpoint townhalls.
     * Never forces chunk loads and never dips below native building/resident reserves.
     */
    public static int consumeSurplus(ServerLevel level, UUID villageA, UUID villageB, int maxUnits) {
        if (maxUnits <= 0)
            return 0;
        int taken = consumeFromVillage(level, villageA, maxUnits);
        if (taken < maxUnits)
            taken += consumeFromVillage(level, villageB, maxUnits - taken);
        return taken;
    }

    public static int availableForVillage(ServerLevel level, UUID villageId) {
        LoadedTownhall th = resolveLoaded(level, villageId);
        if (th == null)
            return 0;
        Map<Item, Integer> counts = countEligible(th.chests());
        int total = 0;
        for (var entry : counts.entrySet()) {
            int retained = retainedCount(level, th.village(), th.townhall(), th.inventory(), entry.getKey());
            total += Math.max(0, entry.getValue() - retained);
        }
        return total;
    }

    private static int consumeFromVillage(ServerLevel level, UUID villageId, int maxUnits) {
        if (maxUnits <= 0)
            return 0;
        LoadedTownhall th = resolveLoaded(level, villageId);
        if (th == null)
            return 0;
        Map<Item, Integer> counts = countEligible(th.chests());
        int remaining = maxUnits;
        int consumed = 0;
        for (var entry : counts.entrySet()) {
            if (remaining <= 0)
                break;
            Item item = entry.getKey();
            int retained = retainedCount(level, th.village(), th.townhall(), th.inventory(), item);
            int surplus = Math.max(0, entry.getValue() - retained);
            int toTake = Math.min(remaining, surplus);
            if (toTake > 0) {
                int removed = removeFromChests(th.chests(), item, toTake);
                consumed += removed;
                remaining -= removed;
            }
        }
        if (consumed > 0)
            th.inventory().invalidateCache();
        return consumed;
    }

    private record LoadedTownhall(
            Village village, BuildingInstance townhall, BuildingInventory inventory, List<ChestBlockEntity> chests) {}

    private static LoadedTownhall resolveLoaded(ServerLevel level, UUID villageId) {
        if (level == null || villageId == null)
            return null;
        Village village = PoliticsService.village(level, villageId);
        if (village == null || !village.isActive() || !level.isLoaded(village.getCenter()))
            return null;
        BuildingInstance townhall = village.getTownhall();
        BuildingInventory inventory = townhall == null ? null : townhall.getInventory();
        if (inventory == null)
            return null;
        NativeStockBridge.Positions pos = NativeStockBridge.positions(inventory);
        if (pos.chests().isEmpty())
            return null;
        for (BlockPos p : pos.furnaces())
            if (!level.isLoaded(p))
                return null;
        for (BlockPos p : pos.firepits())
            if (!level.isLoaded(p))
                return null;
        Map<BlockPos, ChestBlockEntity> physical = new LinkedHashMap<>();
        for (BlockPos cp : pos.chests()) {
            if (!level.isLoaded(cp))
                return null;
            BlockState state = level.getBlockState(cp);
            if (!(state.getBlock() instanceof ChestBlock) || !(level.getBlockEntity(cp) instanceof ChestBlockEntity cbe))
                continue;
            physical.putIfAbsent(cp.immutable(), cbe);
            if (state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                BlockPos neighbor = cp.relative(ChestBlock.getConnectedDirection(state));
                if (!level.isLoaded(neighbor))
                    return null;
                if (level.getBlockEntity(neighbor) instanceof ChestBlockEntity ncbe)
                    physical.putIfAbsent(neighbor.immutable(), ncbe);
            }
        }
        if (physical.isEmpty())
            return null;
        return new LoadedTownhall(village, townhall, inventory, List.copyOf(physical.values()));
    }

    private static boolean isRoadMaterial(ItemStack stack) {
        if (stack.isEmpty())
            return false;
        return stack.is(Items.COBBLESTONE) || stack.is(Items.GRAVEL) || stack.is(ItemTags.PLANKS);
    }

    private static Map<Item, Integer> countEligible(List<ChestBlockEntity> chests) {
        Map<Item, Integer> counts = new LinkedHashMap<>();
        for (ChestBlockEntity chest : chests) {
            for (int i = 0; i < chest.getContainerSize(); i++) {
                ItemStack stack = chest.getItem(i);
                if (isRoadMaterial(stack) && ItemStack.isSameItemSameComponents(stack, new ItemStack(stack.getItem())))
                    counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    private static int retainedCount(ServerLevel level, Village village, BuildingInstance townhall,
            BuildingInventory inventory, Item item) {
        inventory.invalidateCache();
        long construction = constructionNeed(village, item);
        int target = TradeGoodsLoader.getTargetQuantities(village.getCultureId()).getOrDefault(item, 0);
        int residents = 0;
        for (BuildingInstance building : village.getBuildings())
            residents = Math.addExact(residents, GoodAvailabilityHelper.getSinkRequiredGoods(building, item, village));
        int floor = RoyalShipmentConfig.MINIMUM_REMAINDER != null
                ? RoyalShipmentConfig.current().minimumRemainder()
                : RoyalShipmentConfig.Settings.DEFAULTS.minimumRemainder();
        int nonConstruction = (int) Math.min(RoyalVillageStock.MAXIMUM_RETAINED, Math.max((long) target + residents, floor));
        return (int) Math.min(Integer.MAX_VALUE, Math.max(construction, nonConstruction));
    }

    private static long constructionNeed(Village village, Item item) {
        long needs = 0;
        var pending = village.getPendingProject();
        if (pending != null)
            needs += cost(pending.planSetId(), pending.variant(), pending.level(), item);
        for (BuildingInstance building : village.getBuildings()) {
            if (building.isBeingBuilt())
                needs += cost(building.getPlanSetId(), building.getVariant(), building.getLevel(), item);
        }
        for (var queued : village.getControlledQueue())
            needs += cost(queued.planSetId(), queued.variant(), queued.level(), item);
        return needs;
    }

    private static int cost(ResourceLocation planSetId, String variant, int level, Item item) {
        if (planSetId == null || variant == null)
            return 0;
        BuildingPlanSet set = ModCultures.getBuildingPlanSet(planSetId);
        if (set == null)
            return 0;
        BuildingPlanSet.LevelDef def = set.getLevel(variant, level);
        if (def == null)
            return 0;
        int count = def.requiredResources().getOrDefault(BuiltInRegistries.ITEM.getKey(item), 0);
        if (new ItemStack(item).is(AnywoodHelper.LOGS_TAG))
            count += def.requiredResources().getOrDefault(AnywoodHelper.ANYWOOD_LOG, 0);
        return Math.max(0, count);
    }

    private static int removeFromChests(List<ChestBlockEntity> chests, Item item, int amount) {
        int remaining = amount;
        for (ChestBlockEntity chest : chests) {
            boolean dirty = false;
            for (int i = 0; i < chest.getContainerSize() && remaining > 0; i++) {
                ItemStack stack = chest.getItem(i);
                if (!stack.isEmpty() && stack.is(item)
                        && ItemStack.isSameItemSameComponents(stack, new ItemStack(item))) {
                    int take = Math.min(remaining, stack.getCount());
                    stack.shrink(take);
                    chest.setItem(i, stack.isEmpty() ? ItemStack.EMPTY : stack);
                    remaining -= take;
                    dirty = true;
                }
            }
            if (dirty)
                chest.setChanged();
        }
        return amount - remaining;
    }
}
