package com.millenairekingdoms.march;

import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.march.MarchSavedData.Kind;
import com.millenairekingdoms.march.MarchSavedData.March;
import com.millenairekingdoms.march.MarchSavedData.Status;
import com.millenairekingdoms.politics.PoliticsService;
import com.millenairekingdoms.roads.RoadPlacer;
import com.millenairekingdoms.roads.RoadPlanner;
import com.millenairekingdoms.roads.RoadSavedData.Waypoint;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.horse.Mule;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.millenaire.block.ModBlocks;
import org.millenaire.culture.ModCultures;
import org.millenaire.culture.VillagerType;
import org.millenaire.entity.MillVillager;
import org.millenaire.entity.ModEntities;
import org.millenaire.entity.VillagerAppearanceFactory;
import org.millenaire.village.Village;
import org.millenaire.village.VillageBannerService;

/**
 * Proximity materialization coordinator for caravans and armies (same lifecycle as {@code RoadRuntime}):
 * <ul>
 *   <li>A march whose current step has a non-spectator player within {@code spawnRange} (80-96, default 88) and a
 *       loaded, entity-ticking chunk spawns real entities on {@link RoadPlacer#findWalkableY}, then walks the road with
 *       {@code PathNavigation}; background step jumps are suspended while it is physical.</li>
 *   <li>When every player is beyond {@code despawnRange} (96), the chunk unloads or the server stops, all entities are
 *       {@code discard()}ed and background progression resumes from the leader's exact road step.</li>
 *   <li>Entities are never persisted: untracked tagged entities are discarded on join, so restarts cannot duplicate
 *       them.</li>
 * </ul>
 */
public final class MarchRuntime {
    public static final String MARCH_TAG = "mk_march";
    public static final String MARCH_ID_TAG = "mk_march_id";
    public static final String CARAVAN_TAG = "mk_caravan";
    public static final String CARAVAN_ID_TAG = "mk_caravan_id";
    public static final String ARMY_TAG = "mk_army";
    public static final String ARMY_ID_TAG = "mk_army_id";
    public static final String BEARER_TAG = "mk_standard_bearer";
    public static final String LEADER_TAG = "mk_march_leader";
    private static final int SYNC_INTERVAL = 10;
    private static final int LOOKAHEAD = 10;
    private static final double CARAVAN_SPEED = 0.6, ARMY_SPEED = 0.7, FOLLOW_SPEED = 1.1, CHARGE_SPEED = 1.0;
    private static final double MAX_FOLLOW_GAP = 32.0, STRIKE_RANGE = 2.4;
    /** Leads break (and would drop an item) past 10 blocks; re-tie only within this range, untie beyond it. */
    private static final double LEASH_RANGE = 7.0;
    private static final RoadPlacer.RoadStyle WALK_STYLE = new RoadPlacer.RoadStyle(ModBlocks.PATH_GRAVEL.get(),
            ModBlocks.PATH_GRAVEL_SLAB.get(), Blocks.OAK_PLANKS, (SlabBlock) Blocks.OAK_SLAB);

    /** One materialized party: the leader first, then followers (mules or soldiers). */
    static final class Squad {
        final UUID march;
        final Kind kind;
        final List<UUID> members = new ArrayList<>();
        final int initial;
        UUID engagedWith;
        int lastStep = -1, stuck;
        Squad(UUID march, Kind kind, int initial) {
            this.march = march;
            this.kind = kind;
            this.initial = initial;
        }
    }

    private static final Map<ResourceKey<Level>, Map<UUID, Squad>> ACTIVE = new ConcurrentHashMap<>();
    /** Players already charged the army-attack reputation, per army (one penalty per player and army). */
    private static final Map<UUID, java.util.Set<UUID>> ARMY_ATTACKERS = new ConcurrentHashMap<>();
    private static final double FOE_RANGE = 32.0;
    /** Enemy players who attacked a party; kept per march so the grudge survives the party re-forming. */
    private static final Map<UUID, java.util.Set<ServerPlayer>> FOES = new ConcurrentHashMap<>();
    /** Extra observers that count as nearby players (server-side fake players such as automated test watchers). */
    private static final java.util.Set<ServerPlayer> WATCHERS = ConcurrentHashMap.newKeySet();

    private MarchRuntime() {}

    // ---------------------------------------------------------------- lifecycle hooks

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level)
            tick(level);
    }

    public static void tick(ServerLevel level) {
        long now = level.getGameTime();
        if (now % 20L == 0L) {
            MarchService.advance(level, 20L);
            MarchService.resolveEncounters(level);
        }
        if (now % SYNC_INTERVAL == 0L)
            sync(level, observers(level));
        if (now % 200L == 0L)
            MarchService.sweep(level);
    }

    public static void onChunkUnload(ServerLevel level, ChunkPos chunk) {
        Map<UUID, Squad> squads = ACTIVE.get(level.dimension());
        if (squads == null || squads.isEmpty())
            return;
        for (Squad squad : List.copyOf(squads.values())) {
            boolean inside = false;
            for (UUID id : squad.members) {
                Entity e = level.getEntity(id);
                if (e == null || new ChunkPos(e.blockPosition()).equals(chunk)) {
                    inside = true;
                    break;
                }
            }
            if (inside)
                despawn(level, squad.march, true);
        }
    }

    public static void onEntityJoin(EntityJoinLevelEvent event) {
        Entity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level) || !isMarchEntity(entity))
            return;
        Map<UUID, Squad> squads = ACTIVE.get(level.dimension());
        UUID march = marchId(entity);
        Squad squad = squads == null || march == null ? null : squads.get(march);
        if (squad == null || !squad.members.contains(entity.getUUID()))
            event.setCanceled(true); // A saved leftover or duplicate: never let it into the world.
    }

    public static void cleanupAllOnServerBoundary(MinecraftServer server) {
        ACTIVE.clear();
        WATCHERS.clear();
        ARMY_ATTACKERS.clear();
        FOES.clear();
        if (server == null)
            return;
        for (ServerLevel level : server.getAllLevels())
            for (Entity e : level.getAllEntities())
                if (isMarchEntity(e))
                    discardQuietly(e);
        MarchService.clear(server);
    }

    /** Registers a server-side fake player as an observer (it is not part of the level's player list). */
    public static void watch(ServerPlayer player) {
        WATCHERS.add(player);
    }

    public static void unwatch(ServerPlayer player) {
        WATCHERS.remove(player);
    }

    private static List<ServerPlayer> observers(ServerLevel level) {
        List<ServerPlayer> out = new ArrayList<>(level.players());
        for (ServerPlayer p : WATCHERS)
            if (p.level() == level && !out.contains(p))
                out.add(p);
        return out;
    }

    // ---------------------------------------------------------------- queries

    public static boolean isMarchEntity(Entity e) {
        return e != null && e.getPersistentData().getBoolean(MARCH_TAG);
    }

    public static UUID marchId(Entity e) {
        return e != null && e.getPersistentData().hasUUID(MARCH_ID_TAG) ? e.getPersistentData().getUUID(MARCH_ID_TAG)
                                                                        : null;
    }

    public static boolean isMaterialized(ServerLevel level, UUID march) {
        Map<UUID, Squad> squads = ACTIVE.get(level.dimension());
        return squads != null && squads.containsKey(march);
    }

    /** Living tracked entities of one march (leader first). */
    public static List<Entity> entities(ServerLevel level, UUID march) {
        Map<UUID, Squad> squads = ACTIVE.get(level.dimension());
        Squad squad = squads == null ? null : squads.get(march);
        List<Entity> out = new ArrayList<>();
        if (squad != null)
            for (UUID id : squad.members) {
                Entity e = level.getEntity(id);
                if (e != null && e.isAlive())
                    out.add(e);
            }
        return out;
    }

    /** Every tagged march entity in the level, tracked or not (for duplicate checks). */
    public static int countEntities(ServerLevel level, UUID march) {
        int n = 0;
        for (Entity e : level.getAllEntities())
            if (isMarchEntity(e) && e.isAlive() && (march == null || march.equals(marchId(e))))
                n++;
        return n;
    }

    /** The march this materialized army is currently fighting, or {@code null}. */
    public static UUID engagedWith(ServerLevel level, UUID march) {
        Map<UUID, Squad> squads = ACTIVE.get(level.dimension());
        Squad squad = squads == null ? null : squads.get(march);
        return squad == null ? null : squad.engagedWith;
    }

    static void engage(ServerLevel level, UUID a, UUID b) {
        Map<UUID, Squad> squads = ACTIVE.get(level.dimension());
        if (squads == null)
            return;
        Squad sa = squads.get(a), sb = squads.get(b);
        if (sa == null || sb == null)
            return;
        if (sa.kind == Kind.ARMY)
            sa.engagedWith = b;
        if (sb.kind == Kind.ARMY)
            sb.engagedWith = a;
    }

    // ---------------------------------------------------------------- materialization

    /** One proximity pass with an explicit player list (tests pass their own watchers). */
    public static void sync(ServerLevel level, Collection<? extends ServerPlayer> players) {
        sync(level, players, null);
    }

    /** As {@link #sync(ServerLevel, Collection)}, limited to the given marches when {@code only} is not null. */
    public static void sync(ServerLevel level, Collection<? extends ServerPlayer> players, java.util.Set<UUID> only) {
        var cfg = MarchConfig.current();
        var data = MarchSavedData.get(level);
        Map<UUID, Squad> squads = ACTIVE.computeIfAbsent(level.dimension(), k -> new ConcurrentHashMap<>());
        for (UUID id : List.copyOf(squads.keySet())) {
            if (only != null && !only.contains(id))
                continue;
            March m = data.march(id);
            if (m == null || m.status() == Status.DEFEATED)
                despawn(level, id, false);
        }
        double spawnSq = (double) cfg.spawnRange() * cfg.spawnRange();
        double despawnSq = (double) cfg.despawnRange() * cfg.despawnRange();
        for (March m : data.all()) {
            if (m.status() == Status.DEFEATED || only != null && !only.contains(m.id()))
                continue;
            if (m.kind() == Kind.CARAVAN ? !cfg.caravans() : !cfg.armies()) {
                despawn(level, m.id(), false);
                continue;
            }
            Squad squad = squads.get(m.id());
            if (squad != null) {
                Entity leader = leader(level, squad);
                if (leader == null) {
                    despawn(level, m.id(), true);
                    continue;
                }
                MarchService.syncFromLeader(level, m, leader.getX(), leader.getZ());
                if (!loaded(level, leader.blockPosition().getX(), leader.blockPosition().getZ())
                        || !anyPlayerWithin(players, leader.getX(), leader.getZ(), despawnSq)) {
                    despawn(level, m.id(), true);
                    continue;
                }
                if (m.kind() == Kind.ARMY && (!m.marching() || beyondBuiltRoad(m))) {
                    despawn(level, m.id(), true);
                    continue;
                }
                if (m.kind() == Kind.CARAVAN && !m.marching()) {
                    // Arrived at the destination square: rest until the shipment delivers and removes the caravan.
                    for (Entity e : entities(level, m.id()))
                        if (e instanceof Mob mob)
                            mob.getNavigation().stop();
                    continue;
                }
                steer(level, m, squad);
                continue;
            }
            if (!m.marching() || beyondBuiltRoad(m))
                continue;
            Waypoint here = m.position();
            if (!anyPlayerWithin(players, here.x() + 0.5, here.z() + 0.5, spawnSq) || !loaded(level, here.x(), here.z()))
                continue;
            spawn(level, m, squads);
        }
    }

    private static boolean beyondBuiltRoad(March m) {
        return m.kind() == Kind.ARMY && m.roadEndStep() >= 0 && m.stepIndex() >= m.roadEndStep();
    }

    private static void spawn(ServerLevel level, March m, Map<UUID, Squad> squads) {
        discardStrays(level, m.id());
        Squad squad = new Squad(m.id(), m.kind(), 1 + Math.max(0, m.kind() == Kind.CARAVAN ? m.squad() : m.squad() - 1));
        squads.put(m.id(), squad);
        int count = m.kind() == Kind.CARAVAN ? 1 + m.squad() : m.squad();
        for (int i = 0; i < count; i++) {
            int idx = Math.max(0, m.stepIndex() - 2 * i);
            Waypoint w = m.route().get(idx);
            if (!loaded(level, w.x(), w.z()))
                w = m.position();
            int y = RoadPlacer.findWalkableY(level, w.x(), w.z(), WALK_STYLE);
            Entity e = m.kind() == Kind.CARAVAN ? (i == 0 ? caravanLeader(level, m) : packMule(level, m))
                                                : soldier(level, m, i);
            if (e == null)
                continue;
            tag(e, m, i == 0);
            RoadPlanner.Sample s = RoadPlanner.sampleAt(m.route(), idx);
            float yaw = (float) (Math.toDegrees(Math.atan2(s.dirZ(), s.dirX())) - 90.0);
            e.moveTo(w.x() + 0.5, y, w.z() + 0.5, yaw, 0.0f);
            squad.members.add(e.getUUID());
            if (!level.addFreshEntity(e))
                squad.members.remove(e.getUUID());
        }
        if (m.kind() == Kind.CARAVAN)
            leashTrain(level, squad);
        if (squad.members.isEmpty())
            squads.remove(m.id());
    }

    /** Mule train: every pack mule is visibly leashed to the party member ahead of it (leader first). */
    static void leashTrain(ServerLevel level, Squad squad) {
        Entity ahead = null;
        for (UUID id : squad.members) {
            Entity e = level.getEntity(id);
            if (e == null || !e.isAlive())
                continue;
            if (ahead != null && e instanceof Mule mule) {
                if (!mule.isLeashed() || mule.getLeashHolder() != ahead) {
                    if (mule.distanceTo(ahead) <= LEASH_RANGE)
                        mule.setLeashedTo(ahead, true);
                    else if (mule.isLeashed())
                        unleash(mule);
                }
            }
            ahead = e;
        }
    }

    /** Removes a lead without ever dropping a lead item (marching parties never create loot). */
    static void unleash(Entity e) {
        if (e instanceof Mob mob && mob.isLeashed())
            mob.dropLeash(true, false);
    }

    private static void tag(Entity e, March m, boolean leader) {
        var t = e.getPersistentData();
        t.putBoolean(MARCH_TAG, true);
        t.putUUID(MARCH_ID_TAG, m.id());
        if (m.kind() == Kind.CARAVAN) {
            t.putBoolean(CARAVAN_TAG, true);
            t.putUUID(CARAVAN_ID_TAG, m.id());
        } else {
            t.putBoolean(ARMY_TAG, true);
            t.putUUID(ARMY_ID_TAG, m.id());
        }
        if (leader)
            t.putBoolean(LEADER_TAG, true);
    }

    private static MillVillager caravanLeader(ServerLevel level, March m) {
        MillVillager v = ModEntities.MILL_VILLAGER.get().create(level);
        if (v == null)
            return null;
        VillagerType vt = pickType(MarchService.culture(m), false, List.of());
        prepareVillager(v, vt);
        v.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.LEAD));
        v.setCustomName(Component.translatable("millenairekingdoms.march.caravan_leader"));
        v.setCustomNameVisible(false);
        return v;
    }

    private static Mule packMule(ServerLevel level, March m) {
        Mule mule = EntityType.MULE.create(level);
        if (mule == null)
            return null;
        mule.setChest(true);
        mule.goalSelector.removeAllGoals(g -> true);
        mule.targetSelector.removeAllGoals(g -> true);
        mule.setSilent(true);
        return mule;
    }

    private static MillVillager soldier(ServerLevel level, March m, int index) {
        MillVillager v = ModEntities.MILL_VILLAGER.get().create(level);
        if (v == null)
            return null;
        List<String> types = m.unitTypes();
        VillagerType vt = null;
        if (!types.isEmpty()) {
            var id = ResourceLocation.tryParse(types.get(index % types.size()));
            vt = id == null ? null : ModCultures.getVillagerType(id);
        }
        if (vt == null)
            vt = pickType(MarchService.culture(m), true, List.of());
        prepareVillager(v, vt);
        if (index == 0) {
            ItemStack banner = banner(level, m);
            v.setItemSlot(EquipmentSlot.HEAD, banner.copy());
            v.setItemInHand(InteractionHand.MAIN_HAND, banner.copy());
            v.setDropChance(EquipmentSlot.HEAD, 0.0f);
            v.setDropChance(EquipmentSlot.MAINHAND, 0.0f);
            v.getPersistentData().putBoolean(BEARER_TAG, true);
            v.setCustomName(Component.translatable("millenairekingdoms.march.standard_bearer"));
            v.setCustomNameVisible(true);
        } else
            v.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        v.setDropChance(EquipmentSlot.MAINHAND, 0.0f);
        return v;
    }

    private static void prepareVillager(MillVillager v, VillagerType vt) {
        if (vt != null) {
            v.setVillagerTypeId(vt.id());
            v.syncTypeDerivedFlags(vt);
            VillagerAppearanceFactory.randomizeAppearance(v, vt);
        }
        v.setNoAi(false);
        v.goalSelector.removeAllGoals(g -> true);
        v.targetSelector.removeAllGoals(g -> true);
        v.setSilent(true);
    }

    /** The source village's own banner, generated by Millénaire's native banner service when it has none yet. */
    public static ItemStack banner(ServerLevel level, March m) {
        var access = level.registryAccess();
        Village village = PoliticsService.village(level, m.source());
        ItemStack banner = ItemStack.EMPTY;
        try {
            if (village != null)
                banner = village.getBannerStack(access);
            if ((banner == null || banner.isEmpty()) && village != null) {
                var type = ModCultures.getVillageType(village.getVillageTypeId());
                if (type != null)
                    banner = VillageBannerService.generateVillageBanner(
                            type, access, RandomSource.create(m.source().getMostSignificantBits()));
            }
            if (banner == null || banner.isEmpty()) {
                var culture = MarchService.culture(m) == null ? null : ModCultures.getCulture(MarchService.culture(m));
                if (culture != null)
                    banner = VillageBannerService.getCultureBanner(culture, access);
            }
        } catch (RuntimeException unavailable) {
            banner = ItemStack.EMPTY;
        }
        return banner == null || banner.isEmpty() ? VillageBannerService.fallbackBanner() : banner;
    }

    private static VillagerType pickType(ResourceLocation culture, boolean raider, List<String> preferred) {
        var all = ModCultures.getAllVillagerTypes()
                          .values()
                          .stream()
                          .filter(vt -> !vt.isChild() && !vt.isHostile() && vt.isRaider() == raider)
                          .sorted(Comparator.comparing(vt -> vt.id().toString()))
                          .toList();
        var own = all.stream().filter(vt -> culture == null || culture.equals(vt.culture())).toList();
        var pool = own.isEmpty() ? all : own;
        if (!raider)
            for (var vt : pool)
                if (vt.id().getPath().contains("merchant") || vt.id().getPath().contains("trader"))
                    return vt;
        return pool.isEmpty() ? null : pool.getFirst();
    }

    // ---------------------------------------------------------------- steering

    private static void steer(ServerLevel level, March m, Squad squad) {
        long now = level.getGameTime();
        List<Entity> alive = entities(level, m.id());
        if (alive.isEmpty())
            return;
        Entity leader = alive.getFirst();
        if (retaliate(level, m, squad, alive))
            return;
        if (squad.engagedWith != null && fight(level, m, squad, alive))
            return;
        if (m.kind() == Kind.CARAVAN) {
            int next = m.nextStop();
            if (next < m.stops().size() && m.stepIndex() >= m.stops().get(next)) {
                m.setPauseUntil(now + MarchConfig.current().villageStopTicks());
                m.setNextStop(next + 1);
                MarchSavedData.get(level).setDirty();
            }
            if (now < m.pauseUntil()) {
                for (Entity e : alive)
                    if (e instanceof Mob mob)
                        mob.getNavigation().stop();
                return;
            }
        }
        int targetIdx = Math.min(m.lastIndex(), m.stepIndex() + LOOKAHEAD);
        Waypoint target = loadedToward(level, m, m.stepIndex(), targetIdx);
        if (leader instanceof Mob mob && target != null) {
            int y = RoadPlacer.findWalkableY(level, target.x(), target.z(), WALK_STYLE);
            double speed = m.kind() == Kind.CARAVAN ? CARAVAN_SPEED : ARMY_SPEED;
            if (!mob.getNavigation().moveTo(target.x() + 0.5, y, target.z() + 0.5, speed) || stuck(squad, m))
                nudge(level, mob, m, squad);
        }
        for (int i = 1; i < alive.size(); i++) {
            Entity f = alive.get(i);
            if (!(f instanceof Mob mob))
                continue;
            int idx = Math.max(0, m.stepIndex() - 2 * i);
            Waypoint slot = m.route().get(idx);
            double gap = f.distanceTo(leader);
            if (gap > MAX_FOLLOW_GAP && loaded(level, slot.x(), slot.z())) {
                // Safety only: a follower left behind (e.g. stuck in a hole) rejoins its party.
                int y = RoadPlacer.findWalkableY(level, slot.x(), slot.z(), WALK_STYLE);
                f.teleportTo(slot.x() + 0.5, y, slot.z() + 0.5);
                continue;
            }
            if (!loaded(level, slot.x(), slot.z()))
                continue;
            int y = RoadPlacer.findWalkableY(level, slot.x(), slot.z(), WALK_STYLE);
            mob.getNavigation().moveTo(slot.x() + 0.5, y, slot.z() + 0.5, FOLLOW_SPEED);
        }
        if (m.kind() == Kind.CARAVAN)
            leashTrain(level, squad);
    }

    private static boolean stuck(Squad squad, March m) {
        if (m.stepIndex() == squad.lastStep)
            squad.stuck++;
        else {
            squad.stuck = 0;
            squad.lastStep = m.stepIndex();
        }
        return squad.stuck >= 6;
    }

    /** One smooth block along the road when navigation cannot find a path, or past an obstacle if stalled. */
    private static void nudge(ServerLevel level, Mob mob, March m, Squad squad) {
        if (squad.stuck >= 6) {
            int nextIdx = Math.min(m.lastIndex(), m.stepIndex() + 2);
            Waypoint dst = m.route().get(nextIdx);
            if (loaded(level, dst.x(), dst.z())) {
                int y = RoadPlacer.findWalkableY(level, dst.x(), dst.z(), WALK_STYLE);
                mob.setPos(dst.x() + 0.5, y, dst.z() + 0.5);
                m.setStepIndex(nextIdx);
                if (nextIdx >= m.lastIndex() && m.marching())
                    m.setStatus(Status.ARRIVED, level.getGameTime());
                MarchSavedData.get(level).setDirty();
                squad.stuck = 0;
                squad.lastStep = nextIdx;
                return;
            }
        }
        Waypoint next = m.route().get(Math.min(m.lastIndex(), m.stepIndex() + 1));
        if (!loaded(level, next.x(), next.z()))
            return;
        double dx = next.x() + 0.5 - mob.getX(), dz = next.z() + 0.5 - mob.getZ();
        double d = Math.hypot(dx, dz);
        if (d < 1e-3)
            return;
        double step = Math.min(1.0, d);
        int y = RoadPlacer.findWalkableY(level, next.x(), next.z(), WALK_STYLE);
        mob.setPos(mob.getX() + dx / d * step, y, mob.getZ() + dz / d * step);
    }

    private static Waypoint loadedToward(ServerLevel level, March m, int from, int to) {
        for (int i = to; i > from; i--) {
            Waypoint w = m.route().get(i);
            if (loaded(level, w.x(), w.z()))
                return w;
        }
        return null;
    }

    /**
     * Real roadside combat between materialized parties. Returns true while the fight continues (no marching).
     * Each soldier charges the nearest living enemy and strikes within reach; deaths settle through
     * {@link #onDeath}.
     */
    private static boolean fight(ServerLevel level, March m, Squad squad, List<Entity> alive) {
        List<Entity> enemies = entities(level, squad.engagedWith);
        if (enemies.isEmpty()) {
            squad.engagedWith = null;
            return false;
        }
        charge(level, alive, enemies);
        return true;
    }

    /**
     * A party attacked by an enemy player turns on them: every soldier of an army, or the caravan leader (the pack
     * mules halt). Returns true while an attacker is still alive and within {@link #FOE_RANGE} of the party.
     */
    private static boolean retaliate(ServerLevel level, March m, Squad squad, List<Entity> alive) {
        Entity leader = alive.getFirst();
        var foes = FOES.get(m.id());
        if (foes == null)
            return false;
        List<Entity> targets = new ArrayList<>();
        for (ServerPlayer p : foes)
            if (p.isAlive() && !p.isSpectator() && p.level() == level && p.distanceTo(leader) <= FOE_RANGE)
                targets.add(p);
        if (targets.isEmpty())
            return false;
        List<Entity> fighters = new ArrayList<>();
        for (Entity e : alive) {
            if (m.kind() == Kind.ARMY || e instanceof MillVillager)
                fighters.add(e);
            else if (e instanceof Mob mob)
                mob.getNavigation().stop();
        }
        charge(level, fighters, targets);
        return true;
    }

    private static void charge(ServerLevel level, List<Entity> alive, List<Entity> enemies) {
        // Roadside blows are heavier than villager scuffles so a skirmish ends well before natural regeneration.
        float damage = 8.0f;
        for (Entity e : alive) {
            if (!(e instanceof Mob mob))
                continue;
            Entity foe = enemies.stream().min(Comparator.comparingDouble(e::distanceToSqr)).orElse(null);
            if (foe == null)
                break;
            if (e.distanceTo(foe) <= STRIKE_RANGE) {
                mob.getNavigation().stop();
                mob.getLookControl().setLookAt(foe);
                mob.swing(InteractionHand.MAIN_HAND, true);
                if (e instanceof MillVillager v) {
                    var vt = ModCultures.getVillagerType(v.getVillagerTypeId());
                    if (vt != null)
                        damage = Math.clamp(6 + 2L * vt.baseAttackStrength(), 6, 16);
                }
                foe.hurt(level.damageSources().mobAttack(mob), damage);
            } else if (!mob.getNavigation().moveTo(foe, CHARGE_SPEED) || e.distanceTo(foe) < 8.0) {
                // Close in for the last few blocks directly (pathing between two crowded squads often stalls).
                double dx = foe.getX() - e.getX(), dz = foe.getZ() - e.getZ(), d = Math.hypot(dx, dz);
                if (d > 1e-3 && loaded(level, (int) Math.floor(e.getX() + dx / d), (int) Math.floor(e.getZ() + dz / d))) {
                    double step = Math.min(1.0, d - 1.5);
                    if (step > 0)
                        mob.setPos(e.getX() + dx / d * step, foe.getY(), e.getZ() + dz / d * step);
                }
            }
        }
    }

    // ---------------------------------------------------------------- despawn

    /**
     * Discards every entity of a march. When {@code resume} is true the march keeps its leader's last road step and
     * continues in the background; nothing physical is ever persisted.
     */
    public static void despawn(ServerLevel level, UUID march, boolean resume) {
        Map<UUID, Squad> squads = ACTIVE.get(level.dimension());
        Squad squad = squads == null ? null : squads.remove(march);
        if (!resume) {
            FOES.remove(march);
            ARMY_ATTACKERS.remove(march);
        }
        if (squad != null) {

            if (resume) {
                Entity leader = level.getEntity(squad.members.isEmpty() ? null : squad.members.getFirst());
                March m = MarchSavedData.get(level).march(march);
                if (leader != null && m != null)
                    MarchService.syncFromLeader(level, m, leader.getX(), leader.getZ());
            }
            for (UUID id : squad.members) {
                Entity e = level.getEntity(id);
                if (e != null)
                    unleash(e);
            }
            for (UUID id : squad.members) {
                Entity e = level.getEntity(id);
                if (e != null)
                    discardQuietly(e);
            }
        }
        discardStrays(level, march);
    }

    private static void discardStrays(ServerLevel level, UUID march) {
        for (Entity e : level.getAllEntities())
            if (isMarchEntity(e) && march.equals(marchId(e)))
                discardQuietly(e);
    }

    private static void discardQuietly(Entity e) {
        unleash(e);
        // A party member that is dying right now (its own death event is settling the march) finishes its death.
        if (e instanceof LivingEntity living && living.isDeadOrDying())
            return;
        e.discard();
    }

    private static Entity leader(ServerLevel level, Squad squad) {
        if (squad.members.isEmpty())
            return null;
        Entity e = level.getEntity(squad.members.getFirst());
        return e != null && e.isAlive() ? e : null;
    }

    // ---------------------------------------------------------------- combat rules

    /** The kingdom an attacker fights for: a ruling player's kingdom, or the kingdom of an army entity. */
    static UUID allegiance(ServerLevel level, Entity attacker) {
        if (attacker instanceof ServerPlayer player)
            return KingdomSavedData.get(level).ruledBy(player.getUUID());
        if (isMarchEntity(attacker) && attacker.getPersistentData().getBoolean(ARMY_TAG)) {
            March m = MarchSavedData.get(level).march(marchId(attacker));
            return m == null ? null : m.kingdom();
        }
        return null;
    }

    /**
     * Enemies at war and foreign/independent players (banditry) may hurt a marching party; own-kingdom rulers,
     * mobs and environmental damage are ignored.
     */
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        LivingEntity victim = event.getEntity();
        if (!(victim.level() instanceof ServerLevel level) || !isMarchEntity(victim))
            return;
        Entity attacker = event.getSource().getEntity();
        if (!hostile(level, victim, attacker)) {
            event.setCanceled(true);
            return;
        }
        if (attacker instanceof ServerPlayer player) {
            UUID marchId = marchId(victim);
            FOES.computeIfAbsent(marchId, k -> ConcurrentHashMap.newKeySet()).add(player);
            March m = MarchSavedData.get(level).march(marchId);
            if (m != null && m.kind() == Kind.ARMY
                    && ARMY_ATTACKERS.computeIfAbsent(marchId, k -> ConcurrentHashMap.newKeySet()).add(player.getUUID()))
                punishArmyAttack(level, m, player);
        }
    }

    /** Attacking a kingdom's marching army is a grave offence: reputation loss at its source village and capital. */
    static void punishArmyAttack(ServerLevel level, March army, ServerPlayer player) {
        int amount = MarchConfig.current().armyAttackReputation();
        if (amount <= 0)
            return;
        var k = KingdomSavedData.get(level).kingdom(army.kingdom());
        java.util.Set<UUID> villages = new java.util.LinkedHashSet<>(List.of(army.source()));
        if (k != null)
            villages.add(k.capital());
        for (UUID id : villages) {
            Village v = PoliticsService.village(level, id);
            if (v != null)
                v.addReputation(player.getUUID(), -amount);
        }
    }

    private static boolean hostile(ServerLevel level, Entity victim, Entity attacker) {
        March m = MarchSavedData.get(level).march(marchId(victim));
        if (m == null || attacker == null)
            return false;
        UUID side = allegiance(level, attacker);
        if (attacker instanceof ServerPlayer)
            return !m.kingdom().equals(side);
        return MarchService.atWar(KingdomSavedData.get(level), side, m.kingdom());
    }

    public static void onDeath(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (!(victim.level() instanceof ServerLevel level) || !isMarchEntity(victim))
            return;
        UUID marchId = marchId(victim);
        var data = MarchSavedData.get(level);
        March m = data.march(marchId);
        Map<UUID, Squad> squads = ACTIVE.get(level.dimension());
        Squad squad = squads == null ? null : squads.get(marchId);
        unleash(victim);
        if (squad != null) {
            for (UUID id : squad.members) {
                Entity e = level.getEntity(id);
                if (e instanceof Mob mob && mob.getLeashHolder() == victim)
                    unleash(mob);
            }
            squad.members.remove(victim.getUUID());
        }
        if (m == null)
            return;
        Entity killer = event.getSource().getEntity();
        boolean enemy = hostile(level, victim, killer);
        UUID killerAllegiance = enemy ? allegiance(level, killer) : null;
        UUID side = killerAllegiance != null
                && MarchService.atWar(KingdomSavedData.get(level), killerAllegiance, m.kingdom())
                ? killerAllegiance : null;
        UUID actorId = killer instanceof ServerPlayer player ? player.getUUID() : null;
        String actorName = killer instanceof ServerPlayer player ? player.getScoreboardName() : "";
        if (m.kind() == Kind.CARAVAN) {
            if (enemy && killer instanceof ServerPlayer player)
                punishCaravanKill(level, m, player);
            if (enemy)
                MarchService.ambushCaravan(level, m, victim.blockPosition(), side, true, actorId, actorName);
            else
                despawn(level, marchId, true); // Lost for other reasons: re-forms later from its last step.
            return;
        }
        if (squad == null)
            return;
        long left = squad.members.stream().map(level::getEntity).filter(e -> e != null && e.isAlive()).count();
        if (left > 0) {
            if (enemy) {
                // Casualties are permanent: a party that re-forms later never brings its dead back.
                int before = Math.max(1, m.squad());
                m.setSquad((int) left);
                m.setStrength(Math.max(1L, Math.round(m.strength() * (double) left / before)));
                data.setDirty();
            }
            return;
        }
        if (!enemy) {
            despawn(level, marchId, true);
            return;
        }
        UUID winnerMarch = isMarchEntity(killer) ? marchId(killer) : null;
        if (winnerMarch != null) {
            March winner = data.march(winnerMarch);
            Squad ws = squads.get(winnerMarch);
            if (winner != null && ws != null) {
                long survivors = ws.members.stream().map(level::getEntity).filter(e -> e != null && e.isAlive()).count();
                long scaled = Math.max(1L, Math.round(winner.strength() * (double) survivors / Math.max(1, ws.initial)));
                winner.setStrength(scaled);
                winner.setSquad((int) Math.max(1, survivors));
                ws.engagedWith = null;
                data.setDirty();
            }
        }
        MarchService.defeat(level, m, side, winnerMarch, killer instanceof ServerPlayer ? "KILLED_BY_PLAYER" : "ROADSIDE_BATTLE",
                Map.of(), victim.blockPosition(), actorId, actorName);
    }

    /**
     * Killing a kingdom's caravan mule or leader costs the player reputation in the caravan's source and destination
     * villages and in the owning kingdom's capital (each village once).
     */
    static void punishCaravanKill(ServerLevel level, March caravan, ServerPlayer player) {
        int amount = MarchConfig.current().caravanKillReputation();
        if (amount <= 0)
            return;
        var k = KingdomSavedData.get(level).kingdom(caravan.kingdom());
        java.util.Set<UUID> villages = new java.util.LinkedHashSet<>(List.of(caravan.source(), caravan.destination()));
        if (k != null)
            villages.add(k.capital());
        for (UUID id : villages) {
            Village v = PoliticsService.village(level, id);
            if (v != null)
                v.addReputation(player.getUUID(), -amount);
        }
    }

    /** Marching parties never drop loot or equipment; ambushed cargo is dropped by the settlement itself. */
    public static void onDrops(LivingDropsEvent event) {
        if (isMarchEntity(event.getEntity()))
            event.setCanceled(true);
    }

    /** No trading with, riding, leashing or opening a marching party. */
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (isMarchEntity(event.getTarget()))
            event.setCanceled(true);
    }

    public static void onInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (isMarchEntity(event.getTarget()))
            event.setCanceled(true);
    }

    // ---------------------------------------------------------------- helpers

    private static boolean loaded(ServerLevel level, int x, int z) {
        return level.hasChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
    }

    private static boolean anyPlayerWithin(Collection<? extends ServerPlayer> players, double x, double z,
            double maxSq) {
        for (ServerPlayer p : players) {
            if (p.isSpectator())
                continue;
            double dx = p.getX() - x, dz = p.getZ() - z;
            if (dx * dx + dz * dz <= maxSq)
                return true;
        }
        return false;
    }
}
