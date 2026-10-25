package com.millenairekingdoms.test;

import com.millenairekingdoms.atlas.geography.*;
import com.millenairekingdoms.history.ChronicleViews;
import com.millenairekingdoms.network.ChronicleRequestPayload;
import com.mojang.authlib.GameProfile;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Deliberately stalls only the own-cache worker; main-thread sampling/queries must remain available. */
@PrefixGameTestTemplate(false)
public final class GeographyAsyncGameTests {
    @GameTest(templateNamespace="millenairekingdoms_geography_tests",template="empty",batch="geography_async",timeoutTicks=1200)
    public static void liveSamplerQueuesBoundedOwnFileWritesAndNeverExposesUnacknowledgedMasks(GameTestHelper h)throws Exception{
        var level=h.getLevel();var center=h.absolutePos(new BlockPos(2,1,2));
        for(int z=(center.getZ()>>4)-3;z<=(center.getZ()>>4)+3;z++)for(int x=(center.getX()>>4)-3;x<=(center.getX()>>4)+3;x++)level.getChunk(x,z);
        var observer=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"Async cartographer"));observer.setPos(center.getX()+.5,256,center.getZ()+.5);
        // Test-only reflection queues a bounded gate; production needs neither a failure hook nor a test system property.
        var field=GeographyService.class.getDeclaredField("MASK_IO");field.setAccessible(true);var executor=(ExecutorService)field.get(null);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var gate=executor.submit(()->{entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});
        long samplingNanos=0;int loadedBefore=level.getChunkSource().getLoadedChunksCount(),forcedBefore=level.getForcedChunks().size();
        try{
            h.assertTrue(entered.await(1,TimeUnit.SECONDS),"Own-cache worker reached the controlled fixture gate");
            for(int n=0;n<4 && GeographyService.pendingWrites()==0;n++){long started=System.nanoTime();int considered=GeographyService.observePlayers(level.getServer(),List.of(observer));samplingNanos+=System.nanoTime()-started;h.assertTrue(considered<=256,"Live global column budget is preserved");}
            h.assertTrue(GeographyService.pendingWrites()>0 && GeographyService.pendingWrites()<=GeographyService.MAX_PENDING_WRITES,"Main-thread sampler returns while its own bounded file work remains gated");
            h.assertTrue(!GeographyService.ready(observer) && !Files.exists(GeographyService.directory(level,observer.getUUID()).resolve("index.dat")),"Pending observations are neither published nor described as durable");
            var view=ChronicleViews.respond(observer,ChronicleRequestPayload.geography(UUID.randomUUID(),1,0,Long.MAX_VALUE));h.assertTrue(view.getInt("retryAfter")>0 && !view.contains("geography"),"Read-only endpoint retries instead of racing mutable metadata or returning an unacknowledged mask");
            h.assertTrue(loadedBefore==level.getChunkSource().getLoadedChunksCount() && forcedBefore==level.getForcedChunks().size(),"Acquisition and a pending response never load/force chunks");
        }finally{release.countDown();}
        final long measured=samplingNanos;
        h.succeedWhen(()->{
            h.assertTrue(GeographyService.ready(observer),"Own-cache atomic acknowledgement is still pending");var store=GeographyService.store(observer);h.assertTrue(store.size()>0 && store.revision()>0 && !store.query(0,Long.MAX_VALUE).tiles().isEmpty(),"Only the acknowledged real observations become queryable");
            h.assertTrue(GeographyService.lastWriteThread(observer).equals("Kingdoms geography writer"),"File compression/fsync/root commit ran on the dedicated own-cache worker, not the game thread");
            System.out.println("GEOGRAPHY_ASYNC_IO main_sampling_ms="+measured/1_000_000.0+" writer="+GeographyService.lastWriteThread(observer)+" pending_cap="+GeographyService.MAX_PENDING_WRITES+" acknowledged_revision="+store.revision()+" loaded_before="+loadedBefore+" loaded_after="+level.getChunkSource().getLoadedChunksCount()+" forced_before="+forcedBefore+" forced_after="+level.getForcedChunks().size());
        });
    }
}
