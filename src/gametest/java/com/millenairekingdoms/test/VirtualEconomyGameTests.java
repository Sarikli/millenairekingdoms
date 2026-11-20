package com.millenairekingdoms.test;

import com.millenairekingdoms.construction.RoyalConstructionStore;
import com.millenairekingdoms.history.HistoryFiles;
import com.millenairekingdoms.virtualeconomy.VirtualConstructionBoosts;
import com.millenairekingdoms.virtualeconomy.VirtualEconomyConfig;
import com.millenairekingdoms.virtualeconomy.VirtualEconomySavedData;
import com.millenairekingdoms.virtualeconomy.VirtualEconomySavedData.CreditStage;
import com.millenairekingdoms.virtualeconomy.VirtualEconomyService;
import com.millenairekingdoms.virtualeconomy.VirtualEconomyService.Handoff;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.building.AnywoodHelper;
import org.millenaire.building.BuildingId;
import org.millenaire.building.BuildingInstance;
import org.millenaire.building.BuildingPlanSet;
import org.millenaire.building.ConstructionTask;
import org.millenaire.building.PlacementStep;
import org.millenaire.culture.ModCultures;
import org.millenaire.culture.VillagerType;
import org.millenaire.village.Village;
import org.millenaire.village.VillageId;
import org.millenaire.village.VillageSavedData;
import org.millenaire.village.VillagerRecord;

/**
 * Automated verification suite ({@code verifyVirtualEconomy}) for the chunk-free economy of unloaded villages: data
 * driven production and upkeep into a bounded virtual Town Hall buffer, a one-way drain that never duplicates,
 * background material reservation and credit that touches no block and yields to native and royal projects, the hand-off
 * to Millénaire's own pending project / ConstructionTask, and persistence of data/millenairekingdoms_virtual_economy.dat
 * across an independent JVM.
 */
@PrefixGameTestTemplate(false)
public final class VirtualEconomyGameTests {
    private static final String NS = "millenairekingdoms_virtual_economy_tests";
    private static final AtomicInteger NEXT = new AtomicInteger();
    private static final VirtualEconomyConfig.Settings ECONOMY_ONLY =
            VirtualEconomyConfig.Settings.DEFAULT.withConstruction(false);

    private VirtualEconomyGameTests() {}

    // ---------------------------------------------------------------- 1. production, catch-up and storage cap

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void unloadedVillagesAccumulateAbstractedProductionUpToTheTownHallCap(GameTestHelper h) {
        var level = h.getLevel();
        var v = village(h, "virtual-production");
        try {
            var producer = productionBuilding(v);
            var definition = definition(producer);
            int slots = VirtualEconomyService.residentSlots(definition);
            var residents = new ArrayList<VillagerRecord>();
            for (int i = 0; i < Math.max(1, slots); i++)
                residents.add(resident(v, producer, false));
            h.assertTrue(producer.isOperational(), "A complete production building is operational");
            h.assertTrue(VirtualEconomyService.isUnloaded(level, v), "A far, inactive fixture village counts as unloaded");

            var expected = new LinkedHashMap<ResourceLocation, Integer>();
            for (var good : definition.abstractedProduction().entrySet())
                expected.merge(resolve(producer, good.getKey()), good.getValue(), Integer::sum);
            var production = VirtualEconomyService.dailyProduction(v, ECONOMY_ONLY);
            h.assertTrue(production.equals(expected),
                    "Full occupancy produces exactly the plan's abstracted_production: " + production + " vs " + expected);
            h.assertTrue(VirtualEconomyService.dailyConsumption(v, ECONOMY_ONLY).isEmpty(),
                    "Residents without requiredGoods have no upkeep");

            h.assertTrue(VirtualEconomyService.catchUp(level, v, 100, ECONOMY_ONLY) == 0,
                    "The first visit only starts counting days");
            h.assertTrue(VirtualEconomyService.catchUp(level, v, 103, ECONOMY_ONLY) == 3, "Three missed days run");
            var state = VirtualEconomySavedData.get(level).state(v.getId().uuid());
            for (var e : expected.entrySet())
                h.assertTrue(state.count(e.getKey()) == 3 * e.getValue(),
                        "Three days of " + e.getKey() + " are buffered, got " + state.count(e.getKey()));
            h.assertTrue(VirtualEconomyService.catchUp(level, v, 103, ECONOMY_ONLY) == 0, "A day never runs twice");
            h.assertTrue(VirtualEconomyService.catchUp(level, v, 1_000, ECONOMY_ONLY)
                            == ECONOMY_ONLY.maxCatchUpDays(),
                    "Long absences are bounded by maxCatchUpDays");
            VirtualEconomyService.markLoaded(level, v, 2_000);
            h.assertTrue(VirtualEconomyService.catchUp(level, v, 2_000, ECONOMY_ONLY) == 0,
                    "Days spent loaded (native economy) are never simulated");

            int capacity = VirtualEconomyService.capacitySlots(v, ECONOMY_ONLY);
            h.assertTrue(capacity == v.getTownhall().getInventory().getChestPositions().size() * 27,
                    "The cap is the Town Hall chest slot count");
            h.assertTrue(VirtualEconomyService.slotsUsed(state) <= capacity, "The buffer never exceeds the cap");
            var full = new LinkedHashMap<>(state.virtualChest());
            var tight = ECONOMY_ONLY.withMaxVirtualSlots(2);
            for (int i = 0; i < 10; i++)
                VirtualEconomyService.simulateDay(level, v, tight);
            h.assertTrue(state.virtualChest().equals(full),
                    "Once over a (tighter) ceiling, new production is trimmed entirely: the buffer stops growing");
            var fresh = village(h, "virtual-production-cap");
            try {
                var producer2 = productionBuilding(fresh);
                for (int i = 0; i < Math.max(1, slots); i++)
                    resident(fresh, producer2, false);
                for (int i = 0; i < 10; i++)
                    VirtualEconomyService.simulateDay(level, fresh, tight);
                var freshState = VirtualEconomySavedData.get(level).state(fresh.getId().uuid());
                h.assertTrue(VirtualEconomyService.slotsUsed(freshState) <= 2,
                        "A fresh buffer is clamped to two stack slots, got " + VirtualEconomyService.slotsUsed(freshState));
            } finally {
                remove(level, fresh);
            }

            residents.forEach(r -> r.setKilled(true));
            h.assertTrue(VirtualEconomyService.dailyProduction(v, ECONOMY_ONLY).isEmpty(),
                    "A building whose residents were all killed produces nothing");
            var byzPlanSet = ModCultures.getBuildingPlanSet(
                    ResourceLocation.parse("millenaire:byzantines/woodcutter"));
            var byzHut = new BuildingInstance(BuildingId.random(), byzPlanSet.getPlanId("a", 0),
                    v.getCenter().east(16), Rotation.NONE, BuildingInstance.Status.COMPLETE,
                    byzPlanSet.id(), "a", 0);
            v.addBuilding(byzHut);
            v.addVillager(new VillagerRecord(java.util.UUID.randomUUID(),
                    ResourceLocation.parse("millenaire:byzantines/lumberman_byzantine"), byzHut.getId()));
            var byzProd = VirtualEconomyService.dailyProduction(v, ECONOMY_ONLY);
            h.assertTrue(!byzProd.isEmpty()
                            && byzProd.getOrDefault(ResourceLocation.parse("minecraft:oak_log"), 0) > 0,
                    "Non-Norman operational building without abstracted_production falls back to living resident production");
            h.succeed();
        } finally {
            remove(level, v);
        }
    }

    // ---------------------------------------------------------------- 2. villager upkeep

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void unloadedVillagesDeductResidentUpkeepFromRequiredGoods(GameTestHelper h) {
        var level = h.getLevel();
        var v = village(h, "virtual-upkeep");
        try {
            var hungry = ModCultures.getAllVillagerTypes()
                                 .values()
                                 .stream()
                                 .filter(t -> t.resolvedRequiredGoods() != null && !t.resolvedRequiredGoods().isEmpty())
                                 .sorted(java.util.Comparator.comparing(t -> t.id().toString()))
                                 .findFirst()
                                 .orElseThrow();
            for (int i = 0; i < 3; i++)
                v.addVillager(new VillagerRecord(UUID.randomUUID(), hungry.id(), v.getTownhall().getId()));
            var killed = new VillagerRecord(UUID.randomUUID(), hungry.id(), v.getTownhall().getId());
            killed.setKilled(true);
            v.addVillager(killed);
            var upkeep = VirtualEconomyService.dailyConsumption(v, ECONOMY_ONLY);
            for (var e : hungry.resolvedRequiredGoods().entrySet())
                h.assertTrue(upkeep.getOrDefault(BuiltInRegistries.ITEM.getKey(e.getKey()), 0) == 3 * e.getValue(),
                        "Upkeep is requiredGoods x living residents (the killed one does not eat)");
            for (var e : upkeep.entrySet())
                VirtualEconomyService.seed(level, v, e.getKey(), e.getValue() + 5);
            var day = VirtualEconomyService.simulateDay(level, v, ECONOMY_ONLY);
            var state = VirtualEconomySavedData.get(level).state(v.getId().uuid());
            for (var e : upkeep.entrySet()) {
                h.assertTrue(day.consumed().getOrDefault(e.getKey(), 0).equals(e.getValue()),
                        "A full day's upkeep is consumed when stocked");
                h.assertTrue(state.count(e.getKey()) == 5 + day.produced().getOrDefault(e.getKey(), 0),
                        "Exactly the upkeep left the buffer");
            }
            var second = VirtualEconomyService.simulateDay(level, v, ECONOMY_ONLY);
            for (var e : upkeep.entrySet()) {
                h.assertTrue(second.consumed().getOrDefault(e.getKey(), 0) == Math.min(5, e.getValue()),
                        "A short day consumes only what is left");
                h.assertTrue(state.count(e.getKey()) == Math.max(0, 5 - e.getValue()),
                        "Upkeep never drives stock below zero");
            }
            h.succeed();
        } finally {
            remove(level, v);
        }
    }

    // ---------------------------------------------------------------- 3. one-way drain, exactly once

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void loadingDrainsTheVirtualChestIntoTownHallChestsExactlyOnce(GameTestHelper h) {
        var level = h.getLevel();
        var v = village(h, "virtual-drain");
        try {
            var cherry = id(Items.CHERRY_LOG);
            VirtualEconomyService.seed(level, v, cherry, 100);
            v.setActive(true);
            h.assertTrue(VirtualEconomyService.drain(level, v) == 100, "All 100 buffered logs move into the chests");
            var state = VirtualEconomySavedData.get(level).state(v.getId().uuid());
            h.assertTrue(physical(level, v, Items.CHERRY_LOG) == 100 && state.count(cherry) == 0,
                    "The Town Hall holds them and the buffer is empty");
            h.assertTrue(VirtualEconomyService.drain(level, v) == 0 && physical(level, v, Items.CHERRY_LOG) == 100,
                    "A second drain moves nothing (no duplication)");

            // Nearly full chests: what does not fit stays virtual, never lost or doubled.
            var oak = id(Items.OAK_LOG);
            int free = fillAllButOneSlot(level, v);
            VirtualEconomyService.seed(level, v, oak, 200);
            int moved = VirtualEconomyService.drain(level, v);
            h.assertTrue(moved == 64 * free && physical(level, v, Items.OAK_LOG) == moved
                            && state.count(oak) == 200 - moved,
                    "Only the free slot is filled; the rest stays buffered (moved=" + moved + ")");
            // Unload/load cycle without production: the buffer neither grows nor is re-drained twice.
            v.setActive(false);
            VirtualEconomyService.catchUp(level, v, 10, ECONOMY_ONLY);
            VirtualEconomyService.catchUp(level, v, 12, ECONOMY_ONLY);
            v.setActive(true);
            h.assertTrue(physical(level, v, Items.OAK_LOG) + state.count(oak) == 200,
                    "Physical + virtual is conserved across the cycle");
            clearStone(level, v, 2);
            VirtualEconomyService.drain(level, v);
            VirtualEconomyService.drain(level, v);
            h.assertTrue(physical(level, v, Items.OAK_LOG) + state.count(oak) == 200
                            && physical(level, v, Items.OAK_LOG) > moved,
                    "Freed space receives more of the buffer, still conserving all 200 logs");
            try {
                var invField = BuildingInstance.class.getDeclaredField("inventory");
                invField.setAccessible(true);
                var original = v.getTownhall().getInventory();
                var airSpot = v.getCenter().above(6);
                level.getChunkAt(airSpot);
                level.setBlock(airSpot, Blocks.AIR.defaultBlockState(), 2);
                var withAir = new java.util.ArrayList<net.minecraft.core.BlockPos>();
                withAir.add(airSpot);
                withAir.addAll(original.getChestPositions());
                invField.set(v.getTownhall(), new org.millenaire.building.BuildingInventory(withAir));
                try {
                    VirtualEconomyService.drain(level, v);
                } finally {
                    invField.set(v.getTownhall(), original);
                }
                try (var realm = RoyalShipmentSupport.realm(h, "absent_chest_spot")) {
                    var srcHall = realm.source().getTownhall();
                    var srcOrig = srcHall.getInventory();
                    var srcAir = realm.source().getCenter().above(6);
                    level.getChunkAt(srcAir);
                    level.setBlock(srcAir, Blocks.AIR.defaultBlockState(), 2);
                    var srcWithAir = new java.util.ArrayList<net.minecraft.core.BlockPos>();
                    srcWithAir.add(srcAir);
                    srcWithAir.addAll(srcOrig.getChestPositions());
                    invField.set(srcHall, new org.millenaire.building.BuildingInventory(srcWithAir));
                    try {
                        var stock = com.millenairekingdoms.shipping.RoyalVillageStock.forNpcShipment(
                                level, realm.kingdom(), realm.from());
                        var avail = stock.availability(Items.CHERRY_LOG);
                        h.assertTrue(avail.physical() == 96 && avail.available() > 0,
                                "Loaded non-chest Town Hall spots are skipped instead of blocking stock reads");
                        stock.requireUnchanged();
                    } finally {
                        invField.set(srcHall, srcOrig);
                    }
                }
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
            h.succeed();
        } finally {
            remove(level, v);
        }
    }

    // ---------------------------------------------------------------- 4. reservation and credit, zero blocks

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void paidMaterialsEarnBackgroundCreditWithoutTouchingBlocksOrRoyalOrders(GameTestHelper h) {
        var level = h.getLevel();
        var v = village(h, "virtual-credit");
        var royal = village(h, "virtual-credit-royal");
        UUID order = null;
        RoyalConstructionStore store = null;
        try {
            var settings = VirtualEconomyConfig.Settings.DEFAULT.withCreditDays(4);
            var before = snapshot(level, v);
            int buildings = v.getBuildings().size();

            // An existing native pending project keeps its slot.
            var native_ = new Village.PendingProject(ResourceLocation.parse("millenairekingdoms:test_native_project"),
                    "default", 1, true, v.getTownhall().getId());
            v.setPendingProject(native_);
            VirtualEconomyService.simulateDay(level, v, settings);
            h.assertTrue(credit(level, v) == null && native_.equals(v.getPendingProject()),
                    "No credit is chosen while Millénaire already has a pending project");
            v.setPendingProject(null);

            var credit = reserve(h, level, v, settings);
            var required = requiredFor(credit);
            seedRequired(level, v, required);
            int seeded = total(level, v);
            VirtualEconomyService.simulateDay(level, v, settings);
            credit = credit(level, v);
            h.assertTrue(credit.stage() == CreditStage.PAID && credit.materialsPaid(),
                    "Once every requirement is buffered the materials are paid");
            h.assertTrue(credit.reservedTotal() == required.values().stream().mapToInt(Integer::intValue).sum()
                            && total(level, v) == seeded - credit.reservedTotal(),
                    "Exactly the requirements left the buffer into the locked credit");
            double last = credit.progress();
            for (int day = 1; day <= 4; day++) {
                VirtualEconomyService.simulateDay(level, v, settings);
                h.assertTrue(credit(level, v).progress() > last || credit(level, v).progress() == 1.0,
                        "Credit grows every unloaded day");
                last = credit(level, v).progress();
            }
            h.assertTrue(Math.abs(credit(level, v).progress() - 1.0) < 1e-9, "Four days reach 100%");
            VirtualEconomyService.simulateDay(level, v, settings);
            h.assertTrue(credit(level, v).progress() == 1.0, "Credit is clamped at 100%");
            h.assertTrue(snapshot(level, v).equals(before), "Not a single block changed near the village");
            h.assertTrue(v.getBuildings().size() == buildings && v.getPendingProject() == null
                            && v.getBuildings().stream().noneMatch(BuildingInstance::isBeingBuilt),
                    "No building, plot or native project was created while unloaded");

            // A queued royal commission owns the village slot.
            var set = ModCultures.getBuildingPlanSet(credit.planSetId());
            order = UUID.randomUUID();
            store = new RoyalConstructionStore(level.getServer().getWorldPath(LevelResource.ROOT).resolve(
                    "data/millenairekingdoms/royal-construction"));
            store.reserve(new RoyalConstructionStore.Intent(order, level.dimension().location().toString(),
                    UUID.randomUUID(), royal.getId().uuid(), royal.getId().uuid(), null, 0, royal.getVillageTypeId(),
                    set.id(), set.getPlanId(credit.variant(), credit.targetLevel()), credit.variant(), 1,
                    level.getGameTime(), Set.of()));
            h.assertTrue(VirtualEconomyService.royalOrderActive(level, royal.getId().uuid()),
                    "The royal order is visible");
            seedRequired(level, royal, required);
            VirtualEconomyService.simulateDay(level, royal, settings);
            h.assertTrue(credit(level, royal) == null, "No background project competes with a queued royal order");
            store.change(order, RoyalConstructionStore.Stage.REQUESTED, RoyalConstructionStore.Stage.FAILED,
                    RoyalConstructionStore.Blocked.NONE, null);
            order = null;
            VirtualEconomyService.simulateDay(level, royal, settings);
            h.assertTrue(credit(level, royal) != null, "After the royal order ends the village may plan again");
            h.succeed();
        } finally {
            if (order != null && store != null)
                store.change(order, RoyalConstructionStore.Stage.REQUESTED, RoyalConstructionStore.Stage.FAILED,
                        RoyalConstructionStore.Blocked.NONE, null);
            remove(level, v);
            remove(level, royal);
        }
    }

    // ---------------------------------------------------------------- 5. native hand-off

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void paidCreditHandsOffToTheNativePendingProjectAndSpeedsItsConstructionTask(GameTestHelper h) {
        var level = h.getLevel();
        var v = village(h, "virtual-handoff");
        var rejected = village(h, "virtual-handoff-rejected");
        try {
            var settings = VirtualEconomyConfig.Settings.DEFAULT.withCreditDays(2);
            var credit = paid(h, level, v, settings);
            var reserved = Map.copyOf(credit.reserved());
            int virtualBefore = total(level, v);
            v.setActive(true);

            var other = new Village.PendingProject(ResourceLocation.parse("millenairekingdoms:test_native_project"),
                    "default", 1, true, v.getTownhall().getId());
            v.setPendingProject(other);
            h.assertTrue(VirtualEconomyService.handoff(level, v, settings) == Handoff.WAITING
                            && other.equals(v.getPendingProject()) && credit(level, v).stage() == CreditStage.PAID,
                    "An existing native pending project is never overwritten");
            v.setPendingProject(null);

            var physicalBefore = physical(level, v, reserved.keySet());
            h.assertTrue(VirtualEconomyService.handoff(level, v, settings) == Handoff.HANDED_OFF,
                    "With the slot free the project is handed to Millénaire");
            var pending = v.getPendingProject();
            credit = credit(level, v);
            h.assertTrue(pending != null && pending.planSetId().equals(credit.planSetId())
                            && pending.level() == credit.targetLevel() && pending.isUpgrade() == credit.upgrade(),
                    "The native pending project is exactly the paid background project");
            var physicalAfter = physical(level, v, reserved.keySet());
            for (var e : reserved.entrySet())
                h.assertTrue(physicalAfter.get(e.getKey()) - physicalBefore.get(e.getKey()) == e.getValue(),
                        "The paid " + e.getKey() + " are pre-supplied to the Town Hall exactly once");
            h.assertTrue(credit.reservedTotal() == 0 && total(level, v) == virtualBefore,
                    "The credit no longer holds them and the buffer is unchanged (zero duplication)");
            h.assertTrue(VirtualEconomyService.handoff(level, v, settings) == Handoff.HANDED_OFF,
                    "While native growth has not launched it yet, the hand-off waits");

            // Millénaire's own launch: an under-construction building with a live ConstructionTask.
            var building = nativeLaunch(v, credit);
            h.assertTrue(VirtualEconomyService.handoff(level, v, settings) == Handoff.STARTED
                            && credit(level, v) == null,
                    "The live ConstructionTask consumes the credit");
            h.assertTrue(VirtualConstructionBoosts.ratio(building) == 1.0
                            && VirtualConstructionBoosts.scale(20, 1.0, 0.75) == 5
                            && VirtualConstructionBoosts.scale(20, 0.0, 0.75) == 20
                            && VirtualConstructionBoosts.scale(1, 1.0, 0.75) == 1,
                    "100% credit shortens each native block action by maxTimeSaved, never below one tick");
            building.getConstructionTask().advance();
            building.setStatus(BuildingInstance.Status.COMPLETE);
            h.assertTrue(VirtualEconomyService.expireBoosts(level, v) == 1 && VirtualConstructionBoosts.ratio(building) == 0,
                    "The bonus ends with the construction");

            // Millénaire drops the project (for example NO_SPACE): its materials already sit in the Town Hall.
            var second = paid(h, level, rejected, settings);
            var secondReserved = Map.copyOf(second.reserved());
            rejected.setActive(true);
            var rejectedBefore = physical(level, rejected, secondReserved.keySet());
            h.assertTrue(VirtualEconomyService.handoff(level, rejected, settings) == Handoff.HANDED_OFF, "Handed off");
            rejected.setPendingProject(null);
            h.assertTrue(VirtualEconomyService.handoff(level, rejected, settings) == Handoff.REJECTED
                            && credit(level, rejected) == null,
                    "A dropped project releases the credit");
            var rejectedAfter = physical(level, rejected, secondReserved.keySet());
            for (var e : secondReserved.entrySet())
                h.assertTrue(rejectedAfter.get(e.getKey()) - rejectedBefore.get(e.getKey()) == e.getValue(),
                        "Refunded materials stay in the Town Hall, counted once");
            h.succeed();
        } finally {
            remove(level, v);
            remove(level, rejected);
        }
    }

    // ---------------------------------------------------------------- 6. persistence across JVMs

    @GameTest(templateNamespace = NS, template = "empty", batch = "virtual_economy_restart", timeoutTicks = 400)
    public static void virtualBufferAndCreditSurviveAnIndependentJvmAndDrainOnce(GameTestHelper h) {
        var level = h.getLevel();
        var marker = level.getServer().getWorldPath(LevelResource.ROOT).resolve("virtual-economy-restart.dat");
        var cherry = id(Items.CHERRY_LOG);
        if (RoyalShipmentSupport.read()) {
            var saved = HistoryFiles.read(marker);
            var v = VillageSavedData.get(level).getVillageManager().getVillage(new VillageId(saved.getUUID("village")));
            h.assertTrue(v != null && v.getTownhall() != null, "The native village record reloads unchanged");
            try {
                RoyalShipmentSupport.activateVillage(level, v);
                var state = VirtualEconomySavedData.get(level).state(v.getId().uuid());
                h.assertTrue(state != null && state.count(cherry) == saved.getInt("cherry")
                                && state.lastSimulatedDay() == saved.getLong("day"),
                        "virtual_economy.dat restores the buffer and day marker");
                var credit = state.credit();
                h.assertTrue(credit != null && credit.stage().name().equals(saved.getString("stage"))
                                && credit.planSetId().toString().equals(saved.getString("planSet"))
                                && credit.reservedTotal() == saved.getInt("reserved")
                                && Math.abs(credit.progress() - saved.getDouble("progress")) < 1e-9,
                        "The pending credit restores every field");
                int physical = physical(level, v, Items.CHERRY_LOG);
                h.assertTrue(VirtualEconomyService.drain(level, v) == saved.getInt("cherry")
                                && physical(level, v, Items.CHERRY_LOG) == physical + saved.getInt("cherry"),
                        "The restored buffer drains into the Town Hall");
                h.assertTrue(VirtualEconomyService.drain(level, v) == 0, "...exactly once");
                h.succeed();
            } finally {
                remove(level, v);
            }
            return;
        }
        var v = village(h, "virtual-restart");
        var settings = VirtualEconomyConfig.Settings.DEFAULT.withCreditDays(3);
        var credit = paid(h, level, v, settings);
        VirtualEconomyService.simulateDay(level, v, settings);
        VirtualEconomyService.seed(level, v, cherry, 77);
        VirtualEconomyService.catchUp(level, v, 40, settings);
        var data = VirtualEconomySavedData.get(level);
        var state = data.state(v.getId().uuid());
        credit = state.credit();

        // In-memory round trip of every field, including a build-time bonus.
        var tag = data.save(new CompoundTag(), level.registryAccess());
        var copy = VirtualEconomySavedData.load(tag, level.registryAccess()).state(v.getId().uuid());
        h.assertTrue(copy.virtualChest().equals(state.virtualChest()) && copy.lastSimulatedDay() == 40
                        && copy.credit().stage() == credit.stage() && copy.credit().reserved().equals(credit.reserved())
                        && copy.credit().progress() == credit.progress()
                        && copy.credit().targetLevel() == credit.targetLevel()
                        && java.util.Objects.equals(copy.credit().upgradeBuildingId(), credit.upgradeBuildingId()),
                "NBT round trip keeps the buffer, day and credit");

        var saved = new CompoundTag();
        saved.putUUID("village", v.getId().uuid());
        saved.putInt("cherry", state.count(cherry));
        saved.putLong("day", state.lastSimulatedDay());
        saved.putString("stage", credit.stage().name());
        saved.putString("planSet", credit.planSetId().toString());
        saved.putInt("reserved", credit.reservedTotal());
        saved.putDouble("progress", credit.progress());
        HistoryFiles.write(marker, saved);
        data.setDirty();
        VillageSavedData.get(level).setDirty();
        level.getServer().saveEverything(true, true, true);
        h.succeed();
    }

    // ---------------------------------------------------------------- 7-9. royal shipments and the virtual buffer

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 1200)
    public static void unloadedSourceShipsFromItsVirtualBufferWithoutTouchingItsChests(GameTestHelper h) {
        var r = RoyalShipmentSupport.realm(h, "virtual-ship-source");
        var level = r.level();
        var cherry = id(Items.CHERRY_LOG);
        r.source().setActive(false);
        VirtualEconomyService.seed(level, r.source(), cherry, 60);
        h.assertTrue(physicalOf(r, r.source()) == 96, "The unloaded source's chests hold 96 logs");
        var stock = com.millenairekingdoms.shipping.RoyalVillageStock.forNpcShipment(level, r.kingdom(), r.from());
        h.assertTrue(stock.virtual() && stock.products().contains(Items.CHERRY_LOG)
                        && stock.availability(Items.CHERRY_LOG).available() == 60,
                "A shipment quote reads the unloaded source's virtual buffer (60 logs)");
        rejected(h, () -> com.millenairekingdoms.shipping.RoyalVillageStock.forNpc(level, r.kingdom(), r.from()));
        var id = UUID.randomUUID();
        RoyalShipmentSupport.fastRequest(r, null, id);
        untilTerminal(h, r, id, false, 0, () -> {
            var entry = r.store().load(id);
            h.assertTrue(entry.stage() == com.millenairekingdoms.shipping.RoyalShipmentStore.Stage.DELIVERED
                            && entry.collected() == 40 && entry.delivered() == 40,
                    "The shipment completes: " + entry.stage() + " " + entry.diagnosis());
            h.assertTrue(VirtualEconomyService.virtualCount(level, r.from(), cherry) == 20
                            && physicalOf(r, r.source()) == 96,
                    "Exactly 40 logs left the virtual buffer and the chests were never touched: virtual="
                            + VirtualEconomyService.virtualCount(level, r.from(), cherry) + " physical="
                            + physicalOf(r, r.source()) + " target=" + r.goods(r.target()) + " ops="
                            + entry.operations());
            h.assertTrue(r.goods(r.target()) == 44, "The loaded destination received the 40 logs physically");
            h.assertTrue(entry.operations().stream().allMatch(o -> o.applied())
                            && VirtualEconomySavedData.get(level).cargoApplied(entry.operations()
                                                                                       .stream()
                                                                                       .filter(o -> o.role()
                                                                                               == com.millenairekingdoms.shipping.RoyalShipmentStore.Role.COLLECT)
                                                                                       .findFirst()
                                                                                       .orElseThrow()
                                                                                       .id()),
                    "The virtual collection is journaled and in the exactly-once ledger");
        });
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 1200)
    public static void unloadedDestinationReceivesIntoItsVirtualBufferAndDrainsOnLoad(GameTestHelper h) {
        var r = RoyalShipmentSupport.realm(h, "virtual-ship-target");
        var level = r.level();
        var cherry = id(Items.CHERRY_LOG);
        r.target().setActive(false);
        var id = UUID.randomUUID();
        RoyalShipmentSupport.fastRequest(r, null, id);
        untilTerminal(h, r, id, true, 0, () -> {
            var entry = r.store().load(id);
            h.assertTrue(entry.stage() == com.millenairekingdoms.shipping.RoyalShipmentStore.Stage.DELIVERED,
                    "Delivery to an unloaded town hall no longer waits for a player: " + entry.diagnosis());
            h.assertTrue(VirtualEconomyService.virtualCount(level, r.to(), cherry) == 40 && r.goods(r.target()) == 4
                            && r.goods(r.source()) == 56,
                    "40 logs arrive in the virtual buffer; physical chests are unchanged until load");
            r.service().observe(id);
            h.assertTrue(VirtualEconomyService.virtualCount(level, r.to(), cherry) == 40,
                    "Observing a delivered shipment again never re-delivers");
            r.target().setActive(true);
            h.assertTrue(VirtualEconomyService.drain(level, r.target()) == 40 && r.goods(r.target()) == 44
                            && VirtualEconomyService.virtualCount(level, r.to(), cherry) == 0,
                    "When the village loads the delivered logs drain into its Town Hall exactly once");
        });
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void virtualCargoLedgerAppliesEachOperationExactlyOnce(GameTestHelper h) {
        var level = h.getLevel();
        var v = village(h, "virtual-ledger");
        try {
            var item = id(Items.BRICK);
            UUID deliver = UUID.randomUUID(), collect = UUID.randomUUID(), tooMuch = UUID.randomUUID();
            var village = v.getId().uuid();
            h.assertTrue(VirtualEconomyService.applyCargo(level, village, deliver, item, 30)
                            == VirtualEconomyService.Cargo.APPLIED,
                    "A delivery applies");
            h.assertTrue(VirtualEconomyService.applyCargo(level, village, deliver, item, 30)
                                    == VirtualEconomyService.Cargo.ALREADY_APPLIED
                            && VirtualEconomyService.virtualCount(level, village, item) == 30,
                    "Replaying the same operation (crash recovery) changes nothing");
            h.assertTrue(VirtualEconomyService.applyCargo(level, village, tooMuch, item, -31)
                                    == VirtualEconomyService.Cargo.INSUFFICIENT
                            && !VirtualEconomySavedData.get(level).cargoApplied(tooMuch),
                    "A collection beyond the buffer is refused and not recorded");
            h.assertTrue(VirtualEconomyService.applyCargo(level, village, collect, item, -12)
                                    == VirtualEconomyService.Cargo.APPLIED
                            && VirtualEconomyService.virtualCount(level, village, item) == 18,
                    "A collection removes exactly its quantity");
            var data = VirtualEconomySavedData.get(level);
            var copy = VirtualEconomySavedData.load(data.save(new CompoundTag(), level.registryAccess()),
                    level.registryAccess());
            h.assertTrue(copy.cargoApplied(deliver) && copy.cargoApplied(collect) && !copy.cargoApplied(tooMuch)
                            && copy.state(village).count(item) == 18,
                    "The ledger and buffer persist together in virtual_economy.dat");
            var file = net.minecraft.world.level.dimension.DimensionType
                               .getStorageFolder(level.dimension(), level.getServer().getWorldPath(LevelResource.ROOT))
                               .resolve("data/" + VirtualEconomySavedData.NAME + ".dat");
            h.assertTrue(java.nio.file.Files.exists(file), "Each applied cargo operation is written to disk at once");
            // Delivered goods are never trimmed by the daily production cap.
            VirtualEconomyService.simulateDay(level, v, ECONOMY_ONLY.withMaxVirtualSlots(0));
            h.assertTrue(VirtualEconomyService.virtualCount(level, village, item) == 18,
                    "The storage cap only trims new production, never delivered cargo");
            h.succeed();
        } finally {
            remove(level, v);
        }
    }

    private static int physicalOf(RoyalShipmentSupport.Realm r, Village v) {
        return r.goods(v);
    }
    private static void rejected(GameTestHelper h, Runnable action) {
        boolean failed = false;
        try {
            action.run();
        } catch (IllegalStateException expected) {
            failed = true;
        }
        h.assertTrue(failed, "Non-shipment stock access keeps requiring loaded physical chests");
    }
    /** Observes a shipment every tick, keeping only the fixture's loaded villages active, until it is terminal. */
    private static void untilTerminal(GameTestHelper h, RoyalShipmentSupport.Realm r, UUID id, boolean sourceLoaded,
            int attempts, Runnable assertions) {
        h.runAfterDelay(1, () -> {
            // Millénaire re-derives the active flag from loaded chunks every tick; the fixture keeps the "unloaded"
            // endpoint inactive (as a far village without players would be) and the others active.
            RoyalShipmentSupport.activateVillage(r.level(), r.capital());
            RoyalShipmentSupport.activateVillage(r.level(), sourceLoaded ? r.source() : r.target());
            (sourceLoaded ? r.target() : r.source()).setActive(false);
            try {
                r.service().observe(id);
            } catch (RuntimeException waiting) {
                // retried next tick
            }
            var entry = r.store().load(id);
            if (entry.terminal()) {
                try {
                    assertions.run();
                } finally {
                    r.close();
                }
                h.succeed();
                return;
            }
            if (attempts > 600) {
                r.close();
                h.fail("Shipment did not finish: " + entry.stage() + " " + entry.blocked() + " " + entry.diagnosis());
                return;
            }
            untilTerminal(h, r, id, sourceLoaded, attempts + 1, assertions);
        });
    }

    // ---------------------------------------------------------------- fixtures

    private static Village village(GameTestHelper h, String name) {
        int x = (RoyalShipmentSupport.read() ? 1_500_000 : 1_200_000) + NEXT.incrementAndGet() * 4096;
        var v = RoyalShipmentSupport.village(h.getLevel(), name, new BlockPos(x, -50, 90_000));
        v.setActive(false);
        return v;
    }
    private static void remove(ServerLevel level, Village v) {
        if (v != null)
            VillageSavedData.get(level).getVillageManager().removeVillage(v.getId());
    }

    /** The first plan level with resolvable, compact abstracted_production (any culture). */
    private static BuildingInstance productionBuilding(Village v) {
        for (var set : ModCultures.getAllBuildingPlanSets()
                               .values()
                               .stream()
                               .sorted(java.util.Comparator.comparing(s -> s.id().toString()))
                               .toList()) {
            for (var variant : set.variants().entrySet()) {
                for (var level : variant.getValue()) {
                    var production = level.abstractedProduction();
                    if (production.isEmpty() || set.getPlanId(variant.getKey(), level.level()) == null)
                        continue;
                    int slots = 0;
                    boolean ok = true;
                    for (var good : production.entrySet()) {
                        var item = VirtualEconomyService.resolveGood(set.culture(), good.getKey());
                        if (item == null || good.getValue() <= 0) {
                            ok = false;
                            break;
                        }
                        slots += (good.getValue() + 63) / 64;
                    }
                    if (!ok || slots > 6)
                        continue;
                    var building = new BuildingInstance(BuildingId.random(),
                            set.getPlanId(variant.getKey(), level.level()), v.getCenter().offset(48, 0, 0),
                            Rotation.NONE, BuildingInstance.Status.COMPLETE, set.id(), variant.getKey(), level.level());
                    v.addBuilding(building);
                    return building;
                }
            }
        }
        throw new IllegalStateException("No culture defines resolvable abstracted_production");
    }
    private static BuildingPlanSet.LevelDef definition(BuildingInstance b) {
        return ModCultures.getBuildingPlanSet(b.getPlanSetId()).getLevel(b.getVariant(), b.getLevel());
    }
    private static ResourceLocation resolve(BuildingInstance b, String key) {
        var item = VirtualEconomyService.resolveGood(ModCultures.getBuildingPlanSet(b.getPlanSetId()).culture(), key);
        return item;
    }
    private static VillagerRecord resident(Village v, BuildingInstance home, boolean hungry) {
        VillagerType type = ModCultures.getAllVillagerTypes()
                                    .values()
                                    .stream()
                                    .filter(t -> (t.resolvedRequiredGoods() == null || t.resolvedRequiredGoods().isEmpty())
                                                 != hungry)
                                    .filter(t -> t.requiredGoods() == null || t.requiredGoods().isEmpty() || hungry)
                                    .sorted(java.util.Comparator.comparing(t -> t.id().toString()))
                                    .findFirst()
                                    .orElseThrow();
        var record = new VillagerRecord(UUID.randomUUID(), type.id(), home.getId());
        v.addVillager(record);
        return record;
    }

    private static VirtualEconomySavedData.Credit credit(ServerLevel level, Village v) {
        var state = VirtualEconomySavedData.get(level).state(v.getId().uuid());
        return state == null ? null : state.credit();
    }
    private static VirtualEconomySavedData.Credit reserve(
            GameTestHelper h, ServerLevel level, Village v, VirtualEconomyConfig.Settings settings) {
        VirtualEconomyService.simulateDay(level, v, settings);
        var credit = credit(level, v);
        h.assertTrue(credit != null, "The native growth rules offer a next project for the unloaded village");
        if (credit.stage() == CreditStage.RESERVING)
            h.assertTrue(!credit.materialsPaid() && credit.reservedTotal() == 0,
                    "Before its materials exist nothing is reserved");
        return credit;
    }
    private static VirtualEconomySavedData.Credit paid(
            GameTestHelper h, ServerLevel level, Village v, VirtualEconomyConfig.Settings settings) {
        var credit = reserve(h, level, v, settings);
        seedRequired(level, v, requiredFor(credit));
        for (int day = 0; day <= settings.creditDays(); day++)
            VirtualEconomyService.simulateDay(level, v, settings);
        credit = credit(level, v);
        h.assertTrue(credit != null && credit.stage() == CreditStage.PAID && credit.progress() == 1.0,
                "The project is paid and fully credited");
        return credit;
    }
    /** Concrete items for the credit's requirements (anywood as oak logs). */
    private static Map<ResourceLocation, Integer> requiredFor(VirtualEconomySavedData.Credit credit) {
        var definition = ModCultures.getBuildingPlanSet(credit.planSetId())
                                 .getLevel(credit.variant(), credit.targetLevel());
        var out = new LinkedHashMap<ResourceLocation, Integer>();
        for (var e : (definition.requiredResources() == null ? Map.<ResourceLocation, Integer>of() : definition.requiredResources()).entrySet())
            if (e.getValue() > 0)
                out.merge(AnywoodHelper.isAnywood(e.getKey()) ? id(Items.OAK_LOG) : e.getKey(), e.getValue(),
                        Integer::sum);
        return out;
    }
    private static void seedRequired(ServerLevel level, Village v, Map<ResourceLocation, Integer> required) {
        required.forEach((item, count) -> VirtualEconomyService.seed(level, v, item, count));
    }
    private static int total(ServerLevel level, Village v) {
        var state = VirtualEconomySavedData.get(level).state(v.getId().uuid());
        return state == null ? 0 : state.total();
    }
    private static BuildingInstance nativeLaunch(Village v, VirtualEconomySavedData.Credit credit) {
        var task = new ConstructionTask(
                List.of(new PlacementStep(BlockPos.ZERO, Blocks.STONE.defaultBlockState()),
                        new PlacementStep(BlockPos.ZERO.above(), Blocks.STONE.defaultBlockState())),
                0);
        BuildingInstance building;
        if (credit.upgrade()) {
            building = v.getBuilding(new BuildingId(credit.upgradeBuildingId()));
            building.setStatus(BuildingInstance.Status.UPGRADING);
        } else {
            var set = ModCultures.getBuildingPlanSet(credit.planSetId());
            building = new BuildingInstance(BuildingId.random(), set.getPlanId(credit.variant(), credit.targetLevel()),
                    v.getCenter().offset(64, 0, 0), Rotation.NONE, BuildingInstance.Status.UNDER_CONSTRUCTION, set.id(),
                    credit.variant(), credit.targetLevel());
            v.addBuilding(building);
        }
        building.setConstructionTask(task);
        v.setPendingProject(null);
        return building;
    }

    private static ResourceLocation id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item);
    }
    private static List<ChestBlockEntity> chests(ServerLevel level, Village v) {
        var out = new ArrayList<ChestBlockEntity>();
        for (var pos : v.getTownhall().getInventory().getChestPositions())
            out.add((ChestBlockEntity) level.getBlockEntity(pos));
        return out;
    }
    private static int physical(ServerLevel level, Village v, Item item) {
        int total = 0;
        for (var chest : chests(level, v))
            for (int slot = 0; slot < chest.getContainerSize(); slot++)
                if (chest.getItem(slot).is(item))
                    total += chest.getItem(slot).getCount();
        return total;
    }
    private static Map<ResourceLocation, Integer> physical(ServerLevel level, Village v, Set<ResourceLocation> items) {
        var out = new HashMap<ResourceLocation, Integer>();
        for (var id : items)
            out.put(id, physical(level, v, BuiltInRegistries.ITEM.get(id)));
        return out;
    }
    /** Fills every empty slot but one with stone; returns the number of free slots left (1). */
    private static int fillAllButOneSlot(ServerLevel level, Village v) {
        var empty = new ArrayList<int[]>();
        var chests = chests(level, v);
        for (int c = 0; c < chests.size(); c++)
            for (int slot = 0; slot < chests.get(c).getContainerSize(); slot++)
                if (chests.get(c).getItem(slot).isEmpty())
                    empty.add(new int[] {c, slot});
        for (int i = 1; i < empty.size(); i++)
            chests.get(empty.get(i)[0]).setItem(empty.get(i)[1], new ItemStack(Items.STONE, 64));
        return Math.min(1, empty.size());
    }
    private static void clearStone(ServerLevel level, Village v, int slots) {
        int cleared = 0;
        for (var chest : chests(level, v))
            for (int slot = 0; slot < chest.getContainerSize() && cleared < slots; slot++)
                if (chest.getItem(slot).is(Items.STONE)) {
                    chest.setItem(slot, ItemStack.EMPTY);
                    cleared++;
                }
    }
    private static List<BlockState> snapshot(ServerLevel level, Village v) {
        var out = new ArrayList<BlockState>();
        var c = v.getCenter();
        for (int dx = -12; dx <= 12; dx += 2)
            for (int dy = -4; dy <= 8; dy += 2)
                for (int dz = -12; dz <= 12; dz += 2)
                    out.add(level.getBlockState(c.offset(dx, dy, dz)));
        return out;
    }
}
