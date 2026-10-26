package com.millenairekingdoms.economy;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class RoyalConsolidationConfig {
    public static ModConfigSpec.BooleanValue ENABLED;
    public static ModConfigSpec.IntValue INTERVAL,MAX_CAPITALS;
    private RoyalConsolidationConfig(){}
    public static void define(ModConfigSpec.Builder b){b.push("treasuryConsolidation");
        ENABLED=b.comment("Allow new exactly conserving coin denomination maintenance in verified loaded capital storage. Existing operation recovery remains active.").define("enabled",true);
        INTERVAL=b.comment("Ticks between bounded maintenance checks; no inventory scan on ordinary server ticks.").defineInRange("checkIntervalTicks",1200,100,24000);
        MAX_CAPITALS=b.comment("Maximum capitals inspected per maintenance pass, round robin.").defineInRange("maximumCapitalsPerCheck",1,1,16);b.pop();
    }
}
