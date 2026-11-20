package com.millenairekingdoms.virtualeconomy;

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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Independently versioned virtual economy state ({@code data/millenairekingdoms_virtual_economy.dat}). It never mirrors
 * Millénaire's own {@code millenaire_villages.dat}: it only holds what was produced while a village was unloaded and not
 * yet drained into its physical Town Hall, the materials locked for a background project, and the build-time bonus of
 * projects already handed to the native builders. Deleting this file loses only that pending surplus, never native or
 * kingdom state.
 */
public final class VirtualEconomySavedData extends SavedData {
    public static final String NAME = "millenairekingdoms_virtual_economy";
    public static final int FORMAT = 1;

    public enum CreditStage {
        /** Target chosen; waiting until the virtual chest holds every required resource. */
        RESERVING,
        /** Materials deducted from the virtual chest and locked; progress advances 0 -> 1 while unloaded. */
        PAID,
        /** Materials moved into the Town Hall and the native pending project set; waiting for its ConstructionTask. */
        HANDED_OFF
    }

    /** Background construction buffer for one queued native project. Places nothing in the world. */
    public static final class Credit {
        private final ResourceLocation planSetId;
        private final String variant;
        private final int targetLevel;
        private final UUID upgradeBuildingId;
        private CreditStage stage = CreditStage.RESERVING;
        private final Map<ResourceLocation, Integer> reserved = new LinkedHashMap<>();
        private double progress;
        private long stageTick;
        private final List<UUID> priorBuildings = new ArrayList<>();

        public Credit(ResourceLocation planSetId, String variant, int targetLevel, UUID upgradeBuildingId, long tick) {
            this.planSetId = Objects.requireNonNull(planSetId);
            this.variant = variant == null ? "" : variant;
            this.targetLevel = targetLevel;
            this.upgradeBuildingId = upgradeBuildingId;
            this.stageTick = tick;
        }
        public ResourceLocation planSetId() {
            return planSetId;
        }
        public String variant() {
            return variant;
        }
        public int targetLevel() {
            return targetLevel;
        }
        /** Existing building being upgraded, or null for a brand-new building whose plot Millénaire picks on load. */
        public UUID upgradeBuildingId() {
            return upgradeBuildingId;
        }
        public boolean upgrade() {
            return upgradeBuildingId != null;
        }
        public CreditStage stage() {
            return stage;
        }
        public boolean materialsPaid() {
            return stage != CreditStage.RESERVING;
        }
        public Map<ResourceLocation, Integer> reserved() {
            return java.util.Collections.unmodifiableMap(reserved);
        }
        public int reservedTotal() {
            return reserved.values().stream().mapToInt(Integer::intValue).sum();
        }
        public double progress() {
            return progress;
        }
        public long stageTick() {
            return stageTick;
        }
        public List<UUID> priorBuildings() {
            return java.util.Collections.unmodifiableList(priorBuildings);
        }
        void pay(Map<ResourceLocation, Integer> items, long tick) {
            reserved.clear();
            items.forEach((k, v) -> {
                if (v > 0)
                    reserved.merge(k, v, Integer::sum);
            });
            stage = CreditStage.PAID;
            stageTick = tick;
        }
        void setProgress(double value) {
            progress = Math.clamp(value, 0.0, 1.0);
        }
        void handOff(List<UUID> prior, long tick) {
            reserved.clear();
            priorBuildings.clear();
            priorBuildings.addAll(prior);
            stage = CreditStage.HANDED_OFF;
            stageTick = tick;
        }
    }

    /** Per-village buffer. The virtual chest only ever holds net surplus produced while unloaded. */
    public static final class VillageState {
        private final UUID village;
        private long lastSimulatedDay = -1;
        private final Map<ResourceLocation, Integer> virtualChest = new LinkedHashMap<>();
        private Credit credit;

        VillageState(UUID village) {
            this.village = Objects.requireNonNull(village);
        }
        public UUID village() {
            return village;
        }
        public long lastSimulatedDay() {
            return lastSimulatedDay;
        }
        public Map<ResourceLocation, Integer> virtualChest() {
            return java.util.Collections.unmodifiableMap(virtualChest);
        }
        public int count(ResourceLocation item) {
            return virtualChest.getOrDefault(item, 0);
        }
        public int total() {
            return virtualChest.values().stream().mapToInt(Integer::intValue).sum();
        }
        public Credit credit() {
            return credit;
        }
        void setLastSimulatedDay(long day) {
            lastSimulatedDay = day;
        }
        void add(ResourceLocation item, int amount) {
            if (amount > 0)
                virtualChest.merge(item, amount, Integer::sum);
        }
        /** Removes up to amount; returns how many were actually removed (never negative stock). */
        int remove(ResourceLocation item, int amount) {
            int have = virtualChest.getOrDefault(item, 0);
            int taken = Math.min(have, Math.max(0, amount));
            if (have - taken <= 0)
                virtualChest.remove(item);
            else
                virtualChest.put(item, have - taken);
            return taken;
        }
        void setCredit(Credit value) {
            credit = value;
        }
        boolean empty() {
            return virtualChest.isEmpty() && credit == null;
        }
    }

    /** Build-time bonus of a project handed to a live native ConstructionTask. */
    public record Boost(UUID building, UUID village, double ratio, long since) {}

    /** Ids of royal cargo operations already applied to a virtual buffer (exactly-once ledger, newest kept). */
    private static final int MAX_CARGO_LEDGER = 8192;
    private final java.util.LinkedHashSet<UUID> appliedCargo = new java.util.LinkedHashSet<>();

    private final Map<UUID, VillageState> villages = new LinkedHashMap<>();
    private final Map<UUID, Boost> boosts = new LinkedHashMap<>();

    public VirtualEconomySavedData() {}

    public static VirtualEconomySavedData get(ServerLevel level) {
        var data = level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(VirtualEconomySavedData::new, VirtualEconomySavedData::load, null), NAME);
        VirtualConstructionBoosts.sync(data);
        return data;
    }

    public VillageState state(UUID village) {
        return villages.get(village);
    }
    VillageState stateOrCreate(UUID village) {
        return villages.computeIfAbsent(village, VillageState::new);
    }
    public List<VillageState> all() {
        return List.copyOf(villages.values());
    }
    void forget(UUID village) {
        if (villages.remove(village) != null)
            setDirty();
    }
    public boolean cargoApplied(UUID operation) {
        return appliedCargo.contains(operation);
    }
    void recordCargo(UUID operation) {
        appliedCargo.add(operation);
        while (appliedCargo.size() > MAX_CARGO_LEDGER)
            appliedCargo.remove(appliedCargo.iterator().next());
        setDirty();
    }
    public Map<UUID, Boost> boosts() {
        return java.util.Collections.unmodifiableMap(boosts);
    }
    public Boost boost(UUID building) {
        return boosts.get(building);
    }
    void putBoost(Boost boost) {
        boosts.put(boost.building(), boost);
        VirtualConstructionBoosts.put(boost.building(), boost.ratio());
        setDirty();
    }
    void removeBoost(UUID building) {
        if (boosts.remove(building) != null) {
            VirtualConstructionBoosts.remove(building);
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("format", FORMAT);
        ListTag list = new ListTag();
        for (VillageState s : villages.values()) {
            if (s.empty() && s.lastSimulatedDay < 0)
                continue;
            CompoundTag t = new CompoundTag();
            t.putUUID("village", s.village);
            t.putLong("lastSimulatedDay", s.lastSimulatedDay);
            t.put("virtualChest", items(s.virtualChest));
            if (s.credit != null) {
                Credit c = s.credit;
                CompoundTag ct = new CompoundTag();
                ct.putString("planSetId", c.planSetId.toString());
                ct.putString("variant", c.variant);
                ct.putInt("targetLevel", c.targetLevel);
                if (c.upgradeBuildingId != null)
                    ct.putUUID("upgradeBuildingId", c.upgradeBuildingId);
                ct.putString("stage", c.stage.name());
                ct.putBoolean("materialsPaid", c.materialsPaid());
                ct.put("reservedMaterials", items(c.reserved));
                ct.putDouble("progressRatio", c.progress);
                ct.putLong("stageTick", c.stageTick);
                ListTag prior = new ListTag();
                for (UUID id : c.priorBuildings) {
                    CompoundTag p = new CompoundTag();
                    p.putUUID("id", id);
                    prior.add(p);
                }
                ct.put("priorBuildings", prior);
                t.put("pendingUpgradeCredit", ct);
            }
            list.add(t);
        }
        tag.put("villages", list);
        ListTag boostList = new ListTag();
        for (Boost b : boosts.values()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("building", b.building);
            t.putUUID("village", b.village);
            t.putDouble("ratio", b.ratio);
            t.putLong("since", b.since);
            boostList.add(t);
        }
        tag.put("boosts", boostList);
        ListTag cargo = new ListTag();
        for (UUID id : appliedCargo) {
            CompoundTag t = new CompoundTag();
            t.putUUID("id", id);
            cargo.add(t);
        }
        tag.put("appliedCargo", cargo);
        return tag;
    }

    private static ListTag items(Map<ResourceLocation, Integer> map) {
        ListTag list = new ListTag();
        map.forEach((item, count) -> {
            if (count > 0) {
                CompoundTag t = new CompoundTag();
                t.putString("item", item.toString());
                t.putInt("count", count);
                list.add(t);
            }
        });
        return list;
    }
    private static void readItems(ListTag list, Map<ResourceLocation, Integer> into) {
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            var id = ResourceLocation.tryParse(t.getString("item"));
            int count = t.getInt("count");
            if (id != null && count > 0)
                into.merge(id, count, Integer::sum);
        }
    }

    public static VirtualEconomySavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        VirtualEconomySavedData data = new VirtualEconomySavedData();
        int format = tag.getInt("format");
        if (format > FORMAT)
            throw new IllegalStateException("Unsupported virtual economy save format version: " + format);
        ListTag list = tag.getList("villages", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            try {
                VillageState s = new VillageState(t.getUUID("village"));
                s.lastSimulatedDay = t.getLong("lastSimulatedDay");
                readItems(t.getList("virtualChest", Tag.TAG_COMPOUND), s.virtualChest);
                if (t.contains("pendingUpgradeCredit", Tag.TAG_COMPOUND)) {
                    CompoundTag ct = t.getCompound("pendingUpgradeCredit");
                    var planSet = ResourceLocation.tryParse(ct.getString("planSetId"));
                    if (planSet != null) {
                        Credit c = new Credit(planSet, ct.getString("variant"), ct.getInt("targetLevel"),
                                ct.hasUUID("upgradeBuildingId") ? ct.getUUID("upgradeBuildingId") : null,
                                ct.getLong("stageTick"));
                        c.stage = CreditStage.valueOf(ct.getString("stage"));
                        readItems(ct.getList("reservedMaterials", Tag.TAG_COMPOUND), c.reserved);
                        c.setProgress(ct.getDouble("progressRatio"));
                        ListTag prior = ct.getList("priorBuildings", Tag.TAG_COMPOUND);
                        for (int j = 0; j < prior.size(); j++)
                            c.priorBuildings.add(prior.getCompound(j).getUUID("id"));
                        s.credit = c;
                    }
                }
                data.villages.put(s.village, s);
            } catch (RuntimeException corrupt) {
                // A damaged record is dropped: it only held unloaded surplus; native and kingdom state are untouched.
            }
        }
        ListTag boostList = tag.getList("boosts", Tag.TAG_COMPOUND);
        for (int i = 0; i < boostList.size(); i++) {
            CompoundTag t = boostList.getCompound(i);
            try {
                var b = new Boost(t.getUUID("building"), t.getUUID("village"), Math.clamp(t.getDouble("ratio"), 0, 1),
                        t.getLong("since"));
                data.boosts.put(b.building(), b);
            } catch (RuntimeException corrupt) {
                // Losing a bonus only means native builders work at their normal pace.
            }
        }
        ListTag cargo = tag.getList("appliedCargo", Tag.TAG_COMPOUND);
        for (int i = 0; i < cargo.size(); i++)
            if (cargo.getCompound(i).hasUUID("id"))
                data.appliedCargo.add(cargo.getCompound(i).getUUID("id"));
        return data;
    }
}
