package com.millenairekingdoms.shipping;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Gameplay stock safeguards; pricing remains in the existing shared royal economy configuration. */
public final class RoyalShipmentConfig {
    public record Settings(int minimumRemainder,int maximumQuantity) {
        public static final Settings DEFAULTS=new Settings(16,4096);
        public Settings {if(minimumRemainder<0 || maximumQuantity<1 || maximumQuantity>65536)throw new IllegalArgumentException("Invalid royal shipment stock safeguards");}
    }
    /** One minute of staging plus30 seconds per started128 blocks at the normal20 ticks/second. */
    public record Transit(int baseTicks,int ticksPer128) {
        public static final Transit DEFAULTS=new Transit(1200,600);
        public Transit {if(baseTicks<1 || ticksPer128<1)throw new IllegalArgumentException("Shipment transit settings must be positive");}
        public long duration(long distance) {
            if(distance<0)throw new IllegalArgumentException("Negative shipment route distance");
            try{return Math.addExact(baseTicks,Math.multiplyExact(distance/128+(distance%128==0 ? 0 : 1),(long)ticksPer128));}
            catch(ArithmeticException overflow){throw new IllegalArgumentException("Shipment duration is too large",overflow);}
        }
    }
    public static ModConfigSpec.IntValue MINIMUM_REMAINDER,MAXIMUM_QUANTITY,BASE_TRAVEL,TRAVEL_PER128,INTERVAL,MAX_PER_STEP;
    public static ModConfigSpec.BooleanValue TRACKING;
    private RoyalShipmentConfig() {}
    public static void define(ModConfigSpec.Builder b) {
        b.comment("Additional royal-shipment stock safeguards. Native construction, target and resident needs are always protected.").push("royalShipments");
        MINIMUM_REMAINDER=b.comment("Minimum ordinary units of each product retained in the sending town hall, in addition to enforcing native needs.")
                .defineInRange("minimumStockRemaining",16,0,65536);
        MAXIMUM_QUANTITY=b.comment("Maximum real items in one future shipment; does not enable shipments by itself.")
                .defineInRange("maximumQuantity",4096,1,65536);
        BASE_TRAVEL=b.comment("Staging ticks for a new shipment; accepted intents retain their quoted duration.").defineInRange("baseTravelTicks",1200,1,72000);
        TRAVEL_PER128=b.comment("Extra transit ticks per started128 horizontal blocks; default600 is30 seconds at20TPS.").defineInRange("travelTicksPer128Blocks",600,1,72000);
        TRACKING=b.comment("Track only existing paid/issued shipments; no new NPC shipping policy is enabled by this flag.").define("trackingEnabled",true);
        INTERVAL=b.comment("Ticks between bounded existing-shipment passes.").defineInRange("trackingIntervalTicks",100,20,24000);
        MAX_PER_STEP=b.comment("Maximum existing shipments advanced in one pass, one physical/lifecycle boundary per job.").defineInRange("maximumShipmentsPerStep",2,1,16);b.pop();
    }
    public static Transit transit() {
        if(BASE_TRAVEL==null)return Transit.DEFAULTS;
        try{return new Transit(BASE_TRAVEL.get(),TRAVEL_PER128.get());}catch(IllegalStateException unloaded){return Transit.DEFAULTS;}
    }
    public static Settings current() {
        if(MINIMUM_REMAINDER==null)return Settings.DEFAULTS;
        try{return new Settings(MINIMUM_REMAINDER.get(),MAXIMUM_QUANTITY.get());}
        catch(IllegalStateException unloaded){return Settings.DEFAULTS;}
    }
}
