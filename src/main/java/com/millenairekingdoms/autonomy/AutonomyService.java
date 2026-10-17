package com.millenairekingdoms.autonomy;

import com.millenairekingdoms.autonomy.AutonomyConfig.Settings;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.kingdom.KingdomSavedData.Kingdom;
import com.millenairekingdoms.kingdom.KingdomSavedData.War;
import com.millenairekingdoms.kingdom.KingdomSavedData.WarStatus;
import com.millenairekingdoms.politics.DiplomacyService;
import com.millenairekingdoms.politics.PoliticsService;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import org.millenaire.config.MillenaireServerConfig;
import org.millenaire.culture.ModCultures;
import org.millenaire.village.Village;
import org.millenaire.village.VillageSavedData;
import org.slf4j.Logger;

/** Stage 6.2: the political decisions of NPC-governed kingdoms, taken once per decision interval (3 days by default)
 * from KingdomDay, never per tick. Everything is read from persistent data (Millénaire relations and villager records,
 * kingdom data), so unloaded villages take part in politics; nothing here fights or simulates a battle. Raid orders it
 * creates start only as real Millénaire raids (RaidOrderService). A kingdom with a player ruler is never decided for:
 * no war, peace, expansion or raid on its behalf. Order of each evaluation: peace, war, joining, campaigns. */
public final class AutonomyService {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Wars are not ended by NPC decision before this many days; after IDLE_DAYS without a victory they may be. */
    public static final int PEACE_AFTER_DAYS = 12, IDLE_DAYS = 6, LONG_WAR_DAYS = 36;
    /** Base chance per evaluation that an independent village with good relations joins (or applies to) a kingdom. */
    public static final double JOIN_CHANCE = 0.10;
    private static final String TAG = "[Kingdoms] ";

    public record Report(int joined, int applied, int wars, int peaces, int peaceOffers, int orders) {
        public static final Report NONE = new Report(0, 0, 0, 0, 0, 0);
    }

    private AutonomyService() {}

    public static Report evaluate(ServerLevel level, KingdomSavedData data, long day, RandomSource random, Settings s) {
        if (!s.autonomy() || data.kingdoms().isEmpty()) return Report.NONE;
        int[] peace = peace(level, data, day, random, s);
        int wars = wars(level, data, day, random, s);
        int[] joins = s.joining() ? joins(level, data, random) : new int[2];
        int orders = s.campaigns() ? campaigns(level, data, day, random, s) : 0;
        var report = new Report(joins[0], joins[1], wars, peace[0], peace[1], orders);
        LOGGER.info("[Kingdoms] Political evaluation day {}: {} joined, {} applied, {} wars declared, {} peaces, {} peace offers, {} raid orders.",
                day, report.joined, report.applied, report.wars, report.peaces, report.peaceOffers, report.orders);
        return report;
    }

    // ---- Package E: peace and truce ----

    /** Why an NPC side would end this war now, or null. The chance is applied by the caller. */
    public record PeaceMotive(double chance, String reason) {}
    public static PeaceMotive peaceMotive(ServerLevel level, KingdomSavedData data, War war, long day, Settings s) {
        var clock = data.warClock(war.id());
        if (clock == null) return null;
        long age = day - clock.startDay(), idle = day - clock.lastProgressDay();
        if (age < PEACE_AFTER_DAYS) return null;
        double p = 0; String reason = null;
        int taken = data.conquered(war.id());
        if (taken > 0) { p = 0.35; reason = "limited success (" + taken + " villages taken)"; }
        if (idle >= IDLE_DAYS && 0.5 > p) { p = 0.5; reason = "no military progress for " + idle + " days"; }
        Integer relation = relation(level, data.kingdom(war.attackerKingdom()), data.kingdom(war.defenderKingdom()));
        if (relation != null && relation > PoliticsService.HOSTILE_RELATION && 0.6 > p) { p = 0.6; reason = "relations recovered to " + relation; }
        if (!Military.anyReady(level, data, war.attackerKingdom()) && 0.7 > p) { p = 0.7; reason = "no army ready"; }
        if (age >= LONG_WAR_DAYS && 0.8 > p) { p = 0.8; reason = "war lasted " + age + " days"; }
        return reason == null ? null : new PeaceMotive(Math.min(0.95, p * s.frequency().peaceFactor), reason);
    }

    public enum PeaceDecision { NONE, MADE, OFFERED }
    private static int[] peace(ServerLevel level, KingdomSavedData data, long day, RandomSource random, Settings s) {
        int made = 0, offered = 0;
        for (War war : sortedWars(data.wars().stream().filter(w -> w.status() == WarStatus.ACTIVE).toList())) {
            switch (decidePeace(level, data, war, day, random, s)) {
                case MADE -> made++;
                case OFFERED -> offered++;
                default -> { }
            }
        }
        return new int[]{made, offered};
    }
    /** One war: an NPC attacker may end it; against a ruled attacker an NPC defender may only offer peace. */
    public static PeaceDecision decidePeace(ServerLevel level, KingdomSavedData data, War war, long day, RandomSource random, Settings s) {
        if (war.status() != WarStatus.ACTIVE) return PeaceDecision.NONE;
        boolean npcAttacker = data.npcGoverned(war.attackerKingdom()), npcDefender = data.npcGoverned(war.defenderKingdom());
        if (!npcAttacker && (!npcDefender || data.peaceOffered(war.id()))) return PeaceDecision.NONE;
        PeaceMotive motive = peaceMotive(level, data, war, day, s);
        if (motive == null || random.nextDouble() >= motive.chance()) return PeaceDecision.NONE;
        Kingdom attacker = data.kingdom(war.attackerKingdom()), defender = data.kingdom(war.defenderKingdom());
        if (npcAttacker) {
            try { data.peace(war.id(), KingdomSavedData.NPC_PEACE_REASON, s.truceDays()); }
            catch (IllegalArgumentException raidsRunning) { return PeaceDecision.NONE; } // Retried at the next evaluation.
            DiplomacyService.treaty(level, data, war);
            LOGGER.info("[Kingdoms] {} made peace with {} ({}); truce {} days.", attacker.name(), defender.name(), motive.reason(), s.truceDays());
            PoliticsService.notifyRuler(level, defender.id(), Component.literal(TAG + attacker.name() + " made peace with your kingdom ("
                    + motive.reason() + "). Truce: " + s.truceDays() + " days.").withStyle(ChatFormatting.GREEN));
            return PeaceDecision.MADE;
        }
        // The attacker has a ruler: an NPC defender can only offer peace; the ruler decides.
        data.offerPeace(war.id());
        LOGGER.info("[Kingdoms] {} offers peace to {} ({}).", defender.name(), attacker.name(), motive.reason());
        PoliticsService.notifyRuler(level, attacker.id(), Component.literal(TAG + defender.name() + " offers peace (" + motive.reason() + "). ")
                .withStyle(ChatFormatting.GOLD).append(PoliticsService.button("[Accept peace]", "/kingdom war peace", "End the war with " + defender.name())));
        return PeaceDecision.OFFERED;
    }

    // ---- Package B: war decisions ----

    /** The kingdom an NPC kingdom would declare war on now (most hostile first, then nearest), or null. */
    public static Kingdom warTarget(ServerLevel level, KingdomSavedData data, Kingdom k, long day, Settings s) {
        if (!data.npcGoverned(k.id()) || !k.active() || data.activeWar(k.id()) != null || data.activeConquests(k.id()) > 0) return null;
        if (!Military.anyReady(level, data, k.id())) return null;
        Village capital = PoliticsService.village(level, k.capital());
        if (capital == null) return null;
        Kingdom best = null; int bestRelation = 0; double bestDistance = 0;
        for (Kingdom t : sorted(data.kingdoms())) {
            if (t.id().equals(k.id()) || t.fallen() || !t.active() || data.activeWar(t.id()) != null || data.truceUntil(k.id(), t.id()) > day) continue;
            Village other = PoliticsService.village(level, t.capital());
            if (other == null) continue;
            int relation = capital.getRelation(other.getId());
            if (relation > s.warThreshold() || !neighbours(level, data, k, t) || !targetable(level, data, k, t)) continue;
            double distance = capital.getCenter().distSqr(other.getCenter());
            if (best == null || relation < bestRelation || relation == bestRelation && distance < bestDistance) {
                best = t; bestRelation = relation; bestDistance = distance;
            }
        }
        return best;
    }
    /** Geographic reach: some village of k lies within Millénaire's relation radius of some village of t, the distance
     * within which Millénaire itself keeps relations and plans raids. Distant kingdoms do not go to war. */
    public static boolean neighbours(ServerLevel level, KingdomSavedData data, Kingdom k, Kingdom t) {
        long radius = joinRadius();
        for (UUID a : data.members(k.id())) {
            Village va = PoliticsService.village(level, a);
            if (va == null) continue;
            for (UUID b : data.members(t.id())) {
                Village vb = PoliticsService.village(level, b);
                if (vb != null && va.getCenter().distSqr(vb.getCenter()) <= radius * radius) return true;
            }
        }
        return false;
    }
    /** Some village of t could be raided by a ready village of k now: its members, or its capital if it has none. */
    private static boolean targetable(ServerLevel level, KingdomSavedData data, Kingdom k, Kingdom t) {
        var members = new ArrayList<>(data.members(t.id()));
        if (members.size() > 1) members.remove(t.capital());
        for (UUID id : members) {
            Village v = PoliticsService.village(level, id);
            if (v != null && Military.bestSource(level, data, k.id(), v) != null) return true;
        }
        return false;
    }
    /** Chance that k declares war on t at one evaluation: the profile's base, more for the stronger side, never certain. */
    public static double declareChance(ServerLevel level, KingdomSavedData data, Kingdom k, Kingdom t, Settings s) {
        double ratio = (double) Military.strength(level, data, k.id()) / Math.max(1, Military.defence(level, data, t.id()));
        return s.frequency().declare * Math.max(0.5, Math.min(1.5, 0.5 + 0.5 * ratio));
    }

    private static int wars(ServerLevel level, KingdomSavedData data, long day, RandomSource random, Settings s) {
        int declared = 0;
        for (Kingdom k : sorted(data.kingdoms())) if (!k.fallen() && decideWar(level, data, k, day, random, s) != null) declared++;
        return declared;
    }
    /** One NPC kingdom's war decision; returns the war it declared, or null. */
    public static War decideWar(ServerLevel level, KingdomSavedData data, Kingdom k, long day, RandomSource random, Settings s) {
        Kingdom t = warTarget(level, data, k, day, s);
        if (t == null || random.nextDouble() >= declareChance(level, data, k, t, s)) return null;
        War war;
        try { war = data.declareWar(k.id(), t.id(), level.getGameTime()); }
        catch (IllegalArgumentException refused) { return null; }
        LOGGER.info("[Kingdoms] {} declared war on {} (relation {}).", k.name(), t.name(), relation(level, k, t));
        PoliticsService.notifyRuler(level, t.id(), Component.literal(TAG + k.name() + " declared war on your kingdom.").withStyle(ChatFormatting.RED));
        return war;
    }

    // ---- Package A: diplomatic joining ----

    /** Millénaire's own relation radius: villages farther apart have no relation and never join each other's kingdom. */
    public static int joinRadius() {
        try { return Math.max(1, MillenaireServerConfig.SERVER.backgroundRadius.get()); }
        catch (RuntimeException notLoaded) { return 2000; }
    }

    /** The kingdom an independent village would join now (best relation at least 50 with its capital, then nearest), or null.
     * Player villages (any Millénaire owner), hamlets and villages in a raid, order or application never join on their own. */
    public static Kingdom joinTarget(ServerLevel level, KingdomSavedData data, Village v) {
        UUID id = v.getId().uuid();
        var type = ModCultures.getVillageType(v.getVillageTypeId());
        if (type == null || type.loneBuilding() || type.playerControlled() || v.getTownhall() == null || v.getParentVillageId() != null
                || v.getOwnerUUID() != null || data.owner(id) != null || data.involves(id) || data.joinRequest(id) != null
                || data.orderAgainst(id) != null || Military.busy(v)) return null;
        long radius = joinRadius();
        Kingdom best = null; int bestRelation = 0; double bestDistance = 0;
        for (Kingdom k : sorted(data.kingdoms())) {
            if (k.fallen() || !k.active()) continue;
            Village capital = PoliticsService.village(level, k.capital());
            if (capital == null) continue;
            Integer relation = capital.getRelations().get(v.getId());
            double distance = capital.getCenter().distSqr(v.getCenter());
            if (relation == null || relation < PoliticsService.JOIN_RELATION || distance > radius * radius) continue;
            if (best == null || relation > bestRelation || relation == bestRelation && distance < bestDistance) {
                best = k; bestRelation = relation; bestDistance = distance;
            }
        }
        return best;
    }
    /** Measured, never certain: closer villages and smaller kingdoms are likelier. */
    public static double joinChance(ServerLevel level, KingdomSavedData data, Kingdom k, Village v) {
        Village capital = PoliticsService.village(level, k.capital());
        double proximity = 1 - Math.min(1, Math.sqrt(capital.getCenter().distSqr(v.getCenter())) / joinRadius());
        return JOIN_CHANCE * (0.25 + 0.75 * proximity) / (1 + data.members(k.id()).size() / 5.0);
    }

    private static int[] joins(ServerLevel level, KingdomSavedData data, RandomSource random) {
        int joined = 0, applied = 0;
        Map<UUID, Integer> thisRound = new HashMap<>();
        List<Village> villages = new ArrayList<>(VillageSavedData.get(level).getVillageManager().getAllVillages());
        villages.sort(Comparator.comparing(v -> v.getId().uuid().toString()));
        for (Village v : villages) {
            Kingdom k = joinTarget(level, data, v);
            if (k == null || thisRound.getOrDefault(k.id(), 0) > 0) continue;
            switch (decideJoin(level, data, v, random)) {
                case JOINED -> { joined++; thisRound.merge(k.id(), 1, Integer::sum); }
                case APPLIED -> { applied++; thisRound.merge(k.id(), 1, Integer::sum); }
                default -> { }
            }
        }
        return new int[]{joined, applied};
    }
    public enum JoinDecision { NONE, JOINED, APPLIED }
    /** One independent village's choice: join an NPC-governed kingdom, or apply to a ruled one (its ruler decides). */
    public static JoinDecision decideJoin(ServerLevel level, KingdomSavedData data, Village v, RandomSource random) {
        Kingdom k = joinTarget(level, data, v);
        if (k == null || random.nextDouble() >= joinChance(level, data, k, v)) return JoinDecision.NONE;
        UUID id = v.getId().uuid();
        try {
            if (data.npcGoverned(k.id())) {
                data.attach(k.id(), id);
                LOGGER.info("[Kingdoms] {} joined {} by its own choice (relation {}).", PoliticsService.name(v), k.name(),
                        PoliticsService.village(level, k.capital()).getRelation(v.getId()));
                return JoinDecision.JOINED;
            }
            data.requestJoin(id, k.id(), null, "", level.getGameTime());
            LOGGER.info("[Kingdoms] {} applies to join {}; its ruler decides.", PoliticsService.name(v), k.name());
            PoliticsService.notifyRuler(level, k.id(), Component.literal(TAG + PoliticsService.name(v) + " asks to join " + k.name() + ". ")
                    .withStyle(ChatFormatting.GOLD)
                    .append(PoliticsService.button("[Accept]", "/kingdom accept " + id, "Attach " + PoliticsService.name(v))).append(" ")
                    .append(PoliticsService.button("[Refuse]", "/kingdom refuse " + id, "Refuse the application")));
            return JoinDecision.APPLIED;
        } catch (IllegalArgumentException refused) { return JoinDecision.NONE; /* Changed meanwhile; considered again next time. */ }
    }

    // ---- Package C: NPC conquest raid orders ----

    private record Plan(Village target, Village source, double distance) {}

    /** The nearest (target, ready source) pair among the candidates, skipping targets on cooldown or already engaged. */
    private static Plan plan(ServerLevel level, KingdomSavedData data, UUID kingdom, List<UUID> targets, long day, Settings s) {
        Plan best = null;
        for (UUID id : targets) {
            long last = data.lastRaidDay(id);
            if (last >= 0 && day - last < s.raidCooldownDays() || data.involves(id) || data.orderAgainst(id) != null) continue;
            Village target = PoliticsService.village(level, id);
            if (target == null || Military.busy(target)) continue;
            Village source = Military.bestSource(level, data, kingdom, target);
            if (source == null) continue;
            double distance = source.getCenter().distSqr(target.getCenter());
            if (best == null || distance < best.distance) best = new Plan(target, source, distance);
        }
        return best;
    }

    /** War targets of an NPC attacker: the capital once the 60% rule opens it, otherwise the defender's other villages. */
    public static List<UUID> warTargets(KingdomSavedData data, War war) {
        UUID capital = data.kingdom(war.defenderKingdom()).capital();
        if (data.capitalOpen(war)) return List.of(capital);
        return data.members(war.defenderKingdom()).stream().filter(v -> !v.equals(capital)).sorted().toList();
    }
    /** Independent villages hostile to the kingdom's capital (relation at or below the war threshold) within Millénaire's
     * relation radius; player villages are never attacked by NPC decision. */
    public static List<UUID> hostileIndependents(ServerLevel level, KingdomSavedData data, Kingdom k, Settings s) {
        Village capital = PoliticsService.village(level, k.capital());
        if (capital == null) return List.of();
        long radius = joinRadius();
        List<UUID> out = new ArrayList<>();
        for (Village v : VillageSavedData.get(level).getVillageManager().getAllVillages()) {
            UUID id = v.getId().uuid();
            var type = ModCultures.getVillageType(v.getVillageTypeId());
            Integer relation = capital.getRelations().get(v.getId());
            if (type == null || type.loneBuilding() || type.playerControlled() || v.getTownhall() == null || v.getOwnerUUID() != null
                    || data.owner(id) != null || relation == null || relation > s.warThreshold()
                    || capital.getCenter().distSqr(v.getCenter()) > radius * radius) continue;
            out.add(id);
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    private static int campaigns(ServerLevel level, KingdomSavedData data, long day, RandomSource random, Settings s) {
        int orders = 0;
        for (Kingdom k : sorted(data.kingdoms())) if (decideCampaign(level, data, k, day, random, s) != null) orders++;
        return orders;
    }
    /** One NPC kingdom's conquest decision: at most one order per kingdom, for an enemy village in its own war or, at peace,
     * a hostile independent village; the order starts later, only as a real Millénaire raid. Returns the order or null. */
    public static KingdomSavedData.RaidOrder decideCampaign(ServerLevel level, KingdomSavedData data, Kingdom k, long day, RandomSource random, Settings s) {
        long now = level.getGameTime();
        if (k.fallen() || !k.active() || !data.npcGoverned(k.id()) || data.orderOf(k.id()) != null || data.activeConquests(k.id()) > 0) return null;
        War war = data.activeWar(k.id());
        boolean attacking = war != null && war.attackerKingdom().equals(k.id());
        if (war != null && !attacking) return null; // A defender does not counter-conquer (stage-4 directional wars).
        double chance = attacking ? s.frequency().campaign : s.frequency().independent;
        if (random.nextDouble() >= chance) return null;
        Plan plan = plan(level, data, k.id(), attacking ? warTargets(data, war) : hostileIndependents(level, data, k, s), day, s);
        if (plan == null) return null;
        UUID target = plan.target.getId().uuid();
        KingdomSavedData.RaidOrder order;
        try {
            order = data.addOrder(k.id(), target, plan.source.getId().uuid(), null, now, now + RaidOrderService.NPC_PREPARATION,
                    now + RaidOrderService.ORDER_LIFETIME);
        } catch (IllegalArgumentException refused) { return null; }
        LOGGER.info("[Kingdoms] {} prepares a conquest raid from {} on {}{}.", k.name(), PoliticsService.name(plan.source),
                PoliticsService.name(plan.target), attacking ? " (war on " + data.kingdom(war.defenderKingdom()).name() + ")" : " (independent village)");
        if (attacking) PoliticsService.notifyRuler(level, war.defenderKingdom(), Component.literal(TAG + k.name() + " prepares a conquest raid on "
                + PoliticsService.name(plan.target) + ".").withStyle(ChatFormatting.RED));
        return order;
    }

    // ---- helpers ----

    public static Integer relation(ServerLevel level, Kingdom a, Kingdom b) {
        if (a == null || b == null) return null;
        Village ca = PoliticsService.village(level, a.capital()), cb = PoliticsService.village(level, b.capital());
        return ca == null || cb == null ? null : ca.getRelation(cb.getId());
    }
    private static List<Kingdom> sorted(java.util.Collection<Kingdom> kingdoms) {
        return kingdoms.stream().sorted(Comparator.comparing(k -> k.id().toString())).toList();
    }
    private static List<War> sortedWars(List<War> wars) {
        return wars.stream().sorted(Comparator.comparing(w -> w.id().toString())).toList();
    }
}
