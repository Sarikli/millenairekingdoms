package com.millenairekingdoms.capital;

import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import org.millenaire.building.BuildingInstance;
import org.millenaire.building.BuildingPlanSet;
import org.millenaire.culture.ModCultures;
import org.millenaire.culture.VillageType;
import org.millenaire.village.Village;
import org.millenaire.village.VillageSavedData;
import org.slf4j.Logger;

/** 1.1.0: an independent NPC village that developed through Millénaire's own construction may found its own NPC kingdom.
 *
 * <p>Evaluated from KingdomDay once per founding period (checkIntervalDays, 3 by default): when a booked kingdom day starts
 * a new period, every village eligible at that moment gets one chance roll. The period follows from the persisted ledger
 * day, which KingdomDay books before anything else, so a restart never repeats a period, a clock set back runs nothing
 * until the booked day is passed again, and a large time skip counts as one period (no catch-up rolls).
 *
 * <p>Everything is read from Millénaire's persistent village records (town hall plan set, variant, level, status), which
 * exist whether or not the village is loaded; no chunk is loaded. A record that cannot be trusted (unknown plan set, plan
 * not matching its level) or a town hall that is not COMPLETE is skipped until the next period.
 *
 * <p>Founding is a political event only: one KingdomSavedData.create snapshot (kingdom active, no ruler, the village as its
 * capital and only member). No Millénaire data is touched. A village that was ever a capital (natural capitals and fallen
 * kingdoms included) never founds again. */
public final class DynamicFounding {
    private static final Logger LOGGER = LogUtils.getLogger();

    public enum Outcome {
        FOUNDED, DISABLED,
        /** Unknown type, a type Millénaire never generates naturally, a player-controlled type or a lone building. */
        NOT_NATURAL,
        /** Owned by a player (Millénaire ownerUUID). */
        PLAYER_VILLAGE, HAMLET, IN_KINGDOM, FORMER_CAPITAL,
        /** In a conquest raid, targeted by a pending raid order, or applying to join a kingdom. */
        BUSY,
        /** No town hall, or a town hall record whose plan set, variant or plan does not match its level. */
        INVALID_RECORD,
        /** The town hall is not COMPLETE (an upgrade is in progress): deferred to the next period. */
        NOT_COMPLETE, TOO_LOW, TOO_CLOSE, NOT_CHOSEN,
        /** The kingdom record was refused (for example a name clash): nothing was recorded. */
        FAILED
    }
    /** level: the completed 0-based town hall level (-1 when unknown); chance in percent (0 when not eligible). */
    public record Result(Outcome outcome, int level, double chance, UUID kingdom) {
        static Result of(Outcome outcome, int level) { return new Result(outcome, level, 0, null); }
        /** Passed every rule except, possibly, the distance and the roll. */
        public boolean eligible() { return outcome == Outcome.FOUNDED || outcome == Outcome.NOT_CHOSEN || outcome == Outcome.TOO_CLOSE; }
    }
    /** One evaluation: villages looked at, results by outcome, and eligible villages / foundings by town hall level. */
    public record Report(long day, int villages, TreeMap<Outcome, Integer> outcomes, TreeMap<Integer, int[]> byLevel, List<UUID> founded) {
        public static final Report NONE = new Report(-1, 0, new TreeMap<>(), new TreeMap<>(), List.of());
        public int count(Outcome o) { return outcomes.getOrDefault(o, 0); }
        public int rolled() { return count(Outcome.FOUNDED) + count(Outcome.NOT_CHOSEN); }
    }

    private DynamicFounding() {}

    /** Chance in percent for a completed town hall level: 0 below the minimum, then base + steps * increase, within 0..100. */
    public static double chancePercent(int completedLevel, FoundingConfig.Settings s) {
        if (completedLevel < s.minimumLevel()) return 0;
        return Math.max(0, Math.min(100, s.baseChancePercent() + (double) (completedLevel - s.minimumLevel()) * s.increasePerLevel()));
    }

    /** Whether the booked day `day`, following the previously booked `previousDay`, starts a new founding period.
     * Nothing is due before the first booked day (previousDay -1) or for a day that is not later. */
    public static boolean due(long previousDay, long day, int intervalDays) {
        if (previousDay < 0 || day <= previousDay) return false;
        int n = Math.max(1, intervalDays);
        return Math.floorDiv(day, n) > Math.floorDiv(previousDay, n);
    }
    /** The first kingdom day after `bookedDay` that starts a new founding period. */
    public static long nextDueDay(long bookedDay, int intervalDays) {
        int n = Math.max(1, intervalDays);
        return (Math.floorDiv(Math.max(0, bookedDay), n) + 1) * n;
    }

    /** Called by KingdomDay after the day was booked (and after NPC politics): evaluates when the day starts a new period. */
    public static Report onDay(ServerLevel level, KingdomSavedData data, long previousDay, long day, FoundingConfig.Settings s, RandomSource random) {
        if (!s.enabled() || !due(previousDay, day, s.intervalDays())) return Report.NONE;
        return evaluate(level, data, day, s, random);
    }

    /** One evaluation over every village Millénaire knows, in UUID order; each eligible village draws one sample. A kingdom
     * founded earlier in the same evaluation counts for the distance rule of the following villages. */
    public static Report evaluate(ServerLevel level, KingdomSavedData data, long day, FoundingConfig.Settings s, RandomSource random) {
        List<Village> villages = new ArrayList<>(VillageSavedData.get(level).getVillageManager().getAllVillages());
        villages.sort(Comparator.comparing(v -> v.getId().uuid().toString()));
        TreeMap<Outcome, Integer> outcomes = new TreeMap<>();
        TreeMap<Integer, int[]> byLevel = new TreeMap<>();
        List<UUID> founded = new ArrayList<>();
        for (Village v : villages) {
            Result r;
            try { r = consider(level, data, v, s, random::nextDouble); }
            catch (RuntimeException e) {
                // A damaged village record never stops the evaluation; that village is skipped this period.
                LOGGER.error("[Kingdoms] Founding check of village {} failed; it is skipped this period.", v.getId().uuid(), e);
                r = Result.of(Outcome.INVALID_RECORD, -1);
            }
            outcomes.merge(r.outcome(), 1, Integer::sum);
            if (r.eligible()) {
                int[] counts = byLevel.computeIfAbsent(r.level(), l -> new int[3]);
                counts[0]++;
                if (r.outcome() == Outcome.TOO_CLOSE) counts[2]++;
                if (r.outcome() == Outcome.FOUNDED) { counts[1]++; founded.add(r.kingdom()); }
            }
        }
        Report report = new Report(day, villages.size(), outcomes, byLevel, List.copyOf(founded));
        StringBuilder levels = new StringBuilder();
        byLevel.forEach((l, c) -> levels.append(levels.isEmpty() ? "" : ", ").append("L").append(l).append(" ").append(c[0]).append("/").append(c[1]).append("/").append(c[2]));
        LOGGER.info("[Kingdoms] Founding evaluation day {}: {} villages, {} rolled, {} founded, {} too close, {} upgrading, {} too low; eligible/founded/too close by level: [{}].",
                day, report.villages(), report.rolled(), report.count(Outcome.FOUNDED), report.count(Outcome.TOO_CLOSE), report.count(Outcome.NOT_COMPLETE),
                report.count(Outcome.TOO_LOW), levels);
        return report;
    }

    /** The whole rule for one village. sample supplies a uniform value in [0,1), drawn only when every other rule passed. */
    public static Result consider(ServerLevel level, KingdomSavedData data, Village village, FoundingConfig.Settings s,
                                  java.util.function.DoubleSupplier sample) {
        Result check = check(level, data, village, s);
        if (check.outcome() != Outcome.NOT_CHOSEN) return check;
        if (!NaturalCapitals.chosen(check.chance(), sample.getAsDouble())) return check;
        VillageType type = ModCultures.getVillageType(village.getVillageTypeId());
        UUID id = village.getId().uuid();
        KingdomSavedData.Kingdom kingdom;
        try { kingdom = data.create(NaturalCapitals.name(data, village, type), id); }
        catch (IllegalArgumentException refused) {
            // create publishes one snapshot or nothing: no partial political record is left.
            LOGGER.warn("[Kingdoms] Village '{}' could not found a kingdom: {}", village.getVillageName(), refused.getMessage());
            return new Result(Outcome.FAILED, check.level(), check.chance(), null);
        }
        BuildingInstance hall = village.getTownhall();
        LOGGER.info("[Kingdoms] Kingdom founded by development: '{}' ({}), capital '{}' ({}) at {}, {} town hall {} at completed level {} (chance {}%).",
                kingdom.name(), kingdom.id(), village.getVillageName(), id, village.getCenter().toShortString(), type.id(), hall.getPlanId(),
                check.level(), check.chance());
        return new Result(Outcome.FOUNDED, check.level(), check.chance(), kingdom.id());
    }

    /** Every rule except the roll: NOT_CHOSEN with the chance means "eligible, the roll decides"; nothing is changed. */
    public static Result check(ServerLevel level, KingdomSavedData data, Village village, FoundingConfig.Settings s) {
        if (!s.enabled()) return Result.of(Outcome.DISABLED, -1);
        VillageType type = ModCultures.getVillageType(village.getVillageTypeId());
        if (type == null || type.weight() <= 0 || type.playerControlled() || type.loneBuilding()) return Result.of(Outcome.NOT_NATURAL, -1);
        if (village.getOwnerUUID() != null) return Result.of(Outcome.PLAYER_VILLAGE, -1);
        if (village.getParentVillageId() != null) return Result.of(Outcome.HAMLET, -1);
        UUID id = village.getId().uuid();
        if (data.owner(id) != null) return Result.of(Outcome.IN_KINGDOM, -1);
        if (data.kingdoms().stream().anyMatch(k -> k.capital().equals(id))) return Result.of(Outcome.FORMER_CAPITAL, -1);
        if (data.involves(id) || data.orderAgainst(id) != null || data.joinRequest(id) != null) return Result.of(Outcome.BUSY, -1);
        BuildingInstance hall = village.getTownhall();
        if (!validTownhall(hall)) return Result.of(Outcome.INVALID_RECORD, -1);
        if (hall.getStatus() != BuildingInstance.Status.COMPLETE) return Result.of(Outcome.NOT_COMPLETE, hall.getLevel());
        int completed = hall.getLevel();
        if (completed < s.minimumLevel()) return Result.of(Outcome.TOO_LOW, completed);
        double chance = chancePercent(completed, s);
        if (!NaturalCapitals.farFromCapitals(level, data, village.getCenter(), s.minCapitalDistance())) return new Result(Outcome.TOO_CLOSE, completed, chance, null);
        return new Result(Outcome.NOT_CHOSEN, completed, chance, null);
    }

    /** A main town hall whose record agrees with Millénaire's plan data: a town hall plan set, a known variant, and the
     * plan of exactly its stored level (so the plan really has that level). */
    static boolean validTownhall(BuildingInstance hall) {
        if (hall == null || hall.isSubBuilding() || hall.getPlanSetId() == null || hall.getVariant() == null || hall.getLevel() < 0) return false;
        BuildingPlanSet set = ModCultures.getBuildingPlanSet(hall.getPlanSetId());
        if (set == null || !set.isTownHall() || !set.variants().containsKey(hall.getVariant())) return false;
        if (hall.getLevel() >= set.getLevelCount(hall.getVariant())) return false;
        return hall.getPlanId() != null && hall.getPlanId().equals(set.getPlanId(hall.getVariant(), hall.getLevel()))
                && ModCultures.getBuildingPlan(hall.getPlanId()) != null;
    }
}
