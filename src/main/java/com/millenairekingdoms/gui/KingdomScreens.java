package com.millenairekingdoms.gui;

import com.millenairekingdoms.autonomy.AutonomyConfig;
import com.millenairekingdoms.autonomy.AutonomyService;
import com.millenairekingdoms.autonomy.Military;
import com.millenairekingdoms.autonomy.RaidOrderService;
import com.millenairekingdoms.conquest.ConquestService;
import com.millenairekingdoms.economy.EconomyService;
import com.millenairekingdoms.economy.TreasuryService;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.kingdom.KingdomSavedData.Kingdom;
import com.millenairekingdoms.network.KingdomViewPayload;
import com.millenairekingdoms.politics.PoliticsService;
import com.millenairekingdoms.util.KingdomException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import org.millenaire.culture.ModCultures;
import org.millenaire.village.Village;
import org.millenaire.village.VillageSavedData;

/** Builds the kingdom screen's snapshot for one player on the server, from the same rules the actions check again.
 * Sent only when the screen opens or after the player's own action: no periodic traffic. */
public final class KingdomScreens {
    /** Targets listed for a ruler besides the enemy's villages: independent villages nearest the kingdom. */
    public static final int MAX_TARGETS = 30;
    /** Which kingdom each player is looking at (read-only choice; never an authority). */
    private static final Map<UUID, UUID> FOCUS = new HashMap<>();

    private KingdomScreens() {}

    /** Opening the screen starts at the player's own (or nearest) kingdom again; "view" choices last only while it is open. */
    public static void open(ServerPlayer player) { FOCUS.remove(player.getUUID()); send(player, null); }
    public static void send(ServerPlayer player, Component message) {
        CompoundTag view = build(player);
        if (message != null) view.putString("message", Component.Serializer.toJson(message, player.serverLevel().registryAccess()));
        PacketDistributor.sendToPlayer(player, new KingdomViewPayload(view));
    }
    static void focus(ServerPlayer player, UUID kingdom) {
        if (kingdom == null) FOCUS.remove(player.getUUID()); else FOCUS.put(player.getUUID(), kingdom);
    }
    public static void forget(UUID player) { FOCUS.remove(player); }

    static Kingdom focusOf(ServerPlayer player, KingdomSavedData data) {
        UUID chosen = FOCUS.get(player.getUUID());
        Kingdom k = chosen == null ? null : data.kingdom(chosen);
        if (k != null && !k.fallen()) return k;
        UUID ruled = data.ruledBy(player.getUUID());
        if (ruled != null) return data.kingdom(ruled);
        Kingdom near = PoliticsService.nearbyCapital(player);
        if (near != null) return near;
        return data.kingdoms().stream().filter(x -> !x.fallen()).min(Comparator.comparing(Kingdom::name)).orElse(null);
    }

    public static CompoundTag build(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        ConquestService.reconcile(level);
        var data = KingdomSavedData.get(level);
        CompoundTag view = new CompoundTag();
        view.putLong("day", Math.max(0, data.day()));
        UUID ruled = data.ruledBy(player.getUUID());
        if (ruled != null) view.putUUID("ruled", ruled);
        Kingdom focus = focusOf(player, data);
        view.put("kingdoms", kingdoms(level, data, focus));
        view.put("nearby", nearby(player, data));
        if (focus == null) return view;
        boolean isRuler = focus.id().equals(ruled);
        view.putUUID("focus", focus.id());
        view.putBoolean("isRuler", isRuler);
        view.put("kingdom", kingdom(level, data, focus, isRuler));
        var war = data.activeWar(focus.id());
        if (war != null) view.put("war", war(level, data, war, focus));
        view.put("rights", rights(player, data, focus, isRuler));
        if (isRuler) {
            view.put("targets", targets(player, level, data, focus));
            view.put("sources", sources(level, data, focus));
            view.put("requests", requests(level, data, focus));
        }
        view.put("raids", raids(level, data, focus));
        return view;
    }

    private static ListTag kingdoms(ServerLevel level, KingdomSavedData data, Kingdom focus) {
        ListTag list = new ListTag();
        data.kingdoms().stream().filter(k -> !k.fallen()).sorted(Comparator.comparing(Kingdom::name)).forEach(k -> {
            CompoundTag e = new CompoundTag();
            e.putUUID("id", k.id()); e.putString("name", k.name()); e.putBoolean("active", k.active());
            Village capital = PoliticsService.village(level, k.capital());
            e.putString("capital", capital == null ? "?" : PoliticsService.name(capital));
            var ruler = data.ruler(k.id());
            e.putString("ruler", ruler == null ? "" : ruler.lastKnownName());
            e.putInt("members", data.members(k.id()).size());
            if (focus != null && !k.id().equals(focus.id())) {
                Integer relation = AutonomyService.relation(level, focus, k);
                if (relation != null) e.putInt("relation", relation);
                var war = data.activeWar(focus.id());
                if (war != null && (war.attackerKingdom().equals(k.id()) || war.defenderKingdom().equals(k.id())))
                    e.putString("war", war.attackerKingdom().equals(focus.id()) ? "attacking" : "defending");
                long truce = data.truceUntil(focus.id(), k.id());
                if (truce >= 0) e.putLong("truce", truce);
                if (data.activeWar(k.id()) != null) e.putBoolean("atWar", true);
            }
            list.add(e);
        });
        return list;
    }

    private static CompoundTag kingdom(ServerLevel level, KingdomSavedData data, Kingdom k, boolean isRuler) {
        CompoundTag e = new CompoundTag();
        e.putString("name", k.name()); e.putBoolean("active", k.active());
        Village capital = PoliticsService.village(level, k.capital());
        if (capital != null) {
            e.putString("capital", PoliticsService.name(capital));
            var culture = ModCultures.getCulture(capital.getCultureId());
            e.putString("culture", culture == null || culture.displayName() == null ? capital.getCultureId().getPath() : culture.displayName());
            e.putInt("x", capital.getCenter().getX()); e.putInt("y", capital.getCenter().getY()); e.putInt("z", capital.getCenter().getZ());
        }
        var ruler = data.ruler(k.id());
        e.putString("ruler", ruler == null ? "" : ruler.lastKnownName());
        ListTag members = new ListTag();
        data.members(k.id()).stream().map(id -> PoliticsService.village(level, id)).filter(v -> v != null)
                .sorted(Comparator.comparing(v -> !v.getId().uuid().equals(k.capital()))).forEach(v -> {
                    CompoundTag m = new CompoundTag();
                    m.putUUID("id", v.getId().uuid()); m.putString("name", PoliticsService.name(v));
                    m.putBoolean("capital", v.getId().uuid().equals(k.capital()));
                    int townhall = EconomyService.centerLevel(v);
                    m.putInt("level", townhall);
                    m.putLong("income", v.getId().uuid().equals(k.capital()) ? 0 : (long) townhall * EconomyService.DENIERS_PER_LEVEL);
                    members.add(m);
                });
        e.put("members", members);
        e.putLong("income", EconomyService.dailyIncome(level, data, k.id()));
        e.putLong("owed", data.owed(k.id()));
        // The treasury stock is the ruler's business; others see the income only.
        if (isRuler) {
            var coins = TreasuryService.stored(level, k);
            e.putInt("gold", coins.gold()); e.putInt("silver", coins.silver()); e.putInt("bronze", coins.bronze());
            e.putLong("coins", coins.value());
            e.putBoolean("storageLoaded", TreasuryService.loaded(level, k));
        }
        return e;
    }

    private static CompoundTag war(ServerLevel level, KingdomSavedData data, KingdomSavedData.War war, Kingdom focus) {
        CompoundTag e = new CompoundTag();
        e.putUUID("id", war.id());
        e.putString("attacker", data.kingdom(war.attackerKingdom()).name());
        e.putString("defender", data.kingdom(war.defenderKingdom()).name());
        e.putBoolean("attacking", war.attackerKingdom().equals(focus.id()));
        var clock = data.warClock(war.id());
        if (clock != null) { e.putLong("startDay", clock.startDay()); e.putLong("idleSince", clock.lastProgressDay()); }
        e.putBoolean("peaceOffered", data.peaceOffered(war.id()));
        e.putInt("capitalRequired", KingdomSavedData.capitalRequirement(war));
        e.putInt("capitalHeld", data.capitalHeld(war));
        e.putInt("initial", war.initialDefenderMembers().size());
        e.putBoolean("capitalOpen", data.capitalOpen(war));
        e.putInt("capitalWins", data.capitalWins(war));
        e.putInt("conquered", data.conquered(war.id()));
        return e;
    }

    private static CompoundTag rights(ServerPlayer player, KingdomSavedData data, Kingdom focus, boolean isRuler) {
        ServerLevel level = player.serverLevel();
        CompoundTag e = new CompoundTag();
        Village capital = PoliticsService.village(level, focus.capital());
        int reputation = capital == null ? 0 : capital.getCombinedReputation(level, player.getUUID());
        e.putInt("reputation", reputation);
        e.putInt("claimNeeds", PoliticsService.CLAIM_REPUTATION);
        e.putInt("decisionNeeds", PoliticsService.DECISION_REPUTATION);
        e.putInt("requestNeeds", PoliticsService.WAR_REQUEST_REPUTATION);
        boolean near = capital != null && PoliticsService.near(player, capital.getCenter());
        e.putBoolean("near", near);
        var ruler = data.ruler(focus.id());
        Component claim = ruler != null ? (isRuler ? why("ruled_by_you", "You already rule it.") : why("ruled_by", "It is ruled by %s.", ruler.lastKnownName()))
                : data.ruledBy(player.getUUID()) != null ? why("rule_one", "You already rule a kingdom.")
                : !focus.active() ? why("inactive", "The kingdom is inactive.")
                : !near ? why("not_near", "Stand within 64 blocks of its capital centre.")
                : reputation < PoliticsService.CLAIM_REPUTATION ? why("reputation", "Reputation %s of %s.", reputation, PoliticsService.CLAIM_REPUTATION) : null;
        put(e, "claim", claim, level);
        Component decide = null;
        if (isRuler) {
            try { PoliticsService.ruledKingdom(player); } catch (IllegalArgumentException blocked) { decide = KingdomException.of(blocked); }
        } else decide = why("ruler_only", "Only the ruler decides.");
        put(e, "decide", decide, level);
        Component request = ruler != null ? why("ruler_decides", "The ruler decides, not the NPC leader.")
                : !near ? why("not_near", "Stand within 64 blocks of its capital centre.")
                : reputation < PoliticsService.WAR_REQUEST_REPUTATION ? why("reputation", "Reputation %s of %s.", reputation, PoliticsService.WAR_REQUEST_REPUTATION) : null;
        put(e, "request", request, level);
        put(e, "withdraw", isRuler ? TreasuryService.problemComponent(player) : why("ruler_treasury", "Only the ruler takes coins from the treasury."), level);
        var order = data.orderOf(focus.id());
        if (order != null) {
            CompoundTag o = new CompoundTag();
            Village target = PoliticsService.village(level, order.target());
            o.putString("target", target == null ? order.target().toString() : PoliticsService.name(target));
            if (order.source() != null) {
                Village source = PoliticsService.village(level, order.source());
                o.putString("source", source == null ? "?" : PoliticsService.name(source));
            }
            String waiting = RaidOrderService.waitingReason(order.id());
            if (waiting != null) o.putString("waiting", waiting);
            o.putLong("expiresIn", Math.max(0, order.expiresGameTime() - level.getGameTime()));
            o.putBoolean("npc", order.byNpc());
            e.put("order", o);
        }
        return e;
    }
    private static void put(CompoundTag e, String key, Component problem, ServerLevel level) {
        if (problem != null) e.putString(key, Component.Serializer.toJson(problem, level.registryAccess())); else e.putBoolean(key + "Ok", true);
    }
    private static Component why(String key, String fallback, Object... args) {
        return Component.translatableWithFallback("millenairekingdoms.gui.why." + key, fallback, args);
    }

    /** For a ruler: the enemy's villages in the current war and the nearest independent villages, each with its status. */
    private static ListTag targets(ServerPlayer player, ServerLevel level, KingdomSavedData data, Kingdom k) {
        Village capital = PoliticsService.village(level, k.capital());
        List<Village> candidates = new ArrayList<>();
        var war = data.activeWar(k.id());
        if (war != null && war.attackerKingdom().equals(k.id()))
            data.members(war.defenderKingdom()).forEach(id -> { Village v = PoliticsService.village(level, id); if (v != null) candidates.add(v); });
        if (capital != null) {
            long radius = AutonomyService.joinRadius();
            VillageSavedData.get(level).getVillageManager().getAllVillages().stream()
                    .filter(v -> data.owner(v.getId().uuid()) == null && proper(v) && capital.getCenter().distSqr(v.getCenter()) <= radius * radius)
                    .sorted(Comparator.comparingDouble(v -> v.getCenter().distSqr(capital.getCenter()))).limit(MAX_TARGETS).forEach(candidates::add);
        }
        ListTag list = new ListTag();
        var settings = AutonomyConfig.current();
        for (Village v : candidates) {
            UUID id = v.getId().uuid();
            CompoundTag e = new CompoundTag();
            e.putUUID("id", id); e.putString("name", PoliticsService.name(v));
            UUID owner = data.owner(id);
            if (owner != null) e.putString("owner", data.kingdom(owner).name());
            e.putBoolean("capital", data.isCapital(id));
            e.putBoolean("playerVillage", v.getOwnerUUID() != null);
            if (capital != null) e.putInt("distance", (int) Math.sqrt(capital.getCenter().distSqr(v.getCenter())));
            e.putInt("defence", v.getVillageDefendingStrength());
            try {
                var campaign = data.campaignFor(k.id(), id);
                e.putInt("wins", data.wins(campaign)); e.putInt("required", KingdomSavedData.requiredWins(campaign));
            } catch (IllegalArgumentException ignored) { /* Status below explains it. */ }
            e.putLong("cooldown", RaidOrderService.cooldownLeft(data, id, settings));
            try { RaidOrderService.validate(player, id, null); e.putBoolean("ready", true); }
            catch (IllegalArgumentException blocked) { e.putString("status", Component.Serializer.toJson(KingdomException.of(blocked), level.registryAccess())); }
            list.add(e);
        }
        return list;
    }

    private static ListTag sources(ServerLevel level, KingdomSavedData data, Kingdom k) {
        ListTag list = new ListTag();
        data.members(k.id()).stream().map(id -> PoliticsService.village(level, id)).filter(v -> v != null)
                .sorted(Comparator.comparing(PoliticsService::name)).forEach(v -> {
                    CompoundTag e = new CompoundTag();
                    e.putUUID("id", v.getId().uuid()); e.putString("name", PoliticsService.name(v));
                    e.putInt("raiders", Military.availableRaiders(v)); e.putInt("strength", v.getVillageRaidingStrength());
                    e.putBoolean("carries", Military.carriesRaids(v)); e.putBoolean("busy", Military.busy(v));
                    list.add(e);
                });
        return list;
    }

    private static ListTag requests(ServerLevel level, KingdomSavedData data, Kingdom k) {
        ListTag list = new ListTag();
        data.joinRequests().entrySet().stream().filter(e -> e.getValue().kingdom().equals(k.id())).forEach(e -> {
            CompoundTag r = new CompoundTag();
            Village v = PoliticsService.village(level, e.getKey());
            r.putUUID("village", e.getKey()); r.putString("name", v == null ? e.getKey().toString() : PoliticsService.name(v));
            r.putString("from", e.getValue().playerName()); r.putBoolean("npc", e.getValue().fromNpcVillage());
            list.add(r);
        });
        return list;
    }

    private static ListTag raids(ServerLevel level, KingdomSavedData data, Kingdom k) {
        ListTag list = new ListTag();
        data.activeRaids().stream().filter(r -> r.campaign() != null && (k.id().equals(r.campaign().attackerKingdom()) || k.id().equals(data.owner(r.target()))))
                .forEach(r -> {
                    CompoundTag e = new CompoundTag();
                    Village a = PoliticsService.village(level, r.attacker()), t = PoliticsService.village(level, r.target());
                    e.putString("attacker", a == null ? "?" : PoliticsService.name(a)); e.putString("target", t == null ? "?" : PoliticsService.name(t));
                    e.putInt("wins", data.wins(r.campaign())); e.putInt("required", KingdomSavedData.requiredWins(r.campaign()));
                    e.putLong("ticks", level.getGameTime() - r.startGameTime());
                    e.putBoolean("ours", k.id().equals(r.campaign().attackerKingdom()));
                    list.add(e);
                });
        return list;
    }

    /** The independent village the player stands in, with what it would take to join a kingdom or found one. */
    private static CompoundTag nearby(ServerPlayer player, KingdomSavedData data) {
        CompoundTag e = new CompoundTag();
        Village v = PoliticsService.nearbyIndependentVillage(player);
        if (v == null) return e;
        ServerLevel level = player.serverLevel();
        e.putUUID("id", v.getId().uuid()); e.putString("name", PoliticsService.name(v));
        e.putInt("level", EconomyService.centerLevel(v));
        e.putString("owner", v.getOwnerUUID() == null ? "" : v.getOwnerName() == null ? "?" : v.getOwnerName());
        boolean own = v.isControlledBy(player.getUUID());
        e.putBoolean("own", own);
        e.putBoolean("canFound", own && EconomyService.centerLevel(v) >= PoliticsService.FOUND_LEVEL && data.ruledBy(player.getUUID()) == null);
        ListTag relations = new ListTag();
        data.kingdoms().stream().filter(k -> !k.fallen() && k.active()).sorted(Comparator.comparing(Kingdom::name)).forEach(k -> {
            Village capital = PoliticsService.village(level, k.capital());
            if (capital == null) return;
            CompoundTag r = new CompoundTag(); r.putUUID("id", k.id()); r.putString("name", k.name());
            r.putInt("relation", capital.getRelation(v.getId())); relations.add(r);
        });
        e.put("kingdoms", relations);
        return e;
    }

    private static boolean proper(Village v) {
        var type = ModCultures.getVillageType(v.getVillageTypeId());
        return type != null && !type.loneBuilding() && v.getTownhall() != null;
    }
}
