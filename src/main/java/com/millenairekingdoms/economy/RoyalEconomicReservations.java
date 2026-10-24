package com.millenairekingdoms.economy;

import com.millenairekingdoms.shipping.RoyalShipmentStore;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

/** Loaded physical container serialization across money and cargo protocols. Ordinary native trade is unchanged;
 * its activity is handled by same-container proofs or explicit unreceipted-image quarantine. */
@org.jetbrains.annotations.ApiStatus.Internal
public final class RoyalEconomicReservations {
    private RoyalEconomicReservations() {}

    public static void requireTreasuryAvailable(ServerLevel level,List<BlockPos> positions) {
        thread(level);var directory=level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms/royal-shipments");
        if(!Files.exists(directory.resolve("shipments.dat")))return;
        var requested=new HashSet<>(positions);var dimension=level.dimension().location().toString();
        for(var entry:new RoyalShipmentStore(directory).entries())if(entry.intent().dimension().equals(dimension)) {
            for(var op:entry.operations())if(op.pending() && requested.contains(op.position())) {
                throw new IllegalStateException("Physical treasury container has unresolved royal cargo operation "+op.id()+" at "+op.position());
            }
        }
    }

    /** Goods must not establish a new before image over unresolved physical debit/refund evidence. */
    public static void requireCargoAvailable(ServerLevel level,BlockPos position) {
        requireMoneyImagesAvailable(level,List.of(position));
    }
    public static void requireWithdrawalAvailable(ServerLevel level,List<BlockPos> positions) {
        requireTreasuryAvailable(level,positions);requireMoneyImagesAvailable(level,positions);
    }
    private static void requireMoneyImagesAvailable(ServerLevel level,List<BlockPos> positions) {
        thread(level);var requested=new HashSet<>(positions);var directory=level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms/royal-payments");
        if(!Files.exists(directory))return;var dimension=level.dimension().location().toString();
        for(var entry:new RoyalPaymentJournal(directory).entries()) {
            if(entry.state()!=RoyalPaymentJournal.State.PREPARED && entry.state()!=RoyalPaymentJournal.State.REFUND_PREPARED && entry.state()!=RoyalPaymentJournal.State.RECOVERY_REQUIRED)continue;
            var intent=entry.intent();
            if(!intent.contains("dimension",Tag.TAG_STRING))throw new IllegalStateException("Unresolved payment has unknown dimension; preserve its physical evidence");
            if(!dimension.equals(intent.getString("dimension")))continue;
            boolean found=false;
            for(var key:List.of("debit","refund"))if(entry.recovery().contains(key,Tag.TAG_COMPOUND)) {
                found=true;var image=RoyalTreasuryImage.read(level,entry.recovery().getCompound(key));
                if(image.chests().stream().anyMatch(c->requested.contains(c.position())))throw new IllegalStateException("Physical cargo container has unresolved royal payment "+entry.id()+" in requested storage");
            }
            if(!found)throw new IllegalStateException("Unresolved payment has no physical image; preserve journal and postpone cargo");
        }
    }
    private static void thread(ServerLevel level){if(!level.getServer().isSameThread())throw new IllegalStateException("Economic container reservations require the server thread");}
}
