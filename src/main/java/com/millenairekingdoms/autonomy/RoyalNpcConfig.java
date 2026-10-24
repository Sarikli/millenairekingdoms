package com.millenairekingdoms.autonomy;

import com.millenairekingdoms.personality.KingdomPersonality.Category;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Server gameplay balance. Relative weights apply only to independently eligible, justified investments. */
public final class RoyalNpcConfig {
    public record Weights(int ordinary,int preferred,int warMilitary,int borderMilitary,double militaryAggression,
                          double agriculturalAggression,double religiousAggression,double artisanAggression) {
        public static final Weights DEFAULTS=new Weights(1,4,6,4,1.5,0.85,1.0,0.9);
        public Weights {
            if(ordinary<1 || preferred<ordinary || preferred>1000 || warMilitary<1 || warMilitary>1000 || borderMilitary<0 || borderMilitary>1000)
                throw new IllegalArgumentException("Invalid relative investment weights");
            for(double factor:new double[]{militaryAggression,agriculturalAggression,religiousAggression,artisanAggression})
                if(!Double.isFinite(factor) || factor<=0 || factor>3)throw new IllegalArgumentException("Invalid bounded personality aggression factor");
        }
        public double aggression(Category category){return switch(category){case MILITARY->militaryAggression;case AGRICULTURAL->agriculturalAggression;case RELIGIOUS->religiousAggression;case ARTISAN->artisanAggression;case GENERAL->1.0;};}
    }
    public static ModConfigSpec.IntValue ORDINARY,PREFERRED,WAR_MILITARY,BORDER_MILITARY;
    public static ModConfigSpec.DoubleValue MILITARY_AGGRESSION,AGRICULTURAL_AGGRESSION,RELIGIOUS_AGGRESSION,ARTISAN_AGGRESSION;
    private RoyalNpcConfig(){}
    public static void define(ModConfigSpec.Builder b){
        b.comment("Relative NPC preferences. Legal native choices, current NPC authority, real funds, needs, reserves and cooldowns remain separate gates.").push("royalNpcPolicy");
        ORDINARY=b.comment("Relative weight for a justified non-specialist building, never an absolute purchase probability.").defineInRange("ordinaryWeight",1,1,100);
        PREFERRED=b.comment("Relative weight for a justified building matching the capital personality. Default4:1 keeps alternatives possible.").defineInRange("preferredWeight",4,1,1000);
        WAR_MILITARY=b.comment("Positive weight added to military investments for EVERY personality while an actual war is active. Default6 exceeds the peaceful specialist preference.").defineInRange("warMilitaryBonus",6,1,1000);
        BORDER_MILITARY=b.comment("Additional military investment weight for a genuinely threatened border, independent of base personality.").defineInRange("threatenedBorderBonus",4,0,1000);
        MILITARY_AGGRESSION=b.comment("Multiplier on existing eligible NPC declaration/campaign chances;1.5 is a modest relative increase, never bypassing readiness/truce/cooldown/capital rules.").defineInRange("militaryAggressionFactor",1.5,0.01,3.0);
        AGRICULTURAL_AGGRESSION=b.defineInRange("agriculturalAggressionFactor",0.85,0.01,3.0);
        RELIGIOUS_AGGRESSION=b.defineInRange("religiousAggressionFactor",1.0,0.01,3.0);
        ARTISAN_AGGRESSION=b.defineInRange("artisanAggressionFactor",0.9,0.01,3.0);b.pop();
    }
    public static Weights weights(){
        if(ORDINARY==null)return Weights.DEFAULTS;
        try{return new Weights(ORDINARY.get(),PREFERRED.get(),WAR_MILITARY.get(),BORDER_MILITARY.get(),MILITARY_AGGRESSION.get(),AGRICULTURAL_AGGRESSION.get(),RELIGIOUS_AGGRESSION.get(),ARTISAN_AGGRESSION.get());}
        catch(IllegalStateException unloaded){return Weights.DEFAULTS;}
    }
}
