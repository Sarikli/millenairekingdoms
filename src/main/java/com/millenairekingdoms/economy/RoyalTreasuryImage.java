package com.millenairekingdoms.economy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/** Complete versioned physical before/after images, including non-currency slots and durable chest identity. */
final class RoyalTreasuryImage {
    private static final String IDENTITY="millenairekingdoms:royal_treasury";
    private static final int FORMAT=1, MAX_CHESTS=256;
    record Chest(BlockPos position, UUID identity, CompoundTag state, List<ItemStack> before, List<ItemStack> after) {
        boolean matches(ChestBlockEntity entity,boolean changed) {
            if(!identity.equals(RoyalTreasuryImage.identity(entity,false))) return false;
            var expected=changed ? after : before;
            if(entity.getContainerSize()!=expected.size()) return false;
            for(int i=0;i<expected.size();i++) if(!ItemStack.matches(entity.getItem(i),expected.get(i))) return false;
            return true;
        }
    }
    private final List<Chest> chests;
    private RoyalTreasuryImage(List<Chest> chests) { this.chests=List.copyOf(chests); }
    List<Chest> chests() { return chests; }

    static RoyalTreasuryImage create(ServerLevel level,RoyalTreasurySnapshot quote,RoyalCoinPlan.Plan plan) {
        quote.requireUnchanged();
        var positions=quote.positions();
        if(positions.size()>MAX_CHESTS) throw new IllegalArgumentException("Capital treasury has too many physical chests");
        var before=plan.before(); var after=plan.after(); var result=new ArrayList<Chest>(); var ids=new HashSet<UUID>();
        int offset=0;
        for(BlockPos pos:positions) {
            var entity=(ChestBlockEntity)level.getBlockEntity(pos); int size=entity.getContainerSize();
            UUID id=identity(entity,true);
            if(!ids.add(id)) throw new IllegalStateException("Duplicate physical treasury identity: "+pos);
            result.add(new Chest(pos,id,NbtUtils.writeBlockState(entity.getBlockState()),
                    List.copyOf(before.subList(offset,offset+size)),List.copyOf(after.subList(offset,offset+size))));
            offset+=size;
        }
        if(offset!=before.size() || offset!=after.size()) throw new IllegalStateException("Treasury plan size mismatch");
        return new RoyalTreasuryImage(result);
    }

    /** Every physical half must still be exactly one of its recorded images. Never overwrite third-party edits. */
    List<ItemStack> reconcile(ServerLevel level,RoyalTreasurySnapshot quote) {
        quote.requireUnchanged(); requireStorage(level,quote);
        var result=new ArrayList<ItemStack>();
        for(Chest chest:chests) {
            var live=(ChestBlockEntity)level.getBlockEntity(chest.position);
            if(!chest.matches(live,false) && !chest.matches(live,true)) {
                throw new IllegalStateException("Treasury differs from both recorded slot images at "+chest.position);
            }
            chest.after.forEach(stack->result.add(stack.copy()));
        }
        return result;
    }

    void requireStorage(ServerLevel level,RoyalTreasurySnapshot quote) {
        if(!quote.positions().equals(chests.stream().map(Chest::position).toList())) {
            throw new IllegalStateException("Capital treasury positions changed since payment");
        }
        for(Chest chest:chests) {
            var entity=level.getBlockEntity(chest.position);
            if(!(entity instanceof ChestBlockEntity live) || !chest.identity.equals(identity(live,false))
                    || !chest.state.equals(NbtUtils.writeBlockState(live.getBlockState()))) {
                throw new IllegalStateException("Treasury identity or connection changed at "+chest.position);
            }
        }
    }

    void requireAfter(ServerLevel level,RoyalTreasurySnapshot quote) {
        quote.requireUnchanged(); requireStorage(level,quote);
        for(Chest chest:chests) if(!chest.matches((ChestBlockEntity)level.getBlockEntity(chest.position),true)) {
            throw new IllegalStateException("Live treasury changed during persistence at "+chest.position);
        }
    }

    private static UUID identity(ChestBlockEntity chest,boolean create) {
        CompoundTag data=chest.getPersistentData();
        if(!data.contains(IDENTITY)) {
            if(!create) return null;
            var tag=new CompoundTag(); tag.putInt("format",FORMAT); tag.putUUID("id",UUID.randomUUID());
            data.put(IDENTITY,tag); chest.setChanged();
        }
        var tag=data.getCompound(IDENTITY);
        if(tag.getInt("format")!=FORMAT || !tag.hasUUID("id")) throw new IllegalStateException("Unsupported/corrupt treasury identity; preserve chest data");
        return tag.getUUID("id");
    }

    CompoundTag save(ServerLevel level) {
        var tag=new CompoundTag(); tag.putInt("format",FORMAT); var list=new ListTag();
        for(Chest chest:chests) {
            var item=new CompoundTag(); item.putLong("position",chest.position.asLong()); item.putUUID("identity",chest.identity);
            item.put("state",chest.state.copy()); item.put("before",saveSlots(level,chest.before)); item.put("after",saveSlots(level,chest.after)); list.add(item);
        }
        tag.put("chests",list); return tag;
    }

    static RoyalTreasuryImage read(ServerLevel level,CompoundTag tag) {
        if(tag.getInt("format")!=FORMAT || !tag.contains("chests",Tag.TAG_LIST)) throw new IllegalStateException("Unsupported treasury image; preserve payment journal");
        var list=tag.getList("chests",Tag.TAG_COMPOUND);
        if(list.isEmpty() || list.size()>MAX_CHESTS) throw new IllegalStateException("Invalid treasury image size");
        var result=new ArrayList<Chest>(); var positions=new HashSet<BlockPos>(); var ids=new HashSet<UUID>();
        for(Tag raw:list) {
            var item=(CompoundTag)raw;
            if(!item.contains("position",Tag.TAG_LONG) || !item.hasUUID("identity") || !item.contains("state",Tag.TAG_COMPOUND)) {
                throw new IllegalStateException("Incomplete treasury chest image");
            }
            var pos=BlockPos.of(item.getLong("position")); var id=item.getUUID("identity");
            if(!positions.add(pos) || !ids.add(id)) throw new IllegalStateException("Duplicate treasury image position or identity");
            result.add(new Chest(pos,id,item.getCompound("state").copy(),readSlots(level,item,"before"),readSlots(level,item,"after")));
        }
        return new RoyalTreasuryImage(result);
    }

    long value(boolean after) { return RoyalCoinPlan.spendableValue(chests.stream().flatMap(c->(after ? c.after : c.before).stream()).toList()); }

    void requireDirection(boolean refund) {
        for(Chest chest:chests) {
            long delta=RoyalCoinPlan.spendableValue(chest.after)-RoyalCoinPlan.spendableValue(chest.before);
            if(refund ? delta<0 : delta>0) throw new IllegalStateException("Payment image transfers value between independently saved chests; preserve journal");
        }
    }

    RoyalCoinPlan.Plan refundPlan(RoyalTreasurySnapshot quote) {
        var before=quote.slots(); var after=new ArrayList<ItemStack>(); int offset=0;
        for(Chest chest:chests) {
            long amount=RoyalCoinPlan.spendableValue(chest.before)-RoyalCoinPlan.spendableValue(chest.after);
            var current=before.subList(offset,offset+27);
            after.addAll(amount==0 ? current : RoyalCoinPlan.credit(current,amount).after());
            offset+=27;
        }
        return new RoyalCoinPlan.Plan(before,after,RoyalCoinPlan.spendableValue(before),RoyalCoinPlan.spendableValue(after));
    }

    private static ListTag saveSlots(ServerLevel level,List<ItemStack> slots) {
        var list=new ListTag(); for(ItemStack stack:slots) list.add(stack.saveOptional(level.registryAccess())); return list;
    }
    private static List<ItemStack> readSlots(ServerLevel level,CompoundTag tag,String key) {
        var list=tag.getList(key,Tag.TAG_COMPOUND);
        if(list.size()!=27) throw new IllegalStateException("Incomplete physical chest slot image");
        var result=new ArrayList<ItemStack>();
        for(Tag raw:list) {
            var item=(CompoundTag)raw;
            result.add(item.isEmpty() ? ItemStack.EMPTY : ItemStack.parse(level.registryAccess(),item)
                    .orElseThrow(()->new IllegalStateException("Invalid item in treasury slot image")));
        }
        return List.copyOf(result);
    }
}
