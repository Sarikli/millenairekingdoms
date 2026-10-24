package com.millenairekingdoms.test;

import com.millenairekingdoms.economy.RoyalPaymentJournal.State;
import com.millenairekingdoms.economy.RoyalPaymentService;
import com.millenairekingdoms.economy.RoyalTreasuryPersistence;
import com.millenairekingdoms.shipping.RoyalShipmentService;
import com.millenairekingdoms.shipping.RoyalShipmentStore.Stage;
import com.millenairekingdoms.test.RoyalShipmentSupport.Realm;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Joint actual fee/domain boundaries. Every interrupt run deliberately fails with halt70, never a successful test. */
@PrefixGameTestTemplate(false)
public final class RoyalShipmentCrashGameTests {
    private static final List<String> BOUNDARIES=List.of("paid-before-domain","dispatch-before-commit","refund-before-terminal");
    @GameTest(templateNamespace="millenairekingdoms_shipment_crash_prepare",template="empty",timeoutTicks=1200)
    public static void preparePaidShipmentCrashWorld(GameTestHelper h)throws Exception{
        var r=RoyalShipmentSupport.realm(h,"Joint shipment "+boundary());var tag=r.marker();tag.putString("boundary",boundary());tag.putUUID("id",UUID.randomUUID());r.save();NbtIo.writeCompressed(tag,marker(r.level()));
        h.assertTrue(r.coins()==128 && r.goods(r.source())==96 && r.goods(r.target())==4,"Fresh saved native members have real funds and100 goods before any fee/order");System.out.println("ROYAL_SHIPMENT_CRASH_PREPARED "+boundary());h.succeed();
    }
    @GameTest(templateNamespace="millenairekingdoms_shipment_crash_interrupt",template="empty",timeoutTicks=1200)
    public static void interruptActualFeeAndDomainAcknowledgement(GameTestHelper h)throws Exception{
        var tag=read(h);var r=RoyalShipmentSupport.restore(h,tag,true);var id=tag.getUUID("id");var boundary=boundary();
        if(boundary.equals("dispatch-before-commit")){
            RoyalShipmentSupport.fastRequest(r,null,id);r.service().observe(id);var dispatched=r.service().observe(id);
            h.assertTrue(dispatched.stage()==Stage.IN_TRANSIT && r.payments().load(id).state()==State.PAID && r.coins()==119 && r.goods(r.source())==56,"Durable accepted dispatch holds40 before its existing PAID fee commit");halt(boundary,r,id);return;
        }
        final FileChannel[] held=new FileChannel[1];var persistence=new RoyalTreasuryPersistence(){int count;@Override public void flush(ServerLevel level,List<BlockPos> positions){
            super.flush(level,positions);if(++count==(boundary.equals("paid-before-domain") ? 3 : 6))try{
                held[0]=FileChannel.open(RoyalShipmentService.directory(level).resolve(".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);held[0].lock();
            }catch(java.io.IOException failure){throw new IllegalStateException(failure);}
        }};
        var money=RoyalPaymentGameTests.service(r.level(),r.level().getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms/royal-payments"),persistence);
        var constructor=RoyalShipmentService.class.getDeclaredConstructor(ServerLevel.class,RoyalTreasuryPersistence.class,RoyalPaymentService.class);constructor.setAccessible(true);var service=constructor.newInstance(r.level(),new RoyalTreasuryPersistence(),money);boolean rejected=false;
        try{
            if(boundary.equals("paid-before-domain"))RoyalShipmentSupport.fastRequest(r,null,id,service);
            else{RoyalShipmentSupport.fastRequest(r,null,id,service);service.cancelNpc(r.kingdom(),id);}
        }catch(IllegalStateException expected){rejected=true;}finally{if(held[0]!=null)held[0].close();}
        h.assertTrue(rejected && held[0]!=null,"Actual contending index file lock must interrupt acknowledgement after the financial journal moved");
        var row=r.store().load(id);h.assertTrue(row.stage()==(boundary.equals("paid-before-domain") ? Stage.PREPARING : Stage.RETURNING)
                && r.payments().load(id).state()==(boundary.equals("paid-before-domain") ? State.PAID : State.REFUNDED)
                && r.coins()==(boundary.equals("paid-before-domain") ? 119 : 128) && r.goods(r.source())==96,"Actual independently saved native/payment/domain files expose exact interrupted boundary");halt(boundary,r,id);
    }
    @GameTest(templateNamespace="millenairekingdoms_shipment_crash_recover",template="empty",timeoutTicks=1200)
    public static void recoverActualPaidTimedShipmentAcrossJvm(GameTestHelper h)throws Exception{
        var tag=read(h);var r=RoyalShipmentSupport.restore(h,tag,true);var id=tag.getUUID("id");var boundary=boundary();var row=r.store().load(id);
        h.assertTrue(row.stage()==(boundary.equals("paid-before-domain") ? Stage.PREPARING : boundary.equals("dispatch-before-commit") ? Stage.IN_TRANSIT : Stage.RETURNING)
                && r.coins()==(boundary.equals("refund-before-terminal") ? 128 : 119),"Replacement JVM reads original interrupted financial/domain state without repairs");recover(h,r,id,boundary,0);
    }
    private static void recover(GameTestHelper h,Realm r,UUID id,String boundary,int attempts){
        if(attempts>100){r.close();h.fail("Joint shipment recovery timed out: "+r.store().load(id));return;}
        h.runAfterDelay(1,()->{
            r.activate(true);var row=r.service().observe(id);
            if(row.terminal()){
                boolean refund=boundary.equals("refund-before-terminal");h.assertTrue(row.stage()==(refund ? Stage.CANCELLED : Stage.DELIVERED) && r.coins()==(refund ? 128 : 119)
                        && r.goods(r.source())==(refund ? 96 : 56) && r.goods(r.target())==(refund ? 4 : 44) && row.escrow()==0
                        && r.payments().load(id).state()==(refund ? State.REFUNDED : State.COMMITTED),"Actual recovery closes original fee/goods/timer lifecycle exactly once");
                r.service().observe(id);h.assertTrue(r.coins()==(refund ? 128 : 119) && r.goods(r.target())==(refund ? 4 : 44),"Post-crash terminal replay never charges/refunds/credits again");
                System.out.println("ROYAL_SHIPMENT_CRASH_RECOVERED "+boundary+" capital="+r.coins()+" source="+r.goods(r.source())+" target="+r.goods(r.target())+" state="+row.stage());r.close();h.succeed();
            }else{h.assertTrue(row.stage()!=Stage.RECOVERY_REQUIRED,"Expected joint-domain boundary must not need speculative operator repair: "+row.diagnosis());recover(h,r,id,boundary,attempts+1);}
        });
    }
    private static void halt(String boundary,Realm r,UUID id){System.out.println("ROYAL_SHIPMENT_CRASH_BOUNDARY_REACHED "+boundary+" stage="+r.store().load(id).stage()+" money="+r.payments().load(id).state()+" halt=70");System.out.flush();Runtime.getRuntime().halt(70);}
    private static String boundary(){var boundary=System.getProperty("kingdoms.tests.shipmentCrashBoundary","");if(!BOUNDARIES.contains(boundary))throw new IllegalStateException("Set explicit isolated paid-shipment crash boundary");return boundary;}
    private static Path marker(ServerLevel level){return level.getServer().getWorldPath(LevelResource.ROOT).resolve("joint-shipment-crash.dat");}
    private static net.minecraft.nbt.CompoundTag read(GameTestHelper h)throws Exception{var tag=NbtIo.readCompressed(marker(h.getLevel()),NbtAccounter.create(1024*1024));if(!boundary().equals(tag.getString("boundary")))throw new IllegalStateException("Joint shipment crash world mismatch");return tag;}
}
