package com.millenairekingdoms.shipping;

import java.nio.file.Files;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;

/** Persist mandate retirement BEFORE publishing new politics. PREPARING retains its phase through an in-flight fee;
 * the original paid/refundable receipt is later settled, never discarded by same-tick abdicate/reclaim. */
@org.jetbrains.annotations.ApiStatus.Internal
public final class RoyalShipmentGovernance {
    private RoyalShipmentGovernance() {}
    public static void beforePoliticalChange(ServerLevel level,Predicate<RoyalShipmentStore.Intent> invalid){
        if(!level.getServer().isSameThread())throw new IllegalStateException("Shipment retirement requires the server thread");
        var directory=RoyalShipmentService.directory(level);if(Files.exists(directory.resolve("shipments.dat"))){var dimension=level.dimension().location().toString();new RoyalShipmentStore(directory).retire(i->i.dimension().equals(dimension) && invalid.test(i));}
    }
}
