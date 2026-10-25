package com.millenairekingdoms.atlas.geography;

import com.mojang.logging.LogUtils;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Bounded surface observations near actual players. Opening/panning the atlas never scans the world. */
public final class GeographyService {
    public static final int RADIUS=48,COLUMNS_PER_PLAYER=64,COLUMNS_PER_INTERVAL=256,INTERVAL=20,MAX_SESSIONS=16;
    public static final long SOFT_INTERVAL_NANOS=10_000_000L;
    public static final int COAST_PROBE_DISTANCE=32,COASTAL_CANDIDATE=4; // transient only; never a persisted mask value
    public static final class Loading extends RuntimeException {private Loading(){super("Observed geography index is loading; retry later");}}
    public static final int MAX_PENDING_WRITES=4;
    private static final java.util.concurrent.atomic.AtomicInteger PENDING_WRITES=new java.util.concurrent.atomic.AtomicInteger();
    private static final ExecutorService MASK_IO=Executors.newSingleThreadExecutor(task->{var thread=new Thread(task,"Kingdoms geography writer");thread.setDaemon(true);return thread;});
    private static final AtomicBoolean COLD_LOADING=new AtomicBoolean();
    private static final ExecutorService INDEX_IO=Executors.newSingleThreadExecutor(task->{var thread=new Thread(task,"Kingdoms geography index reader");thread.setDaemon(true);return thread;});
    private record Owner(UUID player,String dimension){}
    private static final class Session {
        final GeographyStore direct;final CompletableFuture<GeographyStore> loading;CompletableFuture<Integer> saving;volatile String writer="";int cursor;long warned=Long.MIN_VALUE;
        Session(GeographyStore store){direct=store;loading=null;}
        Session(CompletableFuture<GeographyStore> future){direct=null;loading=future;}
        GeographyStore store(){if(saving!=null){if(!saving.isDone())throw new Loading();saving.getNow(0);saving=null;}if(direct!=null)return direct;if(!loading.isDone())throw new Loading();return loading.getNow(null);}
    }
    private static final class State {
        int rotation;long samplingSequence;
        final LinkedHashMap<Owner,Long> retry=new LinkedHashMap<>(16,.75f,true){
            @Override protected boolean removeEldestEntry(Map.Entry<Owner,Long> entry){return size()>MAX_SESSIONS;}
        };
        final LinkedHashMap<Owner,Session> sessions=new LinkedHashMap<>(16,.75f,true);
    }
    private static final Map<MinecraftServer,State> STATES=new WeakHashMap<>();
    private GeographyService(){}
    public static Path directory(ServerLevel level,UUID player){return DimensionType.getStorageFolder(level.dimension(),level.getServer().getWorldPath(LevelResource.ROOT)).resolve("data/millenairekingdoms/geography").resolve(player.toString());}
    private static Session session(ServerLevel level,UUID player){
        if(!level.getServer().isSameThread())throw new IllegalStateException("Observed geography requires server thread");
        var state=STATES.computeIfAbsent(level.getServer(),unused->new State());var owner=new Owner(player,level.dimension().location().toString());
        long now=level.getServer().getTickCount();var retry=state.retry.get(owner);if(retry!=null && now<retry)throw new IllegalStateException("Observed geography temporarily unavailable; original bytes preserved");
        try{
            var result=state.sessions.get(owner);
            if(result==null){
                var path=directory(level,player);
                if(!java.nio.file.Files.exists(path))result=new Session(new GeographyStore(path,player,owner.dimension()));
                else {
                    if(!COLD_LOADING.compareAndSet(false,true))throw new Loading();
                    // Own-file metadata I/O only; no level/server/player captured by the background reader.
                    String dimension=owner.dimension();UUID identity=owner.player();
                    result=new Session(CompletableFuture.supplyAsync(()->{try{return new GeographyStore(path,identity,dimension);}finally{COLD_LOADING.set(false);}},INDEX_IO));
                }
                // Stagger a recreated cursor: a bounded LRU must not repeatedly map only its first row with >16 observers.
                long seed=owner.player().getMostSignificantBits()^owner.player().getLeastSignificantBits()^(++state.samplingSequence*0x9e3779b97f4a7c15L);seed^=seed>>>33;seed*=0xff51afd7ed558ccdL;seed^=seed>>>33;
                result.cursor=(int)Math.floorMod(seed,(long)(RADIUS*2+1)*(RADIUS*2+1));
                state.sessions.put(owner,result);
                while(state.sessions.size()>MAX_SESSIONS){var iterator=state.sessions.entrySet().iterator();while(iterator.hasNext()){var entry=iterator.next();if((entry.getValue().loading==null || entry.getValue().loading.isDone()) && (entry.getValue().saving==null || entry.getValue().saving.isDone())){iterator.remove();break;}}}
            }
            result.store();state.retry.remove(owner);return result;
        }catch(Loading expected){throw expected;}
        catch(RuntimeException failure){state.retry.put(owner,now+1200);throw failure;}
    }
    public static GeographyStore store(ServerPlayer player){return session(player.serverLevel(),player.getUUID()).store();}
    public static boolean ready(ServerPlayer player){try{store(player);return true;}catch(Loading expected){return false;}}
    public static void clear(MinecraftServer server){
        var state=STATES.remove(server);if(state==null)return;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        for(var session:state.sessions.values())if(session.saving!=null)try{session.saving.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);}catch(Exception failure){com.mojang.logging.LogUtils.getLogger().warn("[Kingdoms] Geography shutdown acknowledgement unavailable; prior cache/evidence preserved",failure);}
    }
    public static int pendingWrites(){return PENDING_WRITES.get();}
    public static String lastWriteThread(ServerPlayer player){var state=STATES.get(player.serverLevel().getServer());if(state==null)return "";var session=state.sessions.get(new Owner(player.getUUID(),player.serverLevel().dimension().location().toString()));return session==null?"":session.writer;}
    private static boolean reserveWrite(){for(;;){int pending=PENDING_WRITES.get();if(pending>=MAX_PENDING_WRITES)return false;if(PENDING_WRITES.compareAndSet(pending,pending+1))return true;}}

    public static int activeSessions(MinecraftServer server){var state=STATES.get(server);return state==null?0:state.sessions.size();}
    public static void onTick(ServerTickEvent.Post event){
        var server=event.getServer();if(server.getTickCount()%INTERVAL!=0)return;
        observePlayers(server,server.getPlayerList().getPlayers());
    }
    /** Same round-robin sampler used by the live tick; callers are server-side only, never packet supplied. */
    public static int observePlayers(MinecraftServer server,List<ServerPlayer> players){
        if(!server.isSameThread())throw new IllegalStateException("Geography observations require server thread");if(players.isEmpty())return 0;
        var state=STATES.computeIfAbsent(server,unused->new State());int count=Math.min(players.size(),COLUMNS_PER_INTERVAL/COLUMNS_PER_PLAYER),processed=0,considered=0;long started=System.nanoTime();
        for(int n=0;n<count;n++){
            if(PENDING_WRITES.get()>=MAX_PENDING_WRITES || n>0 && System.nanoTime()-started>=SOFT_INTERVAL_NANOS)break;processed++;
            var player=players.get(Math.floorMod(state.rotation+n,players.size()));
            var identity=new Owner(player.getUUID(),player.serverLevel().dimension().location().toString());var retry=state.retry.get(identity);if(retry!=null && server.getTickCount()<retry)continue;
            try{considered+=captureAsync(player);}catch(Loading expected){/* One bounded background index reader; retry on a later observation round. */}catch(RuntimeException failure){
                // Geography failure never changes political, native, coin or shipment state.
                var owner=new Owner(player.getUUID(),player.serverLevel().dimension().location().toString());var session=state.sessions.get(owner);
                state.retry.put(owner,(long)server.getTickCount()+1200);
                if(session==null || session.warned==Long.MIN_VALUE || server.getTickCount()-session.warned>=1200){
                    if(session!=null)session.warned=server.getTickCount();LogUtils.getLogger().warn("[Kingdoms] Observed geography unavailable for {} in {}: {}",owner.player(),owner.dimension(),failure.getMessage());
                }
            }
        }
        state.rotation=Math.floorMod(state.rotation+processed,players.size());return considered;
    }
    /** Returns considered columns, including skipped unknown chunks. At most eight tile writes per invocation. */
    /** Direct synchronous validation entry point; live ticks use the same acquisition with bounded async persistence. */
    public static int capture(ServerPlayer player){return capture(player,false);}
    public static int captureAsync(ServerPlayer player){return capture(player,true);}
    private static int capture(ServerPlayer player,boolean asynchronous){
        var level=player.serverLevel();var current=level.getChunkSource().getChunkNow(player.getBlockX()>>4,player.getBlockZ()>>4);
        if(current==null || !current.hasPrimedHeightmap(Heightmap.Types.WORLD_SURFACE))return 0;
        // Underground players cannot discover the surface through the atlas. Can-see-sky is conservative under trees.
        if(!level.canSeeSky(player.blockPosition()))return 0;
        var session=session(level,player.getUUID());var store=session.store();int initialCursor=session.cursor;if(asynchronous && PENDING_WRITES.get()>=MAX_PENDING_WRITES)throw new Loading();var observed=new LinkedHashMap<GeographyTile.Position,byte[]>();int width=RADIUS*2+1,total=width*width;
        for(int n=0;n<COLUMNS_PER_PLAYER;n++){
            int cursor=session.cursor++%total,dx=cursor%width-RADIUS,dz=cursor/width-RADIUS;if(session.cursor==total)session.cursor=0;
            if(dx*dx+dz*dz>RADIUS*RADIUS)continue;
            int x=player.getBlockX()+dx,z=player.getBlockZ()+dz;if(Math.abs((long)x)>29999983 || Math.abs((long)z)>29999983)continue;
            int value=classifyLoadedColumn(level,x,z);if(value==GeographyTile.UNKNOWN)continue;
            var position=GeographyTile.Position.at(x,z);if(!observed.containsKey(position) && observed.size()>=GeographyStore.MAX_BATCH)continue;
            var cells=observed.computeIfAbsent(position,store::cells);GeographyTile.set(cells,x&31,z&31,value);
        }
        if(!asynchronous)store.commit(observed);
        else if(store.wouldChange(observed)){
            if(!reserveWrite()){session.cursor=initialCursor;throw new Loading();}
            try{session.saving=CompletableFuture.supplyAsync(()->{try{session.writer=Thread.currentThread().getName();return store.commit(observed);}finally{PENDING_WRITES.decrementAndGet();}},MASK_IO);}
            catch(RuntimeException rejected){PENDING_WRITES.decrementAndGet();throw rejected;}
        }
        return COLUMNS_PER_PLAYER;
    }
    /** Never creates a chunk or primes a heightmap. Ocean requires real surface water AND an ocean biome at sea level. */
    public static int classifyLoadedColumn(ServerLevel level,int x,int z){
        return classifyCoastal((px,pz)->rawLoadedColumn(level,px,pz),x,z);
    }
    /** A beach's submerged strip is sea only along a real continuous, loaded water line to an ocean seed. */
    public static int classifyCoastal(java.util.function.IntBinaryOperator source,int x,int z){
        int primary=source.applyAsInt(x,z);if(primary!=COASTAL_CANDIDATE)return primary;
        for(int direction=0;direction<4;direction++)for(int distance=1;distance<=COAST_PROBE_DISTANCE;distance++){
            int px=x+(direction==0?distance:direction==1?-distance:0),pz=z+(direction==2?distance:direction==3?-distance:0);int value=source.applyAsInt(px,pz);
            if(value==GeographyTile.OCEAN)return GeographyTile.OCEAN;
            if(value!=COASTAL_CANDIDATE)break; // Never cross land, a river/lake, height mismatch, or an unknown/unloaded column.
        }
        return GeographyTile.OTHER_WATER;
    }
    private static int rawLoadedColumn(ServerLevel level,int x,int z){
        var chunk=level.getChunkSource().getChunkNow(x>>4,z>>4);if(chunk==null || !chunk.hasPrimedHeightmap(Heightmap.Types.WORLD_SURFACE))return GeographyTile.UNKNOWN;
        int y=chunk.getHeight(Heightmap.Types.WORLD_SURFACE,x&15,z&15);if(y<level.getMinBuildHeight() || y>=level.getMaxBuildHeight())return GeographyTile.UNKNOWN;
        var position=new BlockPos(x,y,z);var block=chunk.getBlockState(position);if(block.isAir())return GeographyTile.UNKNOWN;
        if(!block.getFluidState().is(FluidTags.WATER) || block.blocksMotion())return GeographyTile.LAND;
        if(Math.abs((long)y+1-level.getSeaLevel())>2)return GeographyTile.OTHER_WATER;
        var biome=chunk.getNoiseBiome(QuartPos.fromBlock(x),QuartPos.fromBlock(level.getSeaLevel()),QuartPos.fromBlock(z));
        if(biome.is(BiomeTags.IS_RIVER))return GeographyTile.OTHER_WATER;
        if(biome.is(BiomeTags.IS_OCEAN))return GeographyTile.OCEAN;
        return biome.is(BiomeTags.IS_BEACH)?COASTAL_CANDIDATE:GeographyTile.OTHER_WATER;
    }
}
