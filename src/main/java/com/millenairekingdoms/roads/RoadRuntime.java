package com.millenairekingdoms.roads;

import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.politics.PoliticsService;
import com.millenairekingdoms.roads.RoadPlanner.Sample;
import com.millenairekingdoms.roads.RoadSavedData.RoadSegment;
import com.millenairekingdoms.roads.RoadSavedData.Status;
import com.millenairekingdoms.roads.RoadSavedData.Waypoint;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.millenaire.culture.ModCultures;
import org.millenaire.culture.VillagerType;
import org.millenaire.entity.MillVillager;
import org.millenaire.entity.ModEntities;
import org.millenaire.entity.VillagerAppearanceFactory;
import org.millenaire.village.Village;

/**
 * Runtime coordinator for Royal Roads:
 * <ul>
 *   <li>Dual-trigger deferred realization on {@code ChunkEvent.Load} and {@code constructedStepIndex} updates.</li>
 *   <li>Synchronized 1-step-at-a-time walking frontier worker ({@code mk_road_worker}) using {@code PathNavigation}.</li>
 *   <li>Suspends passive background step jumps whenever a player is within 64 blocks and the worker is active.</li>
 *   <li>Supports {@code RETURNING} walk-back on completion/pause and instant {@code discard()} cleanup on chunk unload,
 *       player distance &gt; 64 blocks, or server restart.</li>
 * </ul>
 */
public final class RoadRuntime {
    public static final String WORKER_TAG = "mk_road_worker";
    public static final String WORKER_ROAD_ID_TAG = "mk_road_id";
    public static final String WORKER_RETURNING_TAG = "mk_road_returning";
    public static final String WORKER_RETURN_STEP_TAG = "mk_road_return_step";
    public static final double FRONTIER_ADVANCE_DISTANCE = 3.5;
    private static final int WORKER_SYNC_INTERVAL = 20;
    private static final int RETURN_LOOKAHEAD_STEPS = 12;
    private static final double RETURN_WALK_SPEED = 0.8;

    private static final Map<ResourceKey<Level>, Set<Long>> PENDING_CHUNKS = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, Map<UUID, UUID>> ACTIVE_WORKERS = new ConcurrentHashMap<>();
    private static final Set<ResourceKey<Level>> REALIZING_LEVELS = ConcurrentHashMap.newKeySet();
    private static final Set<ResourceKey<Level>> INITIAL_PLAYER_SYNC_DONE = ConcurrentHashMap.newKeySet();

    private RoadRuntime() {}

    public static void queueChunkForRealization(ServerLevel level, ChunkPos chunkPos) {
        // Only queue here. This is called from ChunkEvent.Load, where changing blocks (in this or neighbouring
        // chunks) is unsafe; the queue is drained from the level tick instead.
        PENDING_CHUNKS.computeIfAbsent(level.dimension(), k -> ConcurrentHashMap.newKeySet()).add(chunkPos.toLong());
    }

    public static void onChunkUnload(ServerLevel level, ChunkPos chunkPos) {
        Set<Long> pending = PENDING_CHUNKS.get(level.dimension());
        if (pending != null)
            pending.remove(chunkPos.toLong());

        Map<UUID, UUID> workers = ACTIVE_WORKERS.get(level.dimension());
        if (workers != null && !workers.isEmpty()) {
            Iterator<Map.Entry<UUID, UUID>> it = workers.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, UUID> entry = it.next();
                Entity entity = level.getEntity(entry.getValue());
                if (entity == null || new ChunkPos(entity.blockPosition()).equals(chunkPos)) {
                    if (entity != null)
                        entity.discard();
                    it.remove();
                }
            }
        }
        // Untracked stray workers are discarded by onEntityJoin when their chunk loads again; scanning every
        // entity of the level on each chunk unload was too expensive.
    }

    public static void cleanupAllOnServerBoundary(MinecraftServer server) {
        PENDING_CHUNKS.clear();
        ACTIVE_WORKERS.clear();
        REALIZING_LEVELS.clear();
        INITIAL_PLAYER_SYNC_DONE.clear();
        if (server == null)
            return;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (isRoadWorker(entity))
                    entity.discard();
            }
        }
    }

    public static void onEntityJoin(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level) || !isRoadWorker(entity))
            return;
        Map<UUID, UUID> workers = ACTIVE_WORKERS.get(level.dimension());
        if (workers == null || !workers.containsValue(entity.getUUID())) {
            entity.discard();
        }
    }

    public static void despawnWorkerForSegment(ServerLevel level, UUID segmentId) {
        Map<UUID, UUID> workers = ACTIVE_WORKERS.get(level.dimension());
        if (workers != null) {
            UUID entityId = workers.remove(segmentId);
            if (entityId != null) {
                Entity entity = level.getEntity(entityId);
                if (entity != null)
                    entity.discard();
            }
        }
        discardDuplicatesForSegment(level, segmentId, null);
    }

    /** Transitions the segment's worker into RETURNING walk-back state (or discards if out of range). */
    public static void onSegmentCompletedOrPaused(ServerLevel level, RoadSegment seg) {
        Map<UUID, UUID> workers = ACTIVE_WORKERS.get(level.dimension());
        if (workers == null)
            return;
        UUID entityId = workers.get(seg.id());
        Entity entity = entityId != null ? level.getEntity(entityId) : null;
        if (!(entity instanceof MillVillager worker) || !worker.isAlive()) {
            workers.remove(seg.id());
            return;
        }
        int anchorIdx = highestRealizedStepBehind(seg);
        if (anchorIdx <= 0) {
            worker.discard();
            workers.remove(seg.id());
            return;
        }
        worker.getPersistentData().putBoolean(WORKER_RETURNING_TAG, true);
        worker.getPersistentData().putInt(WORKER_RETURN_STEP_TAG, anchorIdx);
        steerReturningWorker(level, seg, worker, anchorIdx, true);
    }

    public static boolean isRoadWorker(Entity entity) {
        return entity != null && entity.getPersistentData().getBoolean(WORKER_TAG);
    }

    public static boolean isReturningWorker(Entity entity) {
        return isRoadWorker(entity) && entity.getPersistentData().getBoolean(WORKER_RETURNING_TAG);
    }

    public static int activeWorkerCount(ServerLevel level) {
        int count = 0;
        for (Entity entity : level.getAllEntities()) {
            if (isRoadWorker(entity) && !isReturningWorker(entity) && entity.isAlive())
                count++;
        }
        return count;
    }

    /**
     * Returns true when a player is within {@code frontierWorkerRange} (64 blocks) and {@code mk_road_worker}
     * is active for {@code seg}, so passive background progress must pause and let the worker walk step-by-step.
     */
    public static boolean shouldSuspendBackgroundProgress(
            ServerLevel level, RoadSegment seg, RoadConfig.Settings cfg) {
        return shouldSuspendBackgroundProgress(level, seg, cfg, level.players());
    }

    public static boolean shouldSuspendBackgroundProgress(ServerLevel level, RoadSegment seg,
            RoadConfig.Settings cfg, Collection<? extends ServerPlayer> players) {
        if (seg == null || seg.status() != Status.BUILDING || seg.steps().isEmpty())
            return false;
        Map<UUID, UUID> workers = ACTIVE_WORKERS.get(level.dimension());
        UUID workerId = workers != null ? workers.get(seg.id()) : null;
        Entity worker = workerId != null ? level.getEntity(workerId) : null;
        boolean workerActive = worker instanceof MillVillager && worker.isAlive() && !isReturningWorker(worker);
        if (!workerActive)
            return false;
        if (players == null || players.isEmpty())
            return true;

        int idx = Math.clamp(seg.constructedStepIndex(), 0, seg.steps().size() - 1);
        Waypoint frontier = seg.steps().get(idx);
        double maxDistSq = (double) cfg.frontierWorkerRange() * cfg.frontierWorkerRange();
        return isAnyPlayerWithinRange(players, frontier, worker, maxDistSq);
    }

    public static void onLevelTick(ServerLevel level) {
        drainPendingChunks(level);
        if (level.getGameTime() % WORKER_SYNC_INTERVAL == 0L) {
            RoadService.tickInactiveSegments(level);
            syncFrontierWorkers(level);
            if (!level.players().isEmpty() && INITIAL_PLAYER_SYNC_DONE.add(level.dimension())) {
                RoadPlacer.realizeAvailableSteps(level, RoadSavedData.get(level));
            }
        }
    }

    public static void drainPendingChunks(ServerLevel level) {
        Set<Long> pending = PENDING_CHUNKS.get(level.dimension());
        if (pending == null || pending.isEmpty())
            return;
        if (!REALIZING_LEVELS.add(level.dimension()))
            return;
        try {
            pending.clear();
            RoadSavedData roadData = RoadSavedData.get(level);
            RoadPlacer.realizeAvailableSteps(level, roadData);
        } finally {
            REALIZING_LEVELS.remove(level.dimension());
        }
    }

    /**
     * Finds the highest realized step index in {@code seg.realizedSteps()} immediately behind (or at)
     * {@code seg.constructedStepIndex()}.
     */
    public static int highestRealizedStepBehind(RoadSegment seg) {
        if (seg.steps().isEmpty())
            return 0;
        int frontier = Math.clamp(seg.constructedStepIndex(), 0, seg.steps().size() - 1);
        for (int r = frontier - 1; r >= 0; r--) {
            if (seg.isStepRealized(r))
                return r;
        }
        if (seg.isStepRealized(frontier))
            return frontier;
        return 0;
    }

    public static void syncFrontierWorkers(ServerLevel level) {
        syncFrontierWorkers(level, level.players());
    }

    public static void syncFrontierWorkers(ServerLevel level, Collection<? extends ServerPlayer> players) {
        RoadConfig.Settings cfg = RoadConfig.current();
        if (!cfg.enabled()) {
            cleanupLevelWorkers(level);
            return;
        }
        RoadSavedData roadData = RoadSavedData.get(level);
        Map<UUID, UUID> workers = ACTIVE_WORKERS.computeIfAbsent(level.dimension(), k -> new ConcurrentHashMap<>());
        double maxDistSq = (double) cfg.frontierWorkerRange() * cfg.frontierWorkerRange();

        // 1. Update or clean up existing workers (including RETURNING workers).
        Iterator<Map.Entry<UUID, UUID>> it = workers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, UUID> entry = it.next();
            RoadSegment seg = roadData.segment(entry.getKey());
            Entity existing = level.getEntity(entry.getValue());
            if (seg == null || !(existing instanceof MillVillager worker) || !worker.isAlive()) {
                if (existing != null)
                    existing.discard();
                it.remove();
                continue;
            }
            if (seg.status() == Status.CANCELLED) {
                worker.discard();
                it.remove();
                continue;
            }
            if (seg.status() != Status.BUILDING) {
                if (!isReturningWorker(worker)) {
                    onSegmentCompletedOrPaused(level, seg);
                }
                if (!tickReturningWorker(level, seg, worker, players, maxDistSq)) {
                    worker.discard();
                    it.remove();
                }
            } else if (isReturningWorker(worker)) {
                // Segment resumed BUILDING while worker was returning -> switch back to BUILDING!
                worker.getPersistentData().putBoolean(WORKER_RETURNING_TAG, false);
            }
        }

        // 2. Evaluate every ongoing BUILDING road segment.
        for (RoadSegment seg : roadData.activeSegments()) {
            if (seg.status() != Status.BUILDING || seg.steps().isEmpty()) {
                continue;
            }
            int frontierIdx = Math.clamp(seg.constructedStepIndex(), 0, seg.steps().size() - 1);
            Waypoint frontierWp = seg.steps().get(frontierIdx);
            int cx = SectionPos.blockToSectionCoord(frontierWp.x());
            int cz = SectionPos.blockToSectionCoord(frontierWp.z());
            if (!level.hasChunk(cx, cz)) {
                despawnWorkerForSegment(level, seg.id());
                continue;
            }

            UUID currentId = workers.get(seg.id());
            Entity currentEntity = currentId == null ? null : level.getEntity(currentId);
            boolean playerNear = isAnyPlayerWithinRange(players, frontierWp, currentEntity, maxDistSq);
            if (!playerNear) {
                despawnWorkerForSegment(level, seg.id());
                continue;
            }

            // Ensure any loaded steps up to constructedStepIndex (such as step 0) are realized.
            RoadPlacer.realizeAvailableSteps(level, seg);

            RoadPlacer.RoadStyle style = RoadPlacer.styleFor(seg);
            int anchorIdx = highestRealizedStepBehind(seg);
            Waypoint anchorWp = seg.steps().get(anchorIdx);
            int anchorWalkY = RoadPlacer.findWalkableY(level, anchorWp.x(), anchorWp.z(), style);
            int frontierWalkY = RoadPlacer.findWalkableY(level, frontierWp.x(), frontierWp.z(), style);
            if (anchorWalkY <= level.getMinBuildHeight() + 1)
                continue;

            if (!(currentEntity instanceof MillVillager worker) || !worker.isAlive()) {
                discardDuplicatesForSegment(level, seg.id(), null);
                MillVillager spawned = ModEntities.MILL_VILLAGER.get().create(level);
                if (spawned == null)
                    continue;
                ResourceLocation capitalCulture = resolveCapitalCulture(level, seg);
                VillagerType vt = resolveRoadWorkerType(capitalCulture);
                if (vt != null) {
                    spawned.setVillagerTypeId(vt.id());
                    spawned.syncTypeDerivedFlags(vt);
                    VillagerAppearanceFactory.randomizeAppearance(spawned, vt);
                }
                spawned.setNoAi(false);
                spawned.goalSelector.removeAllGoals(g -> true);
                spawned.targetSelector.removeAllGoals(g -> true);
                spawned.setInvulnerable(true);
                spawned.setSilent(true);
                spawned.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
                spawned.setCustomName(Component.translatable("millenairekingdoms.road.worker_name"));
                spawned.setCustomNameVisible(false);
                spawned.getPersistentData().putBoolean(WORKER_TAG, true);
                spawned.getPersistentData().putBoolean(WORKER_RETURNING_TAG, false);
                spawned.getPersistentData().putUUID(WORKER_ROAD_ID_TAG, seg.id());

                Sample sample = RoadPlanner.sampleAt(seg.steps(), anchorIdx);
                float yaw = (float) (Math.toDegrees(Math.atan2(sample.dirZ(), sample.dirX())) - 90.0);
                spawned.moveTo(anchorWp.x() + 0.5, anchorWalkY, anchorWp.z() + 0.5, yaw, 0.0f);
                workers.put(seg.id(), spawned.getUUID());
                if (!level.addFreshEntity(spawned)) {
                    workers.remove(seg.id());
                } else {
                    spawned.getNavigation().moveTo(frontierWp.x() + 0.5, frontierWalkY, frontierWp.z() + 0.5, 1.0);
                    spawned.swing(InteractionHand.MAIN_HAND, true);
                }
            } else {
                // Existing worker: check distance to steps[constructedStepIndex].
                double distToFrontier = Math.hypot(
                        worker.getX() - (frontierWp.x() + 0.5),
                        worker.getZ() - (frontierWp.z() + 0.5));

                if (distToFrontier <= FRONTIER_ADVANCE_DISTANCE && seg.workerCredit() <= 0
                        && !refillWorkerCredit(level, roadData, seg)) {
                    // Out of surplus materials: the project pauses (and resumes from the daily step once
                    // materials arrive) instead of the worker standing at the frontier forever.
                    seg.setStatus(Status.PAUSED_MATERIALS);
                    seg.setUpdatedGameTime(level.getGameTime());
                    roadData.setDirty();
                    onSegmentCompletedOrPaused(level, seg);
                } else if (distToFrontier <= FRONTIER_ADVANCE_DISTANCE) {
                    // Within 3.5 blocks of steps[constructedStepIndex]: advance +1 step and realize immediately!
                    worker.swing(InteractionHand.MAIN_HAND, true);
                    seg.setWorkerCredit(seg.workerCredit() - 1);
                    if (frontierIdx < seg.steps().size() - 1) {
                        int nextIdx = frontierIdx + 1;
                        seg.setConstructedStepIndex(nextIdx);
                        seg.setUpdatedGameTime(level.getGameTime());
                        roadData.setDirty();
                        RoadPlacer.realizeAvailableSteps(level, seg);

                        int nextAnchorIdx = highestRealizedStepBehind(seg);
                        Waypoint nextAnchorWp = seg.steps().get(nextAnchorIdx);
                        int nextAnchorY = RoadPlacer.findWalkableY(level, nextAnchorWp.x(), nextAnchorWp.z(), style);
                        Sample sample = RoadPlanner.sampleAt(seg.steps(), nextAnchorIdx);
                        float yaw = (float) (Math.toDegrees(Math.atan2(sample.dirZ(), sample.dirX())) - 90.0);

                        // Walk onto the newly realized step behind nextIdx and navigate toward steps[nextIdx].
                        stepWorkerSmoothlyToward(worker, nextAnchorWp.x() + 0.5, nextAnchorY, nextAnchorWp.z() + 0.5, yaw);
                        Waypoint nextFrontierWp = seg.steps().get(nextIdx);
                        int nextFrontierY = RoadPlacer.findWalkableY(level, nextFrontierWp.x(), nextFrontierWp.z(), style);
                        worker.getNavigation().moveTo(
                                nextFrontierWp.x() + 0.5, nextFrontierY, nextFrontierWp.z() + 0.5, 1.0);
                    } else {
                        // Reached final step -> complete road and transition to RETURNING!
                        seg.setConstructedStepIndex(seg.steps().size() - 1);
                        seg.setCompletedBlocks(seg.totalBlocks());
                        seg.setStatus(Status.COMPLETED);
                        seg.setUpdatedGameTime(level.getGameTime());
                        roadData.setDirty();
                        RoadPlacer.realizeAvailableSteps(level, seg);
                        onSegmentCompletedOrPaused(level, seg);
                    }
                } else {
                    // Worker is > 3.5 blocks behind frontier: do NOT increment constructedStepIndex!
                    // Walk along realized steps toward steps[constructedStepIndex] using PathNavigation.
                    Sample sample = RoadPlanner.sampleAt(seg.steps(), anchorIdx);
                    float yaw = (float) (Math.toDegrees(Math.atan2(sample.dirZ(), sample.dirX())) - 90.0);
                    stepWorkerSmoothlyToward(worker, anchorWp.x() + 0.5, anchorWalkY, anchorWp.z() + 0.5, yaw);
                    worker.getNavigation().moveTo(frontierWp.x() + 0.5, frontierWalkY, frontierWp.z() + 0.5, 1.0);
                    worker.swing(InteractionHand.MAIN_HAND, true);
                }
            }
        }
    }

    /**
     * Pays for the worker's next steps on demand: one surplus material unit buys {@code blocksPerMaterial} steps,
     * exactly the background price. When an endpoint village is inactive (its stock cannot be reached) the road
     * advances for free, as the inactive background timer does. Returns false when nothing could be paid.
     */
    private static boolean refillWorkerCredit(ServerLevel level, RoadSavedData roadData, RoadSegment seg) {
        RoadConfig.Settings cfg = RoadConfig.current();
        int perUnit = Math.max(1, cfg.blocksPerMaterial());
        int granted;
        if (!RoadMaterialStock.areBothEndpointsLoaded(level, seg.villageA(), seg.villageB())) {
            granted = perUnit;
        } else {
            int consumed = RoadMaterialStock.consumeSurplus(level, seg.villageA(), seg.villageB(), 1);
            granted = consumed * perUnit;
        }
        if (granted <= 0)
            return false;
        seg.setWorkerCredit(seg.workerCredit() + granted);
        roadData.setDirty();
        return true;
    }

    /**
     * Advances the worker smoothly by at most 1 block along the realized roadway when entity navigation ticks
     * are not running synchronously, never teleporting across large gaps.
     */
    private static void stepWorkerSmoothlyToward(
            MillVillager worker, double targetX, double targetY, double targetZ, float yaw) {
        double dx = targetX - worker.getX();
        double dz = targetZ - worker.getZ();
        double dist = Math.hypot(dx, dz);
        if (dist <= 1e-3)
            return;
        double step = Math.min(dist, 1.0);
        double nx = worker.getX() + (dx / dist) * step;
        double nz = worker.getZ() + (dz / dist) * step;
        ServerLevel level = (ServerLevel) worker.level();
        int ix = (int) Math.floor(nx);
        int iz = (int) Math.floor(nz);
        if (!level.hasChunk(SectionPos.blockToSectionCoord(ix), SectionPos.blockToSectionCoord(iz)))
            return;
        worker.setYRot(yaw);
        worker.setYHeadRot(yaw);
        worker.setPos(nx, targetY, nz);
    }

    /**
     * Walk-back after completion/pause. The worker follows one continuous navigation path toward a waypoint
     * {@link #RETURN_LOOKAHEAD_STEPS} steps back along the road and only gets a new target when that path is done.
     * The old version teleported it one block and gave it a one-step path every second, which made it stutter.
     */
    private static boolean tickReturningWorker(ServerLevel level, RoadSegment seg, MillVillager worker,
            Collection<? extends ServerPlayer> players, double maxDistSq) {
        if (seg.steps().isEmpty())
            return false;
        int curStep = Math.clamp(worker.getPersistentData().getInt(WORKER_RETURN_STEP_TAG), 0, seg.steps().size() - 1);
        if (curStep <= 0)
            return false;
        // Track progress by the road step nearest to the worker (searching only behind the last known step).
        int nearest = curStep;
        double bestSq = Double.MAX_VALUE;
        for (int i = Math.max(0, curStep - RETURN_LOOKAHEAD_STEPS * 2); i <= curStep; i++) {
            Waypoint w = seg.steps().get(i);
            double dx = worker.getX() - (w.x() + 0.5);
            double dz = worker.getZ() - (w.z() + 0.5);
            double dSq = dx * dx + dz * dz;
            if (dSq < bestSq) {
                bestSq = dSq;
                nearest = i;
            }
        }
        if (nearest <= 1)
            return false; // Back at the start of the road.
        Waypoint here = seg.steps().get(nearest);
        if (!level.hasChunk(SectionPos.blockToSectionCoord(here.x()), SectionPos.blockToSectionCoord(here.z())))
            return false;
        if (!isAnyPlayerWithinRange(players, here, worker, maxDistSq))
            return false;
        worker.getPersistentData().putInt(WORKER_RETURN_STEP_TAG, nearest);
        return steerReturningWorker(level, seg, worker, nearest, false);
    }

    private static boolean steerReturningWorker(ServerLevel level, RoadSegment seg, MillVillager worker,
            int fromStep, boolean force) {
        int targetIdx = Math.max(0, fromStep - RETURN_LOOKAHEAD_STEPS);
        // Never path into unloaded chunks: pull the target forward until it is loaded.
        while (targetIdx < fromStep) {
            Waypoint t = seg.steps().get(targetIdx);
            if (level.hasChunk(SectionPos.blockToSectionCoord(t.x()), SectionPos.blockToSectionCoord(t.z())))
                break;
            targetIdx++;
        }
        if (targetIdx >= fromStep)
            return false;
        if (!force && !worker.getNavigation().isDone())
            return true; // Still walking the current path: leave it alone.
        Waypoint target = seg.steps().get(targetIdx);
        int walkY = RoadPlacer.findWalkableY(level, target.x(), target.z(), RoadPlacer.styleFor(seg));
        if (worker.getNavigation().moveTo(target.x() + 0.5, walkY, target.z() + 0.5, RETURN_WALK_SPEED))
            return true;
        // No path (e.g. a gap): fall back to a single smooth step along the road.
        Waypoint next = seg.steps().get(fromStep - 1);
        int nextY = RoadPlacer.findWalkableY(level, next.x(), next.z(), RoadPlacer.styleFor(seg));
        Sample sample = RoadPlanner.sampleAt(seg.steps(), fromStep - 1);
        float yaw = (float) (Math.toDegrees(Math.atan2(-sample.dirZ(), -sample.dirX())) - 90.0);
        stepWorkerSmoothlyToward(worker, next.x() + 0.5, nextY, next.z() + 0.5, yaw);
        return true;
    }

    private static boolean isAnyPlayerWithinRange(Collection<? extends ServerPlayer> players, Waypoint frontierWp,
            Entity worker, double maxDistSq) {
        for (ServerPlayer p : players) {
            if (p.isSpectator())
                continue;
            double dx = p.getX() - (frontierWp.x() + 0.5);
            double dz = p.getZ() - (frontierWp.z() + 0.5);
            if (dx * dx + dz * dz <= maxDistSq)
                return true;
            if (worker != null && worker.isAlive()) {
                double wdx = p.getX() - worker.getX();
                double wdz = p.getZ() - worker.getZ();
                if (wdx * wdx + wdz * wdz <= maxDistSq)
                    return true;
            }
        }
        return false;
    }

    private static ResourceLocation resolveCapitalCulture(ServerLevel level, RoadSegment seg) {
        var kingdom = KingdomSavedData.get(level).kingdom(seg.kingdomId());
        if (kingdom != null) {
            Village capital = PoliticsService.village(level, kingdom.capital());
            if (capital != null && capital.getCultureId() != null)
                return capital.getCultureId();
        }
        return RoadService.parseOrNull(seg.cultureId());
    }

    private static VillagerType resolveRoadWorkerType(ResourceLocation cultureId) {
        ResourceLocation buildPathGoal = ResourceLocation.parse("millenaire:build_path");
        ResourceLocation buildGoal = ResourceLocation.parse("millenaire:build");
        var candidates = ModCultures.getAllVillagerTypes()
                                 .values()
                                 .stream()
                                 .filter(vt -> !vt.isChild() && !vt.isRaider() && !vt.isHostile())
                                 .filter(vt -> cultureId == null || cultureId.equals(vt.culture()))
                                 .sorted(Comparator.comparing(vt -> vt.id().toString()))
                                 .toList();
        if (candidates.isEmpty() && cultureId != null) {
            candidates = ModCultures.getAllVillagerTypes()
                                 .values()
                                 .stream()
                                 .filter(vt -> !vt.isChild() && !vt.isRaider() && !vt.isHostile())
                                 .sorted(Comparator.comparing(vt -> vt.id().toString()))
                                 .toList();
        }
        for (VillagerType vt : candidates) {
            if (vt.goals() != null && vt.goals().contains(buildPathGoal))
                return vt;
        }
        for (VillagerType vt : candidates) {
            if (vt.goals() != null && vt.goals().contains(buildGoal))
                return vt;
        }
        return candidates.isEmpty() ? null : candidates.getFirst();
    }

    private static void cleanupLevelWorkers(ServerLevel level) {
        ACTIVE_WORKERS.remove(level.dimension());
        for (Entity entity : level.getAllEntities()) {
            if (isRoadWorker(entity))
                entity.discard();
        }
    }

    private static void discardDuplicatesForSegment(ServerLevel level, UUID segId, UUID keepUuid) {
        for (Entity entity : level.getAllEntities()) {
            if (!isRoadWorker(entity))
                continue;
            if (entity.getUUID().equals(keepUuid))
                continue;
            if (entity.getPersistentData().hasUUID(WORKER_ROAD_ID_TAG)
                    && segId.equals(entity.getPersistentData().getUUID(WORKER_ROAD_ID_TAG))) {
                entity.discard();
            }
        }
    }
}
