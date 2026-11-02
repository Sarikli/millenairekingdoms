package com.millenairekingdoms.autonomy;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Does not change existing war chances, native military eligibility or political conquest requirements. */
public final class StrategicCombatConfig {
    public static ModConfigSpec.BooleanValue ENABLED;
    public static ModConfigSpec.DoubleValue SCALE;
    public static ModConfigSpec.IntValue CAP,HOME;
    private StrategicCombatConfig(){}
    public static void define(ModConfigSpec.Builder b){b.push("strategicWarfare");
        ENABLED=b.comment("Resolve eligible NPC war and hostile independent-village conquest orders with opposed dice only when both settlements are unloaded. Begun native raids retain native ownership.").define("enabled",true);
        SCALE=b.defineInRange("relativeStrengthScale",6.0,0.0,12.0);CAP=b.defineInRange("maximumDiceModifier",8,0,12);HOME=b.defineInRange("defenderHomeAdvantage",2,0,8);b.pop();
    }
    public static OpposedDice.Settings settings(){return SCALE==null?OpposedDice.Settings.DEFAULT:new OpposedDice.Settings(SCALE.get(),CAP.get(),HOME.get());}
}
