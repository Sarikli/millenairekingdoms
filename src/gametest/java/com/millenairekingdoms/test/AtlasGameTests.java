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

/** Settlement presentation identities, privacy and persistence. No graphical-client claim. */
@PrefixGameTestTemplate(false)
public final class AtlasGameTests {
    private static final String NS="millenairekingdoms_atlas_tests";
    private static final UUID A=new UUID(90,1),B=new UUID(90,2);
    @GameTest(templateNamespace=NS,template="empty")
    public static void persistentColorsDoNotChangeWhenUnrelatedKingdomsAppear(GameTestHelper h)throws Exception{
        var file=HistoryHooks.directory(h.getLevel()).resolve("color-test-"+UUID.randomUUID()+".dat");var colors=new AtlasColors(file);colors.ensure(List.of(A,B));int a=colors.color(A),b=colors.color(B);var bytes=Files.readAllBytes(file);
        colors.ensure(List.of(B,A));h.assertTrue(Arrays.equals(bytes,Files.readAllBytes(file)),"Opening an unchanged atlas performs no color rewrite");
        colors.ensure(List.of(UUID.randomUUID()));var reopen=new AtlasColors(file);h.assertTrue(reopen.color(A)==a && reopen.color(B)==b && a!=b && reopen.color(null)==AtlasColors.NEUTRAL,"Unrelated villages, allocation order and reopened color store never recolor established identities");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty")
    public static void collidingSeedColorsReceiveStableDistinguishableAssignments(GameTestHelper h){
        var seen=new HashMap<Integer,UUID>();UUID first=null,second=null;for(int n=1;n<1000;n++){var id=new UUID(93,n);var prior=seen.putIfAbsent(AtlasColors.seedColor(id),id);if(prior!=null){first=prior;second=id;break;}}
        h.assertTrue(first!=null,"Actual deterministic seed collision is reproduced");var file=HistoryHooks.directory(h.getLevel()).resolve("color-collision-"+UUID.randomUUID()+".dat");var colors=new AtlasColors(file);colors.ensure(List.of(first,second));
        h.assertTrue(colors.color(first)!=colors.color(second) && new AtlasColors(file).color(second)==colors.color(second),"A collision receives another durable color without changing the first identity");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty")
    public static void unknownColorFormatsRemainByteExact(GameTestHelper h)throws Exception{
        var file=HistoryHooks.directory(h.getLevel()).resolve("color-future-"+UUID.randomUUID()+".dat");var tag=new CompoundTag();tag.putInt("format",99);tag.put("colors",new ListTag());HistoryFiles.write(file,tag);var bytes=Files.readAllBytes(file);
        HistoryGameTests.refused(h,()->new AtlasColors(file));h.assertTrue(Arrays.equals(bytes,Files.readAllBytes(file)),"Unknown presentation formats are refused without resetting ownership, history or color bytes");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty")
    public static void labelsNeverOverlapReservedMarkersOrOtherLabels(GameTestHelper h){
        var labels=new AtlasLabels();labels.reserve(new AtlasLabels.Box(0,0,20,20));labels.reserve(new AtlasLabels.Box(15,15,35,35));
        h.assertTrue(!labels.add(new AtlasLabels.Box(30,30,60,50)) && labels.add(new AtlasLabels.Box(36,30,100,50)) && !labels.add(new AtlasLabels.Box(90,40,150,65)) && labels.add(new AtlasLabels.Box(-100,-30,-10,-1)),"Overlapping marker reservations, neighboring labels and negative viewport coordinates are handled without hiding markers");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty")
    public static void inconsistentPoliticalIdentitiesCannotReachTheRenderer(GameTestHelper h){
        var page=AtlasSyncGameTests.page(1,1,1,false);var site=page.getList("settlements",10).getCompound(0);site.putUUID("kingdom",A);site.putString("kingdomName","One realm");
        var realm=new CompoundTag();realm.putUUID("id",A);realm.putString("name","One realm");realm.putInt("color",0xFF22AAAA);page.getList("kingdoms",10).add(realm);
        HistoryGameTests.refused(h,()->new AtlasSnapshotAssembler().accept(page,null));site.putInt("politicalColor",0xFF22AAAA);h.assertTrue(new AtlasSnapshotAssembler().accept(page,null)!=null,"Consistent authoritative marker identity is accepted");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty")
    public static void actualPackagedAtlasHasNoGeometryTasksOrTerritorialRenderer(GameTestHelper h)throws Exception{
        var type=com.millenairekingdoms.MillenaireKingdoms.class;
        h.assertTrue(type.getResource("/com/millenairekingdoms/atlas/AtlasGeometry.class")==null,"The release JAR contains no influence/Voronoi geometry engine");
        try(var input=type.getResourceAsStream("/com/millenairekingdoms/client/AtlasScreen.class")){String bytes=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.ISO_8859_1);h.assertTrue(!bytes.contains("CompletableFuture") && !bytes.contains("AtlasGeometry") && !bytes.contains("VertexConsumer") && bytes.contains("politicalColor") && bytes.contains("kingdomName"),"Actual client code publishes metadata directly and draws shared political marker/label identity");}h.succeed();
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
    @GameTest(templateNamespace=NS,template="empty",batch="atlas_restart",timeoutTicks=1200)
    public static void separateJvmKeepsDiscoveryAndTheExactKingdomColor(GameTestHelper h){
        var level=h.getLevel();var file=level.getServer().getWorldPath(LevelResource.ROOT).resolve("atlas-restart.dat");
        if(System.getProperty("kingdoms.tests.persistence","write").equals("read")){var saved=HistoryFiles.read(file);var player=new Stage5Support.TestPlayer(level,"Atlas returning visitor");player.setUUID(saved.getUUID("player"));h.assertTrue(AtlasDiscovery.known(player).contains(saved.getUUID("village")) && new AtlasColors(file.resolveSibling("saved-atlas-colors.dat")).color(saved.getUUID("kingdom"))==saved.getInt("color"),"Remembered identity and exact color survive another JVM without fake discovery");h.succeed();return;}
        try(var f=new PersonalityGameTests.Villages(h)){var v=f.add("norman/ecclesiastique");var player=new Stage5Support.TestPlayer(level,"Atlas saved visitor");level.getChunkAt(v.getCenter());player.setPos(v.getCenter().getX(),v.getCenter().getY(),v.getCenter().getZ());h.assertTrue(AtlasDiscovery.known(player).contains(v.getId().uuid()),"Genuine proximity committed to separate discovery store");var saved=new CompoundTag();saved.putUUID("player",player.getUUID());saved.putUUID("village",v.getId().uuid());saved.putUUID("kingdom",A);var colors=new AtlasColors(file.resolveSibling("saved-atlas-colors.dat"));colors.ensure(List.of(A,B));saved.putInt("color",colors.color(A));HistoryFiles.write(file,saved);h.succeed();}
    }
}
