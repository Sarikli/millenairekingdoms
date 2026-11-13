package com.millenairekingdoms.autonomy;

import com.millenairekingdoms.economy.EconomyService;
import com.millenairekingdoms.economy.RoyalEconomyConfig;
import com.millenairekingdoms.economy.RoyalPrices;
import com.millenairekingdoms.economy.RoyalTreasurySnapshot;
import com.millenairekingdoms.economy.TreasuryService;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.kingdom.KingdomSavedData.Kingdom;
import com.millenairekingdoms.politics.PoliticsService;
import com.millenairekingdoms.util.KingdomException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import org.millenaire.village.Village;

/**
 * Shared upfront expedition pricing and NPC source-village selection.
 * Never loads chunks solely to compute a quote.
 */
public final class ExpeditionPricingService {
    public record Quote(UUID sourceId, String sourceName, UUID targetId, String targetName, int strength,
            int targetDefense, double distance, long cost, long spendableTreasury, long reserve) {
        public boolean treasuryKnown() {
            return spendableTreasury >= 0;
        }
        public boolean playerCanAfford() {
            return treasuryKnown() && spendableTreasury >= cost;
        }
        public boolean npcCanAfford() {
            return treasuryKnown() && RoyalPrices.npcCanAfford(spendableTreasury, cost, reserve);
        }
    }

    public record NpcCandidate(
            UUID sourceId, String sourceName, int strength, double distance, long cost) {}

    public record NpcSelection(NpcCandidate chosen, String refusalCode, String refusalReason) {
        public boolean ok() {
            return chosen != null;
        }
    }

    private ExpeditionPricingService() {}

    /** Computes an upfront quote for a concrete source and target without loading chunks. */
    public static Quote quote(
            ServerLevel level, KingdomSavedData data, UUID kingdomId, Village source, Village target, boolean npc) {
        if (source == null || target == null || source.getId().equals(target.getId()))
            throw new KingdomException("millenairekingdoms.raid.invalid_villages",
                    "Both raiding and target villages must exist and differ.");
        if (!kingdomId.equals(data.owner(source.getId().uuid())))
            throw new KingdomException("millenairekingdoms.raid.source_not_member",
                    "The raiding village must belong to the kingdom.");
        int strength = source.getVillageRaidingStrength();
        if (strength <= 0)
            throw new KingdomException("millenairekingdoms.raid.no_strength",
                    "%s has no available raiding strength.", PoliticsService.name(source));
        double distance = RoyalPrices.horizontalDistance(source.getCenter(), target.getCenter());
        long cost = RoyalPrices.expedition(RoyalEconomyConfig.current(), strength, distance);
        long spendable = spendableTreasury(level, data, kingdomId);
        long reserve = npc ? RoyalPrices.npcReserve(EconomyService.dailyIncome(level, data, kingdomId)) : 0L;
        return new Quote(source.getId().uuid(), PoliticsService.name(source), target.getId().uuid(),
                PoliticsService.name(target), strength, target.getVillageDefendingStrength(), distance, cost,
                spendable, reserve);
    }

    /** Returns actual spendable treasury without loading chunks, or -1 if capital storage is unloaded/unavailable. */
    public static long spendableTreasury(ServerLevel level, KingdomSavedData data, UUID kingdomId) {
        Kingdom k = data.kingdom(kingdomId);
        if (k == null || k.fallen() || !TreasuryService.loaded(level, k))
            return -1L;
        try {
            new com.millenairekingdoms.economy.RoyalPaymentService(level).reconcileExpeditions(data);
            return TreasuryService.stored(level, k).value();
        } catch (RuntimeException unavailable) {
            return -1L;
        }
    }

    /**
     * Selects the best NPC source village for {@code target} using military sufficiency (>= 1.30x defence),
     * economic reserve (H >= C + R), 25% cost window (C <= 1.25 * Cmin), and quality Q = min(S / (1.60 * D), 1).
     */
    public static NpcSelection selectNpcSource(
            ServerLevel level, KingdomSavedData data, UUID kingdomId, Village target, long spendable, long reserve) {
        if (target == null)
            return new NpcSelection(null, "NO_TARGET", "Target village is missing.");
        int defense = target.getVillageDefendingStrength();
        if (defense < 0)
            return new NpcSelection(null, "UNKNOWN_DEFENSE", "Target defending strength is unknown.");
        List<NpcCandidate> militaryReady = new ArrayList<>();
        var settings = RoyalEconomyConfig.current();
        for (UUID memberId : data.members(kingdomId)) {
            Village v = PoliticsService.village(level, memberId);
            if (v == null || v.getId().equals(target.getId()) || Military.problem(v, target) != null
                    || target.getId().equals(v.getParentVillageId()) || v.getId().equals(target.getParentVillageId()))
                continue;
            int s = v.getVillageRaidingStrength();
            if (s <= 0)
                continue;
            if (defense > 0 && 10L * s < 13L * defense)
                continue;
            double dist = RoyalPrices.horizontalDistance(v.getCenter(), target.getCenter());
            long cost = RoyalPrices.expedition(settings, s, dist);
            militaryReady.add(new NpcCandidate(v.getId().uuid(), PoliticsService.name(v), s, dist, cost));
        }
        if (militaryReady.isEmpty())
            return new NpcSelection(null, "MILITARY_INSUFFICIENT",
                    "No ready member village meets the 1.30x strength requirement.");
        if (spendable < 0)
            return new NpcSelection(null, "TREASURY_UNLOADED",
                    "Capital treasury is unloaded or unavailable for upfront payment.");
        NpcCandidate chosen = selectFromCandidates(militaryReady, defense, spendable, reserve);
        if (chosen == null)
            return new NpcSelection(null, "TREASURY_INSUFFICIENT",
                    "Spendable treasury does not cover expedition cost plus reserve.");
        return new NpcSelection(chosen, null, null);
    }

    /** Pure deterministic source selection over militarily sufficient candidates. */
    public static NpcCandidate selectFromCandidates(
            List<NpcCandidate> militaryReady, int targetDefense, long spendable, long reserve) {
        if (targetDefense < 0 || militaryReady.isEmpty())
            return null;
        List<NpcCandidate> affordable = new ArrayList<>();
        for (NpcCandidate c : militaryReady) {
            if (c.strength() <= 0)
                continue;
            if (targetDefense > 0 && 10L * c.strength() < 13L * targetDefense)
                continue;
            if (RoyalPrices.npcCanAfford(spendable, c.cost(), reserve))
                affordable.add(c);
        }
        if (affordable.isEmpty())
            return null;
        long minCost = affordable.stream().mapToLong(NpcCandidate::cost).min().orElseThrow();
        BigInteger maxAllowedCost = BigInteger.valueOf(minCost).multiply(BigInteger.valueOf(5));
        List<NpcCandidate> window = new ArrayList<>();
        for (NpcCandidate c : affordable) {
            if (BigInteger.valueOf(c.cost()).multiply(BigInteger.valueOf(4)).compareTo(maxAllowedCost) <= 0)
                window.add(c);
        }
        Comparator<NpcCandidate> byQualityDescThenCostDistId = Comparator
                .<NpcCandidate>comparingLong(c -> qualityNumerator(c.strength(), targetDefense))
                .reversed()
                .thenComparingLong(NpcCandidate::cost)
                .thenComparingDouble(NpcCandidate::distance)
                .thenComparing(NpcCandidate::sourceId);
        return window.stream().min(byQualityDescThenCostDistId).orElse(null);
    }

    private static long qualityNumerator(int strength, int defense) {
        if (defense == 0)
            return 1L;
        return Math.min(10L * strength, 16L * defense);
    }
}
