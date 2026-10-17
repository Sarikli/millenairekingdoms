package com.millenairekingdoms.autonomy;

import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.politics.PoliticsService;
import java.util.Comparator;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import org.millenaire.culture.ModCultures;
import org.millenaire.village.Village;
import org.millenaire.village.VillagerRecord;

/** Military readiness, read only from Millénaire's own villager records and rules, so it works for unloaded villages too.
 * Nothing here starts, simulates or decides a battle. */
public final class Military {
    private Military() {}

    /** A village already planning, running or suffering a raid (Millénaire's own raid state or raiding NPCs). */
    public static boolean busy(Village village) {
        return village.getRaidTarget() != null || village.getRaidPlanningStart() != 0
                || village.getRaidStart() != 0 || village.isUnderAttack()
                || village.getVillagerRecords().values().stream().anyMatch(r -> r.isRaidingVillage() || r.isAwayRaiding());
    }

    /** Living raiders at home, not hired or away, with positive strength: the NPCs Millénaire would send. */
    public static int availableRaiders(Village village) {
        int count = 0;
        for (VillagerRecord r : village.getVillagerRecords().values()) {
            var type = ModCultures.getVillagerType(r.getVillagerTypeId());
            if (!r.isKilled() && !r.isRaidingVillage() && !r.isAwayRaiding() && !r.isAwayHired()
                    && type != null && type.isRaider() && r.getMilitaryStrength() > 0) count++;
        }
        return count;
    }

    /** Whether Millénaire lets this kind of village send raids at all (VillageType.carriesRaid, its own natural-raid rule). */
    public static boolean carriesRaids(Village village) {
        var type = ModCultures.getVillageType(village.getVillageTypeId());
        return type != null && !type.loneBuilding() && type.carriesRaid();
    }

    /** Why source cannot raid target now, or null when it is ready. The strength rule is Millénaire's own target rule
     * (RaidManager.maxAcceptableTargetStrength): a village only attacks a target defending with less than twice its raiding strength. */
    public static String problem(Village source, Village target) {
        if (!carriesRaids(source)) return "village type sends no raids";
        if (busy(source)) return "already busy with a raid";
        if (availableRaiders(source) == 0) return "no living raider at home";
        int raiding = source.getVillageRaidingStrength(), defending = target.getVillageDefendingStrength();
        if (raiding <= 0 || defending >= 2 * raiding) return "too weak (raiding " + raiding + " against defence " + defending + ")";
        return null;
    }

    /** Sum of Millénaire raiding strength of the kingdom's villages that can send raids. */
    public static long strength(ServerLevel level, KingdomSavedData data, UUID kingdom) {
        long total = 0;
        for (UUID member : data.members(kingdom)) {
            Village v = PoliticsService.village(level, member);
            if (v != null && carriesRaids(v)) total += Math.max(0, v.getVillageRaidingStrength());
        }
        return total;
    }

    /** Sum of Millénaire defending strength of all the kingdom's villages. */
    public static long defence(ServerLevel level, KingdomSavedData data, UUID kingdom) {
        long total = 0;
        for (UUID member : data.members(kingdom)) {
            Village v = PoliticsService.village(level, member);
            if (v != null) total += Math.max(0, v.getVillageDefendingStrength());
        }
        return total;
    }

    /** The ready member village of the kingdom with the strongest raiding force (the nearest among equals), or null.
     * A barely ready village would mostly lose once the target's defenders have recovered. */
    public static Village bestSource(ServerLevel level, KingdomSavedData data, UUID kingdom, Village target) {
        return data.members(kingdom).stream().map(id -> PoliticsService.village(level, id))
                .filter(v -> v != null && !v.getId().equals(target.getId()) && problem(v, target) == null
                        && !target.getId().equals(v.getParentVillageId()) && !v.getId().equals(target.getParentVillageId()))
                .min(Comparator.comparingInt((Village v) -> -v.getVillageRaidingStrength())
                        .thenComparingDouble(v -> v.getCenter().distSqr(target.getCenter()))).orElse(null);
    }

    /** At least one member village could raid some village right now (used for peace decisions). */
    public static boolean anyReady(ServerLevel level, KingdomSavedData data, UUID kingdom) {
        for (UUID member : data.members(kingdom)) {
            Village v = PoliticsService.village(level, member);
            if (v != null && carriesRaids(v) && !busy(v) && availableRaiders(v) > 0) return true;
        }
        return false;
    }
}
