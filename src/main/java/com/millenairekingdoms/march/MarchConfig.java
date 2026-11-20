package com.millenairekingdoms.march;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server configuration for hybrid physical royal caravans and bannered expedition armies. */
public final class MarchConfig {
    public static ModConfigSpec.BooleanValue CARAVANS;
    public static ModConfigSpec.BooleanValue ARMIES;
    public static ModConfigSpec.IntValue SPAWN_RANGE;
    public static ModConfigSpec.IntValue DESPAWN_RANGE;
    public static ModConfigSpec.DoubleValue ARMY_BLOCKS_PER_SECOND;
    public static ModConfigSpec.IntValue MIN_MARCH_DISTANCE;
    public static ModConfigSpec.IntValue ARRIVAL_RADIUS;
    public static ModConfigSpec.IntValue ENGAGEMENT_RADIUS;
    public static ModConfigSpec.IntValue MAX_SQUAD;
    public static ModConfigSpec.IntValue MARCH_LIFETIME_TICKS;
    public static ModConfigSpec.IntValue VILLAGE_STOP_TICKS;
    public static ModConfigSpec.IntValue CARAVAN_KILL_REPUTATION;
    public static ModConfigSpec.IntValue ARMY_ATTACK_REPUTATION;

    public record Settings(boolean caravans, boolean armies, int spawnRange, int despawnRange,
            double armyBlocksPerSecond, int minMarchDistance, int arrivalRadius, int engagementRadius, int maxSquad,
            long marchLifetimeTicks, int villageStopTicks, int caravanKillReputation, int armyAttackReputation) {
        public static final Settings DEFAULT =
                new Settings(true, true, 88, 96, 1.5, 128, 40, 12, 6, 96_000L, 60, 2048, 5096);
    }

    private MarchConfig() {}

    public static void define(ModConfigSpec.Builder b) {
        b.comment("Hybrid royal caravans and marching expedition armies: background progress along roads, real",
                         "entities only near players, never loading chunks.")
                .push("royalMarches");
        CARAVANS = b.comment("Royal shipments with a continuous friendly road travel as caravans (leader + pack mules).")
                           .define("caravans", true);
        ARMIES = b.comment("Expedition orders march along roads/terrain to the target before the native raid starts.")
                         .define("armies", true);
        SPAWN_RANGE = b.comment("A caravan/army becomes physical when a player is within this many blocks.")
                              .defineInRange("spawnRange", 88, 80, 96);
        DESPAWN_RANGE = b.comment("Physical entities are discarded when every player is farther than this.")
                                .defineInRange("despawnRange", 96, 80, 128);
        ARMY_BLOCKS_PER_SECOND = b.comment("Background march speed of an army in road steps per second.")
                                         .defineInRange("armyBlocksPerSecond", 1.5, 0.1, 20.0);
        MIN_MARCH_DISTANCE =
                b.comment("Villages closer than this (center to center) launch the native raid directly, without a march.")
                        .defineInRange("minimumMarchDistance", 128, 0, 100_000);
        ARRIVAL_RADIUS = b.comment("An army reaches the target perimeter within this many blocks of its center.")
                                 .defineInRange("arrivalRadius", 40, 8, 256);
        ENGAGEMENT_RADIUS = b.comment("Two warring armies closer than this many blocks fight a roadside skirmish.")
                                    .defineInRange("engagementRadius", 12, 2, 64);
        MAX_SQUAD = b.comment("Maximum physical soldiers (including the standard-bearer) spawned for one army.")
                            .defineInRange("maxSquad", 6, 1, 16);
        MARCH_LIFETIME_TICKS = b.comment("A march (and its waiting order) is abandoned after this many ticks.")
                                       .defineInRange("marchLifetimeTicks", 96_000, 2_400, 1_728_000);
        VILLAGE_STOP_TICKS = b.comment("Ticks a caravan rests in each intermediate friendly village square.")
                                     .defineInRange("villageStopTicks", 60, 0, 1200);
        CARAVAN_KILL_REPUTATION =
                b.comment("Reputation a player loses in the caravan's source, destination and capital villages for",
                         "each caravan mule or leader they kill.")
                        .defineInRange("caravanKillReputation", 2048, 0, 1_000_000);
        ARMY_ATTACK_REPUTATION =
                b.comment("Reputation a player loses in a marching army's source village and its kingdom's capital",
                         "the first time they attack that army (once per player and army).")
                        .defineInRange("armyAttackReputation", 5096, 0, 1_000_000);
        b.pop();
    }

    public static Settings current() {
        if (CARAVANS == null)
            return Settings.DEFAULT;
        int spawn = SPAWN_RANGE.get();
        return new Settings(CARAVANS.get(), ARMIES.get(), spawn, Math.max(spawn, DESPAWN_RANGE.get()),
                ARMY_BLOCKS_PER_SECOND.get(), MIN_MARCH_DISTANCE.get(), ARRIVAL_RADIUS.get(), ENGAGEMENT_RADIUS.get(),
                MAX_SQUAD.get(), MARCH_LIFETIME_TICKS.get(), VILLAGE_STOP_TICKS.get(),
                CARAVAN_KILL_REPUTATION.get(), ARMY_ATTACK_REPUTATION.get());
    }
}
