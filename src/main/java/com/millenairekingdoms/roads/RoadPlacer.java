package com.millenairekingdoms.roads;

import com.millenairekingdoms.roads.RoadSavedData.RoadSegment;
import com.millenairekingdoms.roads.RoadSavedData.Waypoint;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Container;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import org.millenaire.block.ModBlocks;
import org.millenaire.building.BuildingInstance;
import org.millenaire.culture.ModCultures;
import org.millenaire.culture.VillageType;
import org.millenaire.village.Village;
import org.millenaire.village.VillageSavedData;
import org.millenaire.village.path.PathMaterials;
import org.millenaire.village.path.PathMaterials.MaterialPair;

/**
 * Per-step, chunk-safe physical realizer for Royal Roads:
 * <ul>
 *   <li>Never force-loads or generates unloaded chunks.</li>
 *   <li>Freezes planned 8-connected {@code (X, Z)} step coordinates across chunk boundaries with smooth
 *       {@code max ±1} {@code Y}-clamping and automatic cut/fill grading.</li>
 *   <li>Tracks per-step realization in {@link RoadSegment#realizedSteps()} ({@code BitSet}) and triggers on both
 *       {@code constructedStepIndex} advancement and {@code ChunkEvent.Load}.</li>
 *   <li>Ignores aquatic vegetation ({@code lily_pad}, {@code seagrass}, {@code tall_seagrass}, {@code kelp},
 *       {@code kelp_plant}) when detecting water and builds contiguous water crossings + 2-step bank abutments
 *       in a single atomic pass once all span chunks are loaded.</li>
 *   <li>Protects Millénaire building bounding boxes (while allowing flush Border Gate threshold connections),
 *       farm crops, and player-placed containers.</li>
 * </ul>
 */
public final class RoadPlacer {
    private static final int TREE_CANOPY_RADIUS = 4;
    private static final int HEADROOM_HEIGHT = 5;
    private static final int MILESTONE_INTERVAL = 90;
    private static final int MAX_BACKFILL_DEPTH = 16;
    /** Village centers farther than this from a road's bounding box cannot own buildings next to it. */
    private static final int VILLAGE_PROTECTION_RADIUS = 192;

    public record RoadStyle(Block fullBlock, SlabBlock slabBlock, Block bridgeDeck, SlabBlock bridgeSlab) {}

    private record ColumnInfo(
            int groundY,
            int waterY,
            int walkY,
            int waterDepth,
            boolean water,
            boolean alreadyBridge,
            boolean ocean,
            boolean lava,
            boolean protectedCol) {}

    private record BridgeCell(int x, int z, int y, boolean bottomSlab, boolean isCore) {}

    private RoadPlacer() {}

    /** Resolves the Millénaire cultural road blocks for a segment's source culture/village type. */
    public static RoadStyle styleFor(RoadSegment segment) {
        MaterialPair chosen = null;
        ResourceLocation typeId = parseLocation(segment.villageTypeId());
        if (typeId != null) {
            VillageType vt = ModCultures.getVillageType(typeId);
            if (vt != null && vt.pathMaterials() != null && !vt.pathMaterials().isEmpty()) {
                List<String> mats = vt.pathMaterials();
                String key = mats.get(mats.size() - 1);
                chosen = PathMaterials.resolve(key);
                if (chosen == null && mats.size() > 1)
                    chosen = PathMaterials.resolve(mats.get(0));
            }
        }
        if (chosen == null)
            chosen = PathMaterials.resolve("pathgravel");
        Block full = chosen != null && chosen.fullBlock() != null ? chosen.fullBlock() : ModBlocks.PATH_GRAVEL.get();
        SlabBlock slab = chosen != null && chosen.slabBlock() != null ? chosen.slabBlock() : ModBlocks.PATH_GRAVEL_SLAB.get();
        return new RoadStyle(full, slab, Blocks.OAK_PLANKS, (SlabBlock) Blocks.OAK_SLAB);
    }

    /**
     * Resolves per-coordinate biome-adaptive road materials: snow-culture roads keep snow paths in cold
     * snow biomes ({@code coldEnoughToSnow || baseTemperature < 0.15f}) and automatically switch to warm/temperate
     * Millénaire paths ({@code path_dirt} in swamps, {@code path_gravel} in temperate biomes) elsewhere.
     */
    public static RoadStyle styleForPos(ServerLevel level, BlockPos pos, RoadStyle baseStyle) {
        if (baseStyle.fullBlock() != ModBlocks.PATH_SNOW.get()
                && baseStyle.slabBlock() != ModBlocks.PATH_SNOW_SLAB.get()) {
            return baseStyle;
        }
        Holder<Biome> biomeHolder = level.getBiome(pos);
        Biome biome = biomeHolder.value();
        if (biome.coldEnoughToSnow(pos) || biome.getBaseTemperature() < 0.15f) {
            return baseStyle;
        }
        if (RoadPlanner.isSwampBiome(biomeHolder)) {
            return new RoadStyle(
                    ModBlocks.PATH_DIRT.get(), ModBlocks.PATH_DIRT_SLAB.get(),
                    baseStyle.bridgeDeck(), baseStyle.bridgeSlab());
        }
        return new RoadStyle(
                ModBlocks.PATH_GRAVEL.get(), ModBlocks.PATH_GRAVEL_SLAB.get(),
                baseStyle.bridgeDeck(), baseStyle.bridgeSlab());
    }

    /**
     * Dual-trigger entry point: realizes all currently loaded, unrealized steps up to {@code constructedStepIndex}
     * across every road segment in {@code roadData}. Never force-loads unloaded chunks.
     */
    public static int realizeAvailableSteps(ServerLevel level, RoadSavedData roadData) {
        if (level == null || roadData == null)
            return 0;
        int totalPlaced = 0;
        boolean dirty = false;
        for (RoadSegment seg : roadData.allSegments()) {
            if (seg.status() == RoadSavedData.Status.CANCELLED && seg.completedBlocks() <= 0
                    && seg.constructedStepIndex() <= 0) {
                continue;
            }
            if (isFullyRealized(seg))
                continue;
            int beforeCard = seg.realizedSteps().cardinality();
            int placed = realizeAvailableSteps(level, seg);
            totalPlaced += placed;
            if (placed > 0 || seg.realizedSteps().cardinality() != beforeCard) {
                dirty = true;
            }
        }
        if (dirty) {
            roadData.setDirty();
        }
        return totalPlaced;
    }

    /**
     * Realizes all currently loaded, unrealized steps {@code 0 .. constructedStepIndex} for {@code segment}.
     * Groups any consecutive run of WATER steps (plus 2 bank abutment steps on both ends) into a single atomic
     * bridge/causeway span that waits until all chunks of the span are loaded.
     */
    public static int realizeAvailableSteps(ServerLevel level, RoadSegment segment) {
        List<Waypoint> steps = segment.steps();
        if (steps.isEmpty())
            return 0;
        int maxStep = Math.min(steps.size() - 1, segment.constructedStepIndex());
        if (maxStep < 0)
            return 0;

        if (isFullyRealized(segment))
            return 0;

        RoadStyle baseStyle = styleFor(segment);
        RoadConfig.Settings cfg = RoadConfig.current();
        Collection<Village> villages = villagesNear(level, steps);
        Map<Long, ColumnInfo> colCache = new HashMap<>();
        List<Integer> newlyDrySteps = new ArrayList<>();

        Set<Long> coreStepCoords = new HashSet<>(steps.size() * 2);
        for (Waypoint w : steps) {
            coreStepCoords.add(pack(w.x(), w.z()));
        }

        Map<Long, Integer> newlyPlacedDryY = new LinkedHashMap<>();
        int placed = 0;
        int i = 0;

        while (i <= maxStep) {
            if (segment.isStepRealized(i)) {
                i++;
                continue;
            }
            Waypoint wp = steps.get(i);
            if (!isStepNeighborhoodLoaded(level, wp.x(), wp.z())) {
                i++;
                continue;
            }

            // Look ahead up to 2 steps (guaranteed in loaded chunks because wp±2 is loaded) to see if step i
            // is the start or 2-step bank abutment of a water crossing.
            int waterTriggerIdx = -1;
            int lookLimit = Math.min(steps.size() - 1, i + 2);
            for (int k = i; k <= lookLimit; k++) {
                Waypoint kw = steps.get(k);
                ColumnInfo kInfo = inspectColumn(level, colCache, kw.x(), kw.z(), baseStyle, villages);
                if (kInfo != null && !kInfo.ocean() && !kInfo.lava() && kInfo.water()) {
                    waterTriggerIdx = k;
                    break;
                }
            }

            if (waterTriggerIdx >= 0) {
                // Expand backward if water started just before waterTriggerIdx.
                int wStart = waterTriggerIdx;
                while (wStart > 0) {
                    Waypoint prevW = steps.get(wStart - 1);
                    ColumnInfo prevInfo = inspectColumn(level, colCache, prevW.x(), prevW.z(), baseStyle, villages);
                    if (prevInfo != null && !prevInfo.ocean() && !prevInfo.lava() && prevInfo.water()) {
                        wStart--;
                    } else {
                        break;
                    }
                }

                // Scan forward through all consecutive water steps to find wEnd.
                int wEnd = wStart;
                boolean waterFullyLoaded = true;
                while (wEnd + 1 < steps.size()) {
                    Waypoint nextW = steps.get(wEnd + 1);
                    if (!isStepNeighborhoodLoaded(level, nextW.x(), nextW.z())) {
                        waterFullyLoaded = false;
                        break;
                    }
                    ColumnInfo nextInfo = inspectColumn(level, colCache, nextW.x(), nextW.z(), baseStyle, villages);
                    if (nextInfo == null) {
                        waterFullyLoaded = false;
                        break;
                    }
                    if (!nextInfo.ocean() && !nextInfo.lava() && nextInfo.water()) {
                        wEnd++;
                    } else {
                        break;
                    }
                }

                int spanStart = Math.max(0, wStart - 2);
                int spanEnd = Math.min(steps.size() - 1, wEnd + 2);

                if (waterFullyLoaded) {
                    for (int s = spanStart; s <= spanEnd; s++) {
                        Waypoint sw = steps.get(s);
                        if (!isStepNeighborhoodLoaded(level, sw.x(), sw.z())) {
                            waterFullyLoaded = false;
                            break;
                        }
                    }
                }

                if (!waterFullyLoaded) {
                    // Wait until level.hasChunkAt is true for the entire bridge span [spanStart .. spanEnd].
                    i = Math.max(i + 1, spanEnd + 1);
                    continue;
                }

                placed += realizeAtomicBridgeSpan(level, segment, steps, spanStart, wStart, wEnd, spanEnd,
                        baseStyle, villages, colCache, coreStepCoords, newlyPlacedDryY, cfg);
                for (int s = spanStart; s <= spanEnd; s++) {
                    if (!segment.isStepRealized(s))
                        continue;
                    Waypoint sw = steps.get(s);
                    segment.markChunkRealized(
                            ChunkPos.asLong(SectionPos.blockToSectionCoord(sw.x()),
                                    SectionPos.blockToSectionCoord(sw.z())),
                            segment.completedBlocks());
                }
                i = spanEnd + 1;
                continue;
            }

            ColumnInfo centerInfo = inspectColumn(level, colCache, wp.x(), wp.z(), baseStyle, villages);
            if (centerInfo == null) {
                i++;
                continue;
            }
            if (centerInfo.ocean() || centerInfo.lava()) {
                segment.markStepRealized(i);
                i++;
                continue;
            }

            int rawY = centerInfo.groundY();
            if (centerInfo.protectedCol() && i > 0) {
                int prevRealized = segment.getRealizedY(i - 1);
                if (prevRealized != Integer.MIN_VALUE)
                    rawY = prevRealized;
            }
            int targetY = computeClampedDryStepY(level, segment, steps, i, rawY, baseStyle, villages, colCache);
            placed += realizeDryStep(level, segment, steps, i, targetY, baseStyle, villages, colCache,
                    coreStepCoords, newlyPlacedDryY);
            segment.setRealizedY(i, targetY);
            segment.markStepRealized(i);
            segment.markChunkRealized(
                    ChunkPos.asLong(SectionPos.blockToSectionCoord(wp.x()), SectionPos.blockToSectionCoord(wp.z())),
                    segment.completedBlocks());
            newlyDrySteps.add(i);
            i++;
        }

        if (!newlyDrySteps.isEmpty()) {
            placeAlongRoadTransitionSlabs(level, segment, steps, newlyDrySteps, baseStyle, villages);
        }
        return placed;
    }

    /** True when every step up to the construction frontier is already physically realized. */
    static boolean isFullyRealized(RoadSegment segment) {
        int size = segment.steps().size();
        if (size == 0)
            return true;
        int maxStep = Math.min(size - 1, segment.constructedStepIndex());
        return segment.realizedSteps().nextClearBit(0) > maxStep;
    }

    /**
     * Only villages that can own buildings near this road. Protection checks run per block, so scanning every
     * building in the world for each block is the dominant realization cost on large worlds.
     */
    private static Collection<Village> villagesNear(ServerLevel level, List<Waypoint> steps) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Waypoint w : steps) {
            minX = Math.min(minX, w.x());
            maxX = Math.max(maxX, w.x());
            minZ = Math.min(minZ, w.z());
            maxZ = Math.max(maxZ, w.z());
        }
        int pad = VILLAGE_PROTECTION_RADIUS;
        List<Village> out = new ArrayList<>();
        for (Village v : VillageSavedData.get(level).getVillageManager().getAllVillages()) {
            BlockPos c = v.getCenter();
            if (c == null)
                continue;
            if (c.getX() >= minX - pad && c.getX() <= maxX + pad && c.getZ() >= minZ - pad && c.getZ() <= maxZ + pad)
                out.add(v);
        }
        return out;
    }

    /**
     * Checks whether {@code (x, z)} and its {@code ±2} brush neighborhood are inside already-loaded chunks
     * ({@code level.hasChunkAt(pos)} without ever force-loading unloaded chunks).
     */
    public static boolean isStepNeighborhoodLoaded(ServerLevel level, int x, int z) {
        int minCx = SectionPos.blockToSectionCoord(x - 2);
        int maxCx = SectionPos.blockToSectionCoord(x + 2);
        int minCz = SectionPos.blockToSectionCoord(z - 2);
        int maxCz = SectionPos.blockToSectionCoord(z + 2);
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                if (!level.hasChunk(cx, cz))
                    return false;
            }
        }
        return true;
    }

    /** Delegating wrapper for chunk-load callers and legacy tests. */
    public static int realizeInLoadedChunk(ServerLevel level, RoadSegment segment, ChunkPos chunkPos) {
        if (!level.hasChunk(chunkPos.x, chunkPos.z))
            return 0;
        int placed = realizeAvailableSteps(level, segment);
        if (segment.completedBlocks() > 0) {
            segment.markChunkRealized(chunkPos.toLong(), segment.completedBlocks());
        }
        return placed;
    }

    /** Delegating wrapper for progress-advance callers and legacy tests. */
    public static void realizeLoadedAlong(ServerLevel level, RoadSegment segment, int fromBlock, int toBlock) {
        realizeAvailableSteps(level, segment);
    }

    /**
     * Computes the dry surface {@code Y} for step {@code i} with smooth {@code Y}-clamping ({@code max ±1} block
     * height delta from step {@code i - 1}) and lookahead smoothing so small dips/bumps grade into 1-block ramps.
     */
    private static int computeClampedDryStepY(ServerLevel level, RoadSegment segment, List<Waypoint> steps,
            int i, int rawY, RoadStyle baseStyle, Collection<Village> villages, Map<Long, ColumnInfo> colCache) {
        int targetY = rawY;

        // Look ahead up to 8 loaded dry steps to avoid plunging into a narrow dip faster than 1 block/step recovery.
        int lookMax = Math.min(steps.size() - 1, i + 8);
        for (int k = i + 1; k <= lookMax; k++) {
            int dist = k - i;
            int ky = segment.getRealizedY(k);
            if (ky == Integer.MIN_VALUE) {
                Waypoint kw = steps.get(k);
                ColumnInfo kc = inspectColumn(level, colCache, kw.x(), kw.z(), baseStyle, villages);
                if (kc == null || kc.ocean() || kc.lava())
                    break;
                if (kc.protectedCol())
                    continue;
                if (kc.water()) {
                    ky = kc.waterY() + 1;
                } else {
                    ky = kc.groundY();
                }
            }
            targetY = Math.clamp(targetY, ky - dist, ky + dist);
        }

        // Strictly clamp to max ±1 block height delta from step i - 1.
        if (i > 0) {
            int prevY = segment.getRealizedY(i - 1);
            if (prevY == Integer.MIN_VALUE && segment.isStepRealized(i - 1)) {
                Waypoint pw = steps.get(i - 1);
                ColumnInfo pc = inspectColumn(level, colCache, pw.x(), pw.z(), baseStyle, villages);
                if (pc != null) {
                    prevY = pc.water() ? pc.waterY() + 1 : pc.groundY();
                }
            }
            if (prevY != Integer.MIN_VALUE) {
                targetY = Math.clamp(targetY, prevY - 1, prevY + 1);
            }
        }
        return targetY;
    }

    private static int realizeDryStep(ServerLevel level, RoadSegment segment, List<Waypoint> steps,
            int i, int centerY, RoadStyle baseStyle, Collection<Village> villages, Map<Long, ColumnInfo> colCache,
            Set<Long> coreStepCoords, Map<Long, Integer> newlyPlacedDryY) {
        Waypoint p = steps.get(i);
        Waypoint prev = i > 0 ? steps.get(i - 1) : p;
        Waypoint next = i + 1 < steps.size() ? steps.get(i + 1) : p;
        double dxForward = next.x() - prev.x();
        double dzForward = next.z() - prev.z();
        double len = Math.hypot(dxForward, dzForward);
        double px = len > 1e-6 ? -dzForward / len : 0.0;
        double pz = len > 1e-6 ? dxForward / len : 1.0;

        int placed = 0;
        // Solid 3x3 disk brush (dx^2 + dz^2 <= 2) eliminates diagonal checkerboard gaps completely.
        for (int brushDz = -1; brushDz <= 1; brushDz++) {
            for (int brushDx = -1; brushDx <= 1; brushDx++) {
                if (brushDx * brushDx + brushDz * brushDz > 2)
                    continue;
                int bx = p.x() + brushDx;
                int bz = p.z() + brushDz;
                long colKey = pack(bx, bz);
                boolean isCenter = (brushDx == 0 && brushDz == 0);
                if (!isCenter && coreStepCoords.contains(colKey))
                    continue; // Neighboring centerline steps are realized at their own step's targetY.
                ColumnInfo colInfo = inspectColumn(level, colCache, bx, bz, baseStyle, villages);
                if (colInfo == null || colInfo.ocean() || colInfo.lava() || colInfo.water())
                    continue;

                if (!isCenter && newlyPlacedDryY.containsKey(colKey))
                    continue;

                int placedY = placeDryColumn(level, bx, bz, baseStyle, villages, colCache, centerY, !isCenter);
                if (placedY != Integer.MIN_VALUE) {
                    placed++;
                    newlyPlacedDryY.put(colKey, placedY);
                }
            }
        }

        // Side-slope uphill shaving & tree clearing on immediate 2-block shoulders.
        for (int sl = -2; sl <= 2; sl += 4) {
            int sx = (int) Math.round(p.x() + px * sl);
            int sz = (int) Math.round(p.z() + pz * sl);
            clearShoulderTrees(level, sx, sz, centerY, villages);
            shaveSideSlopeOverhang(level, sx, sz, centerY, baseStyle, villages);
        }

        // Sparse medieval milestones every ~90 steps outside the road shoulder.
        if (i >= 40 && i % MILESTONE_INTERVAL == 0) {
            if (placeMilestoneIfDue(level, p.x(), p.z(), px, pz, centerY, villages, colCache, baseStyle)) {
                placed++;
            }
        }
        return placed;
    }

    /**
     * Realizes an entire water crossing span {@code [spanStart .. spanEnd]} (containing water steps
     * {@code [wStart .. wEnd]} and up to 2 bank abutment steps on both ends) in one atomic pass across all chunks.
     */
    private static int realizeAtomicBridgeSpan(ServerLevel level, RoadSegment segment, List<Waypoint> steps,
            int spanStart, int wStart, int wEnd, int spanEnd, RoadStyle baseStyle, Collection<Village> villages,
            Map<Long, ColumnInfo> colCache, Set<Long> coreStepCoords, Map<Long, Integer> newlyPlacedDryY,
            RoadConfig.Settings cfg) {
        Waypoint firstW = steps.get(wStart);
        Waypoint lastW = steps.get(wEnd);
        int waterStepCount = wEnd - wStart + 1;
        int physicalWidth = (int) Math.ceil(
                Math.hypot((double) lastW.x() - firstW.x(), (double) lastW.z() - firstW.z())) + 1;
        int spanWidth = Math.max(waterStepCount, physicalWidth);

        int maxDepth = 0;
        int sumWaterY = 0;
        int waterCount = 0;
        int alreadyBridgeCount = 0;
        boolean spanIsSwamp = false;
        boolean spanIsRiver = false;

        for (int idx = wStart; idx <= wEnd; idx++) {
            Waypoint pt = steps.get(idx);
            ColumnInfo info = inspectColumn(level, colCache, pt.x(), pt.z(), baseStyle, villages);
            if (info == null || info.ocean() || info.lava())
                return 0;
            maxDepth = Math.max(maxDepth, info.waterDepth());
            if (info.alreadyBridge())
                alreadyBridgeCount++;
            int wy = info.waterY();
            if (wy != Integer.MIN_VALUE) {
                sumWaterY += wy;
                waterCount++;
                Holder<Biome> b = level.getBiome(new BlockPos(pt.x(), wy, pt.z()));
                if (RoadPlanner.isSwampBiome(b))
                    spanIsSwamp = true;
                if (b.is(net.minecraft.tags.BiomeTags.IS_RIVER))
                    spanIsRiver = true;
            }
        }
        if (waterCount == 0)
            return 0;
        int avgWaterY = Math.round((float) sumWaterY / waterCount);
        int deckBaseY = avgWaterY + 1;

        if (spanWidth > cfg.maxBridgeWidth()) {
            // Over-wide water (> maxBridgeWidth): realize only dry abutment steps and leave open water unbridged
            // without marking the water steps realized with a missing realizedY.
            int placed = 0;
            for (int idx = spanStart; idx < wStart; idx++) {
                ColumnInfo ci = inspectColumn(level, colCache, steps.get(idx).x(), steps.get(idx).z(), baseStyle, villages);
                if (ci != null && !ci.water()) {
                    placed += realizeDryStep(level, segment, steps, idx, ci.groundY(), baseStyle, villages,
                            colCache, coreStepCoords, newlyPlacedDryY);
                    segment.setRealizedY(idx, ci.groundY());
                    segment.markStepRealized(idx);
                }
            }
            for (int idx = wEnd + 1; idx <= spanEnd; idx++) {
                ColumnInfo ci = inspectColumn(level, colCache, steps.get(idx).x(), steps.get(idx).z(), baseStyle, villages);
                if (ci != null && !ci.water()) {
                    placed += realizeDryStep(level, segment, steps, idx, ci.groundY(), baseStyle, villages,
                            colCache, coreStepCoords, newlyPlacedDryY);
                    segment.setRealizedY(idx, ci.groundY());
                    segment.markStepRealized(idx);
                }
            }
            return placed;
        }

        if (alreadyBridgeCount == waterCount) {
            // Every water step already crosses an existing bridge deck: keep it untouched and realize bank approaches.
            int placed = 0;
            for (int idx = spanStart; idx < wStart; idx++) {
                placed += realizeBankAbutmentStep(level, segment, steps, idx, wStart - idx, deckBaseY, baseStyle,
                        villages, colCache, coreStepCoords, newlyPlacedDryY);
            }
            for (int idx = wEnd + 1; idx <= spanEnd; idx++) {
                placed += realizeBankAbutmentStep(level, segment, steps, idx, idx - wEnd, deckBaseY, baseStyle,
                        villages, colCache, coreStepCoords, newlyPlacedDryY);
            }
            for (int idx = wStart; idx <= wEnd; idx++) {
                Waypoint pt = steps.get(idx);
                ColumnInfo ci = inspectColumn(level, colCache, pt.x(), pt.z(), baseStyle, villages);
                if (ci != null) {
                    segment.setRealizedY(idx, ci.groundY());
                }
            }
            segment.markStepsRealized(spanStart, spanEnd);
            return placed;
        }

        int placed = 0;
        // Realize the 2 bank abutment steps on both ends so land road transitions smoothly onto the bridge deck.
        for (int idx = spanStart; idx < wStart; idx++) {
            placed += realizeBankAbutmentStep(level, segment, steps, idx, wStart - idx, deckBaseY, baseStyle,
                    villages, colCache, coreStepCoords, newlyPlacedDryY);
        }
        for (int idx = wEnd + 1; idx <= spanEnd; idx++) {
            placed += realizeBankAbutmentStep(level, segment, steps, idx, idx - wEnd, deckBaseY, baseStyle,
                    villages, colCache, coreStepCoords, newlyPlacedDryY);
        }

        if (spanIsSwamp && maxDepth <= 2 && !spanIsRiver) {
            placed += realizeSwampCauseway(level, segment, steps, wStart, wEnd, avgWaterY, baseStyle, villages, colCache);
        } else if (spanWidth <= 5 || (maxDepth <= 1 && !spanIsRiver)) {
            placed += realizeStoneCauseway(level, segment, steps, wStart, wEnd, avgWaterY, baseStyle, villages, colCache);
        } else {
            placed += realizeArchedBridge(level, segment, steps, wStart, wEnd, avgWaterY, baseStyle, villages, colCache);
        }
        segment.markStepsRealized(spanStart, spanEnd);
        return placed;
    }

    private static int realizeBankAbutmentStep(ServerLevel level, RoadSegment segment, List<Waypoint> steps,
            int idx, int distFromWater, int deckBaseY, RoadStyle baseStyle, Collection<Village> villages,
            Map<Long, ColumnInfo> colCache, Set<Long> coreStepCoords, Map<Long, Integer> newlyPlacedDryY) {
        Waypoint wp = steps.get(idx);
        ColumnInfo info = inspectColumn(level, colCache, wp.x(), wp.z(), baseStyle, villages);
        if (info == null || info.ocean() || info.lava() || info.water())
            return 0;

        int stepY = distFromWater == 1
                ? Math.clamp(deckBaseY - 1, info.groundY() - 1, info.groundY() + 1)
                : Math.clamp(deckBaseY - 1, info.groundY() - 1, info.groundY() + 1);
        int placed = realizeDryStep(level, segment, steps, idx, stepY, baseStyle, villages, colCache,
                coreStepCoords, newlyPlacedDryY);
        segment.setRealizedY(idx, stepY);

        // Place graded approach slab or abutment cap on step directly adjacent to the bridge entrance.
        if (distFromWater == 1 && deckBaseY > stepY) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx * dx + dz * dz > 2)
                        continue;
                    int bx = wp.x() + dx;
                    int bz = wp.z() + dz;
                    ColumnInfo bInfo = inspectColumn(level, colCache, bx, bz, baseStyle, villages);
                    if (bInfo == null || bInfo.water() || bInfo.protectedCol())
                        continue;
                    BlockPos slabPos = new BlockPos(bx, stepY + 1, bz);
                    if (!isProtected(level, slabPos, villages)) {
                        BlockState st = level.getBlockState(slabPos);
                        if (st.isAir() || isClearableFoliage(st)) {
                            RoadStyle localStyle = styleForPos(level, slabPos, baseStyle);
                            BlockState bottomSlab = localStyle.slabBlock().defaultBlockState()
                                    .setValue(SlabBlock.TYPE, SlabType.BOTTOM);
                            level.setBlock(slabPos, bottomSlab, Block.UPDATE_CLIENTS);
                            placed++;
                        }
                    }
                }
            }
        }
        return placed;
    }

    private static int realizeSwampCauseway(ServerLevel level, RoadSegment segment, List<Waypoint> steps,
            int wStart, int wEnd, int fallbackWaterY, RoadStyle baseStyle, Collection<Village> villages,
            Map<Long, ColumnInfo> colCache) {
        Map<Long, BridgeCell> deckMap = new LinkedHashMap<>();
        int spanCount = wEnd - wStart + 1;

        for (int idx = wStart; idx <= wEnd; idx++) {
            Waypoint pt = steps.get(idx);
            ColumnInfo col = inspectColumn(level, colCache, pt.x(), pt.z(), baseStyle, villages);
            int waterY = (col != null && col.waterY() != Integer.MIN_VALUE) ? col.waterY() : fallbackWaterY;
            int deckY = waterY + 1;
            segment.setRealizedY(idx, deckY);

            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx * dx + dz * dz > 2)
                        continue;
                    int bx = pt.x() + dx;
                    int bz = pt.z() + dz;
                    long key = pack(bx, bz);
                    boolean core = (dx == 0 && dz == 0);
                    BridgeCell prevCell = deckMap.get(key);
                    if (prevCell == null || core || deckY > prevCell.y()) {
                        deckMap.put(key, new BridgeCell(bx, bz, deckY, false,
                                core || (prevCell != null && prevCell.isCore())));
                    }
                }
            }
        }

        int placed = 0;
        for (BridgeCell cell : deckMap.values()) {
            clearBridgeColumnAndCanopy(level, cell.x(), cell.z(), cell.y() - 1, cell.y(), villages);

            BlockPos deckPos = new BlockPos(cell.x(), cell.y(), cell.z());
            if (isProtected(level, deckPos, villages) || isLavaOrHazard(level, deckPos))
                continue;

            fillSwampCausewayFoundation(level, cell.x(), cell.z(), cell.y() - 1, villages);
            Block surface = cell.isCore()
                    ? ModBlocks.PATH_DIRT.get()
                    : pickSwampCausewayEdge(cell.x(), cell.z());
            level.setBlock(deckPos, surface.defaultBlockState(), Block.UPDATE_CLIENTS);
            placed++;
        }

        for (int idx = wStart; idx <= wEnd; idx++) {
            int s = idx - wStart;
            Waypoint pt = steps.get(idx);
            double[] perp = perpVector(steps, idx);
            double edgeDist = (Math.abs(perp[0]) > 0.3 && Math.abs(perp[1]) > 0.3) ? 2.5 : 2.0;
            for (int side : new int[] { -1, 1 }) {
                int rx = (int) Math.round(pt.x() + perp[0] * side * edgeDist);
                int rz = (int) Math.round(pt.z() + perp[1] * side * edgeDist);
                long rKey = pack(rx, rz);
                if (deckMap.containsKey(rKey)
                        || hasExistingBridgeDeckInColumn(level, rx, rz, fallbackWaterY + 1, fallbackWaterY + 5, baseStyle))
                    continue;
                clearBridgeColumnAndCanopy(level, rx, rz, fallbackWaterY, fallbackWaterY + 1, villages);
                int deckY = fallbackWaterY + 1;
                BlockPos topPost = new BlockPos(rx, deckY + 1, rz);
                if (isProtected(level, topPost, villages))
                    continue;
                if ((s % 3 == 0) || s == 0 || s == spanCount - 1) {
                    fillPilingColumn(level, rx, rz, deckY, Blocks.OAK_LOG, villages);
                    if (level.getBlockState(topPost).isAir() || isClearableFoliage(level.getBlockState(topPost))) {
                        level.setBlock(topPost, Blocks.OAK_FENCE.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
        return placed;
    }

    private static int realizeStoneCauseway(ServerLevel level, RoadSegment segment, List<Waypoint> steps,
            int wStart, int wEnd, int fallbackWaterY, RoadStyle baseStyle, Collection<Village> villages,
            Map<Long, ColumnInfo> colCache) {
        Map<Long, BridgeCell> deckMap = new LinkedHashMap<>();
        int spanCount = wEnd - wStart + 1;

        for (int idx = wStart; idx <= wEnd; idx++) {
            Waypoint pt = steps.get(idx);
            ColumnInfo col = inspectColumn(level, colCache, pt.x(), pt.z(), baseStyle, villages);
            if (col == null || col.ocean() || col.lava())
                continue;
            int waterY = col.waterY() != Integer.MIN_VALUE ? col.waterY() : fallbackWaterY;
            int deckY = waterY + 1;
            segment.setRealizedY(idx, deckY);

            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx * dx + dz * dz > 2)
                        continue;
                    int bx = pt.x() + dx;
                    int bz = pt.z() + dz;
                    long key = pack(bx, bz);
                    boolean core = (dx == 0 && dz == 0);
                    BridgeCell prevCell = deckMap.get(key);
                    if (prevCell == null || core || deckY > prevCell.y()) {
                        deckMap.put(key, new BridgeCell(bx, bz, deckY, false,
                                core || (prevCell != null && prevCell.isCore())));
                    }
                }
            }
        }

        int placed = 0;
        for (BridgeCell cell : deckMap.values()) {
            clearBridgeColumnAndCanopy(level, cell.x(), cell.z(), cell.y() - 1, cell.y(), villages);

            BlockPos deckPos = new BlockPos(cell.x(), cell.y(), cell.z());
            if (isProtected(level, deckPos, villages) || isLavaOrHazard(level, deckPos))
                continue;

            fillCausewayFoundation(level, cell.x(), cell.z(), cell.y() - 1, !cell.isCore(), villages);
            if (!level.getBlockState(deckPos).is(baseStyle.bridgeDeck())) {
                level.setBlock(deckPos, baseStyle.bridgeDeck().defaultBlockState(), Block.UPDATE_CLIENTS);
                placed++;
            }
        }

        for (int idx = wStart; idx <= wEnd; idx++) {
            int s = idx - wStart;
            boolean pilingStep = (s % 2 == 0) || s == 0 || s == spanCount - 1;
            if (!pilingStep)
                continue;
            Waypoint pt = steps.get(idx);
            double[] perp = perpVector(steps, idx);
            double edgeDist = (Math.abs(perp[0]) > 0.3 && Math.abs(perp[1]) > 0.3) ? 2.5 : 2.0;
            int deckY = fallbackWaterY + 1;
            for (int side : new int[] { -1, 1 }) {
                int bx = (int) Math.round(pt.x() + perp[0] * side * edgeDist);
                int bz = (int) Math.round(pt.z() + perp[1] * side * edgeDist);
                if (deckMap.containsKey(pack(bx, bz))
                        || hasExistingBridgeDeckInColumn(level, bx, bz, fallbackWaterY + 1, fallbackWaterY + 5, baseStyle))
                    continue;
                clearBridgeColumnAndCanopy(level, bx, bz, fallbackWaterY, deckY, villages);
                BlockPos topPost = new BlockPos(bx, deckY + 1, bz);
                if (isProtected(level, topPost, villages))
                    continue;
                fillPilingColumn(level, bx, bz, deckY, Blocks.OAK_LOG, villages);
                if (level.getBlockState(topPost).isAir() || isClearableFoliage(level.getBlockState(topPost))) {
                    level.setBlock(topPost, Blocks.OAK_FENCE.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
        return placed;
    }

    private static int realizeArchedBridge(ServerLevel level, RoadSegment segment, List<Waypoint> steps,
            int wStart, int wEnd, int waterY, RoadStyle baseStyle, Collection<Village> villages,
            Map<Long, ColumnInfo> colCache) {
        int spanCount = wEnd - wStart + 1;
        int deckBaseY = waterY + 1;
        int archBays = spanCount > 24 ? Math.max(2, (int) Math.round(spanCount / 20.0)) : 1;
        double peakHalfBlocks = spanCount >= 12 ? 4.0 : 3.0;

        Map<Long, BridgeCell> deckMap = new LinkedHashMap<>();
        int[] stepBlockY = new int[spanCount];

        for (int idx = wStart; idx <= wEnd; idx++) {
            int s = idx - wStart;
            Waypoint pt = steps.get(idx);
            double bayProgress = ((s + 0.5) * archBays) / spanCount;
            double localT = bayProgress - Math.floor(bayProgress);
            int halfOffset = (int) Math.round(Math.sin(Math.PI * localT) * peakHalfBlocks);
            int blockY = deckBaseY + ((halfOffset + 1) / 2);
            boolean useBottomSlab = (halfOffset % 2) == 1;
            stepBlockY[s] = blockY;
            segment.setRealizedY(idx, blockY);

            // Solid 3x3 disk brush guarantees zero checkerboard water holes on straight or diagonal bridge decks.
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx * dx + dz * dz > 2)
                        continue;
                    int bx = pt.x() + dx;
                    int bz = pt.z() + dz;
                    long key = pack(bx, bz);
                    boolean core = (dx == 0 && dz == 0);
                    BridgeCell prev = deckMap.get(key);
                    if (prev == null || blockY > prev.y()
                            || (blockY == prev.y() && prev.bottomSlab() && !useBottomSlab)) {
                        deckMap.put(key, new BridgeCell(bx, bz, blockY, useBottomSlab,
                                core || (prev != null && prev.isCore())));
                    }
                }
            }
        }

        int placed = 0;
        for (BridgeCell cell : deckMap.values()) {
            clearBridgeColumnAndCanopy(level, cell.x(), cell.z(), waterY + 1, cell.y(), villages);

            BlockPos deckPos = new BlockPos(cell.x(), cell.y(), cell.z());
            if (isProtected(level, deckPos, villages) || isLavaOrHazard(level, deckPos))
                continue;

            // Keep central channel clear underneath arched spans so boats can pass freely.
            for (int ay = waterY + 1; ay < cell.y(); ay++) {
                BlockPos under = new BlockPos(cell.x(), ay, cell.z());
                if (!isProtected(level, under, villages)) {
                    BlockState ust = level.getBlockState(under);
                    if (!ust.isAir() && !isBridgeDeckBlock(ust, baseStyle)) {
                        level.setBlock(under, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
            }
            int deckMaxY = Math.max(cell.y() + 2, waterY + 5);
            if (!hasExistingBridgeDeckInColumn(level, cell.x(), cell.z(), waterY + 1, deckMaxY, baseStyle)) {
                BlockState deckState = cell.bottomSlab()
                        ? baseStyle.bridgeSlab().defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM)
                        : baseStyle.bridgeDeck().defaultBlockState();
                level.setBlock(deckPos, deckState, Block.UPDATE_CLIENTS);
                placed++;
            }
        }

        // Parapets, bridgehead/junction lantern posts, and outer riverbed support piers placed strictly outside deckMap.
        Set<Long> visitedRims = new HashSet<>();
        for (int idx = wStart; idx <= wEnd; idx++) {
            int s = idx - wStart;
            Waypoint pt = steps.get(idx);
            int blockY = stepBlockY[s];
            double[] perp = perpVector(steps, idx);
            double edgeDist = (Math.abs(perp[0]) > 0.3 && Math.abs(perp[1]) > 0.3) ? 2.5 : 2.0;

            boolean isEntrance = (s == 0 || s == spanCount - 1);
            boolean isBayJunction = archBays > 1 && s > 0 && s < spanCount - 1
                    && (int) ((s * archBays) / spanCount) != (int) (((s - 1) * archBays) / spanCount);
            boolean isPier = !isEntrance && (isBayJunction || (s % 4 == 0));
            for (int side : new int[] { -1, 1 }) {
                int rx = (int) Math.round(pt.x() + perp[0] * side * edgeDist);
                int rz = (int) Math.round(pt.z() + perp[1] * side * edgeDist);
                long rimKey = pack(rx, rz);
                if (deckMap.containsKey(rimKey) || !visitedRims.add(rimKey))
                    continue;
                if (hasExistingBridgeDeckInColumn(level, rx, rz, waterY + 1, Math.max(blockY + 2, waterY + 5), baseStyle))
                    continue;
                clearBridgeColumnAndCanopy(level, rx, rz, waterY + 1, blockY, villages);

                BlockPos rimBase = new BlockPos(rx, blockY, rz);
                if (isProtected(level, rimBase, villages))
                    continue;

                if (isPier || isEntrance) {
                    fillPilingColumn(level, rx, rz, blockY, Blocks.STONE_BRICKS, villages);
                } else {
                    level.setBlock(rimBase, baseStyle.bridgeDeck().defaultBlockState(), Block.UPDATE_CLIENTS);
                }

                BlockPos rail1 = rimBase.above();
                if (!isProtected(level, rail1, villages)) {
                    if (isEntrance || isBayJunction) {
                        level.setBlock(rail1, Blocks.COBBLESTONE_WALL.defaultBlockState(), Block.UPDATE_CLIENTS);
                        BlockPos rail2 = rimBase.above(2);
                        BlockPos lanternPos = rimBase.above(3);
                        if (!isProtected(level, rail2, villages) && !isProtected(level, lanternPos, villages)) {
                            level.setBlock(rail2, Blocks.OAK_FENCE.defaultBlockState(), Block.UPDATE_CLIENTS);
                            level.setBlock(lanternPos, Blocks.LANTERN.defaultBlockState(), Block.UPDATE_CLIENTS);
                        }
                    } else {
                        Block railBlock = isPier ? Blocks.COBBLESTONE_WALL : Blocks.OAK_FENCE;
                        level.setBlock(rail1, railBlock.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
        return placed;
    }

    private static double[] perpVector(List<Waypoint> steps, int idx) {
        Waypoint prev = idx > 0 ? steps.get(idx - 1) : steps.get(idx);
        Waypoint next = idx + 1 < steps.size() ? steps.get(idx + 1) : steps.get(idx);
        double dx = next.x() - prev.x();
        double dz = next.z() - prev.z();
        double len = Math.hypot(dx, dz);
        return len > 1e-6 ? new double[] { -dz / len, dx / len } : new double[] { 0.0, 1.0 };
    }

    private static ColumnInfo inspectColumn(ServerLevel level, Map<Long, ColumnInfo> cache, int x, int z,
            RoadStyle style, Collection<Village> villages) {
        if (!level.hasChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z)))
            return null;
        return cache.computeIfAbsent(pack(x, z), k -> computeColumnInfo(level, x, z, style, villages));
    }

    private static ColumnInfo computeColumnInfo(
            ServerLevel level, int x, int z, RoadStyle style, Collection<Village> villages) {
        boolean ocean = false;
        int topSurfaceY = findGroundOrWaterSurfaceY(level, x, z, style);
        if (topSurfaceY < level.getMinBuildHeight()) {
            return new ColumnInfo(topSurfaceY, Integer.MIN_VALUE, topSurfaceY, 0, false, false, true, false, false);
        }

        BlockPos pos = new BlockPos(x, topSurfaceY, z);
        BlockState st = level.getBlockState(pos);
        boolean lava = isLavaOrHazard(level, pos) || isLavaOrHazard(level, pos.above());
        boolean water = !lava && isWaterOrAquaticFluid(level, pos);
        int waterY = water ? topSurfaceY : Integer.MIN_VALUE;
        int waterDepth = water ? measureWaterDepth(level, x, z, topSurfaceY) : 0;

        int bridgeWaterY = (!water && (st.is(style.bridgeDeck()) || st.is(style.bridgeSlab())))
                ? findWaterSurfaceBelow(level, x, z, topSurfaceY, 5)
                : Integer.MIN_VALUE;
        boolean alreadyBridge = bridgeWaterY != Integer.MIN_VALUE;
        if (alreadyBridge) {
            water = true;
            waterY = bridgeWaterY;
            waterDepth = measureWaterDepth(level, x, z, bridgeWaterY);
        }

        boolean protectedCol = isProtected(level, pos, villages);
        int walkY = water ? waterY + 1 : topSurfaceY;
        return new ColumnInfo(
                topSurfaceY, waterY, walkY, waterDepth, water, alreadyBridge, ocean, lava, protectedCol);
    }

    /**
     * Scans downward for ground/water at {@code (x, z)}, skipping leaves, logs, grass/tall grass, flowers,
     * snow layers, {@code lily_pad}, {@code seagrass}, {@code tall_seagrass}, {@code kelp}, and {@code kelp_plant}.
     * If the highest remaining block/fluid has {@code !state.getFluidState().isEmpty()}, returns its surface {@code waterY}.
     */
    private static int findGroundOrWaterSurfaceY(ServerLevel level, int x, int z, RoadStyle style) {
        int topY = Math.max(
                level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z),
                level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
        if (topY <= level.getMinBuildHeight())
            return level.getMinBuildHeight() - 1;
        int y = Math.min(level.getMaxBuildHeight() - 1, topY);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, y, z);
        while (y >= level.getMinBuildHeight()) {
            cursor.setY(y);
            BlockState st = level.getBlockState(cursor);
            if (st.isAir()) {
                y--;
                continue;
            }
            if ((st.is(style.bridgeDeck()) || st.is(style.bridgeSlab()))
                    && findWaterSurfaceBelow(level, x, z, y, 5) != Integer.MIN_VALUE) {
                return y;
            }
            if (isRoadSlabBlock(st, style)
                    || isSkippedSurfaceFloraOrCanopy(st)
                    || isFloatingOverhang(level, x, y, z, st)) {
                // Aquatic plants or waterlogged fixtures inside water carry water FluidState; return water surface.
                FluidState fs = st.getFluidState();
                if (!fs.isEmpty() && !fs.is(FluidTags.LAVA)) {
                    return y;
                }
                y--;
                continue;
            }
            FluidState fs = st.getFluidState();
            if (!fs.isEmpty()) {
                return y;
            }
            if (st.canBeReplaced()) {
                y--;
                continue;
            }
            return y;
        }
        return level.getMinBuildHeight() - 1;
    }

    private static boolean isRoadSlabBlock(BlockState st, RoadStyle style) {
        return st != null && st.getBlock() instanceof SlabBlock
                && (st.is(style.slabBlock()) || isMillenairePathState(st));
    }

    private static boolean isFloatingOverhang(ServerLevel level, int x, int y, int z, BlockState st) {
        if (!(st.is(BlockTags.PLANKS) || st.is(BlockTags.STAIRS) || st.is(BlockTags.SLABS)
                || st.is(BlockTags.TRAPDOORS) || st.is(BlockTags.WOOL) || st.is(BlockTags.WOOL_CARPETS))) {
            return false;
        }
        int minY = Math.max(level.getMinBuildHeight(), y - 4);
        BlockPos.MutableBlockPos below = new BlockPos.MutableBlockPos(x, y - 1, z);
        for (int by = y - 1; by >= minY; by--) {
            below.setY(by);
            BlockState bst = level.getBlockState(below);
            if (bst.isAir() || isSkippedSurfaceFloraOrCanopy(bst))
                return true;
        }
        return false;
    }

    /**
     * Returns true for surface vegetation, tree canopies/trunks, snow layers, aquatic plants, and non-terrain
     * road/post fixtures (torches, lanterns, fences, walls, banners, signs) skipped when finding true ground/water.
     */
    private static boolean isSkippedSurfaceFloraOrCanopy(BlockState st) {
        if (isAquaticPlant(st))
            return true;
        if (st.is(Blocks.SNOW))
            return true;
        if (st.is(Blocks.LANTERN) || st.is(Blocks.SOUL_LANTERN)
                || st.is(Blocks.TORCH) || st.is(Blocks.WALL_TORCH)
                || st.is(Blocks.SOUL_TORCH) || st.is(Blocks.SOUL_WALL_TORCH)
                || st.is(Blocks.REDSTONE_TORCH) || st.is(Blocks.REDSTONE_WALL_TORCH)
                || st.is(BlockTags.FENCES) || st.is(BlockTags.WALLS) || st.is(BlockTags.FENCE_GATES)
                || st.is(BlockTags.BANNERS) || st.is(BlockTags.ALL_SIGNS) || st.is(BlockTags.SIGNS)) {
            return true;
        }
        return st.is(BlockTags.LOGS) || st.is(Blocks.MUSHROOM_STEM) || isCanopyLeaf(st)
                || st.is(BlockTags.SMALL_FLOWERS) || st.is(BlockTags.TALL_FLOWERS) || st.is(BlockTags.FLOWERS)
                || st.getBlock() instanceof DoublePlantBlock || st.is(BlockTags.SAPLINGS)
                || st.is(Blocks.SHORT_GRASS) || st.is(Blocks.TALL_GRASS)
                || st.is(Blocks.FERN) || st.is(Blocks.LARGE_FERN)
                || st.is(Blocks.SWEET_BERRY_BUSH) || st.is(Blocks.DEAD_BUSH) || st.is(Blocks.BAMBOO);
    }

    public static boolean isAquaticPlant(BlockState st) {
        return st.is(Blocks.LILY_PAD) || st.is(Blocks.SEAGRASS) || st.is(Blocks.TALL_SEAGRASS)
                || st.is(Blocks.KELP) || st.is(Blocks.KELP_PLANT);
    }

    private static boolean isWaterOrAquaticFluid(ServerLevel level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        FluidState fs = st.getFluidState();
        if (!fs.isEmpty() && !fs.is(FluidTags.LAVA))
            return true;
        return st.is(Blocks.WATER) || st.is(Blocks.SEAGRASS) || st.is(Blocks.TALL_SEAGRASS)
                || st.is(Blocks.KELP) || st.is(Blocks.KELP_PLANT);
    }

    private static int measureWaterDepth(ServerLevel level, int x, int z, int surfaceY) {
        int depth = 0;
        int minY = Math.max(level.getMinBuildHeight() + 1, surfaceY - 32);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, surfaceY, z);
        for (int y = surfaceY; y >= minY; y--) {
            cursor.setY(y);
            if (!isWaterOrAquaticFluid(level, cursor))
                break;
            depth++;
        }
        return depth;
    }

    private static int findWaterSurfaceBelow(ServerLevel level, int x, int z, int topY, int maxDrop) {
        int minY = Math.max(level.getMinBuildHeight() + 1, topY - maxDrop);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, topY - 1, z);
        for (int y = topY - 1; y >= minY; y--) {
            cursor.setY(y);
            if (isWaterOrAquaticFluid(level, cursor))
                return y;
        }
        return Integer.MIN_VALUE;
    }

    /** Finds the walkable standing Y coordinate on top of the road/bridge block at {@code (x, z)}. */
    public static int findWalkableY(ServerLevel level, int x, int z, RoadStyle style) {
        if (!level.hasChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z)))
            return level.getSeaLevel() + 1;
        int topY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int surfY = findGroundOrWaterSurfaceY(level, x, z, style);
        return Math.max(surfY + 1, topY);
    }

    private static int placeDryColumn(ServerLevel level, int x, int z, RoadStyle baseStyle, Collection<Village> villages,
            Map<Long, ColumnInfo> colCache, int referenceY, boolean lateralEdge) {
        ColumnInfo info = inspectColumn(level, colCache, x, z, baseStyle, villages);
        if (info == null || info.ocean() || info.lava() || info.protectedCol() || info.water())
            return Integer.MIN_VALUE;

        BlockState topState = level.getBlockState(new BlockPos(x, info.groundY(), z));
        boolean topIsRoad = isRoadLayer(topState, baseStyle);
        if (lateralEdge) {
            // A shoulder column already paved by a neighbouring step keeps its surface: re-paving it at this
            // step's height is what stacked path blocks and slabs on top of each other on hillsides.
            if (topIsRoad && referenceY != Integer.MIN_VALUE && Math.abs(info.groundY() - referenceY) <= 2)
                return Integer.MIN_VALUE;
            if (referenceY != Integer.MIN_VALUE && Math.abs(info.groundY() - referenceY) >= 3)
                return Integer.MIN_VALUE;
        }

        int targetY = lateralEdge
                ? Math.clamp(info.groundY(), referenceY - 1, referenceY + 1)
                : referenceY;
        BlockPos groundPos = new BlockPos(x, targetY, z);
        if (isProtected(level, groundPos, villages))
            return Integer.MIN_VALUE;

        if (!lateralEdge) {
            // The centerline owns its column: strip any road layer (shoulder blocks, transition slabs) that an
            // earlier step left above this height so exactly one road surface remains.
            removeRoadLayersAbove(level, x, z, targetY, Math.max(info.groundY(), targetY) + 2, baseStyle, villages);
        }

        BlockState existing = level.getBlockState(groundPos);
        // Cut any natural terrain/foliage above targetY up to max(info.groundY(), targetY) + HEADROOM_HEIGHT.
        int clearTopY = Math.max(info.groundY(), targetY) + HEADROOM_HEIGHT;
        clearColumnCutAndHeadroom(level, x, z, targetY, clearTopY, villages);

        RoadStyle localStyle = styleForPos(level, groundPos, baseStyle);
        if (!isBridgeDeckBlock(existing, localStyle)) {
            Block blockToPlace = pickDryBlock(x, z, localStyle, lateralEdge, existing);
            if (!existing.is(blockToPlace))
                level.setBlock(groundPos, blockToPlace.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        backfillFoundation(level, groundPos, villages);
        return targetY;
    }

    private static boolean isBridgeDeckBlock(BlockState st, RoadStyle style) {
        return st != null && (st.is(style.bridgeDeck()) || st.is(style.bridgeSlab()));
    }

    private static boolean hasExistingBridgeDeckInColumn(
            ServerLevel level, int x, int z, int minY, int maxY, RoadStyle style) {
        if (!level.hasChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z)))
            return false;
        int lo = Math.max(level.getMinBuildHeight(), minY);
        int hi = Math.min(level.getMaxBuildHeight() - 1, maxY);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, lo, z);
        for (int y = lo; y <= hi; y++) {
            cursor.setY(y);
            if (isBridgeDeckBlock(level.getBlockState(cursor), style))
                return true;
        }
        return false;
    }

    /** Road surface blocks and slabs this system (or a Millénaire village) lays down. */
    private static boolean isRoadLayer(BlockState st, RoadStyle style) {
        if (st == null || st.isAir())
            return false;
        return isRoadSurfaceBlock(st, style) || st.is(style.slabBlock());
    }

    private static void removeRoadLayersAbove(ServerLevel level, int x, int z, int roadY, int topY, RoadStyle style,
            Collection<Village> villages) {
        for (int y = roadY + 1; y <= topY; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState st = level.getBlockState(pos);
            if (!isRoadLayer(st, style) || isProtected(level, pos, villages))
                continue;
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    private static void clearColumnCutAndHeadroom(
            ServerLevel level, int x, int z, int roadY, int clearTopY, Collection<Village> villages) {
        for (int y = roadY + 1; y <= clearTopY; y++) {
            BlockPos above = new BlockPos(x, y, z);
            if (isProtected(level, above, villages))
                continue;
            BlockState st = level.getBlockState(above);
            if (st.isAir() || isWaterOrAquaticFluid(level, above))
                continue;
            if (isNaturalLog(level, above, villages)) {
                fellNaturalTree(level, above, villages);
            } else if (isClearableFoliage(st) || isShavableNaturalBlock(st)
                    || st.is(Blocks.COBBLESTONE_WALL) || st.is(Blocks.OAK_FENCE) || st.is(Blocks.LANTERN)) {
                level.setBlock(above, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
    }

    private static Block pickDryBlock(int x, int z, RoadStyle style, boolean lateralEdge, BlockState existing) {
        if (!lateralEdge || existing.is(style.fullBlock()) || isMillenairePathState(existing))
            return style.fullBlock();
        double r = coordHash01(x, z);
        if (r < 0.08)
            return Blocks.DIRT_PATH;
        if (r < 0.16)
            return Blocks.GRAVEL;
        if (r < 0.22)
            return Blocks.MOSSY_COBBLESTONE;
        return style.fullBlock();
    }

    private static Block pickSwampCausewayEdge(int x, int z) {
        double r = coordHash01(x, z);
        if (r < 0.35)
            return Blocks.DIRT_PATH;
        if (r < 0.65)
            return Blocks.GRAVEL;
        if (r < 0.85)
            return Blocks.MOSSY_COBBLESTONE;
        return ModBlocks.PATH_DIRT.get();
    }

    /**
     * Places half-step slabs only where the road itself changes height along its direction of travel, and only at
     * the foot of a rise that follows a level stretch. Continuous 1-block-per-step climbs get no slabs: topping
     * every block with a slab there just raises the whole staircase and produced stacked block+slab columns.
     */
    private static void placeAlongRoadTransitionSlabs(ServerLevel level, RoadSegment segment, List<Waypoint> steps,
            List<Integer> newlyDrySteps, RoadStyle baseStyle, Collection<Village> villages) {
        Set<Integer> candidates = new HashSet<>();
        for (int i : newlyDrySteps) {
            for (int k = i - 2; k <= i + 2; k++) {
                if (k >= 0 && k < steps.size())
                    candidates.add(k);
            }
        }
        for (int k : candidates) {
            int y = segment.getRealizedY(k);
            if (y == Integer.MIN_VALUE)
                continue;
            int prevY = k > 0 ? segment.getRealizedY(k - 1) : Integer.MIN_VALUE;
            int nextY = k + 1 < steps.size() ? segment.getRealizedY(k + 1) : Integer.MIN_VALUE;
            boolean riseAhead = nextY == y + 1 && prevY == y;
            boolean riseBehind = prevY == y + 1 && nextY == y;
            if (!riseAhead && !riseBehind)
                continue;
            Waypoint w = steps.get(k);
            Waypoint toward = steps.get(riseAhead ? k + 1 : k - 1);
            int fx = Integer.signum(toward.x() - w.x());
            int fz = Integer.signum(toward.z() - w.z());
            // Center column plus the two shoulders perpendicular to the travel direction.
            int[][] cols = { { 0, 0 }, { -fz, fx }, { fz, -fx } };
            for (int[] c : cols) {
                int bx = w.x() + c[0];
                int bz = w.z() + c[1];
                if (!level.hasChunk(SectionPos.blockToSectionCoord(bx), SectionPos.blockToSectionCoord(bz)))
                    continue;
                BlockPos below = new BlockPos(bx, y, bz);
                BlockPos slabPos = below.above();
                BlockState under = level.getBlockState(below);
                if (!isRoadSurfaceBlock(under, baseStyle) || under.getBlock() instanceof SlabBlock)
                    continue;
                BlockState st = level.getBlockState(slabPos);
                if (!(st.isAir() || isClearableFoliage(st)) || isProtected(level, slabPos, villages))
                    continue;
                RoadStyle localStyle = styleForPos(level, slabPos, baseStyle);
                BlockState bottomSlab = localStyle.slabBlock().defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
                level.setBlock(slabPos, bottomSlab, Block.UPDATE_CLIENTS);
            }
        }
    }

    private static boolean isRoadSurfaceBlock(BlockState st, RoadStyle style) {
        return st.is(style.fullBlock()) || isMillenairePathState(st)
                || st.is(Blocks.DIRT_PATH) || st.is(Blocks.GRAVEL) || st.is(Blocks.MOSSY_COBBLESTONE);
    }

    public static boolean isMillenairePathState(BlockState st) {
        if (st == null || st.isAir())
            return false;
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(st.getBlock());
        return "millenaire".equals(key.getNamespace()) && key.getPath().startsWith("path");
    }

    /**
     * Backfills empty air or replaceable voids beneath placed road blocks with dirt/cobblestone down to solid ground
     * so roads never float along hillsides, dips, or overhangs.
     */
    private static void backfillFoundation(ServerLevel level, BlockPos groundPos, Collection<Village> villages) {
        int minY = Math.max(level.getMinBuildHeight(), groundPos.getY() - MAX_BACKFILL_DEPTH);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(groundPos.getX(), groundPos.getY() - 1, groundPos.getZ());
        int depth = 0;
        while (cursor.getY() >= minY) {
            BlockState st = level.getBlockState(cursor);
            if (!st.isAir() && !st.canBeReplaced() && !isClearableFoliage(st)) {
                break;
            }
            if (isWaterOrAquaticFluid(level, cursor) || isLavaOrHazard(level, cursor)
                    || isProtected(level, cursor, villages)) {
                break;
            }
            Block fill = depth == 0 ? Blocks.DIRT : Blocks.COBBLESTONE;
            level.setBlock(cursor, fill.defaultBlockState(), Block.UPDATE_CLIENTS);
            cursor.move(0, -1, 0);
            depth++;
        }
    }

    /**
     * Shaves 1-2 blocks of overhanging natural dirt/grass/stone on uphill road shoulders (clearing any trees above)
     * so roads cut cleanly into gentle hillsides without steep overhanging walls right beside travelers.
     */
    private static void shaveSideSlopeOverhang(
            ServerLevel level, int x, int z, int roadY, RoadStyle style, Collection<Village> villages) {
        if (!level.hasChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z)))
            return;
        if (isOceanBiome(level, x, z))
            return;
        int groundY = findGroundOrWaterSurfaceY(level, x, z, style);
        if (groundY < roadY + 1 || groundY > roadY + 2)
            return;

        clearTreeAndHeadroom(level, new BlockPos(x, groundY, z), villages);
        for (int y = groundY; y >= roadY + 1; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            if (isProtected(level, pos, villages))
                continue;
            BlockState st = level.getBlockState(pos);
            if (isShavableNaturalBlock(st)) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
    }

    private static boolean isShavableNaturalBlock(BlockState st) {
        return st.is(BlockTags.DIRT) || st.is(BlockTags.SAND) || st.is(Blocks.GRASS_BLOCK)
                || st.is(Blocks.DIRT) || st.is(Blocks.COARSE_DIRT) || st.is(Blocks.PODZOL)
                || st.is(Blocks.GRAVEL) || st.is(Blocks.STONE) || st.is(Blocks.ANDESITE)
                || st.is(Blocks.GRANITE) || st.is(Blocks.DIORITE) || st.is(Blocks.TUFF)
                || st.is(Blocks.SNOW_BLOCK);
    }

    /** Places a sparse cobblestone wall + fence + lantern milestone 1 block outside the road shoulder. */
    private static boolean placeMilestoneIfDue(ServerLevel level, int cx, int cz,
            double px, double pz, int roadY, Collection<Village> villages,
            Map<Long, ColumnInfo> colCache, RoadStyle style) {
        if (hasNearbyMilestone(level, cx, cz, roadY, 6))
            return false;
        int[] sides = { 2, -2 };
        for (int side : sides) {
            int mx = (int) Math.round(cx + px * side);
            int mz = (int) Math.round(cz + pz * side);
            ColumnInfo info = inspectColumn(level, colCache, mx, mz, style, villages);
            if (info == null || info.ocean() || info.water() || info.lava() || info.protectedCol())
                continue;
            if (Math.abs(info.groundY() - roadY) > 2)
                continue;

            BlockPos base = new BlockPos(mx, info.groundY(), mz);
            BlockState baseSt = level.getBlockState(base);
            if (isRoadLayer(baseSt, style) || isBridgeDeckBlock(baseSt, style))
                continue;
            BlockPos p1 = base.above();
            BlockPos p2 = base.above(2);
            BlockPos p3 = base.above(3);
            if (isProtected(level, p1, villages) || isProtected(level, p2, villages) || isProtected(level, p3, villages))
                continue;
            BlockState st1 = level.getBlockState(p1);
            BlockState st2 = level.getBlockState(p2);
            BlockState st3 = level.getBlockState(p3);
            if (!(st1.isAir() || isClearableFoliage(st1))
                    || !(st2.isAir() || isClearableFoliage(st2))
                    || !(st3.isAir() || isClearableFoliage(st3))) {
                continue;
            }
            clearTreeAndHeadroom(level, base, villages);
            level.setBlock(p1, Blocks.COBBLESTONE_WALL.defaultBlockState(), Block.UPDATE_CLIENTS);
            level.setBlock(p2, Blocks.OAK_FENCE.defaultBlockState(), Block.UPDATE_CLIENTS);
            level.setBlock(p3, Blocks.LANTERN.defaultBlockState(), Block.UPDATE_CLIENTS);
            return true;
        }
        return false;
    }

    private static boolean hasNearbyMilestone(ServerLevel level, int cx, int cz, int roadY, int radius) {
        int minY = Math.max(level.getMinBuildHeight(), roadY - 1);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, roadY + 5);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            int x = cx + dx;
            int chunkX = SectionPos.blockToSectionCoord(x);
            for (int dz = -radius; dz <= radius; dz++) {
                int z = cz + dz;
                if (!level.hasChunk(chunkX, SectionPos.blockToSectionCoord(z)))
                    continue;
                for (int y = minY; y <= maxY; y++) {
                    cursor.set(x, y, z);
                    BlockState st = level.getBlockState(cursor);
                    if (st.is(Blocks.LANTERN) || st.is(Blocks.SOUL_LANTERN))
                        return true;
                }
            }
        }
        return false;
    }

    private static void fillSwampCausewayFoundation(
            ServerLevel level, int x, int z, int topFillY, Collection<Village> villages) {
        int minY = Math.max(level.getMinBuildHeight() + 1, topFillY - 24);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, topFillY, z);
        for (int y = topFillY; y >= minY; y--) {
            cursor.setY(y);
            BlockState st = level.getBlockState(cursor);
            if (!isWaterOrAquaticFluid(level, cursor) && !st.isAir() && !st.canBeReplaced() && !isAquaticPlant(st))
                break;
            if (isProtected(level, cursor, villages))
                break;
            Block fill = (y == topFillY) ? Blocks.DIRT : Blocks.COBBLESTONE;
            level.setBlock(cursor, fill.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    private static void fillCausewayFoundation(
            ServerLevel level, int x, int z, int topFillY, boolean shoulder, Collection<Village> villages) {
        int minY = Math.max(level.getMinBuildHeight() + 1, topFillY - 24);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, topFillY, z);
        for (int y = topFillY; y >= minY; y--) {
            cursor.setY(y);
            BlockState st = level.getBlockState(cursor);
            if (!isWaterOrAquaticFluid(level, cursor) && !st.isAir() && !st.canBeReplaced() && !isAquaticPlant(st))
                break;
            if (isProtected(level, cursor, villages))
                break;
            boolean gravelAccent = shoulder && y == topFillY && coordHash01(x, z) < 0.3;
            Block fill = gravelAccent ? Blocks.GRAVEL : Blocks.COBBLESTONE;
            level.setBlock(cursor, fill.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    private static void fillPilingColumn(
            ServerLevel level, int x, int z, int topY, Block supportBlock, Collection<Village> villages) {
        int minY = Math.max(level.getMinBuildHeight() + 1, topY - 48);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, topY, z);
        for (int y = topY; y >= minY; y--) {
            cursor.setY(y);
            BlockState st = level.getBlockState(cursor);
            boolean waterOrPlant = isWaterOrAquaticFluid(level, cursor) || isAquaticPlant(st);
            if (!waterOrPlant && !st.isAir() && !st.canBeReplaced() && !st.is(supportBlock))
                break;
            if (isProtected(level, cursor, villages))
                break;
            Block fill = waterOrPlant ? Blocks.COBBLESTONE : supportBlock;
            level.setBlock(cursor, fill.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    /**
     * Clears natural trees, attached leaf canopies, hanging vines, and mangrove logs/roots from {@code minClearY}
     * through {@code HEADROOM_HEIGHT} blocks above {@code deckY}, plus surrounding canopy shoulders.
     */
    private static void clearBridgeColumnAndCanopy(
            ServerLevel level, int x, int z, int minClearY, int deckY, Collection<Village> villages) {
        if (!level.hasChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z)))
            return;
        for (int y = Math.min(minClearY, deckY); y <= deckY + HEADROOM_HEIGHT; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            if (isProtected(level, pos, villages))
                continue;
            BlockState st = level.getBlockState(pos);
            if (st.isAir())
                continue;
            if (st.is(Blocks.LILY_PAD)) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                continue;
            }
            if (isWaterOrAquaticFluid(level, pos))
                continue;
            if (isNaturalLog(level, pos, villages)) {
                fellNaturalTree(level, pos, villages);
            } else if (isClearableFoliage(st)) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        clearShoulderTrees(level, x, z, deckY, villages);
    }

    private static void clearTreeAndHeadroom(ServerLevel level, BlockPos surfacePos, Collection<Village> villages) {
        for (int dy = 1; dy <= HEADROOM_HEIGHT; dy++) {
            BlockPos above = surfacePos.above(dy);
            if (isProtected(level, above, villages))
                continue;
            BlockState st = level.getBlockState(above);
            if (st.isAir())
                continue;
            if (isNaturalLog(level, above, villages)) {
                fellNaturalTree(level, above, villages);
            } else if (isClearableFoliage(st)) {
                level.setBlock(above, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
    }

    private static void clearShoulderTrees(
            ServerLevel level, int x, int z, int roadY, Collection<Village> villages) {
        if (!level.hasChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z)))
            return;
        for (int dy = 0; dy <= HEADROOM_HEIGHT; dy++) {
            BlockPos pos = new BlockPos(x, roadY + dy, z);
            if (isNaturalLog(level, pos, villages)) {
                fellNaturalTree(level, pos, villages);
                break;
            }
        }
    }

    private static void fellNaturalTree(ServerLevel level, BlockPos seedLog, Collection<Village> villages) {
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> logs = new HashSet<>();
        BlockPos base = seedLog;
        while (base.getY() > level.getMinBuildHeight() + 1 && isNaturalLog(level, base.below(), villages)) {
            base = base.below();
        }
        queue.add(base);
        logs.add(base);
        while (!queue.isEmpty() && logs.size() < 64) {
            BlockPos curr = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = 0; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0)
                            continue;
                        BlockPos next = curr.offset(dx, dy, dz);
                        if (!logs.contains(next)
                                && level.hasChunk(SectionPos.blockToSectionCoord(next.getX()),
                                        SectionPos.blockToSectionCoord(next.getZ()))
                                && isNaturalLog(level, next, villages)) {
                            logs.add(next);
                            queue.add(next);
                        }
                    }
                }
            }
        }
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (BlockPos logPos : logs) {
            level.setBlock(logPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            if (logPos.getY() < minY)
                minY = logPos.getY();
            if (logPos.getY() > maxY)
                maxY = logPos.getY();
        }
        for (BlockPos logPos : logs) {
            for (int dx = -TREE_CANOPY_RADIUS; dx <= TREE_CANOPY_RADIUS; dx++) {
                for (int dz = -TREE_CANOPY_RADIUS; dz <= TREE_CANOPY_RADIUS; dz++) {
                    if (dx * dx + dz * dz > TREE_CANOPY_RADIUS * TREE_CANOPY_RADIUS)
                        continue;
                    int cx = logPos.getX() + dx;
                    int cz = logPos.getZ() + dz;
                    if (!level.hasChunk(SectionPos.blockToSectionCoord(cx), SectionPos.blockToSectionCoord(cz)))
                        continue;
                    for (int y = Math.max(logPos.getY() - 1, minY); y <= Math.min(logPos.getY() + 3, maxY + 3); y++) {
                        BlockPos lp = new BlockPos(cx, y, cz);
                        if (isProtected(level, lp, villages))
                            continue;
                        BlockState lst = level.getBlockState(lp);
                        if (isCanopyLeaf(lst)) {
                            level.setBlock(lp, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                        }
                    }
                }
            }
        }
    }

    private static boolean isNaturalLog(ServerLevel level, BlockPos pos, Collection<Village> villages) {
        if (isProtected(level, pos, villages))
            return false;
        BlockState st = level.getBlockState(pos);
        if (!st.is(BlockTags.LOGS) && !st.is(Blocks.MUSHROOM_STEM))
            return false;
        String path = BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
        if (path.contains("stripped"))
            return false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0)
                    continue;
                BlockPos adj = pos.offset(dx, 0, dz);
                if (!level.hasChunk(SectionPos.blockToSectionCoord(adj.getX()),
                        SectionPos.blockToSectionCoord(adj.getZ())))
                    continue;
                BlockState ast = level.getBlockState(adj);
                if (ast.is(BlockTags.PLANKS) || ast.is(BlockTags.DOORS) || ast.is(BlockTags.WOODEN_STAIRS)
                        || ast.is(Blocks.GLASS) || ast.is(Blocks.GLASS_PANE) || ast.is(Blocks.TORCH)
                        || ast.is(Blocks.LANTERN)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isCanopyLeaf(BlockState st) {
        return st.is(BlockTags.LEAVES) || st.is(BlockTags.WART_BLOCKS) || st.is(Blocks.SHROOMLIGHT)
                || st.is(Blocks.BROWN_MUSHROOM_BLOCK) || st.is(Blocks.RED_MUSHROOM_BLOCK)
                || st.is(Blocks.VINE) || st.is(Blocks.MANGROVE_ROOTS) || st.is(Blocks.MUDDY_MANGROVE_ROOTS)
                || st.is(Blocks.MOSS_CARPET) || st.is(Blocks.HANGING_ROOTS);
    }

    private static boolean isClearableFoliage(BlockState st) {
        return isCanopyLeaf(st) || isAquaticPlant(st) || st.canBeReplaced()
                || st.is(BlockTags.SMALL_FLOWERS) || st.is(BlockTags.TALL_FLOWERS) || st.is(BlockTags.FLOWERS)
                || st.getBlock() instanceof DoublePlantBlock || st.is(BlockTags.SNOW)
                || st.is(BlockTags.SAPLINGS) || st.is(Blocks.SHORT_GRASS) || st.is(Blocks.TALL_GRASS)
                || st.is(Blocks.FERN) || st.is(Blocks.LARGE_FERN) || st.is(Blocks.SWEET_BERRY_BUSH)
                || st.is(Blocks.DEAD_BUSH) || st.is(Blocks.BAMBOO);
    }

    public static boolean isOceanBiome(ServerLevel level, int x, int z) {
        Holder<Biome> biome = level.getBiome(new BlockPos(x, level.getSeaLevel(), z));
        return RoadPlanner.isOceanBiome(biome);
    }

    private static boolean isLavaOrHazard(ServerLevel level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        return st.getFluidState().is(FluidTags.LAVA) || st.is(Blocks.LAVA) || st.is(Blocks.MAGMA_BLOCK)
                || st.is(Blocks.FIRE) || st.is(Blocks.SOUL_FIRE) || st.is(Blocks.CACTUS);
    }

    /**
     * Protects actual Millénaire building bounding boxes (with 1-block wall clearance for standard buildings, while
     * allowing roads to connect flush to Border Gate thresholds), farm crop plots, and player-placed containers.
     */
    public static boolean isProtected(ServerLevel level, BlockPos pos, Collection<Village> villages) {
        for (Village v : villages) {
            for (BuildingInstance b : v.getBuildings()) {
                if (isInsideBuildingFootprint(level, b, pos))
                    return true;
            }
        }
        for (int dy = -1; dy <= 2; dy++) {
            BlockPos check = pos.offset(0, dy, 0);
            if (!level.hasChunk(SectionPos.blockToSectionCoord(check.getX()),
                    SectionPos.blockToSectionCoord(check.getZ())))
                continue;
            if (level.getBlockEntity(check) != null)
                return true;
            BlockState st = level.getBlockState(check);
            if (isProtectedState(st))
                return true;
        }
        return false;
    }

    private static boolean isInsideBuildingFootprint(ServerLevel level, BuildingInstance b, BlockPos pos) {
        boolean isGate = RoadService.isGateOrBorderPost(b);
        if (isGate) {
            // For Border Gates / border posts, allow road placement on ground blocks up to the gate threshold
            // while protecting actual gate pillars, walls, signs, and banners above ground.
            if (!b.containsPos(pos))
                return false;
            BlockPos origin = b.getOrigin();
            if (origin != null && pos.getY() > origin.getY())
                return true;
            if (level.hasChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()))) {
                BlockState st = level.getBlockState(pos);
                return !st.isAir() && !isShavableNaturalBlock(st) && !isMillenairePathState(st)
                        && !isClearableFoliage(st);
            }
            return false;
        }

        if (b.containsPos(pos))
            return true;
        BlockPos origin = b.getOrigin();
        int ox = origin != null ? origin.getX() : 0;
        int oz = origin != null ? origin.getZ() : 0;
        b.getEffectiveWidth();
        int minX = ox + b.getCachedMinX();
        int maxX = ox + b.getCachedMaxX();
        int minZ = oz + b.getCachedMinZ();
        int maxZ = oz + b.getCachedMaxZ();
        if (origin != null && b.getEffectiveWidth() > 0 && maxX >= minX) {
            return pos.getX() >= minX - 1 && pos.getX() <= maxX + 1
                    && pos.getZ() >= minZ - 1 && pos.getZ() <= maxZ + 1;
        }
        return origin != null && Math.abs(pos.getX() - origin.getX()) <= 1
                && Math.abs(pos.getZ() - origin.getZ()) <= 1;
    }

    private static boolean isProtectedState(BlockState st) {
        Block block = st.getBlock();
        if (st.isAir())
            return false;
        if (block instanceof CropBlock || block instanceof ChestBlock || block instanceof Container)
            return true;
        if (st.is(BlockTags.CROPS) || st.is(Blocks.FARMLAND) || st.is(BlockTags.DOORS)
                || st.is(BlockTags.FENCE_GATES) || st.is(BlockTags.BANNERS)
                || st.is(BlockTags.BEDS) || st.is(BlockTags.SIGNS) || st.is(BlockTags.ALL_SIGNS)
                || st.is(BlockTags.SHULKER_BOXES) || st.is(Blocks.BARREL) || st.is(Blocks.HOPPER)
                || st.is(Blocks.DISPENSER) || st.is(Blocks.DROPPER) || st.is(Blocks.FURNACE)
                || st.is(Blocks.BLAST_FURNACE) || st.is(Blocks.SMOKER) || st.is(Blocks.CRAFTING_TABLE)
                || st.is(Blocks.ANVIL) || st.is(ModBlocks.RICE_PADDY.get())) {
            return true;
        }
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
        if ("millenaire".equals(key.getNamespace())) {
            String path = key.getPath();
            if (path.contains("crop") || path.contains("chest") || path.contains("sign")
                    || path.contains("firepit") || path.contains("kiln") || path.contains("panel"))
                return true;
        }
        return false;
    }

    private static double coordHash01(int x, int z) {
        long h = 0x517cc1b727220a95L ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= (h >>> 31);
        return ((h >>> 11) & 0x1FFFFFL) / (double) (1L << 21);
    }

    private static long pack(int x, int z) {
        return (((long) x) << 32) | (z & 0xffffffffL);
    }

    private static ResourceLocation parseLocation(String raw) {
        if (raw == null || raw.isBlank())
            return null;
        return ResourceLocation.tryParse(raw);
    }
}
