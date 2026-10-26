package com.millenairekingdoms.construction;

import java.nio.file.Files;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

/** Narrow synchronous boundary: retire durable economic mandates before publishing changed political authority.
 * A failed write aborts publication. A crash afterwards may retire an old mandate early, but can never revive it. */
@org.jetbrains.annotations.ApiStatus.Internal
public final class RoyalConstructionGovernance {
    private RoyalConstructionGovernance() {}
    public static void beforePoliticalChange(ServerLevel level,Predicate<RoyalConstructionStore.Intent> invalid) {
        if(!level.getServer().isSameThread()) throw new IllegalStateException("Royal authority retirement requires the server thread");
        var directory=level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms/royal-construction");
        if(Files.exists(directory.resolve("orders.dat"))) {
            String dimension=level.dimension().location().toString();
            new RoyalConstructionStore(directory).retire(intent->intent.dimension().equals(dimension) && invalid.test(intent));
        }
    }
}
