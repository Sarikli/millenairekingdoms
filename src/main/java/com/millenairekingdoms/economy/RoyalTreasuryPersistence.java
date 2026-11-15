package com.millenairekingdoms.economy;

import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkDataEvent;

/**
 * Targeted ordinary chunk serialization, acknowledged writes, flush and disk read-back.
 * No chunk generation/loading and no reliance on ChunkMap.save's swallowed write exceptions.
 * This is a process-crash barrier on the supported filesystem, not a multi-file atomic commit.
 */
public class RoyalTreasuryPersistence {
    private static com.mojang.serialization.Codec<PalettedContainer<BlockState>> states(net.minecraft.core.RegistryAccess registries) {
        return net.minecraft.world.level.chunk.PalettedContainerFactory.create(registries).blockStatesContainerCodec();
    }
    public void flush(ServerLevel level, List<BlockPos> positions) {
        var source = level.getChunkSource();
        var map = source.chunkMap;
        for (var pos : positions.stream().map(ChunkPos::containing).distinct().toList()) {
            var chunk = source.getChunkNow(pos.x(), pos.z());
            if (chunk == null)
                throw new IllegalStateException("Treasury chunk unloaded before persistence: " + pos);
            SerializableChunkData data = SerializableChunkData.copyOf(level, chunk);
            NeoForge.EVENT_BUS.post(new ChunkDataEvent.Save(chunk, level, data));
            map.write(pos, data::write).join();
        }
        map.synchronize(true).join();
    }

    void verify(ServerLevel level, RoyalTreasuryImage image, boolean after) {
        verify(level, image, after, null);
    }

    void verifyApplied(ServerLevel level, RoyalTreasuryImage image, List<ItemStack> expected) {
        verify(level, image, true, expected);
    }

    private void verify(ServerLevel level, RoyalTreasuryImage image, boolean after, List<ItemStack> expected) {
        var chunks = new LinkedHashMap<ChunkPos, CompoundTag>();
        int offset = 0;
        for (var chest : image.chests()) {
            var pos = chest.position();
            var chunkPos = ChunkPos.containing(pos);
            CompoundTag chunk = chunks.computeIfAbsent(chunkPos,
                    key
                    -> level.getChunkSource().chunkMap.read(key).join().orElseThrow(
                            () -> new IllegalStateException("Treasury chunk was not written: " + key)));
            CompoundTag found = null;
            for (Tag raw : chunk.getListOrEmpty("block_entities")) {
                var tag = (CompoundTag) raw;
                if (BlockEntity.getPosFromTag(chunkPos, tag).equals(pos)) {
                    if (found != null)
                        throw new IllegalStateException("Duplicate saved treasury block entity: " + pos);
                    found = tag;
                }
            }
            BlockState state = savedState(level.registryAccess(), chunk, pos);
            if (!chest.state().equals(NbtUtils.writeBlockState(state)) || found == null
                    || !(BlockEntity.loadStatic(pos, state, found, level.registryAccess())
                                    instanceof ChestBlockEntity saved)
                    || !(expected == null
                                    ? chest.matches(saved, after)
                                    : image.matchesApplied(chest, saved, expected.subList(offset, offset + 27)))) {
                throw new IllegalStateException("Treasury disk read-back differs from intended "
                        + (after ? "after" : "before") + " image: " + pos);
            }
            offset += 27;
        }
    }

    static BlockState savedState(net.minecraft.core.RegistryAccess registries, CompoundTag chunk, BlockPos position) {
        for (Tag raw : chunk.getListOrEmpty("sections")) {
            var section = (CompoundTag) raw;
            if (section.getByteOr("Y", (byte) 0) == Math.floorDiv(position.getY(), 16)
                    && section.contains("block_states")) {
                return states(registries).parse(NbtOps.INSTANCE, section.getCompoundOrEmpty("block_states"))
                        .getOrThrow()
                        .get(position.getX() & 15, position.getY() & 15, position.getZ() & 15);
            }
        }
        throw new IllegalStateException("Missing saved treasury block section at " + position);
    }
}
