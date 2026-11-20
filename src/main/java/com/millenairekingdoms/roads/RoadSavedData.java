package com.millenairekingdoms.roads;

import com.millenairekingdoms.kingdom.KingdomSavedData;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Independently versioned SavedData store ({@code data/millenairekingdoms_roads.dat}) for Royal Roads.
 * Stored separately from {@link KingdomSavedData} so existing 1.2.3 saves load with zero migration risk.
 */
public final class RoadSavedData extends SavedData {
    public static final String NAME = "millenairekingdoms_roads";
    public static final int FORMAT = 1;

    public enum Status {
        BUILDING,
        PAUSED_MATERIALS,
        COMPLETED,
        CANCELLED
    }

    public record Waypoint(int x, int z) {}

    public static final class RoadSegment {
        private final UUID id;
        private final UUID kingdomId;
        private final UUID villageA;
        private final UUID villageB;
        private final String cultureId;
        private final String villageTypeId;
        private final List<Waypoint> steps;
        private final int totalBlocks;
        private int completedBlocks;
        private int constructedStepIndex;
        private final BitSet realizedSteps;
        private final int[] realizedY;
        private Status status;
        private final long stipendPaid;
        private final long createdDay;
        private long updatedGameTime;
        /** Steps the live frontier worker may still build; granted by the daily material/quota step. */
        private int workerCredit;
        private final Map<Long, Integer> realizedByChunk = new HashMap<>();

        public RoadSegment(UUID id, UUID kingdomId, UUID villageA, UUID villageB, String cultureId,
                String villageTypeId, List<Waypoint> waypoints, int totalBlocks, int completedBlocks, Status status,
                long stipendPaid, long createdDay, long updatedGameTime) {
            this.id = Objects.requireNonNull(id, "id");
            this.kingdomId = Objects.requireNonNull(kingdomId, "kingdomId");
            this.villageA = Objects.requireNonNull(villageA, "villageA");
            this.villageB = Objects.requireNonNull(villageB, "villageB");
            this.cultureId = cultureId == null ? "" : cultureId;
            this.villageTypeId = villageTypeId == null ? "" : villageTypeId;
            List<Waypoint> dense = RoadPlanner.rasterize8Connected(Objects.requireNonNull(waypoints, "waypoints"));
            this.steps = List.copyOf(dense.isEmpty() ? waypoints : dense);
            this.totalBlocks = totalBlocks > 0 ? totalBlocks : Math.max(1, this.steps.size());
            this.completedBlocks = Math.clamp(completedBlocks, 0, this.totalBlocks);
            this.status = Objects.requireNonNull(status, "status");
            int maxStepIdx = Math.max(0, this.steps.size() - 1);
            if (this.status == Status.COMPLETED || this.completedBlocks >= this.totalBlocks) {
                this.constructedStepIndex = maxStepIdx;
            } else {
                this.constructedStepIndex = Math.clamp(this.completedBlocks, 0, maxStepIdx);
            }
            this.realizedSteps = new BitSet(this.steps.size());
            this.realizedY = new int[this.steps.size()];
            Arrays.fill(this.realizedY, Integer.MIN_VALUE);
            this.stipendPaid = Math.max(0L, stipendPaid);
            this.createdDay = createdDay;
            this.updatedGameTime = updatedGameTime;
        }

        public UUID id() {
            return id;
        }
        public UUID kingdomId() {
            return kingdomId;
        }
        public UUID villageA() {
            return villageA;
        }
        public UUID villageB() {
            return villageB;
        }
        public String cultureId() {
            return cultureId;
        }
        public String villageTypeId() {
            return villageTypeId;
        }
        public List<Waypoint> waypoints() {
            return steps;
        }
        public List<Waypoint> steps() {
            return steps;
        }
        public int totalBlocks() {
            return totalBlocks;
        }
        public int completedBlocks() {
            return completedBlocks;
        }
        public void setCompletedBlocks(int completedBlocks) {
            this.completedBlocks = Math.clamp(completedBlocks, 0, totalBlocks);
            int maxStepIdx = Math.max(0, steps.size() - 1);
            if (this.completedBlocks >= totalBlocks) {
                this.constructedStepIndex = maxStepIdx;
            } else {
                this.constructedStepIndex = Math.clamp(this.completedBlocks, 0, maxStepIdx);
            }
        }
        public int constructedStepIndex() {
            return constructedStepIndex;
        }
        public void setConstructedStepIndex(int stepIndex) {
            int maxStepIdx = Math.max(0, steps.size() - 1);
            this.constructedStepIndex = Math.clamp(stepIndex, 0, maxStepIdx);
            if (this.constructedStepIndex >= maxStepIdx && maxStepIdx > 0) {
                this.completedBlocks = this.totalBlocks;
            } else {
                this.completedBlocks = Math.clamp(this.constructedStepIndex, 0, this.totalBlocks);
            }
        }
        public boolean isAllStepsConstructed() {
            return constructedStepIndex >= Math.max(0, steps.size() - 1);
        }
        public BitSet realizedSteps() {
            return realizedSteps;
        }
        public boolean isStepRealized(int stepIndex) {
            return stepIndex >= 0 && stepIndex < steps.size() && realizedSteps.get(stepIndex);
        }
        public void markStepRealized(int stepIndex) {
            if (stepIndex >= 0 && stepIndex < steps.size()) {
                realizedSteps.set(stepIndex, true);
            }
        }
        public void markStepsRealized(int fromInclusive, int toInclusive) {
            int start = Math.max(0, fromInclusive);
            int end = Math.min(steps.size() - 1, toInclusive);
            if (start <= end) {
                realizedSteps.set(start, end + 1, true);
            }
        }
        public int getRealizedY(int stepIndex) {
            return (stepIndex >= 0 && stepIndex < realizedY.length) ? realizedY[stepIndex] : Integer.MIN_VALUE;
        }
        public void setRealizedY(int stepIndex, int y) {
            if (stepIndex >= 0 && stepIndex < realizedY.length) {
                realizedY[stepIndex] = y;
            }
        }
        int[] realizedYArray() {
            return realizedY;
        }
        public int workerCredit() {
            return workerCredit;
        }
        public void setWorkerCredit(int credit) {
            this.workerCredit = Math.max(0, credit);
        }
        public Status status() {
            return status;
        }
        public void setStatus(Status status) {
            this.status = Objects.requireNonNull(status, "status");
        }
        public long stipendPaid() {
            return stipendPaid;
        }
        public long createdDay() {
            return createdDay;
        }
        public long updatedGameTime() {
            return updatedGameTime;
        }
        public void setUpdatedGameTime(long updatedGameTime) {
            this.updatedGameTime = updatedGameTime;
        }
        public boolean isActive() {
            return status == Status.BUILDING || status == Status.PAUSED_MATERIALS;
        }
        public boolean isCompleted() {
            return status == Status.COMPLETED;
        }
        public boolean connects(UUID u, UUID v) {
            return (villageA.equals(u) && villageB.equals(v)) || (villageA.equals(v) && villageB.equals(u));
        }
        public int realizedProgress(long chunkKey) {
            return realizedByChunk.getOrDefault(chunkKey, -1);
        }
        public void markChunkRealized(long chunkKey, int throughBlock) {
            realizedByChunk.merge(chunkKey, throughBlock, Math::max);
        }
        public Map<Long, Integer> realizedByChunk() {
            return Collections.unmodifiableMap(realizedByChunk);
        }
    }

    private long lastProcessedDay = -1L;
    private final Map<UUID, RoadSegment> segments = new LinkedHashMap<>();

    public RoadSavedData() {}

    public static RoadSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(RoadSavedData::new, RoadSavedData::load, null),
                NAME);
    }

    public long lastProcessedDay() {
        return lastProcessedDay;
    }

    public void setLastProcessedDay(long day) {
        if (this.lastProcessedDay != day) {
            this.lastProcessedDay = day;
            setDirty();
        }
    }

    public void addSegment(RoadSegment segment) {
        segments.put(segment.id(), segment);
        setDirty();
    }

    public RoadSegment segment(UUID id) {
        return segments.get(id);
    }

    public List<RoadSegment> allSegments() {
        return List.copyOf(segments.values());
    }

    public List<RoadSegment> segmentsForKingdom(UUID kingdomId) {
        if (kingdomId == null)
            return List.of();
        List<RoadSegment> out = new ArrayList<>();
        for (RoadSegment seg : segments.values()) {
            if (kingdomId.equals(seg.kingdomId()))
                out.add(seg);
        }
        return List.copyOf(out);
    }

    public RoadSegment activeSegment(UUID kingdomId) {
        if (kingdomId == null)
            return null;
        for (RoadSegment seg : segments.values()) {
            if (kingdomId.equals(seg.kingdomId()) && seg.isActive())
                return seg;
        }
        return null;
    }

    public List<RoadSegment> activeSegments() {
        List<RoadSegment> out = new ArrayList<>();
        for (RoadSegment seg : segments.values()) {
            if (seg.isActive())
                out.add(seg);
        }
        return List.copyOf(out);
    }

    public boolean hasCompletedLink(UUID v1, UUID v2) {
        for (RoadSegment seg : segments.values()) {
            if (seg.isCompleted() && seg.connects(v1, v2))
                return true;
        }
        return false;
    }

    public boolean hasActiveOrCompletedLink(UUID v1, UUID v2) {
        for (RoadSegment seg : segments.values()) {
            if ((seg.isActive() || seg.isCompleted()) && seg.connects(v1, v2))
                return true;
        }
        return false;
    }

    /**
     * Returns all member villages transitively connected to the kingdom's capital through at least one
     * completed road segment whose endpoints both currently belong to {@code kingdomId}.
     * Returns an empty set when no completed friendly road connects any other member to the capital.
     */
    public Set<UUID> connectedToCapital(KingdomSavedData kingdomData, UUID kingdomId) {
        if (kingdomData == null || kingdomId == null)
            return Set.of();
        var kingdom = kingdomData.kingdom(kingdomId);
        if (kingdom == null || !kingdom.active() || kingdom.fallen())
            return Set.of();
        UUID capital = kingdom.capital();
        if (!kingdomId.equals(kingdomData.owner(capital)))
            return Set.of();
        Map<UUID, List<UUID>> adj = friendlyCompletedGraph(kingdomData, kingdomId);
        List<UUID> direct = adj.get(capital);
        if (direct == null || direct.isEmpty())
            return Set.of();
        Set<UUID> visited = new LinkedHashSet<>();
        Queue<UUID> queue = new ArrayDeque<>();
        visited.add(capital);
        queue.add(capital);
        while (!queue.isEmpty()) {
            UUID cur = queue.poll();
            for (UUID next : adj.getOrDefault(cur, List.of())) {
                if (visited.add(next))
                    queue.add(next);
            }
        }
        return visited.size() > 1 ? Collections.unmodifiableSet(visited) : Set.of();
    }

    /** Returns true if {@code v1} and {@code v2} belong to {@code kingdomId} and are linked by completed friendly roads. */
    public boolean areLinkedOnNetwork(KingdomSavedData kingdomData, UUID kingdomId, UUID v1, UUID v2) {
        if (kingdomData == null || kingdomId == null || v1 == null || v2 == null || v1.equals(v2))
            return false;
        if (!kingdomId.equals(kingdomData.owner(v1)) || !kingdomId.equals(kingdomData.owner(v2)))
            return false;
        Map<UUID, List<UUID>> adj = friendlyCompletedGraph(kingdomData, kingdomId);
        if (!adj.containsKey(v1) || !adj.containsKey(v2))
            return false;
        Set<UUID> visited = new HashSet<>();
        Queue<UUID> queue = new ArrayDeque<>();
        visited.add(v1);
        queue.add(v1);
        while (!queue.isEmpty()) {
            UUID cur = queue.poll();
            if (cur.equals(v2))
                return true;
            for (UUID next : adj.getOrDefault(cur, List.of())) {
                if (visited.add(next))
                    queue.add(next);
            }
        }
        return false;
    }

    /**
     * BFS shortest hop path {@code from -> ... -> to} over completed friendly road segments (both endpoints owned by
     * {@code kingdomId}). Returns an empty list when the two villages are not connected (or are the same village).
     */
    public List<UUID> shortestFriendlyPath(KingdomSavedData kingdomData, UUID kingdomId, UUID from, UUID to) {
        if (kingdomData == null || kingdomId == null || from == null || to == null || from.equals(to))
            return List.of();
        if (!kingdomId.equals(kingdomData.owner(from)) || !kingdomId.equals(kingdomData.owner(to)))
            return List.of();
        Map<UUID, List<UUID>> adj = friendlyCompletedGraph(kingdomData, kingdomId);
        Map<UUID, UUID> parent = bfs(adj, from);
        if (!parent.containsKey(to))
            return List.of();
        List<UUID> path = new ArrayList<>();
        for (UUID cur = to; cur != null; cur = parent.get(cur))
            path.add(cur);
        Collections.reverse(path);
        return List.copyOf(path);
    }

    /**
     * Every member village reachable from {@code from} over completed friendly roads, mapped to its BFS predecessor
     * ({@code from} maps to {@code null}). Always contains {@code from} itself.
     */
    public Map<UUID, UUID> friendlyReachable(KingdomSavedData kingdomData, UUID kingdomId, UUID from) {
        if (kingdomData == null || kingdomId == null || from == null)
            return Map.of();
        return Collections.unmodifiableMap(bfs(friendlyCompletedGraph(kingdomData, kingdomId), from));
    }

    private static Map<UUID, UUID> bfs(Map<UUID, List<UUID>> adj, UUID from) {
        Map<UUID, UUID> parent = new LinkedHashMap<>();
        parent.put(from, null);
        Queue<UUID> queue = new ArrayDeque<>();
        queue.add(from);
        while (!queue.isEmpty()) {
            UUID cur = queue.poll();
            // Deterministic expansion order keeps stitched routes identical across restarts.
            List<UUID> next = new ArrayList<>(adj.getOrDefault(cur, List.of()));
            next.sort(UUID::compareTo);
            for (UUID n : next) {
                if (!parent.containsKey(n)) {
                    parent.put(n, cur);
                    queue.add(n);
                }
            }
        }
        return parent;
    }

    /** The (deterministically first) completed segment linking the two villages, or {@code null}. */
    public RoadSegment completedLink(UUID v1, UUID v2) {
        RoadSegment best = null;
        for (RoadSegment seg : segments.values()) {
            if (seg.isCompleted() && seg.connects(v1, v2) && (best == null || seg.id().compareTo(best.id()) < 0))
                best = seg;
        }
        return best;
    }

    /** The segment's steps oriented to start at {@code fromVillage} (reversed when traversed villageB -> villageA). */
    public static List<Waypoint> orientedSteps(RoadSegment seg, UUID fromVillage) {
        if (seg.villageA().equals(fromVillage))
            return seg.steps();
        List<Waypoint> reversed = new ArrayList<>(seg.steps());
        Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    private Map<UUID, List<UUID>> friendlyCompletedGraph(KingdomSavedData kingdomData, UUID kingdomId) {
        Map<UUID, List<UUID>> adj = new HashMap<>();
        for (RoadSegment seg : segments.values()) {
            if (!seg.isCompleted())
                continue;
            if (!kingdomId.equals(kingdomData.owner(seg.villageA()))
                    || !kingdomId.equals(kingdomData.owner(seg.villageB())))
                continue;
            adj.computeIfAbsent(seg.villageA(), k -> new ArrayList<>()).add(seg.villageB());
            adj.computeIfAbsent(seg.villageB(), k -> new ArrayList<>()).add(seg.villageA());
        }
        return adj;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("format", FORMAT);
        tag.putLong("lastProcessedDay", lastProcessedDay);
        ListTag list = new ListTag();
        for (RoadSegment seg : segments.values()) {
            CompoundTag st = new CompoundTag();
            st.putUUID("id", seg.id());
            st.putUUID("kingdomId", seg.kingdomId());
            st.putUUID("villageA", seg.villageA());
            st.putUUID("villageB", seg.villageB());
            st.putString("cultureId", seg.cultureId());
            st.putString("villageTypeId", seg.villageTypeId());
            st.putInt("totalBlocks", seg.totalBlocks());
            st.putInt("completedBlocks", seg.completedBlocks());
            st.putInt("constructedStepIndex", seg.constructedStepIndex());
            st.putLongArray("realizedSteps", seg.realizedSteps().toLongArray());
            // Persist the realized surface heights so +/-1 grading and transition slabs continue seamlessly
            // after a server restart instead of re-guessing heights from the world.
            st.putIntArray("realizedY", seg.realizedYArray());
            st.putInt("workerCredit", seg.workerCredit());
            st.putString("status", seg.status().name());
            st.putLong("stipendPaid", seg.stipendPaid());
            st.putLong("createdDay", seg.createdDay());
            st.putLong("updatedGameTime", seg.updatedGameTime());
            ListTag wps = new ListTag();
            for (Waypoint wp : seg.waypoints()) {
                CompoundTag wt = new CompoundTag();
                wt.putInt("x", wp.x());
                wt.putInt("z", wp.z());
                wps.add(wt);
            }
            st.put("waypoints", wps);
            ListTag realized = new ListTag();
            for (var entry : seg.realizedByChunk().entrySet()) {
                CompoundTag rt = new CompoundTag();
                rt.putLong("chunk", entry.getKey());
                rt.putInt("through", entry.getValue());
                realized.add(rt);
            }
            st.put("realized", realized);
            list.add(st);
        }
        tag.put("segments", list);
        return tag;
    }

    public static RoadSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        RoadSavedData data = new RoadSavedData();
        int format = tag.getInt("format");
        if (format > FORMAT)
            throw new IllegalStateException("Unsupported road save format version: " + format);
        data.lastProcessedDay = tag.contains("lastProcessedDay", Tag.TAG_LONG) ? tag.getLong("lastProcessedDay") : -1L;
        ListTag list = tag.getList("segments", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag st = list.getCompound(i);
            if (!st.hasUUID("id") || !st.hasUUID("kingdomId") || !st.hasUUID("villageA") || !st.hasUUID("villageB"))
                continue;
            ListTag wpsTag = st.getList("waypoints", Tag.TAG_COMPOUND);
            List<Waypoint> wps = new ArrayList<>(wpsTag.size());
            for (int j = 0; j < wpsTag.size(); j++) {
                CompoundTag wt = wpsTag.getCompound(j);
                wps.add(new Waypoint(wt.getInt("x"), wt.getInt("z")));
            }
            if (wps.size() < 2)
                continue;
            Status status = Status.BUILDING;
            try {
                status = Status.valueOf(st.getString("status"));
            } catch (IllegalArgumentException ignored) {}
            RoadSegment seg = new RoadSegment(st.getUUID("id"), st.getUUID("kingdomId"), st.getUUID("villageA"),
                    st.getUUID("villageB"), st.getString("cultureId"), st.getString("villageTypeId"), wps,
                    st.getInt("totalBlocks"), st.getInt("completedBlocks"), status, st.getLong("stipendPaid"),
                    st.getLong("createdDay"), st.getLong("updatedGameTime"));
            if (st.contains("constructedStepIndex", Tag.TAG_INT)) {
                int savedStep = st.getInt("constructedStepIndex");
                int savedComp = st.getInt("completedBlocks");
                seg.setConstructedStepIndex(savedStep);
                if (savedComp == 0 && savedStep == 0) {
                    seg.setCompletedBlocks(0);
                }
            }
            if (st.contains("realizedSteps", Tag.TAG_LONG_ARRAY)) {
                BitSet restoredBits = BitSet.valueOf(st.getLongArray("realizedSteps"));
                int maxBit = Math.min(seg.steps().size(), restoredBits.length());
                for (int b = 0; b < maxBit; b++) {
                    if (restoredBits.get(b)) {
                        seg.markStepRealized(b);
                    }
                }
            }
            if (st.contains("realizedY", Tag.TAG_INT_ARRAY)) {
                int[] ys = st.getIntArray("realizedY");
                int n = Math.min(ys.length, seg.steps().size());
                boolean hasAnyRealizedY = false;
                for (int b = 0; b < n; b++) {
                    if (ys[b] != Integer.MIN_VALUE) {
                        seg.setRealizedY(b, ys[b]);
                        hasAnyRealizedY = true;
                    }
                }
                if (hasAnyRealizedY) {
                    int maxCheck = Math.min(seg.constructedStepIndex(), n - 1);
                    BitSet preHealRealized = (BitSet) seg.realizedSteps().clone();
                    for (int b = 0; b <= maxCheck; b++) {
                        if (preHealRealized.get(b) && seg.getRealizedY(b) == Integer.MIN_VALUE) {
                            seg.realizedSteps().clear(Math.max(0, b - 2), Math.min(seg.steps().size(), b + 3));
                        }
                    }
                }
            }
            seg.setWorkerCredit(st.getInt("workerCredit"));
            ListTag realized = st.getList("realized", Tag.TAG_COMPOUND);
            for (int j = 0; j < realized.size(); j++) {
                CompoundTag rt = realized.getCompound(j);
                seg.markChunkRealized(rt.getLong("chunk"), rt.getInt("through"));
            }
            data.segments.put(seg.id(), seg);
        }
        return data;
    }
}
