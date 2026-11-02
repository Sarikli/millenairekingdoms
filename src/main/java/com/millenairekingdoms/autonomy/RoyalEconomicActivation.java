package com.millenairekingdoms.autonomy;

import com.millenairekingdoms.economy.RoyalNpcDecisionStore;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.politics.PoliticsService;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.millenaire.culture.ModCultures;
import org.millenaire.village.Village;

/** One server-wide, expiring economic lease. Disk status scans precede all tickets; native activation remains native. */
public final class RoyalEconomicActivation {
    // Use the actual vanilla generation-dependency radius/status for a level31 ticking ticket.
    private static final int GENERATION_HALO = ChunkLevel.RADIUS_AROUND_FULL_CHUNK + 2, MAX_PREFLIGHT = 16384, MAX_PENDING_SCANS = 16, MAX_VERIFIED = 32768;
    private static final TicketType<UUID> TICKET = TicketType.create("kingdoms_economic_work", UUID::compareTo, 100);
    private static final Map<MinecraftServer, Lease> ACTIVE = new WeakHashMap<>();
    private static final Map<ServerLevel, State> STATES = new WeakHashMap<>();
    // Released leases must not free IO capacity until their actual immutable scans finish.
    private static final Map<MinecraftServer,List<CompletableFuture<ChunkStatus>>> PENDING = new WeakHashMap<>();
    private record Request(ServerLevel level, UUID kingdom) {}
    private static final Map<MinecraftServer,LinkedHashSet<Request>> QUEUED = new WeakHashMap<>();
    public record Report(UUID kingdom, String phase, int ownedTickets, int submittedStatusChecks, int pendingDiskJobs, long elapsedNanos, String reason) {}
    private static final class State {
        final Map<UUID, Long> retry = new HashMap<>();
        final Map<UUID, Integer> supplyCursor = new HashMap<>();
        final Map<ChunkPos,ChunkStatus> verified = new LinkedHashMap<>();
        Report report = new Report(null,"IDLE",0,0,0,0,"No economic lease requested");
    }
    private enum Phase { VERIFYING, LOADING, WORKING }
    private static final class Lease {
        final ServerLevel level;
        final UUID kingdom, capital, owner = UUID.randomUUID();
        final List<UUID> villages;
        final List<ChunkPos> chunks, preflight;
        final Map<ChunkPos, ChunkStatus> required;
        final Map<ChunkPos, CompletableFuture<ChunkStatus>> scans = new LinkedHashMap<>();
        final List<ChunkPos> owned = new ArrayList<>();
        final long started;
        long workingUntil;
        int scanCursor, ticketCursor;
        Phase phase = Phase.VERIFYING;
        Lease(ServerLevel level, UUID kingdom, List<UUID> villages, List<ChunkPos> chunks, Map<ChunkPos,ChunkStatus> required) {
            this.level=level;this.kingdom=kingdom;this.villages=List.copyOf(villages);this.chunks=List.copyOf(chunks);
            this.preflight=List.copyOf(required.keySet());this.required=Map.copyOf(required);this.started=level.getGameTime();
            this.capital=KingdomSavedData.get(level).kingdom(kingdom).capital();
        }
    }
    private RoyalEconomicActivation() {}
    public static Report report(ServerLevel level) { return STATES.computeIfAbsent(level,l->new State()).report; }
    public static boolean ownsLease(ServerLevel level) { var lease=ACTIVE.get(level.getServer());return lease!=null && lease.level==level; }

    /** Only the ordinary NPC scheduler submits work; this does not authorize money, materials or a commission. */
    public static void offer(ServerLevel level, UUID kingdom) {
        thread(level);if(!enabled())return;
        var server=level.getServer();var active=ACTIVE.get(server);
        if(active!=null && active.level==level && active.kingdom.equals(kingdom))return;
        var queue=QUEUED.computeIfAbsent(server,key->new LinkedHashSet<>());
        if(queue.size()<256)queue.add(new Request(level,kingdom));
        // The economic scheduler often inspects every kingdom in one pass. Its cursor alone cannot make leases fair:
        // the first sorted kingdom would reacquire the slot. Retain other requests in FIFO order across work windows.
        for(int n=0;n<2 && !queue.isEmpty() && !ACTIVE.containsKey(server);n++) {
            var iterator=queue.iterator();var request=iterator.next();iterator.remove();start(request.level(),request.kingdom());
        }
    }

    private static void start(ServerLevel level, UUID kingdom) {
        if (!enabled() || ACTIVE.containsKey(level.getServer())) return;
        var state=STATES.computeIfAbsent(level,l->new State());long now=level.getGameTime();
        if(now<state.retry.getOrDefault(kingdom,0L))return;
        var data=KingdomSavedData.get(level);var k=data.kingdom(kingdom);
        if(k==null || !k.active() || k.fallen() || !data.npcGoverned(kingdom))return;
        var portfolio=new RoyalStrategyStore(level).portfolio(kingdom);
        var villageIds=new LinkedHashSet<UUID>();villageIds.add(k.capital());
        boolean work=false;
        for(var plan:List.of(Optional.ofNullable(portfolio.development()),Optional.ofNullable(portfolio.logistics()))) {
            if(plan.isEmpty())continue;var p=plan.get();
            if(p.id()!=null && p.status()!=RoyalStrategyStore.Status.IDLE && p.status()!=RoyalStrategyStore.Status.INVALIDATED){villageIds.add(p.target());work=true;}
        }
        if(!work)return;
        boolean knownSource=false;
        var history=RoyalNpcDecisionStore.directory(level);
        if(Files.exists(history.resolve("decisions.dat"))) {
            var logistics=portfolio.logistics();
            if(logistics!=null && logistics.id()!=null) {
                var decision=new RoyalNpcDecisionStore(history).load(logistics.id());
                if(decision!=null && decision.choice().kind()==RoyalNpcDecisionStore.Kind.SHIPMENT) {
                    villageIds.add(decision.choice().source());villageIds.add(decision.choice().target());knownSource=true;
                }
            }
        }
        // A linked shipment already names its real source. Do not spend another whole village's chunk budget
        // on a speculative source. Otherwise rotate one additional source only when it fits the same hard bound.
        if(!knownSource) {
            var sources=data.members(kingdom).stream().filter(id->!villageIds.contains(id)).sorted().toList();
            if(!sources.isEmpty()) {
                int cursor=state.supplyCursor.getOrDefault(kingdom,0);var candidate=sources.get(Math.floorMod(cursor,sources.size()));
                state.supplyCursor.put(kingdom,cursor+1);var proposed=new LinkedHashSet<ChunkPos>();
                for(var id:villageIds){var v=PoliticsService.village(level,id);if(v!=null)proposed.addAll(v.computeVillageChunks());}
                var v=PoliticsService.village(level,candidate);if(v!=null)proposed.addAll(v.computeVillageChunks());
                var type=v==null?null:ModCultures.getVillageType(v.getVillageTypeId());
                if(v!=null && v.getTownhall()!=null && v.getOwnerUUID()==null && type!=null && !type.playerControlled() && !type.loneBuilding()
                        && proposed.size()<=RoyalNpcConfig.BACKGROUND_MAX_CHUNKS.get())villageIds.add(candidate);
            }
        }
        var chunks=new LinkedHashSet<ChunkPos>();var check=new LinkedHashSet<ChunkPos>();
        for(var id:villageIds) {
            var village=PoliticsService.village(level,id);
            if(village==null || !kingdom.equals(data.owner(id)) || village.getOwnerUUID()!=null || village.getTownhall()==null)return;
            var type=ModCultures.getVillageType(village.getVillageTypeId());if(type==null || type.playerControlled() || type.loneBuilding())return;
            // Match native working bounds, without setting forceActive (which would create native persistent force tickets).
            chunks.addAll(village.computeVillageChunks());
            int radius=Math.max(32,type.radius());var center=village.getCenter();
            int minX=(center.getX()-radius)>>4,maxX=(center.getX()+radius)>>4;
            int minZ=(center.getZ()-radius)>>4,maxZ=(center.getZ()+radius)>>4;
            if((long)(maxX-minX+1)*(maxZ-minZ+1)>4096){defer(level,kingdom,"Native search area exceeds economic verification bound");return;}
            for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++)check.add(new ChunkPos(x,z));
            if(chunks.size()>RoyalNpcConfig.BACKGROUND_MAX_CHUNKS.get()){defer(level,kingdom,"Native working region exceeds configured economic chunk budget");return;}
        }
        // Verify the ticket-propagation halo as well as native placement/search bounds before requesting any chunk.
        var required=new LinkedHashMap<ChunkPos,ChunkStatus>();
        for(var pos:check)required.put(pos,ChunkStatus.FULL);
        for(var pos:chunks)for(int x=-GENERATION_HALO;x<=GENERATION_HALO;x++)for(int z=-GENERATION_HALO;z<=GENERATION_HALO;z++) {
            var status=ChunkLevel.generationStatus(31+Math.max(Math.abs(x),Math.abs(z)));
            if(status!=null)required.merge(new ChunkPos(pos.x+x,pos.z+z),status,ChunkStatus::max);
        }
        check.addAll(required.keySet());
        if(check.size()>MAX_PREFLIGHT){defer(level,kingdom,"Economic preflight exceeds bounded chunk-status records");return;}
        for(var pos:check)if(!level.getWorldBorder().isWithinBounds(pos.getWorldPosition()))return;
        var lease=new Lease(level,kingdom,new ArrayList<>(villageIds),new ArrayList<>(chunks),required);
        if(militaryBusy(lease)){defer(level,kingdom,"Military operation has priority over economic activation");return;}
        ACTIVE.put(level.getServer(),lease);publish(lease,0,"Verifying previously generated native work/search regions");
    }

    public static void onTick(LevelTickEvent.Post event) {
        if(!(event.getLevel() instanceof ServerLevel level))return;
        var lease=ACTIVE.get(level.getServer());if(lease==null || lease.level!=level)return;
        long start=System.nanoTime();
        try {
            if(!enabled() || !validAuthority(lease) || militaryBusy(lease)){release(lease,"Economic lease released for configuration, authority or military priority");return;}
            long now=level.getGameTime();
            if(lease.phase!=Phase.WORKING && now-lease.started>12000){release(lease,"Bounded activation timed out; unpaid work and domain receipts retained");return;}
            int budget=RoyalNpcConfig.BACKGROUND_REQUESTS.get();
            if(lease.phase==Phase.VERIFYING) {
                var state=STATES.get(level);
                var pendingJobs=PENDING.computeIfAbsent(level.getServer(),server->new ArrayList<>());
                pendingJobs.removeIf(CompletableFuture::isDone);
                for(int n=0;n<budget && lease.scanCursor<lease.preflight.size();n++) {
                    var pos=lease.preflight.get(lease.scanCursor++);
                    net.minecraft.world.level.chunk.ChunkAccess loaded=level.getChunkSource().getChunkNow(pos.x,pos.z);
                    // Native generation may retain a valid unsaved protochunk after nearby FULL chunks unload.
                    // This accessor only inspects an existing holder (no future wait, ticket or generation request).
                    if(loaded==null && level.getChunkSource().getChunkForLighting(pos.x,pos.z) instanceof net.minecraft.world.level.chunk.ChunkAccess existing)loaded=existing;
                    var cached=state.verified.get(pos);
                    if(loaded!=null && loaded.getPersistedStatus().isOrAfter(lease.required.get(pos)))lease.scans.put(pos,CompletableFuture.completedFuture(loaded.getPersistedStatus()));
                    else if(cached!=null && cached.isOrAfter(lease.required.get(pos)))lease.scans.put(pos,CompletableFuture.completedFuture(cached));
                    else {
                        if(pendingJobs.size()>=MAX_PENDING_SCANS){lease.scanCursor--;break;}
                        var scan=scanStatus(level,pos);pendingJobs.add(scan);lease.scans.put(pos,scan);
                    }
                }
                for(var item:lease.scans.entrySet())if(item.getValue().isDone()) {
                    var status=item.getValue().getNow(null);
                    if(status==null || !status.isOrAfter(lease.required.get(item.getKey()))) {
                        release(lease,"Previously generated FULL terrain unavailable or dependency incomplete at "+item.getKey()+"; required="+lease.required.get(item.getKey())+", observed="+status+"; no economic chunk ticket was created");return;
                    }
                }
                if(lease.scanCursor==lease.preflight.size() && lease.scans.values().stream().allMatch(CompletableFuture::isDone)) {
                    for(var item:lease.scans.entrySet())state.verified.put(item.getKey(),item.getValue().getNow(null));
                    while(state.verified.size()>MAX_VERIFIED)state.verified.remove(state.verified.keySet().iterator().next());
                    lease.phase=Phase.LOADING;
                }
            }
            if(lease.phase==Phase.LOADING) {
                for(var id:lease.villages){var v=PoliticsService.village(level,id);if(v!=null && !v.isActive())v.markTransientlyLoaded(now+2);}
                for(int n=0;n<budget && lease.ticketCursor<lease.chunks.size();n++) {
                    var pos=lease.chunks.get(lease.ticketCursor++);
                    level.getChunkSource().addRegionTicket(TICKET,pos,2,lease.owner,true);lease.owned.add(pos);
                }
                if(lease.ticketCursor==lease.chunks.size() && lease.chunks.stream().allMatch(p->level.getChunkSource().getChunkNow(p.x,p.z)!=null && level.areEntitiesLoaded(p.toLong()))
                        && lease.villages.stream().allMatch(id->level.isPositionEntityTicking(PoliticsService.village(level,id).getCenter()))) {
                    lease.phase=Phase.WORKING;lease.workingUntil=now+RoyalNpcConfig.BACKGROUND_LEASE_TICKS.get();
                }
            }
            if(lease.phase==Phase.WORKING && now>=lease.workingUntil){release(lease,"Economic work window completed; persistent work resumes on a later fair visit");return;}
            // A transient region ticket makes chunks entity-ticking, but does not appear in NeoForge's
            // persistent forced-chunk registry. With zero players ServerLevel otherwise stops its entire
            // entity/block-entity loop after 300 ticks: loaded native workers would never run a goal.
            // Keep the normal loop awake only during an authorized, fully loaded, bounded work window.
            // Do not register persistent forced chunks or directly tick entities ourselves.
            if(lease.phase==Phase.WORKING)level.resetEmptyTime();
            if(now%20==0)for(var pos:lease.owned)level.getChunkSource().addRegionTicket(TICKET,pos,2,lease.owner,true);
            publish(lease,System.nanoTime()-start,"Native activation/builders and existing physical domain services retain execution authority");
        } catch(RuntimeException failure) {
            release(lease,"Economic activation deferred: "+failure.getMessage());
            com.mojang.logging.LogUtils.getLogger().warn("[Kingdoms] Economic activation retained work for {}: {}",lease.kingdom,failure.getMessage());
        }
    }

    /** Async work only reads an immutable chunk-status scan result, never a world/entity/inventory. */
    private static CompletableFuture<ChunkStatus> scanStatus(ServerLevel level,ChunkPos pos) {
        var collector=new CollectFields(new FieldSelector(StringTag.TYPE,"Status"),new FieldSelector(IntTag.TYPE,"xPos"),
                new FieldSelector(IntTag.TYPE,"zPos"),new FieldSelector(IntTag.TYPE,"DataVersion"));
        return level.getChunkSource().chunkScanner().scanChunk(pos,collector).thenApply(ignored->{
            if(!(collector.getResult() instanceof CompoundTag tag))return null;
            if(collector.getMissingFieldCount()!=0 || tag.getInt("xPos")!=pos.x || tag.getInt("zPos")!=pos.z || tag.getInt("DataVersion")<=0
                    || tag.getInt("DataVersion")>net.minecraft.SharedConstants.getCurrentVersion().getDataVersion().getVersion())
                throw new IllegalStateException("Chunk status scan invalid at "+pos+" missing="+collector.getMissingFieldCount()+" selected="+tag);
            return ChunkStatus.byName(tag.getString("Status"));
        });
    }
    private static boolean validAuthority(Lease lease) {
        var data=KingdomSavedData.get(lease.level);var k=data.kingdom(lease.kingdom);
        return k!=null && k.capital().equals(lease.capital) && k.active() && !k.fallen() && data.npcGoverned(k.id()) && lease.villages.stream()
                .allMatch(id->{var v=PoliticsService.village(lease.level,id);return v!=null && k.id().equals(data.owner(id)) && v.getOwnerUUID()==null;});
    }
    private static boolean militaryBusy(Lease lease) {
        var data=KingdomSavedData.get(lease.level);var occupied=new HashSet<>(lease.chunks);
        java.util.function.Predicate<UUID> inRegion=id->{var v=id==null?null:PoliticsService.village(lease.level,id);return v!=null && occupied.contains(new ChunkPos(v.getCenter()));};
        if(data.orders().stream().anyMatch(o->inRegion.test(o.source()) || inRegion.test(o.target())))return true;
        if(data.activeRaids().stream().anyMatch(r->inRegion.test(r.attacker()) || inRegion.test(r.target())))return true;
        return lease.villages.stream().map(id->PoliticsService.village(lease.level,id)).anyMatch(v->v==null || Military.busy(v));
    }
    private static boolean enabled() {return RoyalNpcConfig.BACKGROUND_ENABLED.get() && RoyalNpcConfig.budgets().enabled() && AutonomyConfig.current().autonomy();}
    private static int pending(ServerLevel level) {
        return (int)PENDING.getOrDefault(level.getServer(),List.of()).stream().filter(f->!f.isDone()).count();
    }
    private static void publish(Lease lease,long nanos,String reason) {
        STATES.computeIfAbsent(lease.level,l->new State()).report=new Report(lease.kingdom,lease.phase.name(),lease.owned.size(),lease.scans.size(),pending(lease.level),nanos,reason);
    }
    private static void defer(ServerLevel level,UUID kingdom,String reason) {
        var state=STATES.computeIfAbsent(level,l->new State());state.retry.put(kingdom,level.getGameTime()+200);
        state.report=new Report(kingdom,"DEFERRED",0,0,pending(level),0,reason);
    }
    private static void release(Lease lease,String reason) {
        // Native resident records lag live inventories between regular syncs. Preserve the original live records
        // before a controlled unload so subsequent native activation cannot recover an older cargo snapshot.
        if(!lease.owned.isEmpty())for(var id:lease.villages){var village=PoliticsService.village(lease.level,id);if(village!=null)village.syncRecords(lease.level);}
        for(var pos:lease.owned)lease.level.getChunkSource().removeRegionTicket(TICKET,pos,2,lease.owner,true);
        ACTIVE.remove(lease.level.getServer(),lease);defer(lease.level,lease.kingdom,reason);
    }
    /** Must run before vanilla drains chunk unload futures, not only after all worlds have already closed. */
    public static void onStopping(ServerStoppingEvent event) {
        var lease=ACTIVE.get(event.getServer());if(lease!=null) {
            int count=lease.owned.size();release(lease,"Server stopping; release economic tickets before chunk save/unload");
            com.mojang.logging.LogUtils.getLogger().info("[Kingdoms] Released {} economic chunk tickets before shutdown chunk drain",count);
        }
        QUEUED.remove(event.getServer());
    }
    public static void onStopped(ServerStoppedEvent event) {
        var lease=ACTIVE.get(event.getServer());if(lease!=null)release(lease,"Server stopped; only addon-owned expiring tickets released");
        STATES.keySet().removeIf(level->level.getServer()==event.getServer());PENDING.remove(event.getServer());QUEUED.remove(event.getServer());
    }
    private static void thread(ServerLevel level) {if(!level.getServer().isSameThread())throw new IllegalStateException("Economic activation requires the server thread");}
}
