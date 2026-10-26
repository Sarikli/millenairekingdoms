package com.millenairekingdoms.atlas;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Display/visibility only; none of these values change actual village borders or political eligibility. */
public final class AtlasConfig {
    public static ModConfigSpec.IntValue INFLUENCE,DISCOVERY;
    public static ModConfigSpec.BooleanValue PUBLIC;
    private AtlasConfig(){}
    public static void define(ModConfigSpec.Builder b){
        b.comment("Read-only political atlas. Registered positions only; no terrain scan or chunk forcing.").push("politicalAtlas");
        INFLUENCE=b.comment("Retired compatibility key; ignored. The atlas uses settlement markers without influence geometry.").defineInRange("influenceRadiusBlocks",500,64,4096);
        DISCOVERY=b.comment("Loaded settlements within this horizontal distance become known to the connected player. Their own ruled members/local villages are also known.").defineInRange("discoveryRadiusBlocks",512,64,4096);
        PUBLIC=b.comment("Explicit operator opt-in to reveal every registered settlement in the current dimension. Defaultfalse protects undiscovered coordinates.").define("revealAllRegisteredSettlements",false);b.pop();
    }
}
