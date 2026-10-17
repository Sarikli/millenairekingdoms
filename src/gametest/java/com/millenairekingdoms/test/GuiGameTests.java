package com.millenairekingdoms.test;

import static com.millenairekingdoms.test.AutonomySupport.id;
import static com.millenairekingdoms.test.AutonomySupport.raider;
import static com.millenairekingdoms.test.AutonomySupport.remove;

import com.millenairekingdoms.gui.KingdomActions;
import com.millenairekingdoms.gui.KingdomScreens;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.network.KingdomActionPayload;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.item.ModItems;
import org.millenaire.village.Village;

/** Package F, server side of the kingdom screen: every request goes through KingdomActions exactly as a network payload
 * would, for a server-side player, and the snapshot carries every tab's data. */
@PrefixGameTestTemplate(false)
public final class GuiGameTests {
    static final String NS = "millenairekingdoms_gui_tests";

    private static TreasuryGameTests.Realm realm(GameTestHelper h, String name) throws Exception { return TreasuryGameTests.realm(h, name); }
    static Item gold() { return ModItems.DENIER_OR.get(); }
    static Item silver() { return ModItems.DENIER_ARGENT.get(); }
    private static long value(Stage5Support.TestPlayer p) { return TreasuryGameTests.value(p); }
    private static long value(ChestBlockEntity c) { return TreasuryGameTests.value(c); }
    private static KingdomActionPayload withdraw(long amount) { return new KingdomActionPayload("WITHDRAW", Optional.empty(), Optional.empty(), amount, ""); }

    // ---------------------------------------------------------------- F: screen requests are checked on the server

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void screenRequestsGrantNothingByThemselves(GameTestHelper h) throws Exception {
        var r = realm(h, "Screen capital"); var enemy = AutonomySupport.village(h, 5, 5, "Screen enemy"); var free = AutonomySupport.village(h, 1, 5, "Screen free");
        try {
            var level = h.getLevel(); var d = KingdomSavedData.get(level);
            var e = AutonomySupport.kingdom(d, enemy);
            r.chest().setItem(0, new ItemStack(gold(), 1));
            raider(r.capital());
            var guest = Stage5Support.player(h, "Guest", r.capital()); Stage5Support.reputation(h, r.capital(), guest.getUUID(), 100000);
            var before = AutonomySupport.millenaire(h);
            for (var forged : new KingdomActionPayload[]{KingdomActionPayload.of("DECLARE_WAR", e.id()), KingdomActionPayload.of("RAID", id(free)),
                    withdraw(4096), KingdomActionPayload.of("ABDICATE"), KingdomActionPayload.of("PEACE"), KingdomActionPayload.of("ACCEPT", id(free))}) {
                guest.messages.clear();
                KingdomActions.handle(guest, forged);
                guestTick(guest);
            }
            h.assertTrue(d.activeWar(r.kingdom().id()) == null && d.raid(id(free)) == null && value(r.chest()) == 4096 && value(guest) == 0
                    && r.ruler().getUUID().equals(d.ruler(r.kingdom().id()).player()) && before.equals(AutonomySupport.millenaire(h)),
                    "16. Forged requests from a non-ruler change nothing");
            h.assertTrue(KingdomActions.handle(guest, KingdomActionPayload.of("NO_SUCH_ACTION")) == null, "Unknown requests are ignored");
            // The ruler's own requests work and are checked again each time.
            var ok = KingdomActions.handle(r.ruler(), KingdomActionPayload.of("DECLARE_WAR", e.id()));
            h.assertTrue(ok != null && d.activeWar(r.kingdom().id()) != null, "The ruler declares war through the screen");
            h.assertTrue(KingdomActions.handle(r.ruler(), KingdomActionPayload.of("PEACE")) == null && d.activeWar(r.kingdom().id()) != null,
                    "16. Requests closer than 4 ticks apart are ignored");
            // A screen opened before abdicating grants nothing afterwards.
            KingdomScreens.build(r.ruler());
            guestTick(r.ruler());
            h.assertTrue(KingdomActions.handle(r.ruler(), KingdomActionPayload.of("PEACE")) != null && d.activeWar(r.kingdom().id()) == null, "Peace through the screen");
            d.abdicate(r.kingdom().id(), r.ruler().getUUID());
            guestTick(r.ruler());
            KingdomActions.handle(r.ruler(), withdraw(4096));
            h.assertTrue(value(r.chest()) == 4096 && value(r.ruler()) == 0, "16. Stale screen data after abdication grants nothing");
            h.succeed();
        } finally { remove(h, r.capital(), enemy, free); }
    }
    /** Lets the next request of this player pass the 4-tick rate limit without waiting real ticks. */
    private static void guestTick(Stage5Support.TestPlayer p) {
        try {
            var field = KingdomActions.class.getDeclaredField("LAST");
            field.setAccessible(true);
            ((java.util.Map<?, ?>) field.get(null)).remove(p.getUUID());
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void snapshotShowsEveryTabsData(GameTestHelper h) throws Exception {
        var r = realm(h, "Snapshot capital"); var member = AutonomySupport.village(h, 5, 5, "Snapshot member"); var free = AutonomySupport.village(h, 1, 5, "Snapshot free");
        try {
            var d = KingdomSavedData.get(h.getLevel());
            d.attach(r.kingdom().id(), id(member));
            raider(r.capital());
            r.chest().setItem(0, new ItemStack(silver(), 2));
            var view = KingdomScreens.build(r.ruler());
            var k = view.getCompound("kingdom");
            h.assertTrue(view.getBoolean("isRuler") && view.getUUID("focus").equals(r.kingdom().id()) && k.getString("name").equals(r.kingdom().name())
                    && k.getString("capital").equals("Snapshot capital") && k.getList("members", Tag.TAG_COMPOUND).size() == 2
                    && k.getLong("income") == 12 && k.getLong("coins") == 128 && k.contains("x"), "My kingdom and treasury data");
            var targets = view.getList("targets", Tag.TAG_COMPOUND);
            boolean freeListed = targets.stream().anyMatch(t -> ((net.minecraft.nbt.CompoundTag) t).getUUID("id").equals(id(free))
                    && ((net.minecraft.nbt.CompoundTag) t).getBoolean("ready"));
            h.assertTrue(freeListed && view.getList("sources", Tag.TAG_COMPOUND).size() == 2, "Military: named targets with status and sources");
            var rights = view.getCompound("rights");
            h.assertTrue(rights.contains("decideOk") && rights.contains("withdrawOk") && rights.getInt("reputation") == 40000, "Rulership rights");
            var guest = Stage5Support.player(h, "Viewer", r.capital());
            var other = KingdomScreens.build(guest);
            h.assertTrue(!other.getBoolean("isRuler") && !other.contains("targets") && other.getCompound("rights").contains("claim")
                    && other.getCompound("rights").contains("withdraw"), "A non-ruler sees information only, with reasons for each disabled option");
            h.assertTrue(!other.getCompound("kingdom").contains("coins") && view.getCompound("kingdom").getBoolean("storageLoaded"),
                    "M1. Only the ruler sees the treasury stock");
            h.succeed();
        } finally { remove(h, r.capital(), member, free); KingdomSavedData.get(h.getLevel()).clearRuler(r.kingdom().id()); }
    }

}
