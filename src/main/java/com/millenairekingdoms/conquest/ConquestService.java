package com.millenairekingdoms.conquest;

import com.mojang.logging.LogUtils;
import com.millenairekingdoms.autonomy.Military;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.politics.DiplomacyService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.millenaire.combat.raid.RaidManager;
import org.millenaire.culture.ModCultures;
import org.millenaire.village.Village;
import org.millenaire.village.VillageId;
import org.millenaire.village.VillageSavedData;
import org.millenaire.village.VillagerRecord;
import org.slf4j.Logger;

public final class ConquestService {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Millénaire ends a raid that is still running after this many game ticks (RaidManager.frozenRaidTimedOut). */
    public static final long MILLENAIRE_RAID_TIMEOUT = 23000L;
    /** Observation only, never persisted: who went and who defended, to tell real battles from timeouts in the log. */
    private record Watch(Set<UUID> raiders, Set<UUID> defenders) {}
    private static final Map<UUID, Watch> WATCHES = new HashMap<>();
    private ConquestService() {}

    /** Operator test path (/kingdoms test conquest and the integration tests): political checks only; the military
     * preparation, cooldown and relation checks of real gameplay are deliberately skipped. Real orders go through RaidOrderService. */
    public static KingdomSavedData.Raid start(ServerLevel level, UUID attackerId, UUID targetId, UUID initiator) {
        return launch(level, attackerId, targetId, initiator, null);
    }

    /** Starts a real Millénaire raid and its conquest record. Every political rule is validated before any Millénaire side
     * effect; an order it fulfils is removed in the same snapshot as the raid record is created. */
    public static KingdomSavedData.Raid launch(ServerLevel level, UUID attackerId, UUID targetId, UUID initiator, UUID orderId) {
        require(level.getServer().isSameThread(), "Conquest must run on the server thread.");
        reconcile(level);
        var manager = VillageSavedData.get(level).getVillageManager();
        Village attacker = manager.getVillage(new VillageId(attackerId));
        Village target = manager.getVillage(new VillageId(targetId));
        require(attacker != null && target != null, "Both UUIDs must identify villages in the current dimension.");
        require(!attackerId.equals(targetId), "A village cannot raid itself.");
        require(level.getGameTime() > 0 && level.getDayTime() > 0, "World time must be positive for Millénaire raid timestamps.");
        require(!Military.busy(attacker) && !Military.busy(target), "A village is already involved in a raid or raid preparation.");
        var data = KingdomSavedData.get(level);
        require(!data.involves(attackerId) && !data.involves(targetId), "A village already has an active conquest raid.");
        data.startable(attackerId, targetId); // Validate politics and war before starting any Millénaire side effects.
        require(!target.getId().equals(attacker.getParentVillageId())
                && !attacker.getId().equals(target.getParentVillageId()), "Parent and child villages cannot target each other.");
        for (Village village : manager.getAllVillages()) {
            require(!attacker.getId().equals(village.getRaidTarget()) && !target.getId().equals(village.getRaidTarget()),
                    "Another village already has a planned or active raid against one of these villages.");
        }
        for (Village village : new Village[]{attacker, target}) {
            var type = ModCultures.getVillageType(village.getVillageTypeId());
            require(type != null && !type.loneBuilding(), "Only known, non-lone-building village types are supported.");
            require(village.getTownhall() != null, "Both villages need an existing town hall.");
            require(level.getChunkSource().hasChunk(village.getCenter().getX() >> 4, village.getCenter().getZ() >> 4)
                            && level.getWorldBorder().isWithinBounds(village.getCenter()),
                    "Both village centers must be loaded and inside the world border.");
        }
        require(Military.availableRaiders(attacker) > 0, "No living, available raider with positive military strength.");
        if (orderId != null) {
            var order = data.order(orderId);
            require(order != null && order.target().equals(targetId), "The raid order is no longer valid.");
        }
        Set<UUID> defenders = new HashSet<>();
        target.getVillagerRecords().values().forEach(r -> { if (!r.isKilled() && !r.isRaidingVillage()) defenders.add(r.getUuid()); });

        // Verified RaidCommand order. The preparation delay, relation gate and random target selection of Millénaire's
        // own raids are replaced by the addon's political rules. Millénaire performs dispatch, combat, results and cleanup.
        RaidManager.planRaid(attacker, target, level);
        RaidManager.startRaidForced(attacker, target, level);
        require(target.isUnderAttack() && target.getId().equals(attacker.getRaidTarget())
                && attacker.getRaidStart() > 0 && attacker.getRaidStartGameTime() > 0,
                "Millénaire did not start the raid; no conquest progress was awarded.");
        var raid = data.begin(attackerId, targetId, attacker.getRaidStartGameTime(), initiator, orderId);
        Set<UUID> raiders = new HashSet<>();
        attacker.getVillagerRecords().values().forEach(r -> { if (r.isAwayRaiding()) raiders.add(r.getUuid()); });
        WATCHES.put(raid.id(), new Watch(raiders, defenders));
        VillageSavedData.get(level).setDirty();
        LOGGER.info("Conquest raid {} started: {} -> {} at game time {} ({} raiders sent{})", raid.id(), attackerId, targetId,
                raid.startGameTime(), raiders.size(), orderId == null ? ", operator test" : ", order " + orderId);
        return raid;
    }

    private static boolean matches(KingdomSavedData.Raid raid, Village attacker, Village target) {
        return raid != null && attacker != null && target != null
                && raid.attacker().equals(attacker.getId().uuid()) && raid.target().equals(target.getId().uuid())
                && target.getId().equals(attacker.getRaidTarget()) && attacker.getRaidStart() > 0
                && raid.startGameTime() == attacker.getRaidStartGameTime() && target.isUnderAttack();
    }

    /** Called only at the HEAD of the real success-only looting method. */
    public static boolean suppressLoot(Village attacker, Village target, ServerLevel level) {
        var data = KingdomSavedData.get(level);
        var raid = data.raid(target.getId().uuid());
        if (!matches(raid, attacker, target)) return false;
        if (data.recordVictory(raid)) {
            int count = data.wins(raid.campaign());
            var result = data.receipt(raid.id());
            var war = raid.campaign() == null || raid.campaign().warId() == null ? null : data.war(raid.campaign().warId());
            boolean capitalFell = result.transferred() && war != null && war.status() == KingdomSavedData.WarStatus.CONQUERED;
            String victor = result.outcome() == KingdomSavedData.Outcome.WON ? data.kingdom(raid.campaign().attackerKingdom()).name() : "";
            String message = switch (result.outcome()) {
                case WON -> "Conquest " + name(target) + ": " + count + "/" + KingdomSavedData.requiredWins(raid.campaign())
                        + (capitalFell ? " — capital fell; kingdom " + data.kingdom(war.defenderKingdom()).name()
                        + " collapsed and its remaining villages now belong to " + victor + "."
                        : result.transferred() ? " — political allegiance transferred to " + victor + "." : " — first victory.");
                case LEGACY_WON -> "Legacy conquest completed; old target-only progress grants no political ownership.";
                default -> "Conquest context changed; no political progress awarded.";
            };
            notify(level, raid, message + " Loot prevented.");
            notifyKingdoms(level, data, raid, message);
        }
        return true;
    }

    /** Injected after normal result/history/NPC cleanup, immediately before clearRaid resets the key.
     * Runs for every Millénaire raid end: normal raids only add kingdom tension (stage 6.2), conquest raids also close. */
    public static void finish(Village attacker, Village target, ServerLevel level) {
        var data = KingdomSavedData.get(level);
        var raid = data.raid(target.getId().uuid());
        boolean conquest = matches(raid, attacker, target);
        // applyLooting may already have transferred the target. Preserve the war's original parties for this incident.
        try { DiplomacyService.raidIncident(level, data, attacker, target, conquest ? raid.campaign() : null); }
        catch (RuntimeException e) { LOGGER.error("[Kingdoms] Raid tension could not be applied; the raid itself is unaffected.", e); }
        if (!conquest) return;
        if (!raid.victoryRecorded()) notify(level, raid, "Conquest raid failed at " + name(target)
                + (raid.campaign() == null ? "." : ". Progress remains " + data.wins(raid.campaign())
                + "/" + KingdomSavedData.requiredWins(raid.campaign()) + "."));
        logBattle(level, raid, attacker, target);
        data.finish(raid, KingdomSavedData.Outcome.LOST);
    }

    /** Separates real battles from Millénaire's timeout end (23000 game ticks) for the report; changes nothing. */
    private static void logBattle(ServerLevel level, KingdomSavedData.Raid raid, Village attacker, Village target) {
        long ticks = level.getGameTime() - raid.startGameTime();
        Watch watch = WATCHES.remove(raid.id());
        String losses = "unknown (server restarted during the raid)";
        if (watch != null) {
            int raidersLost = 0, defendersLost = 0;
            for (UUID id : watch.raiders) { VillagerRecord r = attacker.getVillagerRecords().get(id); if (r == null || r.isKilled()) raidersLost++; }
            for (UUID id : watch.defenders) { VillagerRecord r = target.getVillagerRecords().get(id); if (r == null || r.isKilled()) defendersLost++; }
            losses = "raiders lost " + raidersLost + "/" + watch.raiders.size() + ", defenders lost " + defendersLost + "/" + watch.defenders.size();
        }
        LOGGER.info("[Kingdoms] Conquest raid {} ended by {} after {} game ticks: {} -> {} | {} | {}", raid.id(),
                ticks >= MILLENAIRE_RAID_TIMEOUT ? "MILLENAIRE TIMEOUT" : "BATTLE", ticks, name(attacker), name(target),
                raid.victoryRecorded() ? "attackers won" : "attackers lost", losses);
    }

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level && level.getGameTime() % 20 == 0) reconcile(level);
    }

    /** Drops stale addon markers only; never clears or modifies a normal Millénaire raid. */
    public static void reconcile(ServerLevel level) {
        var data = KingdomSavedData.get(level);
        if (data.activeRaids().isEmpty()) return;
        var manager = VillageSavedData.get(level).getVillageManager();
        for (var raid : data.activeRaids()) {
            Village attacker = manager.getVillage(new VillageId(raid.attacker()));
            Village target = manager.getVillage(new VillageId(raid.target()));
            if (!matches(raid, attacker, target)) {
                data.finish(raid, KingdomSavedData.Outcome.ABORTED);
                WATCHES.remove(raid.id());
                LOGGER.info("Conquest raid {} no longer matches Millénaire state; marker retired.", raid.id());
            }
        }
    }

    public static String name(Village village) {
        return village.getVillageName() == null ? village.getId().uuid().toString() : village.getVillageName();
    }

    private static void notify(ServerLevel level, KingdomSavedData.Raid raid, String text) {
        LOGGER.info("{} [raid={}]", text, raid.id());
        if (raid.initiator() != null) {
            var player = level.getServer().getPlayerList().getPlayer(raid.initiator());
            if (player != null) player.sendSystemMessage(Component.literal("[Kingdoms] " + text));
        }
    }
    /** The rulers of both kingdoms hear about a conquest victory, whoever ordered it. */
    private static void notifyKingdoms(ServerLevel level, KingdomSavedData data, KingdomSavedData.Raid raid, String text) {
        if (raid.campaign() == null) return;
        UUID attackerKingdom = raid.campaign().attackerKingdom();
        var war = raid.campaign().warId() == null ? null : data.war(raid.campaign().warId());
        for (UUID kingdom : war == null ? new UUID[]{attackerKingdom} : new UUID[]{attackerKingdom, war.defenderKingdom()}) {
            var ruler = data.ruler(kingdom);
            if (ruler == null || ruler.player().equals(raid.initiator())) continue;
            var player = level.getServer().getPlayerList().getPlayer(ruler.player());
            if (player != null) player.sendSystemMessage(Component.literal("[Kingdoms] " + text));
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
