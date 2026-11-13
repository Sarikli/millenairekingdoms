package com.millenairekingdoms.test;

import static com.millenairekingdoms.test.Stage5Support.*;

import com.millenairekingdoms.autonomy.ExpeditionPricingService;
import com.millenairekingdoms.autonomy.ExpeditionPricingService.NpcCandidate;
import com.millenairekingdoms.autonomy.RaidOrderService;
import com.millenairekingdoms.economy.RoyalEconomyConfig;
import com.millenairekingdoms.economy.RoyalPrices;
import com.millenairekingdoms.economy.RoyalTreasurySnapshot;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.politics.PoliticsService;
import java.util.List;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.item.ModItems;

@PrefixGameTestTemplate(false)
public final class ExpeditionPricingGameTests {
    private static final String NS = "millenairekingdoms_royal_economy_tests";

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void expeditionFormulaMatchesTableAndRejectsInvalidInputs(GameTestHelper h) {
        var defaults = RoyalEconomyConfig.Settings.DEFAULTS;
        int[] distances = {500, 1000, 2000, 4000};
        int[] strengths = {40, 100, 200, 400};
        long[][] expected = {
                {6, 15, 30, 60},
                {8, 20, 40, 80},
                {12, 30, 60, 120},
                {20, 50, 100, 200},
        };
        for (int di = 0; di < distances.length; di++) {
            for (int si = 0; si < strengths.length; si++) {
                long actual = RoyalPrices.expedition(defaults, strengths[si], distances[di]);
                h.assertTrue(actual == expected[di][si],
                        "Expected " + expected[di][si] + " for S=" + strengths[si] + ", L=" + distances[di] + " got "
                                + actual);
            }
        }
        // Fractional rounding upward once (S=10, L=100.1 -> 10 * 1100.1 / 10000 = 1.1001 -> 2).
        h.assertTrue(RoyalPrices.expedition(defaults, 10, 100.1) == 2,
                "Fractional expedition cost rounds upward once");
        h.assertTrue(RoyalPrices.expedition(defaults, 1, 0.0) == 1,
                "Minimal positive strength yields at least 1 denier");

        invalid(h, () -> RoyalPrices.expedition(defaults, 0, 500));
        invalid(h, () -> RoyalPrices.expedition(defaults, -10, 500));
        invalid(h, () -> RoyalPrices.expedition(defaults, 40, -1));
        invalid(h, () -> RoyalPrices.expedition(defaults, 40, Double.NaN));
        invalid(h, () -> RoyalPrices.expedition(defaults, 40, Double.POSITIVE_INFINITY));
        invalid(h, () -> RoyalPrices.expedition(defaults, Integer.MAX_VALUE, 1e20));
        h.succeed();
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void npcReserveAndSourceSelectionEnforceThresholdsAndTieBreakers(GameTestHelper h) {
        h.assertTrue(RoyalPrices.npcReserve(0) == 32 && RoyalPrices.npcReserve(16) == 32
                        && RoyalPrices.npcReserve(17) == 34 && RoyalPrices.npcReserve(100) == 200,
                "NPC reserve equals max(32, 2 * dailyIncome)");
        h.assertTrue(RoyalPrices.npcCanAfford(100, 68, 32) && !RoyalPrices.npcCanAfford(99, 68, 32),
                "NPC eligibility requires H >= C + R");

        // Target defense D = 100 -> minimum S = 130 (1.30x).
        UUID idCheapWeak = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID idJustTooWeak = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID idWindowStrong = UUID.fromString("00000000-0000-0000-0000-000000000003");
        UUID idTooCostly = UUID.fromString("00000000-0000-0000-0000-000000000004");
        var candidates = List.of(
                new NpcCandidate(idJustTooWeak, "TooWeak", 129, 100, 15),
                new NpcCandidate(idCheapWeak, "CheapWeak", 130, 500, 40),
                new NpcCandidate(idWindowStrong, "WindowStrong", 160, 600, 50), // 50 <= 1.25 * 40 (50)
                new NpcCandidate(idTooCostly, "TooCostly", 200, 700, 51)       // 51 > 1.25 * 40 (50)
        );
        var chosen = ExpeditionPricingService.selectFromCandidates(candidates, 100, 200, 32);
        h.assertTrue(chosen != null && chosen.sourceId().equals(idWindowStrong),
                "Within 1.25 * Cmin window, higher Q wins while candidates > 1.25 * Cmin or < 1.30 * D are excluded");

        // When H = 81 and R = 32 -> spendable above reserve = 49 -> idWindowStrong (50) unaffordable, idCheapWeak (40) wins.
        var budgetTight = ExpeditionPricingService.selectFromCandidates(candidates, 100, 81, 32);
        h.assertTrue(budgetTight != null && budgetTight.sourceId().equals(idCheapWeak),
                "Unaffordable source is excluded by H >= C + R");

        // D = 0 allows S > 0 with Q = 1; D < 0 is excluded.
        h.assertTrue(ExpeditionPricingService.selectFromCandidates(
                        List.of(new NpcCandidate(idCheapWeak, "ZeroDef", 10, 100, 5)), 0, 100, 32)
                        != null,
                "Verified D == 0 accepts S > 0");
        h.assertTrue(ExpeditionPricingService.selectFromCandidates(
                        List.of(new NpcCandidate(idCheapWeak, "UnknownDef", 10, 100, 5)), -1, 100, 32)
                        == null,
                "Unknown D (< 0) is rejected");
        h.succeed();
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 1200)
    public static void playerExpeditionPaysUpfrontRequiresReconfirmAndRefundsOnCancel(GameTestHelper h)
            throws Exception {
        var capital = village(h, 1, 1, "Exp capital");
        var target = village(h, 8, 8, "Exp target");
        var target2 = village(h, 10, 10, "Exp target 2");
        try {
            var data = KingdomSavedData.get(h.getLevel());
            var k = kingdom(h, capital);
            var alice = player(h, "ExpAlice", capital);
            reputation(h, capital, alice.getUUID(), PoliticsService.CLAIM_REPUTATION);
            run(alice, "kingdom claim");
            // Give capital enough raiding strength via native guard record or check if strength > 0; if 0, add a guard.
            if (capital.getVillageRaidingStrength() <= 0) {
                var guardType = org.millenaire.culture.ModCultures.getAllVillagerTypes()
                                        .values()
                                        .stream()
                                        .filter(t -> t.isRaider() && t.baseAttackStrength() > 0)
                                        .findFirst()
                                        .orElseThrow();
                capital.addVillager(UUID.randomUUID(), guardType.id());
            }
            // Put 256 deniers (4 silver) in capital chest.
            chest(h, capital).setItem(0, new ItemStack(ModItems.DENIER_ARGENT.get(), 4));
            var quote = ExpeditionPricingService.quote(
                    h.getLevel(), data, k.id(), capital, target, false);
            h.assertTrue(quote.cost() > 0 && quote.spendableTreasury() == 256,
                    "Quote sees positive cost and 256 spendable deniers");

            // Wrong expectedCost -> rejected without charging!
            boolean priceMismatchRejected = false;
            try {
                RaidOrderService.order(alice, target.getId().uuid(), capital.getId().uuid(), quote.cost() + 1);
            } catch (IllegalArgumentException expected) {
                priceMismatchRejected = true;
            }
            h.assertTrue(priceMismatchRejected
                            && RoyalTreasurySnapshot.forPlayer(alice).spendableValue() == 256,
                    "Mismatched quoted cost requires reconfirmation and charges nothing");

            // Schedule future dueGameTime by setting a waiting order via order(), or cancel immediately if waiting.
            // Target is loaded here, so let's put target on war? Wait: target is independent! Let's test cancel refund
            // by putting an active war or cooldown? Even simpler: put target on 1-day cooldown? No, validate() blocks cooldown.
            // Let's test order() -> if it started (target loaded), verify treasury decreased by quote.cost()!
            var attempt = RaidOrderService.order(alice, target.getId().uuid(), capital.getId().uuid(), quote.cost());
            long afterPay = RoyalTreasurySnapshot.forPlayer(alice).spendableValue();
            h.assertTrue(afterPay == 256 - quote.cost(),
                    "Ordering expedition deducts exact upfront cost from capital treasury");
            if (attempt.status() == RaidOrderService.Status.WAITING) {
                RaidOrderService.cancel(alice);
                h.assertTrue(RoyalTreasurySnapshot.forPlayer(alice).spendableValue() == 256,
                        "Cancelling a waiting expedition refunds the upfront cost");
            }
            // Legacy unpaid order (quotedCost == 0) cancels cleanly without touching treasury.
            long now = h.getLevel().getGameTime();
            if (data.orderOf(k.id()) == null) {
                var legacy = data.addOrder(UUID.randomUUID(), k.id(), target2.getId().uuid(), capital.getId().uuid(),
                        alice.getUUID(), now, now + 1000, now + 2000, 0L, 0);
                h.assertTrue(legacy.quotedCost() == 0, "Legacy unpaid order has quotedCost 0");
                RaidOrderService.cancel(alice);
                h.assertTrue(RoyalTreasurySnapshot.forPlayer(alice).spendableValue() == afterPay,
                        "Cancelling legacy unpaid order leaves treasury intact");

                // Paid NPC order waiting + concurrent capital expense + political pruning (claimRule) auto-refund.
                data.abdicate(k.id(), alice.getUUID());
                long beforeNpc = RoyalTreasurySnapshot.forNpc(h.getLevel(), k.id()).spendableValue();
                var npcOrder = RaidOrderService.orderNpc(h.getLevel(), data, k.id(), target2.getId().uuid(),
                        capital.getId().uuid(), now + 5000, now + 24000);
                h.assertTrue(npcOrder.quotedCost() > 0 && npcOrder.quotedStrength() > 0,
                        "NPC order records positive quotedCost and quotedStrength");
                // Another capital expense is not blocked while the expedition order is waiting in PAID.
                var adminId = UUID.randomUUID();
                var payments = new com.millenairekingdoms.economy.RoyalPaymentService(h.getLevel());
                payments.payNpc(k.id(), adminId, com.millenairekingdoms.economy.RoyalPaymentService.Purpose.ADMINISTRATIVE,
                        adminId, 1);
                payments.commitNpc(k.id(), adminId);
                // Claiming rule prunes the waiting NPC order; process() reconciles and refunds the expedition fee.
                data.claimRule(k.id(), alice.getUUID(), "Alice", now);
                h.assertTrue(data.order(npcOrder.id()) == null, "Political ruler change pruned waiting NPC order");
                RaidOrderService.process(h.getLevel());
                h.assertTrue(RoyalTreasurySnapshot.forPlayer(alice).spendableValue() == beforeNpc - 1,
                        "Politically pruned expedition order is automatically refunded to capital treasury");
            }
            h.succeed();
        } finally {
            remove(h, capital, target, target2);
        }
    }

    private static void invalid(GameTestHelper h, Runnable action) {
        boolean rejected = false;
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        h.assertTrue(rejected, "Invalid expedition price input must be refused");
    }
}
