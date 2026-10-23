package com.millenairekingdoms.economy;

/** Domain services compute these prices on the server; never trust a client-provided amount or distance. */
public final class RoyalPrices {
    private RoyalPrices() {}
    public static long construction(RoyalEconomyConfig.Settings settings) { return settings.constructionFee(); }
    public static long shipment(RoyalEconomyConfig.Settings settings,long quantity,long horizontalDistanceBlocks) {
        if(quantity<=0 || horizontalDistanceBlocks<0) throw new IllegalArgumentException("Invalid shipment quantity or route distance");
        try {
            long distance=Math.multiplyExact(ceil(horizontalDistanceBlocks,128),settings.shipmentDistanceFee());
            long load=Math.multiplyExact(ceil(quantity,64),settings.shipmentStackFee());
            return Math.addExact(settings.shipmentBaseFee(),Math.addExact(distance,load));
        } catch(ArithmeticException overflow) { throw new IllegalArgumentException("Shipment logistics price is too large",overflow); }
    }
    private static long ceil(long value,long divisor) { return value/divisor+(value%divisor==0 ? 0 : 1); }
}
