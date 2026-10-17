package com.millenairekingdoms.capital;

import net.neoforged.neoforge.common.ModConfigSpec;

/** The "dynamicFounding" section of the server config (same file as the natural capitals): independent NPC villages that
 * developed on their own may found a kingdom. Levels are Millénaire's 0-based town hall plan levels (BuildingInstance.getLevel()). */
public final class FoundingConfig {
    /** One immutable view of the settings, so an evaluation never mixes two config states and tests can pass their own. */
    public record Settings(boolean enabled, int minimumLevel, int intervalDays, double baseChancePercent, double increasePerLevel,
                           int minCapitalDistance) {
        public static final Settings DEFAULTS = new Settings(true, 3, 7, 5.0, 5.0, 1500);
        public Settings withChance(double base, double increase) {
            return new Settings(enabled, minimumLevel, intervalDays, base, increase, minCapitalDistance);
        }
        public Settings withEnabled(boolean on) {
            return new Settings(on, minimumLevel, intervalDays, baseChancePercent, increasePerLevel, minCapitalDistance);
        }
    }

    public static ModConfigSpec.BooleanValue ENABLED;
    public static ModConfigSpec.IntValue MINIMUM_LEVEL, INTERVAL_DAYS, MIN_DISTANCE;
    public static ModConfigSpec.DoubleValue BASE_CHANCE, INCREASE;

    private FoundingConfig() {}

    public static void define(ModConfigSpec.Builder b) {
        b.comment("1.1.0: an independent NPC village whose town hall grew through Millénaire's own construction may found its own",
                "NPC kingdom. Purely political: no building, villager, owner, culture, name, inventory or reputation changes.",
                "Player villages, hamlets, lone buildings, kingdom members and former capitals never found a kingdom this way.").push("dynamicFounding");
        ENABLED = b.comment("Let developed independent NPC villages found kingdoms on their own.")
                .define("enabled", true);
        MINIMUM_LEVEL = b.comment("Lowest completed town hall level (Millénaire's 0-based plan level) that may found a kingdom.",
                        "A town hall still upgrading counts as not completed; maximum levels differ by culture and village type.")
                .defineInRange("minimumTownhallLevel", 3, 0, 100);
        INTERVAL_DAYS = b.comment("Minecraft days between two founding evaluations (one chance roll per eligible village each time).")
                .defineInRange("checkIntervalDays", 7, 1, 365);
        BASE_CHANCE = b.comment("Chance in percent at the minimum town hall level (5.0 = 5%).")
                .defineInRange("baseChancePercent", 5.0, 0.0, 100.0);
        INCREASE = b.comment("Percentage points added for each completed level above the minimum; the chance never exceeds 100%.")
                .defineInRange("chanceIncreasePerLevel", 5.0, 0.0, 100.0);
        MIN_DISTANCE = b.comment("Minimum horizontal distance in blocks between the founding village and every living capital.")
                .defineInRange("minCapitalDistance", 1500, 0, 100_000);
        b.pop();
    }

    /** The current settings, or the defaults while the server config is not loaded. */
    public static Settings current() {
        if (ENABLED == null) return Settings.DEFAULTS;
        try {
            return new Settings(ENABLED.get(), MINIMUM_LEVEL.get(), INTERVAL_DAYS.get(), BASE_CHANCE.get(), INCREASE.get(), MIN_DISTANCE.get());
        } catch (IllegalStateException notLoaded) {
            return Settings.DEFAULTS;
        }
    }
}
