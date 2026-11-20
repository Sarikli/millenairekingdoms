package com.millenairekingdoms.virtualeconomy;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import org.millenaire.building.BuildingInstance;

/**
 * In-memory mirror of persisted build-time bonuses, read by the native builder's per-block action duration. The
 * builder still walks to and places every block itself; only the wait between blocks gets shorter.
 */
public final class VirtualConstructionBoosts {
    private static final Map<UUID, Double> RATIOS = new ConcurrentHashMap<>();
    private static final Set<VirtualEconomySavedData> SYNCED = Collections.newSetFromMap(new WeakHashMap<>());

    private VirtualConstructionBoosts() {}

    static synchronized void sync(VirtualEconomySavedData data) {
        if (SYNCED.add(data))
            data.boosts().values().forEach(b -> RATIOS.put(b.building(), b.ratio()));
    }
    static void put(UUID building, double ratio) {
        RATIOS.put(building, ratio);
    }
    static void remove(UUID building) {
        RATIOS.remove(building);
    }
    public static synchronized void clear() {
        RATIOS.clear();
        SYNCED.clear();
    }

    public static double ratio(BuildingInstance building) {
        if (building == null || building.getId() == null)
            return 0;
        return RATIOS.getOrDefault(building.getId().uuid(), 0.0);
    }

    /** Native per-step duration shortened by the stored background credit; never below one tick. */
    public static int scale(BuildingInstance building, int duration) {
        double ratio = ratio(building);
        if (ratio <= 0 || duration <= 1)
            return duration;
        return scale(duration, ratio, VirtualEconomyConfig.current().maxTimeSaved());
    }
    public static int scale(int duration, double ratio, double maxTimeSaved) {
        if (ratio <= 0 || duration <= 1 || maxTimeSaved <= 0)
            return duration;
        double factor = 1.0 - Math.clamp(ratio, 0, 1) * Math.clamp(maxTimeSaved, 0, 0.95);
        return Math.max(1, (int) Math.ceil(duration * factor));
    }
}
