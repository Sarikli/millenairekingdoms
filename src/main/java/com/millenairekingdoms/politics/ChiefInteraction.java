package com.millenairekingdoms.politics;

import com.millenairekingdoms.kingdom.KingdomSavedData;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.millenaire.culture.ModCultures;
import org.millenaire.entity.MillVillager;

/** Right-clicking a capital's chief adds a short chat overview with clickable options. The event is never cancelled,
 * so Millénaire's own chief screen opens exactly as before; the buttons only run /kingdom commands. */
public final class ChiefInteraction {
    private static final long COOLDOWN_TICKS = 40;
    private static final Map<UUID, Long> lastShown = new HashMap<>();
    private ChiefInteraction() {}

    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide() || event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(event.getEntity() instanceof ServerPlayer player) || !(event.getTarget() instanceof MillVillager villager)) return;
        if (!isChief(villager) || villager.getVillageId() == null) return;
        var data = KingdomSavedData.get(player.serverLevel());
        UUID village = villager.getVillageId().uuid(), kingdom = data.owner(village);
        if (kingdom == null || !data.isCapital(village)) return;
        long now = player.serverLevel().getGameTime();
        Long last = lastShown.get(player.getUUID());
        if (last != null && now >= last && now - last < COOLDOWN_TICKS) return;
        lastShown.put(player.getUUID(), now);
        try { PoliticsService.sendOverview(player, data.kingdom(kingdom)); }
        catch (IllegalArgumentException unavailable) { /* Never disturb Millénaire's own interaction. */ }
    }

    public static void forget(UUID player) { lastShown.remove(player); }

    public static boolean isChief(MillVillager villager) {
        if (villager.isChief()) return true;
        var type = villager.getVillagerTypeId() == null ? null : ModCultures.getVillagerType(villager.getVillagerTypeId());
        return type != null && type.hasTag("chief");
    }
}
