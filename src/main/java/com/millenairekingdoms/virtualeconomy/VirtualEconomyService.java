package com.millenairekingdoms.virtualeconomy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.millenairekingdoms.construction.RoyalConstructionStore;
import com.millenairekingdoms.construction.VirtualGrowthAccess;
import com.millenairekingdoms.economy.RoyalEconomicReservations;
import com.millenairekingdoms.virtualeconomy.VirtualEconomySavedData.Credit;
import com.millenairekingdoms.virtualeconomy.VirtualEconomySavedData.CreditStage;
import com.millenairekingdoms.virtualeconomy.VirtualEconomySavedData.VillageState;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.storage.LevelResource;
import org.millenaire.Millenaire;
import org.millenaire.building.AnywoodHelper;
import org.millenaire.building.BuildingId;
import org.millenaire.building.BuildingInstance;
import org.millenaire.building.BuildingPlanSet;
import org.millenaire.commerce.TradeGoodsLoader;
import org.millenaire.culture.ModCultures;
import org.millenaire.culture.VillagerType;
import org.millenaire.goal.gathering.GatheringGoal;
import org.millenaire.item.ModItems;
import org.millenaire.village.Village;
import org.millenaire.village.VillageSavedData;

/**
 * Chunk-free economy of unloaded villages and its one-way hand-off when they load again.
 *
 * <ul>
 *   <li>Unloaded: each missed day adds the {@code abstracted_production} of every operational building (scaled by its
 *       living residents) to a virtual Town Hall buffer, deducts each living resident's {@code requiredGoods}, and
 *       clamps the buffer to the Town Hall chest slots. The next native project's {@code requiredResources} are then
 *       locked out of the buffer and earn background credit. No block, chunk or native save is touched.</li>
 *   <li>Loaded: the buffer drains into the real Town Hall chests (what does not fit stays virtual), the paid materials
 *       move into the chests and the project becomes the native pending project, and once Millénaire's own builders
 *       start its ConstructionTask the credit becomes a per-block speed bonus.</li>
 * </ul>
 * Every item is in exactly one place at a time: the virtual buffer, the locked credit, or a physical chest.
 */
public final class VirtualEconomyService {
    private static final int CHEST_SLOTS = 27;

    public record DayResult(Map<ResourceLocation, Integer> produced, Map<ResourceLocation, Integer> consumed,
            int clampedItems, CreditStage creditStage) {}
    public enum Handoff {
        /** Nothing paid to hand off. */
        NONE,
        /** Paid credit waits: native/royal work holds the slot, chests are full or storage is reserved. */
        WAITING,
        /** Materials moved to the Town Hall and the native pending project set. */
        HANDED_OFF,
        /** The native builders started the project; the credit became a speed bonus. */
        STARTED,
        /** The target became invalid; locked materials went back to the virtual buffer. */
        REFUNDED,
        /** Millénaire dropped the project (for example NO_SPACE); its materials already sit in the Town Hall. */
        REJECTED,
        /** Native growth never started it within the timeout; its materials already sit in the Town Hall. */
        RELEASED
    }

    private VirtualEconomyService() {}

    // ------------------------------------------------------------------ state

    public static boolean isUnloaded(ServerLevel level, Village village) {
        return !village.isActive() || !level.isPositionEntityTicking(village.getCenter());
    }
    public static boolean eligible(Village village) {
        return village != null && !village.isLoneBuilding() && village.getTownhall() != null
                && village.getTownhall().getInventory() != null;
    }
    public static long today(ServerLevel level) {
        return Math.max(Math.max(0, level.getDayTime()), Math.max(0, level.getGameTime())) / 24_000L;
    }

    // ------------------------------------------------------------------ data-driven rates

    /**
     * Daily production at the current occupancy, from each operational building's {@code abstracted_production},
     * or (for cultures/buildings without {@code abstracted_production}) from its living adult residents' native
     * gathering/bringing-home goods.
     */
    public static Map<ResourceLocation, Integer> dailyProduction(Village village, VirtualEconomyConfig.Settings s) {
        var out = new LinkedHashMap<ResourceLocation, Integer>();
        int living = livingResidents(village);
        for (BuildingInstance building : village.getBuildings()) {
            var definition = definition(building);
            if (definition == null || !building.isOperational())
                continue;
            if (definition.abstractedProduction() != null && !definition.abstractedProduction().isEmpty()) {
                int slots = residentSlots(definition);
                double occupancy;
                if (slots == 0)
                    occupancy = living > 0 ? 1.0 : 0.0; // worked by the village (fields, pens) rather than its own home
                else
                    occupancy = Math.min(1.0, (double) residentsOf(village, building.getId()) / slots);
                if (occupancy <= 0)
                    continue;
                for (var good : definition.abstractedProduction().entrySet()) {
                    var set = ModCultures.getBuildingPlanSet(building.getPlanSetId());
                    var item = resolveGood(set.culture(), good.getKey());
                    if (item == null)
                        item = resolveGood(village.getCultureId(), good.getKey());
                    int amount = (int) Math.floor(Math.max(0, good.getValue()) * occupancy * s.productionScale());
                    if (item != null && amount > 0)
                        out.merge(item, amount, Integer::sum);
                }
            } else if (village.getTownhall() != null && !building.getId().equals(village.getTownhall().getId())) {
                addResidentProduction(village, building, s, out);
            }
        }
        return out;
    }

    private static void addResidentProduction(Village village, BuildingInstance building,
            VirtualEconomyConfig.Settings s, Map<ResourceLocation, Integer> out) {
        for (var record : village.getVillagerRecords().values()) {
            if (record.isKilled() || !building.getId().equals(record.getHomeBuilding())
                    || record.getVillagerTypeId() == null)
                continue;
            VillagerType type = ModCultures.getVillagerType(record.getVillagerTypeId());
            if (type == null || type.isChild())
                continue;
            Set<ResourceLocation> goods = new LinkedHashSet<>();
            if (type.resolvedBringBackHomeGoods() != null) {
                for (Item item : type.resolvedBringBackHomeGoods()) {
                    ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
                    if (isValidProducedGood(id))
                        goods.add(id);
                }
            }
            if (type.bringBackHomeGoods() != null) {
                for (String key : type.bringBackHomeGoods()) {
                    ResourceLocation id = resolveGood(village.getCultureId(), key);
                    if (isValidProducedGood(id))
                        goods.add(id);
                }
            }
            if (type.resolvedCollectGoods() != null) {
                for (ResourceLocation id : type.resolvedCollectGoods()) {
                    if (isValidProducedGood(id))
                        goods.add(id);
                }
            }
            if (type.collectGoods() != null) {
                for (String key : type.collectGoods()) {
                    ResourceLocation id = resolveGood(village.getCultureId(), key);
                    if (isValidProducedGood(id))
                        goods.add(id);
                }
            }
            var registry = Millenaire.getGoalRegistry();
            if (registry != null && type.goals() != null) {
                for (ResourceLocation goalId : type.goals()) {
                    if (!(registry.get(goalId) instanceof GatheringGoal gg))
                        continue;
                    JsonObject hp = gg.getGatheringType().handlerParams();
                    if (hp == null || hp.has("inputs"))
                        continue;
                    for (String arrKey : List.of("outputs", "loot", "harvestItems")) {
                        if (!hp.has(arrKey) || !hp.get(arrKey).isJsonArray())
                            continue;
                        for (JsonElement el : hp.getAsJsonArray(arrKey)) {
                            if (!el.isJsonObject() || !el.getAsJsonObject().has("item"))
                                continue;
                            ResourceLocation id = resolveGood(
                                    village.getCultureId(), el.getAsJsonObject().get("item").getAsString());
                            if (isValidProducedGood(id))
                                goods.add(id);
                        }
                    }
                }
            }
            if (goods.isEmpty())
                continue;
            int perGood = (int) Math.floor(Math.max(4.0, 24.0 / goods.size()) * s.productionScale());
            if (perGood > 0) {
                for (ResourceLocation id : goods)
                    out.merge(id, perGood, Integer::sum);
            }
        }
    }

    private static boolean isValidProducedGood(ResourceLocation itemId) {
        if (itemId == null)
            return false;
        var opt = BuiltInRegistries.ITEM.getOptional(itemId);
        if (opt.isEmpty())
            return false;
        Item item = opt.get();
        if (item == Items.AIR || item == ModItems.DENIER.get() || item == ModItems.DENIER_ARGENT.get()
                || item == ModItems.DENIER_OR.get() || item.getDefaultMaxStackSize() <= 1)
            return false;
        String path = itemId.getPath();
        return !path.endsWith("_sapling") && !path.endsWith("_seeds");
    }

    /** Daily upkeep of the living (not killed) residents, from their {@code VillagerType.requiredGoods}. */
    public static Map<ResourceLocation, Integer> dailyConsumption(Village village, VirtualEconomyConfig.Settings s) {
        var out = new LinkedHashMap<ResourceLocation, Integer>();
        for (var record : village.getVillagerRecords().values()) {
            if (record.isKilled() || record.getVillagerTypeId() == null)
                continue;
            var type = ModCultures.getVillagerType(record.getVillagerTypeId());
            if (type == null)
                continue;
            var resolved = type.resolvedRequiredGoods();
            if (resolved != null && !resolved.isEmpty()) {
                resolved.forEach((item, count) -> {
                    int amount = (int) Math.round(Math.max(0, count) * s.consumptionScale());
                    if (item != null && item != Items.AIR && amount > 0)
                        out.merge(BuiltInRegistries.ITEM.getKey(item), amount, Integer::sum);
                });
            } else if (type.requiredGoods() != null) {
                type.requiredGoods().forEach((key, count) -> {
                    var item = resolveGood(village.getCultureId(), key);
                    int amount = (int) Math.round(Math.max(0, count) * s.consumptionScale());
                    if (item != null && amount > 0)
                        out.merge(item, amount, Integer::sum);
                });
            }
        }
        return out;
    }

    /** Stack slots the virtual buffer (plus locked credit) may occupy: the Town Hall chest slots, hard-capped. */
    public static int capacitySlots(Village village, VirtualEconomyConfig.Settings s) {
        var townhall = village.getTownhall();
        int chests = townhall == null || townhall.getInventory() == null
                ? 0
                : townhall.getInventory().getChestPositions().size();
        return Math.min(s.maxVirtualSlots(), chests * CHEST_SLOTS);
    }

    public static int slotsUsed(VillageState state) {
        int slots = slots(state.virtualChest());
        if (state.credit() != null)
            slots += slots(state.credit().reserved());
        return slots;
    }

    // ------------------------------------------------------------------ unloaded simulation

    /**
     * Runs the missed days of an unloaded village (bounded by maxCatchUpDays). The first visit only starts counting.
     *
     * @return days simulated
     */
    public static int catchUp(ServerLevel level, Village village, long today, VirtualEconomyConfig.Settings s) {
        var data = VirtualEconomySavedData.get(level);
        var state = data.stateOrCreate(village.getId().uuid());
        if (state.lastSimulatedDay() < 0 || today < state.lastSimulatedDay()) {
            state.setLastSimulatedDay(today);
            data.setDirty();
            return 0;
        }
        int days = (int) Math.min(today - state.lastSimulatedDay(), s.maxCatchUpDays());
        for (int i = 0; i < days; i++)
            simulateDay(level, village, s);
        state.setLastSimulatedDay(today);
        data.setDirty();
        return days;
    }

    /** While a village is loaded the native economy runs; only move the day marker so loaded time is never counted. */
    public static void markLoaded(ServerLevel level, Village village, long today) {
        var data = VirtualEconomySavedData.get(level);
        var state = data.stateOrCreate(village.getId().uuid());
        if (state.lastSimulatedDay() != today) {
            state.setLastSimulatedDay(today);
            data.setDirty();
        }
    }

    /** One unloaded day: production, upkeep, storage cap, then background construction. Never touches the world. */
    public static DayResult simulateDay(ServerLevel level, Village village, VirtualEconomyConfig.Settings s) {
        var data = VirtualEconomySavedData.get(level);
        var state = data.stateOrCreate(village.getId().uuid());
        var produced = dailyProduction(village, s);
        produced.forEach(state::add);
        var consumed = new LinkedHashMap<ResourceLocation, Integer>();
        dailyConsumption(village, s).forEach((item, amount) -> {
            int taken = state.remove(item, amount);
            if (taken > 0)
                consumed.put(item, taken);
        });
        int clamped = clamp(state, capacitySlots(village, s), produced);
        if (s.construction())
            advanceCredit(level, village, state, s);
        data.setDirty();
        return new DayResult(produced, consumed, clamped,
                state.credit() == null ? null : state.credit().stage());
    }

    /**
     * Trims whole stacks of the day's most abundant new production until the buffer (with locked credit) fits. Only
     * today's production overflows: goods delivered by royal shipments or kept back from a drain are never discarded.
     */
    static int clamp(VillageState state, int capacity, Map<ResourceLocation, Integer> produced) {
        var trimmable = new LinkedHashMap<ResourceLocation, Integer>();
        produced.forEach((item, amount) -> trimmable.put(item, Math.min(amount, state.count(item))));
        int removed = 0;
        while (slotsUsed(state) > capacity) {
            var largest = trimmable.entrySet()
                                  .stream()
                                  .filter(e -> e.getValue() > 0)
                                  .max(Comparator.<Map.Entry<ResourceLocation, Integer>>comparingInt(
                                                         e -> state.count(e.getKey()))
                                                       .thenComparing(e -> e.getKey().toString()))
                                  .orElse(null);
            if (largest == null)
                break;
            int stack = maxStack(largest.getKey());
            int excess = slotsUsed(state) - capacity;
            int count = state.count(largest.getKey());
            // Drop the partial top stack first so the remainder stays in full stacks.
            int partial = count % stack;
            int drop = partial > 0 ? partial + (excess - 1) * stack : excess * stack;
            drop = Math.min(Math.max(1, drop), largest.getValue());
            int taken = state.remove(largest.getKey(), drop);
            largest.setValue(largest.getValue() - taken);
            removed += taken;
        }
        return removed;
    }

    // ------------------------------------------------------------------ royal shipment cargo

    public enum Cargo { APPLIED, ALREADY_APPLIED, INSUFFICIENT }

    /** Free room for one product in the virtual buffer, by Town Hall chest slots. */
    public static int cargoCapacity(ServerLevel level, Village village, ResourceLocation item) {
        var state = VirtualEconomySavedData.get(level).state(village.getId().uuid());
        int used = state == null ? 0 : slotsUsed(state);
        int free = Math.max(0, capacitySlots(village, VirtualEconomyConfig.current()) - used) * maxStack(item);
        int count = state == null ? 0 : state.count(item);
        int stack = maxStack(item);
        if (count % stack != 0)
            free += stack - count % stack; // the open top stack still has room
        return free;
    }
    public static int virtualCount(ServerLevel level, UUID village, ResourceLocation item) {
        var state = VirtualEconomySavedData.get(level).state(village);
        return state == null ? 0 : state.count(item);
    }

    /**
     * Applies one royal cargo operation to the village's virtual buffer exactly once (negative = collection), records
     * it in the ledger and writes virtual_economy.dat before returning, so the caller may then acknowledge it in the
     * shipment journal. A crash before the write leaves neither the goods nor the ledger entry; replay re-applies.
     */
    public static Cargo applyCargo(ServerLevel level, UUID village, UUID operation, ResourceLocation item, int delta) {
        if (!level.getServer().isSameThread())
            throw new IllegalStateException("Virtual cargo requires the server thread");
        var data = VirtualEconomySavedData.get(level);
        if (data.cargoApplied(operation))
            return Cargo.ALREADY_APPLIED;
        var state = data.stateOrCreate(village);
        if (delta < 0 && state.count(item) < -delta)
            return Cargo.INSUFFICIENT;
        if (delta < 0)
            state.remove(item, -delta);
        else
            state.add(item, delta);
        data.recordCargo(operation);
        flush(level, data);
        return Cargo.APPLIED;
    }

    /** Writes this dimension's virtual_economy.dat and waits until it is on disk. */
    static void flush(ServerLevel level, VirtualEconomySavedData data) {
        var folder = net.minecraft.world.level.dimension.DimensionType
                             .getStorageFolder(level.dimension(), level.getServer().getWorldPath(LevelResource.ROOT))
                             .resolve("data");
        try {
            Files.createDirectories(folder);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot create the virtual economy data folder", e);
        }
        // NeoForge writes saved data on one ordered IO chain: queue this snapshot behind any older autosave snapshot,
        // then wait, so an older snapshot can never land after it and the file is on disk before we acknowledge.
        data.setDirty();
        data.save(folder.resolve(VirtualEconomySavedData.NAME + ".dat").toFile(), level.registryAccess());
        net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
    }

    // ------------------------------------------------------------------ background construction credit

    static void advanceCredit(ServerLevel level, Village village, VillageState state, VirtualEconomyConfig.Settings s) {
        var credit = state.credit();
        if (credit == null) {
            if (village.getPendingProject() != null || VirtualGrowthAccess.nativeSlotBusy(village)
                    || royalOrderActive(level, village.getId().uuid()))
                return;
            credit = choose(level, village);
            if (credit == null)
                return;
            state.setCredit(credit);
        }
        switch (credit.stage()) {
            case RESERVING -> {
                if (royalOrderActive(level, village.getId().uuid()) || village.getPendingProject() != null
                        || !stillValid(village, credit)) {
                    state.setCredit(null); // nothing was deducted yet
                    return;
                }
                var definition = definition(credit);
                var take = allocate(state.virtualChest(),
                        definition.requiredResources() == null ? Map.of() : definition.requiredResources());
                if (take == null)
                    return;
                take.forEach(state::remove);
                credit.pay(take, level.getGameTime());
            }
            case PAID -> credit.setProgress(credit.progress() + 1.0 / s.creditDays());
            case HANDED_OFF -> {}
        }
    }

    /** Native growth candidates, upgrades of fixed buildings first, then by native priority. */
    static Credit choose(ServerLevel level, Village village) {
        var type = ModCultures.getVillageType(village.getVillageTypeId());
        if (type == null)
            return null;
        List<VirtualGrowthAccess.Candidate> candidates;
        try {
            candidates = VirtualGrowthAccess.candidates(level, village, type);
        } catch (RuntimeException unavailable) {
            return null; // the audited native API is unavailable: no invented candidates
        }
        var best = candidates.stream()
                           .filter(c -> c.set() != null && c.level() != null && resolvable(c.level()))
                           .filter(c -> !c.upgrade() || c.building() != null)
                           .sorted(Comparator.<VirtualGrowthAccess.Candidate>comparingInt(c -> c.upgrade() ? 0 : 1)
                                           .thenComparing(c -> -c.level().priority())
                                           .thenComparing(c -> c.set().id().toString()))
                           .findFirst()
                           .orElse(null);
        if (best == null)
            return null;
        return new Credit(best.set().id(), best.variant(), best.level().level(),
                best.upgrade() ? best.building().getId().uuid() : null, level.getGameTime());
    }

    private static boolean resolvable(BuildingPlanSet.LevelDef definition) {
        if (definition.requiredResources() == null)
            return true;
        for (var need : definition.requiredResources().keySet())
            if (!AnywoodHelper.isAnywood(need) && BuiltInRegistries.ITEM.getOptional(need).isEmpty())
                return false;
        return true;
    }

    /** Concrete items covering every requirement (anywood from any log), or null if the buffer cannot cover them. */
    static Map<ResourceLocation, Integer> allocate(
            Map<ResourceLocation, Integer> stock, Map<ResourceLocation, Integer> required) {
        var available = new LinkedHashMap<>(stock);
        var take = new LinkedHashMap<ResourceLocation, Integer>();
        int anywood = 0;
        for (var need : required.entrySet()) {
            int amount = Math.max(0, need.getValue());
            if (amount == 0)
                continue;
            if (AnywoodHelper.isAnywood(need.getKey())) {
                anywood += amount;
                continue;
            }
            int have = available.getOrDefault(need.getKey(), 0);
            if (have < amount)
                return null;
            available.put(need.getKey(), have - amount);
            take.merge(need.getKey(), amount, Integer::sum);
        }
        if (anywood > 0) {
            var logs = available.entrySet()
                               .stream()
                               .filter(e -> e.getValue() > 0 && isLog(e.getKey()))
                               .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString)))
                               .toList();
            for (var log : logs) {
                if (anywood == 0)
                    break;
                int used = Math.min(anywood, log.getValue());
                take.merge(log.getKey(), used, Integer::sum);
                anywood -= used;
            }
            if (anywood > 0)
                return null;
        }
        return take;
    }

    private static boolean stillValid(Village village, Credit credit) {
        var definition = definition(credit);
        if (definition == null)
            return false;
        if (!credit.upgrade())
            return true;
        var building = village.getBuilding(new BuildingId(credit.upgradeBuildingId()));
        return building != null && building.getStatus() == BuildingInstance.Status.COMPLETE
                && credit.planSetId().equals(building.getPlanSetId()) && building.getLevel() < credit.targetLevel();
    }

    /** Adds goods straight to a village's virtual buffer (operator tooling and integration tests only). */
    @org.jetbrains.annotations.ApiStatus.Internal
    public static void seed(ServerLevel level, Village village, ResourceLocation item, int count) {
        var data = VirtualEconomySavedData.get(level);
        data.stateOrCreate(village.getId().uuid()).add(item, count);
        data.setDirty();
    }

    /** A non-terminal player or NPC royal commission for this village always has priority. */
    public static boolean royalOrderActive(ServerLevel level, UUID village) {
        var directory = level.getServer().getWorldPath(LevelResource.ROOT).resolve(
                "data/millenairekingdoms/royal-construction");
        if (!Files.exists(directory.resolve("orders.dat")))
            return false;
        String dimension = level.dimension().location().toString();
        try {
            for (var entry : new RoyalConstructionStore(directory).entries())
                if (entry.intent().village().equals(village) && entry.intent().dimension().equals(dimension)
                        && !entry.terminal())
                    return true;
        } catch (RuntimeException unreadable) {
            return true; // cannot prove the slot is free: defer to royal orders
        }
        return false;
    }

    // ------------------------------------------------------------------ loaded hand-off

    /**
     * One-way drain of the virtual buffer into the loaded Town Hall chests. Containers with unresolved royal
     * payment/shipment images are left alone; whatever does not fit stays virtual.
     *
     * @return items moved into physical chests
     */
    public static int drain(ServerLevel level, Village village) {
        var data = VirtualEconomySavedData.get(level);
        var state = data.state(village.getId().uuid());
        if (state == null || state.virtualChest().isEmpty())
            return 0;
        var chests = chests(level, village);
        if (chests == null)
            return 0;
        int moved = 0;
        for (var entry : List.copyOf(state.virtualChest().entrySet())) {
            var item = BuiltInRegistries.ITEM.getOptional(entry.getKey()).orElse(null);
            if (item == null || item == Items.AIR) {
                state.remove(entry.getKey(), entry.getValue()); // unknown item (removed mod): nothing to give
                continue;
            }
            int inserted = insert(chests, item, entry.getValue(), false);
            if (inserted > 0) {
                state.remove(entry.getKey(), inserted);
                moved += inserted;
            }
        }
        if (moved > 0) {
            village.getTownhall().getInventory().invalidateCache();
            data.setDirty();
        }
        return moved;
    }

    /** Hands a paid background project to Millénaire, then follows it until a live ConstructionTask exists. */
    public static Handoff handoff(ServerLevel level, Village village, VirtualEconomyConfig.Settings s) {
        var data = VirtualEconomySavedData.get(level);
        var state = data.state(village.getId().uuid());
        if (state == null || state.credit() == null)
            return Handoff.NONE;
        var credit = state.credit();
        if (credit.stage() == CreditStage.RESERVING) {
            // Nothing was locked; native growth decides on its own once loaded.
            state.setCredit(null);
            data.setDirty();
            return Handoff.NONE;
        }
        if (credit.stage() == CreditStage.HANDED_OFF)
            return follow(level, village, state, s);
        if (!stillValid(village, credit)) {
            credit.reserved().forEach(state::add);
            state.setCredit(null);
            data.setDirty();
            return Handoff.REFUNDED;
        }
        if (village.getPendingProject() != null || VirtualGrowthAccess.nativeSlotBusy(village)
                || royalOrderActive(level, village.getId().uuid()))
            return Handoff.WAITING;
        var chests = chests(level, village);
        if (chests == null)
            return Handoff.WAITING;
        // All-or-nothing: the native builder needs every requirement in the Town Hall to launch.
        var resolved = new LinkedHashMap<Item, Integer>();
        for (var e : credit.reserved().entrySet())
            BuiltInRegistries.ITEM.getOptional(e.getKey()).ifPresent(item -> resolved.merge(item, e.getValue(), Integer::sum));
        if (!fits(chests, resolved))
            return Handoff.WAITING;
        resolved.forEach((item, count) -> insert(chests, item, count, false));
        village.getTownhall().getInventory().invalidateCache();
        var prior = new ArrayList<UUID>();
        for (var building : village.getBuildings())
            if (credit.planSetId().equals(building.getPlanSetId()))
                prior.add(building.getId().uuid());
        village.setPendingProject(new Village.PendingProject(credit.planSetId(), credit.variant(),
                credit.targetLevel(), credit.upgrade(),
                credit.upgrade() ? new BuildingId(credit.upgradeBuildingId()) : null));
        village.setNoProjectsLeftUntil(0);
        village.markDirty();
        VillageSavedData.get(level).setDirty();
        credit.handOff(prior, level.getGameTime());
        data.setDirty();
        return Handoff.HANDED_OFF;
    }

    private static Handoff follow(ServerLevel level, Village village, VillageState state, VirtualEconomyConfig.Settings s) {
        var data = VirtualEconomySavedData.get(level);
        var credit = state.credit();
        var live = liveTask(village, credit);
        if (live != null) {
            if (credit.progress() > 0)
                data.putBoost(new VirtualEconomySavedData.Boost(live.getId().uuid(), village.getId().uuid(),
                        credit.progress(), level.getGameTime()));
            state.setCredit(null);
            data.setDirty();
            return Handoff.STARTED;
        }
        var pending = village.getPendingProject();
        boolean ours = pending != null && credit.planSetId().equals(pending.planSetId())
                && pending.level() == credit.targetLevel();
        if (ours) {
            if (level.getGameTime() - credit.stageTick() < s.handoffTimeoutTicks())
                return Handoff.HANDED_OFF;
            state.setCredit(null);
            data.setDirty();
            return Handoff.RELEASED;
        }
        state.setCredit(null);
        data.setDirty();
        return Handoff.REJECTED;
    }

    private static BuildingInstance liveTask(Village village, Credit credit) {
        if (credit.upgrade()) {
            var building = village.getBuilding(new BuildingId(credit.upgradeBuildingId()));
            return building != null && building.isBeingBuilt() && building.getConstructionTask() != null ? building
                                                                                                         : null;
        }
        for (var building : village.getBuildings())
            if (credit.planSetId().equals(building.getPlanSetId()) && building.isBeingBuilt()
                    && building.getConstructionTask() != null
                    && !credit.priorBuildings().contains(building.getId().uuid()))
                return building;
        return null;
    }

    /** Drops bonuses whose construction finished or vanished. */
    public static int expireBoosts(ServerLevel level, Village village) {
        var data = VirtualEconomySavedData.get(level);
        int removed = 0;
        for (var boost : List.copyOf(data.boosts().values())) {
            if (!boost.village().equals(village.getId().uuid()))
                continue;
            var building = village.getBuilding(new BuildingId(boost.building()));
            if (building == null || !building.isBeingBuilt() || building.getConstructionTask() == null
                    || building.getConstructionTask().isComplete()) {
                data.removeBoost(boost.building());
                removed++;
            }
        }
        return removed;
    }

    // ------------------------------------------------------------------ physical chests

    /** Loaded, unreserved Town Hall chests; null when any is unloaded, none exist, or storage is held by a royal image. */
    static List<Container> chests(ServerLevel level, Village village) {
        var townhall = village.getTownhall();
        if (townhall == null || townhall.getInventory() == null)
            return null;
        List<BlockPos> positions = townhall.getInventory().getChestPositions();
        if (positions.isEmpty())
            return null;
        var resolved = new LinkedHashMap<BlockPos, Container>();
        for (var pos : positions) {
            if (!level.isLoaded(pos))
                return null;
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof ChestBlock)
                    || !(level.getBlockEntity(pos) instanceof ChestBlockEntity chest))
                continue;
            resolved.putIfAbsent(pos.immutable(), chest);
            if (state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                BlockPos neighbor = pos.relative(ChestBlock.getConnectedDirection(state));
                if (!level.isLoaded(neighbor))
                    return null;
                if (level.getBlockState(neighbor).getBlock() instanceof ChestBlock
                        && level.getBlockEntity(neighbor) instanceof ChestBlockEntity neighborChest) {
                    resolved.putIfAbsent(neighbor.immutable(), neighborChest);
                }
            }
        }
        if (resolved.isEmpty())
            return null;
        try {
            RoyalEconomicReservations.requireWithdrawalAvailable(level, List.copyOf(resolved.keySet()));
        } catch (IllegalStateException reserved) {
            return null;
        }
        return List.copyOf(resolved.values());
    }

    private static boolean fits(List<Container> chests, Map<Item, Integer> items) {
        // Simulate on copies so nothing is written unless everything fits.
        var copies = new ArrayList<Container>();
        for (var chest : chests) {
            var copy = new net.minecraft.world.SimpleContainer(chest.getContainerSize());
            for (int i = 0; i < chest.getContainerSize(); i++)
                copy.setItem(i, chest.getItem(i).copy());
            copies.add(copy);
        }
        for (var e : items.entrySet())
            if (insert(copies, e.getKey(), e.getValue(), true) < e.getValue())
                return false;
        return true;
    }

    /** Merges into matching stacks first, then empty slots; returns how many were inserted. */
    static int insert(List<Container> chests, Item item, int amount, boolean simulated) {
        int left = amount;
        var template = new ItemStack(item);
        int max = Math.min(template.getMaxStackSize(), 64);
        for (int pass = 0; pass < 2 && left > 0; pass++) {
            for (var chest : chests) {
                boolean changed = false;
                for (int slot = 0; slot < chest.getContainerSize() && left > 0; slot++) {
                    var current = chest.getItem(slot);
                    if (pass == 0 && !current.isEmpty() && ItemStack.isSameItemSameComponents(current, template)
                            && current.getCount() < Math.min(max, chest.getMaxStackSize())) {
                        int add = Math.min(left, Math.min(max, chest.getMaxStackSize()) - current.getCount());
                        current.grow(add);
                        left -= add;
                        changed = true;
                    } else if (pass == 1 && current.isEmpty()) {
                        int add = Math.min(left, Math.min(max, chest.getMaxStackSize()));
                        chest.setItem(slot, template.copyWithCount(add));
                        left -= add;
                        changed = true;
                    }
                }
                if (changed && !simulated)
                    chest.setChanged();
            }
        }
        return amount - left;
    }

    // ------------------------------------------------------------------ helpers

    static BuildingPlanSet.LevelDef definition(BuildingInstance building) {
        if (building.getPlanSetId() == null)
            return null;
        var set = ModCultures.getBuildingPlanSet(building.getPlanSetId());
        if (set == null)
            return null;
        String variant = building.getVariant() == null ? "default" : building.getVariant();
        return set.getLevel(variant, building.getLevel());
    }
    static BuildingPlanSet.LevelDef definition(Credit credit) {
        var set = ModCultures.getBuildingPlanSet(credit.planSetId());
        return set == null ? null : set.getLevel(credit.variant(), credit.targetLevel());
    }
    /** Native plan resident slots; unspecified lists (null) count as none. */
    public static int residentSlots(BuildingPlanSet.LevelDef definition) {
        return (definition.maleResidents() == null ? 0 : definition.maleResidents().size())
                + (definition.femaleResidents() == null ? 0 : definition.femaleResidents().size());
    }
    private static int livingResidents(Village village) {
        int count = 0;
        for (var record : village.getVillagerRecords().values())
            if (!record.isKilled())
                count++;
        return count;
    }
    private static int residentsOf(Village village, BuildingId building) {
        int count = 0;
        for (var record : village.getVillagerRecords().values())
            if (!record.isKilled() && building.equals(record.getHomeBuilding()))
                count++;
        return count;
    }

    /** A culture good key ({@code "sand"}) or item id to a registered item id; null if it names nothing. */
    public static ResourceLocation resolveGood(ResourceLocation culture, String key) {
        if (key == null || key.isBlank())
            return null;
        try {
            var good = culture == null ? null : TradeGoodsLoader.getGoodById(culture, key);
            if (good != null) {
                var item = good.resolveItem();
                if (item != null && item != Items.AIR)
                    return BuiltInRegistries.ITEM.getKey(item);
            }
        } catch (RuntimeException ignored) {
            // fall through to a plain item id
        }
        var direct = ResourceLocation.tryParse(key.contains(":") ? key : "minecraft:" + key);
        if (direct != null && BuiltInRegistries.ITEM.getOptional(direct).filter(i -> i != Items.AIR).isPresent())
            return direct;
        var millenaire = ResourceLocation.tryParse("millenaire:" + key);
        if (!key.contains(":") && millenaire != null
                && BuiltInRegistries.ITEM.getOptional(millenaire).filter(i -> i != Items.AIR).isPresent())
            return millenaire;
        return null;
    }
    private static boolean isLog(ResourceLocation id) {
        return BuiltInRegistries.ITEM.getOptional(id)
                .map(item -> new ItemStack(item).is(AnywoodHelper.LOGS_TAG))
                .orElse(false);
    }
    private static int maxStack(ResourceLocation id) {
        return BuiltInRegistries.ITEM.getOptional(id).map(i -> Math.max(1, new ItemStack(i).getMaxStackSize())).orElse(64);
    }
    private static int slots(ResourceLocation id, int count) {
        int stack = maxStack(id);
        return (count + stack - 1) / stack;
    }
    private static int slots(Map<ResourceLocation, Integer> items) {
        int total = 0;
        for (var e : items.entrySet())
            total += slots(e.getKey(), e.getValue());
        return total;
    }
}
