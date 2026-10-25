package com.millenairekingdoms.test;

import com.millenairekingdoms.atlas.*;
import com.millenairekingdoms.history.*;
import java.nio.file.Files;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Pure geometry and real registered-village visibility; never claims physical NPC construction. */
@PrefixGameTestTemplate(false)
public final class AtlasGameTests {
    private static final String NS="millenairekingdoms_atlas_tests";
    private static final UUID A=new UUID(90,1),B=new UUID(90,2);
    static AtlasGeometry.Site site(int n,UUID kingdom,int x,int z){return new AtlasGeometry.Site(new UUID(91,n),kingdom,x,z);}
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void foreignOverlapsClipAtActualNearestSettlementBoundary(GameTestHelper h){
        var mesh=AtlasGeometry.cached("minecraft:overworld",500,List.of(site(1,A,0,0),site(2,B,400,0)));
        h.assertTrue(mesh.cells().get(0).polygon().stream().allMatch(p->p.x()<=200.00001) && mesh.cells().get(1).polygon().stream().allMatch(p->p.x()>=199.99999),"Opposing influence clips at the200-block bisector, no claimed terrain mutation");
        h.assertTrue(mesh.borders().stream().anyMatch(b->Math.abs(b.start().x()-200)<.001 && Math.abs(b.end().x()-200)<.001),"Actual political dividing edge is retained");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void sameRealmJoinsLocallyAndDistantSitesNeverAcquireCorridors(GameTestHelper h){
        var adjacent=AtlasGeometry.cached("minecraft:overworld",500,List.of(site(1,A,0,0),site(2,A,400,0)));
        h.assertTrue(adjacent.borders().stream().noneMatch(b->Math.abs(b.start().x()-200)<.001 && Math.abs(b.end().x()-200)<.001),"A shared same-kingdom interior seam is not a political border");
        var distant=AtlasGeometry.cached("minecraft:overworld",500,List.of(site(1,A,0,0),site(2,A,1600,0)));
        h.assertTrue(distant.cells().get(0).polygon().stream().allMatch(p->p.x()<=500.00001) && distant.cells().get(1).polygon().stream().allMatch(p->p.x()>=1099.99999),"Uninfluenced600-block gap remains empty even for one kingdom");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void duplicateCoordinatesAndWorldEdgesAreFiniteAndDeterministic(GameTestHelper h){
        var first=site(1,A,29_999_000,-29_999_000);var second=site(2,B,29_999_000,-29_999_000);var mesh=AtlasGeometry.cached("minecraft:overworld",500,List.of(second,first));
        h.assertTrue(mesh.cells().getFirst().site().equals(first) && mesh.cells().get(1).polygon().isEmpty() && mesh.cells().getFirst().polygon().stream().allMatch(p->Double.isFinite(p.x()) && Double.isFinite(p.z())),"Stable UUID tie resolves coincident metadata without NaN or a phantom corridor");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void cacheKeysIncludeOwnershipCoordinatesRadiusAndDimension(GameTestHelper h){
        var sites=List.of(site(1,A,0,0),site(2,B,400,0));var original=AtlasGeometry.cached("minecraft:overworld",500,sites);
        h.assertTrue(original==AtlasGeometry.cached("minecraft:overworld",500,sites) && original!=AtlasGeometry.cached("minecraft:the_nether",500,sites) && original!=AtlasGeometry.cached("minecraft:overworld",400,sites),"Stable pan/zoom needs no rebuild; dimensions/radius have separate geometry");
        var captured=AtlasGeometry.cached("minecraft:overworld",500,List.of(site(1,A,0,0),site(2,A,400,0)));h.assertTrue(captured!=original && captured.borders().size()<original.borders().size(),"Capture invalidates current political border topology");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void geometryAndVisibilityBoundsRejectMalformedInput(GameTestHelper h){
        HistoryGameTests.refused(h,()->AtlasGeometry.cached("minecraft:overworld",0,List.of()));HistoryGameTests.refused(h,()->AtlasGeometry.cached("bad dimension",500,List.of()));HistoryGameTests.refused(h,()->AtlasGeometry.cached("minecraft:overworld",500,List.of(site(1,A,0,0),site(1,A,1,1))));
        h.assertTrue(AtlasGeometry.color(A)==AtlasGeometry.color(A) && AtlasGeometry.color(A)!=AtlasGeometry.color(B) && AtlasGeometry.color(null)==0xFF888477,"Colors follow persistent IDs and neutral villages remain distinct from kingdoms");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void actualRegisteredDiscoveryRequiresLoadedProximityAndIsRemembered(GameTestHelper h){
        try(var f=new PersonalityGameTests.Villages(h)){
            var v=f.add("norman/militaire");var level=h.getLevel();var player=new Stage5Support.TestPlayer(level,"Atlas visitor");player.setPos(0,64,0);
            h.assertTrue(!AtlasDiscovery.known(player).contains(v.getId().uuid()) && !level.isLoaded(v.getCenter()),"Far registered coordinates are not disclosed and discovery never forces their chunks");
            level.getChunkAt(v.getCenter());player.setPos(v.getCenter().getX(),v.getCenter().getY(),v.getCenter().getZ());h.assertTrue(AtlasDiscovery.known(player).contains(v.getId().uuid()),"Real loaded proximity discovers an actual registered settlement");
            player.setPos(0,64,0);h.assertTrue(AtlasDiscovery.known(player).contains(v.getId().uuid()),"Already learned geography survives moving away");
            var nether=level.getServer().getLevel(Level.NETHER);var other=new Stage5Support.TestPlayer(nether,"Dimension visitor");other.setUUID(player.getUUID());h.assertTrue(!AtlasDiscovery.known(other).contains(v.getId().uuid()),"Same player has no cross-dimension coordinate leak");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void futureDiscoveryDataRemainsByteExact(GameTestHelper h)throws Exception{
        var player=new Stage5Support.TestPlayer(h.getLevel(),"Future atlas");var file=HistoryHooks.directory(h.getLevel()).resolve("discovery").resolve(player.getUUID()+".dat");var tag=new CompoundTag();tag.putInt("format",99);tag.put("villages",new ListTag());HistoryFiles.write(file,tag);var bytes=Files.readAllBytes(file);
        HistoryGameTests.refused(h,()->AtlasDiscovery.known(player));h.assertTrue(Arrays.equals(bytes,Files.readAllBytes(file)),"Unsupported discovery is not reset to expose or forget locations");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void fiveHundredSettlementsBuildOnceAndReuseTheMesh(GameTestHelper h){
        var sites=new ArrayList<AtlasGeometry.Site>();for(int n=0;n<500;n++)sites.add(site(n,n%3==0?A:B,(n%25)*400,(n/25)*400));long start=System.nanoTime();var mesh=AtlasGeometry.cached("minecraft:overworld",500,sites);long elapsed=System.nanoTime()-start;
        h.assertTrue(mesh.cells().size()==500 && mesh.cells().stream().allMatch(c->c.polygon().size()>=3) && mesh==AtlasGeometry.cached("minecraft:overworld",500,sites),"Hundreds of sites keep finite nonempty clipped territories and reuse cached geometry");System.out.println("ATLAS_GEOMETRY sites=500 coldMicros="+elapsed/1000+" cells="+mesh.cells().size()+" borders="+mesh.borders().size());h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",batch="atlas_restart",timeoutTicks=1200)
    public static void separateJvmKeepsDiscoveryAndTheExactKingdomColor(GameTestHelper h){
        var level=h.getLevel();var file=level.getServer().getWorldPath(LevelResource.ROOT).resolve("atlas-restart.dat");
        if(System.getProperty("kingdoms.tests.persistence","write").equals("read")){var saved=HistoryFiles.read(file);var player=new Stage5Support.TestPlayer(level,"Atlas returning visitor");player.setUUID(saved.getUUID("player"));h.assertTrue(AtlasDiscovery.known(player).contains(saved.getUUID("village")) && AtlasGeometry.color(saved.getUUID("kingdom"))==saved.getInt("color"),"Remembered identity and exact color survive another JVM without fake discovery");h.succeed();return;}
        try(var f=new PersonalityGameTests.Villages(h)){var v=f.add("norman/ecclesiastique");var player=new Stage5Support.TestPlayer(level,"Atlas saved visitor");level.getChunkAt(v.getCenter());player.setPos(v.getCenter().getX(),v.getCenter().getY(),v.getCenter().getZ());h.assertTrue(AtlasDiscovery.known(player).contains(v.getId().uuid()),"Genuine proximity committed to separate discovery store");var saved=new CompoundTag();saved.putUUID("player",player.getUUID());saved.putUUID("village",v.getId().uuid());saved.putUUID("kingdom",A);saved.putInt("color",AtlasGeometry.color(A));HistoryFiles.write(file,saved);h.succeed();}
    }
}
