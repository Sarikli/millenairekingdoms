package com.millenairekingdoms.test;

import com.millenairekingdoms.economy.RoyalPaymentJournal.State;
import com.millenairekingdoms.shipping.RoyalShipmentConfig;
import com.millenairekingdoms.shipping.RoyalShipmentStore.Stage;
import com.millenairekingdoms.test.RoyalShipmentSupport.Realm;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Only fixture availability is supplied at test event priority LOW. After the initial request the probe READS the
 * ledger, never calls observe/resume/step. Production LOWEST listener alone advances the real paid timed shipment. */
@PrefixGameTestTemplate(false)
public final class RoyalShipmentLiveGameTests {
    private static final Map<ServerLevel,List<Realm>> FIXTURES=new WeakHashMap<>();
    static void fixtureTick(ServerLevel level){for(var realm:FIXTURES.getOrDefault(level,List.of()))realm.activate(true);}
    @GameTest(templateNamespace="millenairekingdoms_royal_shipment_live_tests",template="empty",timeoutTicks=24000)
    public static void defaultPeriodicEventsCarryActualPaidShipmentAcrossJvm(GameTestHelper h)throws Exception{
        h.assertTrue(RoyalShipmentConfig.TRACKING.get() && RoyalShipmentConfig.INTERVAL.get()==100 && RoyalShipmentConfig.MAX_PER_STEP.get()==2,"Actual shipping event tracker uses unchanged production defaults100/2");
        var marker=h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("automatic-shipment.dat");Realm realm;UUID id;
        if(RoyalShipmentSupport.read()){
            var tag=NbtIo.readCompressed(marker,NbtAccounter.unlimitedHeap());realm=RoyalShipmentSupport.restore(h,tag,true);id=tag.getUUID("id");
            h.assertTrue(realm.store().load(id).stage()==Stage.IN_TRANSIT && realm.goods(realm.source())==56 && realm.goods(realm.target())==4 && realm.coins()==119,"Original paid automatic transit/chests reopen without repair");
        }else{
            realm=RoyalShipmentSupport.realm(h,"Actual automatic logistics");id=UUID.randomUUID();var row=realm.service().requestNpc(realm.kingdom(),realm.from(),realm.to(),ResourceLocation.parse("minecraft:cherry_log"),40,id);
            h.assertTrue(row.intent().travelTicks()==3600 && row.intent().fee()==9 && realm.goods(realm.source())==96,"Actual request freezes default3600-tick route/real9 fee, no test duration override");
        }
        FIXTURES.put(h.getLevel(),List.of(realm));readOnlyProbe(h,realm,id,marker,0);
    }
    private static void readOnlyProbe(GameTestHelper h,Realm realm,UUID id,java.nio.file.Path marker,int ticks){
        if(ticks>12000){FIXTURES.remove(h.getLevel());realm.close();h.fail("Automatic shipment tracker did not finish expected phase: "+realm.store().load(id));return;}
        h.runAfterDelay(1,()->{
            var row=realm.store().load(id);if(row.stage()==Stage.RECOVERY_REQUIRED){FIXTURES.remove(h.getLevel());realm.close();h.fail("Automatic paid shipment quarantined: "+row.diagnosis());return;}
            if(!RoyalShipmentSupport.read() && row.stage()==Stage.IN_TRANSIT){
                h.assertTrue(realm.goods(realm.source())==56 && realm.goods(realm.target())==4 && realm.coins()==119 && row.escrow()==40 && row.due()-realm.level().getGameTime()>3000,"Actual default event picked up40 and fixed a future deadline");
                var tag=realm.marker();tag.putUUID("id",id);realm.save();try{NbtIo.writeCompressed(tag,marker);}catch(java.io.IOException failure){throw new IllegalStateException(failure);}FIXTURES.remove(h.getLevel());System.out.println("ROYAL_SHIPMENT_AUTOMATIC_WRITE ticks="+ticks+" due="+row.due()+" source=56 target=4 escrow=40");h.succeed();return;
            }
            if(RoyalShipmentSupport.read() && row.terminal()){
                h.assertTrue(row.stage()==Stage.DELIVERED && realm.level().getGameTime()>=row.due() && realm.goods(realm.source())==56 && realm.goods(realm.target())==44 && realm.coins()==119 && realm.payments().load(id).state()==State.COMMITTED,"Actual event alone completes original default timed native delivery after reload with one9 fee");
                FIXTURES.remove(h.getLevel());System.out.println("ROYAL_SHIPMENT_AUTOMATIC_READ ticks="+ticks+" due="+row.due()+" now="+realm.level().getGameTime()+" source=56 target=44 escrow=0 capital=119");realm.close();h.succeed();return;
            }
            readOnlyProbe(h,realm,id,marker,ticks+1);
        });
    }
}
