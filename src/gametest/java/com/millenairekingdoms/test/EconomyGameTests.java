package com.millenairekingdoms.test;

import static com.millenairekingdoms.test.Stage5Support.*;

import com.millenairekingdoms.economy.EconomyService;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.kingdom.KingdomSavedData.Outcome;
import com.millenairekingdoms.politics.PoliticsService;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.building.BuildingId;
import org.millenaire.building.BuildingInstance;
import org.millenaire.building.BuildingInstance.Status;
import org.millenaire.culture.ModCultures;
import org.millenaire.item.ModItems;
import org.millenaire.village.Village;
import org.millenaire.village.VillageId;
import org.millenaire.village.VillageSavedData;

/** Stage 5 work package 2: daily income from attached villages' real town hall levels, paid as Millénaire coins into the
 * capital's real town hall chest. Most tests use their own KingdomSavedData so concurrent tests never pay each other. */
@PrefixGameTestTemplate(false)
public final class EconomyGameTests {

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void incomeFromSeveralVillagesFollowsTownHallLevel(GameTestHelper h) throws Exception {
        var capital = village(h, 1, 1, "Rich capital", 6, Status.COMPLETE);
        var one = village(h, 3, 1, "Level 1", 0, Status.COMPLETE);
        var three = village(h, 5, 1, "Level 3", 2, Status.COMPLETE);
        var upgrading = village(h, 1, 3, "Upgrading to 5", 4, Status.UPGRADING);
        var building = village(h, 3, 3, "Unbuilt", 0, Status.UNDER_CONSTRUCTION);
        var free = village(h, 5, 3, "Independent", 4, Status.COMPLETE);
        try {
            h.assertTrue(EconomyService.centerLevel(one) == 1 && EconomyService.centerLevel(three) == 3
                    && EconomyService.centerLevel(upgrading) == 4 && EconomyService.centerLevel(building) == 0
                    && EconomyService.centerLevel(free) == 5, "Town hall levels read from Millénaire's plan level and status");
            var d = new KingdomSavedData(); var k = d.create("Economy " + UUID.randomUUID(), id(capital));
            for (Village v : new Village[]{one, three, upgrading, building}) d.attach(k.id(), id(v));
            h.assertTrue(EconomyService.dailyIncome(h.getLevel(), d, k.id()) == 28 + 4 + 12 + 16, "Level × 4 per attached village; capital included, unbuilt excluded");
            d.startDay(0, Map.of());
            h.assertTrue(EconomyService.runDay(h.getLevel(), d, 1), "Day 1 runs");
            h.assertTrue(chest(h, capital).countItem(ModItems.DENIER.get()) == 60 && d.owed(k.id()) == 0, "60 deniers placed in the capital chest");
            h.assertTrue(chest(h, free).isEmpty() && chest(h, one).isEmpty(), "Independent villages and members are not tax centres");
            var five = new KingdomSavedData(); var f = five.create("Economy five " + UUID.randomUUID(), id(one));
            five.attach(f.id(), id(free)); five.startDay(0, Map.of());
            h.assertTrue(EconomyService.dailyIncome(h.getLevel(), five, f.id()) == 24, "Level1 capital4 plus level5 member20");
            h.succeed();
        } finally { remove(h, capital, one, three, upgrading, building, free); }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void eachDayPaysOnceAcrossRepeatsSkipsAndReloads(GameTestHelper h) throws Exception {
        var capital = village(h, 1, 1, "Once capital"); var member = village(h, 3, 3, "Once member", 1, Status.COMPLETE);
        try {
            var d = new KingdomSavedData(); var k = d.create("Once " + UUID.randomUUID(), id(capital)); d.attach(k.id(), id(member));
            d.startDay(4, Map.of());
            ChestBlockEntity chest = chest(h, capital);
            h.assertTrue(!EconomyService.runDay(h.getLevel(), d, 4) && chest.isEmpty(), "The starting day pays nothing");
            h.assertTrue(EconomyService.runDay(h.getLevel(), d, 5) && coins(chest) == 12, "Day 5 pays 12");
            h.assertTrue(!EconomyService.runDay(h.getLevel(), d, 5) && !EconomyService.runDay(h.getLevel(), d, 3) && coins(chest) == 12,
                    "The same day or an earlier day (time set back) never pays again");
            var reloaded = KingdomSavedData.load(d.save(new CompoundTag(), h.getLevel().registryAccess()), h.getLevel().registryAccess());
            h.assertTrue(!EconomyService.runDay(h.getLevel(), reloaded, 5) && coins(chest) == 12, "A reload (restart) of day 5 does not pay again");
            h.assertTrue(EconomyService.runDay(h.getLevel(), reloaded, 12) && coins(chest) == 24 && reloaded.day() == 12,
                    "Skipping from day 5 to 12 pays one day, not seven");
            h.succeed();
        } finally { remove(h, capital, member); }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void coinsConvertAndFullStorageKeepsTheRest(GameTestHelper h) throws Exception {
        var capital = village(h, 1, 1, "Coin capital");
        try {
            var d = new KingdomSavedData(); var k = d.create("Coins " + UUID.randomUUID(), id(capital));
            ChestBlockEntity chest = chest(h, capital);
            d.startDay(1, Map.of(k.id(), 4096L + 2 * 64 + 5));
            h.assertTrue(EconomyService.deliver(h.getLevel(), d, k.id()) == 4229 && chest.countItem(ModItems.DENIER_OR.get()) == 1
                    && chest.countItem(ModItems.DENIER_ARGENT.get()) == 2 && chest.countItem(ModItems.DENIER.get()) == 5 && d.owed(k.id()) == 0,
                    "4229 deniers = 1 denier d'or + 2 deniers d'argent + 5 deniers");
            chest.clearContent();
            for (int slot = 0; slot < chest.getContainerSize(); slot++) chest.setItem(slot, new ItemStack(Items.DIRT, 64));
            d.startDay(2, Map.of(k.id(), 10L));
            h.assertTrue(EconomyService.deliver(h.getLevel(), d, k.id()) == 0 && d.owed(k.id()) == 10, "A full storage keeps the income owed; nothing lost");
            chest.setItem(0, ItemStack.EMPTY); chest.setItem(1, new ItemStack(ModItems.DENIER.get(), 60));
            h.assertTrue(EconomyService.deliver(h.getLevel(), d, k.id()) == 10 && chest.countItem(ModItems.DENIER.get()) == 70 && d.owed(k.id()) == 0,
                    "Once space frees up the owed coins are stored exactly once");
            h.assertTrue(EconomyService.deliver(h.getLevel(), d, k.id()) == 0 && chest.countItem(ModItems.DENIER.get()) == 70, "Nothing is delivered twice");
            h.succeed();
        } finally { remove(h, capital); }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void capitalConquestMovesIncomeToTheNewCapital(GameTestHelper h) throws Exception {
        var a = village(h, 1, 1, "Victor capital"); var b = village(h, 5, 1, "Fallen capital", 1, Status.COMPLETE);
        var member = village(h, 3, 3, "Taxed village", 2, Status.COMPLETE); var free = village(h, 5, 5, "Free village", 0, Status.COMPLETE);
        try {
            var d = new KingdomSavedData();
            var ka = d.create("Victor " + UUID.randomUUID(), id(a)); var kb = d.create("Fallen " + UUID.randomUUID(), id(b));
            d.attach(kb.id(), id(member)); d.startDay(0, Map.of());
            EconomyService.runDay(h.getLevel(), d, 1);
            h.assertTrue(coins(chest(h, b)) == 20 && coins(chest(h, a)) == 4, "Before the war B's capital receives B's income");
            // A one-win conquest of an independent village mid-day: it pays its new kingdom from the next day.
            win(d, id(a), id(free));
            d.declareWar(ka.id(), kb.id(), 2);
            win(d, id(a), id(member)); win(d, id(a), id(member));
            win(d, id(a), id(b)); win(d, id(a), id(b));
            h.assertTrue(d.kingdom(kb.id()).fallen() && ka.id().equals(d.owner(id(b))), "B's capital fell");
            EconomyService.runDay(h.getLevel(), d, 2);
            h.assertTrue(coins(chest(h, a)) == 32 && coins(chest(h, b)) == 20,
                    "Next day the conquered villages, former capital included, pay A's capital; B is no longer a tax centre");
            h.succeed();
        } finally { remove(h, a, b, member, free); }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void npcAndRuledKingdomsEarnInactiveDoesNot(GameTestHelper h) throws Exception {
        var npc = village(h, 1, 1, "NPC capital"); var ruled = village(h, 5, 1, "Ruled capital"); var idle = village(h, 1, 5, "Idle capital");
        var m1 = village(h, 3, 1, "NPC member", 0, Status.COMPLETE); var m2 = village(h, 3, 3, "Ruled member", 0, Status.COMPLETE);
        var m3 = village(h, 3, 5, "Idle member", 0, Status.COMPLETE);
        try {
            var d = new KingdomSavedData();
            var kn = d.create("NPC " + UUID.randomUUID(), id(npc)); var kr = d.create("Ruled " + UUID.randomUUID(), id(ruled));
            var ki = d.create("Idle " + UUID.randomUUID(), id(idle));
            d.attach(kn.id(), id(m1)); d.attach(kr.id(), id(m2)); d.attach(ki.id(), id(m3));
            d.claimRule(kr.id(), UUID.randomUUID(), "Ruler", 1); d.setActive(ki.id(), false); d.startDay(0, Map.of());
            EconomyService.runDay(h.getLevel(), d, 1);
            h.assertTrue(coins(chest(h, npc)) == 8 && coins(chest(h, ruled)) == 8 && coins(chest(h, idle)) == 0,
                    "NPC-governed and ruled kingdoms both earn; an inactive kingdom does not");
            h.succeed();
        } finally { remove(h, npc, ruled, idle, m1, m2, m3); }
    }

    /** The real path: the server's level tick notices the new day and pays the shared world data once. Own batch, because
     * changing the world's time is visible to every test running at the same moment. */
    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 400, batch = "stage5_day_change")
    public static void realDayChangePaysOnceThroughTheServerTick(GameTestHelper h) throws Exception {
        var capital = village(h, 1, 1, "Tick capital"); var member = village(h, 3, 3, "Tick member", 1, Status.COMPLETE);
        var data = KingdomSavedData.get(h.getLevel());
        var k = kingdom(h, capital); data.attach(k.id(), id(member));
        long next = Math.max(data.day(), h.getLevel().getDayTime() / EconomyService.DAY_TICKS) + 1;
        h.getLevel().setDayTime(next * EconomyService.DAY_TICKS + 100);
        h.runAfterDelay(60, () -> {
            h.assertTrue(data.day() == next && coins(chest(h, capital)) == 12, "The day change paid 12 deniers once");
            h.runAfterDelay(60, () -> {
                h.assertTrue(coins(chest(h, capital)) == 12 && data.day() == next, "Later ticks of the same day pay nothing more");
                data.setActive(k.id(), false); remove(h, capital, member);
                h.succeed();
            });
        });
    }

    /** Write round: one more day booked as owed income with no storage to put it in (the expected balance is handed to the
     * next JVM in a file). Read round: the same day pays nothing, the balance is exactly as written, the next day adds one payment. */
    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void incomeIsNeverPaidTwiceAcrossRestart(GameTestHelper h) throws Exception {
        var level = h.getLevel(); var data = KingdomSavedData.get(level);
        UUID capitalId = UUID.fromString("5b000000-0000-4000-8000-000000000001"), memberId = UUID.fromString("5b000000-0000-4000-8000-000000000002");
        java.nio.file.Path expectedFile = level.getServer().getServerDirectory().resolve("stage5-income-restart.txt");
        if ("read".equals(System.getProperty("kingdoms.tests.persistence"))) {
            UUID k = data.owner(capitalId);
            long expected = Long.parseLong(java.nio.file.Files.readString(expectedFile).strip());
            h.assertTrue(k != null && expected > 0 && data.owed(k) == expected && !data.kingdom(k).active(), "Owed income survived the restart exactly once");
            h.assertTrue(!EconomyService.runDay(level, data, data.day()) && data.owed(k) == expected, "The persisted day does not pay again after restart");
            boolean member = PoliticsService.village(level, memberId) != null;
            data.setActive(k, true);
            EconomyService.runDay(level, data, data.day() + 1);
            data.setActive(k, false);
            h.assertTrue(member && data.owed(k) == expected + 8, "The next day pays exactly one more day (member village loaded: " + member + ")");
            System.out.println("STAGE5_INCOME_RESTART_READ_PASS: owed " + expected + " kept, no repeat, next day " + data.owed(k));
        } else {
            UUID k = data.owner(capitalId);
            if (k == null) {
                // Real Millénaire villages without storage: the income must wait in the ledger.
                var manager = VillageSavedData.get(level).getVillageManager();
                var c = new Village(new VillageId(capitalId), villageCulture(), villageType(), h.absolutePos(new BlockPos(1, 1, 1)));
                var m = new Village(new VillageId(memberId), villageCulture(), villageType(), h.absolutePos(new BlockPos(3, 1, 3)));
                m.addBuilding(new BuildingInstance(BuildingId.random(), villageType(), m.getCenter(), Rotation.NONE, Status.COMPLETE, null, "a", 1));
                manager.addVillage(c); manager.addVillage(m);
                k = data.create("S5 restart income", capitalId).id(); data.attach(k, memberId); data.setActive(k, false);
            }
            long before = data.owed(k);
            data.setActive(k, true);
            EconomyService.runDay(level, data, Math.max(0, data.day()) + 1);
            data.setActive(k, false); // Later day changes in this JVM must not add more.
            h.assertTrue(data.owed(k) == before + 8, "One day booked 8 deniers");
            java.nio.file.Files.writeString(expectedFile, Long.toString(data.owed(k)));
            data.setDirty(); VillageSavedData.get(level).setDirty();
            System.out.println("STAGE5_INCOME_RESTART_WRITE: owed=" + data.owed(k));
        }
        h.succeed();
    }

    private static ResourceLocation villageType() {
        return ModCultures.getAllVillageTypes().values().stream().filter(v -> !v.loneBuilding()).findFirst().orElseThrow().id();
    }
    private static ResourceLocation villageCulture() {
        return ModCultures.getAllVillageTypes().values().stream().filter(v -> !v.loneBuilding()).findFirst().orElseThrow().culture();
    }
    static UUID id(Village v) { return v.getId().uuid(); }
    static int coins(ChestBlockEntity chest) {
        return chest.countItem(ModItems.DENIER.get()) + 64 * chest.countItem(ModItems.DENIER_ARGENT.get()) + 4096 * chest.countItem(ModItems.DENIER_OR.get());
    }
    private static void win(KingdomSavedData d, UUID attacker, UUID target) {
        var raid = d.begin(attacker, target, 1, null); d.recordVictory(raid); d.finish(raid, Outcome.LOST);
    }
}
