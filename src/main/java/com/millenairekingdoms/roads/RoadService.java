package com.millenairekingdoms.roads;

import com.millenairekingdoms.autonomy.ExpeditionPricingService;
import com.millenairekingdoms.economy.EconomyService;
import com.millenairekingdoms.economy.RoyalPaymentService;
import com.millenairekingdoms.economy.RoyalPrices;
import com.millenairekingdoms.economy.RoyalTreasurySnapshot;
import com.millenairekingdoms.economy.TreasuryService;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.kingdom.KingdomSavedData.Kingdom;
import com.millenairekingdoms.politics.PoliticsService;
import com.millenairekingdoms.roads.RoadPlanner.PlanResult;
import com.millenairekingdoms.roads.RoadSavedData.RoadSegment;
import com.millenairekingdoms.roads.RoadSavedData.Status;
import com.millenairekingdoms.util.KingdomException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.millenaire.building.BuildingInstance;
import org.millenaire.culture.ModCultures;
import org.millenaire.culture.VillageType;
import org.millenaire.village.Village;
import org.millenaire.village.VillageSavedData;

/**
 * Domain service for Royal Roads: ordering, server-side validation, stipend charging,
 * daily surplus-backed construction progress, NPC autonomy, and cancellation.
 */
public final class RoadService {
    public record PairPreview(UUID villageA, String nameA, UUID villageB, String nameB, int distanceBlocks,
            long stipendDeniers, int materialUnits, boolean blocked, String blockedReasonKey) {}

    private static final int MAX_NPC_ROUTE_ATTEMPTS_PER_DAY = 3;
    private static final long MAX_INACTIVE_TICK_GAP = 100L;

    private RoadService() {}

    public static long upfrontStipend(int distanceBlocks, RoadConfig.Settings cfg) {
        return Math.max(0L,
                cfg.upfrontBaseDeniers() + (long) (Math.max(0, distanceBlocks) / 64) * cfg.upfrontDeniersPer64Blocks());
    }

    public static int estimatedMaterials(int totalBlocks, RoadConfig.Settings cfg) {
        return Math.max(1, (int) Math.ceil((double) Math.max(1, totalBlocks) / Math.max(1, cfg.blocksPerMaterial())));
    }

    /** Orders a new Royal Road for a player ruler after validating authority, distance, oceans, and treasury. */
    public static RoadSegment orderPlayerRoad(ServerPlayer player, UUID villageAId, UUID villageBId) {
        ServerLevel level = player.serverLevel();
        RoadConfig.Settings cfg = RoadConfig.current();
        if (!cfg.enabled())
            throw new KingdomException("millenairekingdoms.road.error.disabled", "Royal roads are disabled.");
        Kingdom k = PoliticsService.ruledKingdom(player);
        KingdomSavedData data = KingdomSavedData.get(level);
        if (!data.mayDecide(k.id(), player.getUUID())
                || PoliticsService.reputation(level, k, player.getUUID()) < PoliticsService.DECISION_REPUTATION) {
            throw new KingdomException(
                    "millenairekingdoms.road.error.authority", "Current rule and reputation are required.");
        }
        return createRoad(level, player, data, k, villageAId, villageBId, cfg);
    }

    /** Orders a new Royal Road for an NPC-governed kingdom if treasury reserve permits it. */
    public static RoadSegment orderNpcRoad(ServerLevel level, UUID kingdomId, UUID villageAId, UUID villageBId) {
        RoadConfig.Settings cfg = RoadConfig.current();
        if (!cfg.enabled())
            return null;
        KingdomSavedData data = KingdomSavedData.get(level);
        Kingdom k = data.kingdom(kingdomId);
        if (k == null || !k.active() || k.fallen() || !data.mayDecide(kingdomId, null))
            return null;
        return createRoad(level, null, data, k, villageAId, villageBId, cfg);
    }

    private static RoadSegment createRoad(ServerLevel level, ServerPlayer player, KingdomSavedData data, Kingdom k,
            UUID villageAId, UUID villageBId, RoadConfig.Settings cfg) {
        if (villageAId == null || villageBId == null || villageAId.equals(villageBId))
            throw new KingdomException(
                    "millenairekingdoms.road.error.same_village", "Select two different member villages.");
        if (!k.id().equals(data.owner(villageAId)) || !k.id().equals(data.owner(villageBId)))
            throw new KingdomException(
                    "millenairekingdoms.road.error.not_members", "Both villages must belong to this kingdom.");
        RoadSavedData roadData = RoadSavedData.get(level);
        if (roadData.activeSegment(k.id()) != null)
            throw new KingdomException(
                    "millenairekingdoms.road.error.active_exists", "This kingdom already has an active road project.");
        if (roadData.hasActiveOrCompletedLink(villageAId, villageBId))
            throw new KingdomException(
                    "millenairekingdoms.road.error.already_linked", "These two villages are already linked by road.");

        Village vA = PoliticsService.village(level, villageAId);
        Village vB = PoliticsService.village(level, villageBId);
        if (vA == null || vB == null)
            throw new KingdomException(
                    "millenairekingdoms.road.error.village_missing", "Both villages must exist in world records.");
        VillageType typeA = ModCultures.getVillageType(vA.getVillageTypeId());
        VillageType typeB = ModCultures.getVillageType(vB.getVillageTypeId());
        if (typeA == null || typeB == null || typeA.loneBuilding() || typeB.loneBuilding())
            throw new KingdomException(
                    "millenairekingdoms.road.error.lone_building", "Royal roads cannot connect lone buildings.");

        GateEndpoint epA = resolveGateEndpoint(level, vA, vB.getCenter());
        GateEndpoint epB = resolveGateEndpoint(level, vB, epA.thresholdPos());
        VillageFootprint corridor = corridorFootprint(level, epA.thresholdPos(), epB.thresholdPos());
        RoadPlanner.ExistingRoadQuery existingRoads = RoadPlanner.ExistingRoadIndex.fromSegments(
                roadData.allSegments(), epA.thresholdPos(), epB.thresholdPos());
        PlanResult plan = RoadPlanner.planWithFlushApproaches(level, epA.thresholdPos(), epA.approachPos(),
                epB.approachPos(), epB.thresholdPos(), cfg, corridor::contains, existingRoads);
        if (!plan.ok()) {
            if ("OVER_DISTANCE".equals(plan.failureReason())) {
                throw new KingdomException("millenairekingdoms.road.error.too_far",
                        "Selected villages exceed the maximum road distance (%s blocks).", cfg.maxSegmentDistance());
            }
            if ("WIDE_OCEAN".equals(plan.failureReason()) || "OCEAN_SEPARATED".equals(plan.failureReason())) {
                throw new KingdomException("millenairekingdoms.road.error.wide_ocean",
                        "Cannot build road: villages are separated by open ocean.");
            }
            throw new KingdomException("millenairekingdoms.road.error.invalid_route", "Unable to plan this road route.");
        }

        long stipend = upfrontStipend(plan.totalBlocks(), cfg);
        if (stipend > 0) {
            if (player == null) {
                long spendable = ExpeditionPricingService.spendableTreasury(level, data, k.id());
                long reserve = RoyalPrices.npcReserve(EconomyService.dailyIncome(level, data, k.id()));
                if (!RoyalPrices.npcCanAfford(spendable, stipend, reserve)) {
                    throw new KingdomException("millenairekingdoms.road.error.insufficient_funds",
                            "Capital treasury cannot cover the upfront road stipend (%s deniers).", stipend);
                }
            } else {
                if (!TreasuryService.loaded(level, k))
                    throw new KingdomException("millenairekingdoms.road.error.treasury_unloaded",
                            "Capital treasury chest is unloaded; cannot pay road stipend (%s deniers).", stipend);
                long spendable;
                try {
                    spendable = RoyalTreasurySnapshot.forPlayer(player).spendableValue();
                } catch (RuntimeException unavailable) {
                    // Not necessarily unloaded: show the real reason.
                    throw new KingdomException("millenairekingdoms.road.error.payment_failed_reason",
                            "Could not pay upfront road stipend (%s deniers): %s.", stipend, unavailable.getMessage());
                }
                if (spendable < stipend) {
                    throw new KingdomException("millenairekingdoms.road.error.insufficient_funds",
                            "Capital treasury cannot cover the upfront road stipend (%s deniers).", stipend);
                }
            }
        }

        UUID roadId = UUID.randomUUID();
        if (stipend > 0) {
            var payments = new RoyalPaymentService(level);
            try {
                if (player != null) {
                    payments.payPlayer(
                            player, roadId, RoyalPaymentService.Purpose.ADMINISTRATIVE, roadId, stipend);
                    payments.commitPlayer(player, roadId);
                } else {
                    payments.payNpc(
                            k.id(), roadId, RoyalPaymentService.Purpose.ADMINISTRATIVE, roadId, stipend);
                    payments.commitNpc(k.id(), roadId);
                }
            } catch (RuntimeException failed) {
                // Show why (e.g. no room for change in the treasury chests) instead of a bare failure.
                throw new KingdomException("millenairekingdoms.road.error.payment_failed_reason",
                        "Could not pay upfront road stipend (%s deniers): %s.", stipend, failed.getMessage());
            }
        }

        String cultureId = vA.getCultureId() != null ? vA.getCultureId().toString() : "";
        String villageTypeId = vA.getVillageTypeId() != null ? vA.getVillageTypeId().toString() : "";
        RoadSegment seg = new RoadSegment(roadId, k.id(), villageAId, villageBId, cultureId, villageTypeId,
                plan.waypoints(), plan.waypoints().size(), 0, Status.BUILDING, stipend, data.day(), level.getGameTime());
        roadData.addSegment(seg);
        if (RoadPlacer.realizeAvailableSteps(level, seg) > 0) {
            roadData.setDirty();
        }
        return seg;
    }

    /** Cancels the player's kingdom active road project without deleting already-placed road blocks. */
    public static RoadSegment cancelPlayerRoad(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        Kingdom k = PoliticsService.ruledKingdom(player);
        KingdomSavedData data = KingdomSavedData.get(level);
        if (!data.mayDecide(k.id(), player.getUUID())
                || PoliticsService.reputation(level, k, player.getUUID()) < PoliticsService.DECISION_REPUTATION) {
            throw new KingdomException(
                    "millenairekingdoms.road.error.authority", "Current rule and reputation are required.");
        }
        return cancelActiveRoad(level, k.id());
    }

    public static RoadSegment cancelActiveRoad(ServerLevel level, UUID kingdomId) {
        RoadSavedData roadData = RoadSavedData.get(level);
        RoadSegment active = roadData.activeSegment(kingdomId);
        if (active == null)
            throw new KingdomException(
                    "millenairekingdoms.road.error.no_active", "This kingdom has no active road project.");
        active.setStatus(Status.CANCELLED);
        active.setUpdatedGameTime(level.getGameTime());
        roadData.setDirty();
        RoadRuntime.despawnWorkerForSegment(level, active.id());
        return active;
    }

    /** Runs once per kingdom day inside {@link com.millenairekingdoms.politics.KingdomDay}. */
    public static void dailyStep(ServerLevel level, KingdomSavedData kingdomData, long day) {
        RoadConfig.Settings cfg = RoadConfig.current();
        if (!cfg.enabled())
            return;
        RoadSavedData roadData = RoadSavedData.get(level);
        if (roadData.lastProcessedDay() >= day)
            return;
        roadData.setLastProcessedDay(day);

        // 1. Advance every active road project on bounded daily quota using surplus materials.
        for (RoadSegment seg : roadData.activeSegments()) {
            advanceSegmentDaily(level, kingdomData, roadData, seg, cfg);
        }

        // 2. NPC Kingdom Autonomy: start at most 1 road project if none is active and economic reserve allows it.
        for (Kingdom k : kingdomData.kingdoms()) {
            if (!k.active() || k.fallen() || !kingdomData.npcGoverned(k.id()))
                continue;
            if (roadData.activeSegment(k.id()) != null)
                continue;
            tryStartNpcRoad(level, kingdomData, roadData, k, cfg);
        }
    }

    private static void advanceSegmentDaily(ServerLevel level, KingdomSavedData kingdomData, RoadSavedData roadData,
            RoadSegment seg, RoadConfig.Settings cfg) {
        Kingdom k = kingdomData.kingdom(seg.kingdomId());
        if (k == null || !k.active() || k.fallen()
                || !seg.kingdomId().equals(kingdomData.owner(seg.villageA()))
                || !seg.kingdomId().equals(kingdomData.owner(seg.villageB()))) {
            seg.setStatus(Status.CANCELLED);
            seg.setUpdatedGameTime(level.getGameTime());
            roadData.setDirty();
            RoadRuntime.despawnWorkerForSegment(level, seg.id());
            return;
        }
        if (seg.isAllStepsConstructed()) {
            seg.setCompletedBlocks(seg.totalBlocks());
            seg.setStatus(Status.COMPLETED);
            seg.setUpdatedGameTime(level.getGameTime());
            roadData.setDirty();
            RoadPlacer.realizeAvailableSteps(level, seg);
            RoadRuntime.onSegmentCompletedOrPaused(level, seg);
            return;
        }

        // While a player watches the live worker, the day's quota/materials become the worker's step budget
        // instead of an instant jump, so watching never builds faster or cheaper than the background path.
        boolean workerWatched = RoadRuntime.shouldSuspendBackgroundProgress(level, seg, cfg);

        int blocksPerMat = Math.max(1, cfg.blocksPerMaterial());
        int dailyQuota = Math.max(1, cfg.dailyProgressBlocks());
        int remaining = Math.max(1, seg.totalBlocks() - seg.completedBlocks());
        int desired = Math.min(dailyQuota, remaining);
        int neededUnits = Math.max(1, (int) Math.ceil((double) desired / blocksPerMat));

        boolean bothEndpointsLoaded = RoadMaterialStock.areBothEndpointsLoaded(level, seg.villageA(), seg.villageB());
        int stepBlocks;
        if (!bothEndpointsLoaded) {
            // When one or both endpoint villages are inactive/unloaded, progress advances automatically on schedule.
            stepBlocks = desired;
        } else {
            int consumed = RoadMaterialStock.consumeSurplus(level, seg.villageA(), seg.villageB(), neededUnits);
            if (consumed <= 0) {
                if (workerWatched)
                    return; // The worker keeps spending what is left of its budget; nothing new today.
                if (seg.status() != Status.PAUSED_MATERIALS) {
                    seg.setStatus(Status.PAUSED_MATERIALS);
                    seg.setUpdatedGameTime(level.getGameTime());
                    roadData.setDirty();
                    RoadRuntime.onSegmentCompletedOrPaused(level, seg);
                }
                return;
            }
            stepBlocks = Math.min(desired, consumed * blocksPerMat);
        }

        if (workerWatched) {
            int remainingSteps = Math.max(0, seg.steps().size() - 1 - seg.constructedStepIndex());
            seg.setWorkerCredit(Math.min(remainingSteps, seg.workerCredit() + stepBlocks));
            seg.setUpdatedGameTime(level.getGameTime());
            roadData.setDirty();
            return;
        }
        // A worker budget left over from a watched period is already paid for: build it now in the background.
        stepBlocks += seg.workerCredit();
        seg.setWorkerCredit(0);

        int oldCompleted = seg.completedBlocks();
        int newCompleted = Math.min(seg.totalBlocks(), oldCompleted + stepBlocks);
        seg.setCompletedBlocks(newCompleted);
        seg.setUpdatedGameTime(level.getGameTime());
        if (seg.isAllStepsConstructed()) {
            seg.setStatus(Status.COMPLETED);
            RoadRuntime.onSegmentCompletedOrPaused(level, seg);
        } else {
            seg.setStatus(Status.BUILDING);
        }
        roadData.setDirty();
        RoadPlacer.realizeAvailableSteps(level, seg);
    }

    /**
     * Advances active road orders on an automatic completion timer whenever one or both endpoint villages are
     * inactive/unloaded, completing the road once the configured duration elapses.
     */
    public static void tickInactiveSegments(ServerLevel level) {
        RoadConfig.Settings cfg = RoadConfig.current();
        if (!cfg.enabled())
            return;
        RoadSavedData roadData = RoadSavedData.get(level);
        List<RoadSegment> active = roadData.activeSegments();
        if (active.isEmpty())
            return;
        KingdomSavedData kingdomData = KingdomSavedData.get(level);
        long now = level.getGameTime();
        long duration = Math.max(1L, cfg.inactiveCompletionTicks());
        for (RoadSegment seg : active) {
            Kingdom k = kingdomData.kingdom(seg.kingdomId());
            if (k == null || !k.active() || k.fallen()
                    || !seg.kingdomId().equals(kingdomData.owner(seg.villageA()))
                    || !seg.kingdomId().equals(kingdomData.owner(seg.villageB()))) {
                seg.setStatus(Status.CANCELLED);
                seg.setUpdatedGameTime(now);
                roadData.setDirty();
                RoadRuntime.despawnWorkerForSegment(level, seg.id());
                continue;
            }
            if (RoadRuntime.shouldSuspendBackgroundProgress(level, seg, cfg)) {
                seg.setUpdatedGameTime(now);
                continue;
            }
            if (RoadMaterialStock.areBothEndpointsLoaded(level, seg.villageA(), seg.villageB())) {
                // Restart the inactive timer from now; otherwise a road that stayed loaded for days would
                // complete in one jump the moment its villages unload.
                seg.setUpdatedGameTime(now);
                continue;
            }
            // This runs every 20 ticks, so a larger gap only means a stale timestamp (e.g. read back from the save
            // after a restart). Never turn such a gap into an instant completion.
            long elapsed = Math.min(MAX_INACTIVE_TICK_GAP, Math.max(0L, now - seg.updatedGameTime()));
            if (elapsed <= 0L)
                continue;
            int addBlocks = elapsed >= duration
                    ? seg.totalBlocks()
                    : (int) Math.max(1L, (elapsed * seg.totalBlocks()) / duration);
            int oldCompleted = seg.completedBlocks();
            int newCompleted = Math.min(seg.totalBlocks(), oldCompleted + addBlocks);
            Status nextStatus = newCompleted >= seg.totalBlocks() ? Status.COMPLETED : Status.BUILDING;
            if (newCompleted != oldCompleted || seg.status() != nextStatus) {
                seg.setCompletedBlocks(newCompleted);
                seg.setStatus(nextStatus);
                seg.setUpdatedGameTime(now);
                roadData.setDirty();
                if (nextStatus == Status.COMPLETED) {
                    RoadRuntime.onSegmentCompletedOrPaused(level, seg);
                }
                if (newCompleted > oldCompleted) {
                    RoadPlacer.realizeAvailableSteps(level, seg);
                }
            }
        }
    }

    private static void tryStartNpcRoad(ServerLevel level, KingdomSavedData kingdomData, RoadSavedData roadData,
            Kingdom k, RoadConfig.Settings cfg) {
        Set<UUID> members = kingdomData.members(k.id());
        if (members.size() < 2)
            return;
        Set<UUID> connected = roadData.connectedToCapital(kingdomData, k.id());
        Set<UUID> anchors = connected.isEmpty() ? Set.of(k.capital()) : connected;

        record Candidate(UUID anchor, UUID unconnected, int dist) {}
        List<Candidate> candidates = new ArrayList<>();
        for (UUID m : members) {
            if (anchors.contains(m))
                continue;
            Village mv = PoliticsService.village(level, m);
            if (mv == null)
                continue;
            for (UUID a : anchors) {
                if (roadData.hasActiveOrCompletedLink(a, m))
                    continue;
                Village av = PoliticsService.village(level, a);
                if (av == null)
                    continue;
                BlockPos start = borderPostOrCenter(av, mv.getCenter());
                BlockPos end = borderPostOrCenter(mv, start);
                int dist = (int) Math.round(RoyalPrices.horizontalDistance(start, end));
                if (dist <= cfg.maxSegmentDistance() && dist >= 4)
                    candidates.add(new Candidate(a, m, dist));
            }
        }
        if (candidates.isEmpty())
            return;
        candidates.sort(Comparator.comparingInt(Candidate::dist)
                                .thenComparing(Candidate::unconnected)
                                .thenComparing(Candidate::anchor));

        // Route planning runs on the server thread; bound the attempts per kingdom per day.
        int attempts = 0;
        for (Candidate c : candidates) {
            if (attempts++ >= MAX_NPC_ROUTE_ATTEMPTS_PER_DAY)
                return;
            try {
                if (orderNpcRoad(level, k.id(), c.anchor(), c.unconnected()) != null)
                    return;
            } catch (RuntimeException ignored) {
                // Skip invalid ocean or over-distance candidate and try next closest link.
            }
        }
    }

    /** Returns eligible / visible village pairs for the kingdom screen without loading unloaded chunks. */
    public static List<PairPreview> previewPairs(ServerLevel level, KingdomSavedData kingdomData, UUID kingdomId) {
        RoadConfig.Settings cfg = RoadConfig.current();
        RoadSavedData roadData = RoadSavedData.get(level);
        List<UUID> members = List.copyOf(kingdomData.members(kingdomId));
        List<PairPreview> out = new ArrayList<>();
        for (int i = 0; i < members.size(); i++) {
            UUID idA = members.get(i);
            Village vA = PoliticsService.village(level, idA);
            if (vA == null)
                continue;
            for (int j = i + 1; j < members.size(); j++) {
                UUID idB = members.get(j);
                Village vB = PoliticsService.village(level, idB);
                if (vB == null)
                    continue;
                if (roadData.hasActiveOrCompletedLink(idA, idB))
                    continue;
                BlockPos start = borderPostOrCenter(vA, vB.getCenter());
                BlockPos end = borderPostOrCenter(vB, start);
                int dist = (int) Math.round(RoyalPrices.horizontalDistance(start, end));
                long stipend = upfrontStipend(dist, cfg);
                int materials = estimatedMaterials(dist, cfg);
                boolean blocked = false;
                String reason = "";
                if (dist > cfg.maxSegmentDistance()) {
                    blocked = true;
                    reason = "millenairekingdoms.gui.roads.reason.too_far";
                }
                out.add(new PairPreview(idA, PoliticsService.name(vA), idB, PoliticsService.name(vB), dist, stipend,
                        materials, blocked, reason));
            }
        }
        out.sort(Comparator.comparingInt(PairPreview::distanceBlocks)
                         .thenComparing(PairPreview::nameA)
                         .thenComparing(PairPreview::nameB));
        return out;
    }

    public record GateEndpoint(BlockPos thresholdPos, BlockPos approachPos) {}

    /** Straight approach length (blocks) between a village threshold and the open-country route. */
    private static final int APPROACH_LENGTH = 6;
    /** Extra blocks an approach may be extended so it leaves the village footprint before free routing starts. */
    private static final int MAX_APPROACH_EXTENSION = 24;
    /** Blocks of the straight line toward the other village inspected when scoring an endpoint. */
    private static final int SCORE_LOOKAHEAD = 64;

    public static BlockPos borderPostOrCenter(Village village, BlockPos toward) {
        return resolveGateEndpoint(village, toward).thresholdPos();
    }

    /**
     * Resolves where a Royal Road meets a village. Instead of the 2D-nearest building, every candidate is scored
     * by how well it faces the other village and how much of the village the road would have to cross:
     * <ol>
     *   <li>Border gates ({@code borderpost_gate} / {@code full_wall_gateway}), then plain border posts.</li>
     *   <li>The outermost cell of the village's own path network on the side facing the other village.</li>
     *   <li>The edge of the village footprint (all building boxes + margin) along the line to the other village.</li>
     * </ol>
     * The approach always leaves the village footprint before free routing starts, so the highway never runs
     * through the village interior.
     */
    public static GateEndpoint resolveGateEndpoint(Village village, BlockPos toward) {
        return resolveGateEndpoint(null, village, toward);
    }

    public static GateEndpoint resolveGateEndpoint(ServerLevel level, Village village, BlockPos toward) {
        VillageFootprint footprint = VillageFootprint.of(List.of(village), VillageFootprint.DEFAULT_MARGIN);
        Scored best = bestBuildingEndpoint(village, toward, footprint, 1);
        if (best == null)
            best = bestBuildingEndpoint(village, toward, footprint, 2);
        if (best != null)
            return best.endpoint();

        Scored path = bestPathNetworkEndpoint(village, toward, footprint);
        Scored edge = footprintEdgeEndpoint(village, toward, footprint);
        if (path != null && (edge == null || path.score() <= edge.score() + 24.0))
            return path.endpoint();
        if (edge != null)
            return edge.endpoint();

        BlockPos vc = village.getCenter();
        int[] out = cardinalToward(toward.getX() - vc.getX(), toward.getZ() - vc.getZ());
        return new GateEndpoint(vc, vc.offset(out[0] * APPROACH_LENGTH, 0, out[1] * APPROACH_LENGTH));
    }

    private record Scored(GateEndpoint endpoint, double score) {}

    private static Scored bestBuildingEndpoint(Village village, BlockPos toward, VillageFootprint footprint, int tier) {
        Scored best = null;
        BlockPos vc = village.getCenter();
        for (BuildingInstance b : village.getBuildings()) {
            if (!matchesEndpointTier(b, tier))
                continue;
            BlockPos origin = b.getOrigin();
            if (origin == null)
                continue;
            int[] box = VillageFootprint.buildingBox(b);
            int boxCenterX = (box[0] + box[2]) / 2;
            int boxCenterZ = (box[1] + box[3]) / 2;
            // A gate opens outward: away from the village center (falling back to the target direction).
            int radialX = boxCenterX - vc.getX();
            int radialZ = boxCenterZ - vc.getZ();
            if (radialX == 0 && radialZ == 0) {
                radialX = toward.getX() - boxCenterX;
                radialZ = toward.getZ() - boxCenterZ;
            }
            int[] out = cardinalToward(radialX, radialZ);
            BlockPos threshold = gateThreshold(village, b, box, origin, out);
            BlockPos approach = extendOutOfFootprint(threshold, out, footprint);
            double score = endpointScore(approach, out, toward, footprint);
            if (best == null || score < best.score())
                best = new Scored(new GateEndpoint(threshold, approach), score);
        }
        return best;
    }

    /**
     * Threshold on the gate's center axis, just outside the gate opening. The axis comes from the village's own
     * path cells running through the gate; without those, from the explicit path start or the gate box center.
     * The building origin alone is not the opening's center and left roads 1-3 blocks beside a pillar.
     */
    private static BlockPos gateThreshold(Village village, BuildingInstance b, int[] box, BlockPos origin, int[] out) {
        boolean alongX = out[0] != 0;
        int sign = alongX ? out[0] : out[1];
        var manager = village.getPathManager();
        if (manager != null) {
            long[] acc = new long[3]; // lateral sum, count, (unused)
            int[] outer = { Integer.MIN_VALUE };
            manager.forEachPath((pos, lvl) -> {
                if (pos == null || pos.getX() < box[0] - 1 || pos.getX() > box[2] + 1 || pos.getZ() < box[1] - 1
                        || pos.getZ() > box[3] + 1)
                    return;
                int along = (alongX ? pos.getX() : pos.getZ()) * sign;
                acc[0] += alongX ? pos.getZ() : pos.getX();
                acc[1]++;
                outer[0] = Math.max(outer[0], along);
            });
            if (acc[1] > 0) {
                int lateral = (int) Math.round((double) acc[0] / acc[1]);
                int along = (outer[0] + 1) * sign;
                return alongX ? new BlockPos(along, origin.getY(), lateral) : new BlockPos(lateral, origin.getY(), along);
            }
        }
        int lateral;
        if (b.hasExplicitPathStartPos() && b.getPathStartPos() != null) {
            lateral = alongX ? b.getPathStartPos().getZ() : b.getPathStartPos().getX();
        } else {
            lateral = alongX ? (box[1] + box[3]) / 2 : (box[0] + box[2]) / 2;
        }
        return alongX
                ? new BlockPos(origin.getX() + out[0], origin.getY(), lateral)
                : new BlockPos(lateral, origin.getY(), origin.getZ() + out[1]);
    }

    /** Picks the outermost village path cell facing the other village and leaves the village from it. */
    private static Scored bestPathNetworkEndpoint(Village village, BlockPos toward, VillageFootprint footprint) {
        var manager = village.getPathManager();
        if (manager == null)
            return null;
        BlockPos vc = village.getCenter();
        double dirX = toward.getX() - vc.getX();
        double dirZ = toward.getZ() - vc.getZ();
        double len = Math.hypot(dirX, dirZ);
        if (len < 1e-6)
            return null;
        double ux = dirX / len;
        double uz = dirZ / len;
        BlockPos[] bestPos = new BlockPos[1];
        double[] bestValue = { Double.NEGATIVE_INFINITY };
        manager.forEachPath((pos, levelValue) -> {
            if (pos == null)
                return;
            double rx = pos.getX() - vc.getX();
            double rz = pos.getZ() - vc.getZ();
            double along = rx * ux + rz * uz;
            double lateral = Math.abs(rx * uz - rz * ux);
            // Far out toward the other village, close to the center line.
            double value = along - 0.6 * lateral;
            if (value > bestValue[0]) {
                bestValue[0] = value;
                bestPos[0] = pos;
            }
        });
        BlockPos p = bestPos[0];
        if (p == null || bestValue[0] <= 0.0)
            return null;
        int[] out = cardinalToward(toward.getX() - p.getX(), toward.getZ() - p.getZ());
        BlockPos threshold = p.offset(out[0], 0, out[1]);
        BlockPos approach = extendOutOfFootprint(threshold, out, footprint);
        return new Scored(new GateEndpoint(threshold, approach), endpointScore(approach, out, toward, footprint));
    }

    /** Leaves the village footprint along the straight line from the village center toward the other village. */
    private static Scored footprintEdgeEndpoint(Village village, BlockPos toward, VillageFootprint footprint) {
        BlockPos vc = village.getCenter();
        double dx = toward.getX() - vc.getX();
        double dz = toward.getZ() - vc.getZ();
        double len = Math.hypot(dx, dz);
        if (len < 1e-6)
            return null;
        int[] out = cardinalToward((int) Math.round(dx), (int) Math.round(dz));
        int limit = (int) Math.min(len, 192);
        int outsideRun = 0;
        for (int t = 0; t <= limit; t++) {
            int x = (int) Math.round(vc.getX() + dx * t / len);
            int z = (int) Math.round(vc.getZ() + dz * t / len);
            outsideRun = footprint.contains(x, z) ? 0 : outsideRun + 1;
            if (outsideRun >= 3) {
                BlockPos threshold = new BlockPos(x, vc.getY(), z);
                BlockPos approach = extendOutOfFootprint(threshold, out, footprint);
                return new Scored(new GateEndpoint(threshold, approach),
                        endpointScore(approach, out, toward, footprint));
            }
        }
        return null;
    }

    /** Lower is better: distance plus heavy penalties for crossing the village or facing away from the target. */
    private static double endpointScore(BlockPos approach, int[] out, BlockPos toward, VillageFootprint footprint) {
        double dx = toward.getX() - approach.getX();
        double dz = toward.getZ() - approach.getZ();
        double dist = Math.hypot(dx, dz);
        double score = dist;
        score += 30.0 * footprint.insideSamples(approach.getX(), approach.getZ(), toward.getX(), toward.getZ(),
                SCORE_LOOKAHEAD);
        if (dist > 1e-6) {
            double facing = (out[0] * dx + out[1] * dz) / dist;
            if (facing < 0.0)
                score += 80.0 * -facing;
        }
        return score;
    }

    /** Standard 6-block approach, extended (straight) until it is outside the village footprint. */
    private static BlockPos extendOutOfFootprint(BlockPos threshold, int[] out, VillageFootprint footprint) {
        int length = APPROACH_LENGTH;
        while (length < APPROACH_LENGTH + MAX_APPROACH_EXTENSION
                && footprint.contains(threshold.getX() + out[0] * length, threshold.getZ() + out[1] * length)) {
            length++;
        }
        return threshold.offset(out[0] * length, 0, out[1] * length);
    }

    private static int[] cardinalToward(int dx, int dz) {
        if (dx == 0 && dz == 0)
            return new int[] { 1, 0 };
        if (Math.abs(dx) >= Math.abs(dz))
            return new int[] { dx >= 0 ? 1 : -1, 0 };
        return new int[] { 0, dz >= 0 ? 1 : -1 };
    }

    /** Footprint of every village whose center lies near the straight corridor between two endpoints. */
    static VillageFootprint corridorFootprint(ServerLevel level, BlockPos a, BlockPos b) {
        List<Village> nearby = new ArrayList<>();
        double pad = 480.0 + 160.0;
        int minX = Math.min(a.getX(), b.getX());
        int maxX = Math.max(a.getX(), b.getX());
        int minZ = Math.min(a.getZ(), b.getZ());
        int maxZ = Math.max(a.getZ(), b.getZ());
        for (Village v : VillageSavedData.get(level).getVillageManager().getAllVillages()) {
            BlockPos c = v.getCenter();
            if (c == null)
                continue;
            if (c.getX() >= minX - pad && c.getX() <= maxX + pad && c.getZ() >= minZ - pad && c.getZ() <= maxZ + pad)
                nearby.add(v);
        }
        return VillageFootprint.of(nearby, VillageFootprint.DEFAULT_MARGIN);
    }

    public static boolean isGateOrBorderPost(BuildingInstance b) {
        return matchesEndpointTier(b, 1) || matchesEndpointTier(b, 2);
    }

    private static boolean matchesEndpointTier(BuildingInstance b, int tier) {
        if (b == null)
            return false;
        ResourceLocation planSetId = b.getPlanSetId();
        ResourceLocation planId = b.getPlanId();
        String setPath = planSetId != null ? planSetId.getPath().toLowerCase(Locale.ROOT) : "";
        String planPath = planId != null ? planId.getPath().toLowerCase(Locale.ROOT) : "";

        if (isExcludedPerimeterStructure(setPath) || isExcludedPerimeterStructure(planPath))
            return false;

        var set = planSetId != null ? ModCultures.getBuildingPlanSet(planSetId) : null;
        if (tier == 1) {
            return setPath.contains("borderpost_gate") || planPath.contains("borderpost_gate")
                    || setPath.contains("full_wall_gateway") || planPath.contains("full_wall_gateway")
                    || setPath.endsWith("gateway") || planPath.contains("gateway");
        }
        if (tier == 2) {
            if (b.hasRuntimeTag("borderpostsign"))
                return true;
            if (setPath.contains("borderpost") || planPath.contains("borderpost"))
                return true;
            return set != null && (set.isBorderPost() || set.hasTag("borderpostsign"));
        }
        if (tier == 3) {
            if (set != null && set.isTownHall())
                return true;
            return setPath.contains("townhall") || setPath.contains("chiefhouse")
                    || setPath.contains("fort") || setPath.contains("abbey");
        }
        return false;
    }

    private static boolean isExcludedPerimeterStructure(String path) {
        if (path == null || path.isEmpty())
            return false;
        return path.contains("corner") || path.contains("tower") || path.contains("watchtower")
                || path.contains("slope") || path.contains("cap") || path.contains("outpost")
                || path.equals("full_wall") || path.endsWith("/full_wall") || path.contains("/full_wall_a_");
    }

    public static ResourceLocation parseOrNull(String raw) {
        return raw == null || raw.isBlank() ? null : ResourceLocation.tryParse(raw);
    }
}
