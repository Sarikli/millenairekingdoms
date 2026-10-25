package com.millenairekingdoms.test;

import com.millenairekingdoms.autonomy.AutonomyConfig;
import com.millenairekingdoms.autonomy.AutonomyService;
import com.millenairekingdoms.autonomy.Military;
import com.millenairekingdoms.capital.CapitalConfig;
import com.millenairekingdoms.capital.DynamicFounding;
import com.millenairekingdoms.capital.DynamicFounding.Outcome;
import com.millenairekingdoms.capital.FoundingConfig;
import com.millenairekingdoms.capital.NaturalCapitals;
import com.millenairekingdoms.economy.EconomyService;
import com.millenairekingdoms.gui.KingdomScreens;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.kingdom.KingdomSavedData.Kingdom;
import com.millenairekingdoms.politics.KingdomDay;
import java.lang.reflect.Field;
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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.Millenaire;
import org.millenaire.building.BuildingId;
import org.millenaire.building.BuildingInstance;
import org.millenaire.building.BuildingInventory;
import org.millenaire.building.BuildingPlanSet;
import org.millenaire.culture.ModCultures;
import org.millenaire.culture.VillageType;
import org.millenaire.entity.MillVillager;
import org.millenaire.village.BuildingFinalizer;
import org.millenaire.village.SubBuildingHelper;
import org.millenaire.village.Village;
import org.millenaire.village.VillageChunkLoader;
import org.millenaire.village.VillageGrowthManager;
import org.millenaire.village.VillageId;
import org.millenaire.village.VillageManager;
import org.millenaire.village.VillageSavedData;
import org.millenaire.world.VillageSpawner;

/** 1.1.0 verifyFounding: independent NPC villages that developed on their own found kingdoms. Rule checks run on real
 * Millénaire village records whose town hall is a real plan set, variant and plan of the tested level; the growth test
 * spawns a real village and raises its town hall with Millénaire's own rush upgrades. Probabilities are never left to
 * chance: a fixed sample, 0% or 100%. Batches keep tests that evaluate every village of the world apart. */
@PrefixGameTestTemplate(false)
public final class FoundingGameTests {
    static final String NS = "millenairekingdoms_founding_tests";
    static final FoundingConfig.Settings DEFAULTS = FoundingConfig.Settings.DEFAULTS;
    static final FoundingConfig.Settings ALWAYS = DEFAULTS.withChance(100, 0), NEVER = DEFAULTS.withChance(0, 0);
    static final AutonomyConfig.Settings POLITICS_OFF = AutonomyConfig.Settings.DEFAULTS.with(false, false, false);
    private static final int LOAD_RADIUS = 8;

    private static boolean read() { return "read".equals(System.getProperty("kingdoms.tests.persistence")); }

    // ---------------------------------------------------------------- 1-8, 16-17, 30: the chance and its configuration

    @GameTest(templateNamespace = NS, template = "empty", batch = "founding_rules", timeoutTicks = 200)
    public static void chanceTableFollowsCompletedLevels(GameTestHelper h) {
        h.assertTrue(FoundingConfig.ENABLED.getDefault() && FoundingConfig.MINIMUM_LEVEL.getDefault() == 3 && FoundingConfig.INTERVAL_DAYS.getDefault() == 3
                && FoundingConfig.BASE_CHANCE.getDefault() == 5.0 && FoundingConfig.INCREASE.getDefault() == 5.0 && FoundingConfig.MIN_DISTANCE.getDefault() == 1000,
                "Config defaults: on, level 3, every 3 days, 5% + 5 per level, 1000 blocks");
        h.assertTrue(DEFAULTS.equals(new FoundingConfig.Settings(FoundingConfig.ENABLED.getDefault(), FoundingConfig.MINIMUM_LEVEL.getDefault(),
                FoundingConfig.INTERVAL_DAYS.getDefault(), FoundingConfig.BASE_CHANCE.getDefault(), FoundingConfig.INCREASE.getDefault(),
                FoundingConfig.MIN_DISTANCE.getDefault())), "Code defaults equal the config defaults");
        double[] expected = {0, 0, 0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 80, 85, 90, 95, 100, 100, 100};
        for (int level = 0; level < expected.length; level++)
            h.assertTrue(DynamicFounding.chancePercent(level, DEFAULTS) == expected[level], "1-7. Level " + level + " -> " + expected[level] + "%, got "
                    + DynamicFounding.chancePercent(level, DEFAULTS));
        h.assertTrue(DynamicFounding.chancePercent(40, DEFAULTS) == 100 && DynamicFounding.chancePercent(1000, DEFAULTS) == 100, "8. Never above 100%");
        h.assertTrue(DynamicFounding.chancePercent(-1, DEFAULTS) == 0, "Unknown level: 0%");
        var custom = new FoundingConfig.Settings(true, 5, 7, 10.0, 2.5, 1500);
        h.assertTrue(DynamicFounding.chancePercent(4, custom) == 0 && DynamicFounding.chancePercent(5, custom) == 10 && DynamicFounding.chancePercent(7, custom) == 15,
                "The formula follows the configured minimum level, base and increase");
        h.assertTrue(DynamicFounding.chancePercent(3, NEVER) == 0 && DynamicFounding.chancePercent(17, NEVER) == 0
                && DynamicFounding.chancePercent(3, ALWAYS) == 100, "16-17. 0% and 100% configurations");
        // 30. The natural-capital system has its own 10% rule.
        h.assertTrue(CapitalConfig.CHANCE_PERCENT.getDefault() == 10.0 && CapitalConfig.CHANCE_PERCENT.get() == 10.0 && CapitalConfig.MIN_DISTANCE.getDefault() == 1000
                && NaturalCapitals.chosen(10.0, 0.0999) && !NaturalCapitals.chosen(10.0, 0.1), "30. Natural capitals: 10% of new natural villages");
        h.succeed();
    }

    @GameTest(templateNamespace = NS, template = "empty", batch = "founding_rules", timeoutTicks = 200)
    public static void evaluationPeriodsFollowTheBookedDay(GameTestHelper h) {
        h.assertTrue(!DynamicFounding.due(-1, 7, 7), "Nothing before the first booked day");
        h.assertTrue(DynamicFounding.due(6, 7, 7) && !DynamicFounding.due(7, 8, 7) && !DynamicFounding.due(7, 13, 7) && DynamicFounding.due(13, 14, 7),
                "One evaluation each 7 days, on days 7, 14, ...");
        h.assertTrue(!DynamicFounding.due(7, 7, 7) && !DynamicFounding.due(20, 15, 7), "21. A repeated or earlier day is never due");
        h.assertTrue(DynamicFounding.due(7, 700, 7), "21. A large skip is one due day (a single evaluation)");
        h.assertTrue(DynamicFounding.nextDueDay(0, 7) == 7 && DynamicFounding.nextDueDay(7, 7) == 14 && DynamicFounding.nextDueDay(13, 7) == 14
                && DynamicFounding.nextDueDay(-1, 7) == 7, "Next evaluation day");
        h.assertTrue(DynamicFounding.due(2, 3, 3) && DynamicFounding.due(0, 1, 1), "Another interval");
        h.succeed();
    }

    /** 1-9 on real plan records: every level of a real town hall, then the states of an unfinished building. */
    @GameTest(templateNamespace = NS, template = "empty", batch = "founding_rules", timeoutTicks = 200)
    public static void realTownHallLevelsAndStatuses(GameTestHelper h) {
        ServerLevel level = h.getLevel(); List<Village> made = new ArrayList<>();
        try {
            VillageType type = longestType(false);
            BuildingPlanSet set = hallSet(type); String variant = longestVariant(set);
            int max = set.getLevelCount(variant) - 1;
            h.assertTrue(max >= 10, "A real town hall with at least 11 levels: " + type.id() + " " + max);
            var d = new KingdomSavedData();
            for (int l = 0; l <= max; l++) {
                Village v = record(level, new BlockPos(700_000 + l * 3000, 70, 700_000), type, set, variant, l, BuildingInstance.Status.COMPLETE, made);
                var r = DynamicFounding.check(level, d, v, DEFAULTS);
                double chance = Math.min(100, Math.max(0, (l - 2) * 5));
                if (l < 3) h.assertTrue(r.outcome() == Outcome.TOO_LOW && r.chance() == 0
                        && DynamicFounding.consider(level, d, v, ALWAYS, () -> 0.0).outcome() == Outcome.TOO_LOW && d.owner(id(v)) == null,
                        "1. Level " + l + " never founds, even at 100%");
                else h.assertTrue(r.outcome() == Outcome.NOT_CHOSEN && r.level() == l && r.chance() == chance,
                        "2-7. Completed level " + l + " is eligible at " + chance + "%: " + r);
            }
            System.out.println("FOUNDING_LEVELS " + type.id() + " variant " + variant + ": levels 0.." + max + " checked on real plans");
            // 9. Upgrading to level 3: Millénaire's own transition (startUpgrade) stores the new level with status UPGRADING.
            Village up = record(level, new BlockPos(760_000, 70, 700_000), type, set, variant, 2, BuildingInstance.Status.COMPLETE, made);
            up.getTownhall().startUpgrade(set.getPlanId(variant, 3), 3);
            var r = DynamicFounding.consider(level, d, up, ALWAYS, () -> 0.0);
            h.assertTrue(up.getTownhall().getLevel() == 3 && r.outcome() == Outcome.NOT_COMPLETE && d.owner(id(up)) == null,
                    "9. A town hall still upgrading to level 3 is deferred: " + r);
            for (var status : List.of(BuildingInstance.Status.PLANNED, BuildingInstance.Status.UNDER_CONSTRUCTION)) {
                Village v = record(level, new BlockPos(763_000, 70, 700_000 + status.ordinal() * 3000), type, set, variant, 5, status, made);
                h.assertTrue(DynamicFounding.check(level, d, v, ALWAYS).outcome() == Outcome.NOT_COMPLETE, "9. Status " + status + " is not completed");
            }
            // Untrustworthy records: a plan that does not belong to the stored level, or a level the plan set does not have.
            BlockPos wrongAt = new BlockPos(770_000, 70, 700_000), beyondAt = new BlockPos(773_000, 70, 700_000);
            Village wrong = bare(level, wrongAt, type, made);
            wrong.addBuilding(new BuildingInstance(BuildingId.random(), set.getPlanId(variant, 2), wrongAt, Rotation.NONE,
                    BuildingInstance.Status.COMPLETE, set.id(), variant, 4));
            Village beyond = bare(level, beyondAt, type, made);
            beyond.addBuilding(new BuildingInstance(BuildingId.random(), set.getPlanId(variant, max), beyondAt, Rotation.NONE,
                    BuildingInstance.Status.COMPLETE, set.id(), variant, max + 1));
            Village none = new Village(VillageId.random(), type.culture(), type.id(), new BlockPos(776_000, 70, 700_000));
            manager(level).addVillage(none); made.add(none);
            h.assertTrue(DynamicFounding.check(level, d, wrong, ALWAYS).outcome() == Outcome.INVALID_RECORD
                    && DynamicFounding.check(level, d, beyond, ALWAYS).outcome() == Outcome.INVALID_RECORD
                    && DynamicFounding.check(level, d, none, ALWAYS).outcome() == Outcome.INVALID_RECORD, "11. Invalid Millénaire records never found");
            printLevelMatrix();
            h.succeed();
        } finally { made.forEach(v -> manager(level).removeVillage(v.getId())); }
    }

    /** 10-13, 18: villages that are not independent natural NPC villages never found, whatever the chance. */
    @GameTest(templateNamespace = NS, template = "empty", batch = "founding_rules", timeoutTicks = 200)
    public static void onlyIndependentNaturalVillagesFound(GameTestHelper h) {
        ServerLevel level = h.getLevel(); List<Village> made = new ArrayList<>();
        try {
            VillageType type = longestType(false); BuildingPlanSet set = hallSet(type); String variant = longestVariant(set);
            var d = new KingdomSavedData();
            int[] x = {800_000};
            java.util.function.Supplier<Village> fresh = () -> { x[0] += 3000;
                return record(level, new BlockPos(x[0], 70, 800_000), type, set, variant, 5, BuildingInstance.Status.COMPLETE, made); };
            Village owned = fresh.get(); owned.setOwner(UUID.randomUUID(), "Owner");
            h.assertTrue(DynamicFounding.consider(level, d, owned, ALWAYS, () -> 0.0).outcome() == Outcome.PLAYER_VILLAGE && d.owner(id(owned)) == null,
                    "10. A player-owned village never founds");
            ModCultures.getAllVillageTypes().values().stream().filter(VillageType::playerControlled).findFirst().ifPresent(pt -> {
                Village pv = record(level, new BlockPos(x[0] += 3000, 70, 800_000), pt, set, variant, 5, BuildingInstance.Status.COMPLETE, made);
                h.assertTrue(DynamicFounding.check(level, d, pv, ALWAYS).outcome() == Outcome.NOT_NATURAL, "10. A player-controlled village type never founds");
            });
            Village capital = fresh.get(); Village member = fresh.get();
            var k = d.create("Founding rule kingdom", id(capital)); d.attach(k.id(), id(member));
            h.assertTrue(DynamicFounding.consider(level, d, member, ALWAYS, () -> 0.0).outcome() == Outcome.IN_KINGDOM && k.id().equals(d.owner(id(member))),
                    "11. A member of another kingdom never founds (no automatic independence)");
            h.assertTrue(DynamicFounding.consider(level, d, capital, ALWAYS, () -> 0.0).outcome() == Outcome.IN_KINGDOM && d.kingdoms().size() == 1,
                    "12. A capital (natural or founded) never founds again");
            Village hamlet = fresh.get(); hamlet.setParentVillageId(capital.getId());
            h.assertTrue(DynamicFounding.check(level, d, hamlet, ALWAYS).outcome() == Outcome.HAMLET, "13. A hamlet never founds");
            ModCultures.getAllVillageTypes().values().stream().filter(VillageType::loneBuilding).findFirst().ifPresent(lt -> {
                Village lone = record(level, new BlockPos(x[0] += 3000, 70, 800_000), lt, set, variant, 5, BuildingInstance.Status.COMPLETE, made);
                h.assertTrue(DynamicFounding.check(level, d, lone, ALWAYS).outcome() == Outcome.NOT_NATURAL, "13. A lone building never founds");
            });
            ModCultures.getAllVillageTypes().values().stream().filter(t -> t.weight() <= 0 && !t.loneBuilding() && !t.playerControlled()).findFirst().ifPresent(nt -> {
                Village nn = record(level, new BlockPos(x[0] += 3000, 70, 800_000), nt, set, variant, 5, BuildingInstance.Status.COMPLETE, made);
                h.assertTrue(DynamicFounding.check(level, d, nn, ALWAYS).outcome() == Outcome.NOT_NATURAL, "A type Millénaire never generates naturally does not found");
            });
            Village applicant = fresh.get();
            d.requestJoin(id(applicant), k.id(), null, "", 1);
            h.assertTrue(DynamicFounding.check(level, d, applicant, ALWAYS).outcome() == Outcome.BUSY, "A village applying to join a kingdom does not found");
            // 18. Founding once: the founder becomes a capital, so a second evaluation finds it in a kingdom.
            Village founder = fresh.get();
            var first = DynamicFounding.consider(level, d, founder, ALWAYS, () -> 0.0);
            h.assertTrue(first.outcome() == Outcome.FOUNDED && first.kingdom() != null, "An eligible village founds at 100%");
            var again = DynamicFounding.consider(level, d, founder, ALWAYS, () -> 0.0);
            h.assertTrue(again.outcome() == Outcome.IN_KINGDOM && d.kingdoms().size() == 2, "18. The same village never founds a second kingdom");
            h.assertTrue(DynamicFounding.check(level, d, fresh.get(), DEFAULTS.withEnabled(false)).outcome() == Outcome.DISABLED, "Disabled in config");
            // 12/18. The former capital of a fallen kingdom never founds again (also if a record ever showed it independent).
            Village oldCapital = fresh.get(), victor = fresh.get();
            var fallen = d.create("Founding fallen", id(oldCapital)); var winner = d.create("Founding victor", id(victor));
            var war = d.declareWar(winner.id(), fallen.id(), 1);
            for (int i = 0; i < KingdomSavedData.REQUIRED_WINS; i++) {
                var raid = d.begin(id(victor), id(oldCapital), 10 + i, null);
                h.assertTrue(d.recordVictory(raid), "Victory recorded");
                d.finish(d.raid(id(oldCapital)), KingdomSavedData.Outcome.LOST);
            }
            h.assertTrue(d.kingdom(fallen.id()).fallen() && winner.id().equals(d.owner(id(oldCapital))) && d.war(war.id()).status() == KingdomSavedData.WarStatus.CONQUERED,
                    "The capital fell");
            h.assertTrue(DynamicFounding.check(level, d, oldCapital, ALWAYS).outcome() == Outcome.IN_KINGDOM, "12. A conquered former capital is a member");
            CompoundTag saved = d.save(new CompoundTag(), level.registryAccess());
            ListTag membership = saved.getList("membership", Tag.TAG_COMPOUND);
            membership.removeIf(t -> ((CompoundTag) t).getUUID("village").equals(id(oldCapital)));
            var detached = KingdomSavedData.load(saved, level.registryAccess());
            h.assertTrue(detached.owner(id(oldCapital)) == null && DynamicFounding.consider(level, detached, oldCapital, ALWAYS, () -> 0.0).outcome() == Outcome.FORMER_CAPITAL,
                    "12. A former capital never founds again, even independent");
            h.succeed();
        } finally { made.forEach(v -> manager(level).removeVillage(v.getId())); }
    }

    /** 14-17: distance to living capitals at the boundary, and the configured chance read from the NeoForge config. */
    @GameTest(templateNamespace = NS, template = "empty", batch = "founding_rules", timeoutTicks = 200)
    public static void distanceBoundaryAndConfiguredChance(GameTestHelper h) {
        ServerLevel level = h.getLevel(); List<Village> made = new ArrayList<>();
        double base = FoundingConfig.BASE_CHANCE.get(), increase = FoundingConfig.INCREASE.get();
        boolean enabled = FoundingConfig.ENABLED.get();
        try {
            VillageType type = longestType(false); BuildingPlanSet set = hallSet(type); String variant = longestVariant(set);
            BlockPos origin = new BlockPos(900_000, 70, 900_000);
            var d = new KingdomSavedData();
            Village capital = record(level, origin, type, set, variant, 3, BuildingInstance.Status.COMPLETE, made);
            d.create("Founding distance capital", id(capital));
            Village near = record(level, origin.offset(999, 0, 0), type, set, variant, 5, BuildingInstance.Status.COMPLETE, made);
            Village diagonal = record(level, origin.offset(600, 0, 799), type, set, variant, 5, BuildingInstance.Status.COMPLETE, made);
            Village far = record(level, origin.offset(1000, 0, 0), type, set, variant, 5, BuildingInstance.Status.COMPLETE, made);
            h.assertTrue(DynamicFounding.consider(level, d, near, ALWAYS, () -> 0.0).outcome() == Outcome.TOO_CLOSE
                    && DynamicFounding.check(level, d, diagonal, ALWAYS).outcome() == Outcome.TOO_CLOSE && d.owner(id(near)) == null, "14. 999 blocks is refused");
            // 16-17 through the real config values.
            FoundingConfig.ENABLED.set(true);
            FoundingConfig.BASE_CHANCE.set(0.0); FoundingConfig.INCREASE.set(0.0);
            var zero = FoundingConfig.current();
            h.assertTrue(zero.baseChancePercent() == 0 && DynamicFounding.consider(level, d, far, zero, () -> 0.0).outcome() == Outcome.NOT_CHOSEN
                    && d.owner(id(far)) == null, "16. 0% in the config: no founding even with sample 0");
            FoundingConfig.BASE_CHANCE.set(100.0);
            var hundred = FoundingConfig.current();
            var r = DynamicFounding.consider(level, d, far, hundred, () -> 0.999999);
            h.assertTrue(r.outcome() == Outcome.FOUNDED && r.kingdom().equals(d.owner(id(far))), "15/17. 1000 blocks is accepted; 100% founds");
            // The new capital counts for the next village too.
            Village second = record(level, origin.offset(1900, 0, 0), type, set, variant, 5, BuildingInstance.Status.COMPLETE, made);
            h.assertTrue(DynamicFounding.check(level, d, second, hundred).outcome() == Outcome.TOO_CLOSE, "A founded capital keeps others 1000 blocks away");
            h.succeed();
        } finally {
            FoundingConfig.BASE_CHANCE.set(base); FoundingConfig.INCREASE.set(increase); FoundingConfig.ENABLED.set(enabled);
            made.forEach(v -> manager(level).removeVillage(v.getId()));
        }
    }

    /** 27: an authentic 1.0.1 save (format 6, from the 1.0.1 live test world) opens unchanged; 29: the K key's command stays. */
    @GameTest(templateNamespace = NS, template = "empty", batch = "founding_rules", timeoutTicks = 200)
    public static void olderSavesAndKeyCommandUnchanged(GameTestHelper h) throws Exception {
        var reg = h.getLevel().registryAccess();
        for (String fixture : List.of("/live-1.0.1-kingdoms.dat", "/living-kingdoms-0.4.0-kingdoms.dat")) {
            CompoundTag old;
            try (var input = FoundingGameTests.class.getResourceAsStream(fixture)) {
                if (input == null) throw new IllegalStateException("Missing fixture " + fixture);
                old = NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap()).getCompound("data");
            }
            var d = KingdomSavedData.load(old, reg);
            CompoundTag again = d.save(new CompoundTag(), reg);
            if (old.getInt("format") == 6) {
                h.assertTrue(canonical(again).equals(canonical(old)) && KingdomSavedData.FORMAT == 7 && !d.kingdoms().isEmpty(),
                        "27. The 1.0.1 save loads and saves back identically; the save format stays 6");
                System.out.println("FOUNDING_SAVE 1.0.1 fixture: " + d.kingdoms().size() + " kingdoms, " + d.wars().size() + " wars, day " + d.day());
            } else h.assertTrue(again.getInt("format") == 6 && d.kingdoms().size() == 2, "27. The 0.4.0 save still migrates");
        }
        var root = h.getLevel().getServer().getCommands().getDispatcher().getRoot();
        h.assertTrue(root.getChild("kingdom") != null && root.getChild("kingdom").getChild("gui") != null
                && root.getChild("kingdoms").getChild("founding") != null, "29. The K key's /kingdom gui command and the founding status command exist");
        h.succeed();
    }

    // ---------------------------------------------------------------- 19, 21: the calendar

    @GameTest(templateNamespace = NS, template = "empty", batch = "founding_calendar", timeoutTicks = 400)
    public static void oneRollPerPeriodWhateverTheClock(GameTestHelper h) {
        ServerLevel level = h.getLevel(); List<Village> made = new ArrayList<>();
        try {
            VillageType type = longestType(false); BuildingPlanSet set = hallSet(type); String variant = longestVariant(set);
            var d = new KingdomSavedData();
            neutralize(level, d, Set.of());
            Village v = record(level, new BlockPos(1_000_000, 70, 1_000_000), type, set, variant, 4, BuildingInstance.Status.COMPLETE, made);
            Counting draws = new Counting(0.999);
            // Chance 0: the village stays eligible, so every evaluation draws exactly one sample for it.
            var never = NEVER;
            List<Long> rolls = new ArrayList<>();
            for (long day = 1; day <= 30; day++) {
                int before = draws.count;
                h.assertTrue(KingdomDay.run(level, d, day, POLITICS_OFF, never, draws), "Day " + day + " runs");
                if (draws.count > before) rolls.add(day);
                h.assertTrue(draws.count - before <= 1, "19. At most one roll per evaluation");
            }
            h.assertTrue(rolls.equals(List.of(3L, 6L, 9L, 12L, 15L, 18L, 21L, 24L, 27L, 30L)), "19. One evaluation per 3-day period: " + rolls);
            int count = draws.count;
            h.assertTrue(!KingdomDay.run(level, d, 30, POLITICS_OFF, never, draws) && !KingdomDay.run(level, d, 28, POLITICS_OFF, never, draws)
                    && !KingdomDay.run(level, d, 3, POLITICS_OFF, never, draws) && draws.count == count, "21. Repeated or earlier days (clock set back) roll nothing");
            h.assertTrue(KingdomDay.run(level, d, 31, POLITICS_OFF, never, draws) && draws.count == count, "Counting resumes after the booked day, same period");
            h.assertTrue(KingdomDay.run(level, d, 1000, POLITICS_OFF, never, draws) && draws.count == count + 1, "21. A jump of 969 days is one roll, no catch-up");
            h.assertTrue(KingdomDay.run(level, d, 1001, POLITICS_OFF, never, draws) && draws.count == count + 1, "The next day is the same period");
            // Founding off: no evaluation at all.
            h.assertTrue(KingdomDay.run(level, d, 1050, POLITICS_OFF, never.withEnabled(false), draws) && draws.count == count + 1, "Disabled: no roll");
            // Then 100%: founding happens on the next period start, once.
            h.assertTrue(KingdomDay.run(level, d, 1051, POLITICS_OFF, ALWAYS, draws) && d.owner(id(v)) == null, "Not a period start");
            h.assertTrue(KingdomDay.run(level, d, 1053, POLITICS_OFF, ALWAYS, draws) && d.isCapital(id(v)), "Founded on the period start");
            long kingdoms = d.kingdoms().size();
            KingdomDay.run(level, d, 1064, POLITICS_OFF, ALWAYS, draws);
            h.assertTrue(d.kingdoms().size() == kingdoms, "No second kingdom later");
            h.succeed();
        } finally { made.forEach(v -> manager(level).removeVillage(v.getId())); }
    }

    // ---------------------------------------------------------------- 20: restart

    @GameTest(templateNamespace = NS, template = "empty", batch = "founding_persist", timeoutTicks = 400)
    public static void foundingSurvivesRestart(GameTestHelper h) throws Exception {
        ServerLevel level = h.getLevel();
        Path file = level.getServer().getWorldPath(LevelResource.ROOT).resolve("founding-test.properties");
        var d = KingdomSavedData.get(level);
        if (!read()) {
            VillageType type = longestType(false); BuildingPlanSet set = hallSet(type); String variant = longestVariant(set);
            Village v = record(level, new BlockPos(1_100_000, 70, 1_100_000), type, set, variant, 6, BuildingInstance.Status.COMPLETE, new ArrayList<>());
            v.setVillageName("Founding restart");
            long day = DynamicFounding.nextDueDay(Math.max(1, d.day()), DEFAULTS.intervalDays());
            // A booked day first (a new world books its first day without evaluating), then the period start.
            if (d.day() < 0) d.startDay(day - 1, Map.of());
            else if (d.day() < day - 1) KingdomDay.run(level, d, day - 1, POLITICS_OFF, NEVER, RandomSource.create(1));
            int before = d.kingdoms().size();
            h.assertTrue(KingdomDay.run(level, d, day, POLITICS_OFF, ALWAYS, RandomSource.create(1)), "The period start runs");
            UUID k = d.owner(id(v));
            h.assertTrue(k != null && d.isCapital(id(v)) && d.kingdoms().size() >= before + 1, "Founded before the restart");
            VillageSavedData.get(level).setDirty();
            Properties p = new Properties();
            p.setProperty("village", id(v).toString()); p.setProperty("kingdom", k.toString()); p.setProperty("day", Long.toString(d.day()));
            p.setProperty("name", d.kingdom(k).name()); p.setProperty("hall", v.getTownhall().getPlanId() + "|" + v.getTownhall().getLevel() + "|" + v.getTownhall().getStatus());
            try (var out = Files.newBufferedWriter(file)) { p.store(out, "founding restart check"); }
            System.out.println("FOUNDING_WRITE_PASS " + p);
            h.succeed();
            return;
        }
        Properties p = new Properties();
        try (var in = Files.newBufferedReader(file)) { p.load(in); }
        UUID village = UUID.fromString(p.getProperty("village")), kingdom = UUID.fromString(p.getProperty("kingdom"));
        long day = Long.parseLong(p.getProperty("day"));
        Village v = manager(level).getVillage(new VillageId(village));
        Kingdom k = d.kingdom(kingdom);
        h.assertTrue(v != null && k != null && k.capital().equals(village) && k.name().equals(p.getProperty("name")) && k.active() && d.npcGoverned(kingdom)
                && d.ruler(kingdom) == null && d.members(kingdom).equals(Set.of(village)), "20. The founded kingdom is kept after the restart");
        h.assertTrue(d.day() >= day, "20. The kingdom calendar is kept: booked day " + d.day() + " >= " + day);
        var hall = v.getTownhall();
        h.assertTrue(p.getProperty("hall").equals(hall.getPlanId() + "|" + hall.getLevel() + "|" + hall.getStatus()) && v.getOwnerUUID() == null,
                "The Millénaire village record is unchanged");
        int kingdoms = d.kingdoms().size();
        h.assertTrue(!KingdomDay.run(level, d, day, POLITICS_OFF, ALWAYS, RandomSource.create(2)) && d.kingdoms().size() == kingdoms,
                "20. The founding day does not run again after the restart");
        h.assertTrue(DynamicFounding.check(level, d, v, ALWAYS).outcome() == Outcome.IN_KINGDOM, "The capital never founds again");
        System.out.println("FOUNDING_READ_PASS kingdom " + k.name() + " day " + d.day());
        h.succeed();
    }

    // ---------------------------------------------------------------- 2, 9, 22, 23: a real village grows, then founds

    @GameTest(templateNamespace = NS, template = "empty", batch = "founding_real", timeoutTicks = 6000)
    public static void realVillageGrowsAndFoundsWithoutAnyChange(GameTestHelper h) {
        if (read()) { h.succeed(); return; }
        ServerLevel level = h.getLevel();
        Millenaire.setNaturalSpawnSuppressed(true);
        VillageType type = ModCultures.getAllVillageTypes().values().stream()
                .filter(t -> NaturalCapitals.eligible(t) && deepestGround(t) >= -4 && maxLevel(hallSet(t)) >= 4)
                .min(Comparator.comparing(t -> t.id().toString())).orElseThrow();
        BlockPos at = new BlockPos(-60_000, 0, 60_000);
        force(level, at, true);
        int[] phase = {0};
        h.onEachTick(() -> {
            if (phase[0] == 0 && loaded(level, at)) {
                phase[0] = 1;
                Village village = null;
                try {
                    village = spawnReal(level, type, at);
                    h.assertTrue(village != null, "Millénaire placed a real " + type.id() + " village");
                    runRealChecks(h, level, village);
                    System.out.println("FOUNDING_REAL_PASS " + type.id());
                } finally {
                    if (village != null) discard(level, village);
                    force(level, at, false);
                }
                phase[0] = 2;
            } else if (phase[0] == 2 && ++phase[0] > 2) {
                phase[0] = 3;
            } else if (phase[0] >= 3 && ++phase[0] > 220) {
                h.succeed(); // released chunks settle before the test ends
            }
        });
    }

    private static void runRealChecks(GameTestHelper h, ServerLevel level, Village village) {
        var d = new KingdomSavedData();
        BuildingInstance hall = village.getTownhall();
        BuildingPlanSet set = ModCultures.getBuildingPlanSet(hall.getPlanSetId());
        List<String> steps = new ArrayList<>();
        h.assertTrue(hall.getLevel() == 0 && hall.getStatus() == BuildingInstance.Status.COMPLETE, "A new village starts with a level-0 town hall");
        for (int target = 0; target <= 2; target++) {
            if (target > 0) h.assertTrue(raise(level, village, hall, target), "Millénaire's rush raised the town hall to level " + target);
            var r = DynamicFounding.consider(level, d, village, ALWAYS, () -> 0.0);
            steps.add("L" + hall.getLevel() + " " + hall.getStatus() + " -> " + r.outcome());
            h.assertTrue(r.outcome() == Outcome.TOO_LOW && d.owner(id(village)) == null, "1. Real town hall at level " + hall.getLevel() + ": no founding");
        }
        // 9. Millénaire starts the upgrade to level 3: the record has level 3 and status UPGRADING until the work is done.
        ResourceLocation level2 = hall.getPlanId();
        hall.startUpgrade(set.getPlanId(hall.getVariant(), 3), 3);
        var deferred = DynamicFounding.consider(level, d, village, ALWAYS, () -> 0.0);
        steps.add("L" + hall.getLevel() + " " + hall.getStatus() + " -> " + deferred.outcome());
        h.assertTrue(deferred.outcome() == Outcome.NOT_COMPLETE && d.owner(id(village)) == null, "9. Upgrading to level 3 is deferred");
        hall.startUpgrade(level2, 2); hall.markComplete(); // back to the completed level 2 record; Millénaire's rush then builds level 3
        h.assertTrue(raise(level, village, hall, 3) && hall.getLevel() == 3 && hall.getStatus() == BuildingInstance.Status.COMPLETE, "Real level 3 built");
        var eligible = DynamicFounding.check(level, d, village, DEFAULTS);
        steps.add("L" + hall.getLevel() + " " + hall.getStatus() + " -> " + eligible.outcome() + " " + eligible.chance() + "%");
        h.assertTrue(eligible.outcome() == Outcome.NOT_CHOSEN && eligible.chance() == 5.0, "2. A real completed level-3 town hall is eligible at 5%");
        h.assertTrue(DynamicFounding.consider(level, d, village, DEFAULTS, () -> 0.05).outcome() == Outcome.NOT_CHOSEN && d.owner(id(village)) == null,
                "A sample of 0.05 misses 5%");
        // 22-23. Everything Millénaire knows and everything in the world stays as it was.
        CompoundTag millenaire = VillageSavedData.get(level).save(new CompoundTag(), level.registryAccess()).copy();
        AABB box = village.computeBounds().inflate(4);
        long blocks = blockHash(level, box);
        Set<UUID> npcs = new HashSet<>(); villagers(level, village).forEach(m -> npcs.add(m.getUUID()));
        Map<BlockPos, CompoundTag> chests = new HashMap<>();
        for (BlockPos p : hall.getInventory() == null ? List.<BlockPos>of() : hall.getInventory().getChestPositions()) {
            var be = level.getBlockEntity(p); if (be != null) chests.put(p, be.saveWithFullMetadata(level.registryAccess()));
        }
        var owner = village.getOwnerUUID(); var culture = village.getCultureId(); var name = village.getVillageName();
        int buildings = village.getBuildings().size(), records = village.getVillagerRecords().size();
        var founded = DynamicFounding.consider(level, d, village, DEFAULTS, () -> 0.0499);
        steps.add("L" + hall.getLevel() + " sample 0.0499 -> " + founded.outcome());
        h.assertTrue(founded.outcome() == Outcome.FOUNDED, "A sample below 0.05 founds at 5%");
        Kingdom k = d.kingdom(founded.kingdom());
        h.assertTrue(k.active() && !k.fallen() && k.capital().equals(id(village)) && d.ruler(k.id()) == null && d.npcGoverned(k.id()) && d.mayDecide(k.id(), null)
                && d.members(k.id()).equals(Set.of(id(village))) && k.name().contains(village.getVillageName()), "The new kingdom: active, NPC-governed, one member");
        Map<BlockPos, CompoundTag> chestsAfter = new HashMap<>();
        for (BlockPos p : chests.keySet()) chestsAfter.put(p, level.getBlockEntity(p).saveWithFullMetadata(level.registryAccess()));
        Set<UUID> npcsAfter = new HashSet<>(); villagers(level, village).forEach(m -> npcsAfter.add(m.getUUID()));
        h.assertTrue(millenaire.equals(VillageSavedData.get(level).save(new CompoundTag(), level.registryAccess())), "22-23. Millénaire's saved data is identical");
        h.assertTrue(blocks == blockHash(level, box) && chests.equals(chestsAfter) && npcs.equals(npcsAfter),
                "22-23. Blocks, storage chests and villagers are unchanged (" + npcs.size() + " villagers, " + chests.size() + " chests)");
        h.assertTrue(java.util.Objects.equals(owner, village.getOwnerUUID()) && culture.equals(village.getCultureId()) && name.equals(village.getVillageName())
                && hall.getLevel() == 3 && hall.getStatus() == BuildingInstance.Status.COMPLETE && buildings == village.getBuildings().size()
                && records == village.getVillagerRecords().size(), "22-23. Owner, culture, name, town hall level and records unchanged (no upgrade)");
        System.out.println("FOUNDING_REAL " + village.getVillageTypeId() + " '" + village.getVillageName() + "' steps: " + steps + " | kingdom '" + k.name()
                + "' | " + buildings + " buildings, " + npcs.size() + " villagers, " + chests.size() + " chests unchanged");
    }

    // ---------------------------------------------------------------- 24-26, 28: the new kingdom in NPC politics

    @GameTest(templateNamespace = NS, template = "empty", batch = "founding_politics", timeoutTicks = 400)
    public static void newKingdomJoinsNpcPoliticsEconomyAndScreen(GameTestHelper h) throws Exception {
        ServerLevel level = h.getLevel(); List<Village> made = new ArrayList<>();
        var d = KingdomSavedData.get(level);
        UUID ruled = null;
        try {
            VillageType type = longestType(true); BuildingPlanSet set = hallSet(type); String variant = longestVariant(set);
            BlockPos o = h.absolutePos(new BlockPos(1, 1, 1));
            Village founder = record(level, o, type, set, variant, 5, BuildingInstance.Status.COMPLETE, made);
            chest(level, founder);
            Village rival = record(level, o.offset(1600, 0, 0), type, set, variant, 8, BuildingInstance.Status.COMPLETE, made);
            Village rivalMember = record(level, o.offset(1650, 0, 40), type, set, variant, 1, BuildingInstance.Status.COMPLETE, made);
            Village neighbour = record(level, o.offset(40, 0, 60), type, set, variant, 2, BuildingInstance.Status.COMPLETE, made);
            Village rivalCandidate = record(level, o.offset(900, 0, 900), type, set, variant, 2, BuildingInstance.Status.COMPLETE, made);
            var rk = d.create("Founding rival " + id(rival).toString().substring(0, 8), id(rival));
            d.attach(rk.id(), id(rivalMember));
            var r = DynamicFounding.consider(level, d, founder, ALWAYS, () -> 0.0);
            h.assertTrue(r.outcome() == Outcome.FOUNDED, "The developed village founds at 1600 blocks from the rival capital: " + r);
            Kingdom k = d.kingdom(r.kingdom());
            h.assertTrue(d.npcGoverned(k.id()) && d.mayDecide(k.id(), null), "24. NPC-governed");
            // 28. The founder is not a joining candidate; the neighbour may join the new kingdom.
            AutonomySupport.relation(founder, neighbour, 60); AutonomySupport.relation(rival, founder, 60);
            h.assertTrue(AutonomyService.joinTarget(level, d, founder) == null, "28. A founded capital is never a joining candidate");
            h.assertTrue(k.equals(AutonomyService.joinTarget(level, d, neighbour))
                    && AutonomyService.decideJoin(level, d, neighbour, AutonomySupport.yes()) == AutonomyService.JoinDecision.JOINED
                    && k.id().equals(d.owner(id(neighbour))), "24. An independent village joins the new kingdom diplomatically");
            // 25. RC.8 economy: founder production and member income both reach the capital.
            h.assertTrue(EconomyService.dailyIncome(level, d, k.id()) == (long) (EconomyService.centerLevel(founder) + EconomyService.centerLevel(neighbour)) * EconomyService.DENIERS_PER_LEVEL
                    && EconomyService.dailyIncome(level, d, k.id()) > 0, "25. Daily capital production plus member-village income");
            // 24-25. War: hostile relations and a ready army; then peace and truce follow the normal rules.
            AutonomySupport.relation(founder, rival, -90);
            for (int i = 0; i < 4; i++) AutonomySupport.raider(founder);
            h.assertTrue(Military.anyReady(level, d, k.id()) && Military.strength(level, d, k.id()) > 0 && AutonomyService.neighbours(level, d, k, rk),
                    "25. The military service sees the new kingdom's army and border");
            var war = AutonomyService.decideWar(level, d, k, Math.max(0, d.day()), AutonomySupport.yes(), AutonomySupport.ON);
            h.assertTrue(war != null && war.attackerKingdom().equals(k.id()) && war.defenderKingdom().equals(rk.id()), "24. The new kingdom declares war on its own");
            var order = AutonomyService.decideCampaign(level, d, k, Math.max(0, d.day()), AutonomySupport.yes(), AutonomySupport.ON);
            h.assertTrue(order != null && order.target().equals(id(rivalMember)) && order.byNpc(), "24. It orders a real conquest raid on an enemy village");
            d.removeOrder(order.id());
            d.peace(war.id(), KingdomSavedData.NPC_PEACE_REASON, 10);
            h.assertTrue(d.truceUntil(k.id(), rk.id()) > 0, "Peace and truce apply");
            // 26. The kingdom screen lists it; a player with enough reputation may take over its rule.
            var player = Stage5Support.player(h, "Founding viewer", founder);
            var view = KingdomScreens.build(player);
            boolean listed = view.getList("kingdoms", Tag.TAG_COMPOUND).stream().anyMatch(t -> ((CompoundTag) t).getUUID("id").equals(k.id()));
            h.assertTrue(listed && view.getUUID("focus").equals(k.id()) && view.getCompound("kingdom").getString("name").equals(k.name())
                    && view.getCompound("kingdom").getList("members", Tag.TAG_COMPOUND).size() == 2, "26. The kingdom screen shows the new kingdom");
            Stage5Support.reputation(h, founder, player.getUUID(), 40000);
            h.assertTrue(Stage5Support.run(player, "kingdom claim") == 1 && player.getUUID().equals(d.ruler(k.id()).player()) && !d.npcGoverned(k.id()),
                    "24. A player with enough reputation takes over its rule");
            ruled = k.id();
            h.assertTrue(AutonomyService.decideWar(level, d, k, Math.max(0, d.day()), AutonomySupport.yes(), AutonomySupport.ON) == null,
                    "Once ruled, NPC autonomy no longer decides for it");
            h.assertTrue(DynamicFounding.check(level, d, rivalCandidate, ALWAYS).outcome() == Outcome.TOO_LOW, "Low villages around stay normal villages");
            h.succeed();
        } finally {
            if (ruled != null) d.clearRuler(ruled);
            made.forEach(v -> manager(level).removeVillage(v.getId()));
        }
    }

    /** 28: on a day that is both an NPC decision day and a founding period start, joining runs first (fixed order). */
    @GameTest(templateNamespace = NS, template = "empty", batch = "founding_order", timeoutTicks = 400)
    public static void joiningAndFoundingNeverCollide(GameTestHelper h) {
        ServerLevel level = h.getLevel(); List<Village> made = new ArrayList<>();
        try {
            VillageType type = longestType(false); BuildingPlanSet set = hallSet(type); String variant = longestVariant(set);
            var d = new KingdomSavedData();
            BlockPos o = new BlockPos(1_200_000, 70, 1_200_000);
            Village capital = record(level, o, type, set, variant, 4, BuildingInstance.Status.COMPLETE, made);
            Village both = record(level, o.offset(1600, 0, 0), type, set, variant, 6, BuildingInstance.Status.COMPLETE, made);
            Village alone = record(level, o.offset(1600, 0, 3000), type, set, variant, 6, BuildingInstance.Status.COMPLETE, made);
            neutralize(level, d, Set.of(capital, both, alone));
            var k = d.create("Founding order kingdom", id(capital));
            int kingdomsBefore = d.kingdoms().size();
            AutonomySupport.relation(capital, both, 90);
            d.startDay(20, Map.of()); d.scheduleDecision(21);
            var joinOnly = AutonomyConfig.Settings.DEFAULTS.with(true, true, false);
            h.assertTrue(DynamicFounding.check(level, d, both, ALWAYS).outcome() == Outcome.NOT_CHOSEN && AutonomyService.joinTarget(level, d, both) != null,
                    "28. The village is both a joining and a founding candidate");
            h.assertTrue(KingdomDay.run(level, d, 21, joinOnly, ALWAYS, AutonomySupport.yes()), "Day 21: decisions and founding");
            h.assertTrue(k.id().equals(d.owner(id(both))) && !d.isCapital(id(both)), "28. Joining first: the village joined and founded nothing");
            h.assertTrue(d.isCapital(id(alone)) && AutonomyService.joinTarget(level, d, alone) == null, "28. The other founded, and is no joining candidate");
            h.assertTrue(d.kingdoms().size() == kingdomsBefore + 1, "Exactly one new kingdom");
            h.succeed();
        } finally { made.forEach(v -> manager(level).removeVillage(v.getId())); }
    }

    // ---------------------------------------------------------------- long-term balance by calendar jump

    /** Calendar-jump simulation with the default 5% + 5 per level, every 3 days. Town hall levels stay fixed (no growth is
     * simulated here; the live test covers real development). Group A: isolated villages (3000 blocks apart, no kingdoms)
     * measure the mean time to founding per level. Group B: a dense region (villages 700 blocks apart, two NPC kingdoms,
     * Millénaire's nightly diplomacy and NPC joining on) measures distance refusals, joining and the number of new kingdoms. */
    @GameTest(templateNamespace = NS, template = "empty", batch = "founding_sim", timeoutTicks = 12000)
    public static void longTermFoundingBalance(GameTestHelper h) {
        if (read()) { h.succeed(); return; }
        ServerLevel level = h.getLevel(); List<Village> made = new ArrayList<>();
        try {
            VillageType type = longestType(true); BuildingPlanSet set = hallSet(type); String variant = longestVariant(set);
            int[] levels = {3, 4, 5, 7, 10, 13, 17};
            // ---- A: isolated, 20 seeds x 7 levels x 4 villages, 728 days (242 evaluations)
            Map<Integer, List<Long>> times = new java.util.TreeMap<>(); Map<Integer, Integer> never = new java.util.TreeMap<>();
            List<Village> isolated = new ArrayList<>();
            for (int i = 0; i < levels.length * 4; i++)
                isolated.add(record(level, new BlockPos(2_000_000 + i * 3000, 70, 2_000_000), type, set, variant, levels[i / 4], BuildingInstance.Status.COMPLETE, made));
            long evalNanos = 0, otherNanos = 0; int evals = 0, others = 0;
            for (int seed = 0; seed < 20; seed++) {
                var d = new KingdomSavedData();
                neutralize(level, d, Set.copyOf(isolated));
                d.startDay(0, Map.of());
                RandomSource random = RandomSource.create(1000 + seed);
                Map<UUID, Long> foundedOn = new HashMap<>();
                for (long day = 1; day <= 728; day++) {
                    // Only the calendar and the founding rule (KingdomDay's own order is covered above): the daily relation
                    // drift between dozens of isolated new kingdoms would only fill the log.
                    long t0 = System.nanoTime();
                    long previous = d.day();
                    d.startDay(day, Map.of());
                    DynamicFounding.onDay(level, d, previous, day, DEFAULTS, random);
                    long spent = System.nanoTime() - t0;
                    if (day % DEFAULTS.intervalDays() != 0) { otherNanos += spent; others++; continue; }
                    evalNanos += spent; evals++;
                    for (Village v : isolated) if (!foundedOn.containsKey(id(v)) && d.isCapital(id(v))) foundedOn.put(id(v), day);
                }
                for (int i = 0; i < isolated.size(); i++) {
                    int l = levels[i / 4]; Long day = foundedOn.get(id(isolated.get(i)));
                    if (day == null) never.merge(l, 1, Integer::sum); else times.computeIfAbsent(l, x -> new ArrayList<>()).add(day);
                }
            }
            double previousMean = Double.MAX_VALUE;
            for (int l : levels) {
                List<Long> t = times.getOrDefault(l, List.of());
                double mean = t.stream().mapToLong(Long::longValue).average().orElse(Double.NaN);
                double p = DynamicFounding.chancePercent(l, DEFAULTS) / 100.0;
                double expected = DEFAULTS.intervalDays() / p; // mean days to the first success at the configured cadence
                long median = t.isEmpty() ? -1 : t.stream().sorted().toList().get(t.size() / 2);
                System.out.printf("FOUNDING_SIM_A level %d chance %.0f%%: founded %d/80 within 728 days, mean %.1f days (expected %.1f, uncapped), median %d, never %d%n",
                        l, p * 100, t.size(), mean, expected, median, never.getOrDefault(l, 0));
                h.assertTrue(t.size() + never.getOrDefault(l, 0) == 80, "Every village counted");
                if (l >= 4) h.assertTrue(mean <= previousMean + DEFAULTS.intervalDays(), "Higher levels found sooner on average (level " + l + ")");
                previousMean = mean;
            }
            System.out.printf("FOUNDING_SIM_A cost: %.3f ms per day with a founding evaluation, %.3f ms without (%d evaluations, %d villages in the world)%n",
                    evalNanos / 1e6 / Math.max(1, evals), otherNanos / 1e6 / Math.max(1, others), evals, manager(level).getAllVillages().size());
            // ---- B: a dense region, 6 x 6 villages 700 blocks apart (levels cycle), two NPC kingdoms, 364 days, 5 seeds
            List<Village> region = new ArrayList<>(); Map<UUID, Integer> levelOf = new HashMap<>();
            int[] cycle = {0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 15};
            for (int i = 0; i < 36; i++) {
                int l = cycle[i % cycle.length];
                Village v = record(level, new BlockPos(3_000_000 + (i % 6) * 700, 70, 3_000_000 + (i / 6) * 700), type, set, variant, l,
                        BuildingInstance.Status.COMPLETE, made);
                for (int r = 0; r < 2; r++) AutonomySupport.raider(v);
                AutonomySupport.defender(v);
                region.add(v); levelOf.put(id(v), l);
            }
            var politics = AutonomyConfig.Settings.DEFAULTS.with(true, true, false); // joining and wars; no conquest raids without real battles
            for (int seed = 0; seed < 5; seed++) {
                for (int i = 0; i < region.size(); i++) for (int j = i + 1; j < region.size(); j++)
                    AutonomySupport.relation(region.get(i), region.get(j), 50); // Millénaire's initial same-culture relation
                var d = new KingdomSavedData();
                neutralize(level, d, Set.copyOf(region));
                var k1 = d.create("Sim old kingdom A" + seed, id(region.get(0)));
                var k2 = d.create("Sim old kingdom B" + seed, id(region.get(35)));
                d.startDay(0, Map.of());
                RandomSource random = RandomSource.create(5000 + seed);
                Map<UUID, Long> foundedOn = new HashMap<>(); int tooClose = 0, rolled = 0; long nanos = 0;
                for (long day = 1; day <= 364; day++) {
                    for (Village v : region) org.millenaire.village.VillageDiplomacyHelper.performNightlyDiplomacyDrift(level, v);
                    long t0 = System.nanoTime();
                    KingdomDay.run(level, d, day, politics, DEFAULTS, random);
                    nanos += System.nanoTime() - t0;
                    if (day % DEFAULTS.intervalDays() == 0) for (Village v : region) {
                        var c = DynamicFounding.check(level, d, v, DEFAULTS);
                        if (c.outcome() == Outcome.TOO_CLOSE) tooClose++;
                    }
                    for (Village v : region) if (!foundedOn.containsKey(id(v)) && d.isCapital(id(v)) && !id(v).equals(k1.capital()) && !id(v).equals(k2.capital()))
                        foundedOn.put(id(v), day);
                }
                long joined = region.stream().filter(v -> d.owner(id(v)) != null && !d.isCapital(id(v))).count();
                Map<Integer, Integer> byLevel = new java.util.TreeMap<>();
                foundedOn.keySet().forEach(v -> byLevel.merge(levelOf.get(v), 1, Integer::sum));
                double meanDay = foundedOn.values().stream().mapToLong(Long::longValue).average().orElse(Double.NaN);
                // Every founded capital keeps the configured1000 blocks from every other living capital.
                List<Village> capitals = region.stream().filter(v -> d.isCapital(id(v))).toList();
                for (int i = 0; i < capitals.size(); i++) for (int j = i + 1; j < capitals.size(); j++) {
                    BlockPos a = capitals.get(i).getCenter(), b = capitals.get(j).getCenter();
                    h.assertTrue(Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ()) >= 1000, "Capitals 1000 blocks apart");
                }
                h.assertTrue(foundedOn.keySet().stream().allMatch(v -> levelOf.get(v) >= 3), "Only level 3+ founds");
                System.out.printf("FOUNDING_SIM_B seed %d: %d new kingdoms (by level %s), mean founding day %.1f, %d too-close refusals at evaluations, "
                                + "%d villages joined kingdoms, %d kingdoms in total, %d wars, %.3f ms per simulated day%n",
                        seed, foundedOn.size(), byLevel, meanDay, tooClose, joined, d.kingdoms().size() - 1, d.wars().size(), nanos / 1e6 / 364);
            }
            h.succeed();
        } finally { made.forEach(v -> manager(level).removeVillage(v.getId())); }
    }

    // ---------------------------------------------------------------- helpers

    /** A RandomSource with a fixed value that counts its nextDouble draws (the founding roll). */
    static final class Counting implements RandomSource {
        final double value; int count;
        Counting(double value) { this.value = value; }
        @Override public RandomSource fork() { return this; }
        @Override public PositionalRandomFactory forkPositional() { return RandomSource.create(1).forkPositional(); }
        @Override public void setSeed(long seed) {}
        @Override public int nextInt() { return 0; }
        @Override public int nextInt(int bound) { return 0; }
        @Override public long nextLong() { return 0; }
        @Override public boolean nextBoolean() { return false; }
        @Override public float nextFloat() { return (float) value; }
        @Override public double nextDouble() { count++; return value; }
        @Override public double nextGaussian() { return 0; }
    }

    static UUID id(Village v) { return v.getId().uuid(); }
    static VillageManager manager(ServerLevel level) { return VillageSavedData.get(level).getVillageManager(); }

    /** Villages of other tests (or kept for the restart check) must not take part in an evaluation on fresh test data: they
     * are attached to an inactive kingdom in that data. */
    static void neutralize(ServerLevel level, KingdomSavedData d, Set<Village> own) {
        Set<UUID> mine = new HashSet<>(); own.forEach(v -> mine.add(id(v)));
        List<Village> strays = manager(level).getAllVillages().stream().filter(v -> !mine.contains(id(v)))
                .sorted(Comparator.comparing(v -> id(v).toString())).toList();
        if (strays.isEmpty()) return;
        var k = d.create("Neutral " + UUID.randomUUID().toString().substring(0, 8), id(strays.get(0)));
        for (Village v : strays.subList(1, strays.size())) if (d.owner(id(v)) == null) d.attach(k.id(), id(v));
        d.setActive(k.id(), false);
    }

    /** Natural village types (the founding type rules) whose town hall has the most levels; raids: only types that send raids. */
    static VillageType longestType(boolean raids) {
        return ModCultures.getAllVillageTypes().values().stream()
                .filter(t -> t.weight() > 0 && !t.playerControlled() && !t.loneBuilding() && (!raids || t.carriesRaid()) && hallSet(t) != null)
                .max(Comparator.comparingInt((VillageType t) -> maxLevel(hallSet(t))).thenComparing(t -> t.id().toString(), Comparator.reverseOrder()))
                .orElseThrow();
    }
    static BuildingPlanSet hallSet(VillageType type) {
        for (VillageType.LayoutSlot slot : type.layout())
            if ("centre".equals(slot.role()) || "townhall".equals(slot.role())) {
                BuildingPlanSet set = ModCultures.getBuildingPlanSet(slot.plan());
                return set != null && set.isTownHall() && !set.variants().isEmpty() ? set : null;
            }
        return null;
    }
    static String longestVariant(BuildingPlanSet set) {
        return set.variants().keySet().stream().sorted().max(Comparator.comparingInt(set::getLevelCount)).orElseThrow();
    }
    static int maxLevel(BuildingPlanSet set) { return set == null ? -1 : set.getLevelCount(longestVariant(set)) - 1; }

    /** A Millénaire village record whose town hall is the real plan of the given level (no blocks are placed). */
    static Village record(ServerLevel level, BlockPos center, VillageType type, BuildingPlanSet set, String variant, int hallLevel,
                          BuildingInstance.Status status, List<Village> made) {
        Village v = new Village(VillageId.random(), type.culture(), type.id(), center);
        v.setVillageName("Founding " + made.size() + " L" + hallLevel);
        v.addBuilding(new BuildingInstance(BuildingId.random(), set.getPlanId(variant, Math.min(hallLevel, set.getLevelCount(variant) - 1)), center,
                Rotation.NONE, status, set.id(), variant, hallLevel));
        manager(level).addVillage(v); made.add(v);
        return v;
    }
    /** A Millénaire village record without buildings. */
    static Village bare(ServerLevel level, BlockPos center, VillageType type, List<Village> made) {
        Village v = new Village(VillageId.random(), type.culture(), type.id(), center);
        v.setVillageName("Founding bare " + made.size());
        manager(level).addVillage(v); made.add(v);
        return v;
    }
    /** A chest at the village centre as its town hall storage (the treasury and screen read it). */
    static void chest(ServerLevel level, Village v) throws Exception {
        level.setBlockAndUpdate(v.getCenter(), Blocks.CHEST.defaultBlockState());
        Field inventory = BuildingInstance.class.getDeclaredField("inventory");
        inventory.setAccessible(true);
        inventory.set(v.getTownhall(), new BuildingInventory(List.of(v.getCenter())));
    }

    /** For the report: the real town hall levels of every natural village type, by culture. */
    static void printLevelMatrix() {
        ModCultures.getAllVillageTypes().values().stream().filter(t -> t.weight() > 0 && !t.playerControlled() && !t.loneBuilding())
                .sorted(Comparator.comparing(t -> t.id().toString())).forEach(t -> {
                    BuildingPlanSet set = hallSet(t);
                    if (set == null) { System.out.println("FOUNDING_MATRIX " + t.id() + " no town hall plan set"); return; }
                    StringBuilder variants = new StringBuilder();
                    set.variants().keySet().stream().sorted().forEach(v -> variants.append(v).append("=").append(set.getLevelCount(v) - 1).append(" "));
                    int max = maxLevel(set);
                    System.out.printf("FOUNDING_MATRIX %s hall %s max levels %s| at max level %d: %.0f%% per evaluation%s%n", t.id(), set.id().getPath(), variants,
                            max, DynamicFounding.chancePercent(max, DEFAULTS), max < 3 ? " (never eligible)" : "");
                });
    }

    // Real village helpers (as in the natural capital tests).
    private static Village spawnReal(ServerLevel level, VillageType type, BlockPos at) {
        Set<VillageId> before = new HashSet<>(); manager(level).getAllVillages().forEach(v -> before.add(v.getId()));
        BlockPos surface = at.atY(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()));
        var failure = VillageSpawner.spawnVillage(level, surface, type, 0);
        if (failure != null) throw new IllegalStateException("Millénaire refused the site: " + failure.getString());
        for (Village v : manager(level).getAllVillages())
            if (!before.contains(v.getId()) && v.getParentVillageId() == null && type.id().equals(v.getVillageTypeId())) return v;
        return null;
    }
    /** Millénaire's own rush upgrades, limited to the town hall and its sub-buildings, until the hall reaches target. */
    private static boolean raise(ServerLevel level, Village village, BuildingInstance hall, int target) {
        BuildingPlanSet set = ModCultures.getBuildingPlanSet(hall.getPlanSetId());
        Set<ResourceLocation> chain = NaturalCapitals.chain(set);
        for (int i = 0; i < 200 && hall.getLevel() < target; i++) {
            for (BuildingInstance b : new ArrayList<>(village.getBuildings()))
                if (b.isSubBuilding() && b.getStatus() == BuildingInstance.Status.PLANNED && hall.getId().equals(b.getParentBuildingId()))
                    SubBuildingHelper.completePlannedSubBuilding(level, village, b);
            Set<ResourceLocation> others = new HashSet<>();
            VillageType type = ModCultures.getVillageType(village.getVillageTypeId());
            type.layout().forEach(s -> others.add(s.plan())); others.addAll(type.playerBuildings());
            village.getBuildings().forEach(b -> { if (b.getPlanSetId() != null) others.add(b.getPlanSetId()); });
            others.removeAll(chain);
            if (!VillageGrowthManager.rushOneProject(level, village, null, others) && i > 20) break;
        }
        BuildingFinalizer.applyVillageUpdates(level, village);
        village.markDirty();
        return hall.getLevel() == target && hall.getStatus() == BuildingInstance.Status.COMPLETE;
    }
    private static int deepestGround(VillageType type) {
        BuildingPlanSet set = hallSet(type);
        int min = 0;
        if (set != null) for (var levels : set.variants().values()) for (var l : levels) min = Math.min(min, ModCultures.getBuildingPlan(l.planId()).groundLevel());
        return min;
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
    private static long blockHash(ServerLevel level, AABB box) {
        long hash = 17;
        for (int x = (int) box.minX; x <= (int) box.maxX; x++) for (int z = (int) box.minZ; z <= (int) box.maxZ; z++)
            for (int y = Math.max(level.getMinBuildHeight(), (int) box.minY - 16); y <= Math.min(level.getMaxBuildHeight() - 1, (int) box.maxY + 32); y++)
                hash = hash * 31 + level.getBlockState(new BlockPos(x, y, z)).hashCode();
        return hash;
    }
    private static Tag canonical(Tag tag) {
        if (tag instanceof CompoundTag c) {
            CompoundTag out = new CompoundTag();
            new java.util.TreeSet<>(c.getAllKeys()).forEach(k -> out.put(k, canonical(c.get(k))));
            return out;
        }
        if (tag instanceof ListTag l) {
            List<String> items = new ArrayList<>(); Map<String, Tag> byText = new HashMap<>();
            for (Tag t : l) { Tag ct = canonical(t); items.add(ct.toString()); byText.put(ct.toString(), ct); }
            items.sort(null);
            ListTag out = new ListTag(); items.forEach(s -> out.add(byText.get(s)));
            return out;
        }
        return tag;
    }
}
