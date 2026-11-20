package com.millenairekingdoms.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.millenairekingdoms.capital.NaturalCapitals;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import org.millenaire.culture.VillageType;
import org.millenaire.world.SiteHunter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Since Millénaire 9.1.0 every natural full or small village is founded by SiteHunter.attempt (SiteHunter.ENABLED is
 * true unless -Dmillenaire.siteHunter=false), not by VillageSpawnQueue.trySpawnVillage. Without this hook no natural
 * village ever reaches the capital rule. Like {@link VillageSpawnQueueMixin} the call is unchanged: same arguments,
 * same result; commands, quests, hamlets and player villages never pass through here.
 */
@Mixin(value = SiteHunter.class, remap = false)
public abstract class SiteHunterMixin {
    @WrapOperation(method = "attempt", require = 1,
            at = @At(value = "INVOKE",
                    target =
                            "Lorg/millenaire/world/VillageSpawner;spawnVillage(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lorg/millenaire/culture/VillageType;I)Lnet/minecraft/network/chat/Component;"))
    private Component
    kingdoms$naturalCapital(
            ServerLevel level, BlockPos pos, VillageType type, int completion, Operation<Component> spawn) {
        return NaturalCapitals.onNaturalSpawn(level, type, () -> spawn.call(level, pos, type, completion));
    }
}
