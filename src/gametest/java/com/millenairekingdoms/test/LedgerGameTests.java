package com.millenairekingdoms.test;

import static com.millenairekingdoms.test.Stage5Support.NS;
import static com.millenairekingdoms.test.Stage5Support.rejected;

import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.kingdom.KingdomSavedData.Outcome;
import java.util.Map;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Save format 5: the stage-5 ledger (daily marker, undelivered income, peace offers, join requests), its migration
 * from older formats, its strict loading, and its survival across a separate server JVM. */
@PrefixGameTestTemplate(false)
public final class LedgerGameTests {

    @GameTest(templateNamespace = NS, template = "empty")
    public static void formatFiveRoundTripAndMalformedLedgerIsRejected(GameTestHelper h) {
        var d = new KingdomSavedData(); var a = create(d); var b = create(d); var c = create(d);
        UUID village = UUID.randomUUID(), player = UUID.randomUUID();
        var war = d.declareWar(a.id(), b.id(), 1);
        d.offerPeace(war.id()); d.offerPeace(war.id());
        d.startDay(7, Map.of(a.id(), 12L, c.id(), 0L));
        d.requestJoin(village, c.id(), player, "Owner", 3);
        CompoundTag saved = save(d, h);
        h.assertTrue(saved.getInt("format") == KingdomSavedData.FORMAT && saved.getCompound("ledger").getLong("day") == 7, "The current format stores the ledger");
        var loaded = KingdomSavedData.load(saved.copy(), h.getLevel().registryAccess());
        h.assertTrue(!loaded.isDirty() && loaded.day() == 7 && loaded.owed(a.id()) == 12 && loaded.owed(c.id()) == 0
                && loaded.peaceOffered(war.id()) && loaded.joinRequest(village).player().equals(player), "Ledger round-trips unchanged");
        h.assertTrue(!d.startDay(7, Map.of(a.id(), 12L)) && !d.startDay(6, Map.of(a.id(), 12L)) && d.owed(a.id()) == 12,
                "The same or an earlier day never pays again");
        rejected(h, () -> d.delivered(a.id(), 13)); rejected(h, () -> d.delivered(a.id(), 0));
        d.delivered(a.id(), 5);
        h.assertTrue(d.owed(a.id()) == 7, "Delivery reduces only what was actually placed in storage");
        CompoundTag stale = saved.copy(); stale.getCompound("ledger").getList("peaceOffers", Tag.TAG_COMPOUND).getCompound(0).putUUID("war", UUID.randomUUID());
        rejected(h, () -> KingdomSavedData.load(stale, h.getLevel().registryAccess())); // Offer for an unknown war.
        CompoundTag negative = saved.copy(); negative.getCompound("ledger").getList("owed", Tag.TAG_COMPOUND).getCompound(0).putLong("deniers", -4);
        rejected(h, () -> KingdomSavedData.load(negative, h.getLevel().registryAccess()));
        CompoundTag member = saved.copy(); member.getCompound("ledger").getList("joinRequests", Tag.TAG_COMPOUND).getCompound(0).putUUID("village", a.capital());
        rejected(h, () -> KingdomSavedData.load(member, h.getLevel().registryAccess())); // A capital cannot have a pending application.
        CompoundTag missing = saved.copy(); missing.remove("ledger");
        rejected(h, () -> KingdomSavedData.load(missing, h.getLevel().registryAccess()));
        CompoundTag old = saved.copy(); old.putInt("format", 4);
        rejected(h, () -> KingdomSavedData.load(old, h.getLevel().registryAccess())); // Format 4 never had a ledger.
        h.succeed();
    }

    @GameTest(templateNamespace = NS, template = "empty")
    public static void ledgerFollowsWarsMembershipAndCollapse(GameTestHelper h) {
        var d = new KingdomSavedData(); var a = create(d); var b = create(d); var c = create(d);
        UUID village = UUID.randomUUID();
        var war = d.declareWar(a.id(), b.id(), 1);
        d.offerPeace(war.id());
        d.peace(war.id());
        h.assertTrue(!d.peaceOffered(war.id()), "An offer ends with its war");
        d.requestJoin(village, c.id(), UUID.randomUUID(), "Owner", 1);
        d.attach(c.id(), village);
        h.assertTrue(d.joinRequest(village) == null, "A request disappears once the village belongs to a kingdom");
        rejected(h, () -> d.requestJoin(village, a.id(), UUID.randomUUID(), "Owner", 1));
        d.startDay(1, Map.of(a.id(), 8L, b.id(), 20L));
        var second = d.declareWar(a.id(), b.id(), 2);
        UUID loose = UUID.randomUUID(); d.requestJoin(loose, b.id(), UUID.randomUUID(), "Owner", 2);
        win(d, a.capital(), b.capital()); win(d, a.capital(), b.capital());
        h.assertTrue(d.kingdom(b.id()).fallen() && d.owed(b.id()) == 0 && d.owed(a.id()) == 28 && d.joinRequest(loose) == null
                && !d.peaceOffered(second.id()), "A fallen kingdom keeps no income, offers or applications; its income goes to the victor");
        rejected(h, () -> d.startDay(2, Map.of(b.id(), 4L))); // A fallen kingdom earns nothing.
        var reloaded = KingdomSavedData.load(save(d, h), h.getLevel().registryAccess());
        h.assertTrue(reloaded.owed(a.id()) == 28 && reloaded.day() == 1, "Collapse result reloads");
        h.succeed();
    }

    @GameTest(templateNamespace = NS, template = "empty")
    public static void olderFormatsUpgradeWithEmptyLedger(GameTestHelper h) throws Exception {
        CompoundTag old;
        try (var input = LedgerGameTests.class.getResourceAsStream("/stage4_2A-kingdoms.dat")) {
            if (input == null) throw new IllegalStateException("Missing stage-4.2A fixture");
            old = NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap()).getCompound("data");
        }
        var d = KingdomSavedData.load(old, h.getLevel().registryAccess());
        h.assertTrue(d.isDirty() && d.day() == -1 && d.owed().isEmpty() && d.joinRequests().isEmpty(), "Format 3 loads with an empty ledger");
        var four = new KingdomSavedData(); var k = create(four); four.claimRule(k.id(), UUID.randomUUID(), "Ruler", 1);
        CompoundTag tag = save(four, h); tag.putInt("format", 4); tag.remove("ledger"); tag.remove("autonomy");
        var upgraded = KingdomSavedData.load(tag, h.getLevel().registryAccess());
        h.assertTrue(upgraded.isDirty() && upgraded.day() == -1 && upgraded.ruler(k.id()) != null
                && save(upgraded, h).getInt("format") == KingdomSavedData.FORMAT, "Format 4 keeps rulers, gains an empty ledger and saves in the current format");
        h.succeed();
    }

    /** Write round: a ruled kingdom with an offer, a pending application and a started day. Read round (new JVM): all kept. */
    @GameTest(templateNamespace = NS, template = "empty")
    public static void politicsSurviveServerRestart(GameTestHelper h) {
        var d = KingdomSavedData.get(h.getLevel());
        UUID attacker = UUID.fromString("5a000000-0000-4000-8000-000000000001"), defender = UUID.fromString("5a000000-0000-4000-8000-000000000002");
        UUID ruler = UUID.fromString("5a000000-0000-4000-8000-0000000000aa"), village = UUID.fromString("5a000000-0000-4000-8000-000000000003");
        if ("read".equals(System.getProperty("kingdoms.tests.persistence"))) {
            UUID a = d.owner(attacker), b = d.owner(defender);
            var war = a == null ? null : d.activeWar(a);
            h.assertTrue(war != null && war.defenderKingdom().equals(b) && d.peaceOffered(war.id()) && ruler.equals(d.ruler(b).player())
                    && d.joinRequest(village) != null && d.joinRequest(village).kingdom().equals(b), "Ruler, war, peace offer and application survive restart");
            System.out.println("STAGE5_LEDGER_RESTART_READ_PASS: ruler, active war, peace offer and join request retained");
        } else {
            if (d.owner(attacker) == null) {
                var a = d.create("S5 restart attacker", attacker); var b = d.create("S5 restart defender", defender);
                d.claimRule(b.id(), ruler, "Restart ruler", 5);
                d.offerPeace(d.declareWar(a.id(), b.id(), 5).id());
                d.requestJoin(village, b.id(), UUID.randomUUID(), "Village owner", 6);
            }
            d.setDirty(); System.out.println("STAGE5_LEDGER_RESTART_WRITE: written");
        }
        h.succeed();
    }

    static KingdomSavedData.Kingdom create(KingdomSavedData d) { return d.create("Ledger test " + UUID.randomUUID(), UUID.randomUUID()); }
    static void win(KingdomSavedData d, UUID attacker, UUID target) {
        var raid = d.begin(attacker, target, 1, null); d.recordVictory(raid); d.finish(raid, Outcome.LOST);
    }
    static CompoundTag save(KingdomSavedData d, GameTestHelper h) { return d.save(new CompoundTag(), h.getLevel().registryAccess()); }
}
