package com.millenairekingdoms.test;

import com.millenairekingdoms.economy.RoyalCargoContainers;
import com.millenairekingdoms.economy.RoyalTreasuryPersistence;
import com.millenairekingdoms.shipping.RoyalShipmentStore;
import com.millenairekingdoms.shipping.RoyalShipmentStore.*;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.building.BuildingInventory;
import org.millenaire.village.VillageSavedData;
import org.millenaire.village.VillageId;

/** Explicit opt-in native physical cargo cuts, synthetic route/fee inputs. Interrupt JVMs intentionally never pass. */
@PrefixGameTestTemplate(false)
public final class RoyalCargoCrashGameTests {
    private static final List<String> BOUNDARIES=List.of("before","collect","partial","deliver","return","deliver-activity","slots-before-proof","collect-ack","deliver-ack","collect-activity");
    @GameTest(templateNamespace="millenairekingdoms_cargo_crash_prepare",template="empty",timeoutTicks=1200)
    public static void prepareNativeCargoCrashWorld(GameTestHelper h)throws Exception{
        var boundary=boundary();var f=RoyalCargoGameTests.fixture(h,"cargo-crash-"+boundary);var ids=new ListTag();
        if(boundary.startsWith("deliver") || boundary.equals("return")){
            var collected=RoyalCargoGameTests.plan(f,Role.COLLECT,f.from(),40);f.store().prepare(f.id(),collected);RoyalCargoGameTests.settle(f,collected,new RoyalTreasuryPersistence());
            if(boundary.startsWith("deliver")){f.store().dispatch(f.id(),200);f.store().awaitDelivery(f.id(),1200);}else f.store().returning(f.id());
            var op=RoyalCargoGameTests.plan(f,boundary.startsWith("deliver") ? Role.DELIVER : Role.RETURN,boundary.startsWith("deliver") ? f.to() : f.from(),40);f.store().prepare(f.id(),op);var tag=new CompoundTag();tag.putUUID("id",op.id());ids.add(tag);
        }else{
            var first=RoyalCargoGameTests.plan(f,Role.COLLECT,f.from(),boundary.equals("partial") ? 36 : 40);f.store().prepare(f.id(),first);var tag=new CompoundTag();tag.putUUID("id",first.id());ids.add(tag);
            if(boundary.equals("partial")){var second=RoyalCargoGameTests.plan(f,Role.COLLECT,f.to(),4);f.store().prepare(f.id(),second);var raw=new CompoundTag();raw.putUUID("id",second.id());ids.add(raw);}
        }
        var tag=new CompoundTag();tag.putString("boundary",boundary);tag.putUUID("id",f.id());tag.putString("directory",f.directory().toString());tag.putLong("from",f.from().asLong());tag.putLong("to",f.to().asLong());tag.put("operations",ids);
        VillageSavedData.get(f.level()).setDirty();KingdomSavedData.get(f.level()).setDirty();f.level().getServer().saveEverything(true,true,true);NbtIo.writeCompressed(tag,marker(f.level()));
        h.assertTrue(total(f.level(),tag,false)+f.entry().escrow()==100,"Native saved fixture accounts for100 physical plus escrow goods before the actual interruption");System.out.println("ROYAL_CARGO_CRASH_PREPARED "+boundary);h.succeed();
    }
    @GameTest(templateNamespace="millenairekingdoms_cargo_crash_interrupt",template="empty",timeoutTicks=1200)
    public static void interruptNativeCargoProcess(GameTestHelper h)throws Exception{
        var level=h.getLevel();var tag=read(level);var boundary=boundary();var store=new RoyalShipmentStore(Path.of(tag.getString("directory")));var id=tag.getUUID("id");load(level,store.load(id));var op=tag.getList("operations",10).getCompound(0).getUUID("id");
        if(boundary.equals("slots-before-proof")){
            var pos=BlockPos.of(tag.getLong("from"));var original=(ChestBlockEntity)level.getBlockEntity(pos);var armed=new boolean[1];
            var probe=new ChestBlockEntity(pos,original.getBlockState()){@Override public void setItem(int slot,ItemStack stack){super.setItem(slot,stack);if(armed[0])halt(boundary,store.load(id));}};
            for(int slot=0;slot<27;slot++)probe.setItem(slot,original.getItem(slot).copy());probe.getPersistentData().merge(original.getPersistentData().copy());level.setBlockEntity(probe);
            RoyalCargoContainers.settle(level,store,id,op,new RoyalTreasuryPersistence(),()->armed[0]=true);
        }else if(boundary.endsWith("-ack")){
            RoyalCargoContainers.settle(level,store,id,op,new RoyalTreasuryPersistence(),()->{});halt(boundary,store.load(id));
        }else{
            var persistence=new RoyalTreasuryPersistence(){@Override public void flush(ServerLevel l,List<BlockPos> positions){
                if(!boundary.equals("before"))super.flush(l,positions);
                if(boundary.equals("collect-activity")){
                    var from=BlockPos.of(tag.getLong("from"));if(new BuildingInventory(List.of(from)).add(l,Items.SPRUCE_LOG,40)!=40)throw new IllegalStateException("Native known40 input deposit failed");super.flush(l,List.of(from));
                }
                if(boundary.equals("deliver-activity")){
                    var to=BlockPos.of(tag.getLong("to"));if(new BuildingInventory(List.of(to)).remove(l,Items.SPRUCE_LOG,40)!=40)throw new IllegalStateException("Actual native post-credit withdrawal failed");
                    var recipient=BlockPos.of(tag.getLong("from")).offset(0,0,64);l.getChunkAt(recipient);l.setBlock(recipient,Blocks.CHEST.defaultBlockState(),2);((ChestBlockEntity)l.getBlockEntity(recipient)).setItem(0,new ItemStack(Items.SPRUCE_LOG,40));super.flush(l,List.of(to,recipient));
                }halt(boundary,store.load(id));
            }};
            RoyalCargoContainers.settle(level,store,id,op,persistence,()->{});
        }h.fail("Expected native cargo halt boundary was not reached");
    }
    @GameTest(templateNamespace="millenairekingdoms_cargo_crash_recover",template="empty",timeoutTicks=1200)
    public static void recoverNativeCargoAfterActualAbruptTermination(GameTestHelper h)throws Exception{
        var level=h.getLevel();var tag=read(level);var boundary=boundary();var store=new RoyalShipmentStore(Path.of(tag.getString("directory")));var id=tag.getUUID("id");var entry=store.load(id);load(level,entry);
        if(boundary.equals("deliver-activity"))level.getChunkAt(BlockPos.of(tag.getLong("from")).offset(0,0,64));
        int physical=total(level,tag,boundary.equals("deliver-activity"));int expected=switch(boundary){case "collect","collect-ack"->60;case "partial"->64;default->100;};
        h.assertTrue(physical==expected,"Actual saved physical cargo exposes exact cut "+boundary+": "+physical+" expected="+expected);
        h.assertTrue(entry.escrow()==(boundary.equals("collect-ack") || boundary.startsWith("deliver") && !boundary.equals("deliver-ack") || boundary.equals("return") ? 40 : 0),"Actual root journal before recovery matches the interruption, no synthetic reset");
        for(var raw:tag.getList("operations",10))entry=RoyalCargoContainers.settle(level,store,id,((CompoundTag)raw).getUUID("id"),new RoyalTreasuryPersistence(),()->{});
        h.assertTrue(entry.stage()!=Stage.RECOVERY_REQUIRED && total(level,tag,boundary.equals("deliver-activity"))+entry.escrow()==(boundary.equals("collect-activity") ? 140 : 100),"After recovery physical plus escrow equals original100 plus any known40 external input");
        if(boundary.startsWith("deliver") || boundary.equals("return")){entry=store.finish(id);h.assertTrue(entry.escrow()==0 && (boundary.equals("return") ? entry.returned()==40 && entry.stage()==Stage.CANCELLED : entry.delivered()==40 && entry.stage()==Stage.DELIVERED),"Delivered/returned terminal history follows native proof acknowledgement");}
        else h.assertTrue(entry.collected()==40 && entry.escrow()==40,"All and only acknowledged source operations back held cargo");
        for(var raw:tag.getList("operations",10))RoyalCargoContainers.settle(level,store,id,((CompoundTag)raw).getUUID("id"),new RoyalTreasuryPersistence(),()->{throw new AssertionError("Terminal operation replay cannot execute a new delta");});
        h.assertTrue(total(level,tag,boundary.equals("deliver-activity"))+store.load(id).escrow()==(boundary.equals("collect-activity") ? 140 : 100),"Repeated UUID replay preserves post-crash accounting");System.out.println("ROYAL_CARGO_CRASH_RECOVERED "+boundary+" diskPhysicalBefore="+physical+" physicalAfter="+total(level,tag,boundary.equals("deliver-activity"))+" escrow="+entry.escrow());h.succeed();
    }
    private static int total(ServerLevel level,CompoundTag tag,boolean recipient){return count(level,BlockPos.of(tag.getLong("from")))+count(level,BlockPos.of(tag.getLong("to")))+(recipient ? count(level,BlockPos.of(tag.getLong("from")).offset(0,0,64)) : 0);}
    private static int count(ServerLevel level,BlockPos pos){var chest=(ChestBlockEntity)level.getBlockEntity(pos);int amount=0;for(int slot=0;slot<27;slot++)if(chest.getItem(slot).is(Items.SPRUCE_LOG))amount+=chest.getItem(slot).getCount();return amount;}
    private static void load(ServerLevel level,Entry entry){
        var village=VillageSavedData.get(level).getVillageManager().getVillage(new VillageId(entry.intent().source().village()));if(village==null || village.getTownhall().getInventory()==null)throw new IllegalStateException("Original native metadata did not rehydrate; never repair crash fixture");for(var pos:village.getTownhall().getInventory().getChestPositions())level.getChunkAt(pos);
    }
    private static void halt(String boundary,Entry entry){System.out.println("ROYAL_CARGO_CRASH_BOUNDARY_REACHED "+boundary+" stage="+entry.stage()+" escrow="+entry.escrow()+" halt=70");System.out.flush();Runtime.getRuntime().halt(70);}
    private static String boundary(){var value=System.getProperty("kingdoms.tests.cargoCrashBoundary","");if(!BOUNDARIES.contains(value))throw new IllegalStateException("Explicit isolated native cargo crash boundary is required");return value;}
    private static Path marker(ServerLevel level){return level.getServer().getWorldPath(LevelResource.ROOT).resolve("native-cargo-crash.dat");}
    private static CompoundTag read(ServerLevel level)throws Exception{var tag=NbtIo.readCompressed(marker(level),NbtAccounter.create(1024*1024));if(!tag.getString("boundary").equals(boundary()))throw new IllegalStateException("Native cargo fixture boundary mismatch");return tag;}
}
