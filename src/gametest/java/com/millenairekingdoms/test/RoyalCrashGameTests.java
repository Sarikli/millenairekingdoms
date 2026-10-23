package com.millenairekingdoms.test;

import com.millenairekingdoms.economy.RoyalPaymentJournal;
import com.millenairekingdoms.economy.RoyalPaymentJournal.State;
import com.millenairekingdoms.economy.RoyalPaymentService;
import com.millenairekingdoms.economy.RoyalPaymentService.Purpose;
import com.millenairekingdoms.economy.RoyalTreasuryPersistence;
import com.millenairekingdoms.economy.RoyalTreasurySnapshot;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.item.ModItems;
import org.millenaire.village.VillageId;
import org.millenaire.village.VillageSavedData;

/** Explicit opt-in, isolated fault-probe namespaces. The interrupt stage intentionally never passes a GameTest. */
@PrefixGameTestTemplate(false)
public final class RoyalCrashGameTests {
    private static final UUID REFERENCE=new UUID(91,1);

    @GameTest(templateNamespace="millenairekingdoms_payment_crash_prepare",template="empty",timeoutTicks=1200)
    public static void prepareRealPaymentCrashFixture(GameTestHelper h) throws Exception {
        var boundary=boundary(); var f=RoyalPaymentGameTests.persistentFixture(h,"crash-"+boundary);
        var first=f.position(); var second=f.village().getTownhall().getInventory().getChestPositions().stream()
                .filter(p->!new net.minecraft.world.level.ChunkPos(p).equals(new net.minecraft.world.level.ChunkPos(first))).findFirst().orElseThrow();
        f.chest().setItem(0,new ItemStack(ModItems.DENIER.get(),32));
        var other=(ChestBlockEntity)f.level().getBlockEntity(second);
        other.setItem(0,new ItemStack(ModItems.DENIER_ARGENT.get())); other.setItem(1,new ItemStack(ModItems.DENIER.get(),32)); other.setChanged();
        var tag=new CompoundTag(); tag.putString("boundary",boundary); tag.putUUID("id",UUID.randomUUID()); tag.putUUID("kingdom",f.kingdom());
        tag.putLong("first",first.asLong()); tag.putString("directory",f.directory().toString());
        VillageSavedData.get(f.level()).setDirty(); KingdomSavedData.get(f.level()).setDirty();
        f.level().getServer().saveEverything(true,true,true);
        NbtIo.writeCompressed(tag,marker(f.level()));
        h.assertTrue(RoyalTreasurySnapshot.forNpc(f.level(),f.kingdom()).spendableValue()==128 && f.journal().load(tag.getUUID("id"))==null,
                "Fresh original-plan fixture has 128 real coins, persisted political/village data and no payment yet");
        System.out.println("ROYAL_CRASH_PREPARED "+boundary); h.succeed();
    }

    @GameTest(templateNamespace="millenairekingdoms_payment_crash_interrupt",template="empty",timeoutTicks=1200)
    public static void interruptRealPaymentProcess(GameTestHelper h) throws Exception {
        var level=h.getLevel(); var tag=read(level); var boundary=boundary();
        h.assertTrue(boundary.equals(tag.getString("boundary")),"Crash mode must match the prepared world");
        var kingdom=tag.getUUID("kingdom"); var id=tag.getUUID("id"); var path=Path.of(tag.getString("directory")); loadFixture(level,kingdom);
        var persistence=new RoyalTreasuryPersistence() {
            int count;
            @Override public void flush(ServerLevel l,List<BlockPos> positions) {
                if(++count!=2) { super.flush(l,positions); return; }
                if(boundary.equals("partial")) super.flush(l,List.of(BlockPos.of(tag.getLong("first"))));
                else if(!boundary.equals("before")) super.flush(l,positions);
                var state=new RoyalPaymentJournal(path).load(id).state();
                if(state!=(boundary.equals("refund") ? State.REFUND_PREPARED : State.PREPARED)) throw new IllegalStateException("Unexpected pre-crash state: "+state);
                System.out.println("ROYAL_CRASH_BOUNDARY_REACHED "+boundary+" state="+state+" halt=70"); System.out.flush();
                // No shutdown hooks, Minecraft stop, save-all, finally blocks or Gradle success are executed.
                Runtime.getRuntime().halt(70);
            }
        };
        if(boundary.equals("refund")) {
            RoyalPaymentGameTests.service(level,path,new RoyalTreasuryPersistence()).payNpc(kingdom,id,Purpose.CONSTRUCTION,REFERENCE,64);
            RoyalPaymentGameTests.service(level,path,persistence).refundNpc(kingdom,id);
        } else {
            RoyalPaymentGameTests.service(level,path,persistence).payNpc(kingdom,id,Purpose.CONSTRUCTION,REFERENCE,64);
        }
        h.fail("Crash boundary was not reached; never accept a graceful run as an interruption probe");
    }

    @GameTest(templateNamespace="millenairekingdoms_payment_crash_recover",template="empty",timeoutTicks=1200)
    public static void recoverRealPaymentAfterAbruptTermination(GameTestHelper h) throws Exception {
        var level=h.getLevel(); var tag=read(level); var mode=boundary();
        h.assertTrue(mode.equals(tag.getString("boundary")),"Recovery mode must match its isolated world");
        var kingdom=tag.getUUID("kingdom"); var id=tag.getUUID("id"); var path=Path.of(tag.getString("directory")); loadFixture(level,kingdom);
        var state=new RoyalPaymentJournal(path).load(id).state();
        h.assertTrue(state==(mode.equals("refund") ? State.REFUND_PREPARED : State.PREPARED),"Abruptly terminated JVM did not publish a terminal payment flag");
        long actual=RoyalTreasurySnapshot.forNpc(level,kingdom).spendableValue();
        long expected=switch(mode) { case "before","refund" -> 128; case "partial" -> 96; default -> 64; };
        h.assertTrue(actual==expected,"Disk inventory before recovery must expose the exact interruption boundary: "+actual+" expected "+expected);
        var service=RoyalPaymentGameTests.service(level,path,new RoyalTreasuryPersistence()); boolean refund=mode.equals("refund");
        var result=refund ? service.refundNpc(kingdom,id) : service.payNpc(kingdom,id,Purpose.CONSTRUCTION,REFERENCE,64);
        h.assertTrue(result.state()==(refund ? State.REFUNDED : State.PAID),"Recovery issues a terminal receipt only after its real persistence barrier");
        long finalValue=refund ? 128 : 64;
        h.assertTrue(RoyalTreasurySnapshot.forNpc(level,kingdom).spendableValue()==finalValue,"Post-crash recovery conserves the original expense/refund");
        if(refund) service.refundNpc(kingdom,id); else service.payNpc(kingdom,id,Purpose.CONSTRUCTION,REFERENCE,64);
        h.assertTrue(RoyalTreasurySnapshot.forNpc(level,kingdom).spendableValue()==finalValue,"Post-crash request replay cannot charge or refund again");
        System.out.println("ROYAL_CRASH_RECOVERED "+mode+" diskBefore="+actual+" final="+finalValue); h.succeed();
    }

    private static void loadFixture(ServerLevel level,UUID kingdom) {
        var k=KingdomSavedData.get(level).kingdom(kingdom);
        if(k==null) throw new IllegalStateException("Original political data missing after process replacement");
        var village=VillageSavedData.get(level).getVillageManager().getVillage(new VillageId(k.capital()));
        if(village==null || village.getTownhall()==null || village.getTownhall().getInventory()==null) {
            throw new IllegalStateException("Original plan did not rehydrate saved storage; do not repair the fixture after the crash");
        }
        for(var pos:village.getTownhall().getInventory().getChestPositions()) level.getChunkAt(pos);
    }
    private static String boundary() {
        String value=System.getProperty("kingdoms.tests.crashBoundary","");
        if(!List.of("before","partial","after","refund").contains(value)) throw new IllegalStateException("Set an explicit isolated payment crash boundary");
        return value;
    }
    private static Path marker(ServerLevel level) { return level.getServer().getWorldPath(LevelResource.ROOT).resolve("royal-crash-fixture.dat"); }
    private static CompoundTag read(ServerLevel level) throws Exception { return NbtIo.readCompressed(marker(level),NbtAccounter.create(1024*1024)); }
}
