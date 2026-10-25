package com.millenairekingdoms.test;

import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.kingdom.KingdomSavedData.Outcome;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Stage 5.1A ruler data model only: no reputation, player commands or NPC interaction. Runs with verifyWars. */
@PrefixGameTestTemplate(false)
public final class RulerGameTests {
    private static final String NS = "millenairekingdoms_war_tests";

    @GameTest(templateNamespace = NS, template = "empty")
    public static void oneRulerPerKingdomAndOneKingdomPerPlayer(GameTestHelper h) {
        var d = new KingdomSavedData(); var a = create(d); var b = create(d);
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
        var ruler = d.claimRule(a.id(), alice, "Alice", 5);
        h.assertTrue(alice.equals(d.ruler(a.id()).player()) && a.id().equals(d.ruledBy(alice)) && ruler.sinceGameTime() == 5, "Alice rules A");
        h.assertTrue(d.claimRule(a.id(), alice, "Alice", 9).equals(ruler), "Re-claiming one's own kingdom changes nothing");
        CompoundTag before = save(d, h);
        rejected(h, () -> d.claimRule(a.id(), bob, "Bob", 6)); // A already has a ruler.
        rejected(h, () -> d.claimRule(b.id(), alice, "Alice", 6)); // Alice already rules A.
        rejected(h, () -> d.claimRule(UUID.randomUUID(), bob, "Bob", 6)); // Unknown kingdom.
        rejected(h, () -> d.claimRule(b.id(), null, "Nobody", 6));
        rejected(h, () -> d.claimRule(b.id(), bob, "B".repeat(65), 6));
        rejected(h, () -> d.claimRule(b.id(), bob, "  ", 6));
        rejected(h, () -> d.claimRule(b.id(), bob, null, 6));
        rejected(h, () -> d.abdicate(a.id(), bob)); // Only the ruler abdicates.
        rejected(h, () -> d.abdicate(b.id(), bob));
        h.assertTrue(before.equals(save(d, h)), "Rejected ruler operations publish nothing");
        d.claimRule(b.id(), bob, "Bob", 7);
        d.abdicate(a.id(), alice);
        h.assertTrue(d.ruler(a.id()) == null && d.ruledBy(alice) == null && bob.equals(d.ruler(b.id()).player()), "Abdication frees A only");
        d.claimRule(a.id(), alice, "Alice", 8);
        h.assertTrue(d.clearRuler(a.id()).player().equals(alice) && d.ruler(a.id()) == null && d.clearRuler(a.id()) == null,
                "Operator clear removes the ruler and is idempotent");
        rejected(h, () -> d.clearRuler(UUID.randomUUID()));
        h.succeed();
    }

    @GameTest(templateNamespace = NS, template = "empty")
    public static void kingdomWithoutRulerWorksAndIsNpcGoverned(GameTestHelper h) {
        var d = new KingdomSavedData(); var a = create(d); var b = create(d);
        h.assertTrue(d.ruler(a.id()) == null && d.npcGoverned(a.id()) && d.mayDecide(a.id(), null) && !d.mayDecide(a.id(), UUID.randomUUID()),
                "1/15. A kingdom without a ruler is NPC-governed and only NPC automation may decide for it");
        var war = d.declareWar(a.id(), b.id(), 1);
        win(d, a.capital(), b.capital()); win(d, a.capital(), b.capital());
        h.assertTrue(d.kingdom(b.id()).fallen() && d.war(war.id()).status() == KingdomSavedData.WarStatus.CONQUERED,
                "1. War and capital conquest work with no ruler anywhere");
        h.assertTrue(!d.npcGoverned(b.id()) && !d.mayDecide(b.id(), null), "A fallen kingdom is governed by nobody");
        var c = create(d); var e = create(d); UUID alice = UUID.randomUUID();
        d.claimRule(c.id(), alice, "Alice", 2);
        h.assertTrue(!d.npcGoverned(c.id()) && !d.mayDecide(c.id(), null) && d.mayDecide(c.id(), alice),
                "15. A ruled kingdom refuses NPC decisions and accepts its ruler's");
        // The data-layer war API stays usable by operators and future NPC services whatever the ruler state.
        var peace = d.peace(d.declareWar(e.id(), c.id(), 3).id());
        h.assertTrue(peace.status() == KingdomSavedData.WarStatus.PEACE && alice.equals(d.ruler(c.id()).player()), "War and peace keep the ruler");
        h.succeed();
    }

    @GameTest(templateNamespace = NS, template = "empty")
    public static void inactiveKingdomKeepsRulerButCannotAct(GameTestHelper h) {
        var d = new KingdomSavedData(); var a = create(d); var b = create(d);
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
        d.claimRule(a.id(), alice, "Alice", 1);
        d.setActive(a.id(), false); d.setActive(b.id(), false);
        h.assertTrue(alice.equals(d.ruler(a.id()).player()) && !d.mayDecide(a.id(), alice) && !d.mayDecide(b.id(), null),
                "7. Deactivation keeps the ruler but nobody may decide while passive");
        rejected(h, () -> d.claimRule(b.id(), bob, "Bob", 2));
        rejected(h, () -> d.claimRule(a.id(), bob, "Bob", 2));
        var reloaded = KingdomSavedData.load(save(d, h), h.getLevel().registryAccess());
        h.assertTrue(alice.equals(reloaded.ruler(a.id()).player()), "7. A passive kingdom's ruler is saved and reloaded");
        d.setActive(a.id(), true);
        h.assertTrue(d.mayDecide(a.id(), alice), "Reactivation restores the ruler's authority");
        d.setActive(a.id(), false); d.abdicate(a.id(), alice);
        h.assertTrue(d.ruler(a.id()) == null, "A ruler may still step down from a passive kingdom");
        h.succeed();
    }

    @GameTest(templateNamespace = NS, template = "empty")
    public static void capitalFallRemovesRulerAtomically(GameTestHelper h) {
        var d = new KingdomSavedData(); var a = create(d); var b = create(d);
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
        d.claimRule(a.id(), alice, "Alice", 1); d.claimRule(b.id(), bob, "Bob", 1);
        var war = d.declareWar(a.id(), b.id(), 1);
        win(d, a.capital(), b.capital());
        h.assertTrue(bob.equals(d.ruler(b.id()).player()), "1/2 capital progress keeps the ruler");
        var raid = d.begin(a.capital(), b.capital(), 2, null);
        h.assertTrue(d.recordVictory(raid), "Second capital victory");
        h.assertTrue(d.kingdom(b.id()).fallen() && d.ruler(b.id()) == null && d.ruledBy(bob) == null
                && alice.equals(d.ruler(a.id()).player()), "Collapse and ruler removal are one snapshot; the victor's ruler stays");
        d.finish(raid, Outcome.LOST);
        rejected(h, () -> d.claimRule(b.id(), bob, "Bob", 3)); // A fallen kingdom can never be ruled.
        h.assertTrue(d.war(war.id()).status() == KingdomSavedData.WarStatus.CONQUERED && d.claimRule(create(d).id(), bob, "Bob", 3) != null,
                "The former ruler is free to rule another kingdom");
        h.succeed();
    }

    @GameTest(templateNamespace = NS, template = "empty")
    public static void rulersSaveLoadAndMalformedDataIsRejected(GameTestHelper h) {
        var d = new KingdomSavedData(); var a = create(d); var b = create(d);
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
        d.claimRule(a.id(), alice, "Alice", 11); d.claimRule(b.id(), bob, "Bob", 12);
        CompoundTag saved = save(d, h);
        h.assertTrue(saved.getInt("format") == 6 && saved.getList("rulers", Tag.TAG_COMPOUND).size() == 2, "Detached compatibility format6 stores rulers without a fabricated historical commit");
        var loaded = KingdomSavedData.load(saved.copy(), h.getLevel().registryAccess());
        h.assertTrue(!loaded.isDirty() && loaded.ruler(a.id()).equals(d.ruler(a.id())) && loaded.ruler(b.id()).equals(d.ruler(b.id())),
                "Rulers round-trip unchanged");
        CompoundTag twice = saved.copy(); ruler(twice, b.id()).putUUID("player", alice);
        rejected(h, () -> KingdomSavedData.load(twice, h.getLevel().registryAccess())); // One player, two kingdoms.
        CompoundTag duplicate = saved.copy(); var list = duplicate.getList("rulers", Tag.TAG_COMPOUND);
        CompoundTag second = ruler(duplicate, a.id()).copy(); second.putUUID("player", UUID.randomUUID()); list.add(second);
        rejected(h, () -> KingdomSavedData.load(duplicate, h.getLevel().registryAccess())); // Two rulers, one kingdom.
        CompoundTag unknown = saved.copy(); ruler(unknown, a.id()).putUUID("kingdom", UUID.randomUUID());
        rejected(h, () -> KingdomSavedData.load(unknown, h.getLevel().registryAccess()));
        CompoundTag badUuid = saved.copy(); ruler(badUuid, a.id()).putString("player", "not-a-uuid");
        rejected(h, () -> KingdomSavedData.load(badUuid, h.getLevel().registryAccess())); // Corrupt player UUID.
        CompoundTag noKingdom = saved.copy(); ruler(noKingdom, a.id()).remove("kingdom");
        rejected(h, () -> KingdomSavedData.load(noKingdom, h.getLevel().registryAccess()));
        CompoundTag blank = saved.copy(); ruler(blank, a.id()).putString("lastKnownName", "");
        rejected(h, () -> KingdomSavedData.load(blank, h.getLevel().registryAccess()));
        CompoundTag missing = saved.copy(); missing.remove("rulers");
        rejected(h, () -> KingdomSavedData.load(missing, h.getLevel().registryAccess())); // Format 4 requires the list.
        CompoundTag oldWithRulers = saved.copy(); oldWithRulers.putInt("format", 3);
        rejected(h, () -> KingdomSavedData.load(oldWithRulers, h.getLevel().registryAccess())); // Format 3 never had rulers.
        // A fallen kingdom with a ruler is invalid.
        var e = new KingdomSavedData(); var x = create(e); var y = create(e);
        e.declareWar(x.id(), y.id(), 1); win(e, x.capital(), y.capital()); win(e, x.capital(), y.capital());
        CompoundTag fallen = save(e, h); CompoundTag entry = new CompoundTag();
        entry.putUUID("kingdom", y.id()); entry.putUUID("player", bob); entry.putString("lastKnownName", "Bob"); entry.putLong("sinceGameTime", 1);
        fallen.getList("rulers", Tag.TAG_COMPOUND).add(entry);
        rejected(h, () -> KingdomSavedData.load(fallen, h.getLevel().registryAccess()));
        h.succeed();
    }

    @GameTest(templateNamespace = NS, template = "empty")
    public static void realFormatThreeSaveUpgradesWithoutRulers(GameTestHelper h) throws Exception {
        CompoundTag old;
        try (var input = RulerGameTests.class.getResourceAsStream("/stage4_2A-kingdoms.dat")) {
            if (input == null) throw new IllegalStateException("Missing stage-4.2A fixture");
            old = NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap()).getCompound("data");
        }
        h.assertTrue(old.getInt("format") == 3 && !old.contains("rulers"), "Fixture is an authentic format-3 save");
        var d = KingdomSavedData.load(old, h.getLevel().registryAccess());
        h.assertTrue(d.isDirty() && d.kingdoms().stream().allMatch(k -> d.ruler(k.id()) == null), "Format 3 loads with no rulers and is upgraded");
        UUID north = UUID.fromString("fff8d6ec-6d19-4dd5-a49e-05ee75a9453d"), player = UUID.randomUUID();
        d.claimRule(north, player, "Tester", 1);
        var reloaded = KingdomSavedData.load(save(d, h), h.getLevel().registryAccess());
        h.assertTrue(player.equals(reloaded.ruler(north).player()) && reloaded.activeWar(north) != null, "Upgraded save keeps wars and the new ruler");
        h.succeed();
    }

    @GameTest(templateNamespace = NS, template = "empty")
    public static void rulerSurvivesServerRestart(GameTestHelper h) {
        var d = KingdomSavedData.get(h.getLevel());
        UUID capital = UUID.fromString("51a00000-0000-4000-8000-000000000001");
        UUID player = UUID.fromString("51a00000-0000-4000-8000-0000000000aa");
        if ("read".equals(System.getProperty("kingdoms.tests.persistence"))) {
            UUID kingdom = d.owner(capital);
            var ruler = kingdom == null ? null : d.ruler(kingdom);
            h.assertTrue(ruler != null && ruler.player().equals(player) && ruler.lastKnownName().equals("Offline ruler")
                    && ruler.sinceGameTime() == 42 && kingdom.equals(d.ruledBy(player)), "Ruler survives a new server JVM while the player is offline");
            System.out.println("RULER_RESTART_READ_PASS: ruler retained by UUID after restart");
        } else {
            if (d.owner(capital) == null) d.claimRule(d.create("Ruler restart kingdom", capital).id(), player, "Offline ruler", 42);
            d.setDirty(); System.out.println("RULER_RESTART_WRITE: ruler written");
        }
        h.succeed();
    }

    private static KingdomSavedData.Kingdom create(KingdomSavedData d) { return d.create("Ruler test " + UUID.randomUUID(), UUID.randomUUID()); }
    private static void win(KingdomSavedData d, UUID attacker, UUID target) {
        var raid = d.begin(attacker, target, 1, null); d.recordVictory(raid); d.finish(raid, Outcome.LOST);
    }
    private static CompoundTag ruler(CompoundTag tag, UUID kingdom) {
        for (Tag v : tag.getList("rulers", Tag.TAG_COMPOUND)) if (((CompoundTag) v).getUUID("kingdom").equals(kingdom)) return (CompoundTag) v;
        throw new IllegalStateException("Missing ruler");
    }
    private static CompoundTag save(KingdomSavedData d, GameTestHelper h) { return d.save(new CompoundTag(), h.getLevel().registryAccess()); }
    private static void rejected(GameTestHelper h, Runnable action) {
        boolean rejected = false; try { action.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        h.assertTrue(rejected, "Invalid ruler operation must be rejected");
    }
}
