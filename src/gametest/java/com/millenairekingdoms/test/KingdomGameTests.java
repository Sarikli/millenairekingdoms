package com.millenairekingdoms.test;

import com.millenairekingdoms.conquest.ConquestSavedData;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Registry transactions tested inside a real NeoForge server, independently of NPC combat. */
@PrefixGameTestTemplate(false)
public final class KingdomGameTests {
    private static final String NS = "millenairekingdoms_tests";

    @GameTest(templateNamespace = NS, template = "empty")
    public static void uniqueMembershipAndProtectedCapitals(GameTestHelper h) {
        var d = new KingdomSavedData();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), target = UUID.randomUUID();
        var ka = d.create("First kingdom", a); var kb = d.create("Second kingdom", b);
        d.attach(kb.id(), target);
        CompoundTag before = save(d, h);
        rejected(h, () -> d.attach(ka.id(), target));
        rejected(h, () -> d.attach(ka.id(), b));
        rejected(h, () -> d.create("Duplicate capital", a));
        rejected(h, () -> d.create("FIRST KINGDOM", UUID.randomUUID()));
        rejected(h, () -> d.begin(a, b, 1, null));
        h.assertTrue(before.equals(save(d, h)), "Rejected commands must not partially mutate registry");
        d.attach(kb.id(), target); // Idempotent same-owner attach.
        h.assertTrue(d.members(kb.id()).size() == 2 && d.isCapital(b), "Canonical membership includes capital exactly once");
        var loaded = KingdomSavedData.load(save(d, h), h.getLevel().registryAccess());
        h.assertTrue(loaded.members(kb.id()).equals(d.members(kb.id())), "Members reconstructed from one canonical mapping");
        h.succeed();
    }

    @GameTest(templateNamespace = NS, template = "empty")
    public static void separateKingdomCampaignsAndMembershipRevisions(GameTestHelper h) {
        var d = new KingdomSavedData();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), target = UUID.randomUUID();
        var ka = d.create("Campaign A", a); var kb = d.create("Campaign B", b);
        var ac = d.campaign(a, target);
        h.assertTrue(ac.warId() == null && KingdomSavedData.requiredWins(ac) == 1, "Independent target needs no war and one win");
        win(d, a, target);
        h.assertTrue(d.owner(target).equals(ka.id()) && d.wins(ac) == 1 && d.lastResult(target).transferred(), "One A victory conquers the independent target");
        rejected(h, () -> d.campaign(b, target)); // Now A's village: B needs a war.
        var war = d.declareWar(kb.id(), ka.id(), 1);
        var bc = d.campaign(b, target);
        h.assertTrue(war.id().equals(bc.warId()) && KingdomSavedData.requiredWins(bc) == 2, "Kingdom target is bound to the active war");
        win(d, b, target);
        h.assertTrue(d.owner(target).equals(ka.id()) && d.wins(bc) == 1, "First war victory keeps allegiance");
        d.peace(war.id());
        rejected(h, () -> d.campaign(b, target));
        var next = d.declareWar(kb.id(), ka.id(), 2); var newB = d.campaign(b, target);
        h.assertTrue(!newB.equals(bc) && d.wins(newB) == 0 && d.wins(bc) == 1, "Old war progress stays in its own war");
        win(d, b, target);
        h.assertTrue(d.owner(target).equals(ka.id()) && d.wins(newB) == 1, "Old war win must not accelerate reconquest");
        win(d, b, target);
        h.assertTrue(d.owner(target).equals(kb.id()) && d.wins(newB) == 2 && next.id().equals(d.lastResult(target).campaign().warId()),
                "Two wins of the new war transfer to B");
        h.succeed();
    }

    @GameTest(templateNamespace = NS, template = "empty")
    public static void activeStateAndInFlightAdministrationAreGuarded(GameTestHelper h) {
        var d = new KingdomSavedData();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), target = UUID.randomUUID();
        var ka = d.create("Active A", a); var kb = d.create("Active B", b); d.attach(kb.id(), target);
        d.setActive(ka.id(), false); rejected(h, () -> d.begin(a, target, 1, null));
        rejected(h, () -> d.attach(ka.id(), UUID.randomUUID()));
        d.setActive(ka.id(), true); d.setActive(kb.id(), false);
        rejected(h, () -> d.begin(a, target, 1, null));
        rejected(h, () -> d.begin(a, b, 1, null)); // An inactive capital remains protected.
        d.setActive(kb.id(), true); rejected(h, () -> d.begin(a, target, 1, null)); // No war yet.
        var war = d.declareWar(ka.id(), kb.id(), 1); var raid = d.begin(a, target, 1, null);
        rejected(h, () -> d.setActive(ka.id(), false)); rejected(h, () -> d.setActive(kb.id(), false));
        rejected(h, () -> d.attach(ka.id(), target)); rejected(h, () -> d.create("During battle", target));
        d.finish(raid, KingdomSavedData.Outcome.LOST);
        h.assertTrue(d.wins(raid.campaign()) == 0 && d.owner(target).equals(kb.id()), "Failed battle leaves politics unchanged");
        d.peace(war.id()); d.setActive(kb.id(), false);
        var loaded = KingdomSavedData.load(save(d, h), h.getLevel().registryAccess());
        h.assertTrue(!loaded.kingdom(kb.id()).active(), "Inactive state persists");
        h.succeed();
    }

    @GameTest(templateNamespace = NS, template = "empty")
    public static void completedTransferCannotBeReplayedAfterReload(GameTestHelper h) {
        var d = new KingdomSavedData(); UUID a = UUID.randomUUID(), target = UUID.randomUUID();
        var k = d.create("Replay A", a);
        var second = d.begin(a, target, 2, null); d.recordVictory(second); // Independent target: 1/1 transfer.
        var loaded = KingdomSavedData.load(save(d, h), h.getLevel().registryAccess());
        h.assertTrue(!loaded.recordVictory(second), "Pending result receipt survives serialization");
        loaded.finish(second, KingdomSavedData.Outcome.ABORTED);
        loaded = KingdomSavedData.load(save(loaded, h), h.getLevel().registryAccess());
        CompoundTag before = save(loaded, h);
        h.assertTrue(!loaded.recordVictory(second), "Retired raid cannot replay its political transfer");
        loaded.finish(second, KingdomSavedData.Outcome.LOST);
        h.assertTrue(before.equals(save(loaded, h)) && loaded.owner(target).equals(k.id())
                && loaded.lastResult(target).transferred(), "Receipt and owner remain consistent after repeated callbacks");
        h.succeed();
    }

    @GameTest(templateNamespace = NS, template = "empty")
    public static void failedTransferPublishesNoPartialState(GameTestHelper h) {
        var d = new KingdomSavedData(); UUID a = UUID.randomUUID(), target = UUID.randomUUID();
        var ka = d.create("Transaction A", a); var kb = d.create("Transaction B", UUID.randomUUID()); d.attach(kb.id(), target);
        d.declareWar(ka.id(), kb.id(), 1); win(d, a, target);
        // Inject a revision-overflow fault in a valid pre-transfer snapshot. The second
        // victory must fail before ANY counter, membership or receipt is published.
        CompoundTag tag = save(d, h);
        for (Tag v : tag.getList("revisions", Tag.TAG_COMPOUND)) {
            CompoundTag e = (CompoundTag) v; if (e.getUUID("village").equals(target)) e.putLong("revision", Long.MAX_VALUE);
        }
        for (Tag v : tag.getList("progress", Tag.TAG_COMPOUND)) ((CompoundTag) v).putLong("targetRevision", Long.MAX_VALUE);
        for (Tag v : tag.getList("receipts", Tag.TAG_COMPOUND)) ((CompoundTag) v).getCompound("campaign").putLong("targetRevision", Long.MAX_VALUE);
        d = KingdomSavedData.load(tag, h.getLevel().registryAccess());
        var raid = d.begin(a, target, 2, null); CompoundTag before = save(d, h);
        boolean failed = false;
        try { d.recordVictory(raid); } catch (ArithmeticException expected) { failed = true; }
        h.assertTrue(failed && before.equals(save(d, h)), "Fault before commit must retain the entire previous snapshot");
        var loaded = KingdomSavedData.load(save(d, h), h.getLevel().registryAccess());
        h.assertTrue(loaded.owner(target).equals(kb.id()) && loaded.wins(raid.campaign()) == 1 && loaded.receipt(raid.id()) == null,
                "A saved failed transaction contains neither partial transfer nor a false victory receipt");
        h.succeed();
    }

    @GameTest(templateNamespace = NS, template = "empty")
    public static void legacyProgressIsArchivedAndNeverBecomesPoliticalCredit(GameTestHelper h) {
        var old = new ConquestSavedData(); UUID a = UUID.randomUUID(), target = UUID.randomUUID();
        var first = old.begin(a, target, 1, null); old.recordVictory(first); old.finish(first, ConquestSavedData.Outcome.LOST);
        var pending = old.begin(a, target, 2, null);
        CompoundTag original = old.save(new CompoundTag(), h.getLevel().registryAccess());
        var d = KingdomSavedData.fromLegacy(original, h.getLevel().registryAccess());
        h.assertTrue(d.legacyWins(target) == 1 && d.progress(target).isEmpty() && d.owner(target) == null, "Target-only wins are historical, not political credit");
        h.assertTrue(d.raid(target).id().equals(pending.id()) && d.raid(target).campaign() == null, "Legacy in-flight identity retained for no-loot handling");
        var raid = d.raid(target); h.assertTrue(d.recordVictory(raid), "Legacy result recorded once");
        d.finish(raid, KingdomSavedData.Outcome.LOST);
        h.assertTrue(d.lastResult(target).outcome() == KingdomSavedData.Outcome.LEGACY_WON && d.owner(target) == null, "Legacy victory never transfers a village");
        d = KingdomSavedData.load(save(d, h), h.getLevel().registryAccess());
        d.create("After migration", a); var context = d.campaign(a, target);
        h.assertTrue(d.wins(context) == 0 && d.raid(target) == null, "Reload does not reimport old active markers");
        win(d, a, target); h.assertTrue(d.wins(context) == 1 && d.owner(target).equals(d.owner(a)),
                "Old target-only wins are not added; one new victory conquers the independent target");
        h.assertTrue(original.equals(old.save(new CompoundTag(), h.getLevel().registryAccess())), "Legacy data remains unchanged");
        h.succeed();
    }

    @GameTest(templateNamespace = NS, template = "empty")
    public static void legacyAlreadyWonMarkerRemainsIdempotent(GameTestHelper h) {
        var old = new ConquestSavedData(); UUID a = UUID.randomUUID(), target = UUID.randomUUID();
        var raid = old.begin(a, target, 1, null); old.recordVictory(raid);
        var d = KingdomSavedData.fromLegacy(old.save(new CompoundTag(), h.getLevel().registryAccess()), h.getLevel().registryAccess());
        h.assertTrue(!d.recordVictory(d.raid(target)), "An already recorded old victory cannot be applied again");
        d.finish(d.raid(target), KingdomSavedData.Outcome.ABORTED);
        h.assertTrue(d.lastResult(target).outcome() == KingdomSavedData.Outcome.LEGACY_WON && d.progress(target).isEmpty(), "Legacy receipt closes without political credit");
        h.succeed();
    }

    @GameTest(templateNamespace = NS, template = "empty")
    public static void malformedOrNewerDataIsRejected(GameTestHelper h) {
        var d = new KingdomSavedData(); d.create("Validation", UUID.randomUUID());
        CompoundTag newer = save(d, h); newer.putInt("format", 999);
        rejected(h, () -> KingdomSavedData.load(newer, h.getLevel().registryAccess()));
        CompoundTag duplicate = save(d, h);
        var list = duplicate.getList("membership", Tag.TAG_COMPOUND); list.add(list.getCompound(0).copy());
        rejected(h, () -> KingdomSavedData.load(duplicate, h.getLevel().registryAccess()));
        CompoundTag missing = save(d, h); missing.remove("kingdoms");
        rejected(h, () -> KingdomSavedData.load(missing, h.getLevel().registryAccess()));
        CompoundTag capital = save(d, h); capital.getList("membership", Tag.TAG_COMPOUND).clear();
        rejected(h, () -> KingdomSavedData.load(capital, h.getLevel().registryAccess()));
        h.succeed();
    }

    private static CompoundTag save(KingdomSavedData d, GameTestHelper h) { return d.save(new CompoundTag(), h.getLevel().registryAccess()); }
    private static void win(KingdomSavedData d, UUID a, UUID target) {
        var raid = d.begin(a, target, 1, null); d.recordVictory(raid); d.finish(raid, KingdomSavedData.Outcome.LOST);
    }
    private static void rejected(GameTestHelper h, Runnable action) {
        boolean rejected = false; try { action.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        h.assertTrue(rejected, "Invalid political operation must be rejected");
    }
}
