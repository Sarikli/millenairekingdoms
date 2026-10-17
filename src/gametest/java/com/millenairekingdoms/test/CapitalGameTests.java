package com.millenairekingdoms.test;

import com.millenairekingdoms.command.KingdomsCommands;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.kingdom.KingdomSavedData.Outcome;
import com.millenairekingdoms.kingdom.KingdomSavedData.WarStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Stage 4.3 capital rule, capital progress and kingdom collapse on the data layer; no NPC battles.
 * Real Millénaire raid paths for capitals are exercised in ConquestGameTests. Runs with verifyWars. */
@PrefixGameTestTemplate(false)
public final class CapitalGameTests {
    private static final String NS = "millenairekingdoms_war_tests";

    @GameTest(templateNamespace = NS, template = "empty")
    public static void sixtyPercentTableAndExactThreshold(GameTestHelper h) {
        Map<Integer, Integer> table = Map.of(0, 0, 1, 1, 2, 2, 3, 2, 4, 3, 5, 3, 10, 6);
        table.forEach((members, required) -> {
            var d = new KingdomSavedData(); var a = create(d); var b = create(d);
            List<UUID> villages = attach(d, b, members);
            var war = d.declareWar(a.id(), b.id(), 1);
            h.assertTrue(KingdomSavedData.capitalRequirement(war) == required, members + " initial members require " + required);
            for (int held = 0; held < required; held++) {
                CompoundTag before = save(d, h);
                rejected(h, () -> d.begin(a.capital(), b.capital(), 1, null)); // 1. Below 60%.
                h.assertTrue(before.equals(save(d, h)) && !d.capitalOpen(war), "Rejected capital raid publishes nothing");
                conquer(d, a.capital(), villages.get(held));
            }
            h.assertTrue(d.capitalOpen(war) && d.capitalHeld(war) == required, "2. Capital opens exactly at the threshold");
            var raid = d.begin(a.capital(), b.capital(), 1, null);
            h.assertTrue(war.id().equals(raid.campaign().warId()) && KingdomSavedData.requiredWins(raid.campaign()) == 2, "Capital needs 2/2 of this war");
            d.finish(raid, Outcome.LOST);
            h.assertTrue(d.capitalWins(war) == 0 && d.kingdom(b.id()).active(), "Failed capital raid awards nothing");
        });
        h.succeed();
    }

    @GameTest(templateNamespace = NS, template = "empty")
    public static void zeroMemberKingdomCapitalIsDirectlyOpen(GameTestHelper h) {
        var d = new KingdomSavedData(); var a = create(d); var b = create(d); var c = create(d);
        rejected(h, () -> d.begin(a.capital(), b.capital(), 1, null)); // No war.
        var war = d.declareWar(a.id(), b.id(), 1);
        h.assertTrue(war.initialDefenderMembers().isEmpty() && d.capitalOpen(war), "3. Zero members: capital is open at once");
        rejected(h, () -> d.begin(b.capital(), a.capital(), 1, null)); // Only the war's attacker may target a capital.
        rejected(h, () -> d.begin(c.capital(), b.capital(), 1, null)); // A third kingdom is not in this war.
        win(d, a.capital(), b.capital());
        h.assertTrue(d.capitalWins(war) == 1 && d.kingdom(b.id()).active() && d.war(war.id()).status() == WarStatus.ACTIVE, "7. 1/2 after the first win");
        win(d, a.capital(), b.capital());
        h.assertTrue(d.kingdom(b.id()).fallen() && d.owner(b.capital()).equals(a.id()) && d.war(war.id()).status() == WarStatus.CONQUERED,
                "8. Second win takes the capital");
        h.succeed();
    }

    @GameTest(templateNamespace = NS, template = "empty")
    public static void snapshotIsFixedAndLateJoinersDoNotCount(GameTestHelper h) {
        var d = new KingdomSavedData(); var a = create(d); var b = create(d);
        List<UUID> initial = attach(d, b, 2);
        var war = d.declareWar(a.id(), b.id(), 1);
        List<UUID> late = attach(d, b, 3);
        h.assertTrue(d.war(war.id()).initialDefenderMembers().equals(Set.copyOf(initial)), "4. Snapshot does not change");
        h.assertTrue(KingdomSavedData.capitalRequirement(d.war(war.id())) == 2, "5. Late joiners do not raise the requirement");
        conquer(d, a.capital(), initial.get(0)); conquer(d, a.capital(), late.get(0));
        h.assertTrue(d.capitalHeld(war) == 1 && !d.capitalOpen(war), "A captured late joiner does not count");
        rejected(h, () -> d.begin(a.capital(), b.capital(), 1, null));
        conquer(d, a.capital(), initial.get(1));
        h.assertTrue(d.capitalOpen(war), "Both initial villages open the capital");
        h.succeed();
    }

    @GameTest(templateNamespace = NS, template = "empty")
    public static void lostVillageStopsCountingAndInvalidatesInFlightCapitalRaid(GameTestHelper h) {
        var d = new KingdomSavedData(); var a = create(d); var b = create(d); var c = create(d);
        List<UUID> initial = attach(d, b, 1);
        var war = d.declareWar(a.id(), b.id(), 1);
        conquer(d, a.capital(), initial.get(0));
        var raid = d.begin(a.capital(), b.capital(), 5, null);
        // The rules give no in-war path for the attacker to lose a village, so the loss is injected
        // into a saved snapshot: the captured village now belongs to kingdom C with a new revision.
        CompoundTag tag = save(d, h);
        for (Tag v : tag.getList("membership", Tag.TAG_COMPOUND)) {
            CompoundTag e = (CompoundTag) v; if (e.getUUID("village").equals(initial.get(0))) e.putUUID("kingdom", c.id());
        }
        for (Tag v : tag.getList("revisions", Tag.TAG_COMPOUND)) {
            CompoundTag e = (CompoundTag) v; if (e.getUUID("village").equals(initial.get(0))) e.putLong("revision", e.getLong("revision") + 1);
        }
        var lost = KingdomSavedData.load(tag, h.getLevel().registryAccess());
        var w = lost.war(war.id());
        h.assertTrue(lost.capitalHeld(w) == 0 && !lost.capitalOpen(w), "6. A village the attacker lost no longer counts");
        h.assertTrue(lost.recordVictory(raid) && lost.receipt(raid.id()).outcome() == Outcome.INVALIDATED
                && lost.capitalWins(w) == 0 && lost.kingdom(b.id()).active(), "In-flight capital win below 60% awards nothing");
        lost.finish(raid, Outcome.LOST);
        rejected(h, () -> lost.begin(a.capital(), b.capital(), 6, null));
        h.succeed();
    }

    @GameTest(templateNamespace = NS, template = "empty")
    public static void peaceKeepsHalfCapitalProgressInOldWar(GameTestHelper h) {
        var d = new KingdomSavedData(); var a = create(d); var b = create(d);
        List<UUID> initial = attach(d, b, 1);
        var war = d.declareWar(a.id(), b.id(), 1);
        conquer(d, a.capital(), initial.get(0));
        var raid = d.begin(a.capital(), b.capital(), 2, null);
        rejected(h, () -> d.peace(war.id())); // No war change while the capital raid runs.
        rejected(h, () -> d.setActive(b.id(), false));
        d.recordVictory(raid); rejected(h, () -> d.peace(war.id())); d.finish(raid, Outcome.LOST);
        h.assertTrue(d.capitalWins(war) == 1, "1/2 before peace");
        d.peace(war.id());
        rejected(h, () -> d.begin(a.capital(), b.capital(), 3, null)); // 15. No capital raid after peace.
        var next = d.declareWar(a.id(), b.id(), 4);
        h.assertTrue(d.capitalWins(next) == 0 && d.capitalWins(d.war(war.id())) == 1, "New war starts 0/2; old 1/2 stays with the old war");
        win(d, a.capital(), b.capital());
        h.assertTrue(d.capitalWins(next) == 1 && d.kingdom(b.id()).active() && d.owner(b.capital()).equals(b.id()),
                "Old 1/2 does not complete the new war's capital campaign");
        h.succeed();
    }

    @GameTest(templateNamespace = NS, template = "empty")
    public static void capitalFallCollapsesKingdomAtomically(GameTestHelper h) {
        var d = new KingdomSavedData(); var a = create(d); var b = create(d); var c = create(d);
        UUID aOwn = UUID.randomUUID(); d.attach(a.id(), aOwn);
        UUID cOwn = UUID.randomUUID(); d.attach(c.id(), cOwn);
        List<UUID> bVillages = attach(d, b, 4);
        // An earlier war: C took one of B's villages. That loss must not be undone by the collapse.
        var earlier = d.declareWar(c.id(), b.id(), 1); conquer(d, c.capital(), bVillages.get(3)); d.peace(earlier.id());
        UUID independent = UUID.randomUUID();
        var war = d.declareWar(a.id(), b.id(), 2); // Initial: 3 villages -> 2 required.
        UUID late = UUID.randomUUID(); d.attach(b.id(), late);
        conquer(d, a.capital(), bVillages.get(0)); conquer(d, a.capital(), bVillages.get(1));
        h.assertTrue(d.capitalOpen(war), "Capital open at 2 of 3");
        win(d, a.capital(), b.capital());
        // Other raids still running when the capital falls: A against B's last village, B against an independent village.
        var normal = d.begin(aOwn, bVillages.get(2), 3, null);
        var bRaid = d.begin(late, independent, 3, null);
        var capitalRaid = d.begin(a.capital(), b.capital(), 3, null);
        long capitalRevision = d.revision(b.capital());
        h.assertTrue(d.recordVictory(capitalRaid) && d.receipt(capitalRaid.id()).transferred(), "Second capital win is recorded once");
        var fallen = d.kingdom(b.id());
        h.assertTrue(fallen.fallen() && !fallen.active() && war.id().equals(fallen.fallenInWar()) && d.members(b.id()).isEmpty(), "9. Defender collapsed");
        for (UUID v : List.of(b.capital(), bVillages.get(2), late))
            h.assertTrue(a.id().equals(d.owner(v)), "10. Remaining village transferred to the victor: " + v);
        h.assertTrue(d.revision(b.capital()) == capitalRevision + 1 && !d.isCapital(b.capital()), "Former capital is a normal village of the victor");
        h.assertTrue(d.kingdom(a.id()).capital().equals(a.capital()) && d.isCapital(a.capital()), "11. Victor keeps its capital");
        h.assertTrue(c.id().equals(d.owner(bVillages.get(3))) && c.id().equals(d.owner(cOwn)) && d.owner(independent) == null,
                "Earlier conquests and third kingdoms are not changed");
        var ended = d.war(war.id());
        h.assertTrue(ended.status() == WarStatus.CONQUERED && ended.endReason().equals(KingdomSavedData.CONQUERED_REASON)
                && d.capitalWins(ended) == 2 && d.war(earlier.id()).status() == WarStatus.PEACE, "War closed as CONQUERED; history kept");
        // Concurrent raids finish normally but award nothing.
        for (var r : List.of(normal, bRaid)) {
            h.assertTrue(d.recordVictory(r) && d.receipt(r.id()).outcome() == Outcome.INVALIDATED, "In-flight raid invalidated");
            d.finish(r, Outcome.LOST);
        }
        d.finish(capitalRaid, Outcome.LOST);
        h.assertTrue(d.owner(independent) == null && d.activeRaids().isEmpty(), "No transfer from an invalidated raid");
        // 16. A completed fall is never replayed; reload keeps it.
        h.assertTrue(!d.recordVictory(capitalRaid), "Completed capital fall cannot be applied again");
        CompoundTag saved = save(d, h);
        var loaded = KingdomSavedData.load(saved, h.getLevel().registryAccess());
        h.assertTrue(same(saved, save(loaded, h)) && !loaded.recordVictory(capitalRaid) && loaded.kingdom(b.id()).fallen(), "Reload neither revives nor replays");
        // The fallen kingdom cannot be used again.
        rejected(h, () -> loaded.setActive(b.id(), true));
        rejected(h, () -> loaded.declareWar(b.id(), c.id(), 9));
        rejected(h, () -> loaded.declareWar(c.id(), b.id(), 9));
        rejected(h, () -> loaded.attach(b.id(), UUID.randomUUID()));
        rejected(h, () -> loaded.peace(war.id()));
        rejected(h, () -> loaded.begin(a.capital(), b.capital(), 9, null)); // Now A's own village.
        rejected(h, () -> loaded.create(fallen.name(), UUID.randomUUID())); // Name stays reserved by history.
        h.assertTrue(same(saved, save(loaded, h)), "Rejected uses publish nothing");
        // The victor is free again: a new war against C gets a new UUID.
        h.assertTrue(loaded.activeWar(a.id()) == null && loaded.declareWar(a.id(), c.id(), 10).status() == WarStatus.ACTIVE, "Victor can fight again");
        h.succeed();
    }

    @GameTest(templateNamespace = NS, template = "empty")
    public static void malformedCollapseRecordsAreRejected(GameTestHelper h) {
        var d = new KingdomSavedData(); var a = create(d); var b = create(d);
        var war = d.declareWar(a.id(), b.id(), 1);
        win(d, a.capital(), b.capital()); win(d, a.capital(), b.capital());
        CompoundTag good = save(d, h);
        KingdomSavedData.load(good.copy(), h.getLevel().registryAccess());
        CompoundTag revived = good.copy(); kingdomEntry(revived, b.id()).remove("fallenInWar");
        rejected(h, () -> KingdomSavedData.load(revived, h.getLevel().registryAccess())); // CONQUERED war without a fallen kingdom.
        CompoundTag active = good.copy(); kingdomEntry(active, b.id()).putBoolean("active", true);
        rejected(h, () -> KingdomSavedData.load(active, h.getLevel().registryAccess()));
        CompoundTag member = good.copy(); CompoundTag entry = new CompoundTag();
        entry.putUUID("village", UUID.randomUUID()); entry.putUUID("kingdom", b.id()); member.getList("membership", Tag.TAG_COMPOUND).add(entry);
        CompoundTag revision = new CompoundTag(); revision.putUUID("village", entry.getUUID("village")); revision.putLong("revision", 1);
        member.getList("revisions", Tag.TAG_COMPOUND).add(revision);
        rejected(h, () -> KingdomSavedData.load(member, h.getLevel().registryAccess())); // A fallen kingdom has no members.
        CompoundTag peace = good.copy(); peace.getList("wars", Tag.TAG_COMPOUND).getCompound(0).putString("status", "PEACE");
        rejected(h, () -> KingdomSavedData.load(peace, h.getLevel().registryAccess()));
        CompoundTag wrongWar = good.copy(); kingdomEntry(wrongWar, b.id()).putUUID("fallenInWar", UUID.randomUUID());
        rejected(h, () -> KingdomSavedData.load(wrongWar, h.getLevel().registryAccess()));
        h.assertTrue(d.war(war.id()).status() == WarStatus.CONQUERED, "Valid collapse loads");
        h.succeed();
    }

    @GameTest(templateNamespace = NS, template = "empty")
    public static void realStageFourTwoASaveLoadsAndOpensCapital(GameTestHelper h) throws Exception {
        CompoundTag old;
        try (var input = CapitalGameTests.class.getResourceAsStream("/stage4_2A-kingdoms.dat")) {
            if (input == null) throw new IllegalStateException("Missing stage-4.2A fixture");
            old = NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap()).getCompound("data");
        }
        h.assertTrue(old.getInt("format") == 3, "Fixture is an authentic 0.3.1 save (format 3)");
        var d = KingdomSavedData.load(old.copy(), h.getLevel().registryAccess());
        // Formats 4 and 5 only add an empty ruler list and an empty ledger; every format-3 field is saved back unchanged.
        CompoundTag expected = old.copy(); expected.putInt("format", KingdomSavedData.FORMAT); expected.put("rulers", new net.minecraft.nbt.ListTag());
        CompoundTag ledger = new CompoundTag(); ledger.putLong("day", -1);
        for (String key : new String[]{"owed", "peaceOffers", "joinRequests"}) ledger.put(key, new net.minecraft.nbt.ListTag());
        expected.put("ledger", ledger);
        // Format 6 adds the autonomy section: no truces, cooldowns or orders, and a clock for each ACTIVE war from day 0.
        CompoundTag actual = save(d, h), autonomy = actual.getCompound("autonomy"); actual.remove("autonomy");
        long activeWars = d.wars().stream().filter(w -> w.status() == KingdomSavedData.WarStatus.ACTIVE).count();
        var clocks = autonomy.getList("warClocks", Tag.TAG_COMPOUND);
        h.assertTrue(autonomy.getLong("nextDecisionDay") == -1 && autonomy.getList("truces", Tag.TAG_COMPOUND).isEmpty()
                && autonomy.getList("raidDays", Tag.TAG_COMPOUND).isEmpty() && autonomy.getList("orders", Tag.TAG_COMPOUND).isEmpty()
                && clocks.size() == activeWars && clocks.stream().allMatch(c -> ((CompoundTag) c).getLong("startDay") == 0), "Format 6 adds only empty autonomy data");
        h.assertTrue(d.isDirty() && same(expected, actual), "17. A 0.3.1 save loads and saves back unchanged apart from the format upgrade");
        UUID north = UUID.fromString("fff8d6ec-6d19-4dd5-a49e-05ee75a9453d"), south = UUID.fromString("fc489f8d-f0e6-4a9e-8400-634134b17f1c");
        UUID strong = UUID.fromString("8198c04b-1f55-4e4d-bf47-b4e1723d9401"), southCapital = UUID.fromString("95317e59-9d85-48b9-b204-0f0d6ad8c1fa");
        var war = d.activeWar(north);
        h.assertTrue(war != null && war.defenderKingdom().equals(south) && KingdomSavedData.capitalRequirement(war) == 1
                && d.capitalHeld(war) == 1 && d.capitalOpen(war), "The real 0.3.1 war already meets 60% (1 of 1)");
        String info = KingdomsCommands.describeWar(d, war);
        h.assertTrue(info.contains("initial defender members=1") && info.contains("capital requires=1") && info.contains("held=1")
                && info.contains("capital open=true") && info.contains("progress=0/2"), "war info shows the capital fields: " + info);
        win(d, strong, southCapital); win(d, strong, southCapital);
        h.assertTrue(d.kingdom(south).fallen() && d.members(north).size() == 4 && d.members(south).isEmpty(), "Capital falls on the migrated data");
        var reloaded = KingdomSavedData.load(save(d, h), h.getLevel().registryAccess());
        h.assertTrue(reloaded.kingdom(south).fallen() && reloaded.war(war.id()).status() == WarStatus.CONQUERED, "Collapse persists");
        h.succeed();
    }

    @GameTest(templateNamespace = NS, template = "empty")
    public static void capitalFallSurvivesServerRestart(GameTestHelper h) {
        var d = KingdomSavedData.get(h.getLevel());
        UUID ca = UUID.fromString("40300000-0000-4000-8000-000000000001"), cb = UUID.fromString("40300000-0000-4000-8000-000000000002");
        UUID member = UUID.fromString("40300000-0000-4000-8000-000000000003"), cc = UUID.fromString("40300000-0000-4000-8000-000000000004");
        UUID halfCapital = UUID.fromString("40300000-0000-4000-8000-000000000005");
        if ("read".equals(System.getProperty("kingdoms.tests.persistence"))) {
            UUID a = d.owner(ca), b = d.kingdoms().stream().filter(k -> k.capital().equals(cb)).findFirst().orElseThrow().id();
            var conquered = d.war(d.kingdom(b).fallenInWar());
            h.assertTrue(d.kingdom(b).fallen() && conquered.status() == WarStatus.CONQUERED && d.capitalWins(conquered) == 2
                    && a.equals(d.owner(cb)) && a.equals(d.owner(member)) && d.members(b).isEmpty(), "Collapse survives a new server JVM");
            var half = d.activeWar(d.owner(cc));
            h.assertTrue(half != null && d.capitalWins(half) == 1 && d.owner(halfCapital).equals(half.defenderKingdom()), "Capital 1/2 survives restart");
            var receipt = d.lastResult(cb);
            h.assertTrue(receipt.transferred() && !d.recordVictory(new KingdomSavedData.Raid(receipt.raidId(), ca, cb, 1, null, receipt.campaign(), false)),
                    "Restart does not apply the capital fall again");
            System.out.println("CAPITAL_RESTART_READ_PASS: fallen kingdom, CONQUERED war, 2/2 and 1/2 capital progress retained");
        } else {
            if (d.owner(ca) == null) {
                var a = d.create("Capital restart A", ca); var b = d.create("Capital restart B", cb);
                d.attach(b.id(), member);
                d.declareWar(a.id(), b.id(), 1);
                win(d, ca, member); win(d, ca, member); win(d, ca, cb); win(d, ca, cb);
                var c = d.create("Capital restart C", cc); var e = d.create("Capital restart E", halfCapital);
                d.declareWar(c.id(), e.id(), 2); win(d, cc, halfCapital);
            }
            d.setDirty(); System.out.println("CAPITAL_RESTART_WRITE: collapse and 1/2 capital progress written");
        }
        h.succeed();
    }

    private static KingdomSavedData.Kingdom create(KingdomSavedData d) { return d.create("Capital test " + UUID.randomUUID(), UUID.randomUUID()); }
    private static List<UUID> attach(KingdomSavedData d, KingdomSavedData.Kingdom k, int count) {
        List<UUID> result = new ArrayList<>();
        for (int i = 0; i < count; i++) { UUID v = UUID.randomUUID(); d.attach(k.id(), v); result.add(v); }
        return result;
    }
    private static void win(KingdomSavedData d, UUID attacker, UUID target) {
        var raid = d.begin(attacker, target, 1, null); d.recordVictory(raid); d.finish(raid, Outcome.LOST);
    }
    private static void conquer(KingdomSavedData d, UUID attacker, UUID target) { win(d, attacker, target); win(d, attacker, target); }
    private static CompoundTag kingdomEntry(CompoundTag tag, UUID id) {
        for (Tag v : tag.getList("kingdoms", Tag.TAG_COMPOUND)) if (((CompoundTag) v).getUUID("id").equals(id)) return (CompoundTag) v;
        throw new IllegalStateException("Missing kingdom");
    }
    /** Saved maps have no defined order, so compare NBT with every list treated as a multiset. */
    private static boolean same(Tag a, Tag b) { return canonical(a).equals(canonical(b)); }
    private static String canonical(Tag tag) {
        if (tag instanceof CompoundTag c) return "{" + c.getAllKeys().stream().sorted().map(k -> k + ":" + canonical(c.get(k)))
                .collect(java.util.stream.Collectors.joining(",")) + "}";
        if (tag instanceof net.minecraft.nbt.ListTag l) return "[" + l.stream().map(CapitalGameTests::canonical).sorted()
                .collect(java.util.stream.Collectors.joining(",")) + "]";
        return tag.toString();
    }
    private static CompoundTag save(KingdomSavedData d, GameTestHelper h) { return d.save(new CompoundTag(), h.getLevel().registryAccess()); }
    private static void rejected(GameTestHelper h, Runnable action) {
        boolean rejected = false; try { action.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        h.assertTrue(rejected, "Invalid operation must be rejected");
    }
}
