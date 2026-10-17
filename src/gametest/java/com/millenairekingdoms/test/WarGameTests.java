package com.millenairekingdoms.test;

import com.millenairekingdoms.kingdom.KingdomSavedData;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Focused war and war-bound conquest rule tests on the data layer; no NPC battles.
 * Real Millénaire raid paths for these rules are exercised in ConquestGameTests. */
@PrefixGameTestTemplate(false)
public final class WarGameTests {
    private static final String NS = "millenairekingdoms_war_tests";

    @GameTest(templateNamespace = NS, template = "empty")
    public static void declarePeaceAndRedeclare(GameTestHelper h) {
        var d = new KingdomSavedData(); var a = create(d); var b = create(d);
        UUID member = UUID.randomUUID(); d.attach(b.id(), member);
        var war = d.declareWar(a.id(), b.id(), 123);
        h.assertTrue(war.initialDefenderMembers().equals(java.util.Set.of(member)), "Snapshot excludes capital");
        d.attach(b.id(), UUID.randomUUID());
        h.assertTrue(war.initialDefenderMembers().size() == 1, "Initial snapshot does not grow with later attachments");
        var before = d.members(b.id());
        h.assertTrue(d.peace(war.id()).status() == KingdomSavedData.WarStatus.PEACE, "Peace ends the war");
        CompoundTag saved = save(d, h); d.peace(war.id());
        h.assertTrue(saved.equals(save(d, h)) && d.members(b.id()).equals(before), "Repeated peace is idempotent and preserves membership");
        var next = d.declareWar(b.id(), a.id(), 124);
        h.assertTrue(!next.id().equals(war.id()) && d.wars().size() == 2 && d.activeWar(a.id()).equals(next), "New declaration gets a new UUID and preserves history");
        h.succeed();
    }

    @GameTest(templateNamespace = NS, template = "empty")
    public static void invalidAndOverlappingWarsAreRejected(GameTestHelper h) {
        var d = new KingdomSavedData(); var a = create(d); var b = create(d); var c = create(d);
        rejected(h, () -> d.declareWar(a.id(), a.id(), 1));
        rejected(h, () -> d.declareWar(a.id(), UUID.randomUUID(), 1));
        rejected(h, () -> d.declareWar(a.id(), b.id(), -1));
        d.setActive(b.id(), false); rejected(h, () -> d.declareWar(a.id(), b.id(), 1)); d.setActive(b.id(), true);
        var war = d.declareWar(a.id(), b.id(), 0); CompoundTag before = save(d, h);
        rejected(h, () -> d.declareWar(c.id(), a.id(), 1));
        rejected(h, () -> d.declareWar(b.id(), c.id(), 1));
        rejected(h, () -> d.declareWar(b.id(), a.id(), 1));
        rejected(h, () -> d.setActive(a.id(), false));
        rejected(h, () -> d.peace(UUID.randomUUID()));
        h.assertTrue(before.equals(save(d, h)), "Rejected operations do not partially alter state");
        d.peace(war.id()); d.setActive(a.id(), false); h.succeed();
    }

    @GameTest(templateNamespace = NS, template = "empty")
    public static void peaceWaitsForConquestCleanupAndPreservesResults(GameTestHelper h) {
        var d = new KingdomSavedData(); var a = create(d); var b = create(d);
        UUID target = UUID.randomUUID(); d.attach(b.id(), target);
        UUID independent = UUID.randomUUID();
        var unrelated = d.begin(a.capital(), independent, 1, null);
        var war = d.declareWar(a.id(), b.id(), 2); // An independent-village raid does not block war.
        d.finish(unrelated, KingdomSavedData.Outcome.LOST);
        for (int i = 0; i < 2; i++) {
            var raid = d.begin(a.capital(), target, 3 + i, null);
            CompoundTag before = save(d, h); rejected(h, () -> d.peace(war.id()));
            h.assertTrue(before.equals(save(d, h)), "Peace refusal leaves active raid untouched");
            d.recordVictory(raid); rejected(h, () -> d.peace(war.id()));
            d.finish(raid, KingdomSavedData.Outcome.LOST);
        }
        var receipt = d.lastResult(target); d.peace(war.id());
        h.assertTrue(d.owner(target).equals(a.id()) && d.wins(receipt.campaign()) == 2
                && d.lastResult(target).equals(receipt), "Peace retains completed conquest, receipt and membership");
        h.succeed();
    }

    @GameTest(templateNamespace = NS, template = "empty")
    public static void kingdomVillagesNeedActiveWarAndTwoWinsOfThatWar(GameTestHelper h) {
        var d = new KingdomSavedData(); var a = create(d); var b = create(d); var c = create(d);
        UUID target = UUID.randomUUID(), other = UUID.randomUUID(), own = UUID.randomUUID();
        d.attach(b.id(), target); d.attach(b.id(), other); d.attach(a.id(), own);
        CompoundTag before = save(d, h);
        rejected(h, () -> d.begin(a.capital(), target, 1, null)); // 1. No war.
        h.assertTrue(before.equals(save(d, h)), "Rejected start publishes nothing");
        var war = d.declareWar(a.id(), b.id(), 1);
        rejected(h, () -> d.begin(b.capital(), own, 1, null)); // 3. Defender cannot counter-conquer.
        rejected(h, () -> d.begin(c.capital(), target, 1, null)); // Third kingdom is not at war with B.
        rejected(h, () -> d.begin(a.capital(), b.capital(), 1, null)); // 15. Capitals stay protected.
        var raid = d.begin(a.capital(), target, 2, null);
        h.assertTrue(war.id().equals(raid.campaign().warId()), "Raid, progress and receipt carry the war id");
        h.assertTrue(d.recordVictory(raid) && !d.recordVictory(raid), "13. Same victory counts once");
        d.finish(raid, KingdomSavedData.Outcome.LOST);
        h.assertTrue(d.wins(raid.campaign()) == 1 && d.owner(target).equals(b.id())
                && war.id().equals(d.lastResult(target).campaign().warId()), "First war victory is 1/2");
        d.peace(war.id());
        CompoundTag afterPeace = save(d, h);
        rejected(h, () -> d.begin(a.capital(), target, 3, null)); // No new conquest after peace.
        h.assertTrue(afterPeace.equals(save(d, h)), "Peace refusal publishes nothing");
        var next = d.declareWar(a.id(), b.id(), 4);
        h.assertTrue(!next.id().equals(war.id()), "8. New war has a new UUID");
        var fresh = d.campaign(a.capital(), target);
        h.assertTrue(next.id().equals(fresh.warId()) && d.wins(fresh) == 0 && d.wins(raid.campaign()) == 1,
                "7. Old 1/2 stays with the old war; new war starts 0/2");
        for (int i = 0; i < 2; i++) {
            var r = d.begin(a.capital(), target, 5 + i, null); d.recordVictory(r); d.finish(r, KingdomSavedData.Outcome.LOST);
        }
        h.assertTrue(d.owner(target).equals(a.id()) && d.wins(fresh) == 2 && d.lastResult(target).transferred()
                && d.owner(other).equals(b.id()), "2. Two victories in one war transfer only that village");
        d.peace(next.id());
        var loaded = KingdomSavedData.load(save(d, h), h.getLevel().registryAccess());
        h.assertTrue(loaded.owner(target).equals(a.id()) && loaded.wins(raid.campaign()) == 1 && loaded.wins(fresh) == 2,
                "Captured village and per-war progress survive peace and reload");
        h.succeed();
    }

    @GameTest(templateNamespace = NS, template = "empty")
    public static void independentVillageNeedsNoWarAndOneVictory(GameTestHelper h) {
        var d = new KingdomSavedData(); var a = create(d); var b = create(d);
        UUID village = UUID.randomUUID();
        var lost = d.begin(a.capital(), village, 1, null);
        h.assertTrue(lost.campaign().warId() == null && d.activeWar(a.id()) == null, "6. No war needed");
        d.finish(lost, KingdomSavedData.Outcome.LOST);
        h.assertTrue(d.owner(village) == null && d.progress(village).isEmpty(), "5. Failed raid transfers nothing");
        var raid = d.begin(a.capital(), village, 2, null);
        var war = d.declareWar(b.id(), a.id(), 3); // Unrelated war is not blocked by an independent raid...
        d.peace(war.id()); // ...and neither is peace.
        h.assertTrue(d.recordVictory(raid) && !d.recordVictory(raid), "Victory recorded once");
        h.assertTrue(d.owner(village).equals(a.id()) && d.wins(raid.campaign()) == 1 && d.receipt(raid.id()).transferred(),
                "4. One victory conquers an independent village");
        d.finish(raid, KingdomSavedData.Outcome.LOST);
        var loaded = KingdomSavedData.load(save(d, h), h.getLevel().registryAccess());
        h.assertTrue(!loaded.recordVictory(raid) && loaded.owner(village).equals(a.id()), "Reload neither repeats nor loses the transfer");
        rejected(h, () -> loaded.begin(b.capital(), village, 4, null)); // Now a kingdom village: B needs a war.
        h.succeed();
    }

    @GameTest(templateNamespace = NS, template = "empty")
    public static void stageFourOneSaveLoadsAndOldProgressGetsNoCredit(GameTestHelper h) throws Exception {
        CompoundTag old;
        try (var input = WarGameTests.class.getResourceAsStream("/stage4_1-kingdoms.dat")) {
            if (input == null) throw new IllegalStateException("Missing stage-4.1 fixture");
            old = NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap()).getCompound("data");
        }
        h.assertTrue(old.getInt("format") == 2, "Fixture is an authentic 0.3.0 save");
        var plain = KingdomSavedData.load(old.copy(), h.getLevel().registryAccess());
        CompoundTag migrated = save(plain, h);
        h.assertTrue(plain.isDirty() && migrated.getInt("format") == KingdomSavedData.FORMAT, "Old save is upgraded on next save");
        for (String key : old.getAllKeys()) if (!key.equals("format"))
            h.assertTrue(old.get(key).equals(migrated.get(key)) || unorderedListsEqual(old.get(key), migrated.get(key)), "Upgrade preserves " + key);
        // A 0.3.0 save stored warless 1/2 progress; it must not complete an independent conquest.
        UUID ca = UUID.fromString("40100000-0000-4000-8000-000000000001"), village = UUID.randomUUID();
        CompoundTag partial = old.copy(); CompoundTag entry = new CompoundTag();
        entry.putUUID("attackerKingdom", plain.owner(ca)); entry.putUUID("target", village);
        entry.putLong("targetRevision", 0); entry.putInt("wins", 1); partial.getList("progress", Tag.TAG_COMPOUND).add(entry);
        var d = KingdomSavedData.load(partial, h.getLevel().registryAccess());
        var oldCampaign = d.progress(village).keySet().iterator().next();
        h.assertTrue(KingdomSavedData.STAGE3_WAR.equals(oldCampaign.warId()) && d.wins(d.campaign(ca, village)) == 0, "Old progress is archived");
        var raid = d.begin(ca, village, 1, null); d.recordVictory(raid);
        h.assertTrue(d.owner(village).equals(d.owner(ca)) && d.wins(oldCampaign) == 1, "New rule applies; old entry unchanged");
        CompoundTag injected = save(d, h);
        injected.getList("progress", Tag.TAG_COMPOUND).getCompound(0).putUUID("warId", UUID.randomUUID());
        rejected(h, () -> KingdomSavedData.load(injected, h.getLevel().registryAccess())); // Unknown war id.
        h.succeed();
    }

    @GameTest(templateNamespace = NS, template = "empty")
    public static void malformedWarRecordsAreRejected(GameTestHelper h) {
        var d = new KingdomSavedData(); var a = create(d); var b = create(d); var c = create(d);
        d.declareWar(a.id(), b.id(), 1);
        CompoundTag overlap = save(d, h); var wars = overlap.getList("wars", Tag.TAG_COMPOUND);
        CompoundTag extra = wars.getCompound(0).copy(); extra.putUUID("id", UUID.randomUUID()); extra.putUUID("defenderKingdom", c.id()); wars.add(extra);
        rejected(h, () -> KingdomSavedData.load(overlap, h.getLevel().registryAccess()));
        CompoundTag missing = save(d, h); missing.remove("wars");
        rejected(h, () -> KingdomSavedData.load(missing, h.getLevel().registryAccess()));
        CompoundTag broken = save(d, h); broken.getList("wars", Tag.TAG_COMPOUND).getCompound(0).putUUID("attackerKingdom", UUID.randomUUID());
        rejected(h, () -> KingdomSavedData.load(broken, h.getLevel().registryAccess()));
        CompoundTag reason = save(d, h); reason.getList("wars", Tag.TAG_COMPOUND).getCompound(0).putString("endReason", "OPERATOR_PEACE");
        rejected(h, () -> KingdomSavedData.load(reason, h.getLevel().registryAccess()));
        h.succeed();
    }

    @GameTest(templateNamespace = NS, template = "empty")
    public static void realStageThreeDataMigratesWithoutPoliticalChanges(GameTestHelper h) throws Exception {
        CompoundTag old;
        try (var input = WarGameTests.class.getResourceAsStream("/stage3-kingdoms.dat")) {
            if (input == null) throw new IllegalStateException("Missing stage-3 fixture");
            old = NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap()).getCompound("data");
        }
        h.assertTrue(old.getInt("format") == 1, "Fixture is authentic stage-3 format");
        var loaded = KingdomSavedData.load(old, h.getLevel().registryAccess());
        CompoundTag migrated = save(loaded, h);
        h.assertTrue(migrated.getInt("format") == KingdomSavedData.FORMAT && loaded.isDirty() && loaded.wars().isEmpty(), "Migration marks the current format for saving without inventing wars");
        for (String key : old.getAllKeys()) if (!key.equals("format") && !key.equals("progress") && !key.equals("receipts"))
            h.assertTrue(old.get(key).equals(migrated.get(key)) || unorderedListsEqual(old.get(key), migrated.get(key)), "Migration preserves field " + key);
        for (String key : java.util.List.of("progress", "receipts"))
            h.assertTrue(unorderedListsEqual(old.get(key), withoutStageThreeWar(migrated.getList(key, Tag.TAG_COMPOUND))),
                    "Old campaigns only gain the pre-war marker: " + key);
        var reloaded = KingdomSavedData.load(migrated, h.getLevel().registryAccess());
        h.assertTrue(reloaded.kingdoms().size() == 2 && reloaded.activeRaids().isEmpty(), "Migrated save reloads");
        var transfer = reloaded.lastResult(UUID.fromString("783fe8ef-3e0b-4660-9149-646aaee77957"));
        h.assertTrue(transfer.transferred() && reloaded.wins(transfer.campaign()) == 2, "Old 2/2 transfer remains valid history");
        h.succeed();
    }

    @GameTest(templateNamespace = NS, template = "empty")
    public static void warCommandsRequireOperator(GameTestHelper h) throws Exception {
        var level = h.getLevel(); var d = KingdomSavedData.get(level); var a = create(d); var b = create(d);
        var source = level.getServer().createCommandSourceStack().withLevel(level);
        var dispatcher = level.getServer().getCommands().getDispatcher();
        String declare = "kingdoms war declare " + a.id() + " " + b.id();
        boolean denied = false;
        try { dispatcher.execute(declare, source.withPermission(0)); }
        catch (com.mojang.brigadier.exceptions.CommandSyntaxException expected) { denied = true; }
        h.assertTrue(denied && d.activeWar(a.id()) == null, "Non-operator cannot declare war");
        h.assertTrue(dispatcher.execute(declare, source.withPermission(2)) == 1, "OP 2 can declare war");
        UUID warId = d.activeWar(a.id()).id();
        for (String command : java.util.List.of("kingdoms war info " + warId, "kingdoms war list", "kingdoms war peace " + warId))
            h.assertTrue(dispatcher.execute(command, source.withPermission(2)) == 1, "Command succeeds: " + command);
        h.assertTrue(d.war(warId).status() == KingdomSavedData.WarStatus.PEACE, "Peace command persisted result");
        h.succeed();
    }

    @GameTest(templateNamespace = NS, template = "empty")
    public static void activeWarAndPeaceHistorySurviveServerRestart(GameTestHelper h) {
        String origin = KingdomSavedData.class.getProtectionDomain().getCodeSource().getLocation().toString();
        h.assertTrue(origin.contains("/mods/millenairekingdoms.jar"), "Tests run against packaged JAR");
        var d = KingdomSavedData.get(h.getLevel());
        UUID ca = UUID.fromString("40100000-0000-4000-8000-000000000001"), cb = UUID.fromString("40100000-0000-4000-8000-000000000002");
        UUID warTarget = UUID.fromString("40100000-0000-4000-8000-000000000003"), independent = UUID.fromString("40100000-0000-4000-8000-000000000004");
        if ("read".equals(System.getProperty("kingdoms.tests.persistence"))) {
            var war = d.activeWar(d.owner(ca));
            h.assertTrue(war != null && war.defenderKingdom().equals(d.owner(cb)) && war.startGameTime() == 401,
                    "Active war and membership survive a new server JVM");
            h.assertTrue(d.wars().stream().anyMatch(w -> w.attackerKingdom().equals(d.owner(ca))
                    && w.status() == KingdomSavedData.WarStatus.PEACE && w.endReason().equals("OPERATOR_PEACE")), "Peace history survives restart");
            var campaign = d.campaign(ca, warTarget);
            h.assertTrue(war.id().equals(campaign.warId()) && d.wins(campaign) == 1 && d.owner(warTarget).equals(d.owner(cb))
                    && d.progress(warTarget).size() == 1, "War-bound 1/2 progress survives restart with its war id");
            h.assertTrue(d.owner(independent).equals(d.owner(ca)) && d.lastResult(independent).transferred()
                    && d.lastResult(independent).campaign().warId() == null, "Independent 1/1 conquest survives restart");
            h.assertTrue(!d.recordVictory(new KingdomSavedData.Raid(d.lastResult(independent).raidId(), ca, independent, 403, null,
                    d.lastResult(independent).campaign(), false)), "Restart does not process the same victory again");
            System.out.println("WAR_RESTART_READ_PASS: ACTIVE war, PEACE history, war 1/2 and independent 1/1 retained; artifact=" + origin);
        } else {
            if (d.owner(ca) == null) {
                var a = d.create("War restart A", ca); var b = d.create("War restart B", cb);
                var previous = d.declareWar(a.id(), b.id(), 400); d.peace(previous.id());
                d.declareWar(a.id(), b.id(), 401);
                d.attach(b.id(), warTarget);
                for (UUID target : java.util.List.of(warTarget, independent)) {
                    var raid = d.begin(ca, target, target.equals(warTarget) ? 402 : 403, null);
                    d.recordVictory(raid); d.finish(raid, KingdomSavedData.Outcome.LOST);
                }
            }
            d.setDirty(); System.out.println("WAR_RESTART_WRITE: ACTIVE and PEACE records; artifact=" + origin);
        }
        h.succeed();
    }
    private static KingdomSavedData.Kingdom create(KingdomSavedData d) { return d.create("War test " + UUID.randomUUID(), UUID.randomUUID()); }
    private static CompoundTag save(KingdomSavedData d, GameTestHelper h) { return d.save(new CompoundTag(), h.getLevel().registryAccess()); }
    private static net.minecraft.nbt.ListTag withoutStageThreeWar(net.minecraft.nbt.ListTag list) {
        var copy = list.copy();
        for (Tag v : copy) {
            CompoundTag e = (CompoundTag) v; CompoundTag c = e.contains("campaign") ? e.getCompound("campaign") : e;
            if (c.hasUUID("warId") && c.getUUID("warId").equals(KingdomSavedData.STAGE3_WAR)) c.remove("warId");
        }
        return copy;
    }
    private static boolean unorderedListsEqual(Tag a, Tag b) {
        return a instanceof net.minecraft.nbt.ListTag x && b instanceof net.minecraft.nbt.ListTag y
                && x.size() == y.size() && x.containsAll(y) && y.containsAll(x);
    }
    private static void rejected(GameTestHelper h, Runnable action) {
        boolean rejected = false; try { action.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        h.assertTrue(rejected, "Invalid operation must be rejected");
    }
}
