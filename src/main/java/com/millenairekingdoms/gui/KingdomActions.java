package com.millenairekingdoms.gui;

import com.millenairekingdoms.autonomy.RaidOrderService;
import com.millenairekingdoms.economy.EconomyService;
import com.millenairekingdoms.economy.TreasuryService;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.network.KingdomActionPayload;
import com.millenairekingdoms.politics.PoliticsService;
import com.millenairekingdoms.util.KingdomException;
import com.mojang.logging.LogUtils;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;

/** Server side of the kingdom screen. Each request is mapped to the same service a command uses, for the connection's
 * own ServerPlayer: the client never names who acts, and every rule (ruler, reputation, distance, war, readiness,
 * treasury) is checked again here, so a stale or forged screen grants nothing. Requests closer than MIN_TICKS apart are
 * ignored, and an answer (a fresh snapshot) is sent only to the requesting player. */
public final class KingdomActions {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final long MIN_TICKS = 4;
    public enum Action { REFRESH, VIEW, CLAIM, ABDICATE, DECLARE_WAR, REQUEST_WAR, PEACE, JOIN, ACCEPT, REFUSE, FOUND, RAID, CANCEL_RAID, WITHDRAW,
        CONSTRUCTION_SELECT, CONSTRUCTION_ORDER, CONSTRUCTION_RESUME, CONSTRUCTION_CANCEL }
    private static final Map<UUID, Long> LAST = new HashMap<>();

    private KingdomActions() {}

    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST.remove(event.getEntity().getUUID());
        KingdomScreens.forget(event.getEntity().getUUID());
        RoyalConstructionView.forget(event.getEntity().getUUID());
        com.millenairekingdoms.politics.ChiefInteraction.forget(event.getEntity().getUUID());
    }

    /** Returns the message shown to the player, or null when the request was ignored (unknown or too fast). */
    public static Component handle(ServerPlayer player, KingdomActionPayload request) {
        Action action;
        try { action = Action.valueOf(request.action()); } catch (IllegalArgumentException unknown) { return null; }
        long now = player.serverLevel().getGameTime();
        Long last = LAST.get(player.getUUID());
        if (last != null && now >= last && now - last < MIN_TICKS) return null;
        LAST.put(player.getUUID(), now);
        Component message;
        try {
            message = run(player, action, request);
        } catch (IllegalArgumentException refused) {
            message = action.name().startsWith("CONSTRUCTION_") && !(refused instanceof KingdomException)
                    ? Component.translatable("millenairekingdoms.gui.msg.construction_declined").withStyle(ChatFormatting.DARK_RED)
                    : KingdomException.of(refused).withStyle(ChatFormatting.DARK_RED);
        } catch (RuntimeException failure) {
            LOGGER.error("[Kingdoms] Kingdom screen action {} by {} failed.", action, player.getGameProfile().getName(), failure);
            message = Component.translatableWithFallback("millenairekingdoms.gui.msg.error", "The request could not finish. Refresh and inspect its saved status before retrying.").withStyle(ChatFormatting.DARK_RED);
        }
        KingdomScreens.send(player, message);
        return message;
    }

    private static Component run(ServerPlayer player, Action action, KingdomActionPayload r) {
        UUID first = r.first().orElse(null), second = r.second().orElse(null);
        var data = KingdomSavedData.get(player.serverLevel());
        return switch (action) {
            case REFRESH -> null;
            case VIEW -> { KingdomScreens.focus(player, first); yield null; }
            case CLAIM -> {
                PoliticsService.claim(player);
                UUID k = data.ruledBy(player.getUUID()); KingdomScreens.focus(player, k);
                yield ok("claimed", "You now rule %s. The NPC leader keeps running the village.", data.kingdom(k).name());
            }
            case ABDICATE -> ok("abdicated", "You gave up the rule of %s.", PoliticsService.abdicate(player).name());
            case DECLARE_WAR -> {
                var war = PoliticsService.declareWar(player, need(first));
                yield ok("war_declared", "%s declared war on %s.", data.kingdom(war.attackerKingdom()).name(), data.kingdom(war.defenderKingdom()).name());
            }
            case REQUEST_WAR -> {
                var war = PoliticsService.requestWar(player, need(first));
                yield ok("war_granted", "The leader agreed: %s declared war on %s.", data.kingdom(war.attackerKingdom()).name(), data.kingdom(war.defenderKingdom()).name());
            }
            case PEACE -> PoliticsService.peace(player) == PoliticsService.PeaceResult.MADE ? ok("peace_made", "Peace made.")
                    : ok("peace_offered", "Peace offered; the attacking ruler decides.");
            case JOIN -> PoliticsService.join(player, need(first)) == PoliticsService.JoinResult.JOINED
                    ? ok("joined", "The village joined the kingdom.") : ok("applied", "Application sent; the kingdom's ruler decides.");
            case ACCEPT -> ok("accepted", "%s joined your kingdom.", PoliticsService.name(PoliticsService.accept(player, nonNull(first))));
            case REFUSE -> { PoliticsService.refuse(player, nonNull(first)); yield ok("refused", "Application refused."); }
            case FOUND -> {
                var k = PoliticsService.found(player, r.text());
                KingdomScreens.focus(player, k.id());
                yield ok("founded", "You founded and rule %s.", k.name());
            }
            case RAID -> {
                var attempt = RaidOrderService.order(player, nonNull(first), second);
                yield attempt.status() == RaidOrderService.Status.STARTED ? ok("raid_started", "Conquest raid started.")
                        : ok("raid_waiting", "Conquest raid ordered; it starts when possible (%s).", attempt.reason());
            }
            case CANCEL_RAID -> { RaidOrderService.cancel(player); yield ok("raid_cancelled", "The waiting conquest raid was called off."); }
            case WITHDRAW -> ok("withdrew", "You took %s from the capital's town hall storage.", EconomyService.format(TreasuryService.withdraw(player, r.amount())));
            case CONSTRUCTION_SELECT -> {RoyalConstructionView.select(player,nonNull(first));yield null;}
            case CONSTRUCTION_ORDER -> {
                var order=RoyalConstructionView.order(player,nonNull(first),nonNull(second),net.minecraft.resources.ResourceLocation.parse(r.text()));
                yield constructionStatus(order);
            }
            case CONSTRUCTION_RESUME -> constructionStatus(new com.millenairekingdoms.construction.RoyalConstructionService(player.serverLevel()).retryPlayer(player,nonNull(first)));
            case CONSTRUCTION_CANCEL -> constructionStatus(new com.millenairekingdoms.construction.RoyalConstructionService(player.serverLevel()).cancelPlayer(player,nonNull(first)));
        };
    }

    private static Component constructionStatus(com.millenairekingdoms.construction.RoyalConstructionStore.Entry order) {
        return ok("construction_updated","Order status: %s.",Component.translatable("millenairekingdoms.gui.construction.stage."+order.stage().name()));
    }

    private static String need(UUID id) { return nonNull(id).toString(); }
    private static UUID nonNull(UUID id) {
        if (id == null) throw new KingdomException("millenairekingdoms.gui.msg.nothing_selected", "Select something first.");
        return id;
    }
    private static Component ok(String key, String fallback, Object... args) {
        return Component.translatableWithFallback("millenairekingdoms.gui.msg." + key, fallback, args).withStyle(ChatFormatting.DARK_GREEN);
    }
}
