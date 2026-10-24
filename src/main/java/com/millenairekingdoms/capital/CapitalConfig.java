package com.millenairekingdoms.capital;

import com.millenairekingdoms.autonomy.AutonomyConfig;
import com.millenairekingdoms.economy.RoyalEconomyConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Server config, stored per world in serverconfig/millenairekingdoms-server.toml. */
public final class CapitalConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.DoubleValue CHANCE_PERCENT;
    public static final ModConfigSpec.IntValue MIN_DISTANCE;

    static {
        var b = new ModConfigSpec.Builder();
        b.comment("Rare natural NPC capitals: a new Millénaire village from natural world generation may found its own kingdom.").push("naturalCapitals");
        ENABLED = b.comment("Allow new natural villages to become NPC capitals. Existing villages are never converted.")
                .define("enabled", true);
        CHANCE_PERCENT = b.comment("Chance in percent for each new eligible natural village (10.0 = 10%).")
                .defineInRange("chancePercent", 10.0, 0.0, 100.0);
        MIN_DISTANCE = b.comment("Minimum horizontal distance in blocks between a new natural capital and every living capital.")
                .defineInRange("minCapitalDistance", 1000, 0, 100_000);
        b.pop();
        AutonomyConfig.define(b);
        com.millenairekingdoms.autonomy.RoyalNpcConfig.define(b);
        FoundingConfig.define(b);
        RoyalEconomyConfig.define(b);
        com.millenairekingdoms.construction.RoyalConstructionConfig.define(b);
        com.millenairekingdoms.shipping.RoyalShipmentConfig.define(b);
        SPEC = b.build();
    }

    private CapitalConfig() {}
}
