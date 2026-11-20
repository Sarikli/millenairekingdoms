package com.millenairekingdoms.construction;

import java.util.List;
import net.minecraft.server.level.ServerLevel;
import org.millenaire.building.BuildingInstance;
import org.millenaire.building.BuildingPlanSet;
import org.millenaire.culture.VillageType;
import org.millenaire.village.Village;

/**
 * Read-only view of the audited native growth candidates for the virtual economy. Candidate evaluation is pure village
 * data (tiers, layout, tags, cooldowns): it never touches blocks or chunks.
 */
public final class VirtualGrowthAccess {
    public record Candidate(BuildingPlanSet set, BuildingPlanSet.LevelDef level, String variant, boolean upgrade,
            BuildingInstance building) {}

    private VirtualGrowthAccess() {}

    public static List<Candidate> candidates(ServerLevel level, Village village, VillageType type) {
        return NormalGrowthBridge.candidates(level, village, type)
                .stream()
                .map(c -> new Candidate(c.set(), c.level(), c.variant(), c.upgrade(), c.building()))
                .toList();
    }

    /** Native ordinary construction slot (pending project or a non-wall building under construction). */
    public static boolean nativeSlotBusy(Village village) {
        return RoyalConstructionCatalog.naturalWorkPending(village);
    }
}
