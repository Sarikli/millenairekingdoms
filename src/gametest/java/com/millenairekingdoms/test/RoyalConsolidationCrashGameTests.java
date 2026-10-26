package com.millenairekingdoms.test;

import com.millenairekingdoms.economy.*;
import com.millenairekingdoms.history.HistoryFiles;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.item.ModItems;

/** Real halt70 at injected persistence barriers, using unmodified production journals/receipts and real chunk files. */
@PrefixGameTestTemplate(false)
public final class RoyalConsolidationCrashGameTests {
    @GameTest(templateNamespace="millenairekingdoms_consolidation_crash_tests",template="empty",timeoutTicks=1200)
    public static void physicalCoinsConserveValueAcrossActualJvmInterruption(GameTestHelper h){
        var level=h.getLevel();var marker=level.getServer().getWorldPath(LevelResource.ROOT).resolve("consolidation-crash.dat");var cutMarker=marker.resolveSibling("consolidation-cut.dat");var phase=System.getProperty("kingdoms.tests.consolidationPhase","prepare");var cut=System.getProperty("kingdoms.tests.consolidationCut","before-write");
        if(phase.equals("prepare")){
            var f=RoyalShipmentSupport.realm(h,"consol-process-cut");var c=f.chest(f.capital());RoyalConsolidationGameTests.clear(c);RoyalConsolidationGameTests.mixed(c);var tag=new CompoundTag();tag.put("realm",f.marker());HistoryFiles.write(marker,tag);f.save();h.assertTrue(f.coins()==12610,"Actual native12610 is saved before the independent cut JVM");h.succeed();return;
        }
        var f=RoyalShipmentSupport.restore(h,HistoryFiles.read(marker).getCompound("realm"),false);var c=f.chest(f.capital());var service=new RoyalConsolidationService(level);
        if(phase.equals("cut")){
            var barrier=new RoyalTreasuryPersistence(){int count;@Override public void flush(ServerLevel l,List<BlockPos> positions){
                count++;if(count==2 && cut.equals("before-write")){halt(l);return;}
                if(count==2 && cut.equals("changed-chunk-only")){var e=RoyalConsolidationGameTests.pending(l,f.kingdom());super.flush(l,List.of(BlockPos.of(e.intent().getLong("position"))));halt(l);return;}
                super.flush(l,positions);if(count==2 && cut.equals("after-write"))halt(l);if(count==3 && cut.equals("after-ack"))halt(l);
            }
            private void halt(ServerLevel l){var e=RoyalConsolidationGameTests.pending(l,f.kingdom());var record=new CompoundTag();record.putUUID("id",e.id());record.putString("cut",cut);record.putString("state",e.state().name());HistoryFiles.write(cutMarker,record);h.assertTrue(Set.of("before-write","changed-chunk-only","after-write","after-ack").contains(cut) && f.coins()==12610,"Only documented cut barriers with exact conserving inventory");System.out.println("EXPECTED_CONSOLIDATION_PROCESS_HALT cut="+cut+" operation="+e.id()+" state="+e.state()+" value=12610");System.out.flush();Runtime.getRuntime().halt(70);}};
            RoyalConsolidationGameTests.service(level,barrier).consolidate(f.kingdom());h.fail("Expected actual JVM interruption was not reached");return;
        }
        h.assertTrue(phase.equals("read"),"Known physical interruption recovery phase");var tag=HistoryFiles.read(cutMarker);var id=tag.getUUID("id");var before=service.inspect(id);h.assertTrue(before!=null && f.coins()==12610,"Interrupted physical chunk ordering always retains exact12610, with original operation journal");RoyalConsolidationGameTests.committed(h,service.recover(id),12610);RoyalConsolidationGameTests.committed(h,service.recover(id),12610);h.assertTrue(f.coins()==12610 && RoyalConsolidationGameTests.count(c,ModItems.DENIER_OR.get())==3 && RoyalConsolidationGameTests.count(c,ModItems.DENIER_ARGENT.get())==5 && RoyalConsolidationGameTests.count(c,ModItems.DENIER.get())==2 && service.consolidate(f.kingdom()).status()==RoyalConsolidationService.Status.NO_CHANGE,"Real process recovery converts once, preserves physical12610 and never adds currency");System.out.println("CONSOLIDATION_CRASH_REOPEN cut="+tag.getString("cut")+" originalState="+before.state()+" value=12610 gold=3 silver=5 bronze=2 exactOnce=true");h.succeed();
    }
}
