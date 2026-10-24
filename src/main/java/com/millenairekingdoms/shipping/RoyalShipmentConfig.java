package com.millenairekingdoms.shipping;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Gameplay stock safeguards; pricing remains in the existing shared royal economy configuration. */
public final class RoyalShipmentConfig {
    public record Settings(int minimumRemainder,int maximumQuantity) {
        public static final Settings DEFAULTS=new Settings(16,4096);
        public Settings {if(minimumRemainder<0 || maximumQuantity<1 || maximumQuantity>65536)throw new IllegalArgumentException("Invalid royal shipment stock safeguards");}
    }
    public static ModConfigSpec.IntValue MINIMUM_REMAINDER,MAXIMUM_QUANTITY;
    private RoyalShipmentConfig() {}
    public static void define(ModConfigSpec.Builder b) {
        b.comment("Additional royal-shipment stock safeguards. Native construction, target and resident needs are always protected.").push("royalShipments");
        MINIMUM_REMAINDER=b.comment("Minimum ordinary units of each product retained in the sending town hall, in addition to enforcing native needs.")
                .defineInRange("minimumStockRemaining",16,0,65536);
        MAXIMUM_QUANTITY=b.comment("Maximum real items in one future shipment; does not enable shipments by itself.")
                .defineInRange("maximumQuantity",4096,1,65536);b.pop();
    }
    public static Settings current() {
        if(MINIMUM_REMAINDER==null)return Settings.DEFAULTS;
        try{return new Settings(MINIMUM_REMAINDER.get(),MAXIMUM_QUANTITY.get());}
        catch(IllegalStateException unloaded){return Settings.DEFAULTS;}
    }
}
