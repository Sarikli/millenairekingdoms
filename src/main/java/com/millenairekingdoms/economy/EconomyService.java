package com.millenairekingdoms.economy;

import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.kingdom.KingdomSavedData.Kingdom;
import com.millenairekingdoms.politics.PoliticsService;
import com.mojang.logging.LogUtils;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.ChestBlock;
import org.millenaire.building.BuildingInstance;
import org.millenaire.building.BuildingInventory;
import org.millenaire.item.ModItems;
import org.millenaire.item.MoneyHelper;
import org.millenaire.village.Village;
import org.slf4j.Logger;

/** Daily kingdom income paid in Millénaire's own coins into the capital town hall's existing storage.
 * No currency, treasury block or inventory of its own: earned but not yet stored deniers wait in the kingdom ledger. */
public final class EconomyService {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** One attached village yields its town hall level times 4 deniers per Minecraft day. */
    public static final int DENIERS_PER_LEVEL = 4;
    public static final long DAY_TICKS = 24000L;
    /** Upper bound per coin type and delivery, so a large backlog is stored over several attempts without int overflow. */
    private static final int MAX_COINS_PER_DELIVERY = 100_000;
    private record Coin(int value, java.util.function.Supplier<? extends Item> item) {}
    /** Millénaire 9.0.2 MoneyHelper denominations: denier d'or 4096, denier d'argent 64, denier 1. */
    private static final List<Coin> COINS = List.of(new Coin(MoneyHelper.OR_VALUE, ModItems.DENIER_OR),
            new Coin(MoneyHelper.ARGENT_VALUE, ModItems.DENIER_ARGENT), new Coin(1, ModItems.DENIER));

    private EconomyService() {}

    /** The town hall's development level as Millénaire stores it, counted from 1: BuildingInstance.getLevel() is the 0-based
     * plan level (0 = first construction). A COMPLETE town hall at plan level n stands at level n+1; one UPGRADING to plan
     * level n still stands at its completed level n; a town hall still planned or under first construction counts 0. */
    public static int centerLevel(Village village) {
        BuildingInstance townhall = village == null ? null : village.getTownhall();
        if (townhall == null) return 0;
        return switch (townhall.getStatus()) {
            case COMPLETE -> townhall.getLevel() + 1;
            case UPGRADING -> Math.max(0, townhall.getLevel());
            default -> 0;
        };
    }

    /** Income of an active, living kingdom: every member, including level-scaled capital production. Independent villages pay no one. */
    public static long dailyIncome(ServerLevel level, KingdomSavedData data, UUID kingdomId) {
        Kingdom kingdom = data.kingdom(kingdomId);
        if (kingdom == null || kingdom.fallen() || !kingdom.active()) return 0;
        long income = 0;
        for (UUID member : data.members(kingdomId)) {
            income += (long) centerLevel(PoliticsService.village(level, member)) * DENIERS_PER_LEVEL;
        }
        return income;
    }

    /** Pays day once: every active kingdom's income (rulers or NPC leaders alike) is booked with the day marker in one ledger
     * change, then stored. Returns false, paying nothing, for a day that already ran, an earlier day, or a restart. */
    public static boolean runDay(ServerLevel level, KingdomSavedData data, long day) {
        if (day <= data.day()) return false;
        Map<UUID, Long> income = new HashMap<>();
        for (Kingdom kingdom : data.kingdoms()) if (kingdom.active() && !kingdom.fallen()) income.put(kingdom.id(), dailyIncome(level, data, kingdom.id()));
        if (!data.startDay(day, income)) return false;
        income.forEach((k, v) -> { if (v > 0) LOGGER.info("Kingdom {} earned {} deniers on day {}.", data.kingdom(k).name(), v, day); });
        deliverAll(level, data);
        return true;
    }

    public static void deliverAll(ServerLevel level, KingdomSavedData data) {
        for (UUID kingdom : List.copyOf(data.owed().keySet())) deliver(level, data, kingdom);
    }

    /** Moves a kingdom's undelivered income into its capital town hall's chests as coins, largest first. Millénaire's
     * BuildingInventory.add skips unloaded chests and reports what really fit; only that is booked as delivered,
     * so a full or unloaded storage keeps the rest owed and nothing is created twice or lost. */
    public static long deliver(ServerLevel level, KingdomSavedData data, UUID kingdomId) {
        long owed = data.owed(kingdomId);
        Kingdom kingdom = data.kingdom(kingdomId);
        if (owed <= 0 || kingdom == null || kingdom.fallen()) return 0;
        BuildingInventory storage = storage(level, kingdom);
        if (storage == null) return 0;
        long remaining = owed;
        for (Coin coin : COINS) {
            int want = (int) Math.min(remaining / coin.value(), MAX_COINS_PER_DELIVERY);
            if (want > 0) remaining -= (long) storage.add(level, coin.item().get(), want) * coin.value();
        }
        long delivered = owed - remaining;
        if (delivered == 0) return 0;
        markChanged(level, storage);
        data.delivered(kingdomId, delivered);
        LOGGER.info("Kingdom {}: {} deniers placed in the capital storage ({} still owed).", kingdom.name(), delivered, remaining);
        return delivered;
    }

    /** The capital's existing Millénaire town hall storage, or null when the village or its storage is unavailable. */
    public static BuildingInventory storage(ServerLevel level, Kingdom kingdom) {
        Village capital = PoliticsService.village(level, kingdom.capital());
        BuildingInstance townhall = capital == null ? null : capital.getTownhall();
        return townhall == null ? null : townhall.getInventory();
    }

    public static String format(long deniers) {
        return MoneyHelper.formatPrice((int) Math.min(Integer.MAX_VALUE, deniers));
    }

    /** Container edits through ItemStack.grow do not mark the chest's chunk for saving by themselves. */
    public static void markChanged(ServerLevel level, BuildingInventory storage) {
        for (BlockPos pos : storage.getChestPositions()) {
            if (!level.isLoaded(pos)) continue;
            var state = level.getBlockState(pos);
            if (state.getBlock() instanceof ChestBlock chest) {
                var container = ChestBlock.getContainer(chest, state, level, pos, true);
                if (container != null) container.setChanged();
            }
        }
    }
}
