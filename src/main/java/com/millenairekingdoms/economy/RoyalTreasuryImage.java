package com.millenairekingdoms.economy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.Set;
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
    private static final int FORMAT=2, MAX_CHESTS=256;
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
    private final UUID payment;
    private final CompoundTag intent;
    private final boolean refund;
    private RoyalTreasuryImage(List<Chest> chests,UUID payment,CompoundTag intent,boolean refund) {
        this.chests=List.copyOf(chests); this.payment=payment; this.intent=intent.copy(); this.refund=refund;
    }
    List<Chest> chests() { return chests; }

    static RoyalTreasuryImage create(ServerLevel level,RoyalTreasurySnapshot quote,RoyalCoinPlan.Plan plan,UUID payment,CompoundTag intent,boolean refund) {
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
        return new RoyalTreasuryImage(result,payment,intent,refund);
    }

    /** A receipt proves application even after legitimate native activity. Without one only BEFORE is safe. */
    List<ItemStack> reconcile(ServerLevel level,RoyalTreasurySnapshot quote) {
        quote.requireUnchanged(); requireStorage(level,quote);
        if(payment==null) throw new IllegalStateException("Legacy pending treasury image has no physical operation proof; explicit recovery required");
        var result=new ArrayList<ItemStack>();
        for(Chest chest:chests) {
            var live=(ChestBlockEntity)level.getBlockEntity(chest.position);
            boolean applied=RoyalContainerReceipts.contains(live,operation(chest),binding(chest));
            if(changed(chest)) {
                RoyalContainerReceipts.requireRoom(live,operation(chest),binding(chest));
                if(!applied && !chest.matches(live,false)) {
                    throw new IllegalStateException("Unreceipted treasury differs from recorded before image at "+chest.position);
                }
            }
            // Untouched halves and already applied operations preserve all current slots, including native deposits/withdrawals.
            if(applied || !changed(chest)) for(int slot=0;slot<live.getContainerSize();slot++) result.add(live.getItem(slot).copy());
            else chest.after.forEach(stack->result.add(stack.copy()));
        }
        return result;
    }

    void record(ServerLevel level) {
        for(Chest chest:chests) if(changed(chest)) RoyalContainerReceipts.record(
                (ChestBlockEntity)level.getBlockEntity(chest.position),operation(chest),binding(chest));
    }

    /** Called only AFTER the journal durably acknowledges PAID/REFUNDED; cleanup is not financial work. */
    void cleanup(ServerLevel level) {
        for(Chest chest:chests) if(changed(chest)) RoyalContainerReceipts.remove(
                (ChestBlockEntity)level.getBlockEntity(chest.position),operation(chest),binding(chest));
    }

    void requireBinding(UUID payment,CompoundTag intent,boolean refund) {
        if(this.payment!=null && (!this.payment.equals(payment) || !this.intent.equals(intent) || this.refund!=refund)) {
            throw new IllegalStateException("Treasury image belongs to another payment intent or operation role; preserve journal");
        }
    }

    boolean matchesApplied(Chest chest,ChestBlockEntity entity,List<ItemStack> expected) {
        if(!chest.identity.equals(identity(entity,false)) || entity.getContainerSize()!=expected.size()) return false;
        for(int slot=0;slot<expected.size();slot++) if(!ItemStack.matches(expected.get(slot),entity.getItem(slot))) return false;
        return !changed(chest) || RoyalContainerReceipts.contains(entity,operation(chest),binding(chest));
    }

    private static boolean changed(Chest chest) {
        for(int slot=0;slot<chest.before.size();slot++) if(!ItemStack.matches(chest.before.get(slot),chest.after.get(slot))) return true;
        return false;
    }
    private UUID operation(Chest chest) {
        return UUID.nameUUIDFromBytes((payment+":"+(refund ? "REFUND" : "DEBIT")+":"+chest.identity+":"+chest.position.asLong())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private CompoundTag binding(Chest chest) {
        var tag=new CompoundTag(); tag.putInt("format",1); tag.putUUID("payment",payment); tag.put("intent",intent.copy());
        tag.putString("role",refund ? "REFUND" : "DEBIT"); tag.putUUID("container",chest.identity); tag.putLong("position",chest.position.asLong());
        tag.putLong("delta",RoyalCoinPlan.spendableValue(chest.after)-RoyalCoinPlan.spendableValue(chest.before)); return tag;
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

    void requireAfter(ServerLevel level,RoyalTreasurySnapshot quote,List<ItemStack> expected) {
        quote.requireUnchanged(); requireStorage(level,quote); int offset=0;
        for(Chest chest:chests) {
            if(!matchesApplied(chest,(ChestBlockEntity)level.getBlockEntity(chest.position),expected.subList(offset,offset+27))) {
                throw new IllegalStateException("Live treasury contents or operation proof changed during persistence at "+chest.position);
            }
            offset+=27;
        }
    }

    static UUID identity(ChestBlockEntity chest,boolean create) {
        CompoundTag data=chest.getPersistentData();
        if(!data.contains(IDENTITY)) {
            if(!create) return null;
            var tag=new CompoundTag(); tag.putInt("format",1); tag.putUUID("id",UUID.randomUUID());
            data.put(IDENTITY,tag); chest.setChanged();
        }
        var tag=data.getCompound(IDENTITY);
        if(!tag.getAllKeys().equals(Set.of("format","id")) || !tag.contains("format",Tag.TAG_INT) || tag.getInt("format")!=1 || !tag.hasUUID("id")) throw new IllegalStateException("Unsupported/corrupt treasury identity; preserve chest data");
        return tag.getUUID("id");
    }

    CompoundTag save(ServerLevel level) {
        var tag=new CompoundTag(); tag.putInt("format",payment==null ? 1 : FORMAT); var list=new ListTag();
        if(payment!=null) { tag.putUUID("payment",payment); tag.put("intent",intent.copy()); tag.putString("role",refund ? "REFUND" : "DEBIT"); }
        for(Chest chest:chests) {
            var item=new CompoundTag(); item.putLong("position",chest.position.asLong()); item.putUUID("identity",chest.identity);
            item.put("state",chest.state.copy()); item.put("before",saveSlots(level,chest.before)); item.put("after",saveSlots(level,chest.after)); list.add(item);
        }
        tag.put("chests",list); return tag;
    }

    static RoyalTreasuryImage read(ServerLevel level,CompoundTag tag) {
        if(!tag.contains("format",Tag.TAG_INT) || (tag.getInt("format")!=1 && tag.getInt("format")!=FORMAT) || !tag.contains("chests",Tag.TAG_LIST)) throw new IllegalStateException("Unsupported treasury image; preserve payment journal");
        boolean legacy=tag.getInt("format")==1;
        if(!tag.getAllKeys().equals(legacy ? Set.of("format","chests") : Set.of("format","payment","intent","role","chests"))
                || (!legacy && (!tag.hasUUID("payment") || !tag.contains("intent",Tag.TAG_COMPOUND)
                || !(tag.getString("role").equals("DEBIT") || tag.getString("role").equals("REFUND"))))) {
            throw new IllegalStateException("Unsupported/corrupt treasury operation binding; preserve journal");
        }
        var list=tag.getList("chests",Tag.TAG_COMPOUND);
        if(list.isEmpty() || list.size()>MAX_CHESTS) throw new IllegalStateException("Invalid treasury image size");
        var result=new ArrayList<Chest>(); var positions=new HashSet<BlockPos>(); var ids=new HashSet<UUID>();
        for(Tag raw:list) {
            var item=(CompoundTag)raw;
            if(!item.getAllKeys().equals(Set.of("position","identity","state","before","after")) || !item.contains("position",Tag.TAG_LONG) || !item.hasUUID("identity") || !item.contains("state",Tag.TAG_COMPOUND)) {
                throw new IllegalStateException("Incomplete treasury chest image");
            }
            var pos=BlockPos.of(item.getLong("position")); var id=item.getUUID("identity");
            if(!positions.add(pos) || !ids.add(id)) throw new IllegalStateException("Duplicate treasury image position or identity");
            result.add(new Chest(pos,id,item.getCompound("state").copy(),readSlots(level,item,"before"),readSlots(level,item,"after")));
        }
        return new RoyalTreasuryImage(result,legacy ? null : tag.getUUID("payment"),legacy ? new CompoundTag() : tag.getCompound("intent"),!legacy && tag.getString("role").equals("REFUND"));
    }

    long value(boolean after) { return RoyalCoinPlan.spendableValue(chests.stream().flatMap(c->(after ? c.after : c.before).stream()).toList()); }

    void requireDirection(boolean refund) {
        for(Chest chest:chests) {
            long delta=RoyalCoinPlan.spendableValue(chest.after)-RoyalCoinPlan.spendableValue(chest.before);
            if(delta==0 && changed(chest)) throw new IllegalStateException("Payment mutates slots without changing coin value; preserve journal");
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
