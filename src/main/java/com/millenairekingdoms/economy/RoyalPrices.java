package com.millenairekingdoms.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import net.minecraft.core.BlockPos;

/** Domain services compute these prices on the server; never trust a client-provided amount or distance. */
public final class RoyalPrices {
    private RoyalPrices() {}
    public static long construction(RoyalEconomyConfig.Settings settings) {
        return settings.constructionFee();
    }
    /** Native target0 is initial construction; target1 (display level2) costs two base fees. */
    public static long upgrade(RoyalEconomyConfig.Settings settings, int targetLevel) {
        if (targetLevel < 1 || targetLevel > 256)
            throw new IllegalArgumentException("Invalid native upgrade level");
        return Math.multiplyExact((long) settings.constructionFee(), targetLevel + 1L);
    }
    public static long shipment(RoyalEconomyConfig.Settings settings, long quantity, long horizontalDistanceBlocks) {
        if (quantity <= 0 || horizontalDistanceBlocks < 0)
            throw new IllegalArgumentException("Invalid shipment quantity or route distance");
        try {
            long distance = Math.multiplyExact(ceil(horizontalDistanceBlocks, 128), settings.shipmentDistanceFee());
            long load = Math.multiplyExact(ceil(quantity, 64), settings.shipmentStackFee());
            return Math.addExact(settings.shipmentBaseFee(), Math.addExact(distance, load));
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Shipment logistics price is too large", overflow);
        }
    }
    /** Horizontal Euclidean distance in blocks without rounding intermediate terms. */
    public static double horizontalDistance(BlockPos source, BlockPos target) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        double dx = (double) source.getX() - target.getX();
        double dz = (double) source.getZ() - target.getZ();
        double distance = Math.hypot(dx, dz);
        if (!Double.isFinite(distance) || distance < 0.0)
            throw new IllegalArgumentException("Invalid horizontal distance");
        return distance;
    }
    /**
     * Upfront conquest expedition fee in base deniers:
     * ceil(strength * (offset + distance) / divisor), rounded upward once.
     */
    public static long expedition(
            RoyalEconomyConfig.Settings settings, int strength, double horizontalDistanceBlocks) {
        Objects.requireNonNull(settings, "settings");
        if (strength <= 0)
            throw new IllegalArgumentException("Expedition strength must be positive");
        if (!Double.isFinite(horizontalDistanceBlocks) || horizontalDistanceBlocks < 0.0)
            throw new IllegalArgumentException("Expedition distance must be finite and non-negative");
        BigDecimal factor = BigDecimal.valueOf(settings.expeditionDistanceOffset())
                                    .add(BigDecimal.valueOf(horizontalDistanceBlocks));
        BigDecimal raw = BigDecimal.valueOf(strength).multiply(factor);
        BigDecimal fee = raw.divide(BigDecimal.valueOf(settings.expeditionStrengthDivisor()), 0, RoundingMode.CEILING);
        try {
            long result = fee.longValueExact();
            if (result <= 0)
                throw new IllegalArgumentException("Expedition fee must be positive");
            return result;
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Expedition logistics price is too large", overflow);
        }
    }
    /** NPC economic reserve: max(32, 2 * dailyIncome) in base deniers. */
    public static long npcReserve(long dailyIncome) {
        if (dailyIncome < 0)
            throw new IllegalArgumentException("Daily income cannot be negative");
        return Math.max(32L, Math.multiplyExact(2L, dailyIncome));
    }
    /** Whether actual spendable treasury covers cost + reserve without overflowing. */
    public static boolean npcCanAfford(long spendableTreasury, long cost, long reserve) {
        if (spendableTreasury < 0 || cost <= 0 || reserve < 0)
            return false;
        return spendableTreasury >= reserve && cost <= spendableTreasury - reserve;
    }
    private static long ceil(long value, long divisor) {
        return value / divisor + (value % divisor == 0 ? 0 : 1);
    }
}
