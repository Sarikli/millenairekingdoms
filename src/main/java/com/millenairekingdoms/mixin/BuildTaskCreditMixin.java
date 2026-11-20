package com.millenairekingdoms.mixin;

import com.millenairekingdoms.virtualeconomy.VirtualConstructionBoosts;
import org.millenaire.building.BuildingInstance;
import org.millenaire.building.PlacementStep;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Background construction credit earned while a village was unloaded shortens the native builder's per-block action
 * time for that one building. The villager still walks, fetches and places every block itself; no step is skipped.
 */
@Mixin(targets = "org.millenaire.goal.impl.BuildGoal$BuildTask", remap = false)
public abstract class BuildTaskCreditMixin {
    @Shadow
    @Final
    private BuildingInstance building;

    @Inject(method = "getActionDuration(Lorg/millenaire/building/PlacementStep;)I", at = @At("RETURN"),
            cancellable = true, require = 1)
    private void
    kingdoms$virtualCredit(PlacementStep step, CallbackInfoReturnable<Integer> callback) {
        int scaled = VirtualConstructionBoosts.scale(building, callback.getReturnValueI());
        if (scaled != callback.getReturnValueI())
            callback.setReturnValue(scaled);
    }
}
