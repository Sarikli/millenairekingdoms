package com.millenairekingdoms.roads;

import com.millenairekingdoms.roads.RoadSavedData.RoadSegment;
import com.millenairekingdoms.roads.RoadSavedData.Status;
import com.millenairekingdoms.roads.RoadSavedData.Waypoint;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Chunk-generation-free 2D road planner. Uses only {@link BiomeSource} and
 * {@link ChunkGenerator#getBaseHeight} noise sampling so ungenerated chunks are never loaded or generated
 * during planning.
 *
 * <p>Plans organic curved routes via weighted 2D A* with deterministic Perlin noise perturbation, strict Ocean
 * biome prohibition ({@link BiomeTags#IS_OCEAN}), high River/Mountain/Hill traversal penalties, severe steep-cliff
 * slope rejection for valley/shoreline-following routes, and Catmull-Rom cubic spline smoothing.
 */
public final class RoadPlanner {
    private static final int OCEAN_SAMPLE_STEP = 4;
    private static final double RIVER_COST_MULTIPLIER = 10.0;
    private static final double MOUNTAIN_COST_MULTIPLIER = 12.0;
    private static final double HILL_COST_MULTIPLIER = 8.0;
    private static final double SWAMP_COST_MULTIPLIER = 1.6;
    private static final double SLOPE_PENALTY_WEIGHT = 3.5;
    private static final double STEEP_CLIFF_COST_MULTIPLIER = 55.0;
    private static final double ELEVATION_ABOVE_BASE_WEIGHT = 0.65;
    private static final double PERLIN_FREQUENCY = 0.028;
    private static final int MAX_ASTAR_EXPANSIONS = 32000;

    @FunctionalInterface
    public interface BiomeQuery {
        Holder<Biome> biomeAt(int x, int z);
    }

    @FunctionalInterface
    public interface HeightQuery {
        int heightAt(int x, int z);

        /**
         * Depth of standing water above the ground at {@code (x, z)} (0 = dry land). Lakes and rivers in non-river
         * biomes are otherwise invisible to the planner, because the surface height of water looks like flat land.
         */
        default int waterDepthAt(int x, int z) {
            return 0;
        }
    }

    /** Cost multiplier for A* cells over water (at least as expensive as a river biome). */
    private static final double WATER_COST_MULTIPLIER = 12.0;
    /** Cost multiplier for deep water ({@link #DEEP_WATER_DEPTH}+ blocks), e.g. the middle of a large lake. */
    private static final double DEEP_WATER_COST_MULTIPLIER = 24.0;
    private static final int DEEP_WATER_DEPTH = 5;
    /** Cost discount when riding along an existing road/bridge instead of building a parallel span. */
    private static final double EXISTING_ROAD_COST_MULTIPLIER = 0.85;

    /** Matched span along a previously planned/built road segment for A* edge {@code (x1,z1)->(x2,z2)}. */
    public record EdgeMatch(int segmentIndex, int startStep, int endStep) {}

    /** Spatial query allowing new road plans to detect and reuse existing roads and bridges. */
    public interface ExistingRoadQuery {
        ExistingRoadQuery NONE = new ExistingRoadQuery() {
            @Override
            public boolean mightBeNear(int x, int z, int radius) {
                return false;
            }

            @Override
            public EdgeMatch matchEdge(int x1, int z1, int x2, int z2, int radius,
                    int minStepDelta, int maxStepDelta, int preferredSegIdx) {
                return null;
            }

            @Override
            public List<Waypoint> segmentSteps(int segmentIndex) {
                return List.of();
            }
        };

        boolean mightBeNear(int x, int z, int radius);

        EdgeMatch matchEdge(int x1, int z1, int x2, int z2, int radius,
                int minStepDelta, int maxStepDelta, int preferredSegIdx);

        List<Waypoint> segmentSteps(int segmentIndex);
    }

    /** 16-block spatial hash index of non-cancelled roads inside the planning corridor. */
    public static final class ExistingRoadIndex implements ExistingRoadQuery {
        private static final int CELL_SHIFT = 4;
        private static final int CORRIDOR_PAD = 640;
        private static final int MIDPOINT_STEP_SLACK = 4;

        private record Entry(int segIdx, int stepIdx, int x, int z) {}

        private final List<List<Waypoint>> segments;
        private final Map<Long, List<Entry>> buckets;

        private ExistingRoadIndex(List<List<Waypoint>> segments, Map<Long, List<Entry>> buckets) {
            this.segments = segments;
            this.buckets = buckets;
        }

        /** Indexes active/completed/paused segments touching the corridor between {@code a} and {@code b}. */
        public static ExistingRoadQuery fromSegments(
                Collection<RoadSegment> allSegments, BlockPos a, BlockPos b) {
            if (allSegments == null || allSegments.isEmpty() || a == null || b == null)
                return ExistingRoadQuery.NONE;
            int minX = Math.min(a.getX(), b.getX()) - CORRIDOR_PAD;
            int maxX = Math.max(a.getX(), b.getX()) + CORRIDOR_PAD;
            int minZ = Math.min(a.getZ(), b.getZ()) - CORRIDOR_PAD;
            int maxZ = Math.max(a.getZ(), b.getZ()) + CORRIDOR_PAD;

            List<List<Waypoint>> qualifying = new ArrayList<>();
            for (RoadSegment seg : allSegments) {
                if (seg == null || seg.status() == Status.CANCELLED)
                    continue;
                List<Waypoint> steps = seg.steps();
                if (steps == null || steps.size() < 2)
                    continue;
                boolean inCorridor = false;
                for (Waypoint w : steps) {
                    if (w.x() >= minX && w.x() <= maxX && w.z() >= minZ && w.z() <= maxZ) {
                        inCorridor = true;
                        break;
                    }
                }
                if (inCorridor) {
                    qualifying.add(steps);
                }
            }
            return fromWaypointLists(qualifying);
        }

        /** Builds an index directly from pre-planned waypoint sequences (primarily for tests). */
        public static ExistingRoadQuery fromWaypointLists(List<List<Waypoint>> routes) {
            if (routes == null || routes.isEmpty())
                return ExistingRoadQuery.NONE;
            List<List<Waypoint>> segments = new ArrayList<>(routes.size());
            Map<Long, List<Entry>> buckets = new HashMap<>();
            for (List<Waypoint> route : routes) {
                if (route == null || route.size() < 2)
                    continue;
                int segIdx = segments.size();
                segments.add(route);
                for (int s = 0; s < route.size(); s++) {
                    Waypoint w = route.get(s);
                    long key = pack(w.x() >> CELL_SHIFT, w.z() >> CELL_SHIFT);
                    buckets.computeIfAbsent(key, k -> new ArrayList<>())
                            .add(new Entry(segIdx, s, w.x(), w.z()));
                }
            }
            if (segments.isEmpty())
                return ExistingRoadQuery.NONE;
            return new ExistingRoadIndex(List.copyOf(segments), buckets);
        }

        @Override
        public boolean mightBeNear(int x, int z, int radius) {
            int minBx = (x - radius) >> CELL_SHIFT;
            int maxBx = (x + radius) >> CELL_SHIFT;
            int minBz = (z - radius) >> CELL_SHIFT;
            int maxBz = (z + radius) >> CELL_SHIFT;
            for (int bx = minBx; bx <= maxBx; bx++) {
                for (int bz = minBz; bz <= maxBz; bz++) {
                    if (buckets.containsKey(pack(bx, bz)))
                        return true;
                }
            }
            return false;
        }

        @Override
        public EdgeMatch matchEdge(int x1, int z1, int x2, int z2, int radius,
                int minStepDelta, int maxStepDelta, int preferredSegIdx) {
            if (!mightBeNear(x1, z1, radius) || !mightBeNear(x2, z2, radius))
                return null;
            long rSq = (long) radius * radius;
            Map<Integer, Entry> e1BySeg = findClosestBySeg(x1, z1, radius, rSq);
            if (e1BySeg.isEmpty())
                return null;

            Map<Integer, Entry> e2BySeg = new HashMap<>(4);
            int minBx2 = (x2 - radius) >> CELL_SHIFT;
            int maxBx2 = (x2 + radius) >> CELL_SHIFT;
            int minBz2 = (z2 - radius) >> CELL_SHIFT;
            int maxBz2 = (z2 + radius) >> CELL_SHIFT;
            for (int bx = minBx2; bx <= maxBx2; bx++) {
                for (int bz = minBz2; bz <= maxBz2; bz++) {
                    List<Entry> list = buckets.get(pack(bx, bz));
                    if (list == null)
                        continue;
                    for (Entry e : list) {
                        Entry e1 = e1BySeg.get(e.segIdx);
                        if (e1 == null)
                            continue;
                        int delta = Math.abs(e.stepIdx - e1.stepIdx);
                        if (delta < minStepDelta || delta > maxStepDelta)
                            continue;
                        long dSq = distSq(x2, z2, e.x, e.z);
                        if (dSq > rSq)
                            continue;
                        Entry prevBest = e2BySeg.get(e.segIdx);
                        if (prevBest == null || dSq < distSq(x2, z2, prevBest.x, prevBest.z)) {
                            e2BySeg.put(e.segIdx, e);
                        }
                    }
                }
            }
            if (e2BySeg.isEmpty())
                return null;

            int mx = (x1 + x2) / 2;
            int mz = (z1 + z2) / 2;
            Set<Integer> confirmed = new HashSet<>(4);
            int minBxm = (mx - radius) >> CELL_SHIFT;
            int maxBxm = (mx + radius) >> CELL_SHIFT;
            int minBzm = (mz - radius) >> CELL_SHIFT;
            int maxBzm = (mz + radius) >> CELL_SHIFT;
            for (int bx = minBxm; bx <= maxBxm; bx++) {
                for (int bz = minBzm; bz <= maxBzm; bz++) {
                    List<Entry> list = buckets.get(pack(bx, bz));
                    if (list == null)
                        continue;
                    for (Entry e : list) {
                        Entry e1 = e1BySeg.get(e.segIdx);
                        Entry e2 = e2BySeg.get(e.segIdx);
                        if (e1 == null || e2 == null || confirmed.contains(e.segIdx))
                            continue;
                        if (distSq(mx, mz, e.x, e.z) > rSq)
                            continue;
                        int lo = Math.min(e1.stepIdx, e2.stepIdx) - MIDPOINT_STEP_SLACK;
                        int hi = Math.max(e1.stepIdx, e2.stepIdx) + MIDPOINT_STEP_SLACK;
                        if (e.stepIdx >= lo && e.stepIdx <= hi) {
                            confirmed.add(e.segIdx);
                        }
                    }
                }
            }
            if (confirmed.isEmpty())
                return null;

            if (preferredSegIdx >= 0 && confirmed.contains(preferredSegIdx)) {
                Entry e1 = e1BySeg.get(preferredSegIdx);
                Entry e2 = e2BySeg.get(preferredSegIdx);
                return new EdgeMatch(preferredSegIdx, e1.stepIdx, e2.stepIdx);
            }

            int bestSeg = -1;
            long bestScore = Long.MAX_VALUE;
            for (int segIdx : confirmed) {
                Entry e1 = e1BySeg.get(segIdx);
                Entry e2 = e2BySeg.get(segIdx);
                long score = distSq(x1, z1, e1.x, e1.z) + distSq(x2, z2, e2.x, e2.z);
                if (score < bestScore) {
                    bestScore = score;
                    bestSeg = segIdx;
                }
            }
            Entry bestE1 = e1BySeg.get(bestSeg);
            Entry bestE2 = e2BySeg.get(bestSeg);
            return new EdgeMatch(bestSeg, bestE1.stepIdx, bestE2.stepIdx);
        }

        private Map<Integer, Entry> findClosestBySeg(int x, int z, int radius, long rSq) {
            Map<Integer, Entry> best = new HashMap<>(4);
            int minBx = (x - radius) >> CELL_SHIFT;
            int maxBx = (x + radius) >> CELL_SHIFT;
            int minBz = (z - radius) >> CELL_SHIFT;
            int maxBz = (z + radius) >> CELL_SHIFT;
            for (int bx = minBx; bx <= maxBx; bx++) {
                for (int bz = minBz; bz <= maxBz; bz++) {
                    List<Entry> list = buckets.get(pack(bx, bz));
                    if (list == null)
                        continue;
                    for (Entry e : list) {
                        long dSq = distSq(x, z, e.x, e.z);
                        if (dSq > rSq)
                            continue;
                        Entry cur = best.get(e.segIdx);
                        if (cur == null || dSq < distSq(x, z, cur.x, cur.z)) {
                            best.put(e.segIdx, e);
                        }
                    }
                }
            }
            return best;
        }

        @Override
        public List<Waypoint> segmentSteps(int segmentIndex) {
            return (segmentIndex >= 0 && segmentIndex < segments.size()) ? segments.get(segmentIndex) : List.of();
        }

        private static long distSq(int x1, int z1, int x2, int z2) {
            long dx = (long) x1 - x2;
            long dz = (long) z1 - z2;
            return dx * dx + dz * dz;
        }
    }

    /** Returns true when {@code (x, z)} lies inside a village footprint the road should not cut through. */
    @FunctionalInterface
    public interface FootprintQuery {
        FootprintQuery NONE = (x, z) -> false;

        boolean blocked(int x, int z);
    }

    /** Cost multiplier for A* cells inside a village footprint (soft wall so isolated villages stay reachable). */
    private static final double FOOTPRINT_COST_MULTIPLIER = 40.0;
    /** Steps at each end of a route allowed inside a footprint (the gate/path connection itself). */
    private static final int FOOTPRINT_END_ALLOWANCE = 10;

    public record PlanResult(boolean ok, List<Waypoint> waypoints, int totalBlocks, String failureReason) {
        public static PlanResult success(List<Waypoint> waypoints, int totalBlocks) {
            return new PlanResult(true, waypoints, totalBlocks, null);
        }
        public static PlanResult fail(String reason) {
            return new PlanResult(false, List.of(), 0, reason);
        }
    }

    public record Sample(int x, int z, double dirX, double dirZ) {}

    private RoadPlanner() {}

    /**
     * Plans a 2D route between {@code start} and {@code end} without loading or generating any chunks.
     */
    public static PlanResult plan(ServerLevel level, BlockPos start, BlockPos end, RoadConfig.Settings cfg) {
        BiomeQuery biomeQuery = chunkFreeBiomeQuery(level);
        HeightQuery heightQuery = chunkFreeHeightQuery(level);
        return plan(start, end, cfg, biomeQuery, heightQuery);
    }

    /**
     * Plans a 2D route between {@code startThresh} and {@code endThresh} using orthogonal flush approaches
     * ({@code startThresh -> startApproach} and {@code endApproach -> endThresh}) so roads meet village border
     * gates head-on without cutting diagonally across gate walls.
     */
    public static PlanResult planWithFlushApproaches(ServerLevel level, BlockPos startThresh, BlockPos startApproach,
            BlockPos endApproach, BlockPos endThresh, RoadConfig.Settings cfg) {
        return planWithFlushApproaches(level, startThresh, startApproach, endApproach, endThresh, cfg,
                FootprintQuery.NONE);
    }

    public static PlanResult planWithFlushApproaches(ServerLevel level, BlockPos startThresh, BlockPos startApproach,
            BlockPos endApproach, BlockPos endThresh, RoadConfig.Settings cfg, FootprintQuery footprint) {
        return planWithFlushApproaches(level, startThresh, startApproach, endApproach, endThresh, cfg, footprint,
                ExistingRoadQuery.NONE);
    }

    public static PlanResult planWithFlushApproaches(ServerLevel level, BlockPos startThresh, BlockPos startApproach,
            BlockPos endApproach, BlockPos endThresh, RoadConfig.Settings cfg, FootprintQuery footprint,
            ExistingRoadQuery existingRoads) {
        return planWithFlushApproaches(startThresh, startApproach, endApproach, endThresh, cfg,
                chunkFreeBiomeQuery(level), chunkFreeHeightQuery(level), footprint, existingRoads);
    }

    public static PlanResult planWithFlushApproaches(BlockPos startThresh, BlockPos startApproach,
            BlockPos endApproach, BlockPos endThresh, RoadConfig.Settings cfg,
            BiomeQuery biomeQuery, HeightQuery heightQuery) {
        return planWithFlushApproaches(startThresh, startApproach, endApproach, endThresh, cfg, biomeQuery,
                heightQuery, FootprintQuery.NONE, ExistingRoadQuery.NONE);
    }

    public static PlanResult planWithFlushApproaches(BlockPos startThresh, BlockPos startApproach,
            BlockPos endApproach, BlockPos endThresh, RoadConfig.Settings cfg,
            BiomeQuery biomeQuery, HeightQuery heightQuery, FootprintQuery footprint) {
        return planWithFlushApproaches(startThresh, startApproach, endApproach, endThresh, cfg, biomeQuery,
                heightQuery, footprint, ExistingRoadQuery.NONE);
    }

    public static PlanResult planWithFlushApproaches(BlockPos startThresh, BlockPos startApproach,
            BlockPos endApproach, BlockPos endThresh, RoadConfig.Settings cfg,
            BiomeQuery biomeQuery, HeightQuery heightQuery, FootprintQuery footprint,
            ExistingRoadQuery existingRoads) {
        FootprintQuery fp = footprint == null ? FootprintQuery.NONE : footprint;
        ExistingRoadQuery er = existingRoads == null ? ExistingRoadQuery.NONE : existingRoads;
        double threshDist = Math.hypot((double) endThresh.getX() - startThresh.getX(),
                (double) endThresh.getZ() - startThresh.getZ());
        if (threshDist < 4.0)
            return PlanResult.fail("Villages are too close for a separate highway.");
        if (threshDist > cfg.maxSegmentDistance())
            return PlanResult.fail("OVER_DISTANCE");

        double approachDist = Math.hypot((double) endApproach.getX() - startApproach.getX(),
                (double) endApproach.getZ() - startApproach.getZ());
        boolean useApproaches = threshDist >= 16.0 && approachDist >= 4.0
                && (!startThresh.equals(startApproach) || !endThresh.equals(endApproach))
                && isSegmentOceanFree(startThresh.getX(), startThresh.getZ(),
                        startApproach.getX(), startApproach.getZ(), biomeQuery)
                && isSegmentOceanFree(endApproach.getX(), endApproach.getZ(),
                        endThresh.getX(), endThresh.getZ(), biomeQuery);

        if (!useApproaches) {
            return plan(startThresh, endThresh, cfg, biomeQuery, heightQuery, fp, er);
        }

        PlanResult middle = plan(startApproach, endApproach, cfg, biomeQuery, heightQuery, fp, er);
        if (!middle.ok()) {
            return plan(startThresh, endThresh, cfg, biomeQuery, heightQuery, fp, er);
        }

        List<Waypoint> combined = new ArrayList<>(middle.waypoints().size() + 2);
        Waypoint sT = new Waypoint(startThresh.getX(), startThresh.getZ());
        Waypoint eT = new Waypoint(endThresh.getX(), endThresh.getZ());
        combined.add(sT);
        for (Waypoint w : middle.waypoints()) {
            if (combined.getLast().x() != w.x() || combined.getLast().z() != w.z()) {
                combined.add(w);
            }
        }
        if (combined.getLast().x() != eT.x() || combined.getLast().z() != eT.z()) {
            combined.add(eT);
        }
        List<Waypoint> steps = rasterize8Connected(combined);
        int totalBlocks = steps.size();
        int maxAllowed = Math.max(cfg.maxSegmentDistance(), (int) Math.round(cfg.maxSegmentDistance() * 2.2));
        if (totalBlocks > maxAllowed) {
            return PlanResult.fail("OVER_DISTANCE");
        }
        return PlanResult.success(List.copyOf(steps), Math.max(1, totalBlocks));
    }

    private static BiomeQuery chunkFreeBiomeQuery(ServerLevel level) {
        BiomeSource biomeSource = level.getChunkSource().getGenerator().getBiomeSource();
        Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
        int seaQuartY = QuartPos.fromBlock(level.getSeaLevel());
        return (x, z) ->
                biomeSource.getNoiseBiome(QuartPos.fromBlock(x), seaQuartY, QuartPos.fromBlock(z), sampler);
    }

    private static HeightQuery chunkFreeHeightQuery(ServerLevel level) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();
        int seaLevel = level.getSeaLevel();
        Map<Long, Integer> surfaceCache = new HashMap<>();
        Map<Long, Integer> waterCache = new HashMap<>();
        return new HeightQuery() {
            @Override
            public int heightAt(int x, int z) {
                return surfaceCache.computeIfAbsent(pack(x, z),
                        k -> generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, randomState));
            }

            @Override
            public int waterDepthAt(int x, int z) {
                return waterCache.computeIfAbsent(pack(x, z), k -> {
                    int surface = heightAt(x, z);
                    // ChunkGenerator.getBaseHeight uses global seaLevel as the base fluid surface.
                    if (surface > seaLevel + 1)
                        return 0;
                    // WORLD_SURFACE_WG counts fluids as surface, OCEAN_FLOOR_WG only motion-blocking blocks.
                    int floor = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);
                    return Math.max(0, surface - floor);
                });
            }
        };
    }

    /**
     * Plans an organic curved 2D route using the provided biome query and a flat height baseline.
     */
    public static PlanResult plan(BlockPos start, BlockPos end, RoadConfig.Settings cfg, BiomeQuery biomeQuery) {
        return plan(start, end, cfg, biomeQuery, (x, z) -> 64);
    }

    /**
     * Plans a valley-following, organic curved 2D route using chunk-free biome and base-height queries.
     */
    public static PlanResult plan(
            BlockPos start, BlockPos end, RoadConfig.Settings cfg, BiomeQuery biomeQuery, HeightQuery heightQuery) {
        return plan(start, end, cfg, biomeQuery, heightQuery, FootprintQuery.NONE);
    }

    /**
     * Plans a valley-following route that additionally treats village footprints as very expensive terrain, so the
     * highway runs around villages instead of through their interiors.
     */
    public static PlanResult plan(BlockPos start, BlockPos end, RoadConfig.Settings cfg, BiomeQuery biomeQuery,
            HeightQuery heightQuery, FootprintQuery footprint) {
        return plan(start, end, cfg, biomeQuery, heightQuery, footprint, ExistingRoadQuery.NONE);
    }

    /**
     * Plans a valley-following route that additionally treats village footprints as very expensive terrain and
     * reuses nearby existing roads/bridges when advantageous.
     */
    public static PlanResult plan(BlockPos start, BlockPos end, RoadConfig.Settings cfg, BiomeQuery biomeQuery,
            HeightQuery heightQuery, FootprintQuery footprint, ExistingRoadQuery existingRoads) {
        FootprintQuery fp = footprint == null ? FootprintQuery.NONE : footprint;
        ExistingRoadQuery er = existingRoads == null ? ExistingRoadQuery.NONE : existingRoads;
        double directDist = Math.hypot((double) end.getX() - start.getX(), (double) end.getZ() - start.getZ());
        if (directDist < 4.0)
            return PlanResult.fail("Villages are too close for a separate highway.");
        if (directDist > cfg.maxSegmentDistance())
            return PlanResult.fail("OVER_DISTANCE");

        if (isOceanBiome(biomeQuery.biomeAt(start.getX(), start.getZ()))
                || isOceanBiome(biomeQuery.biomeAt(end.getX(), end.getZ()))) {
            return PlanResult.fail("WIDE_OCEAN");
        }

        int gridStep = directDist < 80.0 ? 4 : directDist < 320.0 ? 8 : 16;
        int startX = start.getX();
        int startZ = start.getZ();
        int endX = end.getX();
        int endZ = end.getZ();

        int goalGx = (int) Math.round((endX - startX) / (double) gridStep);
        int goalGz = (int) Math.round((endZ - startZ) / (double) gridStep);
        int snapEndX = startX + goalGx * gridStep;
        int snapEndZ = startZ + goalGz * gridStep;
        if (!isSegmentOceanFree(snapEndX, snapEndZ, endX, endZ, biomeQuery)) {
            return PlanResult.fail("WIDE_OCEAN");
        }

        int pad = Math.max(128, Math.min(480, (int) Math.ceil(directDist * 0.55)));
        int minX = Math.min(startX, endX) - pad;
        int maxX = Math.max(startX, endX) + pad;
        int minZ = Math.min(startZ, endZ) - pad;
        int maxZ = Math.max(startZ, endZ) + pad;

        long seed = (((long) startX * 341873128712L) ^ ((long) endZ * 132897987541L));

        int snapRadius = Math.max(4, (gridStep / 2) + 2);
        int minStepDelta = Math.max(2, gridStep / 2);
        int maxStepDelta = gridStep * 3 + snapRadius * 2;

        int[] expansionsOut = new int[1];
        List<Waypoint> rawPath = runWeightedAStar(startX, startZ, goalGx, goalGz, gridStep, minX, maxX, minZ, maxZ,
                seed, biomeQuery, heightQuery, fp, er, snapRadius, minStepDelta, maxStepDelta, expansionsOut);
        if (rawPath == null) {
            // Exhausting the search budget is not proof of an ocean; report a generic routing failure then.
            return PlanResult.fail(expansionsOut[0] >= MAX_ASTAR_EXPANSIONS ? "NO_ROUTE" : "WIDE_OCEAN");
        }
        if (rawPath.getLast().x() != endX || rawPath.getLast().z() != endZ) {
            rawPath.add(new Waypoint(endX, endZ));
        }

        List<Waypoint> assembled = er != ExistingRoadQuery.NONE
                ? assembleWithExistingRoads(rawPath, gridStep, seed, biomeQuery, heightQuery, fp, er,
                        snapRadius, minStepDelta, maxStepDelta)
                : null;

        List<Waypoint> steps;
        if (assembled != null) {
            steps = rasterize8Connected(assembled);
            if (!isRouteOceanFree(steps, biomeQuery)) {
                List<Waypoint> rawSteps = rasterize8Connected(rawPath);
                if (!isRouteOceanFree(rawSteps, biomeQuery)) {
                    return PlanResult.fail("WIDE_OCEAN");
                }
                steps = rawSteps;
            }
        } else {
            List<Waypoint> smoothed = smoothPath(rawPath, gridStep, seed, biomeQuery, heightQuery, fp, true);
            steps = rasterize8Connected(smoothed);
            if (!isRouteOceanFree(steps, biomeQuery)) {
                List<Waypoint> rawSteps = rasterize8Connected(rawPath);
                if (!isRouteOceanFree(rawSteps, biomeQuery)) {
                    return PlanResult.fail("WIDE_OCEAN");
                }
                steps = rawSteps;
            }
        }

        int length = steps.size();
        int maxAllowedPath = Math.max(cfg.maxSegmentDistance(), (int) Math.round(cfg.maxSegmentDistance() * 2.1));
        if (length > maxAllowedPath) {
            return PlanResult.fail("OVER_DISTANCE");
        }
        return PlanResult.success(List.copyOf(steps), Math.max(1, length));
    }

    private static List<Waypoint> assembleWithExistingRoads(List<Waypoint> rawPath, int gridStep, long seed,
            BiomeQuery biomeQuery, HeightQuery heightQuery, FootprintQuery fp, ExistingRoadQuery existingRoads,
            int snapRadius, int minStepDelta, int maxStepDelta) {
        if (rawPath.size() < 2)
            return null;
        int mCount = rawPath.size() - 1;
        EdgeMatch[] matches = new EdgeMatch[mCount];
        int prevSeg = -1;
        boolean anyMatch = false;
        for (int k = 0; k < mCount; k++) {
            Waypoint a = rawPath.get(k);
            Waypoint b = rawPath.get(k + 1);
            if (k == mCount - 1 && Math.hypot(b.x() - a.x(), b.z() - a.z()) < minStepDelta) {
                continue;
            }
            EdgeMatch m = existingRoads.matchEdge(
                    a.x(), a.z(), b.x(), b.z(), snapRadius, minStepDelta, maxStepDelta, prevSeg);
            if (m != null) {
                matches[k] = m;
                prevSeg = m.segmentIndex();
                anyMatch = true;
            } else {
                prevSeg = -1;
            }
        }
        if (!anyMatch)
            return null;

        List<Waypoint> assembled = new ArrayList<>();
        assembled.add(rawPath.getFirst());
        int k = 0;
        while (k < mCount) {
            if (matches[k] != null) {
                int runStart = k;
                while (k < mCount && matches[k] != null)
                    k++;
                int runEnd = k - 1;
                EdgeMatch prevMatch = null;
                for (int e = runStart; e <= runEnd; e++) {
                    EdgeMatch m = matches[e];
                    List<Waypoint> segSteps = existingRoads.segmentSteps(m.segmentIndex());
                    if (segSteps.isEmpty())
                        continue;
                    int fromIdx = (e > runStart && prevMatch != null && m.segmentIndex() == prevMatch.segmentIndex())
                            ? prevMatch.endStep()
                            : m.startStep();
                    appendStepRange(assembled, segSteps, fromIdx, m.endStep());
                    prevMatch = m;
                }
            } else {
                int runStart = k;
                while (k < mCount && matches[k] == null)
                    k++;
                int runEnd = k - 1;
                Waypoint startAnchor = assembled.getLast();
                Waypoint endAnchor;
                if (runEnd + 1 < mCount) {
                    EdgeMatch nextMatch = matches[runEnd + 1];
                    List<Waypoint> nextSeg = existingRoads.segmentSteps(nextMatch.segmentIndex());
                    endAnchor = !nextSeg.isEmpty() ? nextSeg.get(nextMatch.startStep()) : rawPath.get(runEnd + 1);
                } else {
                    endAnchor = rawPath.getLast();
                }

                List<Waypoint> freePts = new ArrayList<>((runEnd - runStart) + 2);
                freePts.add(startAnchor);
                for (int v = runStart + 1; v <= runEnd; v++) {
                    appendUniqueWaypoint(freePts, rawPath.get(v));
                }
                appendUniqueWaypoint(freePts, endAnchor);

                List<Waypoint> subPath = smoothFreeRun(
                        freePts, gridStep, seed ^ runStart, biomeQuery, heightQuery, fp);
                for (Waypoint w : subPath) {
                    appendUniqueWaypoint(assembled, w);
                }
            }
        }
        appendUniqueWaypoint(assembled, rawPath.getLast());
        return assembled;
    }

    private static List<Waypoint> smoothFreeRun(List<Waypoint> freePts, int gridStep, long seed,
            BiomeQuery biomeQuery, HeightQuery heightQuery, FootprintQuery fp) {
        if (freePts.size() < 2)
            return freePts;
        Waypoint first = freePts.getFirst();
        Waypoint last = freePts.getLast();
        boolean allowBend = Math.hypot((double) last.x() - first.x(), (double) last.z() - first.z()) >= 24.0;
        return smoothPath(freePts, gridStep, seed, biomeQuery, heightQuery, fp, allowBend);
    }

    private static List<Waypoint> smoothPath(List<Waypoint> rawPts, int gridStep, long seed,
            BiomeQuery biomeQuery, HeightQuery heightQuery, FootprintQuery fp, boolean allowBend) {
        if (rawPts.size() < 2)
            return rawPts;
        List<Waypoint> rawSteps = rasterize8Connected(rawPts);
        int baseFootprint = fp != FootprintQuery.NONE ? footprintIntrusion(rawSteps, fp) : 0;
        int baseWater = waterSteps(rawSteps, heightQuery);

        List<Waypoint> bent = allowBend
                ? applyOrganicBend(rawPts, gridStep, seed, biomeQuery, heightQuery, fp)
                : rawPts;
        List<Waypoint> tier1 = catmullRomSmooth(bent, gridStep, biomeQuery, heightQuery, fp);
        if (isAcceptableSmooth(tier1, baseFootprint, baseWater, biomeQuery, heightQuery, fp))
            return tier1;

        if (allowBend) {
            List<Waypoint> tier2 = catmullRomSmooth(rawPts, gridStep, biomeQuery, heightQuery, fp);
            if (isAcceptableSmooth(tier2, baseFootprint, baseWater, biomeQuery, heightQuery, fp))
                return tier2;
        }
        return rawPts;
    }

    private static boolean isAcceptableSmooth(List<Waypoint> candidate, int baseFootprint, int baseWater,
            BiomeQuery biomeQuery, HeightQuery heightQuery, FootprintQuery fp) {
        if (!isRouteOceanFree(candidate, biomeQuery))
            return false;
        List<Waypoint> smoothSteps = rasterize8Connected(candidate);
        if (!isRouteOceanFree(smoothSteps, biomeQuery))
            return false;
        if (fp != FootprintQuery.NONE && footprintIntrusion(smoothSteps, fp) > baseFootprint + 2)
            return false;
        return waterSteps(smoothSteps, heightQuery) <= baseWater + 3;
    }

    private static void appendStepRange(List<Waypoint> out, List<Waypoint> segSteps, int fromIdx, int toIdx) {
        int dir = Integer.compare(toIdx, fromIdx);
        for (int idx = fromIdx; ; idx += dir) {
            appendUniqueWaypoint(out, segSteps.get(idx));
            if (idx == toIdx)
                break;
        }
    }

    private static void appendUniqueWaypoint(List<Waypoint> out, Waypoint w) {
        if (out.isEmpty() || out.getLast().x() != w.x() || out.getLast().z() != w.z()) {
            out.add(w);
        }
    }

    private record Node(int gx, int gz, double f) {}

    private static List<Waypoint> runWeightedAStar(int startX, int startZ, int goalGx, int goalGz, int gridStep,
            int minX, int maxX, int minZ, int maxZ, long seed, BiomeQuery biomeQuery, HeightQuery heightQuery,
            FootprintQuery footprint, ExistingRoadQuery existingRoads, int snapRadius, int minStepDelta,
            int maxStepDelta, int[] expansionsOut) {
        long startKey = pack(0, 0);
        long goalKey = pack(goalGx, goalGz);

        Map<Long, Double> gScore = new HashMap<>();
        Map<Long, Long> cameFrom = new HashMap<>();
        Map<Long, Holder<Biome>> biomeCache = new HashMap<>();
        Map<Long, Integer> heightCache = new HashMap<>();
        Map<Long, Integer> waterCache = new HashMap<>();

        int startY = cachedHeight(heightCache, heightQuery, startX, startZ);
        int goalY = cachedHeight(heightCache, heightQuery, startX + goalGx * gridStep, startZ + goalGz * gridStep);
        int valleyBaselineY = Math.min(startY, goalY) + 6;

        boolean hasExisting = existingRoads != null && existingRoads != ExistingRoadQuery.NONE;
        double hWeight = hasExisting ? EXISTING_ROAD_COST_MULTIPLIER : 1.0;

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::f));
        gScore.put(startKey, 0.0);
        open.add(new Node(0, 0, Math.hypot(goalGx, goalGz) * gridStep * hWeight));

        int expansions = 0;
        while (!open.isEmpty() && expansions < MAX_ASTAR_EXPANSIONS) {
            Node curr = open.poll();
            long currKey = pack(curr.gx, curr.gz);
            if (currKey == goalKey) {
                expansionsOut[0] = expansions;
                return reconstruct(cameFrom, goalKey, startX, startZ, gridStep);
            }
            double currG = gScore.getOrDefault(currKey, Double.POSITIVE_INFINITY);
            double expectedF = currG + Math.hypot(goalGx - curr.gx, goalGz - curr.gz) * gridStep * hWeight;
            if (curr.f > expectedF + 1e-4)
                continue;
            expansions++;

            int cx = startX + curr.gx * gridStep;
            int cz = startZ + curr.gz * gridStep;
            int currHeight = cachedHeight(heightCache, heightQuery, cx, cz);
            boolean currNearRoad = hasExisting && existingRoads.mightBeNear(cx, cz, snapRadius);

            for (int dgx = -1; dgx <= 1; dgx++) {
                for (int dgz = -1; dgz <= 1; dgz++) {
                    if (dgx == 0 && dgz == 0)
                        continue;
                    int ngx = curr.gx + dgx;
                    int ngz = curr.gz + dgz;
                    int nx = startX + ngx * gridStep;
                    int nz = startZ + ngz * gridStep;
                    if (nx < minX || nx > maxX || nz < minZ || nz > maxZ)
                        continue;

                    Holder<Biome> nextBiome = cachedBiome(biomeCache, biomeQuery, nx, nz);
                    if (isOceanBiome(nextBiome))
                        continue;
                    int mx = (cx + nx) / 2;
                    int mz = (cz + nz) / 2;
                    Holder<Biome> midBiome = cachedBiome(biomeCache, biomeQuery, mx, mz);
                    if (isOceanBiome(midBiome))
                        continue;

                    double stepDist = Math.hypot(dgx, dgz) * gridStep;
                    double nextG;
                    if (currNearRoad && existingRoads.matchEdge(
                            cx, cz, nx, nz, snapRadius, minStepDelta, maxStepDelta, -1) != null) {
                        nextG = currG + stepDist * EXISTING_ROAD_COST_MULTIPLIER;
                    } else {
                        double mult = Math.max(
                                biomeTraversalMultiplier(nextBiome, nx, nz, seed),
                                biomeTraversalMultiplier(midBiome, mx, mz, seed));

                        int midHeight = cachedHeight(heightCache, heightQuery, mx, mz);
                        int nextHeight = cachedHeight(heightCache, heightQuery, nx, nz);
                        int dyEdge = Math.abs(nextHeight - currHeight);
                        int dyMid = Math.max(Math.abs(midHeight - currHeight), Math.abs(nextHeight - midHeight));
                        int dy = Math.max(dyEdge, dyMid);
                        int steepThreshold = Math.max(2, (int) Math.round(gridStep * 0.25));
                        double slopePenalty = (dy * (double) dy) * SLOPE_PENALTY_WEIGHT;
                        if (dy > steepThreshold) {
                            slopePenalty += stepDist * STEEP_CLIFF_COST_MULTIPLIER;
                        }
                        int elevAboveValley = Math.max(0, nextHeight - valleyBaselineY);
                        double elevationPenalty = elevAboveValley * ELEVATION_ABOVE_BASE_WEIGHT;

                        int water = Math.max(cachedWater(waterCache, heightQuery, nx, nz),
                                cachedWater(waterCache, heightQuery, mx, mz));
                        if (water >= DEEP_WATER_DEPTH)
                            mult = Math.max(mult, DEEP_WATER_COST_MULTIPLIER);
                        else if (water > 0)
                            mult = Math.max(mult, WATER_COST_MULTIPLIER);

                        if (footprint.blocked(nx, nz) || footprint.blocked(mx, mz))
                            mult *= FOOTPRINT_COST_MULTIPLIER;
                        nextG = currG + stepDist * mult + slopePenalty + elevationPenalty;
                    }
                    long nextKey = pack(ngx, ngz);
                    if (nextG < gScore.getOrDefault(nextKey, Double.POSITIVE_INFINITY)) {
                        gScore.put(nextKey, nextG);
                        cameFrom.put(nextKey, currKey);
                        double h = Math.hypot(goalGx - ngx, goalGz - ngz) * gridStep * hWeight;
                        open.add(new Node(ngx, ngz, nextG + h));
                    }
                }
            }
        }
        expansionsOut[0] = expansions;
        return null;
    }

    /** Counts route steps inside a village footprint, ignoring the gate connection at both ends. */
    static int footprintIntrusion(List<Waypoint> steps, FootprintQuery footprint) {
        int count = 0;
        for (int i = FOOTPRINT_END_ALLOWANCE; i < steps.size() - FOOTPRINT_END_ALLOWANCE; i++) {
            Waypoint w = steps.get(i);
            if (footprint.blocked(w.x(), w.z()))
                count++;
        }
        return count;
    }

    private static double biomeTraversalMultiplier(Holder<Biome> biome, int x, int z, long seed) {
        if (biome.is(BiomeTags.IS_RIVER))
            return RIVER_COST_MULTIPLIER;
        if (isMountainOrAlpineBiome(biome))
            return MOUNTAIN_COST_MULTIPLIER;
        if (biome.is(BiomeTags.IS_HILL))
            return HILL_COST_MULTIPLIER;
        double n = perlin01(x * PERLIN_FREQUENCY, z * PERLIN_FREQUENCY, seed);
        double base = 1.0 + 1.8 * n;
        return isSwampBiome(biome) ? base * SWAMP_COST_MULTIPLIER : base;
    }

    public static boolean isSwampBiome(Holder<Biome> biome) {
        if (biome == null)
            return false;
        if (biome.is(Biomes.SWAMP) || biome.is(Biomes.MANGROVE_SWAMP)
                || biome.is(BiomeTags.HAS_RUINED_PORTAL_SWAMP)) {
            return true;
        }
        String path = biome.unwrapKey().map(k -> k.location().getPath()).orElse("");
        return path.contains("swamp");
    }

    public static boolean isMountainOrAlpineBiome(Holder<Biome> biome) {
        if (biome == null)
            return false;
        if (biome.is(BiomeTags.IS_MOUNTAIN))
            return true;
        String path = biome.unwrapKey().map(k -> k.location().getPath()).orElse("");
        return path.contains("windswept") || path.contains("peaks") || path.contains("slopes");
    }

    private static List<Waypoint> reconstruct(
            Map<Long, Long> cameFrom, long goalKey, int startX, int startZ, int gridStep) {
        List<Waypoint> out = new ArrayList<>();
        long curr = goalKey;
        while (true) {
            int gx = (int) (curr >> 32);
            int gz = (int) curr;
            out.add(new Waypoint(startX + gx * gridStep, startZ + gz * gridStep));
            Long prev = cameFrom.get(curr);
            if (prev == null)
                break;
            curr = prev;
        }
        Collections.reverse(out);
        return out;
    }

    /**
     * Subsamples coarse A* vertices into control points and adds gentle coordinate-seeded organic lateral curves
     * on open dry land (strictly guarding against ocean, river, or steep-slope entry).
     */
    private static List<Waypoint> applyOrganicBend(
            List<Waypoint> raw, int gridStep, long seed, BiomeQuery biomeQuery, HeightQuery heightQuery,
            FootprintQuery footprint) {
        if (raw.size() < 2)
            return raw;
        if (raw.size() == 2) {
            Waypoint a = raw.getFirst();
            Waypoint b = raw.getLast();
            if (Math.hypot((double) b.x() - a.x(), (double) b.z() - a.z()) < 24.0)
                return raw;
        }
        FootprintQuery fp = footprint == null ? FootprintQuery.NONE : footprint;
        List<Waypoint> ctrl = new ArrayList<>();
        ctrl.add(raw.getFirst());
        int spacing = Math.max(12, gridStep * 2);
        Waypoint last = raw.getFirst();
        for (int i = 1; i < raw.size() - 1; i++) {
            Waypoint w = raw.get(i);
            Waypoint next = raw.get(i + 1);
            int midX = (last.x() + next.x()) / 2;
            int midZ = (last.z() + next.z()) / 2;
            boolean protectsFootprint = fp != FootprintQuery.NONE
                    && fp.blocked(midX, midZ) && !fp.blocked(w.x(), w.z());
            boolean protectsWater = heightQuery.waterDepthAt(midX, midZ) > 0
                    && heightQuery.waterDepthAt(w.x(), w.z()) == 0;
            if (Math.hypot(w.x() - last.x(), w.z() - last.z()) >= spacing || protectsFootprint || protectsWater) {
                ctrl.add(w);
                last = w;
            }
        }
        ctrl.add(raw.getLast());
        if (ctrl.size() <= 2) {
            // Insert one midpoint control handle so even short flat routes gently curve.
            Waypoint a = ctrl.getFirst();
            Waypoint b = ctrl.getLast();
            ctrl = List.of(a, new Waypoint((a.x() + b.x()) / 2, (a.z() + b.z()) / 2), b);
        }

        List<Waypoint> bent = new ArrayList<>(ctrl.size());
        bent.add(ctrl.getFirst());
        double amplitude = Math.max(5.0, Math.min(18.0, gridStep * 1.1));
        int maxBendDeltaY = Math.max(3, gridStep / 2);
        for (int i = 1; i < ctrl.size() - 1; i++) {
            Waypoint prev = ctrl.get(i - 1);
            Waypoint curr = ctrl.get(i);
            Waypoint next = ctrl.get(i + 1);
            double dx = next.x() - prev.x();
            double dz = next.z() - prev.z();
            double len = Math.hypot(dx, dz);
            if (len < 1.0) {
                bent.add(curr);
                continue;
            }
            double px = -dz / len;
            double pz = dx / len;
            double envelope = Math.sin(Math.PI * i / (ctrl.size() - 1));
            double wave = (perlin01(curr.x() * 0.015, curr.z() * 0.015, seed ^ 0x9E3779B97F4A7C15L) - 0.5) * 2.0;
            // Ensure minimum gentle wave away from zero on uniform terrain so straight lines naturally bend.
            if (Math.abs(wave) < 0.35)
                wave = wave >= 0.0 ? 0.55 : -0.55;
            int bx = (int) Math.round(curr.x() + px * amplitude * wave * envelope);
            int bz = (int) Math.round(curr.z() + pz * amplitude * wave * envelope);

            Holder<Biome> origBiome = biomeQuery.biomeAt(curr.x(), curr.z());
            Holder<Biome> bentBiome = biomeQuery.biomeAt(bx, bz);
            int origY = heightQuery.heightAt(curr.x(), curr.z());
            int bentY = heightQuery.heightAt(bx, bz);
            boolean safeTerrain = Math.abs(bentY - origY) <= maxBendDeltaY
                    && (!isMountainOrAlpineBiome(bentBiome) || isMountainOrAlpineBiome(origBiome))
                    && (!bentBiome.is(BiomeTags.IS_HILL) || origBiome.is(BiomeTags.IS_HILL));
            boolean bentIntoWater = heightQuery.waterDepthAt(bx, bz) > 0 && heightQuery.waterDepthAt(curr.x(), curr.z()) == 0;
            if (safeTerrain && !bentIntoWater && !isOceanBiome(bentBiome) && !footprint.blocked(bx, bz)
                    && (!bentBiome.is(BiomeTags.IS_RIVER) || origBiome.is(BiomeTags.IS_RIVER))) {
                bent.add(new Waypoint(bx, bz));
            } else {
                bent.add(curr);
            }
        }
        bent.add(ctrl.getLast());
        return bent;
    }

    /**
     * Smooths control points using centripetal Catmull-Rom spline interpolation into dense, natural curved waypoints,
     * falling back per-span only where a spline sample would enter ocean, water, river biomes, or village footprints.
     */
    private static List<Waypoint> catmullRomSmooth(List<Waypoint> pts, int gridStep,
            BiomeQuery biomeQuery, HeightQuery heightQuery, FootprintQuery footprint) {
        if (pts.size() <= 2)
            return pts;
        FootprintQuery fp = footprint == null ? FootprintQuery.NONE : footprint;
        List<Waypoint> out = new ArrayList<>();
        out.add(pts.getFirst());

        for (int i = 0; i < pts.size() - 1; i++) {
            Waypoint p0 = i > 0 ? pts.get(i - 1) : pts.get(0);
            Waypoint p1 = pts.get(i);
            Waypoint p2 = pts.get(i + 1);
            Waypoint p3 = i + 2 < pts.size() ? pts.get(i + 2) : pts.get(pts.size() - 1);

            int midX = (p1.x() + p2.x()) / 2;
            int midZ = (p1.z() + p2.z()) / 2;
            boolean chordHasWater = heightQuery.waterDepthAt(p1.x(), p1.z()) > 0
                    || heightQuery.waterDepthAt(p2.x(), p2.z()) > 0
                    || heightQuery.waterDepthAt(midX, midZ) > 0;
            boolean chordHasRiver = biomeQuery.biomeAt(p1.x(), p1.z()).is(BiomeTags.IS_RIVER)
                    || biomeQuery.biomeAt(p2.x(), p2.z()).is(BiomeTags.IS_RIVER)
                    || biomeQuery.biomeAt(midX, midZ).is(BiomeTags.IS_RIVER);
            boolean chordInFootprint = fp != FootprintQuery.NONE
                    && (fp.blocked(p1.x(), p1.z()) || fp.blocked(p2.x(), p2.z()) || fp.blocked(midX, midZ));

            double segDist = Math.hypot(p2.x() - p1.x(), p2.z() - p1.z());
            int subdivs = Math.max(2, (int) Math.ceil(segDist / Math.max(3, Math.min(6, gridStep))));

            List<Waypoint> sub = new ArrayList<>(subdivs);
            boolean safe = true;
            for (int s = 1; s <= subdivs; s++) {
                double t = (double) s / subdivs;
                Waypoint w = evalCatmullRom(p0, p1, p2, p3, t);
                Holder<Biome> wb = biomeQuery.biomeAt(w.x(), w.z());
                if (isOceanBiome(wb)
                        || (!chordHasWater && heightQuery.waterDepthAt(w.x(), w.z()) > 0)
                        || (!chordHasRiver && wb.is(BiomeTags.IS_RIVER))
                        || (!chordInFootprint && fp != FootprintQuery.NONE && fp.blocked(w.x(), w.z()))) {
                    safe = false;
                    break;
                }
                sub.add(w);
            }
            if (safe) {
                for (Waypoint w : sub) {
                    if (out.isEmpty() || out.getLast().x() != w.x() || out.getLast().z() != w.z())
                        out.add(w);
                }
            } else {
                if (out.getLast().x() != p2.x() || out.getLast().z() != p2.z())
                    out.add(p2);
            }
        }
        if (out.getLast().x() != pts.getLast().x() || out.getLast().z() != pts.getLast().z()) {
            out.add(pts.getLast());
        }
        return out;
    }

    private static Waypoint evalCatmullRom(Waypoint p0, Waypoint p1, Waypoint p2, Waypoint p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        double x = 0.5 * ((2.0 * p1.x())
                + (-p0.x() + p2.x()) * t
                + (2.0 * p0.x() - 5.0 * p1.x() + 4.0 * p2.x() - p3.x()) * t2
                + (-p0.x() + 3.0 * p1.x() - 3.0 * p2.x() + p3.x()) * t3);
        double z = 0.5 * ((2.0 * p1.z())
                + (-p0.z() + p2.z()) * t
                + (2.0 * p0.z() - 5.0 * p1.z() + 4.0 * p2.z() - p3.z()) * t2
                + (-p0.z() + 3.0 * p1.z() - 3.0 * p2.z() + p3.z()) * t3);
        return new Waypoint((int) Math.round(x), (int) Math.round(z));
    }

    public static boolean isOceanBiome(Holder<Biome> biome) {
        return biome != null && (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_DEEP_OCEAN));
    }

    private static boolean isSegmentOceanFree(int x0, int z0, int x1, int z1, BiomeQuery query) {
        double dist = Math.hypot((double) x1 - x0, (double) z1 - z0);
        int steps = Math.max(1, (int) Math.ceil(dist / OCEAN_SAMPLE_STEP));
        for (int s = 0; s <= steps; s++) {
            double t = (double) s / steps;
            int x = (int) Math.round(x0 + (x1 - x0) * t);
            int z = (int) Math.round(z0 + (z1 - z0) * t);
            if (isOceanBiome(query.biomeAt(x, z)))
                return false;
        }
        return true;
    }

    private static boolean isRouteOceanFree(List<Waypoint> waypoints, BiomeQuery query) {
        for (int i = 0; i < waypoints.size() - 1; i++) {
            Waypoint a = waypoints.get(i);
            Waypoint b = waypoints.get(i + 1);
            if (!isSegmentOceanFree(a.x(), a.z(), b.x(), b.z(), query))
                return false;
        }
        return true;
    }

    private static Holder<Biome> cachedBiome(Map<Long, Holder<Biome>> cache, BiomeQuery query, int x, int z) {
        int qx = QuartPos.fromBlock(x);
        int qz = QuartPos.fromBlock(z);
        long key = pack(qx, qz);
        return cache.computeIfAbsent(key, k -> query.biomeAt(x, z));
    }

    private static int cachedWater(Map<Long, Integer> cache, HeightQuery query, int x, int z) {
        return cache.computeIfAbsent(pack(x, z), k -> query.waterDepthAt(x, z));
    }

    /** Counts route steps over water (sampled every 4 blocks to keep noise lookups cheap). */
    static int waterSteps(List<Waypoint> steps, HeightQuery query) {
        int count = 0;
        for (int i = 0; i < steps.size(); i += 4) {
            Waypoint w = steps.get(i);
            if (query.waterDepthAt(w.x(), w.z()) > 0)
                count++;
        }
        return count;
    }

    private static int cachedHeight(Map<Long, Integer> cache, HeightQuery query, int x, int z) {
        return cache.computeIfAbsent(pack(x, z), k -> query.heightAt(x, z));
    }

    /** Deterministic 2D Perlin-style value/gradient noise in [0.0, 1.0]. */
    private static double perlin01(double x, double z, long seed) {
        int xi = (int) Math.floor(x);
        int zi = (int) Math.floor(z);
        double xf = x - xi;
        double zf = z - zi;
        double u = xf * xf * (3.0 - 2.0 * xf);
        double v = zf * zf * (3.0 - 2.0 * zf);

        double n00 = hash01(xi, zi, seed);
        double n10 = hash01(xi + 1, zi, seed);
        double n01 = hash01(xi, zi + 1, seed);
        double n11 = hash01(xi + 1, zi + 1, seed);

        double nx0 = n00 + u * (n10 - n00);
        double nx1 = n01 + u * (n11 - n01);
        return nx0 + v * (nx1 - nx0);
    }

    private static double hash01(int x, int z, long seed) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= (h >>> 31);
        return ((h >>> 11) & 0x1FFFFFL) / (double) (1L << 21);
    }

    private static long pack(int x, int z) {
        return (((long) x) << 32) | (z & 0xffffffffL);
    }

    public static int polylineLength(List<Waypoint> waypoints) {
        double total = 0.0;
        for (int i = 0; i < waypoints.size() - 1; i++) {
            Waypoint a = waypoints.get(i);
            Waypoint b = waypoints.get(i + 1);
            total += Math.hypot((double) b.x() - a.x(), (double) b.z() - a.z());
        }
        return Math.max(1, (int) Math.ceil(total));
    }

    /**
     * Converts a 2D polyline into a continuous 8-connected integer {@code (x, z)} pixel sequence with
     * {@code max(|dx|, |dz|) <= 1} between every consecutive step.
     */
    public static List<Waypoint> rasterize8Connected(List<Waypoint> waypoints) {
        if (waypoints == null || waypoints.isEmpty())
            return List.of();
        List<Waypoint> pixels = new ArrayList<>();
        pixels.add(waypoints.getFirst());
        for (int i = 0; i < waypoints.size() - 1; i++) {
            int x0 = pixels.getLast().x();
            int z0 = pixels.getLast().z();
            int x1 = waypoints.get(i + 1).x();
            int z1 = waypoints.get(i + 1).z();
            int dx = Math.abs(x1 - x0);
            int dz = Math.abs(z1 - z0);
            int sx = Integer.compare(x1, x0);
            int sz = Integer.compare(z1, z0);
            int err = dx - dz;
            while (x0 != x1 || z0 != z1) {
                int e2 = 2 * err;
                if (e2 > -dz) {
                    err -= dz;
                    x0 += sx;
                }
                if (e2 < dx) {
                    err += dx;
                    z0 += sz;
                }
                if (pixels.getLast().x() != x0 || pixels.getLast().z() != z0) {
                    pixels.add(new Waypoint(x0, z0));
                }
            }
        }
        return pixels;
    }

    /** Samples integer {@code (x, z)} and normalized forward vector at a given step index along waypoints. */
    public static Sample sampleAt(List<Waypoint> waypoints, int stepIndex) {
        if (waypoints == null || waypoints.isEmpty()) {
            return new Sample(0, 0, 1.0, 0.0);
        }
        List<Waypoint> steps = isAlready8Connected(waypoints) ? waypoints : rasterize8Connected(waypoints);
        if (steps.size() < 2) {
            Waypoint w = steps.getFirst();
            return new Sample(w.x(), w.z(), 1.0, 0.0);
        }
        int idx = Math.clamp(stepIndex, 0, steps.size() - 1);
        Waypoint cur = steps.get(idx);
        Waypoint prev = steps.get(Math.max(0, idx - 1));
        Waypoint next = steps.get(Math.min(steps.size() - 1, idx + 1));
        double dx = (double) next.x() - prev.x();
        double dz = (double) next.z() - prev.z();
        double len = Math.hypot(dx, dz);
        double dirX = len > 1e-6 ? dx / len : 1.0;
        double dirZ = len > 1e-6 ? dz / len : 0.0;
        return new Sample(cur.x(), cur.z(), dirX, dirZ);
    }

    private static boolean isAlready8Connected(List<Waypoint> waypoints) {
        for (int i = 0; i < waypoints.size() - 1; i++) {
            Waypoint a = waypoints.get(i);
            Waypoint b = waypoints.get(i + 1);
            int cheb = Math.max(Math.abs(b.x() - a.x()), Math.abs(b.z() - a.z()));
            if (cheb != 1)
                return false;
        }
        return true;
    }
}
