package com.millenairekingdoms.test;

import static com.millenairekingdoms.test.AutonomySupport.id;
import static com.millenairekingdoms.test.AutonomySupport.kingdom;
import static com.millenairekingdoms.test.AutonomySupport.raider;
import static com.millenairekingdoms.test.AutonomySupport.remove;
import static com.millenairekingdoms.test.AutonomySupport.village;

import com.millenairekingdoms.autonomy.AutonomyConfig;
import com.millenairekingdoms.autonomy.RaidOrderService;
import com.millenairekingdoms.history.ChronicleNarrator;
import com.millenairekingdoms.history.HistoryEvent;
import com.millenairekingdoms.history.HistoryHooks;
import com.millenairekingdoms.history.HistoryStore;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.march.MarchConfig;
import com.millenairekingdoms.march.MarchRuntime;
import com.millenairekingdoms.march.MarchSavedData;
import com.millenairekingdoms.march.MarchSavedData.Kind;
import com.millenairekingdoms.march.MarchSavedData.March;
import com.millenairekingdoms.march.MarchSavedData.Status;
import com.millenairekingdoms.march.MarchService;
import com.millenairekingdoms.roads.RoadPlanner;
import com.millenairekingdoms.roads.RoadRouteSolver;
import com.millenairekingdoms.roads.RoadSavedData;
import com.millenairekingdoms.roads.RoadSavedData.RoadSegment;
import com.millenairekingdoms.roads.RoadSavedData.Waypoint;
import com.millenairekingdoms.shipping.RoyalShipmentStore.Stage;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.horse.Mule;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.entity.MillVillager;
import org.millenaire.village.Village;
import org.millenaire.village.VillageSavedData;

/**
 * Automated verification suite ({@code verifyMarches}) for hybrid physical royal caravans and bannered expedition
 * armies: multi-hop road stitching, proximity materialization (spawn within 88, discard beyond 96 / on chunk unload,
 * zero duplicates), caravan leader + chested mules walking the road, legal wartime ambush settlement, armies marching
 * to the target perimeter before the native raid, and roadside skirmishes (offscreen dice and real combat).
 */
@PrefixGameTestTemplate(false)
public final class MarchGameTests {
    private static final String NS = "millenairekingdoms_march_tests";
    private static final ResourceLocation CHERRY = ResourceLocation.parse("minecraft:cherry_log");

    private MarchGameTests() {}

    // ---------------------------------------------------------------- 1. route stitching

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void multiHopRoadRoutesStitchContinuouslyAndReverseIntermediateSegments(GameTestHelper h) {
        try (var r = RoyalShipmentSupport.realm(h, "march-route")) {
            ServerLevel level = r.level();
            var roads = RoadSavedData.get(level);
            BlockPos a = r.capital().getCenter(), b = r.source().getCenter(), c = r.target().getCenter();
            // capital -> source is stored A->B; target -> source is stored the other way round (must be reversed).
            var ab = segment(r.kingdom(), r.capital(), r.source(), List.of(wp(a.offset(6, 0, 0)), wp(b.offset(-6, 0, 3))));
            var cb = segment(r.kingdom(), r.target(), r.source(), List.of(wp(c.offset(-6, 0, 0)), wp(b.offset(6, 0, -3))));
            roads.addSegment(ab);
            roads.addSegment(cb);
            var data = KingdomSavedData.get(level);
            h.assertTrue(roads.shortestFriendlyPath(data, r.kingdom(), id(r.capital()), id(r.target()))
                                 .equals(List.of(id(r.capital()), id(r.source()), id(r.target()))),
                    "BFS finds the two-hop friendly village chain");
            var route = RoadRouteSolver.shipmentRoute(level, r.kingdom(), id(r.capital()), id(r.target()));
            h.assertTrue(route != null && RoadRouteSolver.isContinuous(route.steps()),
                    "Stitched route is one continuous 8-connected list (max(|dx|,|dz|) == 1)");
            var steps = route.steps();
            h.assertTrue(steps.getFirst().equals(wp(a)) && steps.getLast().equals(wp(c)),
                    "Route runs from the source village square to the destination square");
            h.assertTrue(route.stops().size() == 1 && steps.get(route.stops().getFirst()).equals(wp(b)),
                    "The intermediate friendly village center is a recorded stop");
            int reversedStart = steps.indexOf(wp(b.offset(6, 0, -3)));
            int reversedEnd = steps.indexOf(wp(c.offset(-6, 0, 0)));
            h.assertTrue(reversedStart > route.stops().getFirst() && reversedEnd > reversedStart,
                    "The target->source segment is walked reversed (source end first)");
            var reverse = RoadRouteSolver.shipmentRoute(level, r.kingdom(), id(r.target()), id(r.capital()));
            h.assertTrue(reverse != null && reverse.steps().getFirst().equals(wp(c))
                            && reverse.steps().getLast().equals(wp(a)) && RoadRouteSolver.isContinuous(reverse.steps()),
                    "The same network stitches the opposite direction too");
            roads.segment(cb.id()).setStatus(RoadSavedData.Status.CANCELLED);
            h.assertTrue(RoadRouteSolver.shipmentRoute(level, r.kingdom(), id(r.capital()), id(r.target())) == null,
                    "Without a continuous completed road there is no caravan route (background timer only)");
            h.succeed();
        }
    }

    // ---------------------------------------------------------------- 2. proximity materialization

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void proximitySpawnDespawnIsIdempotentAndChunkSafe(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos start = h.absolutePos(new BlockPos(1, 1, 1));
        level.getChunkAt(start);
        var march = new March(UUID.randomUUID(), Kind.CARAVAN, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "millenaire:norman", line(start, 1, 0, 300), List.of(), level.getGameTime(), 200,
                3, List.of(CHERRY.toString()));
        march.setStepIndex(0);
        var data = MarchSavedData.get(level);
        data.put(march);
        var watcher = new Stage5Support.TestPlayer(level, "MarchWatcher");
        try {
            watcher.setPos(start.getX() + 120.0, start.getY(), start.getZ());
            MarchRuntime.sync(level, List.of(watcher), java.util.Set.of(march.id()));
            h.assertTrue(MarchRuntime.countEntities(level, march.id()) == 0, "Nothing spawns beyond 88 blocks");
            watcher.setPos(start.getX() + 50.0, start.getY(), start.getZ());
            MarchRuntime.sync(level, List.of(watcher), java.util.Set.of(march.id()));
            int expected = 1 + 3;
            h.assertTrue(MarchRuntime.isMaterialized(level, march.id())
                            && MarchRuntime.countEntities(level, march.id()) == expected,
                    "Within 88 blocks: exactly one leader and three pack mules spawn");
            MarchRuntime.sync(level, List.of(watcher), java.util.Set.of(march.id()));
            MarchRuntime.sync(level, List.of(watcher), java.util.Set.of(march.id()));
            h.assertTrue(MarchRuntime.countEntities(level, march.id()) == expected,
                    "Repeated passes never spawn duplicates");
            watcher.setPos(start.getX() + 92.0, start.getY(), start.getZ());
            MarchRuntime.sync(level, List.of(watcher), java.util.Set.of(march.id()));
            h.assertTrue(MarchRuntime.countEntities(level, march.id()) == expected,
                    "Between 88 and 96 blocks the party stays (no flicker)");
            int step = march.stepIndex();
            watcher.setPos(start.getX() + 140.0, start.getY(), start.getZ());
            MarchRuntime.sync(level, List.of(watcher), java.util.Set.of(march.id()));
            h.assertTrue(MarchRuntime.countEntities(level, march.id()) == 0 && !MarchRuntime.isMaterialized(level, march.id())
                            && data.march(march.id()).marching() && data.march(march.id()).stepIndex() >= step,
                    "Beyond 96 blocks every entity is discarded and the march resumes in the background");
            watcher.setPos(start.getX() + 30.0, start.getY(), start.getZ());
            MarchRuntime.sync(level, List.of(watcher), java.util.Set.of(march.id()));
            h.assertTrue(MarchRuntime.countEntities(level, march.id()) == expected, "Re-entering range respawns once");
            var leader = MarchRuntime.entities(level, march.id()).getFirst();
            MarchRuntime.onChunkUnload(level, new ChunkPos(leader.blockPosition()));
            h.assertTrue(MarchRuntime.countEntities(level, march.id()) == 0, "Chunk unload discards the whole party");
            // A saved leftover (e.g. from a crash) is refused on join: no duplicate can enter the world.
            var stray = EntityType.MULE.create(level);
            var tag = stray.getPersistentData();
            tag.putBoolean(MarchRuntime.MARCH_TAG, true);
            tag.putUUID(MarchRuntime.MARCH_ID_TAG, march.id());
            stray.moveTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5);
            h.assertTrue(!level.addFreshEntity(stray) && MarchRuntime.countEntities(level, march.id()) == 0,
                    "Untracked tagged entities are discarded on join");
            h.succeed();
        } finally {
            MarchRuntime.despawn(level, march.id(), false);
            data.remove(march.id());
        }
    }

    // ---------------------------------------------------------------- 3. caravan traversal + ambush

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 900)
    public static void caravanLeaderAndChestedMulesWalkTheRoadThenWartimeAmbushSettlesLegally(GameTestHelper h) {
        var r = RoyalShipmentSupport.realm(h, "march-caravan");
        ServerLevel level = r.level();
        r.activate(true);
        var ownRuler = r.ruler();
        var roads = RoadSavedData.get(level);
        BlockPos b = r.source().getCenter(), c = r.target().getCenter();
        roads.addSegment(segment(r.kingdom(), r.source(), r.target(), List.of(wp(b.offset(4, 0, 0)), wp(c.offset(-4, 0, 0)))));
        var id = UUID.randomUUID();
        var service = r.service();
        service.requestPlayer(ownRuler, r.from(), r.to(), CHERRY, 40, id);
        service.requestPlayer(ownRuler, r.from(), r.to(), CHERRY, 40, id);
        var dispatched = service.observe(id);
        h.assertTrue(dispatched.stage() == Stage.IN_TRANSIT, "Shipment dispatched: " + dispatched.stage());
        service.observe(id);
        var caravan = MarchSavedData.get(level).march(id);
        h.assertTrue(caravan != null && caravan.kind() == Kind.CARAVAN && caravan.squad() == 1
                        && RoadRouteSolver.isContinuous(caravan.route()) && caravan.route().getFirst().equals(wp(b)),
                "A road-linked shipment in transit becomes a caravan (40 units -> 1 pack mule) on the stitched road");
        List<ChunkPos> forced = forceAlong(level, caravan.route(), 0, 140);
        var watcher = new Stage5Support.TestPlayer(level, "CaravanWatcher");
        watcher.setPos(b.getX() + 20.0, -60, b.getZ() + 6.0);
        MarchRuntime.watch(watcher);
        BlockPos[] ambushPos = new BlockPos[1];
        Runnable cleanup = () -> {
            if (ambushPos[0] != null)
                level.getEntitiesOfClass(ItemEntity.class, new AABB(ambushPos[0]).inflate(6)).forEach(Entity::discard);
            MarchRuntime.unwatch(watcher);
            MarchService.removeCaravan(level, id);
            forced.forEach(p -> level.setChunkForced(p.x, p.z, false));
            r.close();
        };
        int startStep = caravan.stepIndex();
        h.runAfterDelay(240, () -> {
            try {
                var party = MarchRuntime.entities(level, id);
                h.assertTrue(party.size() == 2 && MarchRuntime.countEntities(level, id) == 2,
                        "Exactly one leader and one mule, no duplicates (got " + party.size() + ")");
                h.assertTrue(party.getFirst() instanceof MillVillager leader
                                && ResourceLocation.parse("millenaire:byzantines").equals(leader.getCultureId())
                                && leader.getPersistentData().getBoolean(MarchRuntime.CARAVAN_TAG)
                                && id.equals(leader.getPersistentData().getUUID(MarchRuntime.CARAVAN_ID_TAG)),
                        "The caravan leader is a tagged MillVillager of the source culture");
                h.assertTrue(party.get(1) instanceof Mule mule && mule.hasChest(), "The pack animal is a chested mule");
                h.assertTrue(((Mule) party.get(1)).isLeashed() && ((Mule) party.get(1)).getLeashHolder() == party.getFirst(),
                        "The pack mule travels on a lead tied to the caravan leader");
                var now = MarchSavedData.get(level).march(id);
                h.assertTrue(now.stepIndex() > startStep + 4,
                        "The caravan walked along the road steps (" + startStep + " -> " + now.stepIndex() + ")");
                h.assertTrue(MarchService.holdsDelivery(level, id),
                        "A watched caravan still on the road holds delivery until it arrives");
                // Own kingdom rulers and non-combat damage cannot hurt the caravan; only foreign/bandit players or
                // enemies at war can.
                float health = ((Mule) party.get(1)).getHealth();
                party.get(1).hurt(level.damageSources().playerAttack(ownRuler), 6.0f);
                party.get(1).hurt(level.damageSources().fall(), 6.0f);
                h.assertTrue(((Mule) party.get(1)).getHealth() == health,
                        "Own ruler and environmental damage cannot hurt the caravan");

                var data = KingdomSavedData.get(level);
                var enemyCapital = RoyalShipmentSupport.village(level, "Bandit realm", r.capital().getCenter().offset(0, 0, 4096));
                try {
                    var enemy = data.create("Bandit realm " + UUID.randomUUID().toString().substring(0, 6), id(enemyCapital));
                    data.declareWar(enemy.id(), r.kingdom(), level.getGameTime());
                    var bandit = new Stage5Support.TestPlayer(level, "Bandit");
                    data.claimRule(enemy.id(), bandit.getUUID(), "Bandit", level.getGameTime());
                    var mule = (Mule) party.get(1);
                    BlockPos where = mule.blockPosition();
                    ambushPos[0] = where;
                    int repSource = r.source().getCombinedReputation(level, bandit.getUUID());
                    int repCapital = r.capital().getCombinedReputation(level, bandit.getUUID());
                    mule.hurt(level.damageSources().playerAttack(bandit), 1000.0f);
                    int penalty = MarchConfig.current().caravanKillReputation();
                    h.assertTrue(penalty > 0
                                    && r.source().getCombinedReputation(level, bandit.getUUID()) == repSource - penalty
                                    && r.capital().getCombinedReputation(level, bandit.getUUID()) == repCapital - penalty,
                            "Killing the kingdom's caravan mule costs the killer reputation at its villages and capital");
                    var entry = r.store().load(id);
                    h.assertTrue(entry.stage() == Stage.AMBUSHED && entry.seized() == 40 && entry.escrow() == 0
                                    && entry.collected() == entry.delivered() + entry.returned() + entry.seized(),
                            "Wartime robbery settles a legal terminal AMBUSHED shipment (collected == delivered + returned + seized)");
                    h.assertTrue(r.store().entries().stream().noneMatch(e -> e.stage() == Stage.RECOVERY_REQUIRED),
                            "No invariant failure or recovery quarantine");
                    int dropped = level.getEntitiesOfClass(ItemEntity.class, new AABB(where).inflate(4))
                                          .stream()
                                          .filter(e -> e.getItem().is(Items.CHERRY_LOG))
                                          .mapToInt(e -> e.getItem().getCount())
                                          .sum();
                    h.assertTrue(dropped == 40, "The seized cargo is dropped where the caravan fell (" + dropped + ")");
                    long purse = level.getEntitiesOfClass(ItemEntity.class, new AABB(where).inflate(4))
                                         .stream()
                                         .mapToLong(e -> {
                                             var st = e.getItem();
                                             long v = st.is(org.millenaire.item.ModItems.DENIER_OR.get())
                                                     ? org.millenaire.item.MoneyHelper.OR_VALUE
                                                     : st.is(org.millenaire.item.ModItems.DENIER_ARGENT.get())
                                                     ? org.millenaire.item.MoneyHelper.ARGENT_VALUE
                                                     : st.is(org.millenaire.item.ModItems.DENIER.get()) ? 1 : 0;
                                             return v * st.getCount();
                                         })
                                         .sum();
                    h.assertTrue(purse == entry.intent().fee(),
                            "The caravan's paid transport fee is carried and dropped as native deniers (" + purse + " of "
                                    + entry.intent().fee() + ")");
                    h.assertTrue(level.getEntitiesOfClass(ItemEntity.class, new AABB(where).inflate(6))
                                         .stream()
                                         .noneMatch(e -> e.getItem().is(Items.LEAD)),
                            "Cutting the mule train never drops a lead item");
                    h.assertTrue(MarchSavedData.get(level).march(id) == null && MarchRuntime.countEntities(level, id) == 0,
                            "The robbed caravan leaves the road");
                    h.assertTrue(r.service().observe(id).stage() == Stage.AMBUSHED, "Terminal state is stable");
                    h.succeed();
                } finally {
                    VillageSavedData.get(level).getVillageManager().removeVillage(enemyCapital.getId());
                }
            } finally {
                cleanup.run();
            }
        });
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 900)
    public static void independentBanditPlayerCanAmbushForeignCaravanWithoutWar(GameTestHelper h) {
        var r = RoyalShipmentSupport.realm(h, "march-bandit");
        ServerLevel level = r.level();
        r.activate(true);
        var roads = RoadSavedData.get(level);
        BlockPos b = r.source().getCenter(), c = r.target().getCenter();
        roads.addSegment(segment(r.kingdom(), r.source(), r.target(), List.of(wp(b.offset(4, 0, 0)), wp(c.offset(-4, 0, 0)))));
        var id = UUID.randomUUID();
        var service = r.service();
        service.requestNpc(r.kingdom(), r.from(), r.to(), CHERRY, 40, id);
        service.requestNpc(r.kingdom(), r.from(), r.to(), CHERRY, 40, id);
        var dispatched = service.observe(id);
        h.assertTrue(dispatched.stage() == Stage.IN_TRANSIT, "Shipment dispatched: " + dispatched.stage());
        service.observe(id);
        var caravan = MarchSavedData.get(level).march(id);
        h.assertTrue(caravan != null && caravan.kind() == Kind.CARAVAN, "Caravan record exists for road shipment");
        List<ChunkPos> forced = forceAlong(level, caravan.route(), 0, 140);
        var watcher = new Stage5Support.TestPlayer(level, "BanditWatcher");
        watcher.setPos(b.getX() + 20.0, -60, b.getZ() + 6.0);
        MarchRuntime.watch(watcher);
        BlockPos[] ambushPos = new BlockPos[1];
        Runnable cleanup = () -> {
            if (ambushPos[0] != null)
                level.getEntitiesOfClass(ItemEntity.class, new AABB(ambushPos[0]).inflate(6)).forEach(Entity::discard);
            MarchRuntime.unwatch(watcher);
            MarchService.removeCaravan(level, id);
            forced.forEach(p -> level.setChunkForced(p.x, p.z, false));
            r.close();
        };
        h.runAfterDelay(240, () -> {
            try {
                var party = MarchRuntime.entities(level, id);
                h.assertTrue(party.size() == 2 && party.get(1) instanceof Mule,
                        "Caravan materialized with leader and pack mule (got " + party.size() + ")");
                var mule = (Mule) party.get(1);
                float startHp = mule.getHealth();
                var zombie = EntityType.ZOMBIE.create(level);
                if (zombie != null) {
                    mule.hurt(level.damageSources().mobAttack(zombie), 6.0f);
                    zombie.discard();
                }
                mule.hurt(level.damageSources().fall(), 6.0f);
                h.assertTrue(mule.getHealth() == startHp,
                        "Non-player mobs and environmental damage still cannot hurt the caravan");

                var data = KingdomSavedData.get(level);
                var peacefulCapital = RoyalShipmentSupport.village(
                        level, "Peaceful neighbour", r.capital().getCenter().offset(0, 0, 4096));
                try {
                    var peaceful = data.create(
                            "Peaceful neighbour " + UUID.randomUUID().toString().substring(0, 6), id(peacefulCapital));
                    var neighbourRuler = new Stage5Support.TestPlayer(level, "PeacefulRuler");
                    data.claimRule(peaceful.id(), neighbourRuler.getUUID(), "PeacefulRuler", level.getGameTime());
                    mule.hurt(level.damageSources().playerAttack(neighbourRuler), 2.0f);
                    h.assertTrue(mule.getHealth() < startHp,
                            "A ruler of a non-warring foreign kingdom can attack the caravan");

                    var loneBandit = new Stage5Support.TestPlayer(level, "LoneBandit");
                    BlockPos where = mule.blockPosition();
                    ambushPos[0] = where;
                    int repSource = r.source().getCombinedReputation(level, loneBandit.getUUID());
                    int repDest = r.target().getCombinedReputation(level, loneBandit.getUUID());
                    int repCapital = r.capital().getCombinedReputation(level, loneBandit.getUUID());
                    mule.hurt(level.damageSources().playerAttack(loneBandit), 1000.0f);

                    int penalty = MarchConfig.current().caravanKillReputation();
                    h.assertTrue(penalty > 0
                                    && r.source().getCombinedReputation(level, loneBandit.getUUID()) == repSource - penalty
                                    && r.target().getCombinedReputation(level, loneBandit.getUUID()) == repDest - penalty
                                    && r.capital().getCombinedReputation(level, loneBandit.getUUID()) == repCapital - penalty,
                            "Banditry against a caravan deducts reputation at source, destination and capital");
                    var entry = r.store().load(id);
                    h.assertTrue(entry.stage() == Stage.AMBUSHED && entry.seized() == 40 && entry.escrow() == 0
                                    && entry.collected() == entry.delivered() + entry.returned() + entry.seized(),
                            "Bandit robbery settles a legal terminal AMBUSHED shipment");
                    h.assertTrue(r.store().entries().stream().noneMatch(e -> e.stage() == Stage.RECOVERY_REQUIRED),
                            "No invariant failure or recovery quarantine");
                    int dropped = level.getEntitiesOfClass(ItemEntity.class, new AABB(where).inflate(4))
                                          .stream()
                                          .filter(e -> e.getItem().is(Items.CHERRY_LOG))
                                          .mapToInt(e -> e.getItem().getCount())
                                          .sum();
                    h.assertTrue(dropped == 40, "Bandit ambush drops the seized cargo (" + dropped + ")");
                    long purse = level.getEntitiesOfClass(ItemEntity.class, new AABB(where).inflate(4))
                                         .stream()
                                         .mapToLong(e -> {
                                             var st = e.getItem();
                                             long v = st.is(org.millenaire.item.ModItems.DENIER_OR.get())
                                                     ? org.millenaire.item.MoneyHelper.OR_VALUE
                                                     : st.is(org.millenaire.item.ModItems.DENIER_ARGENT.get())
                                                     ? org.millenaire.item.MoneyHelper.ARGENT_VALUE
                                                     : st.is(org.millenaire.item.ModItems.DENIER.get()) ? 1 : 0;
                                             return v * st.getCount();
                                         })
                                         .sum();
                    h.assertTrue(purse == entry.intent().fee(),
                            "Bandit ambush drops the caravan's coin purse (" + purse + " of " + entry.intent().fee() + ")");
                    h.assertTrue(level.getEntitiesOfClass(ItemEntity.class, new AABB(where).inflate(6))
                                         .stream()
                                         .noneMatch(e -> e.getItem().is(Items.LEAD)),
                            "Cutting the mule train never drops a lead item");
                    h.assertTrue(MarchSavedData.get(level).march(id) == null && MarchRuntime.countEntities(level, id) == 0,
                            "The ambushed caravan leaves the road");
                    var events = HistoryHooks.store(level)
                                         .query(new HistoryStore.Query(
                                                 r.kingdom(), HistoryEvent.Kind.STRATEGIC_BATTLE, 0, Long.MAX_VALUE, null, 24))
                                         .events();
                    h.assertTrue(events.size() == 1 && "LoneBandit".equals(events.getFirst().actorName())
                                    && "BANDIT_CARAVAN_AMBUSH".equals(events.getFirst().reason()),
                            "Bandit ambush records a single-realm history event naming the player attacker");
                    String prose = ChronicleNarrator.render(events.getFirst(), r.kingdom(), "en_us").prose();
                    String victimName = data.kingdom(r.kingdom()).name();
                    h.assertTrue(prose.contains("LoneBandit") && prose.contains(victimName) && !prose.contains("?"),
                            "Chronicle prose renders player banditry cleanly: " + prose);
                    h.succeed();
                } finally {
                    VillageSavedData.get(level).getVillageManager().removeVillage(peacefulCapital.getId());
                }
            } finally {
                cleanup.run();
            }
        });
    }

    // ---------------------------------------------------------------- 4. bannered army to the perimeter

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 600)
    public static void banneredArmyMarchesToTargetPerimeterBeforeConquestLaunch(GameTestHelper h) throws Exception {
        ServerLevel level = h.getLevel();
        var kc = village(h, 1, 1, "March capital");
        var src = village(h, 3, 1, "March source");
        var tc = village(h, 200, 9, "March enemy capital");
        var target = village(h, 190, 1, "March target");
        List<ChunkPos> forced = new ArrayList<>();
        for (Village v : List.of(src, target, tc)) {
            var c = new ChunkPos(v.getCenter());
            for (int ox = -1; ox <= 1; ox++)
                for (int oz = -1; oz <= 1; oz++) {
                    var p = new ChunkPos(c.x + ox, c.z + oz);
                    if (!forced.contains(p)) {
                        level.setChunkForced(p.x, p.z, true);
                        forced.add(p);
                    }
                }
        }
        var d = KingdomSavedData.get(level);
        var k = kingdom(d, kc);
        var t = kingdom(d, tc);
        d.attach(k.id(), id(src));
        d.attach(t.id(), id(target));
        target.setOwner(UUID.randomUUID(), "Local lord");
        raider(src);
        raider(src);
        d.declareWar(k.id(), t.id(), level.getGameTime());
        long now = level.getGameTime();
        var order = d.addOrder(UUID.randomUUID(), k.id(), id(target), id(src), null, now, now, now + 24_000L, 0L, 0);
        var watcher = new Stage5Support.TestPlayer(level, "ArmyWatcher");
        Runnable cleanup = () -> {
            MarchService.finishArmy(level, order.id());
            forced.forEach(p -> level.setChunkForced(p.x, p.z, false));
            remove(h, kc, src, tc, target);
        };
        try {
            var attempt = RaidOrderService.attempt(level, d, order, AutonomyConfig.current());
            var army = MarchService.army(level, order.id());
            h.assertTrue(attempt.status() == RaidOrderService.Status.WAITING && army != null && army.marching()
                            && attempt.reason().contains("marching"),
                    "Order starts a MARCHING army instead of teleporting raiders (" + attempt.reason() + ")");
            h.assertTrue(d.raid(id(target)) == null && src.getRaidTarget() == null && src.getRaidStart() == 0
                            && !target.isUnderAttack(),
                    "The native raid and its 23000-tick timer have not started");
            var last = army.route().getLast();
            double dx = last.x() - target.getCenter().getX(), dz = last.z() - target.getCenter().getZ();
            h.assertTrue(RoadRouteSolver.isContinuous(army.route())
                            && Math.sqrt(dx * dx + dz * dz) <= MarchConfig.current().arrivalRadius() + 1,
                    "The planned approach is continuous and ends at the target perimeter");
            watcher.setPos(army.position().x() + 10.0, -60, army.position().z());
            MarchRuntime.sync(level, List.of(watcher), java.util.Set.of(order.id()));
            var party = MarchRuntime.entities(level, order.id());
            h.assertTrue(party.size() == 2, "Squad of the source's two available raiders (" + party.size() + ")");
            var bearer = party.getFirst();
            h.assertTrue(bearer.getPersistentData().getBoolean(MarchRuntime.BEARER_TAG)
                            && bearer instanceof MillVillager mv
                            && mv.getMainHandItem().getItem() instanceof BannerItem,
                    "The squad is led by a standard-bearer carrying the native village banner (tag="
                            + bearer.getPersistentData().getBoolean(MarchRuntime.BEARER_TAG) + ", head="
                            + ((MillVillager) bearer).getItemBySlot(EquipmentSlot.HEAD) + ", hand="
                            + ((MillVillager) bearer).getMainHandItem() + ")");
            watcher.setPos(army.position().x() + 400.0, -60, army.position().z());
            MarchRuntime.sync(level, List.of(watcher), java.util.Set.of(order.id()));
            h.assertTrue(MarchRuntime.countEntities(level, order.id()) == 0, "Out of range the army is background-only");
            MarchService.advance(level, 40, java.util.Set.of(order.id()));
            h.assertTrue(RaidOrderService.attempt(level, d, order, AutonomyConfig.current()).status()
                                 == RaidOrderService.Status.WAITING
                            && d.raid(id(target)) == null,
                    "Mid-march attempts keep waiting");
            MarchService.advance(level, 200_000, java.util.Set.of(order.id()));
            h.assertTrue(army.status() == Status.ARRIVED, "The army reaches the perimeter in the background");
        } catch (RuntimeException | AssertionError failure) {
            cleanup.run();
            throw failure;
        }
        boolean[] done = {false};
        h.onEachTick(() -> {
            if (done[0] || h.getTick() < 5)
                return;
            if (!level.isPositionEntityTicking(target.getCenter())) {
                if (h.getTick() > 200) {
                    done[0] = true;
                    cleanup.run();
                    h.fail("Target never became entity-ticking");
                }
                return;
            }
            done[0] = true;
            try {
                RaidOrderService.Attempt launchAttempt = d.order(order.id()) != null
                        ? RaidOrderService.attempt(level, d, order, AutonomyConfig.current()) : null;
                h.assertTrue(d.order(order.id()) == null && d.raid(id(target)) != null
                                && target.getId().equals(src.getRaidTarget()) && target.isUnderAttack(),
                        "On arrival the native Millénaire raid launches through ConquestService.launch ("
                                + (launchAttempt == null ? "none" : launchAttempt.status() + ": " + launchAttempt.reason())
                                + ")");
                h.assertTrue(MarchService.army(level, order.id()) == null, "The army leaves the road once the raid starts");
                h.succeed();
            } finally {
                cleanup.run();
            }
        });
    }

    // ---------------------------------------------------------------- 5. roadside skirmishes

    /** Two warring kingdoms, each with a real waiting order, so the loser's order can fail cleanly. */
    private static March army(ServerLevel level, KingdomSavedData.RaidOrder order, Village source, Village dest,
            List<Waypoint> route, long strength, int squad) {
        String raider = AutonomySupport.raiderType().id().toString();
        var m = new March(order.id(), Kind.ARMY, order.kingdom(), id(source), id(dest), "millenaire:byzantines", route,
                List.of(), 1000L, strength, squad, List.of(raider));
        MarchSavedData.get(level).put(m);
        return m;
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void offscreenRoadsideSkirmishIsDeterministicAndFailsTheLosersOrder(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        var w = warOf(h, "skirmish-dice");
        try {
            BlockPos mid = new BlockPos(w.ca().getCenter().getX() + 300, -60, 90_200);
            March a = army(level, w.orderA(), w.ca(), w.cb(), line(mid.offset(-100, 0, 0), 1, 0, 200), 12, 4);
            March b = army(level, w.orderB(), w.cb(), w.free(), line(mid.offset(100, 0, 0), -1, 0, 200), 12, 4);
            a.setStepIndex(96);
            b.setStepIndex(96);
            h.assertTrue(MarchService.resolveEncounters(level, java.util.Set.of(a.id(), b.id())) == 1, "Exactly one clash between the two armies");
            March winner = a.status() == Status.DEFEATED ? b : a, loser = winner == a ? b : a;
            h.assertTrue(loser.status() == Status.DEFEATED && winner.marching() && winner.strength() < 12
                            && winner.strength() >= 1,
                    "Loser is defeated; the victor marches on with reduced strength");
            var loserOrder = loser == a ? w.orderA() : w.orderB();
            h.assertTrue(w.data().order(loserOrder.id()) == null && MarchService.army(level, loserOrder.id()) == null,
                    "The defeated army's expedition order fails immediately and cleanly");
            UUID firstWinner = winner.id();
            // Identical marches always produce the identical outcome.
            MarchSavedData.get(level).remove(a.id());
            MarchSavedData.get(level).remove(b.id());
            March a2 = new March(a.id(), Kind.ARMY, a.kingdom(), a.source(), a.destination(), a.culture(), a.route(),
                    List.of(), a.createdTick(), 12, 4, a.unitTypes());
            March b2 = new March(b.id(), Kind.ARMY, b.kingdom(), b.source(), b.destination(), b.culture(), b.route(),
                    List.of(), b.createdTick(), 12, 4, b.unitTypes());
            MarchSavedData.get(level).put(a2);
            MarchSavedData.get(level).put(b2);
            h.assertTrue(MarchService.skirmishByDice(level, a2, b2).id().equals(firstWinner),
                    "OpposedDice resolution is deterministic for the same two marches");
            h.succeed();
        } finally {
            MarchService.finishArmy(level, w.orderA().id());
            MarchService.finishArmy(level, w.orderB().id());
            w.cleanup(level);
        }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 1200)
    public static void materializedWarringArmiesFightOnTheSpotUntilOneSideWins(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        var w = warOf(h, "skirmish-live");
        BlockPos mid = h.absolutePos(new BlockPos(1, 1, 1));
        int ground = mid.getY();
        March a = army(level, w.orderA(), w.ca(), w.cb(), line(mid.offset(-40, 0, 0), 1, 0, 80), 10, 2);
        March b = army(level, w.orderB(), w.cb(), w.free(), line(mid.offset(40, 0, 0), -1, 0, 80), 10, 2);
        a.setStepIndex(36);
        b.setStepIndex(36);
        List<ChunkPos> forced = forceAlong(level, a.route(), 0, a.route().size());
        var watcher = new Stage5Support.TestPlayer(level, "BattleWatcher");
        watcher.setPos(mid.getX() + 0.5, ground, mid.getZ() + 12.0);
        MarchRuntime.watch(watcher);
        Runnable cleanup = () -> {
            MarchRuntime.unwatch(watcher);
            MarchService.finishArmy(level, w.orderA().id());
            MarchService.finishArmy(level, w.orderB().id());
            forced.forEach(p -> level.setChunkForced(p.x, p.z, false));
            w.cleanup(level);
        };
        h.onEachTick(() -> {
            if (MarchRuntime.isMaterialized(level, a.id()) && MarchRuntime.isMaterialized(level, b.id()))
                SEEN[0]++;
            if (MarchRuntime.engagedWith(level, a.id()) != null)
                SEEN[1]++;
            for (var e : MarchRuntime.entities(level, a.id()))
                if (e instanceof net.minecraft.world.entity.LivingEntity le)
                    SEEN[2] = Math.min(SEEN[2] == 0 ? 1000 : SEEN[2], (int) le.getHealth());
            if (MarchRuntime.entities(level, a.id()).size() == 2 && MarchRuntime.entities(level, b.id()).size() == 2)
                SEEN[3] = (int) MarchRuntime.entities(level, a.id()).getFirst().distanceTo(
                        MarchRuntime.entities(level, b.id()).getFirst());
            if (a.status() == Status.DEFEATED || b.status() == Status.DEFEATED) {
                try {
                    March winner = a.status() == Status.DEFEATED ? b : a;
                    h.assertTrue(winner.status() != Status.DEFEATED,
                            "Exactly one side loses the roadside battle");
                    March loser = winner == a ? b : a;
                    h.assertTrue(MarchRuntime.countEntities(level, loser.id()) == 0,
                            "The defeated squad leaves the road");
                    h.succeed();
                } finally {
                    cleanup.run();
                }
            } else if (h.getTick() > 1100) {
                cleanup.run();
                var md = MarchSavedData.get(level);
                h.fail("Materialized armies never finished their fight (a=" + MarchRuntime.countEntities(level, a.id())
                        + ", b=" + MarchRuntime.countEntities(level, b.id()) + ", recA=" + (md.march(a.id()) != null)
                        + ", recB=" + (md.march(b.id()) != null) + ", st=" + a.status() + "/" + b.status() + ", idx="
                        + a.stepIndex() + "/" + b.stepIndex() + ", orders=" + (w.data().order(w.orderA().id()) != null)
                        + "/" + (w.data().order(w.orderB().id()) != null) + ", war="
                        + MarchService.atWar(w.data(), a.kingdom(), b.kingdom()) + ", time=" + level.getGameTime()
                        + ", seen=" + java.util.Arrays.toString(SEEN) + ")");
            }
        });
    }

    // ---------------------------------------------------------------- 5c. a player attacks an army

    /** An enemy ruler for tests: never actually takes damage, but records every blow a marching party lands. */
    static final class Raider extends net.neoforged.neoforge.common.util.FakePlayer {
        int blows;
        Raider(ServerLevel level, String name) {
            super(level, new com.mojang.authlib.GameProfile(UUID.randomUUID(), name));
        }
        @Override
        public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
            if (MarchRuntime.isMarchEntity(source.getEntity()))
                blows++;
            return false;
        }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 800)
    public static void attackedArmyFightsBackCostsReputationAndItsDeathFailsTheRaidAtOnce(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        int x = (RoyalShipmentSupport.read() ? 960_000 : 860_000) + (++nextWar) * 4096;
        var ca = RoyalShipmentSupport.village(level, "Retaliation A", new BlockPos(x, -50, 95_000));
        var cb = RoyalShipmentSupport.village(level, "Retaliation B", new BlockPos(x + 600, -50, 95_000));
        var d = KingdomSavedData.get(level);
        var ka = d.create("Retaliation A " + UUID.randomUUID().toString().substring(0, 6), id(ca)).id();
        var kb = d.create("Retaliation B " + UUID.randomUUID().toString().substring(0, 6), id(cb)).id();
        d.declareWar(ka, kb, level.getGameTime());
        var treasury = (net.minecraft.world.level.block.entity.ChestBlockEntity) level.getBlockEntity(
                ca.getTownhall().getInventory().getChestPositions().getFirst());
        treasury.setItem(0, new net.minecraft.world.item.ItemStack(org.millenaire.item.ModItems.DENIER_ARGENT.get(), 2));
        var orderId = UUID.randomUUID();
        long fee = 100L, now = level.getGameTime();
        new com.millenairekingdoms.economy.RoyalPaymentService(level).payNpc(
                ka, orderId, com.millenairekingdoms.economy.RoyalPaymentService.Purpose.EXPEDITION, orderId, fee);
        var order = d.addOrder(orderId, ka, id(cb), id(ca), null, now, now, now + 24_000L, fee, 1);
        BlockPos start = h.absolutePos(new BlockPos(1, 1, 1));
        March army = army(level, order, ca, cb, line(start, 1, 0, 60), 10, 2);
        army.setStepIndex(10);
        List<ChunkPos> forced = forceAlong(level, army.route(), 0, army.route().size());
        var bandit = new Raider(level, "Retaliator");
        d.claimRule(kb, bandit.getUUID(), "Retaliator", now);
        var here = army.position();
        int groundY = level.getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, here.x() + 3, here.z());
        bandit.setPos(here.x() + 3.5, groundY, here.z() + 0.5);
        MarchRuntime.watch(bandit);
        int[] stage = {0};
        int[] rep = new int[2];
        BlockPos[] fell = new BlockPos[1];
        Runnable cleanup = () -> {
            MarchRuntime.unwatch(bandit);
            MarchService.finishArmy(level, orderId);
            d.removeOrder(orderId);
            forced.forEach(p -> level.setChunkForced(p.x, p.z, false));
            var mgr = VillageSavedData.get(level).getVillageManager();
            mgr.removeVillage(ca.getId());
            mgr.removeVillage(cb.getId());
        };
        h.onEachTick(() -> {
            try {
                var party = MarchRuntime.entities(level, orderId);
                if (stage[0] == 0 && party.size() == 2) {
                    Entity lead = party.getFirst();
                    bandit.setPos(lead.getX() + 2.0, lead.getY(), lead.getZ());
                    rep[0] = ca.getCombinedReputation(level, bandit.getUUID());
                    lead.hurt(level.damageSources().playerAttack(bandit), 1.0f);
                    lead.hurt(level.damageSources().playerAttack(bandit), 1.0f);
                    int penalty = MarchConfig.current().armyAttackReputation();
                    h.assertTrue(penalty == 5096 && ca.getCombinedReputation(level, bandit.getUUID()) == rep[0] - penalty,
                            "Attacking a marching army costs 5096 reputation, once per player and army");
                    stage[0] = 1;
                } else if (stage[0] == 1 && bandit.blows > 0) {
                    // The squad turned on its attacker. Now cut it down: losses are permanent.
                    party.get(1).hurt(level.damageSources().playerAttack(bandit), 1000.0f);
                    h.assertTrue(army.squad() == 1 && army.strength() == 5,
                            "A fallen soldier stays dead (squad " + army.squad() + ", strength " + army.strength() + ")");
                    MarchRuntime.despawn(level, orderId, true);
                    stage[0] = 2;
                } else if (stage[0] == 2 && party.size() == 1) {
                    h.assertTrue(MarchRuntime.countEntities(level, orderId) == 1,
                            "The re-formed party brings back only the survivor");
                    fell[0] = party.getFirst().blockPosition();
                    party.getFirst().hurt(level.damageSources().playerAttack(bandit), 1000.0f);
                    h.assertTrue(army.status() == Status.DEFEATED && d.order(orderId) == null
                                    && MarchService.army(level, orderId) == null && d.raid(id(cb)) == null,
                            "Killing the whole army fails its raid immediately, with no native raid launched");
                    long purse = level.getEntitiesOfClass(ItemEntity.class, new AABB(fell[0]).inflate(4))
                                         .stream()
                                         .mapToLong(e -> {
                                             var st = e.getItem();
                                             long v = st.is(org.millenaire.item.ModItems.DENIER_OR.get())
                                                     ? org.millenaire.item.MoneyHelper.OR_VALUE
                                                     : st.is(org.millenaire.item.ModItems.DENIER_ARGENT.get())
                                                     ? org.millenaire.item.MoneyHelper.ARGENT_VALUE
                                                     : st.is(org.millenaire.item.ModItems.DENIER.get()) ? 1 : 0;
                                             return v * st.getCount();
                                         })
                                         .sum();
                    h.assertTrue(purse == fee, "The raid order's paid fee drops where the army fell (" + purse + ")");
                    h.assertTrue(new com.millenairekingdoms.economy.RoyalPaymentService(level)
                                         .inspectExpedition(orderId, ka, id(ca), null, fee)
                                         .state()
                                    == com.millenairekingdoms.economy.RoyalPaymentJournal.State.COMMITTED,
                            "The expedition fee stays spent (never refunded)");
                    cleanup.run();
                    h.succeed();
                } else if (h.getTick() > 760) {
                    cleanup.run();
                    h.fail("Retaliation scenario stalled at stage " + stage[0] + " (blows=" + bandit.blows + ", party="
                            + party.size() + ")");
                }
            } catch (RuntimeException | AssertionError failure) {
                cleanup.run();
                throw failure;
            }
        });
    }

    // ---------------------------------------------------------------- 6. restart safety

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void restartNeverDuplicatesEntitiesAndMarchesRoundTrip(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos start = h.absolutePos(new BlockPos(2, 1, 2));
        var march = new March(UUID.randomUUID(), Kind.ARMY, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "millenaire:norman", line(start, 0, 1, 60), List.of(5, 20), level.getGameTime(), 9, 3,
                List.of(AutonomySupport.raiderType().id().toString()));
        march.setStepIndex(4);
        var data = MarchSavedData.get(level);
        data.put(march);
        try {
            var saved = data.save(new CompoundTag(), level.registryAccess());
            var reloaded = MarchSavedData.load(saved, level.registryAccess()).march(march.id());
            h.assertTrue(reloaded != null && reloaded.route().equals(march.route())
                            && reloaded.stepIndex() == 4 && reloaded.stops().equals(List.of(5, 20))
                            && reloaded.kind() == Kind.ARMY && reloaded.strength() == 9 && reloaded.squad() == 3
                            && saved.getInt("format") == MarchSavedData.FORMAT,
                    "marches.dat round-trips every field in its own versioned format");
            var watcher = new Stage5Support.TestPlayer(level, "RestartWatcher");
            watcher.setPos(start.getX() + 5.0, start.getY(), start.getZ());
            MarchRuntime.sync(level, List.of(watcher), java.util.Set.of(march.id()));
            var party = MarchRuntime.entities(level, march.id());
            h.assertTrue(party.size() == 3, "Three soldiers materialize");
            // Simulate the chunk being saved and reloaded after a crash: the old entity data comes back.
            List<CompoundTag> disk = new ArrayList<>();
            for (Entity e : party) {
                var t = new CompoundTag();
                e.save(t);
                disk.add(t);
            }
            MarchRuntime.despawn(level, march.id(), true);
            int back = 0;
            for (var t : disk) {
                var e = EntityType.create(t, level).orElse(null);
                if (e != null && level.addFreshEntity(e))
                    back++;
            }
            h.assertTrue(back == 0 && MarchRuntime.countEntities(level, march.id()) == 0,
                    "Entities restored from disk are refused: a restart can never duplicate a party");
            MarchRuntime.sync(level, List.of(watcher), java.util.Set.of(march.id()));
            h.assertTrue(MarchRuntime.countEntities(level, march.id()) == 3,
                    "The party re-forms exactly once from its saved road step");
            h.succeed();
        } finally {
            MarchRuntime.despawn(level, march.id(), false);
            data.remove(march.id());
        }
    }

    // ---------------------------------------------------------------- helpers

    private record WarFixture(KingdomSavedData data, Village ca, Village cb, Village free,
            KingdomSavedData.RaidOrder orderA, KingdomSavedData.RaidOrder orderB) {
        void cleanup(ServerLevel level) {
            data.removeOrder(orderA.id());
            data.removeOrder(orderB.id());
            var mgr = VillageSavedData.get(level).getVillageManager();
            mgr.removeVillage(ca.getId());
            mgr.removeVillage(cb.getId());
            mgr.removeVillage(free.getId());
        }
    }

    private static int nextWar = 0;
    private static final int[] SEEN = new int[4];

    private static synchronized WarFixture warOf(GameTestHelper h, String name) {
        ServerLevel level = h.getLevel();
        int x = (RoyalShipmentSupport.read() ? 900_000 : 800_000) + (++nextWar) * 4096;
        var ca = RoyalShipmentSupport.village(level, name + " A", new BlockPos(x, -50, 90_000));
        var cb = RoyalShipmentSupport.village(level, name + " B", new BlockPos(x + 600, -50, 90_000));
        var free = RoyalShipmentSupport.village(level, name + " free", new BlockPos(x + 1200, -50, 90_000));
        var d = KingdomSavedData.get(level);
        var ka = d.create(name + " A " + UUID.randomUUID().toString().substring(0, 6), id(ca)).id();
        var kb = d.create(name + " B " + UUID.randomUUID().toString().substring(0, 6), id(cb)).id();
        d.declareWar(ka, kb, level.getGameTime());
        long now = level.getGameTime();
        var oa = d.addOrder(UUID.randomUUID(), ka, id(cb), id(ca), null, now, now, now + 24_000L, 0L, 0);
        var ob = d.addOrder(UUID.randomUUID(), kb, id(free), id(cb), null, now, now, now + 24_000L, 0L, 0);
        return new WarFixture(d, ca, cb, free, oa, ob);
    }

    private static RoadSegment segment(UUID kingdom, Village a, Village b, List<Waypoint> waypoints) {
        return new RoadSegment(UUID.randomUUID(), kingdom, id(a), id(b), "millenaire:byzantines",
                a.getVillageTypeId().toString(), waypoints, 0, 0, RoadSavedData.Status.COMPLETED, 0L, 0L, 0L);
    }

    private static Waypoint wp(BlockPos p) {
        return new Waypoint(p.getX(), p.getZ());
    }

    private static List<Waypoint> line(BlockPos start, int dx, int dz, int length) {
        return RoadPlanner.rasterize8Connected(
                List.of(wp(start), new Waypoint(start.getX() + dx * length, start.getZ() + dz * length)));
    }

    private static List<ChunkPos> forceAlong(ServerLevel level, List<Waypoint> route, int from, int to) {
        List<ChunkPos> out = new ArrayList<>();
        for (int i = Math.max(0, from); i < Math.min(route.size(), to); i++) {
            var w = route.get(i);
            for (int ox = -1; ox <= 1; ox++)
                for (int oz = -1; oz <= 1; oz++) {
                    var p = new ChunkPos(SectionPos.blockToSectionCoord(w.x()) + ox,
                            SectionPos.blockToSectionCoord(w.z()) + oz);
                    if (!out.contains(p)) {
                        out.add(p);
                        level.setChunkForced(p.x, p.z, true);
                    }
                }
        }
        return out;
    }
}
