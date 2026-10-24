package com.millenairekingdoms.test;

import com.millenairekingdoms.autonomy.AutonomyConfig;
import com.millenairekingdoms.autonomy.RoyalNpcConfig;
import com.millenairekingdoms.economy.RoyalNpcDecisionStore;
import com.millenairekingdoms.economy.RoyalNpcDecisionStore.Kind;
import com.millenairekingdoms.economy.RoyalPaymentJournal.State;
import com.millenairekingdoms.shipping.RoyalShipmentConfig;
import com.millenairekingdoms.shipping.RoyalShipmentStore.Stage;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** After fixture setup this probe READS only. Production NPC HIGH events choose/issue and shipping LOWEST events move
 * native goods. Test fixtures explicitly keep their own villages loaded/active and sprint real ticks, never edit time. */
@PrefixGameTestTemplate(false)
public final class RoyalNpcLiveGameTests {
    private static final Map<ServerLevel,List<RoyalShipmentSupport.Realm>> FIXTURES=new WeakHashMap<>();
    static void fixtureTick(ServerLevel level){for(var r:FIXTURES.getOrDefault(level,List.of()))r.activate(true);}
    @GameTest(templateNamespace="millenairekingdoms_royal_npc_live_tests",template="empty",timeoutTicks=20000)
    public static void defaultAutomaticNpcNeedsAndShippingEventsSurviveSeparateJvmWithoutDirectPolicyCalls(GameTestHelper h) throws Exception {
        h.assertTrue(AutonomyConfig.current().autonomy() && RoyalNpcConfig.budgets().enabled() && RoyalNpcConfig.cadence().intervalTicks()==600 && RoyalNpcConfig.cadence().maximumKingdoms()==2
                && RoyalShipmentConfig.TRACKING.get() && RoyalShipmentConfig.INTERVAL.get()==100 && RoyalShipmentConfig.MAX_PER_STEP.get()==2,"Actual automatic event defaults600/2 NPC,100/2 shipping are enabled");
        var level=h.getLevel();var marker=level.getServer().getWorldPath(LevelResource.ROOT).resolve("automatic-npc-economy.dat");RoyalShipmentSupport.Realm realm;
        if(RoyalShipmentSupport.read()){
            var tag=NbtIo.readCompressed(marker,NbtAccounter.create(1024*1024));realm=RoyalShipmentSupport.restore(h,tag,true);var entry=realm.store().load(tag.getUUID("order"));
            h.assertTrue(entry.stage()==Stage.IN_TRANSIT && entry.escrow()==tag.getInt("quantity") && realm.coins()==247,"Actual automatic chosen/paid/collected native goods and intent reopen without repairs");
        }else{realm=RoyalShipmentSupport.realm(h,"Actual automatic NPC logistics");RoyalNpcEconomyGameTests.shortage(h,realm);}
        FIXTURES.put(level,List.of(realm));level.getServer().tickRateManager().requestGameToSprint(16000);probe(h,realm,marker,0);
    }
    private static void probe(GameTestHelper h,RoyalShipmentSupport.Realm realm,Path marker,int ticks){
        if(ticks>12000){FIXTURES.remove(h.getLevel());realm.close();h.fail("Automatic NPC decisions/shipping did not reach expected phase");return;}
        h.runAfterDelay(1,()->{
            var rows=new RoyalNpcDecisionStore(RoyalNpcDecisionStore.directory(h.getLevel())).entries().stream().filter(d->d.kingdom().equals(realm.kingdom()) && d.choice().kind()==Kind.SHIPMENT).toList();
            if(rows.size()>1){FIXTURES.remove(h.getLevel());realm.close();h.fail("Automatic original need duplicated its persisted policy attempt");return;}
            if(!rows.isEmpty()){
                var decision=rows.getFirst();var row=realm.store().load(decision.id());
                if(row!=null && row.stage()==Stage.RECOVERY_REQUIRED){FIXTURES.remove(h.getLevel());realm.close();h.fail("Automatic shipment needs recovery: "+row.diagnosis());return;}
                if(!RoyalShipmentSupport.read() && row!=null && row.stage()==Stage.IN_TRANSIT){
                    var product=net.minecraft.core.registries.BuiltInRegistries.ITEM.get(row.intent().product());int quantity=row.intent().quantity();
                    h.assertTrue(row.intent().travelTicks()==3600 && row.intent().fee()==9 && row.escrow()==quantity && realm.coins()==247 && row.due()-realm.level().getGameTime()>3000
                            && RoyalNpcEconomyGameTests.count(realm,realm.target(),product)==0,"Production events alone found native deficit, paid9, collected actual cargo and froze future default3600-tick route");
                    var tag=realm.marker();tag.putUUID("order",row.intent().id());tag.putInt("quantity",quantity);tag.putInt("sourceCount",RoyalNpcEconomyGameTests.count(realm,realm.source(),product));realm.save();
                    try{NbtIo.writeCompressed(tag,marker);}catch(java.io.IOException failure){throw new IllegalStateException(failure);}FIXTURES.remove(h.getLevel());
                    System.out.println("ROYAL_NPC_AUTOMATIC_WRITE ticks="+ticks+" issued="+row.intent().issued()+" due="+row.due()+" held="+row.escrow()+" treasury=247");h.succeed();return;
                }
                if(RoyalShipmentSupport.read() && row!=null && row.terminal()){
                    try{
                        var tag=NbtIo.readCompressed(marker,NbtAccounter.create(1024*1024));var product=net.minecraft.core.registries.BuiltInRegistries.ITEM.get(row.intent().product());
                        h.assertTrue(row.stage()==Stage.DELIVERED && row.escrow()==0 && realm.level().getGameTime()>=row.due() && realm.coins()==247 && realm.payments().load(row.intent().id()).state()==State.COMMITTED
                                && RoyalNpcEconomyGameTests.count(realm,realm.source(),product)==tag.getInt("sourceCount") && RoyalNpcEconomyGameTests.count(realm,realm.target(),product)==tag.getInt("quantity"),"Production events alone deliver exact original default-timed cargo after JVM reopen with one9 fee and no second policy attempt");
                    }catch(java.io.IOException failure){throw new IllegalStateException(failure);}FIXTURES.remove(h.getLevel());
                    System.out.println("ROYAL_NPC_AUTOMATIC_READ ticks="+ticks+" due="+row.due()+" now="+realm.level().getGameTime()+" delivered="+row.delivered()+" treasury=247 attempts=1");realm.close();h.succeed();return;
                }
            }
            probe(h,realm,marker,ticks+1);
        });
    }
    private RoyalNpcLiveGameTests(){}
}
