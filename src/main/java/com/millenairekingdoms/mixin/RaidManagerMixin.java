package com.millenairekingdoms.mixin;

import com.millenairekingdoms.conquest.ConquestService;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import org.millenaire.combat.raid.RaidManager;
import org.millenaire.village.Village;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Descriptors and argument order verified against the unmodified Millénaire 9.0.2 JAR. */
@Mixin(value = RaidManager.class, remap = false)
public abstract class RaidManagerMixin {
    @Inject(method = "applyLooting(Lorg/millenaire/village/Village;Lorg/millenaire/village/Village;Lnet/minecraft/server/level/ServerLevel;)Ljava/util/Map;",
            at = @At("HEAD"), cancellable = true, require = 1)
    private static void kingdoms$preventConquestLoot(Village attacker, Village target, ServerLevel level,
                                                     CallbackInfoReturnable<Map<Item, Integer>> callback) {
        if (ConquestService.suppressLoot(attacker, target, level)) callback.setReturnValue(Map.of());
    }

    @Inject(method = "endRaid(Lorg/millenaire/village/Village;Lorg/millenaire/village/Village;Lnet/minecraft/server/level/ServerLevel;)V",
            at = @At(value = "INVOKE", target = "Lorg/millenaire/village/Village;clearRaid()V"), require = 1)
    private static void kingdoms$finishConquest(Village attacker, Village target, ServerLevel level, CallbackInfo callback) {
        ConquestService.finish(attacker, target, level);
    }
}
