package com.millenairekingdoms.economy;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server-side administrative prices in real deniers, separate from materials and existing taxes. */
public final class RoyalEconomyConfig {
    public record Settings(int constructionFee,int shipmentBaseFee,int shipmentDistanceFee,int shipmentStackFee) {
        public static final Settings DEFAULTS=new Settings(64,4,1,1);
        public Settings {
            if(constructionFee<=0 || shipmentBaseFee<=0 || shipmentDistanceFee<0 || shipmentStackFee<0) {
                throw new IllegalArgumentException("Royal administration must have positive base fees and nonnegative scaling fees");
            }
        }
    }
    public static ModConfigSpec.IntValue CONSTRUCTION_FEE, SHIPMENT_BASE_FEE, SHIPMENT_DISTANCE_FEE, SHIPMENT_STACK_FEE;
    private RoyalEconomyConfig() {}

    public static void define(ModConfigSpec.Builder b) {
        b.comment("Royal administrative expenditure in actual Millenaire deniers. Materials/goods are still required separately.",
                "These prices do not change the existing tax formula. Orders keep the price accepted by their server-side intent.")
                .push("royalEconomy");
        CONSTRUCTION_FEE=b.comment("Patronage fee for one royal construction request; nonrefundable after durable acceptance.")
                .defineInRange("constructionFeeDeniers",64,1,1_000_000);
        SHIPMENT_BASE_FEE=b.comment("Positive base logistics fee for a shipment, independent of the goods' value.")
                .defineInRange("shipmentBaseFeeDeniers",4,1,1_000_000);
        SHIPMENT_DISTANCE_FEE=b.comment("Extra deniers per started 128 blocks of horizontal route distance.")
                .defineInRange("shipmentFeePer128Blocks",1,0,1_000_000);
        SHIPMENT_STACK_FEE=b.comment("Extra deniers per started group of 64 transported items.")
                .defineInRange("shipmentFeePer64Items",1,0,1_000_000);
        b.pop();
    }

    public static Settings current() {
        if(CONSTRUCTION_FEE==null) return Settings.DEFAULTS;
        try { return new Settings(CONSTRUCTION_FEE.get(),SHIPMENT_BASE_FEE.get(),SHIPMENT_DISTANCE_FEE.get(),SHIPMENT_STACK_FEE.get()); }
        catch(IllegalStateException notLoaded) { return Settings.DEFAULTS; }
    }
}
