package com.millenairekingdoms.test;

import com.millenairekingdoms.economy.*;
import com.millenairekingdoms.history.HistoryFiles;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.item.ModItems;

/** Ordinary production event timing; fixture alone pins the player capital storage chunks and keeps native activity available.
 * No direct runtime.step/service.consolidate invocation in this probe; cold capital receives no availability repair. */
@PrefixGameTestTemplate(false)
public final class RoyalConsolidationLiveGameTests {
    @GameTest(templateNamespace="millenairekingdoms_consolidation_live_tests",template="empty",timeoutTicks=4000)
    public static void automaticBoundedMaintenanceConservesPlayerTreasuryAndSkipsUnavailableCapital(GameTestHelper h)throws Exception{
        var level=h.getLevel();var marker=level.getServer().getWorldPath(LevelResource.ROOT).resolve("consolidation-live.dat");
        if(RoyalShipmentSupport.read()){
            var tag=HistoryFiles.read(marker);var realm=RoyalShipmentSupport.restore(h,tag.getCompound("realm"),false);var c=realm.chest(realm.capital());var e=new RoyalConsolidationService(level).inspect(tag.getUUID("operation"));h.assertTrue(e!=null && e.state()==RoyalPaymentJournal.State.COMMITTED && RoyalConsolidationGameTests.value(c)==4096 && c.getItem(0).is(ModItems.DENIER_OR.get()),"Event-created conservation receipt and original native gold reopen without another conversion");System.out.println("CONSOLIDATION_AUTOMATIC_REOPEN value=4096 exactReceipt=true");h.succeed();return;
        }
        var cold=RoyalShipmentSupport.realm(h,"consol-live-unavailable");cold.capital().setActive(false);var realm=RoyalShipmentSupport.realm(h,"consol-live-player");realm.ruler();var c=realm.chest(realm.capital());RoyalConsolidationGameTests.clear(c);c.setItem(0,new ItemStack(ModItems.DENIER_ARGENT.get(),64));var pinned=realm.capital().getTownhall().getInventory().getChestPositions().stream().map(net.minecraft.world.level.ChunkPos::new).distinct().toList();for(var chunk:pinned)level.setChunkForced(chunk.x,chunk.z,true);var start=level.getGameTime();var forced=Set.copyOf(level.getForcedChunks());class Probe implements Runnable{public void run(){
            RoyalShipmentSupport.activateVillage(level,realm.capital());
            var entries=new java.util.ArrayList<RoyalPaymentJournal.Entry>();var service=new RoyalConsolidationService(level);var root=RoyalConsolidationService.directory(level).resolve("archive");if(java.nio.file.Files.exists(root))try(var files=java.nio.file.Files.list(root)){for(var p:files.filter(x->x.toString().endsWith(".dat")).toList()){var id=UUID.fromString(p.getFileName().toString().replace(".dat",""));var e=service.inspect(id);if(e.intent().getUUID("kingdom").equals(realm.kingdom()))entries.add(e);}}catch(java.io.IOException failure){throw new IllegalStateException(failure);}
            if(!entries.isEmpty()){var e=entries.getFirst();h.assertTrue(entries.size()==1 && e.state()==RoyalPaymentJournal.State.COMMITTED && RoyalConsolidationGameTests.value(c)==4096 && c.getItem(0).is(ModItems.DENIER_OR.get()),"Production tick performs one useful conversion without direct test scheduling or service calls");var report=RoyalConsolidationRuntime.last(level);h.assertTrue(report.inspected()<=1 && forced.equals(level.getForcedChunks()) && RoyalConsolidationGameTests.pending(level,cold.kingdom())==null,"Default one-capital budget and unavailable capital do not force chunks or authorize unsafe work");var tag=new CompoundTag();tag.put("realm",realm.marker());tag.putUUID("operation",e.id());HistoryFiles.write(marker,tag);for(var chunk:pinned)level.setChunkForced(chunk.x,chunk.z,false);realm.save();System.out.println("CONSOLIDATION_AUTOMATIC fixturePinnedChunks="+pinned.size()+" ticks="+(level.getGameTime()-start)+" before=4096 after=4096 lastMainThreadMicros="+(report.elapsedNanos()/1000)+" inspected="+report.inspected()+" players="+level.players().size());h.succeed();return;}
            if(level.getGameTime()-start>3600)h.fail("No automatic treasury consolidation within3600ticks; last="+RoyalConsolidationRuntime.last(level)+" pending="+service.current().size());else h.runAfterDelay(10,()->run());
        }}h.runAfterDelay(10,new Probe());
    }
}
