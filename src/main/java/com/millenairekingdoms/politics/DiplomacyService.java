package com.millenairekingdoms.politics;

import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.kingdom.KingdomSavedData.Kingdom;
import com.millenairekingdoms.kingdom.KingdomSavedData.WarStatus;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import org.millenaire.village.Village;
import org.millenaire.village.VillageRelations;
import org.millenaire.village.VillageSavedData;
import org.slf4j.Logger;

/** Kingdom relations are Millénaire's own relations between capital villages. Once per Minecraft day each pair of active
 * kingdoms may drift by one point: rarely, never past ±50 by drift alone (Millénaire raids on its own below -90), and it
 * never declares war or makes peace. Player diplomacy at the chief screen still moves relations as Millénaire defines.
 *
 * <p>Stage 6.2 political tension, only from Millénaire's own relations and events (no constant decline):
 * <ul><li>Borders: where member villages of two kingdoms know each other (Millénaire keeps relations only between villages
 * within its backgroundRadius, and its nightly diplomacy moves them), the capitals' relation follows the average of those
 * member relations by at most {@value #BORDER_STEP} points a day.</li>
 * <li>Raid incidents: every Millénaire raid, normal or conquest, between villages of two different kingdoms lowers their
 * capitals' relation by {@value #RAID_TENSION} when it ends.</li>
 * <li>A peace treaty decided by a ruler or an NPC kingdom improves the capitals' relation once by {@value #PEACE_TREATY}.</li></ul>
 * Millénaire's own nightly diplomacy (VillageDiplomacyHelper) also keeps acting on every capital relation that exists. */
public final class DiplomacyService {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** About one day in four a pair's relation moves. */
    public static final int DRIFT_CHANCE = 4;
    public static final int DRIFT_LIMIT = VillageRelations.GOOD;
    public static final int RAID_TENSION = 10, PEACE_TREATY = 20, BORDER_STEP = 2;
    private DiplomacyService() {}

    /** Kingdoms at war only drift apart; at peace the direction is random. Returns the number of changed pairs. */
    public static int drift(ServerLevel level, KingdomSavedData data, RandomSource random) {
        List<Kingdom> kingdoms = living(data);
        int changed = 0;
        for (int i = 0; i < kingdoms.size(); i++) for (int j = i + 1; j < kingdoms.size(); j++) {
            Kingdom a = kingdoms.get(i), b = kingdoms.get(j);
            Village ca = PoliticsService.village(level, a.capital()), cb = PoliticsService.village(level, b.capital());
            if (ca == null || cb == null || random.nextInt(DRIFT_CHANCE) != 0) continue;
            boolean atWar = data.wars().stream().anyMatch(w -> w.status() == WarStatus.ACTIVE
                    && (w.attackerKingdom().equals(a.id()) && w.defenderKingdom().equals(b.id())
                    || w.attackerKingdom().equals(b.id()) && w.defenderKingdom().equals(a.id())));
            int delta = atWar || random.nextBoolean() ? -1 : 1;
            int current = ca.getRelation(cb.getId()), next = current + delta;
            if (Math.abs(next) > DRIFT_LIMIT && Math.abs(next) > Math.abs(current)) continue;
            ca.adjustRelationSymmetric(level, cb.getId(), delta, false);
            LOGGER.info("Relations between {} and {} drifted from {} to {}{}.", a.name(), b.name(), current, ca.getRelation(cb.getId()), atWar ? " (at war)" : "");
            changed++;
        }
        if (changed > 0) VillageSavedData.get(level).setDirty();
        return changed;
    }

    /** The average Millénaire relation between member villages of a and b that know each other (the capitals' own pair
     * excluded), or null when the two kingdoms share no border. */
    public static Double borderRelation(ServerLevel level, KingdomSavedData data, Kingdom a, Kingdom b) {
        return borderRelation(villages(level, data, a), villages(level, data, b), a.capital(), b.capital());
    }
    private static Double borderRelation(List<Village> as, List<Village> bs, UUID capitalA, UUID capitalB) {
        long sum = 0; int count = 0;
        for (Village va : as) for (Village vb : bs) {
            if (va.getId().uuid().equals(capitalA) && vb.getId().uuid().equals(capitalB)) continue;
            Integer relation = va.getRelations().get(vb.getId());
            if (relation != null) { sum += relation; count++; }
        }
        return count == 0 ? null : (double) sum / count;
    }

    /** Daily: each pair of kingdoms that shares a border moves its capitals' relation 1-2 points toward the border average.
     * Returns the number of changed pairs. */
    public static int border(ServerLevel level, KingdomSavedData data, RandomSource random) {
        List<Kingdom> kingdoms = living(data);
        Map<UUID, List<Village>> members = new HashMap<>();
        for (Kingdom k : kingdoms) members.put(k.id(), villages(level, data, k));
        int changed = 0;
        for (int i = 0; i < kingdoms.size(); i++) for (int j = i + 1; j < kingdoms.size(); j++) {
            Kingdom a = kingdoms.get(i), b = kingdoms.get(j);
            Village ca = PoliticsService.village(level, a.capital()), cb = PoliticsService.village(level, b.capital());
            if (ca == null || cb == null) continue;
            Double mean = borderRelation(members.get(a.id()), members.get(b.id()), a.capital(), b.capital());
            if (mean == null) continue;
            int current = ca.getRelation(cb.getId()), diff = (int) Math.round(mean) - current;
            if (Math.abs(diff) < 2) continue;
            int step = Integer.signum(diff) * Math.min(Math.abs(diff), 1 + random.nextInt(BORDER_STEP));
            ca.adjustRelationSymmetric(level, cb.getId(), step, false);
            LOGGER.debug("Border relations pull {} and {} from {} to {} (border average {}).", a.name(), b.name(), current,
                    ca.getRelation(cb.getId()), Math.round(mean));
            changed++;
        }
        if (changed > 0) VillageSavedData.get(level).setDirty();
        return changed;
    }

    /** A Millénaire raid between villages of two different living kingdoms just ended: their capitals' relation worsens. */
    public static boolean raidIncident(ServerLevel level, KingdomSavedData data, Village attacker, Village target) {
        return raidIncident(level, data, attacker, target, null);
    }

    /** War conquests retain their pre-transfer sides. Independent and normal raids use current membership;
     * a fallen kingdom still receives no new political relation, as before. No additional Millénaire raid is triggered. */
    public static boolean raidIncident(ServerLevel level, KingdomSavedData data, Village attacker, Village target,
                                       KingdomSavedData.Campaign campaign) {
        UUID ka = data.owner(attacker.getId().uuid()), kb = data.owner(target.getId().uuid());
        var war = campaign == null || campaign.warId() == null ? null : data.war(campaign.warId());
        if (war != null) { ka = war.attackerKingdom(); kb = war.defenderKingdom(); }
        if (ka == null || kb == null || ka.equals(kb)) return false;
        Kingdom a = data.kingdom(ka), b = data.kingdom(kb);
        if (a.fallen() || b.fallen()) return false;
        Village ca = PoliticsService.village(level, a.capital()), cb = PoliticsService.village(level, b.capital());
        if (ca == null || cb == null) return false;
        int before = ca.getRelation(cb.getId());
        ca.adjustRelationSymmetric(level, cb.getId(), -RAID_TENSION, false);
        VillageSavedData.get(level).setDirty();
        LOGGER.info("Raid of {} on {} strains relations between {} and {}: {} -> {}.", PoliticsService.name(attacker),
                PoliticsService.name(target), a.name(), b.name(), before, ca.getRelation(cb.getId()));
        return true;
    }

    /** A peace treaty (decided by a ruler or an NPC kingdom) improves the two capitals' relation once. */
    public static void treaty(ServerLevel level, KingdomSavedData data, KingdomSavedData.War war) {
        Kingdom a = data.kingdom(war.attackerKingdom()), b = data.kingdom(war.defenderKingdom());
        if (a == null || b == null || a.fallen() || b.fallen()) return;
        Village ca = PoliticsService.village(level, a.capital()), cb = PoliticsService.village(level, b.capital());
        if (ca == null || cb == null) return;
        ca.adjustRelationSymmetric(level, cb.getId(), PEACE_TREATY, false);
        VillageSavedData.get(level).setDirty();
    }

    private static List<Kingdom> living(KingdomSavedData data) {
        return data.kingdoms().stream().filter(k -> k.active() && !k.fallen()).sorted(Comparator.comparing(k -> k.id().toString())).toList();
    }
    private static List<Village> villages(ServerLevel level, KingdomSavedData data, Kingdom k) {
        List<Village> out = new ArrayList<>();
        for (UUID id : data.members(k.id())) { Village v = PoliticsService.village(level, id); if (v != null) out.add(v); }
        return out;
    }
}
