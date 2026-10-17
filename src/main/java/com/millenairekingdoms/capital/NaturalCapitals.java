package com.millenairekingdoms.capital;

import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import org.millenaire.building.BuildingInstance;
import org.millenaire.building.BuildingPlan;
import org.millenaire.building.BuildingPlanSet;
import org.millenaire.building.ClearMargins;
import org.millenaire.culture.Culture;
import org.millenaire.culture.ModCultures;
import org.millenaire.culture.VillageType;
import org.millenaire.village.BuildingFinalizer;
import org.millenaire.village.SubBuildingHelper;
import org.millenaire.village.Village;
import org.millenaire.village.VillageGrowthManager;
import org.millenaire.village.VillageId;
import org.millenaire.village.VillageManager;
import org.millenaire.village.VillageSavedData;
import org.millenaire.world.VillageTerrainMap;
import org.slf4j.Logger;

/** Stage 6.1: a rare new village from Millénaire's natural world generation becomes the capital of its own NPC kingdom.
 * Called only from the natural spawn in VillageSpawnQueue, never for commands, hamlets or player villages, so a village
 * is considered once, at its creation; restarts and chunk reloads never call it again.
 * The town hall reaches its final plan level through Millénaire's own rush upgrades (the code its distant-village
 * completion uses), inside the footprint Millénaire already reserved for it. The kingdom is recorded only afterwards,
 * in one KingdomSavedData snapshot, so a failure leaves a normal village and no political record. */
public final class NaturalCapitals {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Safety bounds mirroring Millénaire's VillageSpawner.stabilizeCompletion. */
    private static final int ROUNDS = 5, RUSHES_PER_ROUND = 100;

    public enum Outcome { FOUNDED, DISABLED, NOT_ELIGIBLE, ALREADY_IN_KINGDOM, NOT_CHOSEN, TOO_CLOSE, NO_SPACE, NOT_DEVELOPED }
    /** kingdom is set only for FOUNDED; levels are 0-based plan levels of the town hall (-1 when unknown). */
    public record Result(Outcome outcome, UUID kingdom, int level, int finalLevel) {
        static Result of(Outcome outcome) { return new Result(outcome, null, -1, -1); }
    }

    private NaturalCapitals() {}

    /** Wraps Millénaire's natural VillageSpawner.spawnVillage call; its result is returned unchanged. */
    public static Component onNaturalSpawn(ServerLevel level, VillageType type, Supplier<Component> spawn) {
        VillageManager manager = null;
        Set<VillageId> before = new HashSet<>();
        try {
            if (type != null && !type.loneBuilding() && CapitalConfig.ENABLED.get() && eligible(type)) {
                manager = VillageSavedData.get(level).getVillageManager();
                manager.getAllVillages().forEach(v -> before.add(v.getId()));
            }
        } catch (RuntimeException e) {
            LOGGER.error("[Kingdoms] Natural capital pre-check failed; spawning a normal village.", e);
            manager = null;
        }
        if (manager == null) return spawn.get();
        Component failure = spawn.get();
        if (failure != null) return failure;
        try {
            Village village = null;
            for (Village v : manager.getAllVillages())
                if (!before.contains(v.getId()) && v.getParentVillageId() == null && type.id().equals(v.getVillageTypeId())) village = v;
            if (village != null) {
                Result result = consider(level, KingdomSavedData.get(level), village, ThreadLocalRandom.current().nextDouble());
                if (result.outcome != Outcome.FOUNDED)
                    LOGGER.info("[Kingdoms] Natural village '{}' ({}) at {}: not a capital ({})", village.getVillageName(),
                            type.id(), village.getCenter().toShortString(), result.outcome);
            }
        } catch (RuntimeException e) {
            // Never disturb Millénaire's own spawn: the village stays a normal village without a political record.
            LOGGER.error("[Kingdoms] Natural capital check failed; the village stays a normal village.", e);
        }
        return null;
    }

    /** The whole rule for one new natural village. sample is a uniform value in [0,1) for the chance roll. */
    public static Result consider(ServerLevel level, KingdomSavedData data, Village village, double sample) {
        if (!CapitalConfig.ENABLED.get()) return Result.of(Outcome.DISABLED);
        VillageType type = ModCultures.getVillageType(village.getVillageTypeId());
        if (!eligible(type) || village.getParentVillageId() != null || village.getOwnerUUID() != null) return Result.of(Outcome.NOT_ELIGIBLE);
        UUID id = village.getId().uuid();
        if (data.owner(id) != null) return Result.of(Outcome.ALREADY_IN_KINGDOM);
        if (!chosen(CapitalConfig.CHANCE_PERCENT.get(), sample)) return Result.of(Outcome.NOT_CHOSEN);
        if (!farFromCapitals(level, data, village.getCenter(), CapitalConfig.MIN_DISTANCE.get())) return Result.of(Outcome.TOO_CLOSE);
        BuildingInstance hall = village.getTownhall();
        String problem = spaceProblem(level, village, hall);
        if (problem != null) {
            LOGGER.info("[Kingdoms] Natural capital refused for '{}': {}.", village.getVillageName(), problem);
            return Result.of(Outcome.NO_SPACE);
        }
        int finalLevel = finalLevel(hall);
        if (!develop(level, village, hall)) {
            LOGGER.warn("[Kingdoms] Natural capital refused: town hall of '{}' reached plan level {} of {}.",
                    village.getVillageName(), hall.getLevel(), finalLevel);
            return new Result(Outcome.NOT_DEVELOPED, null, hall.getLevel(), finalLevel);
        }
        KingdomSavedData.Kingdom kingdom = data.create(name(data, village, type), id);
        LOGGER.info("[Kingdoms] Natural capital founded: kingdom '{}' ({}), capital '{}' ({}) at {}, {} town hall {} at plan level {}/{}.",
                kingdom.name(), kingdom.id(), village.getVillageName(), id, village.getCenter().toShortString(), type.id(),
                hall.getPlanId(), hall.getLevel(), finalLevel);
        return new Result(Outcome.FOUNDED, kingdom.id(), hall.getLevel(), finalLevel);
    }

    /** sample in [0,1): 0% never, 100% always. */
    public static boolean chosen(double chancePercent, double sample) { return sample * 100.0 < chancePercent; }

    /** At least minDistance blocks (horizontal) from the centre of every living capital Millénaire still knows. */
    public static boolean farFromCapitals(ServerLevel level, KingdomSavedData data, BlockPos pos, int minDistance) {
        VillageManager manager = VillageSavedData.get(level).getVillageManager();
        for (KingdomSavedData.Kingdom k : data.kingdoms()) {
            if (k.fallen()) continue;
            Village capital = manager.getVillage(new VillageId(k.capital()));
            if (capital == null) continue;
            BlockPos c = capital.getCenter();
            if (Math.hypot(c.getX() - pos.getX(), c.getZ() - pos.getZ()) < minDistance) return false;
        }
        return true;
    }

    /** A natural village type whose town hall has real upgrade levels that all stay inside the level-0 footprint:
     * every level of the town hall and of its sub-buildings (placed by Millénaire at the town hall's own origin) has the
     * same width and depth, and every plan exists. Lone buildings, player villages and hamlet-only types are excluded. */
    public static boolean eligible(VillageType type) {
        if (type == null || type.loneBuilding() || type.playerControlled() || type.weight() <= 0) return false;
        BuildingPlanSet hall = centre(type);
        if (hall == null || !hall.isTownHall() || hall.variants().isEmpty()) return false;
        Set<List<Integer>> footprints = new HashSet<>();
        for (List<BuildingPlanSet.LevelDef> levels : hall.variants().values()) {
            if (levels.size() < 2) return false;
            List<Integer> first = List.of(levels.get(0).width(), levels.get(0).depth());
            for (BuildingPlanSet.LevelDef l : levels)
                if (!first.equals(List.of(l.width(), l.depth())) || ModCultures.getBuildingPlan(l.planId()) == null) return false;
            footprints.add(first);
        }
        Set<ResourceLocation> chain = chain(hall);
        for (VillageType.LayoutSlot slot : type.layout())
            if (!isCentre(slot.role()) && chain.contains(slot.plan())) return false;
        for (ResourceLocation subId : chain) {
            if (subId.equals(hall.id())) continue;
            BuildingPlanSet sub = ModCultures.getBuildingPlanSet(subId);
            if (sub == null || footprints.size() != 1) return false;
            for (List<BuildingPlanSet.LevelDef> levels : sub.variants().values())
                for (BuildingPlanSet.LevelDef l : levels)
                    if (!footprints.contains(List.of(l.width(), l.depth())) || ModCultures.getBuildingPlan(l.planId()) == null) return false;
        }
        return true;
    }

    /** The town hall's last plan level for its variant, or -1. */
    public static int finalLevel(BuildingInstance hall) {
        BuildingPlanSet set = hall == null || hall.getPlanSetId() == null ? null : ModCultures.getBuildingPlanSet(hall.getPlanSetId());
        return set == null || hall.getVariant() == null ? -1 : set.getLevelCount(hall.getVariant()) - 1;
    }

    /** The completed town hall stands alone in its footprint (only its own sub-buildings share it), every level fits the
     * world height and the whole footprint is loaded. Millénaire reserved that footprint when it placed the village. */
    public static boolean spaceFor(ServerLevel level, Village village, BuildingInstance hall) { return spaceProblem(level, village, hall) == null; }

    /** Why the town hall cannot be raised in place, or null when it can. */
    public static String spaceProblem(ServerLevel level, Village village, BuildingInstance hall) {
        if (hall == null || hall.isSubBuilding() || hall.getStatus() != BuildingInstance.Status.COMPLETE) return "no completed town hall";
        if (finalLevel(hall) <= hall.getLevel()) return "town hall has no higher level";
        BuildingPlanSet set = ModCultures.getBuildingPlanSet(hall.getPlanSetId());
        BuildingPlan current = ModCultures.getBuildingPlan(hall.getPlanId());
        if (set == null || !set.isTownHall() || current == null) return "unknown town hall plan";
        var rect = footprint(hall, current);
        if (!level.hasChunksAt(new BlockPos(rect.startX(), 0, rect.startZ()),
                new BlockPos(rect.startX() + rect.width() - 1, 0, rect.startZ() + rect.depth() - 1))) return "footprint not loaded";
        // Plans keep their own ground level (cellars reach below the surface); sub-buildings are placed at the town hall origin.
        int base = hall.getOrigin().getY() - current.groundLevel(), tallestSub = 0;
        for (ResourceLocation id : chain(set)) {
            BuildingPlanSet sub = id.equals(set.id()) ? null : ModCultures.getBuildingPlanSet(id);
            if (sub != null) for (List<BuildingPlanSet.LevelDef> levels : sub.variants().values())
                for (BuildingPlanSet.LevelDef l : levels) tallestSub = Math.max(tallestSub, ModCultures.getBuildingPlan(l.planId()).height());
        }
        for (BuildingPlanSet.LevelDef l : set.variants().get(hall.getVariant())) {
            BuildingPlan plan = ModCultures.getBuildingPlan(l.planId());
            int y = base + plan.groundLevel();
            if (y < level.getMinBuildHeight() || y + Math.max(plan.height(), tallestSub) > level.getMaxBuildHeight())
                return "level " + l.level() + " leaves the world height (from y " + y + ")";
        }
        for (BuildingInstance other : village.getBuildings()) {
            if (other == hall || hall.getId().equals(other.getParentBuildingId())) continue;
            BuildingPlan plan = ModCultures.getBuildingPlan(other.getPlanId());
            if (plan == null) return "unknown plan " + other.getPlanId();
            var o = footprint(other, plan);
            if (o.startX() < rect.startX() + rect.width() && rect.startX() < o.startX() + o.width()
                    && o.startZ() < rect.startZ() + rect.depth() && rect.startZ() < o.startZ() + o.depth())
                return other.getPlanId().getPath() + " overlaps the town hall footprint";
        }
        return null;
    }

    /** Raises the town hall to its final level with Millénaire's public rush API, exactly as its natural completion does,
     * but only for the town hall and its sub-buildings: every other plan set is excluded from the rush.
     * Returns whether the final level was reached. */
    public static boolean develop(ServerLevel level, Village village, BuildingInstance hall) {
        BuildingPlanSet set = ModCultures.getBuildingPlanSet(hall.getPlanSetId());
        Set<ResourceLocation> chain = chain(set);
        boolean any = false;
        for (int round = 0; round < ROUNDS; round++) {
            boolean changed = false;
            for (BuildingInstance b : new ArrayList<>(village.getBuildings()))
                if (b.isSubBuilding() && b.getStatus() == BuildingInstance.Status.PLANNED && hall.getId().equals(b.getParentBuildingId())) {
                    SubBuildingHelper.completePlannedSubBuilding(level, village, b);
                    changed = true;
                }
            // rushOneProject clears the set after a success, so each call gets a fresh one.
            for (int i = 0; i < RUSHES_PER_ROUND && VillageGrowthManager.rushOneProject(level, village, null, others(village, chain)); i++) changed = true;
            any |= changed;
            if (!changed) break;
        }
        if (any) {
            // The same village-wide updates VillageSpawner.spawnVillage runs after its own completion rush.
            village.updatePens(level, true);
            village.getPathManager().recalculatePaths(level, village, true);
            BuildingFinalizer.applyVillageUpdates(level, village);
            village.markDirty();
            VillageSavedData.get(level).setDirty();
        }
        int last = finalLevel(hall);
        return hall.getStatus() == BuildingInstance.Status.COMPLETE && hall.getLevel() == last && last > 0
                && hall.getPlanId().equals(set.getPlanId(hall.getVariant(), last));
    }

    /** "Kingdom of <village>", then "<Culture> Kingdom of <village>", then numbered (II, III, ...);
     * unique among all kingdoms of the dimension (fallen ones included) and at most 64 characters. */
    public static String name(KingdomSavedData data, Village village, VillageType type) {
        Set<String> taken = new HashSet<>();
        data.kingdoms().forEach(k -> taken.add(k.name().toLowerCase(Locale.ROOT)));
        String base = village.getVillageName() == null || village.getVillageName().isBlank() ? type.name() : village.getVillageName().strip();
        Culture culture = ModCultures.getCulture(type.culture());
        List<String> heads = new ArrayList<>(List.of("Kingdom of "));
        if (culture != null && culture.displayName() != null && !culture.displayName().isBlank())
            heads.add(culture.displayName().strip() + " Kingdom of ");
        for (int n = 1; ; n++) {
            String suffix = n == 1 ? "" : " " + numeral(n);
            for (String head : n == 1 ? heads : heads.subList(0, 1)) {
                int room = 64 - head.length() - suffix.length();
                String name = head + (base.length() > room ? base.substring(0, room).strip() : base) + suffix;
                if (!taken.contains(name.toLowerCase(Locale.ROOT))) return name;
            }
        }
    }
    private static String numeral(int n) {
        String[] r = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return n < r.length ? r[n] : Integer.toString(n);
    }

    private static BuildingPlanSet centre(VillageType type) {
        for (VillageType.LayoutSlot slot : type.layout())
            if (isCentre(slot.role())) return ModCultures.getBuildingPlanSet(slot.plan());
        return null;
    }
    private static boolean isCentre(String role) { return "centre".equals(role) || "townhall".equals(role); }

    /** The town hall plan set and every sub-building it can place, resolved as Millénaire's SubBuildingHelper does. */
    public static Set<ResourceLocation> chain(BuildingPlanSet hall) {
        Set<ResourceLocation> chain = new HashSet<>();
        chain.add(hall.id());
        List<String> keys = new ArrayList<>(hall.startingSubBuildings());
        hall.variants().values().forEach(levels -> levels.forEach(l -> keys.addAll(l.subBuildings())));
        for (String key : keys)
            chain.add(ResourceLocation.fromNamespaceAndPath("millenaire", hall.culture().getPath() + "/" + key.toLowerCase()));
        return chain;
    }

    /** Every plan set the rush could otherwise pick for this village (new buildings and upgrades), minus the town hall chain. */
    private static Set<ResourceLocation> others(Village village, Set<ResourceLocation> chain) {
        Set<ResourceLocation> out = new HashSet<>();
        VillageType type = ModCultures.getVillageType(village.getVillageTypeId());
        if (type != null) {
            type.layout().forEach(s -> out.add(s.plan()));
            out.addAll(type.playerBuildings());
        }
        village.getBuildings().forEach(b -> { if (b.getPlanSetId() != null) out.add(b.getPlanSetId()); });
        out.removeAll(chain);
        return out;
    }

    private static VillageTerrainMap.FootprintRect footprint(BuildingInstance b, BuildingPlan plan) {
        return VillageTerrainMap.computeFootprintRect(b.getOrigin().getX(), b.getOrigin().getZ(), plan.width(), plan.depth(),
                ClearMargins.symmetric(0), b.getRotation());
    }
}
