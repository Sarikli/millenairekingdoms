package com.millenairekingdoms.economy;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/** Internal operation proofs serialized by the native chest serializer alongside Items.
 * Unknown/corrupt data is never replaced. Only the owning acknowledged operation may remove its proof. */
final class RoyalContainerReceipts {
    private static final String KEY="millenairekingdoms:royal_operations";
    private static final int LIMIT=256;
    private RoyalContainerReceipts() {}

    static boolean contains(ChestBlockEntity chest,UUID operation,CompoundTag binding) {
        var entries=read(chest);
        for(Tag raw:entries) {
            var entry=(CompoundTag)raw;
            if(entry.getUUID("id").equals(operation)) {
                if(!entry.getCompound("binding").equals(binding)) throw new IllegalStateException("Physical operation receipt binding differs at "+chest.getBlockPos());
                return true;
            }
        }
        return false;
    }

    /** Preflight all metadata before any slot mutation; record() is then non-failing on the server thread. */
    static void requireRoom(ChestBlockEntity chest,UUID operation,CompoundTag binding) {
        if(!contains(chest,operation,binding) && read(chest).size()>=LIMIT) {
            throw new IllegalStateException("Physical operation receipt limit reached; preserve unresolved proofs at "+chest.getBlockPos());
        }
    }

    static void record(ChestBlockEntity chest,UUID operation,CompoundTag binding) {
        requireRoom(chest,operation,binding);
        if(contains(chest,operation,binding)) return;
        var entries=read(chest).copy(); var entry=new CompoundTag(); entry.putUUID("id",operation); entry.put("binding",binding.copy()); entries.add(entry);
        write(chest,entries);
    }

    static void remove(ChestBlockEntity chest,UUID operation,CompoundTag binding) {
        if(!contains(chest,operation,binding)) return;
        var entries=read(chest).copy();
        entries.removeIf(raw->((CompoundTag)raw).getUUID("id").equals(operation));
        write(chest,entries);
    }

    private static ListTag read(ChestBlockEntity chest) {
        var data=chest.getPersistentData();
        if(!data.contains(KEY)) return new ListTag();
        if(!data.contains(KEY,Tag.TAG_COMPOUND)) throw invalid(chest);
        var tag=data.getCompound(KEY);
        if(!tag.getAllKeys().equals(Set.of("format","entries")) || !tag.contains("format",Tag.TAG_INT) || tag.getInt("format")!=1
                || !tag.contains("entries",Tag.TAG_LIST)) throw invalid(chest);
        var entries=(ListTag)tag.get("entries");
        if((!entries.isEmpty() && entries.getElementType()!=Tag.TAG_COMPOUND)
                || entries.size()>LIMIT) throw invalid(chest);
        var ids=new HashSet<UUID>();
        for(Tag raw:entries) {
            var entry=(CompoundTag)raw;
            if(!entry.getAllKeys().equals(Set.of("id","binding")) || !entry.hasUUID("id") || !entry.contains("binding",Tag.TAG_COMPOUND)
                    || entry.getCompound("binding").isEmpty() || !ids.add(entry.getUUID("id"))) throw invalid(chest);
        }
        return entries;
    }
    private static void write(ChestBlockEntity chest,ListTag entries) {
        if(entries.isEmpty()) chest.getPersistentData().remove(KEY);
        else { var tag=new CompoundTag(); tag.putInt("format",1); tag.put("entries",entries); chest.getPersistentData().put(KEY,tag); }
        chest.setChanged();
    }
    private static IllegalStateException invalid(ChestBlockEntity chest) {
        return new IllegalStateException("Unsupported/corrupt physical operation receipts; preserve chest data at "+chest.getBlockPos());
    }
}
