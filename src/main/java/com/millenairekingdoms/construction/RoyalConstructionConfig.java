package com.millenairekingdoms.construction;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server gameplay tracking budget. Pausing tracking does not stop original Millenaire builders or growth. */
public final class RoyalConstructionConfig {
    public static ModConfigSpec.BooleanValue TRACKING;
    public static ModConfigSpec.IntValue INTERVAL,MAX_PER_STEP;
    private RoyalConstructionConfig() {}
    public static void define(ModConfigSpec.Builder b) {
        b.comment("Royal order observation/recovery only. Original village growth/builders remain independent.").push("royalConstruction");
        TRACKING=b.comment("Track accepted work and retry already-issued authorized requests in loaded villages.").define("trackingEnabled",true);
        INTERVAL=b.comment("Ticks between bounded observation/retry steps; no remote chunk loading.").defineInRange("trackingIntervalTicks",100,20,1200);
        MAX_PER_STEP=b.comment("Maximum distinct order jobs handled per step; completed/recovery histories are not ticked.").defineInRange("maximumOrdersPerStep",2,1,16);
        b.pop();
    }
}
