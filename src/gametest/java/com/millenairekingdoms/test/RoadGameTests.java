package com.millenairekingdoms.test;

import com.millenairekingdoms.autonomy.ExpeditionPricingService;
import com.millenairekingdoms.economy.EconomyService;
import com.millenairekingdoms.economy.RoyalEconomyConfig;
import com.millenairekingdoms.economy.RoyalPrices;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.roads.RoadConfig;
import com.millenairekingdoms.roads.RoadPlacer;
import com.millenairekingdoms.roads.RoadPlanner;
import com.millenairekingdoms.roads.RoadRuntime;
import com.millenairekingdoms.roads.RoadSavedData;
import com.millenairekingdoms.roads.RoadSavedData.RoadSegment;
import com.millenairekingdoms.roads.RoadSavedData.Status;
import com.millenairekingdoms.roads.RoadSavedData.Waypoint;
import com.millenairekingdoms.roads.RoadService;
import com.millenairekingdoms.shipping.RoyalShipmentService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.block.ModBlocks;
import org.millenaire.building.BuildingId;
import org.millenaire.building.BuildingInstance;
import org.millenaire.village.Village;
import org.millenaire.village.VillageSavedData;

/**
 * Automated verification suite ({@code verifyRoads}) for Millenaire Kingdoms 1.2.4 Royal Roads:
 * chunk-safe 2D planning, surplus consumption + pause/resume, physical cultural road placement + protection,
 * transitive connectivity, +2 daily bonus, shipment speedup, expedition discount, conquest break, NPC autonomy,
 * frontier worker idempotency, and separate-JVM persistence.
 */
@PrefixGameTestTemplate(false)
public final class RoadGameTests {
    private static final String NS = "millenairekingdoms_road_tests";
    private static final UUID PERSIST_KINGDOM_ID = UUID.fromString("12400000-0000-0000-0000-000000000001");
    private static final UUID PERSIST_CAPITAL_ID = UUID.fromString("12400000-0000-0000-0000-000000000002");
    private static final UUID PERSIST_MEMBER_ID = UUID.fromString("12400000-0000-0000-0000-000000000003");
    private static final UUID PERSIST_ROAD_ID = UUID.fromString("12400000-0000-0000-0000-000000000004");

    private RoadGameTests() {}

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 400)
    public static void chunkSafePlanningNeverLoadsIntermediateChunks(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos start = new BlockPos(4800, 64, 4800);
        BlockPos end = new BlockPos(5500, 64, 4800);
        BlockPos midpoint = new BlockPos(5150, 64, 4800);
        int midCx = SectionPos.blockToSectionCoord(midpoint.getX());
        int midCz = SectionPos.blockToSectionCoord(midpoint.getZ());
        h.assertTrue(!level.hasChunk(midCx, midCz), "Intermediate chunk must start unloaded");

        RoadConfig.Settings cfg = RoadConfig.current();
        RoadPlanner.PlanResult plan = RoadPlanner.plan(level, start, end, cfg);
        h.assertTrue(plan.ok(), "Overland 700m route should plan cleanly");
        h.assertTrue(!level.hasChunk(midCx, midCz),
                "RoadPlanner must never load or generate intermediate chunks during route planning");

        RoadPlanner.PlanResult tooFar = RoadPlanner.plan(level, start, start.offset(1500, 0, 0), cfg);
        h.assertTrue(!tooFar.ok() && "OVER_DISTANCE".equals(tooFar.failureReason()),
                "Segments exceeding maxSegmentDistance must be rejected");
        h.succeed();
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 400)
    public static void surplusConsumptionPausesOnEmptyAndAutoResumes(GameTestHelper h) {
        try (var f = RoyalShipmentSupport.realm(h, "road-surplus")) {
            f.activate(false);
            var chest = f.chest(f.source());
            for (int i = 0; i < 27; i++)
                chest.setItem(i, ItemStack.EMPTY);
            // Place 64 cobblestone, then trim to leave exact 4 surplus units above the protected reserve floor.
            chest.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
            chest.setChanged();
            int initialSurplus = com.millenairekingdoms.roads.RoadMaterialStock.availableForVillage(
                    f.level(), f.source().getId().uuid());
            h.assertTrue(initialSurplus >= 4,
                    "Expected >= 4 surplus from 64 cobblestone, got " + initialSurplus);
            int retainedBaseline = 64 - initialSurplus;
            if (initialSurplus > 4) {
                chest.setItem(0, new ItemStack(Items.COBBLESTONE, retainedBaseline + 4));
                chest.setChanged();
            }

            var ruler = f.ruler();
            RoadSegment ordered = RoadService.orderPlayerRoad(
                    ruler, f.capital().getId().uuid(), f.source().getId().uuid());
            h.assertTrue(ordered.status() == Status.BUILDING, "Ordered road starts BUILDING");

            KingdomSavedData kd = KingdomSavedData.get(f.level());
            RoadSavedData rd = RoadSavedData.get(f.level());
            long baseDay = Math.max(rd.lastProcessedDay() + 1L, Math.max(10L, kd.day() + 1L));

            // Day 1: consumes all 4 surplus units (advances 4 * 5 = 20 blocks, preserves protected baseline).
            RoadService.dailyStep(f.level(), kd, baseDay);
            RoadSegment afterDay1 = RoadSavedData.get(f.level()).segment(ordered.id());
            h.assertTrue(afterDay1.completedBlocks() == 20,
                    "Expected 20 completed blocks from 4 surplus units, got " + afterDay1.completedBlocks());
            int cobbleLeft = 0;
            for (int i = 0; i < 27; i++)
                if (chest.getItem(i).is(Items.COBBLESTONE))
                    cobbleLeft += chest.getItem(i).getCount();
            h.assertTrue(cobbleLeft == retainedBaseline,
                    "Protected baseline (" + retainedBaseline + ") must never be touched; got " + cobbleLeft);

            // Day 2: 0 surplus -> gracefully pauses without cancelling.
            RoadService.dailyStep(f.level(), kd, baseDay + 1L);
            RoadSegment afterDay2 = RoadSavedData.get(f.level()).segment(ordered.id());
            h.assertTrue(afterDay2.status() == Status.PAUSED_MATERIALS && afterDay2.completedBlocks() == 20,
                    "Road must pause on empty surplus without losing progress");

            // Restock surplus (simulating shipment / production arrival) -> Day 3 auto-resumes!
            chest.setItem(1, new ItemStack(Items.COBBLESTONE, 10));
            chest.setChanged();
            RoadService.dailyStep(f.level(), kd, baseDay + 2L);
            RoadSegment afterDay3 = RoadSavedData.get(f.level()).segment(ordered.id());
            h.assertTrue(afterDay3.completedBlocks() > 20,
                    "Road must auto-resume once surplus is available (completed=" + afterDay3.completedBlocks() + ")");
            h.assertTrue(afterDay3.status() == Status.BUILDING || afterDay3.status() == Status.COMPLETED,
                    "Resumed status should be BUILDING or COMPLETED");
            h.succeed();
        }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 400)
    public static void physicalRealizationPlacesCulturalRoadAndProtectsFarmsChestsBuildings(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos origin = h.absolutePos(new BlockPos(2, 2, 2));
        // Prepare flat terrain Strip (24 blocks long along +X) with a water span and protected objects.
        for (int dx = 0; dx < 24; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos ground = origin.offset(dx, 0, dz);
                level.setBlockAndUpdate(ground, Blocks.GRASS_BLOCK.defaultBlockState());
                for (int dy = 1; dy <= 4; dy++)
                    level.setBlockAndUpdate(ground.above(dy), Blocks.AIR.defaultBlockState());
            }
        }
        // Water channel at dx = 10..11 (surface at ground Y).
        for (int dx = 10; dx <= 11; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                level.setBlockAndUpdate(origin.offset(dx, 0, dz), Blocks.WATER.defaultBlockState());
            }
        }
        // Protected crop + farmland + chest right on the path centerline at dx = 5, 6, 7.
        BlockPos cropSoil = origin.offset(5, 0, 0);
        BlockPos cropPlant = origin.offset(5, 1, 0);
        level.setBlockAndUpdate(cropSoil, Blocks.FARMLAND.defaultBlockState());
        level.setBlockAndUpdate(cropPlant, Blocks.WHEAT.defaultBlockState());
        BlockPos chestPos = origin.offset(7, 1, 0);
        level.setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());

        RoadSegment seg = new RoadSegment(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "millenaire:norman", "millenaire:norman_hamlet",
                List.of(new Waypoint(origin.getX(), origin.getZ()),
                        new Waypoint(origin.getX() + 23, origin.getZ())),
                23, 23, Status.COMPLETED, 0L, 0L, 0L);

        RoadPlacer.realizeLoadedAlong(level, seg, 0, 23);

        // Open ground (dx = 15) should have cultural road block (from norman_hamlet pathMaterials).
        var expectedStyle = RoadPlacer.styleFor(seg);
        h.assertTrue(level.getBlockState(origin.offset(15, 0, 0)).is(expectedStyle.fullBlock()),
                "Open ground must receive Millenaire cultural road block");
        // Water channel (dx = 10) should have bridge deck 1 block above water.
        h.assertTrue(level.getBlockState(origin.offset(10, 1, 0)).is(expectedStyle.bridgeDeck()),
                "Water crossing must receive bridge deck 1 block above water");
        // Protected farmland, crop, and chest must remain untouched.
        h.assertTrue(level.getBlockState(cropSoil).is(Blocks.FARMLAND), "Farmland must be protected");
        h.assertTrue(level.getBlockState(cropPlant).is(Blocks.WHEAT), "Crops must be protected");
        h.assertTrue(level.getBlockEntity(chestPos) instanceof ChestBlockEntity, "Chest containers must be protected");
        h.succeed();
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 400)
    public static void transitiveConnectivityBonusSpeedDiscountAndConquestBreak(GameTestHelper h) {
        try (var f = RoyalShipmentSupport.realm(h, "road-network")) {
            ServerLevel level = f.level();
            KingdomSavedData data = KingdomSavedData.get(level);
            BlockPos posA = f.capital().getCenter();
            BlockPos posB = f.source().getCenter();
            BlockPos posC = f.target().getCenter();
            Village enemyCap = RoyalShipmentSupport.village(level, "Enemy Cap", posA.offset(300, 0, 300));
            try {
                var k2 = data.create("Enemy Realm " + UUID.randomUUID().toString().substring(0, 6),
                        enemyCap.getId().uuid());

                long baseIncome = EconomyService.dailyIncome(level, data, f.kingdom());

                // Complete A -> B and B -> C to form transitive C -> B -> Capital chain.
                RoadSavedData roads = RoadSavedData.get(level);
                RoadSegment segAB = new RoadSegment(UUID.randomUUID(), f.kingdom(), f.capital().getId().uuid(),
                        f.source().getId().uuid(), "millenaire:byzantines",
                        f.capital().getVillageTypeId().toString(),
                        List.of(new Waypoint(posA.getX(), posA.getZ()), new Waypoint(posB.getX(), posB.getZ())),
                        512, 512, Status.COMPLETED, 4L, 1L, level.getGameTime());
                RoadSegment segBC = new RoadSegment(UUID.randomUUID(), f.kingdom(), f.source().getId().uuid(),
                        f.target().getId().uuid(), "millenaire:byzantines",
                        f.source().getVillageTypeId().toString(),
                        List.of(new Waypoint(posB.getX(), posB.getZ()), new Waypoint(posC.getX(), posC.getZ())),
                        512, 512, Status.COMPLETED, 4L, 1L, level.getGameTime());
                roads.addSegment(segAB);
                roads.addSegment(segBC);

                var connected = roads.connectedToCapital(data, f.kingdom());
                h.assertTrue(connected.contains(f.capital().getId().uuid())
                                && connected.contains(f.source().getId().uuid())
                                && connected.contains(f.target().getId().uuid()),
                        "Transitive chain C -> B -> Capital must connect all 3 villages");

                long roadIncome = EconomyService.dailyIncome(level, data, f.kingdom());
                int expectedBonus = 3 * RoadConfig.current().connectedVillageDailyBonus();
                h.assertTrue(roadIncome == baseIncome + expectedBonus,
                        "Expected +" + expectedBonus + " road income, got delta " + (roadIncome - baseIncome));

                // Verify shipment travel reduction between source (B) and target (C).
                f.activate(true);
                f.chest(f.source()).setItem(0, new ItemStack(Items.CHERRY_LOG, 32));
                var quote = new RoyalShipmentService(level).quotePlayer(
                        f.ruler(), f.from(), f.to(),
                        ResourceLocation.withDefaultNamespace("cherry_log"), 4);
                long rawTicks = com.millenairekingdoms.shipping.RoyalShipmentConfig.transit().duration(quote.distance());
                h.assertTrue(quote.travelTicks() < rawTicks,
                        "Linked shipment travel ticks (" + quote.travelTicks() + ") must be reduced vs " + rawTicks);

                // Verify expedition fee discount from connected village vs unconnected target.
                var guardType = org.millenaire.culture.ModCultures.getAllVillagerTypes()
                                        .values()
                                        .stream()
                                        .filter(t -> t.isRaider() && t.baseAttackStrength() > 0)
                                        .findFirst()
                                        .orElseThrow();
                f.source().addVillager(UUID.randomUUID(), guardType.id());
                int strength = f.source().getVillageRaidingStrength();
                h.assertTrue(strength > 0, "Source village must have raiding strength");
                var discounted = ExpeditionPricingService.quote(level, data, f.kingdom(), f.source(), enemyCap, false);
                long undiscounted = RoyalPrices.expedition(
                        RoyalEconomyConfig.current(), strength,
                        RoyalPrices.horizontalDistance(posB, enemyCap.getCenter()));
                h.assertTrue(discounted.cost() < undiscounted,
                        "Connected village expedition cost (" + discounted.cost() + ") must beat raw " + undiscounted);

                // Conquer intermediate village B to K2: C loses active connectivity immediately!
                var war = data.declareWar(k2.id(), f.kingdom(), level.getGameTime());
                for (int w = 0; w < 2; w++) {
                    var raid = data.begin(enemyCap.getId().uuid(), f.source().getId().uuid(),
                            level.getGameTime() + w + 1L, null);
                    data.recordVictory(raid);
                    data.finish(raid, KingdomSavedData.Outcome.LOST);
                }
                h.assertTrue(k2.id().equals(data.owner(f.source().getId().uuid())),
                        "Conquest must transfer intermediate village B to K2");
                var afterConquest = roads.connectedToCapital(data, f.kingdom());
                h.assertTrue(!afterConquest.contains(f.target().getId().uuid()),
                        "Conquering intermediate village B must break C's active capital-network status");
                h.assertTrue(roads.segment(segAB.id()).isCompleted() && roads.segment(segBC.id()).isCompleted(),
                        "Physical completed road records/blocks stay intact despite conquest");
                data.peace(war.id(), KingdomSavedData.PEACE_REASON, 0);

                // Reclaim B back to original kingdom: C immediately regains connectivity!
                var reclaimWar = data.declareWar(f.kingdom(), k2.id(), level.getGameTime() + 10L);
                for (int w = 0; w < 2; w++) {
                    var raid = data.begin(f.capital().getId().uuid(), f.source().getId().uuid(),
                            level.getGameTime() + 20L + w, null);
                    data.recordVictory(raid);
                    data.finish(raid, KingdomSavedData.Outcome.LOST);
                }
                data.peace(reclaimWar.id(), KingdomSavedData.PEACE_REASON, 0);
                h.assertTrue(roads.connectedToCapital(data, f.kingdom()).contains(f.target().getId().uuid()),
                        "Reclaiming intermediate village B restores C's capital-network status");
                h.succeed();
            } finally {
                var mgr = VillageSavedData.get(level).getVillageManager();
                mgr.removeVillage(enemyCap.getId());
            }
        }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 400)
    public static void npcAutonomyBuildsOneClosestLinkAtATime(GameTestHelper h) {
        try (var f = RoyalShipmentSupport.realm(h, "road-npc")) {
            f.activate(true);
            ServerLevel level = f.level();
            KingdomSavedData data = KingdomSavedData.get(level);
            // Place generous gold/silver deniers in capital so NPC treasury covers reserve + stipend.
            var capChest = f.chest(f.capital());
            capChest.setItem(0, new ItemStack(org.millenaire.item.ModItems.DENIER_OR.get(), 4));
            capChest.setChanged();

            RoadSavedData roads = RoadSavedData.get(level);
            long npcDay = Math.max(roads.lastProcessedDay() + 1L, Math.max(10L, data.day() + 10L));
            RoadService.dailyStep(level, data, npcDay);
            RoadSegment active = roads.activeSegment(f.kingdom());
            h.assertTrue(active != null, "NPC kingdom must autonomously initiate a road project");

            // Stepping next day must keep at most 1 active project for this kingdom.
            RoadService.dailyStep(level, data, npcDay + 1L);
            long activeCount = roads.segmentsForKingdom(f.kingdom()).stream().filter(RoadSegment::isActive).count();
            h.assertTrue(activeCount == 1L, "NPC kingdom must never run > 1 active road project at a time");
            h.succeed();
        }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 400)
    public static void frontierWorkerSpawnsOnceInRangeAndCleansUpCleanly(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        RoadRuntime.cleanupAllOnServerBoundary(level.getServer());
        BlockPos p1 = h.absolutePos(new BlockPos(2, 2, 2));
        BlockPos p2 = h.absolutePos(new BlockPos(20, 2, 2));
        level.setBlockAndUpdate(p1.below(), Blocks.GRASS_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(p2.below(), Blocks.GRASS_BLOCK.defaultBlockState());

        RoadSegment seg = new RoadSegment(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "millenaire:norman", "millenaire:norman_hamlet",
                List.of(new Waypoint(p1.getX(), p1.getZ()), new Waypoint(p2.getX(), p2.getZ())),
                18, 4, Status.BUILDING, 2L, 0L, 0L);
        RoadSavedData.get(level).addSegment(seg);

        var player = new Stage5Support.TestPlayer(level, "RoadWatcher");
        // Far away (> 64 blocks): 0 workers.
        player.setPos(p1.getX() + 120.0, p1.getY(), p1.getZ() + 120.0);
        RoadRuntime.syncFrontierWorkers(level, List.of(player));
        h.assertTrue(RoadRuntime.activeWorkerCount(level) == 0, "No worker when player is out of 64-block range");

        // Move within range (10 blocks): exactly 1 MillVillager worker with capital culture, and idempotent on repeated syncs.
        player.setPos(p1.getX() + 8.0, p1.getY(), p1.getZ());
        RoadRuntime.syncFrontierWorkers(level, List.of(player));
        h.assertTrue(RoadRuntime.activeWorkerCount(level) == 1, "Expected 1 worker when player enters 64-block range");
        org.millenaire.entity.MillVillager foundWorker = null;
        for (var e : level.getAllEntities()) {
            if (RoadRuntime.isRoadWorker(e) && e instanceof org.millenaire.entity.MillVillager mv) {
                foundWorker = mv;
                break;
            }
        }
        h.assertTrue(foundWorker != null && foundWorker.getCultureId() != null
                        && foundWorker.getCultureId().equals(ResourceLocation.parse("millenaire:norman")),
                "Frontier worker must be a MillVillager matching the capital/kingdom culture");
        RoadRuntime.syncFrontierWorkers(level, List.of(player));
        h.assertTrue(RoadRuntime.activeWorkerCount(level) == 1, "Repeated sync must not spawn duplicate workers");

        // Pause project -> worker despawns cleanly.
        seg.setStatus(Status.PAUSED_MATERIALS);
        RoadRuntime.syncFrontierWorkers(level, List.of(player));
        h.assertTrue(RoadRuntime.activeWorkerCount(level) == 0, "Paused project must despawn frontier worker");

        // Reactivate -> respawns 1; chunk unload / boundary cleanup removes all.
        seg.setStatus(Status.BUILDING);
        RoadRuntime.syncFrontierWorkers(level, List.of(player));
        h.assertTrue(RoadRuntime.activeWorkerCount(level) == 1, "Unpaused project should respawn worker in range");
        RoadRuntime.onChunkUnload(level, new ChunkPos(p1.offset(4, 0, 0)));
        RoadRuntime.cleanupAllOnServerBoundary(level.getServer());
        h.assertTrue(RoadRuntime.activeWorkerCount(level) == 0, "Cleanup must remove all frontier workers");
        h.succeed();
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 400)
    public static void inactiveVillageRoadAndBuildingOrdersProcessAndAutoCompleteOnTimer(GameTestHelper h) {
        int prevRoadTicks = RoadConfig.INACTIVE_COMPLETION_TICKS.get();
        try (var f = RoyalShipmentSupport.realm(h, "inactive-orders")) {
            // Keep capital active for treasury payment, but set member villages INACTIVE.
            f.capital().setActive(true);
            f.source().setActive(false);
            f.target().setActive(false);

            RoadConfig.INACTIVE_COMPLETION_TICKS.set(20);

            var ruler = f.ruler();

            // 1. Order a road to an inactive village -> immediately accepted and BUILDING.
            RoadSegment road = RoadService.orderPlayerRoad(
                    ruler, f.capital().getId().uuid(), f.source().getId().uuid());
            h.assertTrue(road != null && road.status() == Status.BUILDING,
                    "Road order to an inactive village must be accepted immediately");

            // 2. Order a building commission for an inactive village -> immediately accepted, fee debited, and QUEUED in front of village.
            var catalog = com.millenairekingdoms.construction.RoyalConstructionCatalog.forPlayer(
                    ruler, f.source().getId().uuid());
            h.assertTrue(!catalog.options().isEmpty(), "Inactive village must expose construction options");
            var choice = catalog.options().getFirst();
            UUID buildOrderId = UUID.randomUUID();
            int buildingsBefore = f.source().getBuildings().size();
            long treasuryBefore = com.millenairekingdoms.economy.RoyalTreasurySnapshot.forPlayer(ruler).spendableValue();
            var buildService = new com.millenairekingdoms.construction.RoyalConstructionService(f.level());
            var buildEntry = buildService.requestQuotedPlayer(
                    ruler, f.source().getId().uuid(), choice.planSet(), buildOrderId, choice.patronageFee());
            long treasuryAfter = com.millenairekingdoms.economy.RoyalTreasurySnapshot.forPlayer(ruler).spendableValue();
            h.assertTrue(buildEntry.stage() == com.millenairekingdoms.construction.RoyalConstructionStore.Stage.QUEUED
                            && treasuryAfter == treasuryBefore - choice.patronageFee(),
                    "Building order for inactive village must immediately debit/reserve treasury fee and enter QUEUED stage, got "
                            + buildEntry.stage());
            h.assertTrue(f.source().getPendingProject() != null
                            && f.source().getPendingProject().planSetId().equals(choice.planSet()),
                    "Inactive village must immediately receive the pending project in front of its queue");

            // 3. Advance server game time past the road completion duration while village remains inactive:
            // Road order auto-completes on timer, while building order remains QUEUED for Millenaire's native builder.
            var levelData = (net.minecraft.world.level.storage.ServerLevelData) f.level().getLevelData();
            long now = levelData.getGameTime();
            try {
                levelData.setGameTime(now + 25L);
                RoadService.tickInactiveSegments(f.level());
                RoadSegment completedRoad = RoadSavedData.get(f.level()).segment(road.id());
                h.assertTrue(completedRoad.isCompleted()
                                && completedRoad.completedBlocks() == completedRoad.totalBlocks(),
                        "Inactive road order must automatically complete once its timer elapses");

                var observedBuild = buildService.observe(buildOrderId);
                h.assertTrue(observedBuild.stage()
                                == com.millenairekingdoms.construction.RoyalConstructionStore.Stage.QUEUED
                                && f.source().getBuildings().size() == buildingsBefore
                                && f.source().getPendingProject() != null
                                && f.source().getPendingProject().planSetId().equals(choice.planSet()),
                        "Inactive building order must stay QUEUED with pendingProject ready for Millenaire's native builder when activated");
            } finally {
                levelData.setGameTime(now);
            }
            h.succeed();
        } finally {
            RoadConfig.INACTIVE_COMPLETION_TICKS.set(prevRoadTicks);
        }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 400)
    public static void separateJvmRestartPersistencePreservesRoadsAnd123Saves(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        boolean readPhase = "read".equals(System.getProperty("kingdoms.tests.persistence"));
        KingdomSavedData data = KingdomSavedData.get(level);
        RoadSavedData roads = RoadSavedData.get(level);

        if (!readPhase) {
            // Write phase: create genuine KingdomSavedData + RoadSavedData entries and persist cleanly.
            BlockPos capPos = new BlockPos(900_000, -50, 900_000);
            BlockPos memPos = new BlockPos(900_040, -50, 900_000);
            Village pCap = RoyalShipmentSupport.village(level, "Persist Road Cap", capPos);
            Village pMem = RoyalShipmentSupport.village(level, "Persist Road Mem", memPos);
            var pk = data.create(
                    "Persist Road Realm " + UUID.randomUUID().toString().substring(0, 8),
                    pCap.getId().uuid());
            data.attach(pk.id(), pMem.getId().uuid());
            RoadSegment seg = new RoadSegment(PERSIST_ROAD_ID, pk.id(), pCap.getId().uuid(),
                    pMem.getId().uuid(), "millenaire:byzantines", pCap.getVillageTypeId().toString(),
                    List.of(new Waypoint(750_010, 750_020), new Waypoint(750_050, 750_020)),
                    40, 40, Status.COMPLETED, 6L, 3L, 1234L);
            seg.markChunkRealized(ChunkPos.asLong(0, 1), 25);
            seg.markStepsRealized(0, 15);
            roads.addSegment(seg);
            roads.setDirty();
            data.setDirty();
            h.assertTrue(KingdomSavedData.FORMAT == 7, "KingdomSavedData format must stay 7 for 1.2.3 compatibility");
            h.succeed();
            return;
        }

        // Read phase (fresh JVM): verify millenairekingdoms_roads loaded cleanly alongside KingdomSavedData.
        RoadSegment restored = roads.segment(PERSIST_ROAD_ID);
        h.assertTrue(restored != null, "Saved Royal Road must survive JVM restart");
        h.assertTrue(data.kingdom(restored.kingdomId()) != null
                        && restored.kingdomId().equals(data.owner(restored.villageA()))
                        && restored.kingdomId().equals(data.owner(restored.villageB())),
                "Associated 1.2.3 KingdomSavedData realm and members must survive restart alongside roads store");
        h.assertTrue(restored.totalBlocks() == 40 && restored.completedBlocks() == 40,
                "Saved progress mismatch after restart");
        h.assertTrue(restored.constructedStepIndex() == restored.steps().size() - 1
                        && restored.isStepRealized(0) && restored.isStepRealized(15) && !restored.isStepRealized(16),
                "Saved constructedStepIndex and realizedSteps BitSet must survive JVM restart");
        h.assertTrue(restored.status() == Status.COMPLETED && restored.stipendPaid() == 6L,
                "Saved status and stipend must survive JVM restart");
        h.assertTrue(restored.realizedProgress(ChunkPos.asLong(0, 1)) == 25,
                "Chunk realization progress must survive JVM restart");
        h.assertTrue(KingdomSavedData.FORMAT == 7 && data.day() >= -1L,
                "1.2.3 KingdomSavedData store must load cleanly alongside 1.2.4 road store");
        h.succeed();
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 400)
    public static void waterBridgeUsesOnlyPlanksBorderPostEndpointsAndViewExposesMaterials(GameTestHelper h) {
        try (var f = RoyalShipmentSupport.realm(h, "water-border-view")) {
            ServerLevel level = f.level();
            // 1. Place water span clear of village buildings and realize road across it; verify every bridge block is OAK_PLANKS.
            BlockPos wStart = f.capital().getCenter().offset(0, 0, 32);
            BlockPos wEnd = wStart.offset(10, 0, 0);
            for (int x = wStart.getX() - 1; x <= wEnd.getX() + 1; x++) {
                for (int z = wStart.getZ() - 2; z <= wStart.getZ() + 2; z++) {
                    BlockPos base = new BlockPos(x, wStart.getY(), z);
                    level.getChunkAt(base);
                    level.setBlockAndUpdate(base.below(), Blocks.STONE.defaultBlockState());
                    level.setBlockAndUpdate(base, Blocks.WATER.defaultBlockState());
                    level.setBlockAndUpdate(base.above(), Blocks.AIR.defaultBlockState());
                }
            }
            RoadSegment waterSeg = new RoadSegment(UUID.randomUUID(), f.kingdom(), f.capital().getId().uuid(),
                    f.source().getId().uuid(), "millenaire:norman", "millenaire:norman_hamlet",
                    List.of(new Waypoint(wStart.getX(), wStart.getZ()), new Waypoint(wEnd.getX(), wEnd.getZ())),
                    10, 10, Status.BUILDING, 2L, 0L, 0L);
            RoadPlacer.realizeLoadedAlong(level, waterSeg, 0, 10);
            for (int x = wStart.getX() + 1; x <= wEnd.getX() - 1; x++) {
                BlockPos deckPos = new BlockPos(x, wStart.getY() + 1, wStart.getZ());
                h.assertTrue(level.getBlockState(deckPos).is(Blocks.OAK_PLANKS),
                        "Over-water bridge block at " + deckPos + " must be OAK_PLANKS, got "
                                + level.getBlockState(deckPos));
            }

            // 2. Add border posts to capital and source and verify RoadService picks the border post endpoints.
            BlockPos bpA = f.capital().getCenter().offset(18, 0, 0);
            BlockPos bpB = f.source().getCenter().offset(-18, 0, 0);
            var postA = new org.millenaire.building.BuildingInstance(
                    org.millenaire.building.BuildingId.random(),
                    ResourceLocation.parse("millenaire:norman/borderpost_a_0"), bpA,
                    net.minecraft.world.level.block.Rotation.NONE,
                    org.millenaire.building.BuildingInstance.Status.COMPLETE,
                    ResourceLocation.parse("millenaire:norman/borderpost"), "a", 0);
            postA.addRuntimeTags(List.of("borderpostsign"));
            f.capital().addBuilding(postA);
            var postB = new org.millenaire.building.BuildingInstance(
                    org.millenaire.building.BuildingId.random(),
                    ResourceLocation.parse("millenaire:norman/borderpost_a_0"), bpB,
                    net.minecraft.world.level.block.Rotation.NONE,
                    org.millenaire.building.BuildingInstance.Status.COMPLETE,
                    ResourceLocation.parse("millenaire:norman/borderpost"), "a", 0);
            postB.addRuntimeTags(List.of("borderpostsign"));
            f.source().addBuilding(postB);

            var ruler = f.ruler();
            RoadSegment bpRoad = RoadService.orderPlayerRoad(
                    ruler, f.capital().getId().uuid(), f.source().getId().uuid());
            Waypoint firstWp = bpRoad.waypoints().getFirst();
            Waypoint lastWp = bpRoad.waypoints().getLast();
            h.assertTrue(Math.abs(firstWp.x() - bpA.getX()) <= 4 && Math.abs(firstWp.z() - bpA.getZ()) <= 4
                            && Math.abs(lastWp.x() - bpB.getX()) <= 4 && Math.abs(lastWp.z() - bpB.getZ()) <= 4,
                    "Road endpoints must snap flush right outside the villages' border posts");

            // 3. Verify RoyalRoadView exposes expected material fields for the ruler screen.
            var view = com.millenairekingdoms.gui.RoyalRoadView.build(ruler, f.kingdom());
            var activeTag = view.getCompound("active");
            h.assertTrue(activeTag.contains("neededMaterials") && activeTag.getInt("neededMaterials") > 0
                            && activeTag.contains("availableSurplus"),
                    "RoyalRoadView must expose expected neededMaterials and availableSurplus for the ruler screen");
            h.succeed();
        }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 400)
    public static void organicCurvatureRejectsOceanAndDetoursAroundRivers(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        var biomeRegistry = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME);
        var plains = biomeRegistry.getHolderOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS);
        var ocean = biomeRegistry.getHolderOrThrow(net.minecraft.world.level.biome.Biomes.OCEAN);
        var river = biomeRegistry.getHolderOrThrow(net.minecraft.world.level.biome.Biomes.RIVER);
        RoadConfig.Settings cfg = RoadConfig.current();

        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos end = new BlockPos(240, 64, 0);

        // 1. Flat open plains -> must produce genuinely curved non-linear path (not a straight line z=0).
        RoadPlanner.PlanResult curved = RoadPlanner.plan(start, end, cfg, (x, z) -> plains);
        h.assertTrue(curved.ok() && curved.waypoints().size() >= 6,
                "Overland curved plan must produce smoothed spline waypoints");
        int maxDevZ = curved.waypoints().stream().mapToInt(w -> Math.abs(w.z())).max().orElse(0);
        h.assertTrue(maxDevZ >= 3, "Curved road must deviate naturally from straight line (maxZ=" + maxDevZ + ")");

        // 2. Ocean barrier separating endpoints completely within search bounds -> strictly rejected.
        RoadPlanner.PlanResult blockedOcean = RoadPlanner.plan(
                start, end, cfg, (x, z) -> (x >= 100 && x <= 140) ? ocean : plains);
        h.assertTrue(!blockedOcean.ok() && "WIDE_OCEAN".equals(blockedOcean.failureReason()),
                "Continuous ocean separator must be strictly rejected");

        // 3. Finite river barrier (spanning z in [-48..48], dry land around z=±60) -> A* detours on dry land.
        RoadPlanner.BiomeQuery finiteRiver = (x, z) -> (x >= 100 && x <= 140 && Math.abs(z) <= 48) ? river : plains;
        RoadPlanner.PlanResult dryDetour = RoadPlanner.plan(start, end, cfg, finiteRiver);
        h.assertTrue(dryDetour.ok(), "Route around finite river must succeed on dry land");
        boolean steppedInRiver = false;
        for (Waypoint w : dryDetour.waypoints()) {
            if (finiteRiver.biomeAt(w.x(), w.z()).is(net.minecraft.tags.BiomeTags.IS_RIVER)) {
                steppedInRiver = true;
                break;
            }
        }
        h.assertTrue(!steppedInRiver,
                "Pathfinder must take a dry-land detour around finite river rather than bridging unnecessarily");
        h.succeed();
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 400)
    public static void pitDetourAndTreeClearingProtectVillagesDuringRealization(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos origin = h.absolutePos(new BlockPos(2, 2, 2));
        // Prepare flat 32x24 grass platform inside loaded test area.
        for (int dx = 0; dx < 32; dx++) {
            for (int dz = -12; dz <= 12; dz++) {
                BlockPos ground = origin.offset(dx, 0, dz);
                level.getChunkAt(ground);
                for (int dy = -9; dy < 0; dy++)
                    level.setBlockAndUpdate(ground.offset(0, dy, 0), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(ground, Blocks.GRASS_BLOCK.defaultBlockState());
                for (int dy = 1; dy <= 10; dy++)
                    level.setBlockAndUpdate(ground.above(dy), Blocks.AIR.defaultBlockState());
            }
        }

        // Carve a 4-block deep dry trench across centerline at dx = 11..13, dz = -3..3 to verify frozen (X, Z)
        // smooth Y-clamping (max ±1 delta per step) and automatic cut/fill grading.
        for (int dx = 11; dx <= 13; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -4; dy <= 0; dy++) {
                    level.setBlockAndUpdate(origin.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
                }
            }
        }

        // Place an oak tree directly on the road centerline at dx = 22, dz = 0.
        for (int dy = 1; dy <= 5; dy++)
            level.setBlockAndUpdate(origin.offset(22, dy, 0), Blocks.OAK_LOG.defaultBlockState());
        for (int tx = 20; tx <= 24; tx++) {
            for (int tz = -2; tz <= 2; tz++) {
                for (int ty = 3; ty <= 6; ty++) {
                    if (tx == 22 && tz == 0 && ty <= 5)
                        continue;
                    level.setBlockAndUpdate(origin.offset(tx, ty, tz), Blocks.OAK_LEAVES.defaultBlockState());
                }
            }
        }

        // Register a protected Millenaire BuildingInstance near dx = 22, dz = 7 with its own OAK_LOG pillar.
        Village village = RoyalShipmentSupport.village(level, "Tree Guard Ville", origin.offset(26, 0, 7));
        BlockPos guardedOrigin = origin.offset(22, 0, 7);
        BuildingInstance guardedBldg = new BuildingInstance(BuildingId.random(),
                ResourceLocation.parse("millenaire:norman/house_a_0"), guardedOrigin,
                net.minecraft.world.level.block.Rotation.NONE, BuildingInstance.Status.COMPLETE,
                ResourceLocation.parse("millenaire:norman/house"), "a", 0);
        village.addBuilding(guardedBldg);
        BlockPos guardedLog = origin.offset(22, 2, 7);
        level.setBlockAndUpdate(guardedLog, Blocks.OAK_LOG.defaultBlockState());

        try {
            RoadSegment seg = new RoadSegment(UUID.randomUUID(), UUID.randomUUID(), village.getId().uuid(),
                    UUID.randomUUID(), "millenaire:norman", "millenaire:norman_hamlet",
                    List.of(new Waypoint(origin.getX() + 2, origin.getZ()),
                            new Waypoint(origin.getX() + 28, origin.getZ())),
                    27, 27, Status.COMPLETED, 0L, 0L, 0L);

            RoadPlacer.realizeAvailableSteps(level, seg);
            var expectedStyle = RoadPlacer.styleFor(seg);

            // Verify frozen (X, Z) coordinates: every step stays at dz=0 and smooth Y-clamping keeps |dY| <= 1
            // with automatic backfill across the trench at dx=11..13.
            for (int i = 0; i < seg.steps().size(); i++) {
                h.assertTrue(seg.isStepRealized(i), "Step " + i + " must be marked realized");
                if (i > 0) {
                    int dy = Math.abs(seg.getRealizedY(i) - seg.getRealizedY(i - 1));
                    h.assertTrue(dy <= 1, "Smooth Y-clamping must enforce max ±1 block height delta, got " + dy);
                }
            }
            int midTrenchY = seg.getRealizedY(10); // dx = 2 + 10 = 12
            h.assertTrue(level.getBlockState(new BlockPos(origin.getX() + 12, midTrenchY, origin.getZ()))
                            .is(expectedStyle.fullBlock()),
                    "Frozen (X, Z) road block must be placed at clamped Y across trench");
            h.assertTrue(!level.getBlockState(new BlockPos(origin.getX() + 12, midTrenchY - 1, origin.getZ())).isAir(),
                    "Automatic cut/fill grading must backfill foundation beneath clamped road block");

            // Verify tree at dx=22, dz=0 was cleared down to ground level with 5-block vertical headroom.
            h.assertTrue(level.getBlockState(origin.offset(22, 0, 0)).is(expectedStyle.fullBlock()),
                    "Road block must be placed on true ground beneath cleared tree");
            for (int dy = 1; dy <= 5; dy++) {
                h.assertTrue(level.getBlockState(origin.offset(22, dy, 0)).isAir(),
                        "Road headroom dy=" + dy + " above cleared tree must be air");
            }
            // Verify protected Millenaire building log was untouched.
            h.assertTrue(level.getBlockState(guardedLog).is(Blocks.OAK_LOG),
                    "Millenaire building logs must be strictly protected from tree clearing");
            h.succeed();
        } finally {
            VillageSavedData.get(level).getVillageManager().removeVillage(village.getId());
        }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 400)
    public static void plannerFollowsValleysAroundMountainsAndAvoidsAlpineBiomes(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        var biomeRegistry = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME);
        var plains = biomeRegistry.getHolderOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS);
        var peaks = biomeRegistry.getHolderOrThrow(net.minecraft.world.level.biome.Biomes.JAGGED_PEAKS);
        var windswept = biomeRegistry.getHolderOrThrow(net.minecraft.world.level.biome.Biomes.WINDSWEPT_HILLS);
        RoadConfig.Settings cfg = RoadConfig.current();

        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos end = new BlockPos(240, 64, 0);

        // 1. Steep central ridge rising to Y=94 across |z|<=48 with flat valley floor at |z|>=60 -> follows valley.
        RoadPlanner.HeightQuery centralMountain = (x, z) -> {
            if (x >= 80 && x <= 140 && Math.abs(z) <= 52) {
                int dzEdge = Math.max(0, 52 - Math.abs(z));
                int dxEdge = Math.min(x - 80, 140 - x);
                return 64 + Math.min(30, Math.min(dzEdge, dxEdge) * 2);
            }
            return 64;
        };
        RoadPlanner.PlanResult valleyRoute = RoadPlanner.plan(start, end, cfg, (x, z) -> plains, centralMountain);
        h.assertTrue(valleyRoute.ok(), "Valley-following route around mountain ridge must succeed");
        int maxSampledHeight = valleyRoute.waypoints().stream()
                .mapToInt(w -> centralMountain.heightAt(w.x(), w.z()))
                .max()
                .orElse(64);
        h.assertTrue(maxSampledHeight <= 70,
                "Route must hug valley foothills rather than climbing steep ridge (maxY=" + maxSampledHeight + ")");

        // 2. Alpine / windswept mountain biome barrier across |z|<=48 on flat Y=64 -> detours through plains.
        RoadPlanner.BiomeQuery alpineBarrier = (x, z) -> (x >= 90 && x <= 140 && Math.abs(z) <= 44)
                ? (x < 115 ? peaks : windswept)
                : plains;
        RoadPlanner.PlanResult alpineDetour = RoadPlanner.plan(start, end, cfg, alpineBarrier, (x, z) -> 64);
        h.assertTrue(alpineDetour.ok(), "Route around alpine biome barrier must succeed");
        boolean enteredAlpine = alpineDetour.waypoints().stream()
                .anyMatch(w -> RoadPlanner.isMountainOrAlpineBiome(alpineBarrier.biomeAt(w.x(), w.z())));
        h.assertTrue(!enteredAlpine,
                "Pathfinder must detour around mountain/windswept biomes when a plains corridor exists");
        h.succeed();
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 400)
    public static void gradedSlabsCutAndFillCausewaysAndArchedBridges(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos origin = h.absolutePos(new BlockPos(2, 6, 2));

        // 1. Prepare test strip (extending ±2 blocks past dx=1..60 so chunk-neighborhood checks at both ends
        // never depend on batch X alignment): stepped hill + hollow overhang, deep river, and 28-block river.
        for (int dx = -2; dx <= 63; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                BlockPos base = origin.offset(dx, 0, dz);
                level.getChunkAt(base);
                for (int dy = -6; dy < 0; dy++)
                    level.setBlockAndUpdate(base.offset(0, dy, 0), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(base, Blocks.GRASS_BLOCK.defaultBlockState());
                for (int dy = 1; dy <= 10; dy++)
                    level.setBlockAndUpdate(base.above(dy), Blocks.AIR.defaultBlockState());
            }
        }

        // Raised terrace step at dx=6..10 (1 block higher) with hollow cavity directly underneath dx=7 (dz=0)
        // and 1-block dirt overhang at dz=+2 (dx=8).
        for (int dx = 6; dx <= 10; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                level.setBlockAndUpdate(origin.offset(dx, 1, dz), Blocks.GRASS_BLOCK.defaultBlockState());
            }
        }
        level.setBlockAndUpdate(origin.offset(7, 0, 0), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(origin.offset(8, 2, 2), Blocks.DIRT.defaultBlockState());

        // Deep river at dx=16..25 (10 wide, 4 deep: water at dy=-3..0, riverbed STONE at dy=-4).
        for (int dx = 16; dx <= 25; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = -3; dy <= 0; dy++) {
                    level.setBlockAndUpdate(origin.offset(dx, dy, dz), Blocks.WATER.defaultBlockState());
                }
            }
        }

        // Wide 28-block river at dx=31..58 (previously skipped by 24-block cap, now bridged with multi-arch spans).
        for (int dx = 31; dx <= 58; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                level.setBlockAndUpdate(origin.offset(dx, 0, dz), Blocks.WATER.defaultBlockState());
            }
        }

        RoadSegment seg = new RoadSegment(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "millenaire:norman", "millenaire:norman_hamlet",
                List.of(new Waypoint(origin.getX() + 1, origin.getZ()),
                        new Waypoint(origin.getX() + 60, origin.getZ())),
                59, 59, Status.COMPLETED, 0L, 0L, 0L);

        RoadPlacer.realizeLoadedAlong(level, seg, 0, 59);
        var style = RoadPlacer.styleFor(seg);

        // Verify graded slab at 1-block step transition (dx=5->6 step-up slab or dx=10->11 step-down slab).
        boolean foundSlab = level.getBlockState(origin.offset(5, 1, 0)).is(style.slabBlock())
                || level.getBlockState(origin.offset(11, 1, 0)).is(style.slabBlock());
        h.assertTrue(foundSlab, "1-block elevation transition must place a cultural slab for smooth grading");

        // Verify backfill under dx=7 (was hollow AIR, must now be filled with DIRT/COBBLESTONE).
        var fillBelow = level.getBlockState(origin.offset(7, 0, 0));
        h.assertTrue(!fillBelow.isAir() && (fillBelow.is(Blocks.DIRT) || fillBelow.is(Blocks.COBBLESTONE)),
                "Floating road block void must be backfilled cleanly, got " + fillBelow);

        // Verify uphill dirt overhang at dx=8, dz=2 was shaved cleanly down.
        h.assertTrue(level.getBlockState(origin.offset(8, 2, 2)).isAir(),
                "Overhanging uphill shoulder dirt must be shaved down cleanly");

        // Verify Arched Bridge over deep 10-wide river (dx=16..25): mid-span arches above dy=1 leaving boat clearance!
        boolean midSpanArchedHigh = false;
        for (int dy = 2; dy <= 4; dy++) {
            var st = level.getBlockState(origin.offset(20, dy, 0));
            if (st.is(style.bridgeDeck()) || st.is(style.bridgeSlab())) {
                midSpanArchedHigh = true;
                break;
            }
        }
        h.assertTrue(midSpanArchedHigh, "Deep river bridge must arch above water level for boat clearance");
        h.assertTrue(level.getBlockState(origin.offset(20, 1, 0)).isAir(),
                "Navigable channel directly above mid-span water must remain clear air for boats");

        // Verify bridgehead lantern post at entrance edge (dx=16, dz=±2).
        boolean hasLantern = false;
        for (int dy = 2; dy <= 5; dy++) {
            if (level.getBlockState(origin.offset(16, dy, 2)).is(Blocks.LANTERN)
                    || level.getBlockState(origin.offset(16, dy, -2)).is(Blocks.LANTERN)) {
                hasLantern = true;
                break;
            }
        }
        h.assertTrue(hasLantern, "Arched river bridge entrance must have a bridgehead lantern post");

        // Verify 28-block wide river (dx=31..58) IS bridged continuously with multi-arch spans (including mid-span dx=45).
        for (int dx = 31; dx <= 58; dx++) {
            boolean hasBridgeDeck = false;
            for (int dy = 1; dy <= 4; dy++) {
                var st = level.getBlockState(origin.offset(dx, dy, 0));
                if (st.is(style.bridgeDeck()) || st.is(style.bridgeSlab())) {
                    hasBridgeDeck = true;
                    break;
                }
            }
            h.assertTrue(hasBridgeDeck, "28-block wide river must be continuously bridged at dx=" + dx);
        }

        // Verify RoadSavedData self-heals legacy realized steps whose realizedY stayed Integer.MIN_VALUE.
        RoadSavedData healData = new RoadSavedData();
        RoadSegment broken = new RoadSegment(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "millenaire:norman", "millenaire:norman_hamlet",
                List.of(new Waypoint(origin.getX() + 1, origin.getZ()),
                        new Waypoint(origin.getX() + 60, origin.getZ())),
                59, 59, Status.COMPLETED, 0L, 0L, 0L);
        broken.markStepsRealized(0, 58);
        for (int b = 0; b <= 58; b++) {
            if (b < 30 || b > 40)
                broken.setRealizedY(b, origin.getY());
        }
        healData.addSegment(broken);
        var savedTag = healData.save(new net.minecraft.nbt.CompoundTag(), level.registryAccess());
        RoadSavedData reloaded = RoadSavedData.load(savedTag, level.registryAccess());
        RoadSegment healedSeg = reloaded.segment(broken.id());
        h.assertTrue(healedSeg != null && healedSeg.isStepRealized(10) && !healedSeg.isStepRealized(35)
                        && !healedSeg.isStepRealized(28) && !healedSeg.isStepRealized(42),
                "Reloading legacy unbridged steps with Integer.MIN_VALUE height must clear them and 2-step abutments for re-realization");
        h.succeed();
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 400)
    public static void snowCultureRoadAdaptsToWarmBiomeAndTargetsBorderGateOverCornerTower(GameTestHelper h) {
        try (var f = RoyalShipmentSupport.realm(h, "snow-gate-test")) {
            ServerLevel level = f.level();
            BlockPos origin = h.absolutePos(new BlockPos(2, 2, 2));

            // 1. Test biome-adaptive fallback for snow-culture road in temperate/plains test chunk.
            String snowVillageTypeId = org.millenaire.culture.ModCultures.getAllVillageTypes().values().stream()
                    .filter(vt -> vt.pathMaterials() != null && vt.pathMaterials().contains("pathsnow"))
                    .map(vt -> vt.id().toString())
                    .findFirst()
                    .orElse("millenaire:inuits_huntingvillage");
            for (int dx = 0; dx <= 16; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos p = origin.offset(dx, 0, dz);
                    level.getChunkAt(p);
                    level.setBlockAndUpdate(p.below(), Blocks.STONE.defaultBlockState());
                    level.setBlockAndUpdate(p, Blocks.GRASS_BLOCK.defaultBlockState());
                    for (int dy = 1; dy <= 5; dy++)
                        level.setBlockAndUpdate(p.above(dy), Blocks.AIR.defaultBlockState());
                }
            }
            RoadSegment snowSeg = new RoadSegment(UUID.randomUUID(), f.kingdom(), f.capital().getId().uuid(),
                    f.source().getId().uuid(), "millenaire:inuits", snowVillageTypeId,
                    List.of(new Waypoint(origin.getX() + 2, origin.getZ()),
                            new Waypoint(origin.getX() + 14, origin.getZ())),
                    12, 12, Status.COMPLETED, 0L, 0L, 0L);
            RoadPlacer.realizeLoadedAlong(level, snowSeg, 0, 12);

            var snowTestStyle = new RoadPlacer.RoadStyle(
                    org.millenaire.block.ModBlocks.PATH_SNOW.get(),
                    org.millenaire.block.ModBlocks.PATH_SNOW_SLAB.get(),
                    Blocks.OAK_PLANKS, (net.minecraft.world.level.block.SlabBlock) Blocks.OAK_SLAB);
            var adaptedWarmStyle = RoadPlacer.styleForPos(level, origin.offset(8, 0, 0), snowTestStyle);
            h.assertTrue(adaptedWarmStyle.fullBlock() == org.millenaire.block.ModBlocks.PATH_GRAVEL.get()
                            || adaptedWarmStyle.fullBlock() == org.millenaire.block.ModBlocks.PATH_DIRT.get(),
                    "Warm/temperate biome must override snow path palette to non-snow Millenaire paths");
            for (int dx = 2; dx <= 14; dx++) {
                h.assertTrue(!level.getBlockState(origin.offset(dx, 0, 0))
                                .is(org.millenaire.block.ModBlocks.PATH_SNOW.get()),
                        "Snow path blocks must never be placed in warm/temperate biomes at dx=" + dx);
            }

            // 2. Place closer corner watchtowers AND actual twin-pillar borderpost_gate structures -> must target gate!
            BlockPos cornerA = f.capital().getCenter().offset(18, 0, 10);
            BlockPos gateA = f.capital().getCenter().offset(14, 0, 0);
            BlockPos cornerB = f.source().getCenter().offset(-18, 0, 10);
            BlockPos gateB = f.source().getCenter().offset(-14, 0, 0);

            f.capital().addBuilding(new BuildingInstance(BuildingId.random(),
                    ResourceLocation.parse("millenaire:byzantines/borderpost_corner_a_0"), cornerA,
                    net.minecraft.world.level.block.Rotation.NONE, BuildingInstance.Status.COMPLETE,
                    ResourceLocation.parse("millenaire:byzantines/borderpost_corner"), "a", 0));
            f.capital().addBuilding(new BuildingInstance(BuildingId.random(),
                    ResourceLocation.parse("millenaire:byzantines/borderpost_gate_a_0"), gateA,
                    net.minecraft.world.level.block.Rotation.CLOCKWISE_90, BuildingInstance.Status.COMPLETE,
                    ResourceLocation.parse("millenaire:byzantines/borderpost_gate"), "a", 0));

            f.source().addBuilding(new BuildingInstance(BuildingId.random(),
                    ResourceLocation.parse("millenaire:byzantines/borderpost_corner_a_0"), cornerB,
                    net.minecraft.world.level.block.Rotation.NONE, BuildingInstance.Status.COMPLETE,
                    ResourceLocation.parse("millenaire:byzantines/borderpost_corner"), "a", 0));
            f.source().addBuilding(new BuildingInstance(BuildingId.random(),
                    ResourceLocation.parse("millenaire:byzantines/borderpost_gate_a_0"), gateB,
                    net.minecraft.world.level.block.Rotation.CLOCKWISE_90, BuildingInstance.Status.COMPLETE,
                    ResourceLocation.parse("millenaire:byzantines/borderpost_gate"), "a", 0));

            RoadSegment gateRoad = RoadService.orderPlayerRoad(
                    f.ruler(), f.capital().getId().uuid(), f.source().getId().uuid());
            Waypoint startWp = gateRoad.waypoints().getFirst();
            Waypoint endWp = gateRoad.waypoints().getLast();
            double distStartToGate = Math.hypot(startWp.x() - gateA.getX(), startWp.z() - gateA.getZ());
            double distStartToCorner = Math.hypot(startWp.x() - cornerA.getX(), startWp.z() - cornerA.getZ());
            h.assertTrue(distStartToGate < distStartToCorner && Math.abs(startWp.z() - gateA.getZ()) <= 5,
                    "Road must target borderpost_gate instead of closer borderpost_corner (start="
                            + startWp + ", gateA=" + gateA + ")");
            double distEndToGate = Math.hypot(endWp.x() - gateB.getX(), endWp.z() - gateB.getZ());
            double distEndToCorner = Math.hypot(endWp.x() - cornerB.getX(), endWp.z() - cornerB.getZ());
            h.assertTrue(distEndToGate < distEndToCorner && Math.abs(endWp.z() - gateB.getZ()) <= 5,
                    "Road end must target borderpost_gate instead of borderpost_corner");

            // Also verify 100% polyline realization reaches the final gate threshold waypoint without stopping short.
            var gatePix = RoadPlanner.rasterize8Connected(gateRoad.waypoints());
            h.assertTrue(gatePix.getLast().x() == endWp.x() && gatePix.getLast().z() == endWp.z(),
                    "Rasterized road must reach 100% of final Border Gate threshold coordinate");
            h.succeed();
        }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 400)
    public static void diagonalBridgeHasZeroHolesClearsTreesAndRejectsSteepCliffs(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos origin = h.absolutePos(new BlockPos(4, 5, 4));

        // 1. Prepare 24x24 diagonal arena with 10-block deep water channel diagonally crossed from (2,2) to (20,20)
        // plus an oak tree trunk + leaves + vines standing right across the diagonal bridge line at (11, 11).
        for (int dx = 0; dx <= 24; dx++) {
            for (int dz = 0; dz <= 24; dz++) {
                BlockPos ground = origin.offset(dx, 0, dz);
                level.getChunkAt(ground);
                for (int dy = -6; dy < 0; dy++)
                    level.setBlockAndUpdate(ground.offset(0, dy, 0), Blocks.STONE.defaultBlockState());
                boolean waterZone = (dx + dz >= 14 && dx + dz <= 28);
                if (waterZone) {
                    for (int dy = -3; dy <= 0; dy++)
                        level.setBlockAndUpdate(ground.offset(0, dy, 0), Blocks.WATER.defaultBlockState());
                } else {
                    level.setBlockAndUpdate(ground, Blocks.GRASS_BLOCK.defaultBlockState());
                }
                for (int dy = 1; dy <= 10; dy++)
                    level.setBlockAndUpdate(ground.above(dy), Blocks.AIR.defaultBlockState());
            }
        }
        // Place obstructing oak tree + leaves + vines over the water channel on the diagonal bridge line at (11, 11).
        for (int dy = 1; dy <= 6; dy++)
            level.setBlockAndUpdate(origin.offset(11, dy, 11), Blocks.OAK_LOG.defaultBlockState());
        for (int lx = 9; lx <= 13; lx++) {
            for (int lz = 9; lz <= 13; lz++) {
                for (int ly = 4; ly <= 7; ly++) {
                    if (lx == 11 && lz == 11 && ly <= 6)
                        continue;
                    level.setBlockAndUpdate(origin.offset(lx, ly, lz), Blocks.OAK_LEAVES.defaultBlockState());
                }
            }
        }
        level.setBlockAndUpdate(origin.offset(10, 4, 11), Blocks.VINE.defaultBlockState());

        RoadSegment diagSeg = new RoadSegment(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "millenaire:norman", "millenaire:norman_hamlet",
                List.of(new Waypoint(origin.getX() + 2, origin.getZ() + 2),
                        new Waypoint(origin.getX() + 21, origin.getZ() + 21)),
                32, 32, Status.COMPLETED, 0L, 0L, 0L);

        RoadPlacer.realizeLoadedAlong(level, diagSeg, 0, 32);
        var style = RoadPlacer.styleFor(diagSeg);

        // Verify ZERO diagonal checkerboard water holes anywhere inside the 3x3 brush along the diagonal bridge!
        for (int step = 8; step <= 13; step++) {
            for (int bdz = -1; bdz <= 1; bdz++) {
                for (int bdx = -1; bdx <= 1; bdx++) {
                    boolean hasDeckBlock = false;
                    for (int dy = 1; dy <= 5; dy++) {
                        var st = level.getBlockState(origin.offset(step + bdx, dy, step + bdz));
                        if (st.is(style.bridgeDeck()) || st.is(style.bridgeSlab())) {
                            hasDeckBlock = true;
                            // Also verify 5-block tree/vine headroom above deck block is completely clear!
                            for (int hd = 1; hd <= 4; hd++) {
                                var hst = level.getBlockState(origin.offset(step + bdx, dy + hd, step + bdz));
                                h.assertTrue(hst.isAir(),
                                        "Bridge headroom must have zero logs/leaves/vines at ("
                                                + (step + bdx) + "," + (dy + hd) + "," + (step + bdz) + "), got " + hst);
                            }
                            break;
                        }
                    }
                    h.assertTrue(hasDeckBlock,
                            "Diagonal bridge 3x3 brush must have zero checkerboard water holes at offset ("
                                    + (step + bdx) + "," + (step + bdz) + ")");
                }
            }
        }

        // Verify strong steep-slope penalty (> 2 blocks on 8-block steps) avoids sheer 6-block cliff barrier.
        var biomeRegistry = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME);
        var plains = biomeRegistry.getHolderOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS);
        RoadPlanner.HeightQuery cliffBarrier = (x, z) -> (x >= 88 && x <= 128 && Math.abs(z) <= 44) ? 74 : 64;
        RoadPlanner.PlanResult valley = RoadPlanner.plan(new BlockPos(0, 64, 0), new BlockPos(216, 64, 0),
                RoadConfig.current(), (x, z) -> plains, cliffBarrier);
        h.assertTrue(valley.ok(), "Steep cliff avoidance plan must succeed along lower shoreline/valley");
        int peakY = valley.waypoints().stream().mapToInt(w -> cliffBarrier.heightAt(w.x(), w.z())).max().orElse(64);
        h.assertTrue(peakY <= 66, "Strong slope penalty must route around sudden |dY|>2 cliff (peakY=" + peakY + ")");
        h.succeed();
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 400)
    public static void unifiedStepsBitSetAtomicKelpBridgeAndWalkingWorkerSync(GameTestHelper h) {
        try (var f = RoyalShipmentSupport.realm(h, "unified-road-arch")) {
            ServerLevel level = f.level();
            RoadRuntime.cleanupAllOnServerBoundary(level.getServer());

            // 1. Place Border Gates on origin (capital) and destination (source) villages and verify:
            //    - Continuous 8-connected steps (max(|dx|, |dz|) == 1) with totalBlocks == steps.size().
            //    - Both endpoints physically connect flush (<= 2 blocks) to the Border Gates with a 6-block straight approach.
            BlockPos gateA = f.capital().getCenter().offset(12, 0, 0);
            BlockPos gateB = f.source().getCenter().offset(-12, 0, 0);
            var gateBuildingA = new BuildingInstance(BuildingId.random(),
                    ResourceLocation.parse("millenaire:norman/borderpost_gate_a_0"), gateA,
                    net.minecraft.world.level.block.Rotation.CLOCKWISE_90, BuildingInstance.Status.COMPLETE,
                    ResourceLocation.parse("millenaire:norman/borderpost_gate"), "a", 0);
            var gateBuildingB = new BuildingInstance(BuildingId.random(),
                    ResourceLocation.parse("millenaire:norman/borderpost_gate_a_0"), gateB,
                    net.minecraft.world.level.block.Rotation.CLOCKWISE_90, BuildingInstance.Status.COMPLETE,
                    ResourceLocation.parse("millenaire:norman/borderpost_gate"), "a", 0);
            f.capital().addBuilding(gateBuildingA);
            f.source().addBuilding(gateBuildingB);

            RoadSegment ordered = RoadService.orderPlayerRoad(
                    f.ruler(), f.capital().getId().uuid(), f.source().getId().uuid());
            List<Waypoint> steps = ordered.steps();
            h.assertTrue(steps.size() >= 20 && ordered.totalBlocks() == steps.size(),
                    "totalBlocks (" + ordered.totalBlocks() + ") must equal steps.size() (" + steps.size() + ")");
            for (int i = 0; i < steps.size() - 1; i++) {
                int dx = Math.abs(steps.get(i + 1).x() - steps.get(i).x());
                int dz = Math.abs(steps.get(i + 1).z() - steps.get(i).z());
                h.assertTrue(Math.max(dx, dz) == 1,
                        "Every consecutive step must be 8-connected (max(|dx|,|dz|)==1) at i=" + i);
            }
            Waypoint firstStep = steps.getFirst();
            Waypoint lastStep = steps.getLast();
            // The gate opening is centered on the gate box, not on the building origin: measure the lateral
            // offset against the box center axis (roads used to end 1-3 blocks beside a pillar).
            int[] boxA = com.millenairekingdoms.roads.VillageFootprint.buildingBox(gateBuildingA);
            int[] boxB = com.millenairekingdoms.roads.VillageFootprint.buildingBox(gateBuildingB);
            double startGateDist = Math.hypot(firstStep.x() - gateA.getX(), firstStep.z() - (boxA[1] + boxA[3]) / 2);
            double endGateDist = Math.hypot(lastStep.x() - gateB.getX(), lastStep.z() - (boxB[1] + boxB[3]) / 2);
            h.assertTrue(startGateDist <= 2.0 && endGateDist <= 2.0,
                    "Both endpoints must connect flush (<= 2 blocks) to Border Gates (got "
                            + startGateDist + ", " + endGateDist + ")");
            // Verify straight 6-block orthogonal approach at both ends.
            for (int k = 0; k <= 6; k++) {
                h.assertTrue(steps.get(k).z() == firstStep.z() && steps.get(k).x() == firstStep.x() + k,
                        "Origin gate approach must be a straight 6-block orthogonal segment at k=" + k);
                int tailIdx = steps.size() - 1 - k;
                h.assertTrue(steps.get(tailIdx).z() == lastStep.z() && steps.get(tailIdx).x() == lastStep.x() - k,
                        "Destination gate approach must be a straight 6-block orthogonal segment at k=" + k);
            }

            // 2. River crossing containing seagrass, tall_seagrass, kelp, kelp_plant, and lily_pad straddling a chunk border!
            BlockPos baseOrigin = h.absolutePos(new BlockPos(2, 5, 2));
            int borderX = ((baseOrigin.getX() + 16) >> 4) << 4; // Exact chunk boundary X (multiple of 16)
            int roadZ = baseOrigin.getZ() + 4;
            int baseY = baseOrigin.getY();

            for (int x = borderX - 14; x <= borderX + 14; x++) {
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos pos = new BlockPos(x, baseY, roadZ + dz);
                    level.getChunkAt(pos);
                    for (int dy = -6; dy < 0; dy++)
                        level.setBlockAndUpdate(pos.offset(0, dy, 0), Blocks.STONE.defaultBlockState());
                    boolean inRiver = (x >= borderX - 4 && x <= borderX + 4);
                    if (inRiver) {
                        for (int dy = -3; dy <= 0; dy++)
                            level.setBlockAndUpdate(pos.offset(0, dy, 0), Blocks.WATER.defaultBlockState());
                    } else {
                        level.setBlockAndUpdate(pos, Blocks.GRASS_BLOCK.defaultBlockState());
                    }
                    for (int dy = 1; dy <= 8; dy++)
                        level.setBlockAndUpdate(pos.above(dy), Blocks.AIR.defaultBlockState());
                }
            }
            // Populate river across the chunk border with seagrass, tall_seagrass, kelp, kelp_plant, and lily_pad!
            level.setBlockAndUpdate(new BlockPos(borderX - 3, baseY, roadZ), Blocks.SEAGRASS.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(borderX - 1, baseY - 1, roadZ), Blocks.KELP_PLANT.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(borderX - 1, baseY, roadZ), Blocks.KELP.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(borderX, baseY + 1, roadZ), Blocks.LILY_PAD.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(borderX + 1, baseY, roadZ), Blocks.TALL_SEAGRASS.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(borderX + 2, baseY, roadZ), Blocks.KELP.defaultBlockState());

            RoadSegment bridgeSeg = new RoadSegment(UUID.randomUUID(), f.kingdom(), f.capital().getId().uuid(),
                    f.source().getId().uuid(), "millenaire:norman", "millenaire:norman_hamlet",
                    List.of(new Waypoint(borderX - 10, roadZ), new Waypoint(borderX + 10, roadZ)),
                    21, 21, Status.COMPLETED, 0L, 0L, 0L);
            RoadSavedData.get(level).addSegment(bridgeSeg);
            // Trigger via ChunkEvent.Load queue mechanism and verify 100% realizedSteps and gap-free arched bridge!
            RoadRuntime.queueChunkForRealization(level, new ChunkPos(new BlockPos(borderX, baseY, roadZ)));
            RoadRuntime.drainPendingChunks(level);

            h.assertTrue(bridgeSeg.realizedSteps().cardinality() == bridgeSeg.steps().size(),
                    "100% of steps across chunk boundary must be realized in BitSet ("
                            + bridgeSeg.realizedSteps().cardinality() + "/" + bridgeSeg.steps().size() + ")");
            var style = RoadPlacer.styleFor(bridgeSeg);
            for (int x = borderX - 4; x <= borderX + 4; x++) {
                for (int dz = -1; dz <= 1; dz++) {
                    boolean hasBridgeBlock = false;
                    for (int dy = 1; dy <= 4; dy++) {
                        var st = level.getBlockState(new BlockPos(x, baseY + dy, roadZ + dz));
                        if (st.is(style.bridgeDeck()) || st.is(style.bridgeSlab())) {
                            hasBridgeBlock = true;
                            break;
                        }
                    }
                    h.assertTrue(hasBridgeBlock,
                            "Atomic bridge across chunk border with kelp/seagrass must have zero gaps at x="
                                    + x + " dz=" + dz);
                }
            }

            // 3. Verify walking worker step-by-step sync & background progress suspension within 64 blocks!
            ordered.setConstructedStepIndex(0);
            ordered.setCompletedBlocks(0);
            for (int k = 0; k < 12 && k < ordered.steps().size(); k++) {
                Waypoint kw = ordered.steps().get(k);
                for (int dz = -2; dz <= 2; dz++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        BlockPos gp = new BlockPos(kw.x() + dx, gateA.getY(), kw.z() + dz);
                        level.getChunkAt(gp);
                        level.setBlockAndUpdate(gp.below(), Blocks.STONE.defaultBlockState());
                        level.setBlockAndUpdate(gp, Blocks.GRASS_BLOCK.defaultBlockState());
                        level.setBlockAndUpdate(gp.above(), Blocks.AIR.defaultBlockState());
                        level.setBlockAndUpdate(gp.above(2), Blocks.AIR.defaultBlockState());
                    }
                }
            }

            var watcher = new Stage5Support.TestPlayer(level, "FrontierWatcher");
            watcher.setPos(firstStep.x() + 2.0, gateA.getY() + 1.0, firstStep.z() + 2.0);
            // Spawn mk_road_worker at step 0 (constructedStepIndex stays 0 on initial spawn).
            RoadRuntime.syncFrontierWorkers(level, List.of(watcher));
            h.assertTrue(RoadRuntime.activeWorkerCount(level) == 1 && ordered.constructedStepIndex() == 0,
                    "Worker must spawn at step 0 without jumping constructedStepIndex ahead");

            // While watcher + worker are active within 64 blocks, dailyStep must NOT jump 20-45 blocks ahead!
            var chest = f.chest(f.source());
            chest.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
            chest.setChanged();
            KingdomSavedData kd = KingdomSavedData.get(level);
            RoadSavedData rd = RoadSavedData.get(level);
            RoadService.dailyStep(level, kd, Math.max(rd.lastProcessedDay() + 1L, kd.day() + 50L));
            h.assertTrue(ordered.constructedStepIndex() == 0,
                    "Passive background daily progress must be suspended while player is within 64m and worker is active");

            // Next sync with worker within 3.5 blocks of steps[0] advances constructedStepIndex by +1 (to 1)!
            RoadRuntime.syncFrontierWorkers(level, List.of(watcher));
            h.assertTrue(ordered.constructedStepIndex() == 1 && ordered.isStepRealized(1),
                    "Worker within 3.5 blocks of frontier must advance constructedStepIndex by +1 and realize step 1");

            // Move worker 15 blocks away (> 3.5 blocks) from steps[1]: sync must NOT increment constructedStepIndex!
            org.millenaire.entity.MillVillager workerEntity = null;
            for (var e : level.getAllEntities()) {
                if (RoadRuntime.isRoadWorker(e) && e instanceof org.millenaire.entity.MillVillager mv) {
                    workerEntity = mv;
                    break;
                }
            }
            h.assertTrue(workerEntity != null, "Active MillVillager road worker must exist");
            workerEntity.setPos(firstStep.x() - 15.0, gateA.getY() + 1.0, firstStep.z());
            RoadRuntime.syncFrontierWorkers(level, List.of(watcher));
            h.assertTrue(ordered.constructedStepIndex() == 1,
                    "constructedStepIndex must NOT advance when worker is > 3.5 blocks away from frontier");

            RoadRuntime.cleanupAllOnServerBoundary(level.getServer());
            h.succeed();
        }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 400)
    public static void existingBridgeReuseAndParapetOverlapProtection(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        var biomeRegistry = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME);
        var plains = biomeRegistry.getHolderOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS);
        RoadConfig.Settings cfg = RoadConfig.current();

        // 1. Planner single-bridge reuse across an impassably long river barrier (x in [80..112]).
        RoadPlanner.HeightQuery riverBarrier = new RoadPlanner.HeightQuery() {
            @Override
            public int heightAt(int x, int z) {
                return 64;
            }

            @Override
            public int waterDepthAt(int x, int z) {
                return (x >= 80 && x <= 112) ? 4 : 0;
            }
        };
        RoadPlanner.PlanResult plan1 = RoadPlanner.plan(new BlockPos(0, 64, 0), new BlockPos(192, 64, 0),
                cfg, (x, z) -> plains, riverBarrier);
        h.assertTrue(plan1.ok(), "First road plan across river must succeed");
        int plan1DevZ = plan1.waypoints().stream().mapToInt(w -> Math.abs(w.z())).max().orElse(0);
        h.assertTrue(plan1DevZ >= 3,
                "Overland road crossing a river barrier must retain smooth organic curvature (maxDevZ=" + plan1DevZ + ")");

        RoadPlanner.FootprintQuery nearbyVillage = (x, z) -> x >= 32 && x <= 56 && z >= 16 && z <= 40;
        RoadPlanner.PlanResult curvedWithFootprintAndRiver = RoadPlanner.plan(
                new BlockPos(0, 64, 0), new BlockPos(192, 64, 0),
                cfg, (x, z) -> plains, riverBarrier, nearbyVillage, RoadPlanner.ExistingRoadQuery.NONE);
        h.assertTrue(curvedWithFootprintAndRiver.ok(),
                "Road crossing a river near a village footprint must succeed");
        int skirtMaxDevZ = curvedWithFootprintAndRiver.waypoints().stream()
                .mapToInt(w -> Math.abs(w.z())).max().orElse(0);
        boolean enteredFootprint = curvedWithFootprintAndRiver.waypoints().stream()
                .anyMatch(w -> nearbyVillage.blocked(w.x(), w.z()));
        h.assertTrue(!enteredFootprint && skirtMaxDevZ >= 3,
                "River + nearby village road must keep footprint intrusion 0 while staying curved (entered="
                        + enteredFootprint + ", maxDevZ=" + skirtMaxDevZ + ")");

        RoadPlanner.ExistingRoadQuery idx1 = RoadPlanner.ExistingRoadIndex.fromWaypointLists(List.of(plan1.waypoints()));
        RoadPlanner.PlanResult plan2 = RoadPlanner.plan(new BlockPos(0, 64, 14), new BlockPos(192, 64, 24),
                cfg, (x, z) -> plains, riverBarrier, RoadPlanner.FootprintQuery.NONE, idx1);
        h.assertTrue(plan2.ok(), "Second nearby road plan reusing existing bridge must succeed");

        List<Waypoint> p2 = plan2.waypoints();
        for (int i = 0; i < p2.size() - 1; i++) {
            int dx = Math.abs(p2.get(i + 1).x() - p2.get(i).x());
            int dz = Math.abs(p2.get(i + 1).z() - p2.get(i).z());
            h.assertTrue(Math.max(dx, dz) == 1, "Reused route must remain strictly 8-connected at step " + i);
        }

        Set<Long> plan1Coords = new HashSet<>(plan1.waypoints().size() * 2);
        for (Waypoint w : plan1.waypoints()) {
            plan1Coords.add((((long) w.x()) << 32) | (w.z() & 0xffffffffL));
        }
        int checkedRiverSteps = 0;
        for (Waypoint w : p2) {
            if (w.x() >= 84 && w.x() <= 108) {
                checkedRiverSteps++;
                long key = (((long) w.x()) << 32) | (w.z() & 0xffffffffL);
                h.assertTrue(plan1Coords.contains(key),
                        "Road #2 must reuse Road #1's exact bridge centerline across river at ("
                                + w.x() + ", " + w.z() + ")");
            }
        }
        h.assertTrue(checkedRiverSteps >= 20, "Road #2 must traverse the full reused river bridge span");

        // 2. Cancelled segments must be excluded from ExistingRoadIndex.
        RoadSegment cancelled = new RoadSegment(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "millenaire:norman", "millenaire:norman_hamlet",
                plan1.waypoints(), plan1.totalBlocks(), 0, Status.CANCELLED, 0L, 0L, 0L);
        RoadPlanner.ExistingRoadQuery cancelledQuery = RoadPlanner.ExistingRoadIndex.fromSegments(
                List.of(cancelled), new BlockPos(0, 64, 14), new BlockPos(192, 64, 24));
        h.assertTrue(cancelledQuery == RoadPlanner.ExistingRoadQuery.NONE,
                "Cancelled road segments must be excluded from ExistingRoadIndex");

        // 3. Physical full-bridge adoption (reverse traversal + bank fork) in an isolated coordinate patch
        // well away from the GameTest structure grid so it cannot shadow neighboring test plots.
        BlockPos origin = new BlockPos(920_000, -54, 920_000);
        for (int dx = -16; dx <= 28; dx++) {
            for (int dz = -18; dz <= 18; dz++) {
                BlockPos base = origin.offset(dx, 0, dz);
                level.getChunkAt(base);
                for (int dy = -6; dy < 0; dy++) {
                    level.setBlockAndUpdate(base.offset(0, dy, 0), Blocks.STONE.defaultBlockState());
                }
                boolean inRiver = (dx >= 0 && dx <= 15 && dz >= -8 && dz <= 8);
                if (inRiver) {
                    for (int dy = -3; dy <= 0; dy++) {
                        level.setBlockAndUpdate(base.offset(0, dy, 0), Blocks.WATER.defaultBlockState());
                    }
                } else {
                    level.setBlockAndUpdate(base, Blocks.GRASS_BLOCK.defaultBlockState());
                }
                for (int dy = 1; dy <= 10; dy++) {
                    level.setBlockAndUpdate(base.above(dy), Blocks.AIR.defaultBlockState());
                }
            }
        }

        RoadSegment seg1 = new RoadSegment(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "millenaire:norman", "millenaire:norman_hamlet",
                List.of(new Waypoint(origin.getX() - 10, origin.getZ()),
                        new Waypoint(origin.getX() + 25, origin.getZ())),
                36, 36, Status.COMPLETED, 0L, 0L, 0L);
        RoadPlacer.realizeLoadedAlong(level, seg1, 0, seg1.steps().size() - 1);
        var style = RoadPlacer.styleFor(seg1);

        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> bridgeSnapshot = new HashMap<>();
        int[] bridgeDeckY = new int[16];
        for (int dx = 0; dx <= 15; dx++) {
            bridgeDeckY[dx] = -1;
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 1; dy <= 6; dy++) {
                    BlockPos bp = origin.offset(dx, dy, dz);
                    var st = level.getBlockState(bp);
                    bridgeSnapshot.put(bp, st);
                    if (dz == 0 && bridgeDeckY[dx] < 0 && (st.is(style.bridgeDeck()) || st.is(style.bridgeSlab()))) {
                        bridgeDeckY[dx] = bp.getY();
                    }
                }
            }
            h.assertTrue(bridgeDeckY[dx] > origin.getY(),
                    "Road #1 must build physical bridge deck at dx=" + dx);
        }

        // Road #2 traverses the exact bridge in reverse from East to West, then forks North on the West bank.
        RoadSegment seg2 = new RoadSegment(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "millenaire:norman", "millenaire:norman_hamlet",
                List.of(new Waypoint(origin.getX() + 25, origin.getZ()),
                        new Waypoint(origin.getX() - 4, origin.getZ()),
                        new Waypoint(origin.getX() - 4, origin.getZ() - 10)),
                45, 45, Status.COMPLETED, 0L, 0L, 0L);
        RoadPlacer.realizeLoadedAlong(level, seg2, 0, seg2.steps().size() - 1);

        List<Waypoint> s2 = seg2.steps();
        for (int k = 0; k < s2.size(); k++) {
            Waypoint w = s2.get(k);
            int dx = w.x() - origin.getX();
            if (dx >= 0 && dx <= 15 && w.z() == origin.getZ()) {
                h.assertTrue(seg2.isStepRealized(k) && seg2.getRealizedY(k) == bridgeDeckY[dx],
                        "Reversed Road #2 must mark reused bridge step realized at existing deck Y=" + bridgeDeckY[dx]);
            }
        }
        for (var entry : bridgeSnapshot.entrySet()) {
            h.assertTrue(level.getBlockState(entry.getKey()).equals(entry.getValue()),
                    "Adopted existing bridge blocks must remain completely untouched at " + entry.getKey());
        }
        var forkSt = level.getBlockState(origin.offset(-4, 0, -8));
        h.assertTrue(forkSt.is(style.fullBlock()) || RoadPlacer.isMillenairePathState(forkSt),
                "Dry-land fork off adopted bridge must still be paved, got " + forkSt);

        // 4. Partial overlap / crossing defense-in-depth: orthogonal crossing over Road #1's bridge mid-span.
        RoadSegment seg3 = new RoadSegment(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "millenaire:norman", "millenaire:norman_hamlet",
                List.of(new Waypoint(origin.getX() + 8, origin.getZ() - 14),
                        new Waypoint(origin.getX() + 8, origin.getZ() + 14)),
                29, 29, Status.COMPLETED, 0L, 0L, 0L);
        RoadPlacer.realizeLoadedAlong(level, seg3, 0, seg3.steps().size() - 1);

        h.assertTrue(seg3.realizedSteps().cardinality() == seg3.steps().size(),
                "Crossing bridge segment must finish realization cleanly");
        for (int dx = 0; dx <= 15; dx++) {
            int expectedY = bridgeDeckY[dx];
            for (int dz = -1; dz <= 1; dz++) {
                int foundY = -1;
                for (int dy = 1; dy <= 4; dy++) {
                    BlockPos p = origin.offset(dx, dy, dz);
                    var st = level.getBlockState(p);
                    if (st.is(style.bridgeDeck()) || st.is(style.bridgeSlab())) {
                        foundY = p.getY();
                        break;
                    }
                }
                h.assertTrue(foundY > origin.getY(),
                        "Road #1 deck must remain intact after orthogonal bridge crossing at dx=" + dx + " dz=" + dz);
                for (int y = Math.max(expectedY, foundY) + 1; y <= Math.max(expectedY, foundY) + 4; y++) {
                    var aboveSt = level.getBlockState(new BlockPos(origin.getX() + dx, y, origin.getZ() + dz));
                    h.assertTrue(!aboveSt.is(Blocks.COBBLESTONE_WALL) && !aboveSt.is(Blocks.OAK_FENCE)
                                    && !aboveSt.is(Blocks.STONE_BRICKS) && !aboveSt.is(Blocks.LANTERN)
                                    && !aboveSt.is(style.bridgeDeck()) && !aboveSt.is(style.bridgeSlab()),
                            "Crossing bridge must never place parapets, pilings, lanterns, or stacked deck on Road #1 deck at ("
                                    + dx + "," + y + "," + dz + "), got " + aboveSt);
                }
            }
        }

        // 5. Border-post overhang next to road endpoint must NOT pull dry steps upward into a dirt/cobble staircase.
        for (int dx = -12; dx <= 0; dx++) {
            for (int dz = -17; dz <= -13; dz++) {
                BlockPos base = origin.offset(dx, 0, dz);
                level.getChunkAt(base);
                for (int dy = -3; dy < 0; dy++)
                    level.setBlockAndUpdate(base.offset(0, dy, 0), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(base, Blocks.GRASS_BLOCK.defaultBlockState());
                for (int dy = 1; dy <= 8; dy++)
                    level.setBlockAndUpdate(base.above(dy), Blocks.AIR.defaultBlockState());
            }
        }
        // Build overhanging wooden cross-arm, stairs, torch, and banner at dy = 3..4 with open air below at dy = 1..2.
        for (int dy = 1; dy <= 4; dy++)
            level.setBlockAndUpdate(origin.offset(0, dy, -14), Blocks.OAK_LOG.defaultBlockState());
        level.setBlockAndUpdate(origin.offset(-1, 3, -15), Blocks.OAK_PLANKS.defaultBlockState());
        level.setBlockAndUpdate(origin.offset(-2, 3, -15), Blocks.OAK_STAIRS.defaultBlockState());
        level.setBlockAndUpdate(origin.offset(-1, 4, -15), Blocks.GREEN_BANNER.defaultBlockState());
        level.setBlockAndUpdate(origin.offset(-2, 4, -15), Blocks.TORCH.defaultBlockState());

        RoadSegment overhangSeg = new RoadSegment(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "millenaire:norman", "millenaire:norman_hamlet",
                List.of(new Waypoint(origin.getX() - 10, origin.getZ() - 15),
                        new Waypoint(origin.getX() - 1, origin.getZ() - 15)),
                10, 10, Status.COMPLETED, 0L, 0L, 0L);
        RoadPlacer.realizeLoadedAlong(level, overhangSeg, 0, overhangSeg.steps().size() - 1);
        for (int k = 0; k < overhangSeg.steps().size(); k++) {
            h.assertTrue(overhangSeg.isStepRealized(k) && overhangSeg.getRealizedY(k) == origin.getY(),
                    "Road ending under a border-post overhang must stay at ground Y=" + origin.getY()
                            + ", got " + overhangSeg.getRealizedY(k) + " at step " + k);
        }
        for (int dx = -10; dx <= -1; dx++) {
            for (int dy = 1; dy <= 2; dy++) {
                var st = level.getBlockState(origin.offset(dx, dy, -15));
                h.assertTrue(st.isAir(),
                        "No dirt/cobblestone staircase blocks may climb toward border-post overhang at ("
                                + dx + "," + dy + ",-15), got " + st);
            }
        }

        // 6. Shared dry road with existing transition slabs and a shoulder milestone must stay flat and never stack lanterns.
        for (int dx = 35; dx <= 135; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                BlockPos base = origin.offset(dx, 0, dz);
                level.getChunkAt(base);
                for (int dy = -3; dy < 0; dy++)
                    level.setBlockAndUpdate(base.offset(0, dy, 0), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(base, Blocks.GRASS_BLOCK.defaultBlockState());
                for (int dy = 1; dy <= 8; dy++)
                    level.setBlockAndUpdate(base.above(dy), Blocks.AIR.defaultBlockState());
            }
        }
        for (int dx = 123; dx <= 127; dx++) {
            level.setBlockAndUpdate(origin.offset(dx, 0, 0), style.fullBlock().defaultBlockState());
            level.setBlockAndUpdate(origin.offset(dx, 1, 0), style.slabBlock().defaultBlockState());
        }
        // Pre-existing Road #1 milestone on shoulder at i = 90 (dx = 35 + 90 = 125, dz = +2).
        level.setBlockAndUpdate(origin.offset(125, 1, 2), Blocks.COBBLESTONE_WALL.defaultBlockState());
        level.setBlockAndUpdate(origin.offset(125, 2, 2), Blocks.OAK_FENCE.defaultBlockState());
        level.setBlockAndUpdate(origin.offset(125, 3, 2), Blocks.LANTERN.defaultBlockState());

        RoadSegment sharedDrySeg = new RoadSegment(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "millenaire:norman", "millenaire:norman_hamlet",
                List.of(new Waypoint(origin.getX() + 35, origin.getZ()),
                        new Waypoint(origin.getX() + 131, origin.getZ())),
                97, 97, Status.COMPLETED, 0L, 0L, 0L);
        RoadPlacer.realizeLoadedAlong(level, sharedDrySeg, 0, sharedDrySeg.steps().size() - 1);
        for (int k = 86; k <= 94; k++) {
            h.assertTrue(sharedDrySeg.isStepRealized(k) && sharedDrySeg.getRealizedY(k) == origin.getY(),
                    "Shared dry road over existing slabs/milestone must remain at ground Y=" + origin.getY()
                            + ", got " + sharedDrySeg.getRealizedY(k) + " at step " + k);
        }
        h.assertTrue(level.getBlockState(origin.offset(125, 3, 2)).is(Blocks.LANTERN),
                "Existing milestone lantern on shoulder must remain intact");
        for (int dx = 120; dx <= 130; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = 4; dy <= 7; dy++) {
                    var hiSt = level.getBlockState(origin.offset(dx, dy, dz));
                    h.assertTrue(!hiSt.is(Blocks.LANTERN) && !hiSt.is(Blocks.SOUL_LANTERN)
                                    && !hiSt.is(Blocks.COBBLESTONE_WALL) && !hiSt.is(Blocks.OAK_FENCE),
                            "No stacked milestone blocks may appear above existing milestone height at ("
                                    + dx + "," + dy + "," + dz + "), got " + hiSt);
                }
            }
        }
        h.succeed();
    }
}
