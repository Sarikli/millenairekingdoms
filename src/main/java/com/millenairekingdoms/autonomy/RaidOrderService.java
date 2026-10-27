package com.millenairekingdoms.autonomy;

import com.millenairekingdoms.autonomy.AutonomyConfig.Settings;
import com.millenairekingdoms.conquest.ConquestService;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.kingdom.KingdomSavedData.Kingdom;
import com.millenairekingdoms.kingdom.KingdomSavedData.RaidOrder;
import com.millenairekingdoms.politics.PoliticsService;
import com.millenairekingdoms.util.KingdomException;
import com.mojang.logging.LogUtils;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.millenaire.culture.ModCultures;
import org.millenaire.village.Village;
import org.slf4j.Logger;

/** The one trusted way real gameplay (player rulers and NPC kingdoms alike) starts a conquest raid. Unlike the operator test
 * command it enforces military readiness (Millénaire's own raid rules, see Military), the per-target cooldown, one raid
 * or order per kingdom and per target, and that the target village is loaded so Millénaire's NPCs really fight there.
 * Orders are stored in the kingdom data: a raid that cannot start yet waits (bounded by its expiry, never retried
 * forever), every attempt re-validates the politics, the target and the raiding village, and the order is removed in the
 * same snapshot that records the started raid, so a restart can never launch it twice. No chunk is ever force-loaded. */
public final class RaidOrderService {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** An NPC kingdom's preparation before its raid may leave (an eighth day), and every order's lifetime (one day). */
    public static final long NPC_PREPARATION = 3000L, ORDER_LIFETIME = 24000L, CHECK_INTERVAL = 50L;
    public enum Status { STARTED, RESOLVED, WAITING, DROPPED }
    public record Attempt(Status status, String reason, KingdomSavedData.Raid raid) {
        static Attempt waiting(String reason) { return new Attempt(Status.WAITING, reason, null); }
        static Attempt dropped(String reason) { return new Attempt(Status.DROPPED, reason, null); }
    }
    /** Why each waiting order did not start at its last attempt; memory only, for status displays. */
    private static final Map<UUID, String> WAITING = new HashMap<>();

    private RaidOrderService() {}

    public static String waitingReason(UUID orderId) { return WAITING.get(orderId); }

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level && level.getGameTime() % CHECK_INTERVAL == 0) process(level);
    }

    /** Attempts every due order once; returns how many raids started. */
    public static int process(ServerLevel level) {
        var data = KingdomSavedData.get(level);
        if (data.orders().isEmpty()) return 0;
        Settings s = AutonomyConfig.current();
        long now = level.getGameTime();
        int started = 0;
        for (RaidOrder order : data.orders().stream().sorted(Comparator.comparingLong(RaidOrder::issuedGameTime)).toList()) {
            if (data.order(order.id()) == null || now < order.dueGameTime()) continue;
            Attempt attempt;
            try { attempt = StrategicBattleService.attempt(level,data,order);if(attempt==null)attempt = attempt(level, data, order, s); }
            catch (RuntimeException e) {
                // Never let one order stop the world tick; it is dropped instead of being retried every 50 ticks.
                LOGGER.error("[Kingdoms] Conquest order {} failed unexpectedly; it is dropped.", order.id(), e);
                attempt = Attempt.dropped("an unexpected error (see the server log)");
            }
            if (attempt.status() == Status.STARTED || attempt.status()==Status.RESOLVED) { started++; WAITING.remove(order.id()); continue; }
            if (attempt.status() == Status.DROPPED || now >= order.expiresGameTime()) {
                data.removeOrder(order.id());
                String previous = WAITING.remove(order.id());
                String why = attempt.status() == Status.DROPPED ? attempt.reason() : "expired while waiting: " + attempt.reason();
                if ("the order expired".equals(why) && previous != null) why = "expired while waiting: " + previous;
                LOGGER.info("[Kingdoms] Conquest order {} of {} on {} dropped ({}).", order.id(), data.kingdom(order.kingdom()) == null ? order.kingdom()
                        : data.kingdom(order.kingdom()).name(), order.target(), why);
                tell(level, data, order, com.millenairekingdoms.localization.PublicText.of("[Kingdoms] The conquest raid on %s was called off: %s.", targetName(level, order), attempt.status()==Status.DROPPED?com.millenairekingdoms.localization.PublicText.error(why):com.millenairekingdoms.localization.PublicText.of("expired while waiting: %s",com.millenairekingdoms.localization.PublicText.error(attempt.reason())))
                        .withStyle(ChatFormatting.GRAY));
            } else WAITING.put(order.id(), attempt.reason());
        }
        return started;
    }

    /** One attempt to turn an order into a real Millénaire raid. */
    public static Attempt attempt(ServerLevel level, KingdomSavedData data, RaidOrder order, Settings s) {
        // Check before any village lookup or Millénaire mutation. process must not launch an expired order on its last retry.
        if (!order.equals(data.order(order.id()))) return Attempt.dropped("the order was withdrawn or replaced");
        if (level.getGameTime() >= order.expiresGameTime()) return Attempt.dropped("the order expired");
        Village target = PoliticsService.village(level, order.target());
        if (target == null) return Attempt.dropped("the target village no longer exists");
        // The order is only as good as the authority that gave it: an NPC order needs the kingdom to be still without a
        // ruler; a ruler's order needs that player to still rule, with the decision reputation (8192) at the capital.
        var ruler = data.ruler(order.kingdom());
        if (order.byNpc()) {
            if (ruler != null) return Attempt.dropped("the kingdom now has a ruler, who decides its raids");
        } else {
            if (ruler == null || !ruler.player().equals(order.issuer())) return Attempt.dropped("the ruler who ordered it no longer rules");
            Village capital = PoliticsService.village(level, data.kingdom(order.kingdom()).capital());
            if (capital == null || capital.getCombinedReputation(level, order.issuer()) < PoliticsService.DECISION_REPUTATION)
                return Attempt.waiting("the ruler's reputation at the capital is below " + PoliticsService.DECISION_REPUTATION);
        }
        KingdomSavedData.Campaign campaign;
        try { campaign = data.campaignFor(order.kingdom(), order.target()); }
        catch (IllegalArgumentException changed) { return Attempt.dropped(changed.getMessage()); }
        if (!Objects.equals(campaign.warId(), order.warId())) return Attempt.dropped("the war changed");
        if (data.wins(campaign) >= KingdomSavedData.requiredWins(campaign)) return Attempt.dropped("the campaign is complete");
        long cooldown = cooldownLeft(data, order.target(), s);
        if (cooldown > 0) return Attempt.waiting("cooldown, " + cooldown + " more day(s)");
        if (data.involves(order.target()) || Military.busy(target)) return Attempt.waiting("the target is busy with another raid");
        Village source;
        if (order.source() != null) {
            source = PoliticsService.village(level, order.source());
            if (source == null || !order.kingdom().equals(data.owner(order.source()))) return Attempt.dropped("the raiding village left the kingdom");
            String problem = Military.problem(source, target);
            if (problem != null) return Attempt.waiting(PoliticsService.name(source) + ": " + problem);
        } else {
            source = Military.bestSource(level, data, order.kingdom(), target);
            if (source == null) return Attempt.waiting("no village of the kingdom is ready");
        }
        if (!level.isPositionEntityTicking(target.getCenter())) return Attempt.waiting(PoliticsService.name(target) + " is not loaded (no player nearby)");
        if (!level.getChunkSource().hasChunk(source.getCenter().getX() >> 4, source.getCenter().getZ() >> 4))
            return Attempt.waiting(PoliticsService.name(source) + " is not loaded (no player nearby)");
        try {
            var raid = ConquestService.launch(level, source.getId().uuid(), order.target(), order.issuer(), order.id());
            LOGGER.info("[Kingdoms] Conquest order {} started raid {}: {} -> {}.", order.id(), raid.id(), PoliticsService.name(source), PoliticsService.name(target));
            tell(level, data, order, com.millenairekingdoms.localization.PublicText.of("[Kingdoms] %s sent its soldiers against %s.", PoliticsService.name(source), PoliticsService.name(target)).withStyle(ChatFormatting.GOLD));
            return new Attempt(Status.STARTED, "", raid);
        } catch (IllegalArgumentException notNow) {
            return Attempt.waiting(notNow.getMessage());
        }
    }

    /** Remaining cooldown days before the next conquest raid on target may start (0 = none). */
    public static long cooldownLeft(KingdomSavedData data, UUID target, Settings s) {
        long last = data.lastRaidDay(target);
        return last < 0 ? 0 : Math.max(0, last + s.raidCooldownDays() - Math.max(data.day(), 0));
    }

    /** A ruler orders a conquest raid (GUI or /kingdom raid). source null = the strongest ready village of the kingdom.
     * Every rule is checked on the server, for the player's real UUID; the raid starts now when possible, otherwise the
     * order waits (for example until the target village is loaded) and expires after ORDER_LIFETIME. */
    public static Attempt order(ServerPlayer player, UUID targetId, UUID sourceId) {
        ServerLevel level = player.serverLevel();
        var data = KingdomSavedData.get(level);
        ConquestService.reconcile(level);
        Kingdom kingdom = validate(player, targetId, sourceId);
        long now = level.getGameTime();
        RaidOrder order = data.addOrder(kingdom.id(), targetId, sourceId, player.getUUID(), now, now, now + ORDER_LIFETIME);
        Attempt attempt = attempt(level, data, order, AutonomyConfig.current());
        if (attempt.status() == Status.DROPPED) {
            data.removeOrder(order.id());
            throw new KingdomException("millenairekingdoms.raid.refused", "The raid cannot start: %s.", com.millenairekingdoms.localization.PublicText.error(attempt.reason()));
        }
        if (attempt.status() == Status.WAITING) WAITING.put(order.id(), attempt.reason());
        return attempt;
    }

    /** Every rule of a ruler's order, without side effects (also used to show the screen's per-target status).
     * Returns the player's kingdom or throws the reason. */
    public static Kingdom validate(ServerPlayer player, UUID targetId, UUID sourceId) {
        ServerLevel level = player.serverLevel();
        var data = KingdomSavedData.get(level);
        Kingdom kingdom = PoliticsService.ruledKingdom(player);
        Village target = PoliticsService.village(level, targetId);
        var type = target == null ? null : ModCultures.getVillageType(target.getVillageTypeId());
        KingdomException.check(type != null && !type.loneBuilding() && target.getTownhall() != null,
                "millenairekingdoms.raid.unknown_target", "Unknown target village.");
        KingdomException.check(!kingdom.id().equals(data.owner(targetId)), "millenairekingdoms.raid.own_village", "%s already belongs to your kingdom.", PoliticsService.name(target));
        var campaign = data.campaignFor(kingdom.id(), targetId); // War, capital and campaign rules, with their own messages.
        KingdomException.check(data.wins(campaign) < KingdomSavedData.requiredWins(campaign), "millenairekingdoms.raid.complete", "This conquest campaign is complete.");
        KingdomException.check(data.orderOf(kingdom.id()) == null && data.activeConquests(kingdom.id()) == 0,
                "millenairekingdoms.raid.kingdom_busy", "Your kingdom already has a conquest raid under way; wait until it ends.");
        KingdomException.check(data.orderAgainst(targetId) == null && !data.involves(targetId) && !Military.busy(target),
                "millenairekingdoms.raid.target_busy", "%s is already the target of a raid.", PoliticsService.name(target));
        long cooldown = cooldownLeft(data, targetId, AutonomyConfig.current());
        KingdomException.check(cooldown == 0, "millenairekingdoms.raid.cooldown", "%s was raided recently; wait %s more day(s).", PoliticsService.name(target), cooldown);
        if (sourceId != null) {
            Village source = PoliticsService.village(level, sourceId);
            KingdomException.check(source != null && kingdom.id().equals(data.owner(sourceId)), "millenairekingdoms.raid.foreign_source",
                    "The raiding village must belong to your kingdom.");
            String problem = Military.problem(source, target);
            KingdomException.check(problem == null, "millenairekingdoms.raid.source_not_ready", "%s is not ready: %s.", PoliticsService.name(source), problem);
        } else KingdomException.check(Military.bestSource(level, data, kingdom.id(), target) != null, "millenairekingdoms.raid.no_source",
                "No village of your kingdom is ready to raid %s (raiders at home and enough strength are needed).", PoliticsService.name(target));
        return kingdom;
    }

    /** The ruler cancels the kingdom's waiting order. */
    public static RaidOrder cancel(ServerPlayer player) {
        var data = KingdomSavedData.get(player.serverLevel());
        Kingdom kingdom = PoliticsService.ruledKingdom(player);
        RaidOrder order = data.orderOf(kingdom.id());
        KingdomException.check(order != null, "millenairekingdoms.raid.no_order", "Your kingdom has no conquest raid waiting.");
        data.removeOrder(order.id());
        WAITING.remove(order.id());
        return order;
    }

    private static String targetName(ServerLevel level, RaidOrder order) {
        Village v = PoliticsService.village(level, order.target());
        return v == null ? order.target().toString() : PoliticsService.name(v);
    }
    /** The issuing player, or for an NPC order nobody (its kingdom has no ruler by definition). */
    private static void tell(ServerLevel level, KingdomSavedData data, RaidOrder order, Component message) {
        if (order.issuer() == null) return;
        var player = level.getServer().getPlayerList().getPlayer(order.issuer());
        if (player != null) com.millenairekingdoms.localization.PublicText.send(player,message);
    }
    static List<RaidOrder> of(KingdomSavedData data, UUID kingdom) {
        return data.orders().stream().filter(o -> o.kingdom().equals(kingdom)).toList();
    }
}
