package com.millenairekingdoms.virtualeconomy;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server configuration for the chunk-free background economy of unloaded villages. Production and upkeep amounts come
 * from Millénaire's own data ({@code LevelDef.abstractedProduction()}, {@code VillagerType.requiredGoods()}); these
 * values only scale, pace and bound them.
 */
public final class VirtualEconomyConfig {
    public static ModConfigSpec.BooleanValue ENABLED;
    public static ModConfigSpec.IntValue INTERVAL;
    public static ModConfigSpec.IntValue MAX_PER_STEP;
    public static ModConfigSpec.IntValue MAX_CATCH_UP_DAYS;
    public static ModConfigSpec.DoubleValue PRODUCTION_SCALE;
    public static ModConfigSpec.DoubleValue CONSUMPTION_SCALE;
    public static ModConfigSpec.IntValue MAX_VIRTUAL_SLOTS;
    public static ModConfigSpec.BooleanValue CONSTRUCTION;
    public static ModConfigSpec.IntValue CREDIT_DAYS;
    public static ModConfigSpec.DoubleValue MAX_TIME_SAVED;
    public static ModConfigSpec.IntValue HANDOFF_TIMEOUT_TICKS;
    public static ModConfigSpec.BooleanValue SHIPMENTS;

    /**
     * @param productionScale  multiplier on a building's daily {@code abstracted_production} at full occupancy
     * @param consumptionScale multiplier on each living resident's daily {@code requiredGoods}
     * @param maxVirtualSlots  hard ceiling on stack slots held virtually (also bounded by the Town Hall chest slots)
     * @param creditDays       unloaded days a paid project needs to reach 100% background credit
     * @param maxTimeSaved     share of the native per-block action time saved at 100% credit (0 = no bonus)
     */
    public record Settings(boolean enabled, int interval, int maxPerStep, int maxCatchUpDays, double productionScale,
            double consumptionScale, int maxVirtualSlots, boolean construction, int creditDays, double maxTimeSaved,
            long handoffTimeoutTicks) {
        public static final Settings DEFAULT = new Settings(true, 100, 8, 14, 1.0, 1.0, 1728, true, 4, 0.75, 72_000L);

        public Settings {
            interval = Math.max(1, interval);
            maxPerStep = Math.clamp(maxPerStep, 1, 64);
            maxCatchUpDays = Math.clamp(maxCatchUpDays, 1, 365);
            productionScale = Math.clamp(productionScale, 0.0, 16.0);
            consumptionScale = Math.clamp(consumptionScale, 0.0, 16.0);
            maxVirtualSlots = Math.clamp(maxVirtualSlots, 0, 100_000);
            creditDays = Math.clamp(creditDays, 1, 365);
            maxTimeSaved = Math.clamp(maxTimeSaved, 0.0, 0.95);
            handoffTimeoutTicks = Math.max(1, handoffTimeoutTicks);
        }
        public Settings withConstruction(boolean value) {
            return new Settings(enabled, interval, maxPerStep, maxCatchUpDays, productionScale, consumptionScale,
                    maxVirtualSlots, value, creditDays, maxTimeSaved, handoffTimeoutTicks);
        }
        public Settings withCreditDays(int value) {
            return new Settings(enabled, interval, maxPerStep, maxCatchUpDays, productionScale, consumptionScale,
                    maxVirtualSlots, construction, value, maxTimeSaved, handoffTimeoutTicks);
        }
        public Settings withMaxVirtualSlots(int value) {
            return new Settings(enabled, interval, maxPerStep, maxCatchUpDays, productionScale, consumptionScale,
                    value, construction, creditDays, maxTimeSaved, handoffTimeoutTicks);
        }
    }

    private VirtualEconomyConfig() {}

    public static void define(ModConfigSpec.Builder b) {
        b.comment("Virtual economy of unloaded villages: daily production/upkeep from Millenaire's own building and",
                         "villager data into a bounded virtual Town Hall buffer, background construction credit, and a",
                         "one-way hand-off to the native builders once the village loads naturally. Never loads chunks,",
                         "places blocks or edits millenaire_villages.dat; state lives in",
                         "data/millenairekingdoms_virtual_economy.dat.")
                .push("virtualEconomy");
        ENABLED = b.comment("Simulate unloaded villages and hand their surplus and paid projects back when they load.")
                          .define("enabled", true);
        INTERVAL = b.comment("Ticks between bounded village passes.").defineInRange("intervalTicks", 100, 20, 24_000);
        MAX_PER_STEP = b.comment("Villages visited per pass (round robin).").defineInRange("maxVillagesPerStep", 8, 1, 64);
        MAX_CATCH_UP_DAYS = b.comment("At most this many missed days are simulated when a village is next visited.")
                                    .defineInRange("maxCatchUpDays", 14, 1, 365);
        PRODUCTION_SCALE = b.comment("Multiplier on each building's daily abstracted_production at full occupancy.")
                                   .defineInRange("productionScale", 1.0, 0.0, 16.0);
        CONSUMPTION_SCALE = b.comment("Multiplier on each living resident's daily requiredGoods upkeep.")
                                    .defineInRange("consumptionScale", 1.0, 0.0, 16.0);
        MAX_VIRTUAL_SLOTS =
                b.comment("Hard ceiling on stack slots held virtually; the Town Hall chest slot count also caps it.")
                        .defineInRange("maxVirtualSlots", 1728, 0, 100_000);
        CONSTRUCTION = b.comment("Reserve materials for the next native project and build background credit.")
                               .define("construction", true);
        CREDIT_DAYS = b.comment("Unloaded days a paid project needs to reach 100% background credit.")
                              .defineInRange("creditDays", 4, 1, 365);
        MAX_TIME_SAVED = b.comment("Share of the native per-block build time saved at 100% credit (0 disables).")
                                 .defineInRange("maxTimeSaved", 0.75, 0.0, 0.95);
        HANDOFF_TIMEOUT_TICKS =
                b.comment("A handed-off project the native engine never starts is released after this many ticks",
                         "(its materials already sit in the Town Hall chests).")
                        .defineInRange("handoffTimeoutTicks", 72_000, 1_200, 1_728_000);
        SHIPMENTS = b.comment("Royal shipments may collect from / deliver to the virtual Town Hall buffer of a village",
                         "that is not loaded, instead of waiting until a player loads it.")
                            .define("shipments", true);
        b.pop();
    }

    /** Royal shipments use the virtual buffer of unloaded villages (independent of the scheduled pass). */
    public static boolean shipments() {
        return SHIPMENTS == null || SHIPMENTS.get();
    }

    public static Settings current() {
        if (ENABLED == null)
            return Settings.DEFAULT;
        return new Settings(ENABLED.get(), INTERVAL.get(), MAX_PER_STEP.get(), MAX_CATCH_UP_DAYS.get(),
                PRODUCTION_SCALE.get(), CONSUMPTION_SCALE.get(), MAX_VIRTUAL_SLOTS.get(), CONSTRUCTION.get(),
                CREDIT_DAYS.get(), MAX_TIME_SAVED.get(), HANDOFF_TIMEOUT_TICKS.get());
    }
}
