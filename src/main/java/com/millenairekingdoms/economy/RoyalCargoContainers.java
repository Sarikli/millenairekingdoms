package com.millenairekingdoms.economy;

import com.millenairekingdoms.shipping.RoyalGoodsPlan;
import com.millenairekingdoms.shipping.RoyalShipmentStore;
import com.millenairekingdoms.shipping.RoyalShipmentStore.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import org.millenaire.item.ModItems;

/** Internal physical cargo coordinator. The domain MUST validate agency/member/stock/capacity before planning,
 * and supply current mandate/storage revalidation before a new mutation. No network or scheduled consumer yet.
 * All acknowledgements load the durable operation rather than trusting a cached/stale pending DTO. */
@org.jetbrains.annotations.ApiStatus.Internal
public final class RoyalCargoContainers {
    private RoyalCargoContainers() {}
    private enum Evidence { BEFORE, APPLIED }
    private record Slots(List<ItemStack> before,List<ItemStack> after) {}

    /** Creates identities and acknowledges the untouched native BEFORE image. No goods are moved. */
    public static Operation plan(ServerLevel level,Intent intent,Role role,BlockPos position,int quantity,RoyalTreasuryPersistence persistence) {
        thread(level);RoyalEconomicReservations.requireCargoAvailable(level,position);var chest=live(level,position);var product=product(intent);var before=slots(chest);RoyalGoodsPlan.Plan plan;
        if(role==Role.COLLECT)plan=RoyalGoodsPlan.remove(before,product,quantity);
        else {
            if(quantity<1 || quantity>intent.quantity())throw new IllegalArgumentException("Invalid per-container cargo allocation");
            var cargo=new ArrayList<ItemStack>();int remaining=quantity;
            while(remaining>0){int count=Math.min(remaining,Math.min(64,product.getMaxStackSize()));cargo.add(product.copyWithCount(count));remaining-=count;}
            plan=RoyalGoodsPlan.insert(before,cargo);
        }
        var image=new CompoundTag();image.putInt("format",1);image.put("state",NbtUtils.writeBlockState(chest.getBlockState()));image.put("before",save(level,plan.before()));image.put("after",save(level,plan.after()));
        var operation=new Operation(UUID.randomUUID(),role,RoyalTreasuryImage.identity(chest,true),position,quantity,image,Outcome.PREPARED);
        validate(level,intent,operation);RoyalContainerReceipts.requireRoom(chest,operation.id(),binding(intent,operation));
        persistence.flush(level,List.of(position));requireBefore(level,intent,operation);verify(level,intent,operation,plan.before(),false);return operation;
    }

    /** Native proof and Items are acknowledged before adding/removing any spendable escrow in the shipment journal.
     * Cancellation of an unapplied collect/delivery only resolves its allocation, never takes or fabricates cargo.
     * revalidate is domain authority/stock/storage validation, run only for a NEW physical delta. */
    public static Entry settle(ServerLevel level,RoyalShipmentStore store,UUID shipment,UUID operationId,RoyalTreasuryPersistence persistence,Runnable revalidate) {
        thread(level);var entry=store.load(shipment);if(entry==null)throw new IllegalArgumentException("Unknown physical shipment");
        var operation=find(entry,operationId);if(!operation.pending())return entry;
        if(entry.stage()==Stage.RECOVERY_REQUIRED) return entry;
        if(!level.isLoaded(operation.position()))return store.block(shipment,Blocked.UNLOADED,"Original cargo chunk is unavailable; no forced loading");
        Evidence evidence;
        try{evidence=inspect(level,entry.intent(),operation);}catch(IllegalStateException failure){return store.quarantine(shipment,diagnosis(failure));}
        var intent=entry.intent();
        if(entry.cancellationRequested() && operation.role()!=Role.RETURN && evidence==Evidence.BEFORE){
            persistence.flush(level,List.of(operation.position()));requireBefore(level,intent,operation);verify(level,intent,operation,validate(level,intent,operation).before,false);
            return store.cancelOperation(shipment,operation.id());
        }
        if(evidence==Evidence.BEFORE){
            RoyalEconomicReservations.requireCargoAvailable(level,operation.position());
            revalidate.run();
            // Native save events/revalidation may retire authority. Never publish a new delta from a stale mandate.
            var current=store.load(shipment);if(current.stage()==Stage.RECOVERY_REQUIRED)return current;
            if(current.cancellationRequested() && operation.role()!=Role.RETURN)return settle(level,store,shipment,operationId,persistence,revalidate);
            if(!find(current,operationId).equals(operation))throw new IllegalStateException("Cargo operation changed before native mutation");
            RoyalEconomicReservations.requireCargoAvailable(level,operation.position());
            requireBefore(level,intent,operation);
        }
        var chest=live(level,operation.position());var expected=evidence==Evidence.APPLIED ? slots(chest) : validate(level,intent,operation).after;
        RoyalContainerReceipts.requireRoom(chest,operation.id(),binding(intent,operation));
        if(evidence==Evidence.BEFORE){
            for(int slot=0;slot<27;slot++)chest.setItem(slot,expected.get(slot).copy());
            RoyalContainerReceipts.record(chest,operation.id(),binding(intent,operation));chest.setChanged();
        }
        persistence.flush(level,List.of(operation.position()));requireContents(level,intent,operation,expected,true);verify(level,intent,operation,expected,true);
        var acknowledged=store.acknowledge(shipment,operation.id());
        try{RoyalContainerReceipts.remove(live(level,operation.position()),operation.id(),binding(intent,operation));persistence.flush(level,List.of(operation.position()));}
        catch(RuntimeException failure){com.mojang.logging.LogUtils.getLogger().warn("Acknowledged cargo operation {} retained native receipt cleanup: {}",operation.id(),failure.toString());}
        return acknowledged;
    }

    private static Operation find(Entry entry,UUID id){return entry.operations().stream().filter(o->o.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown physical cargo operation"));}
    private static Evidence inspect(ServerLevel level,Intent intent,Operation operation){
        validate(level,intent,operation);var chest=requireContainer(level,operation);
        if(RoyalContainerReceipts.contains(chest,operation.id(),binding(intent,operation)))return Evidence.APPLIED;
        requireBefore(level,intent,operation);return Evidence.BEFORE;
    }
    private static ChestBlockEntity requireContainer(ServerLevel level,Operation operation){
        var chest=live(level,operation.position());
        if(!operation.container().equals(RoyalTreasuryImage.identity(chest,false)) || !operation.image().getCompound("state").equals(NbtUtils.writeBlockState(chest.getBlockState())))
            throw new IllegalStateException("Cargo container identity/connection changed at "+operation.position());return chest;
    }
    private static void requireBefore(ServerLevel level,Intent intent,Operation operation){requireContents(level,intent,operation,validate(level,intent,operation).before,false);}
    private static void requireContents(ServerLevel level,Intent intent,Operation operation,List<ItemStack> expected,boolean applied){
        var chest=requireContainer(level,operation);
        if(!matches(chest,expected) || RoyalContainerReceipts.contains(chest,operation.id(),binding(intent,operation))!=applied)
            throw new IllegalStateException("Cargo contents/proof differ from intended "+(applied ? "applied" : "unapplied before")+" image at "+operation.position());
    }
    private static Slots validate(ServerLevel level,Intent intent,Operation operation){
        if(!intent.dimension().equals(level.dimension().location().toString()) || operation.quantity()>intent.quantity())throw new IllegalStateException("Cargo allocation dimension/quantity differs from shipment");
        var product=product(intent);var image=operation.image();var before=read(level,image,"before");var after=read(level,image,"after");int delta=0;
        for(int slot=0;slot<27;slot++){
            var a=before.get(slot);var b=after.get(slot);if(ItemStack.matches(a,b))continue;
            if(!a.isEmpty() && !ItemStack.isSameItemSameComponents(a,product) || !b.isEmpty() && !ItemStack.isSameItemSameComponents(b,product))throw new IllegalStateException("Cargo image changes unrelated goods/components");
            int change=b.getCount()-a.getCount();if(operation.role()==Role.COLLECT ? change>0 : change<0)throw new IllegalStateException("Cargo image shifts items between independent slots/containers");delta=Math.addExact(delta,change);
        }
        if(delta!=(operation.role()==Role.COLLECT ? -operation.quantity() : operation.quantity()))throw new IllegalStateException("Native cargo image quantity differs from immutable allocation");return new Slots(before,after);
    }
    private static ItemStack product(Intent intent){
        var item=BuiltInRegistries.ITEM.getOptional(intent.product()).orElseThrow(()->new IllegalStateException("Unknown native cargo product"));
        if(item==Items.AIR || item==ModItems.DENIER.get() || item==ModItems.DENIER_ARGENT.get() || item==ModItems.DENIER_OR.get())throw new IllegalStateException("Royal cargo must be ordinary noncurrency goods");return new ItemStack(item);
    }
    private static CompoundTag binding(Intent intent,Operation operation){
        var tag=new CompoundTag();tag.putInt("format",1);tag.putString("kind","SHIPMENT");tag.put("intent",RoyalShipmentStore.saveIntent(intent));tag.putUUID("operation",operation.id());tag.putString("role",operation.role().name());
        tag.putUUID("container",operation.container());tag.putLong("position",operation.position().asLong());tag.putInt("quantity",operation.quantity());tag.put("image",operation.image());return tag;
    }
    private static ChestBlockEntity live(ServerLevel level,BlockPos position){
        if(!level.isLoaded(position))throw new IllegalStateException("Cargo chunk is unloaded; postpone without forcing it: "+position);
        if(!(level.getBlockEntity(position) instanceof ChestBlockEntity chest) || chest.getContainerSize()!=27)throw new IllegalStateException("Cargo chest is missing/unsupported: "+position);return chest;
    }
    private static List<ItemStack> slots(ChestBlockEntity chest){var slots=new ArrayList<ItemStack>();for(int i=0;i<27;i++)slots.add(chest.getItem(i).copy());return List.copyOf(slots);}
    private static boolean matches(ChestBlockEntity chest,List<ItemStack> expected){for(int i=0;i<27;i++)if(!ItemStack.matches(chest.getItem(i),expected.get(i)))return false;return true;}
    private static ListTag save(ServerLevel level,List<ItemStack> slots){var list=new ListTag();for(var stack:slots)list.add(stack.saveOptional(level.registryAccess()));return list;}
    private static List<ItemStack> read(ServerLevel level,CompoundTag image,String key){
        var list=image.getList(key,Tag.TAG_COMPOUND);if(list.size()!=27)throw new IllegalStateException("Incomplete native cargo slot image");var result=new ArrayList<ItemStack>();
        for(Tag raw:list){var tag=(CompoundTag)raw;var stack=tag.isEmpty() ? ItemStack.EMPTY : ItemStack.parse(level.registryAccess(),tag).orElseThrow(()->new IllegalStateException("Unknown native cargo stack"));
            if(!stack.isEmpty() && (stack.getCount()<1 || stack.getCount()>Math.min(64,stack.getMaxStackSize())))throw new IllegalStateException("Invalid native cargo stack extent");result.add(stack);}
        return List.copyOf(result);
    }
    private static void verify(ServerLevel level,Intent intent,Operation operation,List<ItemStack> expected,boolean applied){
        var position=operation.position();var chunk=level.getChunkSource().chunkMap.read(new ChunkPos(position)).join().orElseThrow(()->new IllegalStateException("Cargo chunk not written"));
        var state=RoyalTreasuryPersistence.savedState(chunk,position);CompoundTag found=null;
        for(Tag raw:chunk.getList("block_entities",Tag.TAG_COMPOUND)){var tag=(CompoundTag)raw;if(BlockEntity.getPosFromTag(tag).equals(position)){
            if(found!=null)throw new IllegalStateException("Duplicate saved cargo chest");found=tag;}}
        if(found==null || !operation.image().getCompound("state").equals(NbtUtils.writeBlockState(state))
                || !(BlockEntity.loadStatic(position,state,found,level.registryAccess()) instanceof ChestBlockEntity saved)
                || !operation.container().equals(RoyalTreasuryImage.identity(saved,false)) || !matches(saved,expected)
                || RoyalContainerReceipts.contains(saved,operation.id(),binding(intent,operation))!=applied)throw new IllegalStateException("Native cargo disk contents/proof read-back differs at "+position);
    }
    private static String diagnosis(Exception failure){var message=failure.getMessage();return message==null ? failure.getClass().getSimpleName() : message.substring(0,Math.min(512,message.length()));}
    private static void thread(ServerLevel level){if(!level.getServer().isSameThread())throw new IllegalStateException("Cargo operations require the server thread");}
}
