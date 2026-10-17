package com.millenairekingdoms.test;

import static com.millenairekingdoms.test.AutonomySupport.id;
import static com.millenairekingdoms.test.AutonomySupport.raider;
import static com.millenairekingdoms.test.AutonomySupport.remove;

import com.millenairekingdoms.economy.TreasuryService;
import com.millenairekingdoms.kingdom.KingdomSavedData;
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

/** Package G: the ruler's withdrawals of real Millénaire coins from the capital's own town hall storage. */
@PrefixGameTestTemplate(false)
public final class TreasuryGameTests {
    static final String NS = "millenairekingdoms_gui_tests";

    record Realm(Village capital, KingdomSavedData.Kingdom kingdom, Stage5Support.TestPlayer ruler, ChestBlockEntity chest) {}
    static Realm realm(GameTestHelper h, String name) throws Exception {
        var capital = AutonomySupport.village(h, 1, 1, name);
        var d = KingdomSavedData.get(h.getLevel());
        var k = AutonomySupport.kingdom(d, capital);
        var ruler = Stage5Support.player(h, "R" + name.hashCode(), capital);
        Stage5Support.reputation(h, capital, ruler.getUUID(), 40000);
        h.assertTrue(Stage5Support.run(ruler, "kingdom claim") == 1, "Ruler claims " + name);
        return new Realm(capital, k, ruler, (ChestBlockEntity) h.getLevel().getBlockEntity(capital.getCenter()));
    }
    static Item gold() { return ModItems.DENIER_OR.get(); }
    static Item silver() { return ModItems.DENIER_ARGENT.get(); }
    static Item bronze() { return ModItems.DENIER.get(); }
    static long value(Stage5Support.TestPlayer p) {
        var inv = p.getInventory();
        return inv.countItem(gold()) * 4096L + inv.countItem(silver()) * 64L + inv.countItem(bronze());
    }
    static long value(ChestBlockEntity c) { return c.countItem(gold()) * 4096L + c.countItem(silver()) * 64L + c.countItem(bronze()); }

    // ---------------------------------------------------------------- G: treasury

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void rulerWithdrawsRealCoinsFromTheTownHall(GameTestHelper h) throws Exception {
        var r = realm(h, "Treasury capital");
        try {
            r.chest.setItem(0, new ItemStack(gold(), 2)); r.chest.setItem(1, new ItemStack(silver(), 3)); r.chest.setItem(2, new ItemStack(bronze(), 10));
            r.chest.setItem(3, new ItemStack(Items.WHEAT, 32));
            var coins = TreasuryService.stored(h.getLevel(), r.kingdom);
            h.assertTrue(coins.gold() == 2 && coins.silver() == 3 && coins.bronze() == 10 && coins.value() == 2 * 4096 + 3 * 64 + 10, "17. Real coins seen in storage");
            long amount = 4096 + 64 + 5;
            h.assertTrue(TreasuryService.withdraw(r.ruler, amount) == amount, "Exact withdrawal");
            h.assertTrue(value(r.ruler) == amount && r.ruler.getInventory().countItem(gold()) == 1 && r.ruler.getInventory().countItem(silver()) == 1
                    && r.ruler.getInventory().countItem(bronze()) == 5, "17. Real Millénaire coin items in the inventory");
            h.assertTrue(value(r.chest) == coins.value() - amount && r.chest.countItem(Items.WHEAT) == 32, "Deducted from the storage; other goods untouched");
            h.assertTrue(r.capital.getOwnerUUID() == null, "Millénaire ownership unchanged");
            h.succeed();
        } finally { remove(h, r.capital); KingdomSavedData.get(h.getLevel()).clearRuler(r.kingdom.id()); }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void treasuryNeverLosesOrDuplicatesCoins(GameTestHelper h) throws Exception {
        var r = realm(h, "Safe treasury");
        try {
            var level = h.getLevel();
            r.chest.setItem(0, new ItemStack(gold(), 1));
            long total = value(r.chest) + value(r.ruler);
            AutonomySupport.rejected(h, () -> TreasuryService.withdraw(r.ruler, 10)); // Only one gold coin: no exact change.
            h.assertTrue(value(r.chest) == 4096 && value(r.ruler) == 0, "18. No exact change: nothing moved");
            AutonomySupport.rejected(h, () -> TreasuryService.withdraw(r.ruler, 5000));
            for (int i = 0; i < 36; i++) r.ruler.getInventory().items.set(i, new ItemStack(Items.DIRT, 64));
            AutonomySupport.rejected(h, () -> TreasuryService.withdraw(r.ruler, 4096));
            h.assertTrue(value(r.chest) == 4096 && value(r.ruler) == 0, "18. Full inventory: nothing taken, nothing lost");
            r.ruler.getInventory().items.set(7, ItemStack.EMPTY);
            h.assertTrue(TreasuryService.withdraw(r.ruler, 4096) == 4096 && value(r.chest) == 0 && value(r.ruler) == 4096, "One free slot is enough");
            AutonomySupport.rejected(h, () -> TreasuryService.withdraw(r.ruler, 4096));
            h.assertTrue(value(r.chest) + value(r.ruler) == total, "18. The same coins cannot be taken twice; total conserved");
            // Two requests in a row (the server runs them one after the other) never pay more than the stock.
            r.chest.setItem(0, new ItemStack(bronze(), 50));
            r.ruler.getInventory().items.set(8, ItemStack.EMPTY);
            int paid = 0;
            for (int i = 0; i < 2; i++) try { paid += TreasuryService.withdraw(r.ruler, 40); } catch (IllegalArgumentException refused) { }
            h.assertTrue(paid == 40 && r.chest.countItem(bronze()) == 10 && r.ruler.getInventory().countItem(bronze()) == 40, "18. Back-to-back requests: one paid");
            h.succeed();
        } finally { remove(h, r.capital); KingdomSavedData.get(h.getLevel()).clearRuler(r.kingdom.id()); }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void onlyTheRulerNearTheCapitalWithdraws(GameTestHelper h) throws Exception {
        var r = realm(h, "Guarded treasury");
        try {
            r.chest.setItem(0, new ItemStack(silver(), 10));
            var thief = Stage5Support.player(h, "Thief", r.capital); Stage5Support.reputation(h, r.capital, thief.getUUID(), 100000);
            AutonomySupport.rejected(h, () -> TreasuryService.withdraw(thief, 64));
            h.assertTrue(Stage5Support.run(thief, "kingdom treasury withdraw 64") == 0 && value(thief) == 0, "16. A non-ruler cannot take coins");
            Stage5Support.moveTo(r.ruler, r.capital, 80);
            AutonomySupport.rejected(h, () -> TreasuryService.withdraw(r.ruler, 64));
            Stage5Support.moveTo(r.ruler, r.capital, 0);
            Stage5Support.reputation(h, r.capital, r.ruler.getUUID(), 8191);
            AutonomySupport.rejected(h, () -> TreasuryService.withdraw(r.ruler, 64)); // Decisions need 8192.
            Stage5Support.reputation(h, r.capital, r.ruler.getUUID(), 40000);
            h.assertTrue(Stage5Support.run(r.ruler, "kingdom treasury withdraw 64") == 1 && value(r.ruler) == 64 && r.chest.countItem(silver()) == 9,
                    "The ruler at the capital withdraws through the command too");
            h.assertTrue(value(r.chest) == 9 * 64, "Exactly one coin left the storage");
            h.succeed();
        } finally { remove(h, r.capital); KingdomSavedData.get(h.getLevel()).clearRuler(r.kingdom.id()); }
    }

    /** Write round: a withdrawal from a chest in a fixed, force-loaded chunk. Read round (new JVM): the chest holds exactly
     * what was left, so a restart neither restores nor duplicates withdrawn coins. */
    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 400)
    public static void withdrawalSurvivesServerRestart(GameTestHelper h) throws Exception {
        var level = h.getLevel();
        BlockPos pos = new BlockPos(2000, -60, 2000);
        level.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, true);
        if (AutonomySupport.read()) {
            var chest = (ChestBlockEntity) level.getBlockEntity(pos);
            h.assertTrue(chest != null && chest.countItem(gold()) == 1 && chest.countItem(silver()) == 0 && chest.countItem(bronze()) == 0,
                    "The storage after restart holds exactly the coins left");
            System.out.println("GUI_TREASURY_RESTART_READ_PASS: 1 gold left, withdrawn coins neither restored nor duplicated");
            h.succeed();
            return;
        }
        level.setBlockAndUpdate(pos, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
        var chest = (ChestBlockEntity) level.getBlockEntity(pos);
        var type = AutonomySupport.raidingType();
        var capital = new Village(org.millenaire.village.VillageId.random(), type.culture(), type.id(), pos);
        capital.setVillageName("Restart treasury");
        var hall = new org.millenaire.building.BuildingInstance(org.millenaire.building.BuildingId.random(), type.id(), pos,
                net.minecraft.world.level.block.Rotation.NONE, org.millenaire.building.BuildingInstance.Status.COMPLETE, null, "a", 2);
        var field = org.millenaire.building.BuildingInstance.class.getDeclaredField("inventory");
        field.setAccessible(true);
        field.set(hall, new org.millenaire.building.BuildingInventory(java.util.List.of(pos)));
        capital.addBuilding(hall);
        org.millenaire.village.VillageSavedData.get(level).getVillageManager().addVillage(capital);
        var d = KingdomSavedData.get(level);
        var k = d.create("Restart treasury " + UUID.randomUUID().toString().substring(0, 8), id(capital));
        var ruler = new Stage5Support.TestPlayer(level, "Treasurer");
        ruler.moveTo(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
        Stage5Support.reputation(h, capital, ruler.getUUID(), 40000);
        d.claimRule(k.id(), ruler.getUUID(), "Treasurer", 1);
        chest.setItem(0, new ItemStack(gold(), 1)); chest.setItem(1, new ItemStack(silver(), 1)); chest.setItem(2, new ItemStack(bronze(), 1));
        h.assertTrue(TreasuryService.withdraw(ruler, 65) == 65 && chest.countItem(gold()) == 1 && value(chest) == 4096, "Withdrawn before saving");
        d.clearRuler(k.id());
        System.out.println("GUI_TREASURY_RESTART_WRITE: 65 deniers withdrawn, 1 gold left in the chest");
        h.succeed();
    }
}
