package com.millenairekingdoms.march;

import com.millenairekingdoms.roads.RoadSavedData.Waypoint;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Independently versioned march tracking ({@code data/millenairekingdoms_marches.dat}). It only holds positions along
 * routes; shipment accounting stays in {@code RoyalShipmentStore} and orders/raids stay in {@code KingdomSavedData}, so
 * losing or deleting this file can never break those stores. Physical entities are never persisted here.
 */
public final class MarchSavedData extends SavedData {
    public static final String NAME = "millenairekingdoms_marches";
    public static final int FORMAT = 1;

    public enum Kind { CARAVAN, ARMY }
    public enum Status {
        /** Advancing along the route. */
        MARCHING,
        /** Reached the end of its route (caravan at the destination square, army at the target perimeter). */
        ARRIVED,
        /** Army lost a roadside skirmish (or a caravan was robbed); waiting for its order/shipment to settle. */
        DEFEATED
    }

    public static final class March {
        private final UUID id;
        private final Kind kind;
        private final UUID kingdom;
        private final UUID source;
        private final UUID destination;
        private final String culture;
        private final List<Waypoint> route;
        private final List<Integer> stops;
        private final long createdTick;
        private int stepIndex;
        private double progress;
        private int nextStop;
        private long pauseUntil;
        private Status status;
        private long strength;
        private int squad;
        private final List<String> unitTypes;
        private long statusTick;
        /** Caravans: the shipment's durable arrival tick, which paces background progress. Armies: unused (0). */
        private long etaTick;
        /** Step index where the built Royal Road ends (-1 when there is no built road or it ends at route end). */
        private int roadEndStep = -1;

        public March(UUID id, Kind kind, UUID kingdom, UUID source, UUID destination, String culture,
                List<Waypoint> route, List<Integer> stops, long createdTick, long strength, int squad,
                List<String> unitTypes) {
            this.id = Objects.requireNonNull(id);
            this.kind = Objects.requireNonNull(kind);
            this.kingdom = Objects.requireNonNull(kingdom);
            this.source = Objects.requireNonNull(source);
            this.destination = Objects.requireNonNull(destination);
            this.culture = culture == null ? "" : culture;
            if (route == null || route.size() < 2)
                throw new IllegalArgumentException("A march needs a route of at least two steps");
            this.route = List.copyOf(route);
            this.stops = List.copyOf(stops == null ? List.of() : stops);
            this.createdTick = createdTick;
            this.strength = Math.max(0, strength);
            this.squad = Math.max(1, squad);
            this.unitTypes = List.copyOf(unitTypes == null ? List.of() : unitTypes);
            this.status = Status.MARCHING;
            this.statusTick = createdTick;
        }

        public UUID id() {
            return id;
        }
        public Kind kind() {
            return kind;
        }
        public UUID kingdom() {
            return kingdom;
        }
        public UUID source() {
            return source;
        }
        public UUID destination() {
            return destination;
        }
        public String culture() {
            return culture;
        }
        public List<Waypoint> route() {
            return route;
        }
        public List<Integer> stops() {
            return stops;
        }
        public long createdTick() {
            return createdTick;
        }
        public int stepIndex() {
            return stepIndex;
        }
        public int lastIndex() {
            return route.size() - 1;
        }
        public Waypoint position() {
            return route.get(Math.clamp(stepIndex, 0, route.size() - 1));
        }
        public int remainingSteps() {
            return lastIndex() - stepIndex;
        }
        public double progress() {
            return progress;
        }
        public int nextStop() {
            return nextStop;
        }
        public long pauseUntil() {
            return pauseUntil;
        }
        public Status status() {
            return status;
        }
        public long statusTick() {
            return statusTick;
        }
        public long strength() {
            return strength;
        }
        public int squad() {
            return squad;
        }
        public List<String> unitTypes() {
            return unitTypes;
        }
        public boolean marching() {
            return status == Status.MARCHING;
        }

        public long etaTick() {
            return etaTick;
        }
        void setEtaTick(long tick) {
            etaTick = tick;
        }
        public int roadEndStep() {
            return roadEndStep;
        }
        public void setRoadEndStep(int value) {
            roadEndStep = value;
        }
        public void setStepIndex(int index) {
            stepIndex = Math.clamp(index, 0, lastIndex());
        }
        void setProgress(double value) {
            progress = Math.max(0.0, value);
        }
        void setNextStop(int value) {
            nextStop = Math.max(0, value);
        }
        void setPauseUntil(long tick) {
            pauseUntil = tick;
        }
        public void setStatus(Status value, long tick) {
            status = Objects.requireNonNull(value);
            statusTick = tick;
        }
        void setStrength(long value) {
            strength = Math.max(0, value);
        }
        void setSquad(int value) {
            squad = Math.max(1, value);
        }
    }

    private final Map<UUID, March> marches = new LinkedHashMap<>();

    public MarchSavedData() {}

    public static MarchSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(MarchSavedData::new, MarchSavedData::load, null), NAME);
    }

    public March march(UUID id) {
        return id == null ? null : marches.get(id);
    }

    public List<March> all() {
        return List.copyOf(marches.values());
    }

    public List<March> of(Kind kind) {
        List<March> out = new ArrayList<>();
        for (March m : marches.values())
            if (m.kind == kind)
                out.add(m);
        return out;
    }

    public void put(March march) {
        marches.put(march.id(), march);
        setDirty();
    }

    public March remove(UUID id) {
        March removed = marches.remove(id);
        if (removed != null)
            setDirty();
        return removed;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("format", FORMAT);
        ListTag list = new ListTag();
        for (March m : marches.values()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("id", m.id);
            t.putString("kind", m.kind.name());
            t.putUUID("kingdom", m.kingdom);
            t.putUUID("source", m.source);
            t.putUUID("destination", m.destination);
            t.putString("culture", m.culture);
            int[] xs = new int[m.route.size()], zs = new int[m.route.size()];
            for (int i = 0; i < m.route.size(); i++) {
                xs[i] = m.route.get(i).x();
                zs[i] = m.route.get(i).z();
            }
            t.putIntArray("xs", xs);
            t.putIntArray("zs", zs);
            t.putIntArray("stops", m.stops.stream().mapToInt(Integer::intValue).toArray());
            t.putLong("createdTick", m.createdTick);
            t.putInt("stepIndex", m.stepIndex);
            t.putDouble("progress", m.progress);
            t.putInt("nextStop", m.nextStop);
            t.putLong("pauseUntil", m.pauseUntil);
            t.putString("status", m.status.name());
            t.putLong("statusTick", m.statusTick);
            t.putLong("strength", m.strength);
            t.putInt("squad", m.squad);
            t.putLong("etaTick", m.etaTick);
            if (m.roadEndStep >= 0)
                t.putInt("roadEndStep", m.roadEndStep);
            ListTag types = new ListTag();
            for (String type : m.unitTypes)
                types.add(net.minecraft.nbt.StringTag.valueOf(type));
            t.put("unitTypes", types);
            list.add(t);
        }
        tag.put("marches", list);
        return tag;
    }

    public static MarchSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        MarchSavedData data = new MarchSavedData();
        int format = tag.getInt("format");
        if (format > FORMAT)
            throw new IllegalStateException("Unsupported march save format version: " + format);
        ListTag list = tag.getList("marches", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            try {
                int[] xs = t.getIntArray("xs"), zs = t.getIntArray("zs");
                if (xs.length != zs.length || xs.length < 2)
                    continue;
                List<Waypoint> route = new ArrayList<>(xs.length);
                for (int j = 0; j < xs.length; j++)
                    route.add(new Waypoint(xs[j], zs[j]));
                List<Integer> stops = new ArrayList<>();
                for (int s : t.getIntArray("stops"))
                    if (s >= 0 && s < route.size())
                        stops.add(s);
                List<String> types = new ArrayList<>();
                ListTag typeList = t.getList("unitTypes", Tag.TAG_STRING);
                for (int j = 0; j < typeList.size(); j++)
                    types.add(typeList.getString(j));
                March m = new March(t.getUUID("id"), Kind.valueOf(t.getString("kind")), t.getUUID("kingdom"),
                        t.getUUID("source"), t.getUUID("destination"), t.getString("culture"), route, stops,
                        t.getLong("createdTick"), t.getLong("strength"), t.getInt("squad"), types);
                m.setStepIndex(t.getInt("stepIndex"));
                m.setProgress(t.getDouble("progress"));
                m.setNextStop(t.getInt("nextStop"));
                m.setPauseUntil(t.getLong("pauseUntil"));
                m.setStatus(Status.valueOf(t.getString("status")), t.getLong("statusTick"));
                m.setEtaTick(t.getLong("etaTick"));
                if (t.contains("roadEndStep"))
                    m.setRoadEndStep(t.getInt("roadEndStep"));
                data.marches.put(m.id(), m);
            } catch (RuntimeException corrupt) {
                // A damaged march record is dropped; shipments/orders keep running on their own timers.
            }
        }
        return data;
    }
}
