package com.millenairekingdoms.test;

import com.millenairekingdoms.atlas.geography.*;
import com.millenairekingdoms.history.ChronicleViews;
import com.millenairekingdoms.network.ChronicleRequestPayload;
import com.mojang.authlib.GameProfile;
import java.nio.file.*;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Disabled behavior replaces active-survey tests; previous survey artifacts/evidence remain historical. */
@PrefixGameTestTemplate(false)
public final class GeographyDisabledGameTests {
    private static final String NS="millenairekingdoms_geography_tests";
    private static void refused(GameTestHelper h,Runnable action){boolean refused=false;try{action.run();}catch(UnsupportedOperationException expected){refused=true;}h.assertTrue(refused,"Disabled cache access must fail before touching world/files");}
    @GameTest(templateNamespace=NS,template="empty")
    public static void everySurveyEntryPointIsInertWithoutEvenDereferencingAWorld(GameTestHelper h){
        h.assertTrue(!GeographyService.ENABLED && GeographyService.capture(null)==0 && GeographyService.captureAsync(null)==0 && GeographyService.observePlayers(null,List.of())==0,"Scanning is unconditionally disabled, including direct/legacy entry points");
        h.assertTrue(GeographyService.classifyLoadedColumn(null,0,0)==GeographyTile.UNKNOWN && GeographyService.classifyCoastal((x,z)->{throw new AssertionError("Terrain provider must never be invoked");},0,0)==GeographyTile.UNKNOWN,"Neither world reads nor coastal probes execute");
        h.assertTrue(!GeographyService.ready(null) && GeographyService.pendingWrites()==0 && GeographyService.activeSessions(null)==0 && GeographyService.lastWriteThread(null).isEmpty(),"No session, processing or persistence job can be created");GeographyService.clear(null);refused(h,()->GeographyService.store(null));h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty")
    public static void oldCacheBytesAreUntouchedInWriteAndSeparateJvmReopen(GameTestHelper h)throws Exception{
        var level=h.getLevel();var owner=UUID.fromString("21874642-a463-497a-b03a-372d19638455");var root=GeographyService.directory(level,owner);Files.createDirectories(root);var index=root.resolve("index.dat");var sentinel=new byte[]{0,42,99,12,-1,7,3,9};
        if("read".equals(System.getProperty("kingdoms.tests.persistence")))h.assertTrue(Files.exists(index),"Separate JVM must reopen the same existing cache evidence");else Files.write(index,sentinel);
        var observer=new FakePlayer(level,new GameProfile(owner,"Disabled cache"));int loaded=level.getChunkSource().getLoadedChunksCount(),forced=level.getForcedChunks().size();
        GeographyService.capture(observer);GeographyService.captureAsync(observer);GeographyService.observePlayers(level.getServer(),List.of(observer));var view=ChronicleViews.respond(observer,ChronicleRequestPayload.geography(UUID.randomUUID(),1,0,Long.MAX_VALUE));
        refused(h,()->new GeographyStore(root,owner,level.dimension().location().toString()));h.assertTrue(Arrays.equals(Files.readAllBytes(index),sentinel),"Disabled code preserves arbitrary old/future/corrupt cache bytes instead of reading/resetting them");
        try(var entries=Files.list(root)){h.assertTrue(entries.count()==1,"No lock, index replacement, tile, shard or temporary file is created");}
        h.assertTrue(view.getBoolean("disabled") && loaded==level.getChunkSource().getLoadedChunksCount() && forced==level.getForcedChunks().size(),"No terrain load, ticket or cache publication occurs");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty")
    public static void legacyGeographyRequestsCannotBlockThePoliticalAtlas(GameTestHelper h){
        var level=h.getLevel();var observer=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"Political atlas"));var session=UUID.randomUUID();
        for(int n=0;n<8;n++){var view=ChronicleViews.respond(observer,ChronicleRequestPayload.geography(session,n,0,Long.MAX_VALUE));h.assertTrue(view.getBoolean("disabled") && view.getString("error").equals("disabled") && !view.contains("retryAfter") && !view.contains("geography"),"Legacy requests receive a terminal disabled response with no Loading, masks or disk access");}
        var political=ChronicleViews.respond(observer,new ChronicleRequestPayload(session,9,"ATLAS",Optional.empty(),"",0,Long.MAX_VALUE,Optional.empty(),"",Optional.empty()));h.assertTrue(!political.contains("retryAfter") && !political.contains("error") && political.getString("mode").equals("ATLAS"),"Disabled ocean traffic cannot consume the political atlas budget");ChronicleViews.forget(observer.getUUID());h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty")
    public static void noGeographyWorkersOrClientLayerArePresent(GameTestHelper h)throws Exception{
        h.assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t->t.getName().startsWith("Kingdoms geography")),"Disabled runtime never starts an index reader, mask processor or persistence worker");
        try(var input=com.millenairekingdoms.MillenaireKingdoms.class.getResourceAsStream("/com/millenairekingdoms/client/AtlasScreen.class")){h.assertTrue(input!=null,"Inspect the actual packaged client atlas without loading client classes on a server");String bytes=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.ISO_8859_1);h.assertTrue(!bytes.contains("Geography") && !bytes.contains("GEOGRAPHY") && !bytes.contains("ocean_"),"Packaged political atlas has no ocean renderer, request loop or reactivation button");}
        h.succeed();
    }
}
