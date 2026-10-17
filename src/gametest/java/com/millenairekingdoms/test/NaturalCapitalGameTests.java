package com.millenairekingdoms.test;

import com.millenairekingdoms.capital.CapitalConfig;
import com.millenairekingdoms.capital.NaturalCapitals;
import com.millenairekingdoms.capital.NaturalCapitals.Outcome;
import com.millenairekingdoms.economy.EconomyService;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.kingdom.KingdomSavedData.Kingdom;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.Millenaire;
import org.millenaire.block.mock.MockBlock;
import org.millenaire.building.BuildingInstance;
import org.millenaire.building.BuildingPlan;
import org.millenaire.building.BuildingPlanSet;
import org.millenaire.culture.ModCultures;
import org.millenaire.culture.VillageType;
import org.millenaire.entity.MillVillager;
import org.millenaire.item.ModItems;
import org.millenaire.village.Village;
import org.millenaire.village.VillageChunkLoader;
import org.millenaire.village.VillageId;
import org.millenaire.village.VillageManager;
import org.millenaire.village.VillageSavedData;
import org.millenaire.world.VillageSpawner;

/** Stage 6.1 natural NPC capitals on real Millénaire villages in a real (flat) world. Natural spawning is driven through
 * Millénaire's own VillageSpawnQueue, so the mixin, the chance and the distance rule run exactly as in world generation.
 * Probabilities are never left to chance: the configured percentage is set to 0 or 100, or a fixed sample is used.
 * Batches keep the config-changing tests apart. */
@PrefixGameTestTemplate(false)
public final class NaturalCapitalGameTests {
    static final String NS = "millenairekingdoms_capital_tests";
    /** Share of a plan's solid, non-marker blocks that must stand in the world. Calibrated against Millénaire's own instant placement. */
    private static final double MIN_MATCH = 0.90;
    private static final int LOAD_RADIUS = 8;

    private static boolean read() { return "read".equals(System.getProperty("kingdoms.tests.persistence")); }
    /** -Dcapitals.only=natural|matrix|rules runs one group only (development convenience); the others pass at once. */
    private static boolean skip(GameTestHelper h, String group) {
        String only = System.getProperty("capitals.only");
        if (only == null || only.equals(group)) return false;
        h.succeed(); return true;
    }

    // ---------------------------------------------------------------- rules (deterministic, no world generation)

    @GameTest(templateNamespace = NS, template = "empty", batch = "capital_rules", timeoutTicks = 200)
    public static void chanceDistanceAndEligibilityRules(GameTestHelper h) {
        if (skip(h, "rules")) return;
        ServerLevel level = h.getLevel();
        double chance = CapitalConfig.CHANCE_PERCENT.get(); int distance = CapitalConfig.MIN_DISTANCE.get();
        List<Village> made = new ArrayList<>();
        try {
            h.assertTrue(CapitalConfig.CHANCE_PERCENT.getDefault() == 2.0 && CapitalConfig.MIN_DISTANCE.getDefault() == 1500
                    && CapitalConfig.ENABLED.getDefault(), "Defaults: enabled, 2%, 1500 blocks");
            h.assertTrue(NaturalCapitals.chosen(2.0, 0.0) && NaturalCapitals.chosen(2.0, 0.0199) && !NaturalCapitals.chosen(2.0, 0.02)
                    && !NaturalCapitals.chosen(2.0, 0.5), "2%: samples below 0.02 only");
            h.assertTrue(!NaturalCapitals.chosen(0.0, 0.0) && NaturalCapitals.chosen(100.0, 0.999999), "0% never, 100% always");
            // 3. The probability is read from the NeoForge config.
            CapitalConfig.CHANCE_PERCENT.set(50.0);
            h.assertTrue(NaturalCapitals.chosen(CapitalConfig.CHANCE_PERCENT.get(), 0.4) && !NaturalCapitals.chosen(CapitalConfig.CHANCE_PERCENT.get(), 0.6),
                    "Changed config chance applies");
            CapitalConfig.CHANCE_PERCENT.set(chance);

            // 4. Distance to every living capital, exactly at the boundary.
            BlockPos origin = new BlockPos(500_000, 70, 500_000);
            Village capital = synthetic(level, origin, eligibleType(), made);
            var d = new KingdomSavedData(); var k = d.create("Rule capital", capital.getId().uuid());
            h.assertTrue(!NaturalCapitals.farFromCapitals(level, d, origin.offset(1499, 0, 0), 1500)
                    && NaturalCapitals.farFromCapitals(level, d, origin.offset(1500, 0, 0), 1500)
                    && NaturalCapitals.farFromCapitals(level, d, origin.offset(900, 0, 1200), 1500)
                    && !NaturalCapitals.farFromCapitals(level, d, origin.offset(900, 0, 1199), 1500), "Horizontal distance, 1500 inclusive");
            h.assertTrue(NaturalCapitals.farFromCapitals(level, d, origin.offset(1000, 0, 0), 900), "A configured distance is used as given");
            d.setActive(k.id(), false);
            h.assertTrue(!NaturalCapitals.farFromCapitals(level, d, origin.offset(1000, 0, 0), 1500), "An inactive but living kingdom still has a capital");

            // Rule order on real Millénaire village records (no structures needed before the space check).
            Village near = synthetic(level, origin.offset(1000, 0, 0), eligibleType(), made);
            CapitalConfig.CHANCE_PERCENT.set(100.0);
            h.assertTrue(NaturalCapitals.consider(level, d, near, 0.5).outcome() == Outcome.TOO_CLOSE, "Too close to a capital");
            CapitalConfig.CHANCE_PERCENT.set(2.0);
            h.assertTrue(NaturalCapitals.consider(level, d, near, 0.5).outcome() == Outcome.NOT_CHOSEN, "2% with sample 0.5 is not chosen");
            h.assertTrue(NaturalCapitals.consider(level, d, capital, 0.0).outcome() == Outcome.ALREADY_IN_KINGDOM, "5. A capital never founds again");
            Village owned = synthetic(level, origin.offset(-4000, 0, 0), eligibleType(), made);
            owned.setOwner(UUID.randomUUID(), "Player");
            h.assertTrue(NaturalCapitals.consider(level, d, owned, 0.0).outcome() == Outcome.NOT_ELIGIBLE, "6. A player-owned village is never chosen");
            CapitalConfig.ENABLED.set(false);
            h.assertTrue(NaturalCapitals.consider(level, d, near, 0.0).outcome() == Outcome.DISABLED, "Disabled in config");
            CapitalConfig.ENABLED.set(true);

            // Eligibility comes from Millénaire's real plan data.
            List<String> eligible = new ArrayList<>(), excluded = new ArrayList<>();
            for (VillageType t : ModCultures.getAllVillageTypes().values()) {
                boolean e = NaturalCapitals.eligible(t);
                (e ? eligible : excluded).add(t.id().getPath());
                if (t.loneBuilding() || t.playerControlled() || t.weight() <= 0)
                    h.assertTrue(!e, "Lone buildings, player villages and non-natural types are never eligible: " + t.id());
            }
            eligible.sort(null);
            h.assertTrue(eligible.size() >= 7, "Every culture offers natural capitals");
            System.out.println("CAPITAL_ELIGIBLE (" + eligible.size() + "): " + eligible);
            h.assertTrue(NaturalCapitals.name(d, near, eligibleType()).startsWith("Kingdom of "), "Readable kingdom name");
            h.succeed();
        } finally {
            CapitalConfig.CHANCE_PERCENT.set(chance); CapitalConfig.MIN_DISTANCE.set(distance); CapitalConfig.ENABLED.set(true);
            made.forEach(v -> manager(level).removeVillage(v.getId()));
        }
    }

    @GameTest(templateNamespace = NS, template = "empty", batch = "capital_rules", timeoutTicks = 200)
    public static void namesStayUniqueAndShort(GameTestHelper h) {
        if (skip(h, "rules")) return;
        ServerLevel level = h.getLevel(); List<Village> made = new ArrayList<>();
        try {
            var d = new KingdomSavedData();
            Village a = synthetic(level, new BlockPos(600_000, 70, 600_000), eligibleType(), made); a.setVillageName("Varenne");
            Village b = synthetic(level, new BlockPos(610_000, 70, 600_000), eligibleType(), made); b.setVillageName("varenne");
            Village c = synthetic(level, new BlockPos(620_000, 70, 600_000), eligibleType(), made); c.setVillageName("Varenne");
            Village longName = synthetic(level, new BlockPos(630_000, 70, 600_000), eligibleType(), made); longName.setVillageName("X".repeat(80));
            String first = NaturalCapitals.name(d, a, eligibleType()); d.create(first, a.getId().uuid());
            String second = NaturalCapitals.name(d, b, eligibleType()); d.create(second, b.getId().uuid());
            String third = NaturalCapitals.name(d, c, eligibleType()); d.create(third, c.getId().uuid());
            String four = NaturalCapitals.name(d, longName, eligibleType()); d.create(four, longName.getId().uuid());
            h.assertTrue(first.equals("Kingdom of Varenne") && second.endsWith("Kingdom of varenne") && !second.equals("Kingdom of varenne")
                    && third.equals("Kingdom of Varenne II") && four.length() <= 64, "Names: " + first + " / " + second + " / " + third + " / " + four);
            h.succeed();
        } finally { made.forEach(v -> manager(level).removeVillage(v.getId())); }
    }

    /** 13. An authentic 0.4.0 save (format 5, ruler, war, ledger) opens unchanged and keeps its format. */
    @GameTest(templateNamespace = NS, template = "empty", batch = "capital_rules", timeoutTicks = 200)
    public static void livingKingdoms040SaveOpensUnchanged(GameTestHelper h) throws Exception {
        if (skip(h, "rules")) return;
        CompoundTag old;
        try (var input = NaturalCapitalGameTests.class.getResourceAsStream("/living-kingdoms-0.4.0-kingdoms.dat")) {
            if (input == null) throw new IllegalStateException("Missing 0.4.0 fixture");
            old = NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap()).getCompound("data");
        }
        h.assertTrue(old.getInt("format") == 5 && old.getList("rulers", Tag.TAG_COMPOUND).size() == 1, "Fixture is the 0.4.0 live-test save");
        var reg = h.getLevel().registryAccess();
        var d = KingdomSavedData.load(old, reg);
        CompoundTag again = d.save(new CompoundTag(), reg);
        // Format 6 (1.0.0) keeps every 0.4.0 field and only adds the autonomy section (the fixture's war ended in peace: no clock).
        CompoundTag autonomy = again.getCompound("autonomy"); again.remove("autonomy");
        CompoundTag expected = old.copy(); expected.putInt("format", KingdomSavedData.FORMAT);
        h.assertTrue(canonical(again).equals(canonical(expected)) && autonomy.getList("orders", Tag.TAG_COMPOUND).isEmpty()
                && autonomy.getList("truces", Tag.TAG_COMPOUND).isEmpty(), "Loads and saves back identically apart from the format-6 additions");
        h.assertTrue(d.kingdoms().size() == 2 && d.kingdoms().stream().filter(k -> d.npcGoverned(k.id())).count() == 1,
                "Both kingdoms kept: one ruled by a player, one NPC-governed");
        h.succeed();
    }

    // ---------------------------------------------------------------- every eligible town hall, physically

    /** 8, 9, 10 per village type: Millénaire places the village through its public API (as commands do, so no kingdom
     * is created even at 100%), then NaturalCapitals.develop raises the town hall. The blocks in the world must match
     * the final plan, not level 0. Each village is removed afterwards to keep memory bounded. */
    @GameTest(templateNamespace = NS, template = "empty", batch = "capital_matrix", timeoutTicks = 24000)
    public static void everyEligibleTownHallReachesItsRealFinalLevel(GameTestHelper h) {
        if (skip(h, "matrix")) return;
        if (read()) { h.succeed(); return; }
        ServerLevel level = h.getLevel();
        Millenaire.setNaturalSpawnSuppressed(true);
        List<VillageType> types = ModCultures.getAllVillageTypes().values().stream().filter(NaturalCapitals::eligible)
                .sorted(Comparator.comparing(t -> t.id().toString())).toList();
        double chance = CapitalConfig.CHANCE_PERCENT.get();
        CapitalConfig.CHANCE_PERCENT.set(100.0);
        List<String> failures = new ArrayList<>();
        int[] index = {0}; int[] phase = {0}; int[] pause = {0};
        BlockPos[] pos = {null};
        // onEachTick: an exception fails the test at once instead of being retried.
        h.onEachTick(() -> {
            if (index[0] >= types.size()) {
                CapitalConfig.CHANCE_PERCENT.set(chance);
                h.assertTrue(failures.isEmpty(), "Town halls not developed: " + failures);
                System.out.println("CAPITAL_MATRIX_PASS " + types.size() + " village types");
                h.succeed();
                return;
            }
            VillageType type = types.get(index[0]);
            if (phase[0] == 0) {
                pos[0] = new BlockPos(-40_000 + index[0] * 800, 0, 40_000);
                force(level, pos[0], true); phase[0] = 1;
            } else if (phase[0] == 1 && loaded(level, pos[0])) {
                if (deepestGround(type) < -4) mound(level, pos[0]); // Other types keep the flat ground their start buildings expect.
                try { failures.addAll(developAndCheck(h, level, type, pos[0])); }
                catch (RuntimeException e) { failures.add(type.id() + ": " + e); e.printStackTrace(); }
                force(level, pos[0], false); phase[0] = 2; pause[0] = 0;
            } else if (phase[0] == 2 && ++pause[0] >= 60) {
                index[0]++; phase[0] = 0; // Released chunks unload before the next village.
            }
        });
    }

    private static List<String> developAndCheck(GameTestHelper h, ServerLevel level, VillageType type, BlockPos at) {
        List<String> failures = new ArrayList<>();
        VillageManager manager = manager(level);
        Set<VillageId> before = ids(manager);
        BlockPos surface = at.atY(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()));
        var failure = VillageSpawner.spawnVillage(level, surface, type, 0);
        if (failure != null) return List.of(type.id() + ": Millénaire refused the site: " + failure.getString());
        Village village = newVillage(manager, before, type, surface);
        try {
            if (village == null) return List.of(type.id() + ": village not found");
            if (KingdomSavedData.get(level).owner(village.getId().uuid()) != null) failures.add(type.id() + ": API spawn founded a kingdom");
            BuildingInstance hall = village.getTownhall();
            int start = hall.getLevel(), last = NaturalCapitals.finalLevel(hall);
            Check initial = structure(level, village, hall, last);
            String problem = NaturalCapitals.spaceProblem(level, village, hall);
            if (problem != null) failures.add(type.id() + ": no space: " + problem);
            boolean developed = NaturalCapitals.develop(level, village, hall);
            Check after = structure(level, village, hall, hall.getLevel());
            List<BuildingInstance> subs = village.getBuildings().stream().filter(b -> hall.getId().equals(b.getParentBuildingId())).toList();
            boolean subsDone = subs.stream().allMatch(b -> b.getStatus() == BuildingInstance.Status.COMPLETE);
            List<BlockPos> chests = hall.getInventory() == null ? List.of() : hall.getInventory().getChestPositions();
            List<String> notChests = chests.stream().filter(p -> !(level.getBlockState(p).getBlock() instanceof ChestBlock))
                    .map(p -> p.toShortString() + "=" + level.getBlockState(p).getBlock()).toList();
            // Millénaire's own plans occasionally let a later sub-building template replace a town hall chest spot.
            boolean chestsReal = !chests.isEmpty() && notChests.size() * 10 <= chests.size();
            if (!chestsReal) System.out.println("CAPITAL_MATRIX " + type.id() + " non-chest storage: " + notChests);
            System.out.printf("CAPITAL_MATRIX %s variant=%s level %d -> %d/%d plan=%s before: added=%.3f after: %s subs=%d done=%s chests=%d real=%s%n",
                    type.id(), hall.getVariant(), start, hall.getLevel(), last, hall.getPlanId().getPath(), initial.added, after, subs.size(), subsDone, chests.size(), chestsReal);
            if (!developed || hall.getLevel() != last) failures.add(type.id() + ": level " + hall.getLevel() + "/" + last);
            if (after.match < MIN_MATCH || after.added < MIN_MATCH || initial.added > 0.5)
                failures.add(type.id() + ": blocks before added=" + initial.added + " after " + after);
            if (!subsDone) failures.add(type.id() + ": sub-building not complete");
            if (!chestsReal) failures.add(type.id() + ": town hall chests " + chests.size());
            return failures;
        } finally {
            if (village != null) discard(level, village);
        }
    }

    // ---------------------------------------------------------------- natural world generation through Millénaire's queue

    private record Candidate(String key, int dx, int dz, double chance, boolean capital) {}
    /** Relative to world spawn, all inside Millénaire's 2000-block "no initial completion" zone: a normal natural village
     * keeps its level-0 town hall, so "unchanged" is visible. A-C: 2000 apart (both capitals), B: 1000-1414 from them
     * (too close), D: 1600 from A but chance 0 in the config. */
    private static final List<Candidate> CANDIDATES = List.of(
            new Candidate("A", 1000, 0, 100.0, true),
            new Candidate("B", 1000, 1000, 100.0, false),
            new Candidate("C", -1000, 0, 100.0, true),
            new Candidate("D", 1000, -1600, 0.0, false));

    @GameTest(templateNamespace = NS, template = "empty", batch = "capital_natural", timeoutTicks = 12000)
    public static void naturalGenerationFoundsRareCapitals(GameTestHelper h) throws Exception {
        ServerLevel level = h.getLevel();
        Millenaire.setNaturalSpawnSuppressed(true);
        if (skip(h, "natural")) return;
        Path file = level.getServer().getWorldPath(LevelResource.ROOT).resolve("natural-capitals-test.properties");
        if (read()) { verifyAfterRestart(h, level, file); return; }
        BlockPos spawnPos = level.getSharedSpawnPos();
        h.assertTrue(NaturalCapitals.farFromCapitals(level, KingdomSavedData.get(level), spawnPos, 2700),
                "Run this suite on a fresh world: another suite left a capital near spawn");
        double chance = CapitalConfig.CHANCE_PERCENT.get();
        var queue = Millenaire.getSpawnQueue();
        Map<String, Village> spawned = new HashMap<>();
        List<Integer> step = new ArrayList<>(List.of(0, 0, 0)); // candidate index, phase, attempt
        BlockPos[] pos = {null}; Set<VillageId>[] before = new Set[1];
        Map<UUID, BlockPos> watched = new HashMap<>(); long[] watchFrom = {0}, loadFrom = {0};
        h.onEachTick(() -> {
            int i = step.get(0), phase = step.get(1);
            if (i < CANDIDATES.size()) {
                Candidate c = CANDIDATES.get(i);
                if (phase == 0) {
                    BlockPos spawn = level.getSharedSpawnPos();
                    pos[0] = new BlockPos(spawn.getX() + c.dx + step.get(2) * 160, 64, spawn.getZ() + c.dz + step.get(2) * 160);
                    force(level, pos[0], true); step.set(1, 1);
                } else if (phase == 1 && loaded(level, pos[0])) {
                    mound(level, pos[0]);
                    CapitalConfig.CHANCE_PERCENT.set(c.chance);
                    before[0] = ids(manager(level));
                    queue.reset(); // Only this candidate: candidates queued before suppression would keep the queue busy.
                    queue.enqueue(pos[0]);
                    step.set(1, 2);
                } else if (phase == 2) {
                    queue.trySpawnNext(level);
                    if (((Number) queue.getStats().get("mainQueueSize")).intValue() == 0) {
                        Village v = newVillage(manager(level), before[0], null, pos[0]);
                        force(level, pos[0], false);
                        if (v == null) {
                            if (step.get(2) >= 3) throw new IllegalStateException("Millénaire placed no village near candidate " + c.key);
                            step.set(2, step.get(2) + 1); step.set(1, 0);
                        } else {
                            spawned.put(c.key, v);
                            System.out.println("CAPITAL_NATURAL " + c.key + " village '" + v.getVillageName() + "' " + v.getVillageTypeId()
                                    + " at " + v.getCenter().toShortString() + " kingdom=" + KingdomSavedData.get(level).owner(v.getId().uuid()));
                            step.set(0, i + 1); step.set(1, 0); step.set(2, 0);
                        }
                    }
                }
                return;
            }
            if (watched.isEmpty() && loadFrom[0] == 0) {
                // Millénaire releases its village chunk tickets when no player is near; load both capitals again.
                CapitalConfig.CHANCE_PERCENT.set(chance);
                force(level, spawned.get("A").getCenter(), true); force(level, spawned.get("C").getCenter(), true);
                loadFrom[0] = level.getGameTime();
                return;
            }
            if (watched.isEmpty()) {
                boolean ready = loaded(level, spawned.get("A").getCenter()) && loaded(level, spawned.get("C").getCenter())
                        && !villagers(level, spawned.get("A")).isEmpty() && !villagers(level, spawned.get("C")).isEmpty();
                if (!ready && level.getGameTime() - loadFrom[0] < 600) return;
                checkWorld(h, level, spawned, file);
                for (MillVillager m : villagers(level, spawned.get("A"))) watched.put(m.getUUID(), m.blockPosition());
                watchFrom[0] = level.getGameTime();
                return;
            }
            if (watchFrom[0] < 0) {
                // Let the released chunks unload before the server stops (a stop during unloading can stall the shutdown save).
                if (level.getGameTime() + watchFrom[0] >= 200) h.succeed();
                return;
            }
            if (level.getGameTime() - watchFrom[0] < 400) return;
            // 10. Millénaire NPCs keep living: all still there, and some walked.
            List<MillVillager> now = villagers(level, spawned.get("A"));
            long moved = now.stream().filter(m -> watched.containsKey(m.getUUID()) && m.blockPosition().distManhattan(watched.get(m.getUUID())) > 2).count();
            h.assertTrue(now.size() >= watched.size() && moved > 0, "Capital villagers alive (" + now.size() + "/" + watched.size() + ") and moving (" + moved + ")");
            System.out.println("CAPITAL_NATURAL villagers " + now.size() + " moved " + moved);
            force(level, spawned.get("A").getCenter(), false); force(level, spawned.get("C").getCenter(), false);
            watchFrom[0] = -level.getGameTime();
        });
    }

    private static void checkWorld(GameTestHelper h, ServerLevel level, Map<String, Village> spawned, Path file) {
        KingdomSavedData data = KingdomSavedData.get(level);
        Properties out = new Properties();
        for (Candidate c : CANDIDATES) {
            Village v = spawned.get(c.key); UUID id = v.getId().uuid(); UUID k = data.owner(id);
            BuildingInstance hall = v.getTownhall();
            out.setProperty(c.key + ".village", id.toString());
            out.setProperty(c.key + ".pos", v.getCenter().getX() + "," + v.getCenter().getZ());
            if (!c.capital) {
                // 1. A normal natural village is left exactly as Millénaire made it.
                h.assertTrue(k == null && hall.getLevel() == 0 && hall.getStatus() == BuildingInstance.Status.COMPLETE,
                        "Candidate " + c.key + " stays a normal village with its level-0 town hall");
                continue;
            }
            // 2, 7, 8, 9: kingdom record and the physical final-level town hall.
            Kingdom kingdom = data.kingdom(k);
            int last = NaturalCapitals.finalLevel(hall);
            h.assertTrue(k != null && data.isCapital(id) && kingdom.active() && !kingdom.fallen() && data.ruler(k) == null
                    && data.npcGoverned(k) && data.mayDecide(k, null) && data.members(k).equals(Set.of(id)), c.key + ": active NPC kingdom, capital only");
            h.assertTrue(hall.getLevel() == last && last > 0 && hall.getStatus() == BuildingInstance.Status.COMPLETE
                    && hall.getPlanId().equals(plan(hall, last).id()), c.key + ": town hall at its final plan level " + hall.getLevel() + "/" + last);
            Check check = structure(level, v, hall, last);
            h.assertTrue(check.match >= MIN_MATCH && check.added >= MIN_MATCH, c.key + ": final-level blocks in the world, " + check);
            List<BlockPos> chests = hall.getInventory().getChestPositions();
            long missing = chests.stream().filter(p -> !(level.getBlockState(p).getBlock() instanceof ChestBlock)).count();
            // As in the matrix: a later sub-building template of Millénaire's own plans may replace a few chest spots.
            h.assertTrue(!chests.isEmpty() && missing * 10 <= chests.size(), c.key + ": town hall chests exist (" + missing + " of " + chests.size() + " missing)");
            List<MillVillager> people = villagers(level, v);
            if (people.stream().noneMatch(com.millenairekingdoms.politics.ChiefInteraction::isChief)) {
                var all = level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(MillVillager.class), m -> true);
                System.out.println("CAPITAL_NATURAL debug positions " + all.stream().limit(8).map(m -> m.blockPosition().toShortString() + "/" + m.isAlive() + "/" + m.isRemoved()).toList()
                        + " records " + v.getVillagerRecords().values().stream().limit(4).map(r -> r.getVillagerTypeId() + "@" + r.getLastKnownPos() + " killed=" + r.isKilled()).toList()
                        + " chunkLoaded=" + level.hasChunkAt(v.getCenter()) + " forced=" + v.isChunksForceLoaded());
                System.out.println("CAPITAL_NATURAL debug " + c.key + " records=" + v.getVillagerRecords().size() + " nearby=" + all.size() + " "
                        + all.stream().limit(12).map(m -> m.getVillagerTypeId() + "/" + m.getVillageId() + "/chief=" + m.isChief()).toList());
            }
            h.assertTrue(!people.isEmpty() && people.stream().anyMatch(com.millenairekingdoms.politics.ChiefInteraction::isChief), c.key + ": villagers with a chief");
            out.setProperty(c.key + ".kingdom", k.toString());
            out.setProperty(c.key + ".level", Integer.toString(last));
            out.setProperty(c.key + ".plan", hall.getPlanId().toString());
            System.out.printf("CAPITAL_NATURAL %s '%s' kingdom='%s' level %d/%d plan=%s %s chests=%d villagers=%d%n",
                    c.key, v.getVillageName(), kingdom.name(), hall.getLevel(), last, hall.getPlanId().getPath(), check, chests.size(), people.size());
        }
        h.assertTrue(CANDIDATES.stream().filter(c -> data.owner(spawned.get(c.key).getId().uuid()) != null).count() == 2,
                "4. Exactly two natural capitals: B was too close, D not chosen");
        // 5, 12. The same village never founds twice; Millénaire's queue on the same spots creates nothing new.
        Village a = spawned.get("A");
        h.assertTrue(NaturalCapitals.consider(level, data, a, 0.0).outcome() == Outcome.ALREADY_IN_KINGDOM, "A cannot found a second kingdom");
        int villages = manager(level).getAllVillages().size(), kingdoms = data.kingdoms().size();
        requeue(level, spawned);
        h.assertTrue(manager(level).getAllVillages().size() == villages && data.kingdoms().size() == kingdoms, "Re-queued spots create nothing");
        // Income: a natural capital with no members earns nothing; with one it is paid into its own town hall chests.
        UUID ka = data.owner(a.getId().uuid());
        h.assertTrue(EconomyService.dailyIncome(level, data, ka) == 0, "No members, no income");
        var copy = KingdomSavedData.load(data.save(new CompoundTag(), level.registryAccess()), level.registryAccess());
        Village member = synthetic(level, a.getCenter().offset(300, 0, 300), ModCultures.getVillageType(a.getVillageTypeId()), new ArrayList<>());
        member.addBuilding(new BuildingInstance(org.millenaire.building.BuildingId.random(), a.getTownhall().getPlanId(), member.getCenter(),
                net.minecraft.world.level.block.Rotation.NONE, BuildingInstance.Status.COMPLETE, a.getTownhall().getPlanSetId(), a.getTownhall().getVariant(), 0));
        copy.attach(ka, member.getId().uuid());
        int coins = a.getTownhall().getInventory().getCount(level, ModItems.DENIER.get());
        copy.startDay(0, Map.of());
        boolean paid = EconomyService.runDay(level, copy, 1);
        int after = a.getTownhall().getInventory().getCount(level, ModItems.DENIER.get());
        manager(level).removeVillage(member.getId());
        h.assertTrue(paid && after == coins + EconomyService.DENIERS_PER_LEVEL && copy.owed(ka) == 0, "Income paid into the natural capital's town hall (" + coins + " -> " + after + ")");
        try { Files.writeString(file, toText(out)); } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        data.setDirty(); VillageSavedData.get(level).setDirty();
        System.out.println("CAPITAL_NATURAL_WRITE_PASS " + out);
    }

    /** 11, 12, 15 after a real restart in a separate JVM. */
    private static void verifyAfterRestart(GameTestHelper h, ServerLevel level, Path file) throws Exception {
        Properties p = new Properties();
        try (var r = Files.newBufferedReader(file)) { p.load(r); }
        KingdomSavedData data = KingdomSavedData.get(level);
        Map<String, Village> spawned = new HashMap<>();
        for (Candidate c : CANDIDATES) {
            Village v = manager(level).getVillage(new VillageId(UUID.fromString(p.getProperty(c.key + ".village"))));
            h.assertTrue(v != null, c.key + ": Millénaire village kept");
            spawned.put(c.key, v);
            UUID k = data.owner(v.getId().uuid());
            if (!c.capital) { h.assertTrue(k == null, c.key + ": still no kingdom"); continue; }
            BuildingInstance hall = v.getTownhall();
            h.assertTrue(k != null && k.toString().equals(p.getProperty(c.key + ".kingdom")) && data.isCapital(v.getId().uuid()) && data.npcGoverned(k)
                    && data.kingdom(k).active() && data.members(k).size() == 1, c.key + ": same NPC kingdom after restart");
            h.assertTrue(hall.getLevel() == Integer.parseInt(p.getProperty(c.key + ".level")) && hall.getPlanId().toString().equals(p.getProperty(c.key + ".plan")),
                    c.key + ": town hall level kept");
            Check check = structure(level, v, hall, hall.getLevel());
            h.assertTrue(check.match >= MIN_MATCH && check.added >= MIN_MATCH, c.key + ": buildings kept after restart, " + check);
            System.out.println("CAPITAL_RESTART " + c.key + " " + v.getVillageName() + " level " + hall.getLevel() + " " + check);
        }
        int villages = manager(level).getAllVillages().size(), kingdoms = data.kingdoms().size();
        requeue(level, spawned);
        h.assertTrue(manager(level).getAllVillages().size() == villages && data.kingdoms().size() == kingdoms,
                "12. Reloaded chunks and re-queued spots create no second kingdom");
        // 15. Zero-member natural capital under the existing war rules (on a copy, the saved world stays as it is).
        var copy = KingdomSavedData.load(data.save(new CompoundTag(), level.registryAccess()), level.registryAccess());
        UUID a = spawned.get("A").getId().uuid(), c = spawned.get("C").getId().uuid();
        UUID ka = copy.owner(a), kc = copy.owner(c);
        var war = copy.declareWar(kc, ka, Math.max(1, level.getGameTime()));
        h.assertTrue(war.initialDefenderMembers().isEmpty() && KingdomSavedData.capitalRequirement(war) == 0 && copy.capitalOpen(war),
                "A zero-member capital is open as soon as war starts");
        var campaign = copy.startable(c, a);
        h.assertTrue(war.id().equals(campaign.warId()) && KingdomSavedData.requiredWins(campaign) == 2, "Capital needs 2 wins in this war");
        for (int i = 0; i < 2; i++) { var raid = copy.begin(c, a, 1, null); copy.recordVictory(raid); copy.finish(raid, KingdomSavedData.Outcome.LOST); }
        h.assertTrue(copy.kingdom(ka).fallen() && kc.equals(copy.owner(a)) && copy.war(war.id()).status() == KingdomSavedData.WarStatus.CONQUERED,
                "Two wins conquer the capital and end the kingdom");
        System.out.println("CAPITAL_RESTART_READ_PASS: 2 natural kingdoms, buildings and levels kept, no duplicates, war rules intact");
        h.succeed();
    }

    // ---------------------------------------------------------------- helpers

    private static void requeue(ServerLevel level, Map<String, Village> spawned) {
        var queue = Millenaire.getSpawnQueue();
        for (Village v : spawned.values()) queue.enqueue(v.getCenter());
        for (int i = 0; i < 20 && ((Number) queue.getStats().get("mainQueueSize")).intValue() > 0; i++) queue.trySpawnNext(level);
    }

    private static VillageManager manager(ServerLevel level) { return VillageSavedData.get(level).getVillageManager(); }
    private static Set<VillageId> ids(VillageManager m) { Set<VillageId> s = new HashSet<>(); m.getAllVillages().forEach(v -> s.add(v.getId())); return s; }
    private static Village newVillage(VillageManager m, Set<VillageId> before, VillageType type, BlockPos near) {
        Village best = null;
        for (Village v : m.getAllVillages())
            if (!before.contains(v.getId()) && v.getParentVillageId() == null && (type == null || type.id().equals(v.getVillageTypeId()))
                    && v.getCenter().distManhattan(near.atY(v.getCenter().getY())) < 600) best = v;
        return best;
    }
    private static VillageType eligibleType() {
        return ModCultures.getAllVillageTypes().values().stream().filter(NaturalCapitals::eligible)
                .min(Comparator.comparing(t -> t.id().toString())).orElseThrow();
    }
    /** A Millénaire village record without structures, for the rule checks that run before any building is touched. */
    private static Village synthetic(ServerLevel level, BlockPos center, VillageType type, List<Village> made) {
        Village v = new Village(VillageId.random(), type.culture(), type.id(), center);
        v.setVillageName("Rule " + made.size());
        manager(level).addVillage(v); made.add(v);
        return v;
    }
    /** Lowest plan ground level of the type's town hall and its sub-buildings (cellars). */
    private static int deepestGround(VillageType type) {
        int min = 0;
        for (VillageType.LayoutSlot slot : type.layout()) {
            if (!"centre".equals(slot.role()) && !"townhall".equals(slot.role())) continue;
            BuildingPlanSet set = ModCultures.getBuildingPlanSet(slot.plan());
            for (var levels : set.variants().values()) for (var l : levels) min = Math.min(min, ModCultures.getBuildingPlan(l.planId()).groundLevel());
        }
        return min;
    }
    /** The flat test world has bedrock four blocks below the grass, too shallow for town halls with cellars (plan ground
     * level down to -8). A walkable 14-block mound gives the town hall at this centre real ground below it, as normal terrain does. */
    private static void mound(ServerLevel level, BlockPos center) {
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, center.getX(), center.getZ());
        int height = 14, low = -75, high = 75; // a wide plateau: Millénaire wants level ground for the start buildings too
        var dirt = net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState();
        var grass = net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState();
        for (int dx = low - height; dx <= high + height; dx++) for (int dz = low - height; dz <= high + height; dz++) {
            int outside = Math.max(Math.max(low - dx, dx - high), Math.max(low - dz, dz - high));
            int top = height - Math.max(0, outside);
            for (int y = 0; y < top; y++)
                level.setBlock(new BlockPos(center.getX() + dx, surface + y, center.getZ() + dz), y == top - 1 ? grass : dirt, 2);
            if (top > 0) level.setBlock(new BlockPos(center.getX() + dx, surface - 1, center.getZ() + dz), dirt, 2);
        }
    }
    private static void force(ServerLevel level, BlockPos pos, boolean add) {
        ChunkPos c = new ChunkPos(pos);
        for (int x = -LOAD_RADIUS; x <= LOAD_RADIUS; x++) for (int z = -LOAD_RADIUS; z <= LOAD_RADIUS; z++) level.setChunkForced(c.x + x, c.z + z, add);
    }
    private static boolean loaded(ServerLevel level, BlockPos pos) {
        int r = LOAD_RADIUS * 16;
        return level.hasChunksAt(pos.offset(-r, 0, -r), pos.offset(r, 0, r));
    }
    private static List<MillVillager> villagers(ServerLevel level, Village v) {
        AABB box = new AABB(v.getCenter()).inflate(160, 128, 160);
        return level.getEntitiesOfClass(MillVillager.class, box, m -> m.isAlive() && v.getId().equals(m.getVillageId()));
    }
    private static void discard(ServerLevel level, Village v) {
        villagers(level, v).forEach(net.minecraft.world.entity.Entity::discard);
        VillageChunkLoader.releaseVillageChunks(level, v.getCenter(), v.getLoadedChunks());
        manager(level).removeVillage(v.getId());
    }
    private static BuildingPlan plan(BuildingInstance hall, int level) {
        BuildingPlanSet set = ModCultures.getBuildingPlanSet(hall.getPlanSetId());
        return ModCultures.getBuildingPlan(set.getPlanId(hall.getVariant(), level));
    }
    /** Where a plan's origin would be for this building (only the ground level can differ between levels). */
    private static BlockPos originFor(BuildingInstance b, BuildingPlan plan) {
        BuildingPlan current = ModCultures.getBuildingPlan(b.getPlanId());
        return b.getOrigin().below(current.groundLevel()).above(plan.groundLevel());
    }

    /** Physical check of a town hall. Millénaire stores each upgrade level as a delta template that is placed on top of
     * the previous levels, so the building at level n is level 0 plus deltas 1..n, the last template writing a position
     * winning. match: share of these expected blocks (air included) found in the world; upgraded: the same share only
     * where levels 1..n changed level 0 (proves the upgrades are physically there). Positions written by any of the town
     * hall's sub-buildings and Millénaire marker blocks are left out; painted bricks match any painted brick. */
    private record Check(double match, double upgraded, double added, int positions, int upgradedPositions, int addedPositions) {
        @Override public String toString() {
            return String.format("match=%.3f upgraded=%.3f added=%.3f (%d/%d/%d positions)", match, upgraded, added, positions, upgradedPositions, addedPositions);
        }
    }
    private static Check structure(ServerLevel level, Village village, BuildingInstance hall, int upTo) {
        BuildingPlanSet set = ModCultures.getBuildingPlanSet(hall.getPlanSetId());
        Map<BlockPos, Block> expected = new HashMap<>(), first = new HashMap<>();
        Set<BlockPos> unknown = new HashSet<>();
        for (int l = 0; l <= upTo; l++) {
            BuildingPlan plan = ModCultures.getBuildingPlan(set.getPlanId(hall.getVariant(), l));
            Map<BlockPos, Block> layer = template(level, plan, originFor(hall, plan), hall.getRotation());
            layer.forEach((pos, block) -> { if (block == null) { expected.remove(pos); unknown.add(pos); } else { expected.put(pos, block); unknown.remove(pos); } });
            if (l == 0) first.putAll(expected);
        }
        for (BuildingInstance sub : village.getBuildings()) {
            if (!hall.getId().equals(sub.getParentBuildingId())) continue;
            BuildingPlanSet subSet = ModCultures.getBuildingPlanSet(sub.getPlanSetId());
            for (BuildingPlanSet.LevelDef l : subSet.variants().get(sub.getVariant())) {
                BuildingPlan plan = ModCultures.getBuildingPlan(l.planId());
                template(level, plan, originFor(sub, plan), sub.getRotation()).keySet().forEach(expected::remove);
            }
        }
        int ok = 0, changed = 0, changedOk = 0, added = 0, addedOk = 0;
        for (var e : expected.entrySet()) {
            boolean same = same(level.getBlockState(e.getKey()).getBlock(), e.getValue());
            if (same) ok++;
            if (first.get(e.getKey()) != e.getValue()) {
                changed++; if (same) changedOk++;
                // Solid blocks the upgrades add: absent before the upgrades, so they show that the deltas were placed.
                // Dirt is left out: deeper upgrade foundations (indian chiefhouse b: ground_level -1 -> -5) refill the
                // test ground with the dirt it already holds, which would count as "placed" before any upgrade.
                if (!(e.getValue() instanceof net.minecraft.world.level.block.AirBlock)
                        && !e.getValue().defaultBlockState().is(net.minecraft.tags.BlockTags.DIRT)) { added++; if (same) addedOk++; }
            }
        }
        return new Check(expected.isEmpty() ? 0 : (double) ok / expected.size(), changed == 0 ? 0 : (double) changedOk / changed,
                added == 0 ? 0 : (double) addedOk / added, expected.size(), changed, added);
    }
    private static boolean same(Block world, Block expected) {
        if (world == expected) return true;
        if (expected instanceof org.millenaire.block.IPaintedBlock) return world instanceof org.millenaire.block.IPaintedBlock;
        return world instanceof net.minecraft.world.level.block.AirBlock && expected instanceof net.minecraft.world.level.block.AirBlock;
    }
    /** The raw template of one plan in world positions; null marks a Millénaire marker (replaced when placed). */
    private static Map<BlockPos, Block> template(ServerLevel level, BuildingPlan plan, BlockPos origin, net.minecraft.world.level.block.Rotation rotation) {
        Map<BlockPos, Block> out = new HashMap<>();
        var template = org.millenaire.building.TemplateLoader.load(plan, level, org.millenaire.building.TemplateLoader.cultureFsFor(plan)).orElseThrow();
        CompoundTag nbt = template.save(new CompoundTag());
        ListTag paletteTag = org.millenaire.building.NbtPaletteHelper.resolvePaletteTag(nbt);
        List<Block> palette = new ArrayList<>();
        for (int i = 0; paletteTag != null && i < paletteTag.size(); i++)
            palette.add(net.minecraft.nbt.NbtUtils.readBlockState(level.holderLookup(net.minecraft.core.registries.Registries.BLOCK), paletteTag.getCompound(i)).getBlock());
        var settings = new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings().setRotation(rotation);
        for (Tag t : nbt.getList("blocks", Tag.TAG_COMPOUND)) {
            CompoundTag e = (CompoundTag) t; ListTag pos = e.getList("pos", Tag.TAG_INT);
            Block block = palette.get(e.getInt("state"));
            BlockPos rel = net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.calculateRelativePosition(settings,
                    new BlockPos(pos.getInt(0), pos.getInt(1), pos.getInt(2)));
            if (block != net.minecraft.world.level.block.Blocks.STRUCTURE_VOID) out.put(origin.offset(rel), block instanceof MockBlock ? null : block);
        }
        return out;
    }
    private static String toText(Properties p) {
        var w = new java.io.StringWriter();
        try { p.store(w, "stage 6.1 natural capital test"); } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        return w.toString();
    }
    /** Saved maps have no defined order, so compare NBT with every list treated as a multiset. */
    private static Tag canonical(Tag tag) {
        if (tag instanceof CompoundTag c) { CompoundTag out = new CompoundTag(); for (String k : c.getAllKeys()) out.put(k, canonical(c.get(k))); return out; }
        if (tag instanceof ListTag l) {
            List<Tag> items = new ArrayList<>(); l.forEach(t -> items.add(canonical(t)));
            items.sort(Comparator.comparing(Tag::toString));
            ListTag out = new ListTag(); out.addAll(items); return out;
        }
        return tag;
    }
}
