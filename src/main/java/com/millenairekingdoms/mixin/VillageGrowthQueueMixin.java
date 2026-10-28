package com.millenairekingdoms.mixin;

import com.millenairekingdoms.construction.RoyalConstructionRuntime;
import net.minecraft.server.level.ServerLevel;
import org.millenaire.village.Village;
import org.millenaire.village.VillageGrowthManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Original9.0.3 has no pre-growth event. The original method always continues with the same arguments;
 * an existing persisted unpaid mandate may claim an empty ordinary slot before another natural choice. */
@Mixin(value=VillageGrowthManager.class,remap=false)
public abstract class VillageGrowthQueueMixin {
    @Inject(method="evaluateGrowth(Lnet/minecraft/server/level/ServerLevel;Lorg/millenaire/village/Village;)V",at=@At("HEAD"),require=1)
    private static void kingdoms$waitingMandate(ServerLevel level,Village village,CallbackInfo callback) {
        RoyalConstructionRuntime.beforeNativeGrowth(level,village);
    }
}
