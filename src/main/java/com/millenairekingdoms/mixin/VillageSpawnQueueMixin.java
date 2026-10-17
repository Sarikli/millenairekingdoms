package com.millenairekingdoms.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.millenairekingdoms.capital.NaturalCapitals;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import org.millenaire.culture.VillageType;
import org.millenaire.world.VillageSpawnQueue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Millénaire 9.0.2 has no event for natural village creation. This call site in VillageSpawnQueue.trySpawnVillage
 * runs only for natural world generation; commands (SpawnVillageCommand, SpawnLoneBuildingCommand), quests, hamlets and
 * player villages (VillageCreationRequestPayload) call spawnVillage elsewhere and are never wrapped. The call itself is
 * unchanged: same arguments, same result. */
@Mixin(value = VillageSpawnQueue.class, remap = false)
public abstract class VillageSpawnQueueMixin {
    @WrapOperation(method = "trySpawnVillage", require = 1, at = @At(value = "INVOKE",
            target = "Lorg/millenaire/world/VillageSpawner;spawnVillage(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lorg/millenaire/culture/VillageType;I)Lnet/minecraft/network/chat/Component;"))
    private Component kingdoms$naturalCapital(ServerLevel level, BlockPos pos, VillageType type, int completion, Operation<Component> spawn) {
        return NaturalCapitals.onNaturalSpawn(level, type, () -> spawn.call(level, pos, type, completion));
    }
}
