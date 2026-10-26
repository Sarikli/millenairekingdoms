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
    public record BudgetLimits(boolean enabled,long reserve,int sharePermille){
        public static final BudgetLimits DEFAULTS=new BudgetLimits(true,128,250);
        public BudgetLimits{if(reserve<0 || sharePermille<1 || sharePermille>1000)throw new IllegalArgumentException("Invalid NPC spending limits");}
    }
    public static ModConfigSpec.BooleanValue ECONOMIC_ENABLED;
    public static ModConfigSpec.IntValue TREASURY_RESERVE,FEE_SHARE;
    public record Cadence(int attemptDays,int projectDays,int villageDays,int routeDays,int maximumVillages,int maximumCargo,int maximumDistance,int intervalTicks,int maximumKingdoms){
        public static final Cadence DEFAULTS=new Cadence(1,3,6,3,8,64,2048,600,2);
        public Cadence{if(attemptDays<1 || projectDays<attemptDays || villageDays<projectDays || routeDays<1 || maximumVillages<1 || maximumVillages>32
                || maximumCargo<1 || maximumCargo>4096 || maximumDistance<1 || intervalTicks<20 || maximumKingdoms<1 || maximumKingdoms>16)throw new IllegalArgumentException("Invalid bounded NPC decision cadence");}
    }
    public static ModConfigSpec.IntValue ATTEMPT_DAYS,PROJECT_DAYS,VILLAGE_DAYS,ROUTE_DAYS,MAX_VILLAGES,MAX_CARGO,MAX_DISTANCE,INTERVAL_TICKS,MAX_KINGDOMS;
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
        ARTISAN_AGGRESSION=b.defineInRange("artisanAggressionFactor",0.9,0.01,3.0);
        ECONOMIC_ENABLED=b.comment("Allow new NPC economic decisions and their first fee; existing paid domain recovery remains active.").define("economicDecisionsEnabled",true);
        TREASURY_RESERVE=b.comment("Minimum actual deniers retained after a policy-owned first fee. Default128 protects two silver coins; owed taxes do not count.").defineInRange("treasuryReserveDeniers",128,0,1_000_000_000);
        FEE_SHARE=b.comment("Maximum first fee as permille of current actual treasury. Default250 caps one major fee at a quarter; native material costs remain independent.").defineInRange("maximumFeeSharePermille",250,1,1000);
        ATTEMPT_DAYS=b.comment("Minimum days between persisted economic attempts, including unavailable/failed decisions.").defineInRange("attemptIntervalDays",1,1,120);
        PROJECT_DAYS=b.comment("Minimum days between new royal project attempts per kingdom; must be at least attemptIntervalDays.").defineInRange("projectCooldownDays",3,1,240);
        VILLAGE_DAYS=b.comment("Minimum days between royal project attempts in the same village; must be at least projectCooldownDays.").defineInRange("villageProjectCooldownDays",6,1,480);
        ROUTE_DAYS=b.comment("Minimum days between shipments in either direction on a village pair; artisan capitals use half, at least1.").defineInRange("routeCooldownDays",3,1,120);
        MAX_VILLAGES=b.comment("Maximum member metadata/stock candidates per decision; rotating window, artisan capitals inspect up to twice this, capped32.").defineInRange("maximumVillagesPerDecision",8,1,32);
        MAX_CARGO=b.comment("Maximum real goods in an NPC shortage shipment; shared stock/reserve/transport rules still apply.").defineInRange("maximumCargoPerDecision",64,1,4096);
        MAX_DISTANCE=b.comment("Maximum horizontal route considered for an NPC shortage shipment; distant chunks are never forced.").defineInRange("maximumShipmentDistance",2048,1,100_000);
        INTERVAL_TICKS=b.comment("Ticks between bounded NPC economic checks. Persistent day cooldowns limit actual decisions.").defineInRange("checkIntervalTicks",600,20,24000);
        MAX_KINGDOMS=b.comment("Maximum kingdoms inspected per pass, round robin.").defineInRange("maximumKingdomsPerCheck",2,1,16);b.pop();
    }
    public static Weights weights(){
        if(ORDINARY==null)return Weights.DEFAULTS;
        try{return new Weights(ORDINARY.get(),PREFERRED.get(),WAR_MILITARY.get(),BORDER_MILITARY.get(),MILITARY_AGGRESSION.get(),AGRICULTURAL_AGGRESSION.get(),RELIGIOUS_AGGRESSION.get(),ARTISAN_AGGRESSION.get());}
        catch(IllegalStateException unloaded){return Weights.DEFAULTS;}
    }
    public static BudgetLimits budgets(){
        if(ECONOMIC_ENABLED==null)return BudgetLimits.DEFAULTS;
        try{return new BudgetLimits(ECONOMIC_ENABLED.get(),TREASURY_RESERVE.get(),FEE_SHARE.get());}
        catch(IllegalStateException unloaded){return BudgetLimits.DEFAULTS;}
    }
    public static Cadence cadence(){
        if(ATTEMPT_DAYS==null)return Cadence.DEFAULTS;
        try{return new Cadence(ATTEMPT_DAYS.get(),PROJECT_DAYS.get(),VILLAGE_DAYS.get(),ROUTE_DAYS.get(),MAX_VILLAGES.get(),MAX_CARGO.get(),MAX_DISTANCE.get(),INTERVAL_TICKS.get(),MAX_KINGDOMS.get());}
        catch(IllegalStateException unloaded){return Cadence.DEFAULTS;}
    }
}
