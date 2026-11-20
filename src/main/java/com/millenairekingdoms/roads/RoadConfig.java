package com.millenairekingdoms.roads;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server gameplay configuration for the 1.2.4 Royal Roads system. */
public final class RoadConfig {
    public static ModConfigSpec.BooleanValue ENABLED;
    public static ModConfigSpec.IntValue MAX_SEGMENT_DISTANCE;
    public static ModConfigSpec.IntValue MAX_DEEP_OCEAN_BLOCKS;
    public static ModConfigSpec.IntValue DAILY_PROGRESS_BLOCKS;
    public static ModConfigSpec.IntValue BLOCKS_PER_MATERIAL;
    public static ModConfigSpec.IntValue UPFRONT_BASE_DENIERS;
    public static ModConfigSpec.IntValue UPFRONT_DENIERS_PER_64_BLOCKS;
    public static ModConfigSpec.IntValue CONNECTED_VILLAGE_DAILY_BONUS;
    public static ModConfigSpec.IntValue SHIPMENT_DURATION_REDUCTION_PERCENT;
    public static ModConfigSpec.IntValue EXPEDITION_FEE_DISCOUNT_PERCENT;
    public static ModConfigSpec.IntValue FRONTIER_WORKER_RANGE;
    public static ModConfigSpec.IntValue INACTIVE_COMPLETION_TICKS;
    public static ModConfigSpec.IntValue MAX_BRIDGE_WIDTH;

    public record Settings(boolean enabled, int maxSegmentDistance, int maxDeepOceanBlocks, int dailyProgressBlocks,
            int blocksPerMaterial, int upfrontBaseDeniers, int upfrontDeniersPer64Blocks,
            int connectedVillageDailyBonus, int shipmentDurationReductionPercent, int expeditionFeeDiscountPercent,
            int frontierWorkerRange, int inactiveCompletionTicks, int maxBridgeWidth) {}

    private RoadConfig() {}

    public static void define(ModConfigSpec.Builder b) {
        b.comment("Royal Roads: chained kingdom highway network, chunk-safe construction, and logistics bonuses.")
                .push("royalRoads");
        ENABLED = b.comment("Enable Royal Road construction, realization, and network bonuses.")
                          .define("enabled", true);
        MAX_SEGMENT_DISTANCE =
                b.comment("Maximum horizontal Euclidean distance in blocks allowed for a single road segment.")
                        .defineInRange("maxSegmentDistance", 1200, 64, 10000);
        MAX_DEEP_OCEAN_BLOCKS =
                b.comment("Maximum contiguous deep-ocean crossing in blocks permitted along a planned road route.")
                        .defineInRange("maxDeepOceanBlocks", 120, 16, 2000);
        DAILY_PROGRESS_BLOCKS =
                b.comment("Maximum logical road length in blocks constructed per kingdom day when surplus exists.")
                        .defineInRange("dailyProgressBlocks", 45, 5, 500);
        BLOCKS_PER_MATERIAL =
                b.comment("Logical blocks advanced per consumed surplus material unit (cobblestone, gravel, or planks).")
                        .defineInRange("blocksPerMaterial", 5, 1, 64);
        UPFRONT_BASE_DENIERS = b.comment("Base upfront denier stipend charged when ordering a road project.")
                                       .defineInRange("upfrontBaseDeniers", 4, 0, 10000);
        UPFRONT_DENIERS_PER_64_BLOCKS =
                b.comment("Additional upfront denier stipend per 64 blocks of planned road distance.")
                        .defineInRange("upfrontDeniersPer64Blocks", 2, 0, 1000);
        CONNECTED_VILLAGE_DAILY_BONUS =
                b.comment("Daily income bonus in deniers for each member village connected to the capital road network.")
                        .defineInRange("connectedVillageDailyBonus", 2, 0, 100);
        SHIPMENT_DURATION_REDUCTION_PERCENT =
                b.comment("Percent reduction in Royal Shipment travel ticks when both endpoints are road-linked.")
                        .defineInRange("shipmentDurationReductionPercent", 38, 0, 80);
        EXPEDITION_FEE_DISCOUNT_PERCENT =
                b.comment("Percent discount on upfront conquest expedition fees when source or target is road-connected.")
                        .defineInRange("expeditionFeeDiscountPercent", 25, 0, 75);
        FRONTIER_WORKER_RANGE =
                b.comment("Player proximity range in blocks for spawning the live frontier road builder NPC.")
                        .defineInRange("frontierWorkerRange", 64, 16, 128);
        INACTIVE_COMPLETION_TICKS =
                b.comment("Automatic completion duration in ticks for a road order when endpoint village(s) are inactive.")
                        .defineInRange("inactiveCompletionTicks", 72000, 20, 1728000);
        MAX_BRIDGE_WIDTH =
                b.comment("Maximum contiguous bridge width in blocks across rivers and straits.")
                        .defineInRange("maxBridgeWidth", 160, 80, 512);
        b.pop();
    }

    public static Settings current() {
        return new Settings(ENABLED.get(), MAX_SEGMENT_DISTANCE.get(), MAX_DEEP_OCEAN_BLOCKS.get(),
                DAILY_PROGRESS_BLOCKS.get(), BLOCKS_PER_MATERIAL.get(), UPFRONT_BASE_DENIERS.get(),
                UPFRONT_DENIERS_PER_64_BLOCKS.get(), CONNECTED_VILLAGE_DAILY_BONUS.get(),
                SHIPMENT_DURATION_REDUCTION_PERCENT.get(), EXPEDITION_FEE_DISCOUNT_PERCENT.get(),
                FRONTIER_WORKER_RANGE.get(), INACTIVE_COMPLETION_TICKS.get(), MAX_BRIDGE_WIDTH.get());
    }
}
