package com.millenairekingdoms.test;

import com.millenairekingdoms.atlas.geography.*;
import com.millenairekingdoms.history.HistoryFiles;
import com.millenairekingdoms.network.ChronicleRequestPayload;
import com.mojang.authlib.GameProfile;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Own-cache fault fixtures plus an explicitly generated vanilla noise-world coast. Production never generates terrain. */
@PrefixGameTestTemplate(false)
public final class GeographyGameTests {
    private static final String NS="millenairekingdoms_geography_tests",DIM=NS+":ocean_probe";
    private static Path scratch(GameTestHelper h,String name){return h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("geography-fixtures").resolve(name+"-"+UUID.randomUUID());}
    private static byte[] mask(int value){var cells=new byte[GeographyTile.BYTES];GeographyTile.set(cells,0,0,value);return cells;}
    private static GeographyStore fresh(GameTestHelper h,String name){return new GeographyStore(scratch(h,name),UUID.randomUUID(),"minecraft:overworld");}
    private static void rejected(GameTestHelper h,Runnable action,String message){boolean failed=false;try{action.run();}catch(RuntimeException expected){failed=true;}h.assertTrue(failed,message);}
    private static CompoundTag page(GeographyStore.Page page){var tag=new CompoundTag();tag.putString("mode","GEOGRAPHY");tag.putString("dimension","minecraft:overworld");tag.putLong("watermark",page.watermark());tag.putLong("nextRevision",page.next());tag.putBoolean("more",page.more());tag.putBoolean("reset",page.reset());var list=new ListTag();for(var tile:page.tiles()){var row=new CompoundTag();row.putInt("x",tile.position().x());row.putInt("z",tile.position().z());row.putLong("revision",tile.revision());row.putByteArray("cells",tile.cells());list.add(row);}tag.put("geography",list);return tag;}
    private static CompoundTag read(Path file){try{return NbtIo.readCompressed(file,NbtAccounter.create(16L*1024*1024));}catch(Exception e){throw new IllegalStateException(e);}}
    private static CompoundTag firstMeta(Path root){var part=read(root.resolve("index.dat")).getList("shards",10).getCompound(0);return read(root.resolve("shard-"+part.getUUID("file")+".dat")).getList("tiles",10).getCompound(0);}
    private static void write(Path file,CompoundTag tag){try{Files.createDirectories(file.getParent());NbtIo.writeCompressed(tag,file);}catch(Exception e){throw new IllegalStateException(e);}}
    private static String digest(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(Exception e){throw new AssertionError(e);}}
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void packedObservedMaskUsesExactNegativeCoordinatesAndDefensiveCopies(GameTestHelper h){
        var bytes=mask(GeographyTile.OCEAN);GeographyTile.set(bytes,31,31,GeographyTile.OTHER_WATER);var tile=new GeographyTile(GeographyTile.Position.at(-1,-33),1,bytes);bytes[0]=0;var copy=tile.cells();copy[255]=0;
        h.assertTrue(tile.position().equals(new GeographyTile.Position(-1,-2)) && tile.position().blockX()==-32 && tile.position().blockZ()==-64,"Floor tile coordinates preserve the atlas world coordinate system at negative boundaries");
        h.assertTrue(tile.at(0,0)==GeographyTile.OCEAN && tile.at(31,31)==GeographyTile.OTHER_WATER && tile.at(1,1)==GeographyTile.UNKNOWN && tile.cells().length==256,"Two-bit masks preserve unknown/ocean/inland cells and cannot be mutated by callers");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void deltaPagingHandlesConcurrentObservationsWithoutRetainedWorldSnapshots(GameTestHelper h){
        var store=fresh(h,"pages");for(int n=0;n<130;n++)store.commit(Map.of(new GeographyTile.Position(n,0),mask(GeographyTile.LAND)));
        var client=new GeographySnapshot();var first=store.query(0,Long.MAX_VALUE);h.assertTrue(first.tiles().size()==64 && first.more() && first.watermark()==130,"Bounded first page fixes its revision watermark");client.accept(page(first));
        store.commit(Map.of(new GeographyTile.Position(100,0),mask(GeographyTile.OCEAN)));store.commit(Map.of(new GeographyTile.Position(130,0),mask(GeographyTile.LAND)));
        while(client.accept(page(store.query(client.after(),client.through())))){}h.assertTrue(client.after()==130 && client.size()==129,"A tile updated after the page watermark is deferred, not mixed with a different snapshot");
        while(client.accept(page(store.query(client.after(),client.through())))){}h.assertTrue(client.after()==132 && client.size()==131 && client.at(3200,0)==GeographyTile.OCEAN,"Next delta includes both the changed tile and new discovery without losing facts");
        h.assertTrue(store.hotSize()<=GeographyStore.HOT_TILES && store.query(999,Long.MAX_VALUE).reset(),"Hot cache remains bounded; a restored older backup requests an explicit client cache reset");
        var reset=store.query(999,Long.MAX_VALUE);client.accept(page(reset));h.assertTrue(client.size()==0 && client.after()==0,"Reset clears only the client geography cache and restarts observation download");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void unknownFutureIndexAndForeignOwnerDimensionPreserveExactBytes(GameTestHelper h)throws Exception{
        var root=scratch(h,"future-index");var owner=UUID.randomUUID();var store=new GeographyStore(root,owner,"minecraft:overworld");store.commit(Map.of(new GeographyTile.Position(0,0),mask(GeographyTile.OCEAN)));var file=root.resolve("index.dat");var original=Files.readAllBytes(file);var tag=read(file);tag.putInt("format",999);write(file,tag);var future=Files.readAllBytes(file);
        rejected(h,()->new GeographyStore(root,owner,"minecraft:overworld"),"Future geography format is not reset or guessed");h.assertTrue(Arrays.equals(future,Files.readAllBytes(file)),"Future index bytes remain untouched");Files.write(file,original);
        rejected(h,()->new GeographyStore(root,UUID.randomUUID(),"minecraft:overworld"),"A different player cannot read this cache");rejected(h,()->new GeographyStore(root,owner,"minecraft:the_nether"),"A different dimension cannot read this cache");h.assertTrue(Arrays.equals(original,Files.readAllBytes(file)),"Foreign-owner/dimension checks never rewrite source bytes");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void hotCacheCannotHideFutureTileBytesBeforeReplacement(GameTestHelper h)throws Exception{
        var root=scratch(h,"future-tile");var store=new GeographyStore(root,UUID.randomUUID(),"minecraft:overworld");var position=new GeographyTile.Position(0,0);store.commit(Map.of(position,mask(GeographyTile.LAND)));store.cells(position);
        var index=root.resolve("index.dat");var indexBytes=Files.readAllBytes(index);var meta=firstMeta(root);var file=root.resolve("tile-"+meta.getUUID("file")+".dat");var tag=read(file);tag.putInt("format",999);write(file,tag);var future=Files.readAllBytes(file);
        rejected(h,()->store.commit(Map.of(position,mask(GeographyTile.OCEAN))),"Replacement must re-read old bytes even when the old tile is hot");
        h.assertTrue(Arrays.equals(future,Files.readAllBytes(file)) && Arrays.equals(indexBytes,Files.readAllBytes(index)),"Future tile and original index remain byte-identical; no acknowledgement invented");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void memoryIndexCannotHideFutureShardBytesBeforeReplacement(GameTestHelper h)throws Exception{
        var root=scratch(h,"future-shard");var store=new GeographyStore(root,UUID.randomUUID(),"minecraft:overworld");var position=new GeographyTile.Position(0,0);store.commit(Map.of(position,mask(GeographyTile.LAND)));
        var index=root.resolve("index.dat");var indexBytes=Files.readAllBytes(index);var part=read(index).getList("shards",10).getCompound(0);var file=root.resolve("shard-"+part.getUUID("file")+".dat");var tag=read(file);tag.putInt("format",999);write(file,tag);var future=Files.readAllBytes(file);
        rejected(h,()->store.commit(Map.of(position,mask(GeographyTile.OCEAN))),"Replacing a shard must revalidate its original bytes before writing any tile");
        h.assertTrue(Arrays.equals(future,Files.readAllBytes(file)) && Arrays.equals(indexBytes,Files.readAllBytes(index)),"Future shard and root bytes are preserved exactly");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void adversarialShardCapacityIsBoundedWithoutEvictingAnyAcknowledgedObservations(GameTestHelper h)throws Exception{
        var root=scratch(h,"shard-bound");var store=new GeographyStore(root,UUID.randomUUID(),"minecraft:overworld");var positions=new ArrayList<GeographyTile.Position>();
        for(int x=0;positions.size()<513;x++){var position=new GeographyTile.Position(x,0);if(GeographyStore.shard(position)==0)positions.add(position);}
        for(int start=0;start<512;start+=8){var batch=new LinkedHashMap<GeographyTile.Position,byte[]>();for(int n=start;n<start+8;n++)batch.put(positions.get(n),mask(GeographyTile.LAND));store.commit(batch);}
        var before=Files.readAllBytes(root.resolve("index.dat"));rejected(h,()->store.commit(Map.of(positions.getLast(),mask(GeographyTile.OCEAN))),"An adversarial concentration stops at512 members per shard rather than growing a giant per-tick index");
        h.assertTrue(store.size()==512 && !store.knows(positions.getLast()) && Arrays.equals(before,Files.readAllBytes(root.resolve("index.dat"))),"Capacity refusal preserves all acknowledged masks/index bytes");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void sharedSamplerUsesGlobalColumnBudgetBoundedSessionsAndFairRotation(GameTestHelper h){
        var level=h.getLevel();var players=new ArrayList<net.minecraft.server.level.ServerPlayer>();var center=h.absolutePos(new BlockPos(2,1,2));
        for(int n=0;n<20;n++){var player=player(level,UUID.randomUUID());player.setPos(center.getX()+.5,256,center.getZ()+.5);players.add(player);}
        int total=0;for(int n=0;n<24;n++){int considered=GeographyService.observePlayers(level.getServer(),players);h.assertTrue(considered<=256,"Actual tick sampler never considers more than256 columns across all players in an interval");total+=considered;h.assertTrue(GeographyService.activeSessions(level.getServer())<=16,"Server metadata/hot tile sessions remain globally bounded to16 even with20 controlled profiles");}
        h.assertTrue(total>0,"Round-robin observation runs over controlled server-side player positions; no authentication claim");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",batch="geography_eviction",timeoutTicks=1200)
    public static void sessionEvictionWith21ObserversStillDiscoversNewSurfaceInsteadOfRestartingOneCorner(GameTestHelper h){
        var level=h.getLevel();var center=h.absolutePos(new BlockPos(2,1,2));for(int z=(center.getZ()>>4)-3;z<=(center.getZ()>>4)+3;z++)for(int x=(center.getX()>>4)-3;x<=(center.getX()>>4)+3;x++)level.getChunk(x,z);var players=new ArrayList<FakePlayer>();
        for(int n=0;n<21;n++){var observer=player(level,UUID.randomUUID());observer.setPos(center.getX()+.5,256,center.getZ()+.5);players.add(observer);}
        h.succeedWhen(()->{
            for(var observer:players)try{GeographyService.capture(observer);}catch(GeographyService.Loading expected){/* bounded background metadata read */}
            h.assertTrue(GeographyService.ready(players.getFirst()),"First observer's own acknowledged index is still loading");int known=0;var page=GeographyService.store(players.getFirst()).query(0,Long.MAX_VALUE);for(var tile:page.tiles())for(int z=0;z<32;z++)for(int x=0;x<32;x++)if(tile.at(x,z)!=GeographyTile.UNKNOWN)known++;
            h.assertTrue(known>64,"Evicted/recreated sessions must extend discovery beyond one64-column round; observed="+known);h.assertTrue(GeographyService.activeSessions(level.getServer())<=16,"Discovery progress must not defeat the global16-session memory cap");
            System.out.println("GEOGRAPHY_EVICTION observers=21 sessions="+GeographyService.activeSessions(level.getServer())+" first_observer_known_cells="+known);
        });
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void geographyWireAcceptsOnlyBoundedRevisionWindowsAndNoIdentityCoordinatesOrGameplayAuthority(GameTestHelper h){
        var request=ChronicleRequestPayload.geography(UUID.randomUUID(),81,123,456);var buffer=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),h.getLevel().registryAccess());
        try{ChronicleRequestPayload.CODEC.encode(buffer,request);h.assertTrue(ChronicleRequestPayload.CODEC.decode(buffer).equals(request) && com.millenairekingdoms.network.KingdomNetwork.VERSION.equals("8"),"Version8 encodes only bounded revision cursors/session/serial; dimensions and player identity are connection authoritative");}finally{buffer.release();}
        rejected(h,()->ChronicleRequestPayload.geography(UUID.randomUUID(),0,-1,Long.MAX_VALUE),"Negative revision window is refused");
        rejected(h,()->new ChronicleRequestPayload(UUID.randomUUID(),0,"GEOGRAPHY",Optional.of(UUID.randomUUID()),"",0,Long.MAX_VALUE,Optional.empty(),"",Optional.empty()),"Geography cannot request another kingdom/player's observations through extra IDs");
        rejected(h,()->new ChronicleRequestPayload(UUID.randomUUID(),0,"GEOGRAPHY",Optional.empty(),"",0,Long.MAX_VALUE,Optional.empty(),"scan 0,0",Optional.empty()),"No scan/search/coordinate command exists in geography requests");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void externalIndexChangesAndConcurrentWriterLocksCannotOverwriteCache(GameTestHelper h)throws Exception{
        var root=scratch(h,"locked");var owner=UUID.randomUUID();var store=new GeographyStore(root,owner,"minecraft:overworld");var pos=new GeographyTile.Position(0,0);store.commit(Map.of(pos,mask(GeographyTile.LAND)));var index=root.resolve("index.dat");var before=Files.readAllBytes(index);
        try(var channel=FileChannel.open(root.resolve("cache.lock"),StandardOpenOption.WRITE);var lock=channel.lock()){
            rejected(h,()->store.commit(Map.of(pos,mask(GeographyTile.OCEAN))),"A concurrent cache writer refuses the update without touching game state");
        }
        h.assertTrue(Arrays.equals(before,Files.readAllBytes(index)),"Contended writer leaves the acknowledged index intact");var external=new GeographyStore(root,owner,"minecraft:overworld");external.commit(Map.of(new GeographyTile.Position(1,0),mask(GeographyTile.OCEAN)));var changed=Files.readAllBytes(index);
        rejected(h,()->store.commit(Map.of(pos,mask(GeographyTile.OCEAN))),"A stale session does not overwrite another index revision");h.assertTrue(Arrays.equals(changed,Files.readAllBytes(index)),"External committed bytes remain preserved");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void immutableVersionIndexIgnoresUncommittedOrphansAndRefusesMissingRoot(GameTestHelper h)throws Exception{
        var root=scratch(h,"orphan");var owner=UUID.randomUUID();var store=new GeographyStore(root,owner,"minecraft:overworld");var pos=new GeographyTile.Position(0,0);store.commit(Map.of(pos,mask(GeographyTile.LAND)));var index=read(root.resolve("index.dat"));var source=root.resolve("tile-"+firstMeta(root).getUUID("file")+".dat");var orphan=root.resolve("tile-"+UUID.randomUUID()+".dat");Files.copy(source,orphan);var bytes=Files.readAllBytes(orphan);
        var reopened=new GeographyStore(root,owner,"minecraft:overworld");h.assertTrue(reopened.size()==1 && reopened.cells(pos)[0]==mask(GeographyTile.LAND)[0],"Crash-before-index orphan never becomes an observed tile");
        Files.delete(root.resolve("index.dat"));rejected(h,()->new GeographyStore(root,owner,"minecraft:overworld"),"Missing index with existing tile files refuses an implicit reset");h.assertTrue(Arrays.equals(bytes,Files.readAllBytes(orphan)),"Uncommitted evidence is preserved for explicit review");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void malformedClientPageIsAtomicAndLargePacketsOrIndexesAreRejected(GameTestHelper h){
        var store=fresh(h,"malformed");store.commit(Map.of(new GeographyTile.Position(0,0),mask(GeographyTile.LAND)));var client=new GeographySnapshot();client.accept(page(store.query(0,Long.MAX_VALUE)));var previous=client.tile(new GeographyTile.Position(0,0));
        var broken=page(new GeographyStore.Page(List.of(new GeographyTile(new GeographyTile.Position(1,0),2,mask(GeographyTile.OCEAN)),new GeographyTile(new GeographyTile.Position(2,0),3,mask(GeographyTile.LAND))),3,3,false,false));broken.getList("geography",10).getCompound(1).putByteArray("cells",new byte[255]);
        rejected(h,()->client.accept(broken),"Malformed last row cannot partially publish earlier rows");h.assertTrue(client.size()==1 && client.after()==1 && client.tile(new GeographyTile.Position(0,0)).equals(previous),"Client tile set and revision stay intact on a bad page");
        var tooMany=page(new GeographyStore.Page(List.of(),1,1,false,false));var list=new ListTag();for(int n=0;n<65;n++)list.add(new CompoundTag());tooMany.put("geography",list);rejected(h,()->client.accept(tooMany),"No more than64 tiles per network page");
        rejected(h,()->store.commit(java.util.stream.IntStream.range(0,9).boxed().collect(java.util.stream.Collectors.toMap(x->new GeographyTile.Position(x,0),x->mask(GeographyTile.LAND)))),"Write batches are capped independently of page limits");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void coastlineRasterMasksPoliticsOnlyOnObservedOceanAndKeepsUnknownAndRiversSeparate(GameTestHelper h){
        var cells=new byte[256];for(int z=0;z<32;z++)for(int x=0;x<32;x++)GeographyTile.set(cells,x,z,x<16?GeographyTile.LAND:GeographyTile.OCEAN);GeographyTile.set(cells,10,10,GeographyTile.OTHER_WATER);var tile=new GeographyTile(new GeographyTile.Position(0,0),1,cells);var tiles=Map.of(tile.position(),tile);var view=new GeographyRaster.View(16,16,4,128,128);var image=GeographyRaster.build(tiles,view);
        h.assertTrue(GeographyRaster.coastEdges(tiles,16,10)==1 && GeographyRaster.coastEdges(tiles,31,10)==0,"Only a real adjacent observed land/ocean edge draws a coast; unknown tile boundaries do not");
        int land=8+64*image.width(),ocean=100+64*image.width();h.assertTrue(image.ground()[land]==GeographyRaster.LAND_COLOR && image.ocean()[land]==0 && image.ocean()[ocean]==GeographyRaster.OCEAN_COLOR,"Land retains translucent political color while the opaque ocean overlay masks fills and borders");
        h.assertTrue(GeographyRaster.at(tiles,10,10)==GeographyTile.OTHER_WATER && GeographyRaster.at(tiles,100,100)==GeographyTile.UNKNOWN,"River/lake and unknown are separate classifications, never fabricated sea/land");
        for(double zoom:List.of(.01,.09,1.0,4.0)){var moved=new GeographyRaster.View(-1234.5,987.5,zoom,800,400);h.assertTrue(Math.abs(moved.worldX(moved.screenX(-456)) +456)<.000001 && Math.abs(moved.worldZ(moved.screenZ(1024))-1024)<.000001,"Pan/zoom share an invertible atlas coordinate transform at "+zoom);}
        var large=GeographyRaster.build(tiles,new GeographyRaster.View(16,16,.01,3840,2160));h.assertTrue(large.width()<=1024 && large.height()<=1024 && large.ground().length<=1024*1024,"Viewport pixels are bounded independently of large window size/world extent");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void submergedBeachRequiresContinuousRealOceanConnectionAndNeverCrossesRiverLakeLandOrUnknown(GameTestHelper h){
        int candidate=GeographyService.COASTAL_CANDIDATE;
        h.assertTrue(GeographyService.classifyCoastal((x,z)->z==0 && x==12?GeographyTile.OCEAN:candidate,0,0)==GeographyTile.OCEAN,"Loaded sea-level beach water connected to an ocean seed reaches the true shore");
        for(int barrier:List.of(GeographyTile.LAND,GeographyTile.OTHER_WATER,GeographyTile.UNKNOWN))h.assertTrue(GeographyService.classifyCoastal((x,z)->z==0 && x==1?barrier:z==0 && x==2?GeographyTile.OCEAN:candidate,0,0)==GeographyTile.OTHER_WATER,"A land barrier, river/lake, or unknown column blocks promotion of beach water: "+barrier);
        h.assertTrue(GeographyService.classifyCoastal((x,z)->x==0 && z==0?GeographyTile.OTHER_WATER:GeographyTile.OCEAN,0,0)==GeographyTile.OTHER_WATER,"River/lake/non-sea-level primary water never becomes ocean merely because ocean is nearby");
        var reads=new java.util.concurrent.atomic.AtomicInteger();int distant=GeographyService.classifyCoastal((x,z)->{reads.incrementAndGet();return x==33 && z==0?GeographyTile.OCEAN:candidate;},0,0);
        h.assertTrue(distant==GeographyTile.OTHER_WATER && reads.get()<=129,"Coastal supporting probes stop at32 blocks/four rays, no flood-fill/world scan/cache inference");
        var level=h.getLevel();var base=h.absolutePos(new BlockPos(2,1,2));var position=new BlockPos(base.getX(),63,base.getZ());var original=level.getBlockState(position);
        try{var slab=net.minecraft.world.level.block.Blocks.OAK_SLAB.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED,true);level.setBlock(position,slab,18);int before=level.getChunkSource().getLoadedChunksCount();
            h.assertTrue(GeographyService.classifyLoadedColumn(level,position.getX(),position.getZ())==GeographyTile.LAND && before==level.getChunkSource().getLoadedChunksCount(),"A waterlogged solid pier/slab is observed land, never open surface water; collision flag is cached without neighbor shape queries");
        }finally{level.setBlock(position,original,18);}h.succeed();
    }
    private static FakePlayer player(ServerLevel level,UUID id){return new FakePlayer(level,new GameProfile(id,"Ocean cartographer"));}
    private static ServerLevel noise(GameTestHelper h){var level=h.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(DIM)));h.assertTrue(level!=null,"Test-only vanilla noise dimension is loaded");return level;}
    /** This fixture alone explicitly generates a small coast patch. No production code calls getChunk or generator APIs. */
    private static BlockPos coast(GameTestHelper h,ServerLevel level){
        var ocean=level.findClosestBiome3d(b->b.is(BiomeTags.IS_OCEAN),new BlockPos(0,level.getSeaLevel(),0),4096,64,384);h.assertTrue(ocean!=null,"Vanilla seed contains an ocean biome within the bounded fixture search");
        var generator=level.getChunkSource().getGenerator();var random=level.getChunkSource().randomState();BlockPos a=ocean.getFirst(),b=null;int sea=level.getSeaLevel();
        // A beach/non-ocean biome can still be submerged. Seek actual terrain above sea level, not merely a biome boundary.
        int[][] directions={{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
        search:for(int distance=16;distance<=2048;distance+=16)for(var direction:directions){int x=a.getX()+direction[0]*distance,z=a.getZ()+direction[1]*distance;
            if(generator.getBaseHeight(x,z,Heightmap.Types.WORLD_SURFACE_WG,level,random)>sea){b=new BlockPos(x,sea,z);break search;}}
        h.assertTrue(b!=null,"Bounded fixture search finds actual solid terrain above sea level");
        for(int n=0;n<16;n++){var middle=new BlockPos((a.getX()+b.getX())/2,sea,(a.getZ()+b.getZ())/2);int height=generator.getBaseHeight(middle.getX(),middle.getZ(),Heightmap.Types.WORLD_SURFACE_WG,level,random);if(height<=sea)a=middle;else b=middle;}
        int cx=b.getX()>>4,cz=b.getZ()>>4;for(int z=cz-1;z<=cz+1;z++)for(int x=cx-1;x<=cx+1;x++)level.getChunk(x,z);
        var counts=new int[4];BlockPos coast=null;for(int z=(cz-1)*16;z<(cz+2)*16;z++)for(int x=(cx-1)*16;x<(cx+2)*16;x++){int value=GeographyService.classifyLoadedColumn(level,x,z);counts[value]++;if(value==GeographyTile.OCEAN){for(var direction:net.minecraft.core.Direction.Plane.HORIZONTAL){var neighbor=new BlockPos(x,sea,z).relative(direction);if(GeographyService.classifyLoadedColumn(level,neighbor.getX(),neighbor.getZ())==GeographyTile.LAND)coast=new BlockPos(x,sea,z);}}}
        System.out.println("GEOGRAPHY_VANILLA_FIXTURE ocean_biome="+ocean.getFirst()+" shore_candidate="+b+" explicit_full_chunk_requests=9 unknown_land_ocean_other="+Arrays.toString(counts));
        if(coast!=null)return coast;throw new IllegalStateException("Vanilla patch has no directly adjacent verified ocean-water/land cell; ocean="+ocean.getFirst()+" candidate="+b+" counts="+Arrays.toString(counts)+" chunks=9");
    }
    @GameTest(templateNamespace=NS,template="empty",batch="geography_actual_restart",timeoutTicks=12000)
    public static void actualVanillaOceanCoastIsObservedWithoutChunkLoadsAndSurvivesSeparateJvm(GameTestHelper h)throws Exception{
        var level=noise(h);var marker=h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("observed-ocean-restart.dat");boolean read="read".equals(System.getProperty("kingdoms.tests.persistence"));
        if(read){
            var saved=HistoryFiles.read(marker);var player=player(level,saved.getUUID("player"));h.succeedWhen(()->{h.assertTrue(GeographyService.ready(player),"Bounded background index reader is still loading");int before=level.getChunkSource().getLoadedChunksCount(),forced=level.getForcedChunks().size();var store=GeographyService.store(player);var page=store.query(0,Long.MAX_VALUE);var snapshot=new GeographySnapshot();snapshot.accept(page(page));
            var tile=store.cells(GeographyTile.Position.at(saved.getInt("x"),saved.getInt("z")));h.assertTrue(digest(tile).equals(saved.getString("maskSha256")) && store.revision()==saved.getLong("revision") && snapshot.at(saved.getInt("x"),saved.getInt("z"))==GeographyTile.OCEAN,"Separate JVM reloads the exact acknowledged real coast mask/revision");
            h.assertTrue(before==level.getChunkSource().getLoadedChunksCount() && forced==level.getForcedChunks().size(),"Disk cache/network query reopens without loading or forcing any terrain");System.out.println("GEOGRAPHY_REAL_COAST_REOPEN dimension="+DIM+" ocean="+saved.getInt("x")+","+saved.getInt("z")+" mask_sha256="+saved.getString("maskSha256")+" loaded_before="+before+" loaded_after="+level.getChunkSource().getLoadedChunksCount());});return;
        }
        var coast=coast(h,level);var player=player(level,UUID.randomUUID());player.setPos(coast.getX()+.5,256,coast.getZ()+.5);int before=level.getChunkSource().getLoadedChunksCount(),forced=level.getForcedChunks().size();long started=System.nanoTime(),worst=0;
        for(int n=0;n<160;n++){long one=System.nanoTime();int considered=GeographyService.capture(player);worst=Math.max(worst,System.nanoTime()-one);h.assertTrue(considered<=64,"Per-invocation sample budget remains64 columns");}
        var store=GeographyService.store(player);var snapshot=new GeographySnapshot();snapshot.accept(page(store.query(0,Long.MAX_VALUE)));var samples=new HashMap<GeographyTile.Position,GeographyTile>();snapshot.tiles().forEach(t->samples.put(t.position(),t));
        h.assertTrue(snapshot.at(coast.getX(),coast.getZ())==GeographyTile.OCEAN && GeographyRaster.coastEdges(samples,coast.getX(),coast.getZ())!=0,"Actual generated water+ocean-biome+sea-level coast becomes the same-world-coordinate ocean/coast render mask");
        int observedAfter=level.getChunkSource().getLoadedChunksCount();h.assertTrue(before==observedAfter && forced==level.getForcedChunks().size(),"Production observation and cached query never add loaded chunks or forced tickets");
        int unknownX=(coast.getX()>>4)*16+1024;h.assertTrue(GeographyService.classifyLoadedColumn(level,unknownX,coast.getZ())==GeographyTile.UNKNOWN && snapshot.at(unknownX,coast.getZ())==GeographyTile.UNKNOWN,"Unvisited/unloaded terrain stays unknown");
        var other=player(level,UUID.randomUUID());other.setPos(player.position());h.assertTrue(GeographyService.store(other).size()==0 && !Files.exists(GeographyService.directory(level,other.getUUID())),"Another player at the same coordinates receives no geography without personal observations");
        var otherDimension=player(h.getLevel(),player.getUUID());h.assertTrue(GeographyService.store(otherDimension).size()==0,"Same UUID in another dimension has a separate empty geography cache");
        var request=new ChronicleRequestPayload(UUID.randomUUID(),1,"GEOGRAPHY",Optional.empty(),"",0,Long.MAX_VALUE,Optional.empty(),"",Optional.empty());int queryBefore=level.getChunkSource().getLoadedChunksCount();var wire=GeographyView.build(player,request);int queryAfter=level.getChunkSource().getLoadedChunksCount();h.assertTrue(queryBefore==queryAfter,"Connection-authoritative geography response never loads terrain");
        int encodedBytes;var packetBuffer=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),level.registryAccess());
        try{com.millenairekingdoms.network.ChronicleViewPayload.CODEC.encode(packetBuffer,new com.millenairekingdoms.network.ChronicleViewPayload(wire));encodedBytes=packetBuffer.readableBytes();h.assertTrue(com.millenairekingdoms.network.ChronicleViewPayload.CODEC.decode(packetBuffer).view().equals(wire),"The real per-player geography response round-trips the production codec without losing masks/revisions");}finally{packetBuffer.release();}
        h.assertTrue(wire.getList("geography",10).size()<=64 && encodedBytes<65536 && wire.sizeInBytes()<65536,"Serialized payload bytes and accounted NBT are independently bounded; no raw chunk/seed payload");
        // A diagnostic CPU raster from actual observed masks, explicitly NOT a graphical Minecraft screenshot.
        var raster=GeographyRaster.build(samples,new GeographyRaster.View(coast.getX()+.5,coast.getZ()+.5,4,384,256));var picture=new java.awt.image.BufferedImage(raster.width(),raster.height(),java.awt.image.BufferedImage.TYPE_INT_ARGB);for(int z=0;z<raster.height();z++)for(int x=0;x<raster.width();x++){int i=x+z*raster.width(),color=raster.ocean()[i]!=0?raster.ocean()[i]:raster.ground()[i]!=0?raster.ground()[i]:0xFFCEC29E;picture.setRGB(x,z,color);}javax.imageio.ImageIO.write(picture,"PNG",marker.resolveSibling("observed-ocean-coast-model.png").toFile());
        var saved=new CompoundTag();saved.putUUID("player",player.getUUID());saved.putInt("x",coast.getX());saved.putInt("z",coast.getZ());saved.putLong("revision",store.revision());saved.putString("maskSha256",digest(store.cells(GeographyTile.Position.at(coast.getX(),coast.getZ()))));HistoryFiles.write(marker,saved);
        System.out.println("GEOGRAPHY_REAL_COAST_WRITE dimension="+DIM+" ocean="+coast.getX()+","+coast.getZ()+" mask_sha256="+saved.getString("maskSha256")+" calls=160 considered_max=64 tiles="+store.size()+" payload_wire_bytes="+encodedBytes+" accounted_nbt_bytes="+wire.sizeInBytes()+" observation_loaded_before="+before+" observation_loaded_after="+observedAfter+" query_loaded_before="+queryBefore+" query_loaded_after="+queryAfter+" fixture_loaded_final="+level.getChunkSource().getLoadedChunksCount()+" forced_before="+forced+" forced_after="+level.getForcedChunks().size()+" observation_ms="+(System.nanoTime()-started)/1_000_000.0+" worst_call_ms="+worst/1_000_000.0+" graphics_verified=false");h.succeed();
    }
}
