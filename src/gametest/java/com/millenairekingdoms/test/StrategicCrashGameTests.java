package com.millenairekingdoms.test;

import com.millenairekingdoms.autonomy.*;
import com.millenairekingdoms.history.*;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.village.VillageSavedData;

/** Actual test-JVM halts at public durable domain boundaries; no production crash switch or forced battle outcome. */
@PrefixGameTestTemplate(false)
public final class StrategicCrashGameTests {
    @GameTest(templateNamespace="millenairekingdoms_strategic_crash_tests",template="empty",timeoutTicks=1200)
    public static void committedDiceAndPoliticalReceiptsRecoverOnceAfterActualProcessInterruption(GameTestHelper h){
        var level=h.getLevel();var marker=level.getServer().getWorldPath(LevelResource.ROOT).resolve("strategic-crash.dat");var cutMarker=marker.resolveSibling("strategic-cut.dat");var phase=System.getProperty("kingdoms.tests.strategicCrashPhase","prepare");var cut=System.getProperty("kingdoms.tests.strategicCut","after-roll");
        if(phase.equals("prepare")){
            var fixture=new StrategicMilitaryGameTests.Fixture(h);var order=fixture.order(fixture.b.getId().uuid());var saved=new CompoundTag();saved.putUUID("order",order.id());saved.putLong("due",order.dueGameTime());HistoryFiles.write(marker,saved);VillageSavedData.get(level).setDirty();level.getServer().saveEverything(true,true,true);StrategicBattleService.acknowledgePolitical(level,fixture.data);h.succeed();return;
        }
        if(phase.equals("cut")){
            var saved=HistoryFiles.read(marker);long delay=Math.max(1,saved.getLong("due")-level.getGameTime()+1);h.runAfterDelay((int)delay,()->{
                var data=KingdomSavedData.get(level);var order=data.order(saved.getUUID("order"));h.assertTrue(order!=null,"Prepared original military order reopens before the cut");var op=StrategicMilitaryGameTests.operation(h,order,true);new StrategicBattleStore(level).commit(op);var record=new CompoundTag();record.putString("cut",cut);record.put("operation",StrategicBattleStore.encode(op));HistoryFiles.write(cutMarker,record);
                if(!cut.equals("after-roll")){
                    var raid=data.beginIdentified(order.source(),order.target(),op.tick(),null,op.id(),op.id());
                    if(cut.equals("after-victory") || cut.equals("after-political-ack"))data.recordVictory(raid);
                    if(cut.equals("after-political-ack"))data.finish(data.raid(order.target()),KingdomSavedData.Outcome.LOST);
                    StrategicBattleService.acknowledgePolitical(level,data);
                }
                h.assertTrue(Set.of("after-roll","after-begin","after-victory","after-political-ack").contains(cut),"Only documented military process-cut phases");System.out.println("EXPECTED_STRATEGIC_PROCESS_HALT cut="+cut+" operation="+op.id()+" counter="+data.wins(op.campaign()));System.out.flush();Runtime.getRuntime().halt(70);
            });return;
        }
        h.assertTrue(phase.equals("read"),"Known interruption fixture phase");var saved=HistoryFiles.read(cutMarker);var original=StrategicBattleStore.decode(saved.getCompound("operation"));var store=new StrategicBattleStore(level);var current=store.read(original.id());h.assertTrue(current!=null && current.roll().equals(original.roll()),"Actual process interruption preserves the exact committed dice, never a replacement draw");StrategicBattleService.resume(level,store,current);
        var data=KingdomSavedData.get(level);h.assertTrue(data.receipt(original.id())!=null && data.receipt(original.id()).outcome()==KingdomSavedData.Outcome.WON && data.wins(original.campaign())==1 && data.activeRaids().isEmpty() && store.read(original.id()).status()==StrategicBattleStore.Status.RESOLVED,"Original native-record battle advances political progress exactly once across a real process halt");
        var event=HistoryHooks.store(level).get(original.narrative().id());h.assertTrue(event!=null && event.equals(original.narrative()),"A single exact factual Chronicle follows the acknowledged political receipt");StrategicBattleService.resume(level,store,store.read(original.id()));h.assertTrue(data.wins(original.campaign())==1,"Repeated recovered operation cannot duplicate conquest credit");System.out.println("STRATEGIC_CRASH_REOPEN cut="+saved.getString("cut")+" operation="+original.id()+" counter=1 exactDice=true history="+event.id());h.succeed();
    }
}
