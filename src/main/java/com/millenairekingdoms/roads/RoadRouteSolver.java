package com.millenairekingdoms.roads;

import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.politics.PoliticsService;
import com.millenairekingdoms.roads.RoadSavedData.RoadSegment;
import com.millenairekingdoms.roads.RoadSavedData.Waypoint;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.millenaire.village.Village;

/**
 * Chained route solver for hybrid caravans and marching armies. Everything here works from saved road records and the
 * chunk-free {@link RoadPlanner}; no chunk is ever loaded or generated.
 * <ul>
 *   <li>Multi-hop friendly routes stitch completed {@link RoadSegment#steps()} along the BFS shortest village path,
 *       reversing a segment's steps when it is walked {@code villageB -> villageA} and passing through every
 *       intermediate village center (recorded as a stop).</li>
 *   <li>Expedition routes follow friendly roads to the member village nearest the target (the border village facing
 *       it), then plan the final cross-border approach with {@link RoadPlanner#plan}.</li>
 * </ul>
 * Every returned route is one continuous 8-connected list: {@code max(|dx|, |dz|) == 1} between consecutive steps.
 */
public final class RoadRouteSolver {
    /**
     * @param steps continuous 8-connected walk
     * @param stops step indices of intermediate friendly village centers, ascending
     * @param villages the friendly village path that was followed (source first)
     */
    public record Route(List<Waypoint> steps, List<Integer> stops, List<UUID> villages, int roadEndStep) {
        public Route(List<Waypoint> steps, List<Integer> stops, List<UUID> villages) {
            this(steps, stops, villages, -1);
        }
        public Route {
            steps = List.copyOf(steps);
            stops = List.copyOf(stops);
            villages = List.copyOf(villages);
        }
    }

    private RoadRouteSolver() {}

    /**
     * Stitches the completed segments joining consecutive villages of {@code villagePath}. Returns {@code null} when a
     * hop has no completed segment. {@code centers} may return {@code null} for unknown villages (then the stitch goes
     * straight from one segment end to the next segment start).
     */
    public static Route stitch(RoadSavedData roads, List<UUID> villagePath, Function<UUID, BlockPos> centers) {
        return stitch(roads, villagePath, centers, true);
    }

    public static Route stitch(RoadSavedData roads, List<UUID> villagePath, Function<UUID, BlockPos> centers,
            boolean includeLastCenter) {
        if (roads == null || villagePath == null || villagePath.size() < 2)
            return null;
        List<Waypoint> out = new ArrayList<>();
        List<Integer> stops = new ArrayList<>();
        BlockPos first = centers.apply(villagePath.getFirst());
        if (first != null)
            link(out, new Waypoint(first.getX(), first.getZ()));
        for (int i = 0; i < villagePath.size() - 1; i++) {
            RoadSegment seg = roads.completedLink(villagePath.get(i), villagePath.get(i + 1));
            if (seg == null || seg.steps().isEmpty())
                return null;
            if (i > 0) {
                BlockPos square = centers.apply(villagePath.get(i));
                if (square != null) {
                    link(out, new Waypoint(square.getX(), square.getZ()));
                    stops.add(out.size() - 1);
                }
            }
            for (Waypoint w : RoadSavedData.orientedSteps(seg, villagePath.get(i)))
                link(out, w);
        }
        if (includeLastCenter) {
            BlockPos last = centers.apply(villagePath.getLast());
            if (last != null)
                link(out, new Waypoint(last.getX(), last.getZ()));
        }
        return out.size() < 2 ? null : new Route(out, stops, villagePath);
    }

    /** Stitched friendly road route for a royal shipment, or {@code null} when no continuous road exists. */
    public static Route shipmentRoute(ServerLevel level, UUID kingdom, UUID from, UUID to) {
        if (!RoadConfig.current().enabled())
            return null;
        KingdomSavedData data = KingdomSavedData.get(level);
        RoadSavedData roads = RoadSavedData.get(level);
        List<UUID> path = roads.shortestFriendlyPath(data, kingdom, from, to);
        if (path.size() < 2)
            return null;
        return stitch(roads, path, id -> center(level, id));
    }

    /**
     * Army route: friendly roads from {@code source} to the reachable member village nearest {@code targetCenter}, then a
     * chunk-free planned approach to the target, cut at the first step within {@code arrivalRadius} of its center.
     * Returns {@code null} when no approach can be planned (callers then keep the legacy direct launch).
     */
    public static Route expeditionRoute(ServerLevel level, UUID kingdom, UUID source, BlockPos targetCenter,
            int arrivalRadius) {
        KingdomSavedData data = KingdomSavedData.get(level);
        RoadSavedData roads = RoadSavedData.get(level);
        BlockPos sourceCenter = center(level, source);
        if (sourceCenter == null || targetCenter == null)
            return null;
        boolean roadsOn = RoadConfig.current().enabled();
        Map<UUID, UUID> reach = new java.util.LinkedHashMap<>();
        reach.put(source, null);
        if (roadsOn)
            reach.putAll(roads.friendlyReachable(data, kingdom, source));
        UUID border = source;
        double best = distSq(sourceCenter, targetCenter);
        int bestHops = 0;
        for (UUID v : reach.keySet()) {
            BlockPos c = center(level, v);
            if (c == null)
                continue;
            double d = distSq(c, targetCenter);
            int hops = hops(reach, v);
            if (d < best - 1e-6 || Math.abs(d - best) <= 1e-6 && (hops < bestHops
                    || hops == bestHops && v.compareTo(border) < 0)) {
                best = d;
                border = v;
                bestHops = hops;
            }
        }
        List<UUID> path = new ArrayList<>();
        for (UUID cur = border; cur != null; cur = reach.get(cur))
            path.add(cur);
        Collections.reverse(path);
        List<Waypoint> out = new ArrayList<>();
        List<Integer> stops = new ArrayList<>();
        Route road = path.size() >= 2 ? stitch(roads, path, id -> center(level, id), false) : null;
        int roadEndStep = -1;
        BlockPos gate;
        if (road != null) {
            out.addAll(road.steps());
            stops.addAll(road.stops());
            roadEndStep = out.size() - 1;
            gate = new BlockPos(out.getLast().x(), sourceCenter.getY(), out.getLast().z());
        } else {
            path = List.of(source);
            border = source;
            out.add(new Waypoint(sourceCenter.getX(), sourceCenter.getZ()));
            Village borderVillage = PoliticsService.village(level, border);
            gate = borderVillage == null
                    ? new BlockPos(out.getLast().x(), sourceCenter.getY(), out.getLast().z())
                    : RoadService.resolveGateEndpoint(level, borderVillage, targetCenter).thresholdPos();
            link(out, new Waypoint(gate.getX(), gate.getZ()));
        }
        long arrivalSq = (long) arrivalRadius * arrivalRadius;
        if (distSq(gate, targetCenter) > arrivalSq) {
            RoadPlanner.PlanResult plan = RoadPlanner.plan(level, gate,
                    new BlockPos(targetCenter.getX(), gate.getY(), targetCenter.getZ()), RoadConfig.current());
            if (!plan.ok())
                return null;
            for (Waypoint w : plan.waypoints())
                link(out, w);
        }
        int cut = out.size() - 1;
        for (int i = 0; i < out.size(); i++) {
            Waypoint w = out.get(i);
            double dx = w.x() - targetCenter.getX(), dz = w.z() - targetCenter.getZ();
            if (dx * dx + dz * dz <= arrivalSq) {
                cut = i;
                break;
            }
        }
        List<Waypoint> steps = new ArrayList<>(out.subList(0, cut + 1));
        if (steps.size() < 2)
            return null;
        final int last = steps.size() - 1;
        stops.removeIf(s -> s >= last);
        return new Route(steps, stops, path, roadEndStep >= 0 ? Math.min(roadEndStep, last) : -1);
    }

    /** Appends {@code p} to {@code out}, rasterizing any gap so the list stays 8-connected without duplicates. */
    public static void link(List<Waypoint> out, Waypoint p) {
        if (out.isEmpty()) {
            out.add(p);
            return;
        }
        Waypoint prev = out.getLast();
        if (prev.x() == p.x() && prev.z() == p.z())
            return;
        if (Math.max(Math.abs(prev.x() - p.x()), Math.abs(prev.z() - p.z())) == 1) {
            out.add(p);
            return;
        }
        List<Waypoint> line = RoadPlanner.rasterize8Connected(List.of(prev, p));
        for (int i = 1; i < line.size(); i++)
            out.add(line.get(i));
    }

    /** True when every consecutive pair satisfies {@code max(|dx|, |dz|) == 1}. */
    public static boolean isContinuous(List<Waypoint> steps) {
        for (int i = 0; i + 1 < steps.size(); i++) {
            Waypoint a = steps.get(i), b = steps.get(i + 1);
            if (Math.max(Math.abs(a.x() - b.x()), Math.abs(a.z() - b.z())) != 1)
                return false;
        }
        return true;
    }

    private static int hops(Map<UUID, UUID> parents, UUID v) {
        int n = 0;
        for (UUID cur = parents.get(v); cur != null; cur = parents.get(cur))
            n++;
        return n;
    }

    private static BlockPos center(ServerLevel level, UUID village) {
        Village v = PoliticsService.village(level, village);
        return v == null ? null : v.getCenter();
    }

    private static double distSq(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }
}
