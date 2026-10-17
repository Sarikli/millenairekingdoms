package com.millenairekingdoms.economy;

import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.kingdom.KingdomSavedData.Kingdom;
import com.millenairekingdoms.politics.PoliticsService;
import com.millenairekingdoms.util.KingdomException;
import com.mojang.logging.LogUtils;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.millenaire.building.BuildingInstance;
import org.millenaire.building.BuildingInventory;
import org.millenaire.item.ModItems;
import org.millenaire.item.MoneyHelper;
import org.millenaire.village.Village;
import org.slf4j.Logger;

/** Package G: the ruler takes real Millénaire coins out of the capital town hall's own storage (the chests the daily income
 * fills). No virtual treasury, no new currency, no new chest: the coins are removed with Millénaire's BuildingInventory.remove
 * and handed over as the same coin items. Only the three loose coin items are touched, never other goods, and the town
 * hall's chests stay locked as Millénaire decides. Everything runs synchronously on the server thread: a request first
 * checks the stock and the player's free inventory space, then removes, then gives; a shortfall is rolled back, so no
 * request can lose or duplicate coins, and two requests can only run one after the other. */
public final class TreasuryService {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Same reach as Millénaire's own village interaction and the claim rule. */
    public static final double REACH = PoliticsService.CAPITAL_REACH;

    /** Loose coins in the storage: denier d'or (4096), denier d'argent (64), denier (1). */
    public record Coins(int gold, int silver, int bronze) {
        public long value() { return (long) gold * MoneyHelper.OR_VALUE + (long) silver * MoneyHelper.ARGENT_VALUE + bronze; }
    }

    private TreasuryService() {}

    static Item gold() { return ModItems.DENIER_OR.get(); }
    static Item silver() { return ModItems.DENIER_ARGENT.get(); }
    static Item bronze() { return ModItems.DENIER.get(); }

    public static BuildingInventory storage(ServerLevel level, Kingdom kingdom) { return EconomyService.storage(level, kingdom); }

    /** Whether every chest of the capital's town hall storage is loaded, so that the scan below sees the whole stock. */
    public static boolean loaded(ServerLevel level, Kingdom kingdom) {
        BuildingInventory storage = storage(level, kingdom);
        return storage != null && storage.getChestPositions().stream().allMatch(level::isLoaded);
    }

    /** The coins in the capital's loaded town hall chests right now (Millénaire's own scan). */
    public static Coins stored(ServerLevel level, Kingdom kingdom) {
        BuildingInventory storage = storage(level, kingdom);
        if (storage == null) return new Coins(0, 0, 0);
        var contents = storage.scanChests(level);
        return new Coins(contents.getOrDefault(gold(), 0), contents.getOrDefault(silver(), 0), contents.getOrDefault(bronze(), 0));
    }

    /** The coins that pay exactly amount from this stock (largest first; each value divides the next, so this finds a
     * way whenever one exists), or null. */
    public static Coins change(Coins stock, long amount) {
        if (amount <= 0) return null;
        int g = (int) Math.min(stock.gold, amount / MoneyHelper.OR_VALUE);
        long rest = amount - (long) g * MoneyHelper.OR_VALUE;
        int s = (int) Math.min(stock.silver, rest / MoneyHelper.ARGENT_VALUE);
        rest -= (long) s * MoneyHelper.ARGENT_VALUE;
        if (rest > stock.bronze) return null;
        return new Coins(g, s, (int) rest);
    }
    /** The largest amount at most `amount` that the stock can pay exactly. */
    public static long payable(Coins stock, long amount) {
        long want = Math.min(amount, stock.value());
        int g = (int) Math.min(stock.gold, want / MoneyHelper.OR_VALUE);
        long rest = want - (long) g * MoneyHelper.OR_VALUE;
        int s = (int) Math.min(stock.silver, rest / MoneyHelper.ARGENT_VALUE);
        rest -= (long) s * MoneyHelper.ARGENT_VALUE;
        return (long) g * MoneyHelper.OR_VALUE + (long) s * MoneyHelper.ARGENT_VALUE + Math.min(rest, stock.bronze);
    }

    /** Whether the player's main inventory (36 slots) can take all these coins, simulated on copies. */
    public static boolean fits(ServerPlayer player, Coins coins) {
        NonNullList<ItemStack> items = player.getInventory().items;
        int[] counts = new int[items.size()];
        Item[] kinds = new Item[items.size()];
        for (int i = 0; i < items.size(); i++) { counts[i] = items.get(i).getCount(); kinds[i] = items.get(i).isEmpty() ? null : items.get(i).getItem(); }
        for (var entry : List.of(new Object[]{gold(), coins.gold}, new Object[]{silver(), coins.silver}, new Object[]{bronze(), coins.bronze})) {
            Item item = (Item) entry[0]; int left = (Integer) entry[1];
            int max = item.getDefaultMaxStackSize();
            for (int i = 0; i < counts.length && left > 0; i++)
                if (kinds[i] == item && ItemStack.isSameItemSameComponents(items.get(i), new ItemStack(item)) && counts[i] < max) {
                    int add = Math.min(left, max - counts[i]); counts[i] += add; left -= add;
                }
            for (int i = 0; i < counts.length && left > 0; i++)
                if (kinds[i] == null) { int add = Math.min(left, max); kinds[i] = item; counts[i] = add; left -= add; }
            if (left > 0) return false;
        }
        return true;
    }

    /** Why this player cannot withdraw from their kingdom's treasury now, or null. */
    public static String problem(ServerPlayer player) {
        try { check(player); return null; } catch (IllegalArgumentException e) { return e.getMessage(); }
    }
    public static net.minecraft.network.chat.Component problemComponent(ServerPlayer player) {
        try { check(player); return null; } catch (IllegalArgumentException e) { return KingdomException.of(e); }
    }
    private static Kingdom check(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        Kingdom kingdom = PoliticsService.ruledKingdom(player); // Only the ruler, with decision reputation, of an active kingdom.
        Village capital = PoliticsService.capital(level, kingdom);
        BuildingInstance hall = capital.getTownhall();
        KingdomException.check(hall != null && hall.getInventory() != null, "millenairekingdoms.treasury.no_storage",
                "The capital %s has no town hall storage.", PoliticsService.name(capital));
        BlockPos at = hall.getOrigin() == null ? capital.getCenter() : hall.getOrigin();
        KingdomException.check(PoliticsService.near(player, at) || PoliticsService.near(player, capital.getCenter()), "millenairekingdoms.treasury.too_far",
                "Stand within %s blocks of the town hall of %s to take coins from its storage.", (int) REACH, PoliticsService.name(capital));
        return kingdom;
    }

    /** Takes exactly `amount` deniers out of the capital storage into the ruler's inventory and returns it. */
    public static long withdraw(ServerPlayer player, long amount) {
        ServerLevel level = player.serverLevel();
        KingdomException.check(level.getServer().isSameThread(), "millenairekingdoms.treasury.thread", "Treasury actions run on the server thread.");
        Kingdom kingdom = check(player);
        KingdomException.check(amount > 0 && amount <= Integer.MAX_VALUE, "millenairekingdoms.treasury.amount", "Choose an amount of at least one denier.");
        BuildingInventory storage = storage(level, kingdom);
        Coins stock = stored(level, kingdom);
        KingdomException.check(stock.value() >= amount, "millenairekingdoms.treasury.not_enough",
                "The storage holds only %s.", EconomyService.format(stock.value()));
        Coins coins = change(stock, amount);
        KingdomException.check(coins != null, "millenairekingdoms.treasury.no_change",
                "The storage has no coins to pay exactly %s; it can pay %s.", EconomyService.format(amount), EconomyService.format(payable(stock, amount)));
        KingdomException.check(fits(player, coins), "millenairekingdoms.treasury.no_space",
                "Your inventory has no room for these coins; nothing was taken.");
        int[] want = {coins.gold, coins.silver, coins.bronze};
        Item[] items = {gold(), silver(), bronze()};
        int[] removed = new int[3];
        for (int i = 0; i < 3; i++) {
            removed[i] = storage.remove(level, items[i], want[i]);
            if (removed[i] < want[i]) {
                for (int j = 0; j <= i; j++) if (removed[j] > 0) storage.add(level, items[j], removed[j]);
                EconomyService.markChanged(level, storage);
                throw new KingdomException("millenairekingdoms.treasury.changed", "The storage changed; nothing was taken.");
            }
        }
        for (int i = 0; i < 3; i++) {
            int left = want[i];
            while (left > 0) {
                ItemStack stack = new ItemStack(items[i], Math.min(left, items[i].getDefaultMaxStackSize()));
                left -= stack.getCount();
                player.getInventory().add(stack);
                if (!stack.isEmpty()) {
                    // Cannot happen after fits(); never lose a coin: back into the storage, or at the player's feet.
                    int back = storage.add(level, items[i], stack.getCount());
                    if (back < stack.getCount()) player.drop(new ItemStack(items[i], stack.getCount() - back), false);
                    LOGGER.warn("[Kingdoms] {} coins did not fit into {}'s inventory and were returned.", stack.getCount(), player.getGameProfile().getName());
                }
            }
        }
        EconomyService.markChanged(level, storage);
        player.getInventory().setChanged();
        LOGGER.info("[Kingdoms] {} withdrew {} ({} gold, {} silver, {} deniers) from the storage of {} ({} left).", player.getGameProfile().getName(),
                EconomyService.format(amount), coins.gold, coins.silver, coins.bronze, kingdom.name(), EconomyService.format(stored(level, kingdom).value()));
        return amount;
    }

    public static String describe(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        var data = KingdomSavedData.get(level);
        var id = data.ruledBy(player.getUUID());
        KingdomException.check(id != null, "millenairekingdoms.treasury.not_ruler", "Only a kingdom's ruler can use its treasury.");
        Kingdom kingdom = data.kingdom(id);
        Coins stock = stored(level, kingdom);
        String problem = problem(player);
        return kingdom.name() + " treasury (capital town hall storage): " + EconomyService.format(stock.value()) + " (" + stock.gold + " gold, "
                + stock.silver + " silver, " + stock.bronze + " deniers) | daily income " + EconomyService.format(EconomyService.dailyIncome(level, data, id))
                + (data.owed(id) > 0 ? " | waiting for storage space: " + EconomyService.format(data.owed(id)) : "")
                + (problem == null ? "" : " | withdrawal not possible now: " + problem);
    }
}
