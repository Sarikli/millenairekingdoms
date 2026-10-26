package com.millenairekingdoms.test;

import com.millenairekingdoms.history.ChronicleViews;
import com.millenairekingdoms.network.ChronicleRequestPayload;
import java.nio.file.*;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Current removal gates; all older survey and inert-facade fixtures remain in historical archives. */
@PrefixGameTestTemplate(false)
public final class GeographyDisabledGameTests {
    private static final String NS="millenairekingdoms_geography_tests";
    private static ChronicleRequestPayload political(UUID session){return new ChronicleRequestPayload(session,9,"ATLAS",Optional.empty(),"",0,Long.MAX_VALUE,Optional.empty(),"",Optional.empty());}
    @GameTest(templateNamespace=NS,template="empty")
    public static void surveyProcessingAndPersistenceClassesAreAbsentFromTheActualJar(GameTestHelper h){
        var type=com.millenairekingdoms.MillenaireKingdoms.class;for(var name:List.of("GeographyService","GeographyFiles","GeographyStore","GeographySnapshot","GeographyTile","GeographyRaster","GeographyView"))h.assertTrue(type.getResource("/com/millenairekingdoms/atlas/geography/"+name+".class")==null,"Retired implementation is absent from the production artifact: "+name);h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty")
    public static void oldCacheBytesStayUntouchedAcrossSeparateJvmAndPoliticalRequests(GameTestHelper h)throws Exception{
        var level=h.getLevel();var owner=UUID.fromString("21874642-a463-497a-b03a-372d19638455");var root=DimensionType.getStorageFolder(level.dimension(),level.getServer().getWorldPath(LevelResource.ROOT)).resolve("data/millenairekingdoms/geography").resolve(owner.toString());Files.createDirectories(root);var index=root.resolve("index.dat");var sentinel=new byte[]{0,42,99,12,-1,7,3,9};
        if(RoyalShipmentSupport.read())h.assertTrue(Files.exists(index),"Separate JVM reopens the same historical bytes");else Files.write(index,sentinel);
        var player=new Stage5Support.TestPlayer(level,"Retired geography observer");player.setUUID(owner);int loaded=level.getChunkSource().getLoadedChunksCount();ChronicleViews.respond(player,political(UUID.randomUUID()));
        h.assertTrue(Arrays.equals(sentinel,Files.readAllBytes(index)) && loaded==level.getChunkSource().getLoadedChunksCount(),"Normal political metadata never reads/replaces a retired cache or forces terrain chunks");try(var entries=Files.list(root)){h.assertTrue(entries.count()==1,"No geography lock, tile, job, index replacement or temporary file");}ChronicleViews.forget(owner);h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty")
    public static void retiredGeographyPacketsAreRejectedBeforeAnyRuntimeRequestBudget(GameTestHelper h){
        var session=UUID.randomUUID();for(int n=0;n<8;n++){final int serial=n;HistoryGameTests.refused(h,()->new ChronicleRequestPayload(session,serial,"GEOGRAPHY",Optional.empty(),"",0,Long.MAX_VALUE,Optional.empty(),"",Optional.empty()));}
        var player=new Stage5Support.TestPlayer(h.getLevel(),"Political request");var view=ChronicleViews.respond(player,political(session));h.assertTrue(!view.contains("retryAfter") && !view.contains("error"),"Rejected retired modes cannot consume the political throttle or retain Loading");ChronicleViews.forget(player.getUUID());h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty")
    public static void noGeographyWorkersHooksOrClientRequestsRemain(GameTestHelper h)throws Exception{
        h.assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t->t.getName().startsWith("Kingdoms geography")),"No retired background worker exists");
        for(var name:List.of("client/AtlasScreen","history/ChronicleViews","MillenaireKingdoms"))try(var input=com.millenairekingdoms.MillenaireKingdoms.class.getResourceAsStream("/com/millenairekingdoms/"+name+".class")){h.assertTrue(input!=null,"Actual production class exists");var bytes=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.ISO_8859_1);h.assertTrue(!bytes.contains("Geography") && !bytes.contains("GEOGRAPHY"),"No production scanner, renderer, packet dispatch or cleanup hook: "+name);}h.succeed();
    }
}
