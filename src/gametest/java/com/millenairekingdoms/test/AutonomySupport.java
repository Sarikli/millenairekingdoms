package com.millenairekingdoms.test;

import com.millenairekingdoms.autonomy.AutonomyConfig;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import org.millenaire.building.BuildingId;
import org.millenaire.building.BuildingInstance;
import org.millenaire.building.BuildingInventory;
import org.millenaire.combat.raid.RaidManager;
import org.millenaire.culture.ModCultures;
import org.millenaire.culture.VillageType;
import org.millenaire.culture.VillagerType;
import org.millenaire.village.Village;
import org.millenaire.village.VillageId;
import org.millenaire.village.VillageSavedData;
import org.millenaire.village.VillagerRecord;

/** Stage 6.2 test helpers: real Millénaire villages of raid-carrying types, real raider and defender records, and a
 * random source with a chosen value so that probabilistic decisions are tested at both ends, never left to chance. */
final class AutonomySupport {
    static final String NS = "millenairekingdoms_autonomy_tests";
    static final AutonomyConfig.Settings ON = AutonomyConfig.Settings.DEFAULTS;
    private AutonomySupport() {}

    /** A RandomSource whose nextDouble/nextFloat are fixed (0 = every chance succeeds, 0.999 = none does). */
    static final class Fixed implements RandomSource {
        final double value;
        Fixed(double value) { this.value = value; }
        @Override public RandomSource fork() { return this; }
        @Override public PositionalRandomFactory forkPositional() { return RandomSource.create(1).forkPositional(); }
        @Override public void setSeed(long seed) {}
        @Override public int nextInt() { return 0; }
        @Override public int nextInt(int bound) { return (int) Math.min(bound - 1, Math.floor(value * bound)); }
        @Override public long nextLong() { return 0; }
        @Override public boolean nextBoolean() { return value < 0.5; }
        @Override public float nextFloat() { return (float) value; }
        @Override public double nextDouble() { return value; }
        @Override public double nextGaussian() { return 0; }
    }
    static RandomSource yes() { return new Fixed(0.0); }
    static RandomSource no() { return new Fixed(0.999); }

    static VillageType raidingType() { return raidingTypes().get(0); }
    /** Natural, non-player village types that send raids, one per culture, sorted for determinism. */
    static List<VillageType> raidingTypes() {
        var seen = new java.util.HashSet<ResourceLocation>();
        return ModCultures.getAllVillageTypes().values().stream()
                .filter(t -> !t.loneBuilding() && t.carriesRaid() && !t.playerControlled() && t.weight() > 0)
                .sorted(java.util.Comparator.comparing(t -> t.id().toString()))
                .filter(t -> seen.add(t.culture())).toList();
    }
    static VillagerType raiderType() {
        return ModCultures.getAllVillagerTypes().values().stream().filter(t -> t.isRaider() && t.baseAttackStrength() > 0)
                .sorted(java.util.Comparator.comparing(t -> t.id().toString())).findFirst().orElseThrow();
    }
    static VillagerType defenderType() {
        return ModCultures.getAllVillagerTypes().values().stream().filter(t -> t.isHelpInAttacks() && !t.isRaider() && t.baseAttackStrength() > 0)
                .sorted(java.util.Comparator.comparing(t -> t.id().toString())).findFirst().orElseThrow();
    }

    /** A real village with one chest town hall at test-relative (x, z), of a raid-carrying type. */
    static Village village(GameTestHelper h, int x, int z, String name) throws Exception { return village(h, x, z, name, raidingType()); }
    static Village village(GameTestHelper h, int x, int z, String name, VillageType type) throws Exception {
        return at(h, h.absolutePos(new BlockPos(x, 1, z)), name, type, true);
    }
    /** A village whose centre lies far outside every loaded chunk (Millénaire keeps unloaded villages in its records). */
    static Village farVillage(GameTestHelper h, String name) throws Exception {
        BlockPos pos = h.absolutePos(new BlockPos(1, 1, 1)).offset(200_000 + h.getLevel().random.nextInt(10_000), 0, 200_000);
        return at(h, pos, name, raidingType(), false);
    }
    private static Village at(GameTestHelper h, BlockPos center, String name, VillageType type, boolean chest) throws Exception {
        ServerLevel level = h.getLevel();
        if (level.getDayTime() <= 0) level.setDayTime(1000);
        if (chest) level.setBlockAndUpdate(center, Blocks.CHEST.defaultBlockState());
        var village = new Village(VillageId.random(), type.culture(), type.id(), center);
        village.setVillageName(name);
        var townhall = new BuildingInstance(BuildingId.random(), type.id(), center, Rotation.NONE, BuildingInstance.Status.COMPLETE, null, "a", 2);
        Field inventory = BuildingInstance.class.getDeclaredField("inventory");
        inventory.setAccessible(true);
        inventory.set(townhall, new BuildingInventory(List.of(center)));
        village.addBuilding(townhall);
        VillageSavedData.get(level).getVillageManager().addVillage(village);
        return village;
    }
    static VillagerRecord raider(Village v) { return add(v, raiderType()); }
    static VillagerRecord defender(Village v) { return add(v, defenderType()); }
    private static VillagerRecord add(Village v, VillagerType type) {
        var record = new VillagerRecord(UUID.randomUUID(), type.id(), v.getTownhall().getId());
        v.addVillager(record);
        return record;
    }
    static UUID id(Village v) { return v.getId().uuid(); }
    static void relation(Village a, Village b, int value) { a.setRelation(b.getId(), value); b.setRelation(a.getId(), value); }
    static KingdomSavedData.Kingdom kingdom(KingdomSavedData d, Village capital) {
        return d.create("A6 " + capital.getVillageName() + " " + capital.getId().uuid().toString().substring(0, 8), id(capital));
    }
    static void remove(GameTestHelper h, Village... villages) {
        var manager = VillageSavedData.get(h.getLevel()).getVillageManager();
        for (Village v : villages) if (v != null) manager.removeVillage(v.getId());
    }
    static CompoundTag millenaire(GameTestHelper h) {
        return VillageSavedData.get(h.getLevel()).save(new CompoundTag(), h.getLevel().registryAccess()).copy();
    }
    /** Millénaire's own raid end (private RaidManager.endRaid / applyLooting / checkBattleStatus), as its tick calls them. */
    static Object raidManager(String name, Village attacker, Village target, ServerLevel level) throws Exception {
        Method method = RaidManager.class.getDeclaredMethod(name, Village.class, Village.class, ServerLevel.class);
        method.setAccessible(true);
        return method.invoke(null, attacker, target, level);
    }
    static void battleStatus(Village target, ServerLevel level) throws Exception {
        Method check = RaidManager.class.getDeclaredMethod("checkBattleStatus", Village.class, ServerLevel.class);
        check.setAccessible(true);
        check.invoke(null, target, level);
    }
    static void rejected(GameTestHelper h, Runnable action) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        h.assertTrue(rejected, "Invalid operation must be rejected");
    }
    static boolean read() { return "read".equals(System.getProperty("kingdoms.tests.persistence")); }
}
