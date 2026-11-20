package com.millenairekingdoms.march;

import com.millenairekingdoms.autonomy.Military;
import com.millenairekingdoms.autonomy.OpposedDice;
import com.millenairekingdoms.autonomy.StrategicCombatConfig;
import com.millenairekingdoms.history.HistoryEvent;
import com.millenairekingdoms.history.HistoryHooks;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.localization.PublicText;
import com.millenairekingdoms.march.MarchSavedData.Kind;
import com.millenairekingdoms.march.MarchSavedData.March;
import com.millenairekingdoms.march.MarchSavedData.Status;
import com.millenairekingdoms.politics.PoliticsService;
import com.millenairekingdoms.roads.RoadRouteSolver;
import com.millenairekingdoms.roads.RoadSavedData.Waypoint;
import com.millenairekingdoms.shipping.RoyalShipmentService;
import com.millenairekingdoms.shipping.RoyalShipmentStore;
import com.mojang.logging.LogUtils;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.millenaire.culture.ModCultures;
import org.millenaire.village.Village;
import org.millenaire.village.VillagerRecord;
import org.slf4j.Logger;

/**
 * Durable, entity-free side of hybrid marches: creating caravans/armies, background step progression, offscreen
 * skirmish/ambush resolution and settlement. {@link MarchRuntime} owns the physical entities.
 */
public final class MarchService {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Shipments without a continuous road are re-checked at most this often (a road may be completed meanwhile). */
    private static final long NO_ROUTE_RETRY = 1200L;
    private static final Map<ServerLevel, Map<UUID, Long>> NO_ROUTE = new WeakHashMap<>();

    private MarchService() {}

    // ---------------------------------------------------------------- caravans

    /**
     * Ensures a dispatched, road-linked shipment has its caravan record. Idempotent and cheap once created; a shipment
     * without a continuous friendly road stays a pure background timer (no record, never a forced spawn).
     */
    public static March ensureCaravan(ServerLevel level, RoyalShipmentStore.Entry entry) {
        if (!MarchConfig.current().caravans() || entry.stage() != RoyalShipmentStore.Stage.IN_TRANSIT
                || entry.cancellationRequested())
            return null;
        var data = MarchSavedData.get(level);
        var id = entry.intent().id();
        var existing = data.march(id);
        if (existing != null)
            return existing;
        long now = level.getGameTime();
        var misses = NO_ROUTE.computeIfAbsent(level, k -> new HashMap<>());
        Long retry = misses.get(id);
        if (retry != null && now < retry)
            return null;
        var intent = entry.intent();
        RoadRouteSolver.Route route;
        try {
            route = RoadRouteSolver.shipmentRoute(
                    level, intent.kingdom(), intent.source().village(), intent.destination().village());
        } catch (RuntimeException failure) {
            route = null;
        }
        if (route == null) {
            misses.put(id, now + NO_ROUTE_RETRY);
            return null;
        }
        misses.remove(id);
        Village source = PoliticsService.village(level, intent.source().village());
        String culture = source == null || source.getCultureId() == null ? "" : source.getCultureId().toString();
        int quantity = intent.quantity();
        int mules = quantity <= 64 ? 1 : quantity <= 192 ? 2 : 3;
        var caravan = new March(id, Kind.CARAVAN, intent.kingdom(), intent.source().village(),
                intent.destination().village(), culture, route.steps(), route.stops(), now, quantity, mules,
                List.of(intent.product().toString()));
        caravan.setEtaTick(entry.due());
        long dispatched = entry.due() - intent.travelTicks();
        double elapsed = Math.clamp((double) (now - dispatched) / Math.max(1L, intent.travelTicks()), 0.0, 1.0);
        caravan.setStepIndex((int) Math.floor(elapsed * caravan.lastIndex()));
        skipPassedStops(caravan);
        data.put(caravan);
        return caravan;
    }

    /** Physically watched caravans still walking hold delivery until they reach the destination square. */
    public static boolean holdsDelivery(ServerLevel level, UUID shipment) {
        var caravan = MarchSavedData.get(level).march(shipment);
        // Bounded: a watched caravan that cannot make progress never holds goods hostage past half a day.
        return caravan != null && caravan.kind() == Kind.CARAVAN && caravan.marching()
                && level.getGameTime() < caravan.etaTick() + 12_000L && MarchRuntime.isMaterialized(level, shipment);
    }

    public static void removeCaravan(ServerLevel level, UUID shipment) {
        var data = MarchSavedData.get(level);
        var caravan = data.march(shipment);
        if (caravan == null || caravan.kind() != Kind.CARAVAN)
            return;
        MarchRuntime.despawn(level, shipment, false);
        data.remove(shipment);
    }

    /**
     * Robbery of a caravan by an enemy kingdom at war or an independent/bandit player. Settles the shipment as
     * terminal AMBUSHED (exact SEIZE of the escrow), drops the seized goods at {@code where} when that spot is loaded
     * and {@code dropGoods}, records history and tells the owning ruler. Returns true when the shipment really became
     * AMBUSHED.
     */
    public static boolean ambushCaravan(ServerLevel level, March caravan, BlockPos where, UUID ambusher,
            boolean dropGoods) {
        return ambushCaravan(level, caravan, where, ambusher, dropGoods, null, "");
    }

    public static boolean ambushCaravan(ServerLevel level, March caravan, BlockPos where, UUID ambusher,
            boolean dropGoods, UUID actorId, String actorName) {
        if (caravan == null || caravan.kind() != Kind.CARAVAN)
            return false;
        RoyalShipmentStore.Entry settled;
        try {
            settled = new RoyalShipmentService(level).ambush(caravan.id(), where);
        } catch (RuntimeException failure) {
            LOGGER.warn("[Kingdoms] Caravan {} ambush could not be settled: {}", caravan.id(), failure.getMessage());
            return false;
        }
        if (settled.stage() != RoyalShipmentStore.Stage.AMBUSHED)
            return false;
        int seized = settled.seized();
        if (dropGoods && level.hasChunkAt(where)) {
            var item = BuiltInRegistries.ITEM.getOptional(settled.intent().product()).orElse(null);
            if (item != null) {
                int left = seized, max = Math.max(1, new ItemStack(item).getMaxStackSize());
                while (left > 0) {
                    int n = Math.min(left, max);
                    level.addFreshEntity(new ItemEntity(
                            level, where.getX() + 0.5, where.getY() + 0.5, where.getZ() + 0.5, new ItemStack(item, n)));
                    left -= n;
                }
            }
            // The caravan also carries its paid transport fee as a purse; robbers take those coins too.
            for (ItemStack coins : coinStacks(settled.intent().fee()))
                level.addFreshEntity(new ItemEntity(
                        level, where.getX() + 0.5, where.getY() + 0.5, where.getZ() + 0.5, coins));
        }
        MarchRuntime.despawn(level, caravan.id(), false);
        MarchSavedData.get(level).remove(caravan.id());
        var facts = new HashMap<String, String>();
        facts.put("mode", "CARAVAN_AMBUSH");
        facts.put("product", settled.intent().product().toString());
        facts.put("seized", Integer.toString(seized));
        facts.put("feeSeized", Long.toString(settled.intent().fee()));
        facts.put("x", Integer.toString(where.getX()));
        facts.put("z", Integer.toString(where.getZ()));
        if (actorName != null && !actorName.isBlank())
            facts.put("attacker", actorName);
        String reason = ambusher != null ? "WARTIME_CARAVAN_AMBUSH" : "BANDIT_CARAVAN_AMBUSH";
        record(level, "caravan-ambush:" + caravan.id(), ambusher, caravan.kingdom(),
                List.of(caravan.source(), caravan.destination()), reason, "CARAVAN_AMBUSHED", facts, actorId,
                actorName);
        tellKingdom(level, caravan.kingdom(),
                PublicText.of("[Kingdoms] Your royal caravan from %s to %s was ambushed by %s; %s %s were seized.",
                                  name(level, caravan.source()), name(level, caravan.destination()),
                                  attackerLabel(level, ambusher, actorName), seized,
                                  settled.intent().product().getPath())
                        .withStyle(ChatFormatting.RED));
        LOGGER.info("[Kingdoms] Caravan {} ambushed at {} by {} ({} seized).", caravan.id(), where.toShortString(),
                ambusher != null ? ambusher : attackerLabel(level, null, actorName), seized);
        return true;
    }

    // ---------------------------------------------------------------- armies

    public static March army(ServerLevel level, UUID order) {
        var march = MarchSavedData.get(level).march(order);
        return march != null && march.kind() == Kind.ARMY ? march : null;
    }

    /** True while an army of this order is still on the road (its native raid must not start yet). */
    public static boolean isMarching(ServerLevel level, UUID order) {
        var march = army(level, order);
        return march != null && march.marching();
    }

    /** A march that has not arrived (still walking, or beaten on the road) is never resolved by strategic dice. */
    public static boolean blocksStrategic(ServerLevel level, UUID order) {
        var march = army(level, order);
        return march != null && march.status() != Status.ARRIVED;
    }

    /** True while the march keeps its order alive past the order's own expiry. */
    public static boolean holdsOrder(ServerLevel level, UUID order) {
        var march = army(level, order);
        return march != null && march.status() != Status.DEFEATED
                && level.getGameTime() - march.createdTick() < MarchConfig.current().marchLifetimeTicks();
    }

    /** Whether this source/target pair belongs to the physical march flow at all. */
    public static boolean marchEligible(Village source, Village target) {
        var cfg = MarchConfig.current();
        if (!cfg.armies() || source == null || target == null)
            return false;
        double dx = source.getCenter().getX() - target.getCenter().getX();
        double dz = source.getCenter().getZ() - target.getCenter().getZ();
        return Math.sqrt(dx * dx + dz * dz) >= cfg.minMarchDistance();
    }

    /**
     * Starts the bannered army of an expedition order: friendly roads to the border village facing the target, then a
     * chunk-free planned approach to the target perimeter. Returns {@code null} when no route can be planned, in which
     * case the caller keeps the established direct launch.
     */
    public static March startArmy(ServerLevel level, UUID order, UUID kingdom, Village source, Village target) {
        var existing = army(level, order);
        if (existing != null)
            return existing;
        var cfg = MarchConfig.current();
        RoadRouteSolver.Route route;
        try {
            route = RoadRouteSolver.expeditionRoute(
                    level, kingdom, source.getId().uuid(), target.getCenter(), cfg.arrivalRadius());
        } catch (RuntimeException failure) {
            LOGGER.warn("[Kingdoms] Army route for order {} unavailable: {}", order, failure.getMessage());
            route = null;
        }
        if (route == null)
            return null;
        List<String> types = new ArrayList<>();
        source.getVillagerRecords()
                .values()
                .stream()
                .filter(r -> {
                    var type = ModCultures.getVillagerType(r.getVillagerTypeId());
                    return !r.isKilled() && !r.isRaidingVillage() && !r.isAwayRaiding() && !r.isAwayHired()
                            && type != null && type.isRaider() && r.getMilitaryStrength() > 0;
                })
                .sorted(java.util.Comparator.comparing(VillagerRecord::getUuid))
                .forEach(r -> types.add(r.getVillagerTypeId().toString()));
        int squad = Math.max(1, Math.min(cfg.maxSquad(), Math.max(1, Military.availableRaiders(source))));
        String culture = source.getCultureId() == null ? "" : source.getCultureId().toString();
        var march = new March(order, Kind.ARMY, kingdom, source.getId().uuid(), target.getId().uuid(), culture,
                route.steps(), route.stops(), level.getGameTime(), Math.max(1, source.getVillageRaidingStrength()),
                squad, types);
        march.setRoadEndStep(route.roadEndStep());
        MarchSavedData.get(level).put(march);
        LOGGER.info("[Kingdoms] Army of order {} marches {} -> {} ({} road steps, {} friendly hops).", order,
                PoliticsService.name(source), PoliticsService.name(target), route.steps().size(),
                Math.max(0, route.villages().size() - 1));
        return march;
    }

    /** The native raid started (or the order ended): the army leaves the road. */
    public static void finishArmy(ServerLevel level, UUID order) {
        var data = MarchSavedData.get(level);
        var march = data.march(order);
        if (march == null || march.kind() != Kind.ARMY)
            return;
        MarchRuntime.despawn(level, order, false);
        data.remove(order);
    }

    /** Marks an army defeated (roadside skirmish or killed by enemies), records history and informs both kingdoms. */
    public static void defeat(ServerLevel level, March loser, UUID winnerKingdom, UUID winnerMarch, String mode,
            Map<String, String> extraFacts) {
        defeat(level, loser, winnerKingdom, winnerMarch, mode, extraFacts, null);
    }

    /**
     * As above; {@code fellAt} is where the last soldier fell when the army was killed physically. The expedition fee the
     * army carried then drops there as native deniers (the fee itself stays spent, so no money is created).
     */
    public static void defeat(ServerLevel level, March loser, UUID winnerKingdom, UUID winnerMarch, String mode,
            Map<String, String> extraFacts, BlockPos fellAt) {
        defeat(level, loser, winnerKingdom, winnerMarch, mode, extraFacts, fellAt, null, "");
    }

    public static void defeat(ServerLevel level, March loser, UUID winnerKingdom, UUID winnerMarch, String mode,
            Map<String, String> extraFacts, BlockPos fellAt, UUID actorId, String actorName) {
        if (loser == null || loser.kind() != Kind.ARMY || loser.status() == Status.DEFEATED)
            return;
        loser.setStatus(Status.DEFEATED, level.getGameTime());
        MarchSavedData.get(level).setDirty();
        MarchRuntime.despawn(level, loser.id(), false);
        var order = KingdomSavedData.get(level).order(loser.id());
        long fee = order == null ? 0L : order.quotedCost();
        if (fellAt != null && fee > 0 && level.hasChunkAt(fellAt))
            for (ItemStack coins : coinStacks(fee))
                level.addFreshEntity(new ItemEntity(
                        level, fellAt.getX() + 0.5, fellAt.getY() + 0.5, fellAt.getZ() + 0.5, coins));
        var facts = new HashMap<String, String>(extraFacts == null ? Map.of() : extraFacts);
        facts.put("mode", mode);
        facts.put("loserOrder", loser.id().toString());
        facts.put("feeDropped", Long.toString(fellAt != null ? fee : 0L));
        if (winnerMarch != null)
            facts.put("winnerOrder", winnerMarch.toString());
        var where = loser.position();
        facts.put("x", Integer.toString(where.x()));
        facts.put("z", Integer.toString(where.z()));
        var winner = winnerMarch == null ? null : MarchSavedData.get(level).march(winnerMarch);
        List<UUID> places = new ArrayList<>();
        if (winner != null)
            places.add(winner.source());
        places.add(loser.source());
        places.add(loser.destination());
        record(level, "roadside:" + loser.id(), winnerKingdom, loser.kingdom(), places, "ROADSIDE_SKIRMISH",
                "ATTACKER_VICTORY", facts, actorId, actorName);
        tellKingdom(level, loser.kingdom(),
                PublicText.of("[Kingdoms] Your army marching on %s was defeated on the road by %s.",
                                  name(level, loser.destination()), attackerLabel(level, winnerKingdom, actorName))
                        .withStyle(ChatFormatting.RED));
        if (winnerKingdom != null)
            tellKingdom(level, winnerKingdom,
                    PublicText.of("[Kingdoms] Your forces defeated the army of %s on the road.",
                                      kingdomName(level, loser.kingdom()))
                            .withStyle(ChatFormatting.GOLD));
        // The raid fails right away (no waiting for the next order pass): fee spent, order and march removed.
        com.millenairekingdoms.autonomy.RaidOrderService.failDefeatedArmy(level, loser.id());
    }

    // ---------------------------------------------------------------- background progression

    /**
     * Advances every non-materialized march by {@code dt} ticks. Physically materialized marches are driven by their
     * entities instead (no raw step jumps while players watch).
     */
    public static void advance(ServerLevel level, long dt) {
        advance(level, dt, null);
    }

    /** As {@link #advance(ServerLevel, long)}, limited to the given marches when {@code only} is not null. */
    public static void advance(ServerLevel level, long dt, java.util.Set<UUID> only) {
        var data = MarchSavedData.get(level);
        long now = level.getGameTime();
        double armyPerTick = MarchConfig.current().armyBlocksPerSecond() / 20.0;
        boolean dirty = false;
        for (March m : data.all()) {
            if (!m.marching() || MarchRuntime.isMaterialized(level, m.id()) || only != null && !only.contains(m.id()))
                continue;
            double rate;
            if (m.kind() == Kind.CARAVAN) {
                long left = Math.max(1L, m.etaTick() - now);
                rate = (double) m.remainingSteps() / left;
            } else
                rate = armyPerTick;
            double progress = m.progress() + rate * dt;
            int steps = (int) Math.floor(progress);
            m.setProgress(progress - steps);
            if (steps > 0) {
                m.setStepIndex(m.stepIndex() + steps);
                skipPassedStops(m);
                dirty = true;
            }
            if (m.stepIndex() >= m.lastIndex()) {
                m.setStatus(Status.ARRIVED, now);
                dirty = true;
            }
        }
        if (dirty)
            data.setDirty();
    }

    /** Moves a march to the road step nearest its physical leader (used by the runtime while materialized). */
    static void syncFromLeader(ServerLevel level, March m, double x, double z) {
        int from = Math.max(0, m.stepIndex() - 8), to = Math.min(m.lastIndex(), m.stepIndex() + 24);
        int best = m.stepIndex();
        double bestSq = Double.MAX_VALUE;
        for (int i = from; i <= to; i++) {
            Waypoint w = m.route().get(i);
            double dx = x - (w.x() + 0.5), dz = z - (w.z() + 0.5);
            double d = dx * dx + dz * dz;
            if (d < bestSq) {
                bestSq = d;
                best = i;
            }
        }
        if (best != m.stepIndex()) {
            m.setStepIndex(best);
            m.setProgress(0.0);
            MarchSavedData.get(level).setDirty();
        }
        if (m.stepIndex() >= m.lastIndex() && m.marching()) {
            m.setStatus(Status.ARRIVED, level.getGameTime());
            MarchSavedData.get(level).setDirty();
        }
    }

    private static void skipPassedStops(March m) {
        int next = m.nextStop();
        while (next < m.stops().size() && m.stops().get(next) < m.stepIndex())
            next++;
        m.setNextStop(next);
    }

    // ---------------------------------------------------------------- encounters

    /**
     * Roadside encounters for marches that are not both physically present: two warring armies within engagement
     * range clash by deterministic {@link OpposedDice}; an army that reaches an enemy caravan robs it. Encounters between
     * materialized parties are left to real entity combat in {@link MarchRuntime}.
     */
    public static int resolveEncounters(ServerLevel level) {
        return resolveEncounters(level, null);
    }

    /** As {@link #resolveEncounters(ServerLevel)}, limited to the given marches when {@code only} is not null. */
    public static int resolveEncounters(ServerLevel level, java.util.Set<UUID> only) {
        var data = MarchSavedData.get(level);
        var kingdoms = KingdomSavedData.get(level);
        int radius = MarchConfig.current().engagementRadius();
        long radiusSq = (long) radius * radius;
        int resolved = 0;
        List<March> armies = data.of(Kind.ARMY)
                                     .stream()
                                     .filter(m -> m.marching() && (only == null || only.contains(m.id())))
                                     .toList();
        for (int i = 0; i < armies.size(); i++) {
            March a = armies.get(i);
            if (!a.marching())
                continue;
            for (int j = i + 1; j < armies.size(); j++) {
                March b = armies.get(j);
                if (!b.marching() || !a.marching() || !atWar(kingdoms, a.kingdom(), b.kingdom())
                        || distSq(a.position(), b.position()) > radiusSq)
                    continue;
                if (MarchRuntime.isMaterialized(level, a.id()) && MarchRuntime.isMaterialized(level, b.id())) {
                    MarchRuntime.engage(level, a.id(), b.id());
                    continue;
                }
                skirmishByDice(level, a, b);
                resolved++;
            }
            if (!a.marching())
                continue;
            for (March c : data.of(Kind.CARAVAN)) {
                if (!c.marching() || only != null && !only.contains(c.id()) || !atWar(kingdoms, a.kingdom(), c.kingdom())
                        || distSq(a.position(), c.position()) > radiusSq)
                    continue;
                if (MarchRuntime.isMaterialized(level, a.id()) && MarchRuntime.isMaterialized(level, c.id())) {
                    MarchRuntime.engage(level, a.id(), c.id());
                    continue;
                }
                var w = c.position();
                if (ambushCaravan(level, c, new BlockPos(w.x(), level.getSeaLevel(), w.z()), a.kingdom(), false))
                    resolved++;
            }
        }
        return resolved;
    }

    /** Deterministic offscreen clash: the seed depends only on the two march identities. */
    public static March skirmishByDice(ServerLevel level, March a, March b) {
        March first = a.id().compareTo(b.id()) <= 0 ? a : b, second = first == a ? b : a;
        long seed = first.id().getMostSignificantBits() ^ Long.rotateLeft(second.id().getLeastSignificantBits(), 17)
                ^ first.createdTick() * 31L ^ second.createdTick();
        var settings = StrategicCombatConfig.settings();
        // Neither army is at home on the road: no defender home advantage.
        var fair = new OpposedDice.Settings(settings.scale(), settings.maximumModifier(), 0);
        long sa = Math.max(1, first.strength()), sb = Math.max(1, second.strength());
        var roll = OpposedDice.roll(sa, sb, RandomSource.create(seed), fair);
        March winner = roll.attackerWon() ? first : second, loser = winner == first ? second : first;
        long ws = winner == first ? sa : sb, ls = winner == first ? sb : sa;
        long remaining = Math.max(1L, ws - Math.max(0L, ls / 2));
        winner.setStrength(remaining);
        winner.setSquad(Math.max(1, (int) Math.round(winner.squad() * (double) remaining / ws)));
        MarchSavedData.get(level).setDirty();
        defeat(level, loser, winner.kingdom(), winner.id(), "ROADSIDE_DICE",
                Map.of("winnerStrength", Long.toString(ws), "loserStrength", Long.toString(ls), "winnerDie",
                        Integer.toString(winner == first ? roll.attackDie() : roll.defenseDie()), "loserDie",
                        Integer.toString(winner == first ? roll.defenseDie() : roll.attackDie()), "modifier",
                        Integer.toString(roll.relativeModifier())));
        return winner;
    }

    // ---------------------------------------------------------------- housekeeping

    /** Drops march records whose shipment/order has ended. Never touches shipment or order data itself. */
    public static void sweep(ServerLevel level) {
        var data = MarchSavedData.get(level);
        if (data.all().isEmpty())
            return;
        var kingdoms = KingdomSavedData.get(level);
        Map<UUID, RoyalShipmentStore.Entry> shipments = null;
        long now = level.getGameTime();
        for (March m : data.all()) {
            boolean stale;
            if (m.kind() == Kind.CARAVAN) {
                if (shipments == null) {
                    shipments = new HashMap<>();
                    var dir = RoyalShipmentService.directory(level);
                    if (Files.exists(dir.resolve("shipments.dat"))) {
                        try {
                            for (var e : new RoyalShipmentStore(dir).entries())
                                shipments.put(e.intent().id(), e);
                        } catch (RuntimeException unavailable) {
                            return; // Never guess: keep every caravan until the journal is readable again.
                        }
                    }
                }
                var entry = shipments.get(m.id());
                stale = entry == null || entry.cancellationRequested()
                        || entry.stage() != RoyalShipmentStore.Stage.IN_TRANSIT;
            } else {
                stale = kingdoms.order(m.id()) == null
                        || m.marching() && now - m.createdTick() >= MarchConfig.current().marchLifetimeTicks();
            }
            if (stale) {
                MarchRuntime.despawn(level, m.id(), false);
                data.remove(m.id());
            }
        }
    }

    public static void clear(net.minecraft.server.MinecraftServer server) {
        NO_ROUTE.keySet().removeIf(l -> l.getServer() == server);
    }

    // ---------------------------------------------------------------- helpers

    public static boolean atWar(KingdomSavedData data, UUID a, UUID b) {
        if (a == null || b == null || a.equals(b))
            return false;
        for (var w : data.wars())
            if (w.status() == KingdomSavedData.WarStatus.ACTIVE
                    && (w.attackerKingdom().equals(a) && w.defenderKingdom().equals(b)
                            || w.attackerKingdom().equals(b) && w.defenderKingdom().equals(a)))
                return true;
        return false;
    }

    static long distSq(Waypoint a, Waypoint b) {
        long dx = a.x() - b.x(), dz = a.z() - b.z();
        return dx * dx + dz * dz;
    }

    private static void record(ServerLevel level, String source, UUID winner, UUID loser, List<UUID> places,
            String reason, String outcome, Map<String, String> facts, UUID actorId, String actorName) {
        try {
            var data = KingdomSavedData.get(level);
            List<HistoryEvent.Realm> realms = new ArrayList<>();
            if (winner != null && data.kingdom(winner) != null && !winner.equals(loser))
                realms.add(HistoryHooks.realm(level, winner));
            if (loser != null && data.kingdom(loser) != null)
                realms.add(HistoryHooks.realm(level, loser));
            if (realms.isEmpty())
                return;
            List<HistoryEvent.Place> where = new ArrayList<>();
            for (UUID p : places)
                if (p != null && where.stream().noneMatch(x -> x.id().equals(p)) && where.size() < 3)
                    where.add(HistoryHooks.place(level, p));
            HistoryHooks.record(level, HistoryHooks.event(level, HistoryEvent.Kind.STRATEGIC_BATTLE, source, realms,
                    where, actorId, actorName == null ? "" : actorName, reason, outcome, facts));
        } catch (RuntimeException failure) {
            LOGGER.warn("[Kingdoms] March history {} not recorded: {}", source, failure.getMessage());
        }
    }

    static void tellKingdom(ServerLevel level, UUID kingdom, Component message) {
        if (kingdom == null)
            return;
        var ruler = KingdomSavedData.get(level).ruler(kingdom);
        if (ruler == null)
            return;
        var player = level.getServer().getPlayerList().getPlayer(ruler.player());
        if (player != null)
            PublicText.send(player, message);
    }

    private static String name(ServerLevel level, UUID village) {
        Village v = PoliticsService.village(level, village);
        return v == null ? "?" : PoliticsService.name(v);
    }

    private static String kingdomName(ServerLevel level, UUID kingdom) {
        var k = kingdom == null ? null : KingdomSavedData.get(level).kingdom(kingdom);
        return k == null ? "?" : k.name();
    }

    private static String attackerLabel(ServerLevel level, UUID kingdom, String actorName) {
        if (kingdom != null)
            return kingdomName(level, kingdom);
        return actorName != null && !actorName.isBlank() ? actorName : "?";
    }

    /** Native denier coins worth exactly {@code amount} (gold, then silver, then copper), in stacks of at most 64. */
    public static List<ItemStack> coinStacks(long amount) {
        List<ItemStack> out = new ArrayList<>();
        long left = Math.max(0, amount);
        long[] values = {org.millenaire.item.MoneyHelper.OR_VALUE, org.millenaire.item.MoneyHelper.ARGENT_VALUE, 1};
        var items = List.of(org.millenaire.item.ModItems.DENIER_OR.get(), org.millenaire.item.ModItems.DENIER_ARGENT.get(),
                org.millenaire.item.ModItems.DENIER.get());
        for (int i = 0; i < values.length; i++) {
            long count = left / values[i];
            left -= count * values[i];
            while (count > 0) {
                int n = (int) Math.min(64, count);
                out.add(new ItemStack(items.get(i), n));
                count -= n;
            }
        }
        return out;
    }

    static ResourceLocation culture(March m) {
        return m.culture().isEmpty() ? null : ResourceLocation.tryParse(m.culture());
    }
}
