package com.millenairekingdoms.observation;

import com.google.gson.*;
import com.millenairekingdoms.autonomy.*;
import com.millenairekingdoms.capital.CapitalConfig;
import com.millenairekingdoms.construction.*;
import com.millenairekingdoms.economy.*;
import com.millenairekingdoms.history.*;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.personality.KingdomPersonality;
import com.millenairekingdoms.shipping.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.common.Mod;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.millenaire.Millenaire;
import org.millenaire.config.MillenaireServerConfig;
import org.millenaire.culture.ModCultures;
import org.millenaire.test.TestPlayerManager;
import org.millenaire.village.*;

/** Separate observer of the experimental1.2.2 candidate JAR. Never calls a gameplay service mutator.
 * Setup uses the original dependency's passive test player to explore normal terrain.
 * Vanilla tick sprint executes every tick; neither clock, relationships, resources nor outcomes are edited. */
@Mod("kingdoms_natural_observer")
public final class NaturalObservation {
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private static final TicketType<ChunkPos> EXPLORATION=TicketType.create("kingdoms_observer_exploration",Comparator.comparingLong(ChunkPos::toLong));
    private final Set<ChunkPos> explorationTickets=new HashSet<>();
    private final Set<UUID> knownVillages=new HashSet<>(),knownHistory=new HashSet<>();
    private final List<int[]> route=new ArrayList<>();
    private Path output; private ServerLevel level; private ServerPlayer observer;
    private volatile String phase="BOOT"; private int ticks,stops,lastSnapshot=-1; private long baseTick,baseDay,previousTick,previousDay,observedTicks,observerCost;
    private long wallStarted=System.nanoTime(),baselineWall; private UUID anchor; private EmbeddedChannel channel;
    private final String mode=System.getProperty("kingdoms.observation.mode","write");
    private final boolean reopen="reopen".equals(mode)||"diagnose-reopen".equals(mode);
    private final boolean diagnostic=mode.startsWith("diagnose");
    private final long diagnosticTicks=Long.getLong("kingdoms.observation.ticks",24000L);
    private static final boolean COLD = Boolean.parseBoolean(System.getProperty("kingdoms.observation.cold","true"));
    private long resumedAtElapsedTicks;
    private final java.util.concurrent.atomic.AtomicInteger observedNewChunks=new java.util.concurrent.atomic.AtomicInteger();
    public NaturalObservation(){
        for(int radius=0;radius<=20;radius++)for(int z=-radius;z<=radius;z++)for(int x=-radius;x<=radius;x++)
            if(Math.max(Math.abs(x),Math.abs(z))==radius)route.add(new int[]{x*384,z*384});
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,false,ServerStartedEvent.class,this::started);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,false,ServerTickEvent.Post.class,this::tick);
        NeoForge.EVENT_BUS.addListener(this::chunkLoaded);
    }
    private void chunkLoaded(net.neoforged.neoforge.event.level.ChunkEvent.Load event){
        if(event.getLevel()==level && event.isNewChunk() && (phase.equals("OBSERVE") || phase.equals("DIAGNOSE"))){
            observedNewChunks.incrementAndGet();
        }
    }
    private void started(ServerStartedEvent event){
        level=event.getServer().overworld();output=event.getServer().getWorldPath(LevelResource.ROOT).resolve(diagnostic?"economy-diagnostics-"+System.getProperty("kingdoms.observation.label","probe"):"natural-observation");
        try{Files.createDirectories(output);log("START",Map.of("seed",level.getSeed(),"mode",mode,"normalDimension",level.dimension().location().toString(),"capitalChance",CapitalConfig.CHANCE_PERCENT.get(),"capitalSpacing",CapitalConfig.MIN_DISTANCE.get(),"autonomy",AutonomyConfig.current().autonomy(),"originalResourceCosts",!MillenaireServerConfig.SERVER.ignoreResourceCost.get(),"worldGeneration","normal; original ChunkEvent.Load -> native VillageSpawnQueue only"));if(diagnostic&&reopen)verifyDiagnosticReopen();else if(mode.equals("resume"))resumeObservation();else if(reopen)verifyReopen();}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    private void tick(ServerTickEvent.Post event){
        if(level==null||phase.equals("DONE")||phase.equals("FAILED"))return;
        long started=System.nanoTime();ticks++;
        try{
            if(diagnostic){diagnose();return;}
            if(phase.equals("BOOT")){
                if(reopen){verifyReopen();return;}
                if(ticks<100)return;
                if(Files.exists(output.resolve("baseline.json")))throw new IllegalStateException("Refuse to overwrite an earlier observation");
                if(CapitalConfig.CHANCE_PERCENT.get()!=10.0||CapitalConfig.MIN_DISTANCE.get()!=1000||!AutonomyConfig.current().autonomy()||MillenaireServerConfig.SERVER.ignoreResourceCost.get())throw new IllegalStateException("Production defaults were changed");
                observer=TestPlayerManager.spawn(level.getServer(),level,level.getSharedSpawnPos().above(2));
                observer.setGameMode(GameType.CREATIVE);observer.setInvulnerable(true);
                channel=(EmbeddedChannel)field(field(observer.connection,"connection"),"channel");
                phase="EXPLORE";command("tick sprint 100000000");visit();
            }
            drainPackets();
            if(ticks%20==0)discover();
            if(phase.equals("EXPLORE")){
                if(ticks%300==0){
                    var data=KingdomSavedData.get(level);var villages=politicalVillages();
                    long independent=villages.stream().filter(v->data.owner(v.getId().uuid())==null&&v.getParentVillageId()==null).count();
                    var capitals=data.kingdoms().stream().filter(k->!k.fallen()).toList();
                    if(capitals.size()>=2&&independent>=12&&capitals.stream().filter(k->{var c=village(k.capital());return c!=null&&villages.stream().filter(v->data.owner(v.getId().uuid())==null&&v.getParentVillageId()==null&&horizontal(c,v)<=2000).count()>=2;}).count()>=2){
                        anchor=capitals.stream().map(k->village(k.capital())).filter(Objects::nonNull).min(Comparator.comparingDouble(v->v.getCenter().distSqr(level.getSharedSpawnPos()))).orElseThrow().getId().uuid();
                        teleport(village(anchor).getCenter().offset(0,3,0));
                        for(var chunk:explorationTickets)level.getChunkSource().removeRegionTicket(EXPLORATION,chunk,2,chunk);explorationTickets.clear();
                        phase="SETTLE";previousTick=level.getGameTime();log("SETTLING",Map.of("anchor",anchor,"villages",villages.size(),"independent",independent,"kingdoms",capitals.size(),"explorationStops",stops));
                    }else{
                        log("EXPLORATION",Map.of("stops",stops,"villages",villages.size(),"independent",independent,"kingdoms",capitals.size(),"queue",Millenaire.getSpawnQueue().getStats(),"playerPosition",observer.blockPosition().toShortString(),"loadedChunks",level.getChunkSource().getLoadedChunksCount()));visit();
                    }
                }
            }else if(phase.equals("SETTLE")){
                if(level.getGameTime()-previousTick>=600){
                    baseTick=level.getGameTime();baseDay=level.getDayTime();previousTick=baseTick;previousDay=baseDay;baselineWall=System.nanoTime();phase="OBSERVE";
                    if(COLD && observer!=null){TestPlayerManager.remove();observer=null;channel=null;}
                    var baseline=snapshot(0);write("baseline.json",baseline);snapshotDay(0);log("BASELINE",Map.of("baseTick",baseTick,"baseDayTime",baseDay,"targetActualTicks",2880000,"anchor",anchor,"observationPlayer",COLD?"zero players after normal native generation/settling; only production temporary economic leases":"passive creative player at one naturally generated capital"));
                }
            }else if(phase.equals("OBSERVE")){
                long now=level.getGameTime(),day=level.getDayTime();
                if(COLD && !level.getServer().getPlayerList().getPlayers().isEmpty())throw new IllegalStateException("Cold measurement must have zero players");
                if(now!=previousTick+1||day!=previousDay+1)throw new IllegalStateException("Clock discontinuity: "+previousTick+" -> "+now+", dayTime "+previousDay+" -> "+day);
                previousTick=now;previousDay=day;observedTicks++;long elapsed=now-baseTick;
                if(observedTicks!=elapsed)throw new IllegalStateException("Missing observed tick");
                int completed=(int)(elapsed/24000);
                if(completed>lastSnapshot){if(completed>0){long saveStarted=System.nanoTime();command("save-all flush");log("CHECKPOINT",Map.of("day",completed,"gameTime",level.getGameTime(),"saveMillis",(System.nanoTime()-saveStarted)/1e6));}snapshotDay(completed);}
                if(elapsed>=2880000){
                    var result=snapshot(120);result.put("observationComplete",true);result.put("consecutiveTicks",observedTicks);result.put("resumedAtElapsedTicks",resumedAtElapsedTicks);result.put("ticksValidatedInFinalJvm",observedTicks-resumedAtElapsedTicks);result.put("wallSeconds",(System.nanoTime()-baselineWall)/1e9);result.put("wallTimeScope",resumedAtElapsedTicks==0?"observation since original baseline":"final JVM segment only; earlier interrupted segment excluded");result.put("observerNanos",observerCost);write("final.json",result);phase="DONE";log("COMPLETE",Map.of("days",120,"actualTicks",observedTicks,"worldDayTimeDelta",day-baseDay,"wallSeconds",(System.nanoTime()-baselineWall)/1e9));command("tick sprint stop");level.getServer().halt(false);
                }
                if(phase.equals("OBSERVE")&&ticks%200==0&&Files.exists(output.resolve("pause.request"))){command("save-all flush");write("paused.json",snapshot(completed));Files.move(output.resolve("pause.request"),output.resolve("pause-applied-"+now+".txt"));phase="DONE";log("PAUSED",Map.of("elapsedTicks",elapsed,"gameTime",now));command("tick sprint stop");level.getServer().halt(false);}
            }
        }catch(Throwable failure){
            phase="FAILED";failure.printStackTrace();try{write("failure.json",Map.of("tick",level.getGameTime(),"dayTime",level.getDayTime(),"phase",phase,"message",failure.toString(),"observedTicks",observedTicks));}catch(Exception ignored){}
            log("FAILED",Map.of("message",failure.toString()));level.getServer().halt(false);
        }finally{observerCost+=System.nanoTime()-started;}
    }
    /** Read-only probe of a disposable copy, including the empty-dimension entity-tick gate. */
    private void diagnose()throws Exception{
        if(ticks<100)return;
        if(phase.equals("BOOT")){
            if(Files.exists(output.resolve("baseline.json")))throw new IllegalStateException("Do not overwrite diagnostic evidence");
            baseTick=level.getGameTime();baseDay=level.getDayTime();previousTick=baseTick;previousDay=baseDay;baselineWall=System.nanoTime();phase="DIAGNOSE";
            write("baseline.json",snapshot(0));command("tick sprint 100000000");
        }else{
            if(level.getGameTime()!=previousTick+1 || level.getDayTime()!=previousDay+1)throw new IllegalStateException("Diagnostic clock discontinuity");
            previousTick=level.getGameTime();previousDay=level.getDayTime();observedTicks++;
        }
        if(!level.players().isEmpty() || !explorationTickets.isEmpty())throw new IllegalStateException("Diagnostic must have zero players and exploration tickets");
        if(observedTicks%200==0){
            var row=new LinkedHashMap<String,Object>();row.put("elapsedTicks",observedTicks);row.put("gameTime",level.getGameTime());
            row.put("emptyTime",field(level,"emptyTime"));row.put("forcedChunks",level.getForcedChunks().size());
            row.put("nativeOrNeoForced",net.neoforged.neoforge.common.world.chunk.ForcedChunkManager.hasForcedChunks(level));row.put("lease",RoyalEconomicActivation.report(level));
            var villages=new ArrayList<Map<String,Object>>();var data=KingdomSavedData.get(level);
            for(var v:politicalVillages()){
                if(!v.isActive() || !level.isLoaded(v.getCenter()) || data.owner(v.getId().uuid())==null)continue;
                var vr=new LinkedHashMap<String,Object>();vr.put("id",v.getId().uuid());vr.put("name",v.getVillageName());vr.put("center",v.getCenter().toShortString());vr.put("pending",v.getPendingProject());
                vr.put("npcs",v.getVillagerUuids().stream().map(level::getEntity).filter(e->e instanceof org.millenaire.entity.MillVillager).map(e->{var npc=(org.millenaire.entity.MillVillager)e;return Map.of("id",npc.getUUID(),"ticks",npc.tickCount,"position",npc.blockPosition().toShortString(),"noAi",npc.isNoAi(),"goal",String.valueOf(npc.getGoalScheduler().getCurrentGoalId()));}).toList());
                try{
                    var stock=RoyalVillageStock.forNpc(level,data.owner(v.getId().uuid()),v.getId().uuid());
                    vr.put("stock",stock.products().stream().limit(64).map(stock::availability).toList());
                    var p=v.getPendingProject();if(p!=null){var set=ModCultures.getBuildingPlanSet(p.planSetId());var def=set==null?null:set.getLevel(p.variant(),p.level());if(def!=null){vr.put("required",def.requiredResources());vr.put("missing",stock.missing(def.requiredResources()));}}
                }catch(RuntimeException unavailable){vr.put("stockReason",String.valueOf(unavailable.getMessage()));}
                villages.add(vr);
            }
            row.put("villages",villages);append("samples.jsonl",row);
            if(observedTicks%24000==0){
                if(observedTicks>0)command("save-all flush");
                var daily=snapshot((int)(observedTicks/24000));append("daily.jsonl",daily);write("latest.json",daily);
                log("DIAGNOSTIC_DAY",Map.of("ticks",observedTicks,"lease",RoyalEconomicActivation.report(level),"loadedVillages",villages.size(),"newChunks",observedNewChunks.get()));
            }
        }
        if(observedTicks>=diagnosticTicks){
            command("save-all flush");write("final.json",snapshot((int)(observedTicks/24000)));phase="DONE";
            log("DIAGNOSTIC_COMPLETE",Map.of("ticks",observedTicks,"output",output.toString()));command("tick sprint stop");level.getServer().halt(false);
        }
    }
    private void verifyDiagnosticReopen()throws Exception{
        var expected=JsonParser.parseString(Files.readString(output.resolve("final.json"))).getAsJsonObject();
        var actual=JSON.toJsonTree(snapshot(expected.get("relativeDay").getAsInt())).getAsJsonObject();
        for(var key:List.of("gameTime","dayTime","bookedDay","payments","construction","shipments","economicDecisions","historySize","historyCounts"))
            if(!Objects.equals(expected.get(key),actual.get(key)))throw new IllegalStateException("Diagnostic restart changed recorded "+key);
        var byId=new HashMap<String,JsonObject>();for(var row:actual.getAsJsonArray("kingdoms")){var k=row.getAsJsonObject();byId.put(k.get("id").getAsString(),k);}
        if(byId.size()!=expected.getAsJsonArray("kingdoms").size())throw new IllegalStateException("Diagnostic kingdom count changed");
        for(var row:expected.getAsJsonArray("kingdoms")){var k=row.getAsJsonObject();var other=byId.get(k.get("id").getAsString());if(other==null)throw new IllegalStateException("Diagnostic kingdom absent");
            for(var key:List.of("id","capital","fallen","active","members","owed","portfolio"))if(!Objects.equals(k.get(key),other.get(key)))throw new IllegalStateException("Diagnostic restart changed kingdom "+key);
        }
        write("reopen.json",Map.of("sameWorldSeparateJvm",true,"economicRecordsPreserved",true,"kingdomPortfoliosPreserved",true,"gameTime",level.getGameTime(),"dayTime",level.getDayTime()));
        phase="DONE";log("DIAGNOSTIC_REOPEN",Map.of("sameWorldSeparateJvm",true));level.getServer().halt(false);
    }
    private void resumeObservation()throws Exception{
        var baseline=JsonParser.parseString(Files.readString(output.resolve("baseline.json"))).getAsJsonObject();
        var latest=JsonParser.parseString(Files.readString(output.resolve("latest.json"))).getAsJsonObject();
        if(Files.exists(output.resolve("final.json")))throw new IllegalStateException("Completed observation requires reopen verification, not another simulation");
        baseTick=baseline.get("baseTick").getAsLong();baseDay=baseline.get("baseDayTime").getAsLong();previousTick=level.getGameTime();previousDay=level.getDayTime();observedTicks=previousTick-baseTick;lastSnapshot=latest.get("relativeDay").getAsInt();
        if(observedTicks<latest.get("observedTicks").getAsLong()||previousDay-baseDay!=observedTicks||observedTicks>=2880000||observedTicks/24000!=lastSnapshot)throw new IllegalStateException("Saved clock is inconsistent with recorded daily samples; preserve evidence");
        for(var line:Files.readAllLines(output.resolve("generation.jsonl")))if(!line.isBlank())knownVillages.add(UUID.fromString(JsonParser.parseString(line).getAsJsonObject().get("id").getAsString()));
        if(Files.exists(output.resolve("history.jsonl")))for(var line:Files.readAllLines(output.resolve("history.jsonl")))if(!line.isBlank())knownHistory.add(UUID.fromString(JsonParser.parseString(line).getAsJsonObject().get("id").getAsString()));
        var all=politicalVillages().stream().map(v->v.getId().uuid()).collect(Collectors.toSet());if(!all.containsAll(knownVillages))throw new IllegalStateException("Recorded naturally generated village missing from reopened native save");
        var initialCapitals=new ArrayList<Village>();var data=KingdomSavedData.get(level);
        for(var entry:baseline.getAsJsonArray("kingdoms")){var k=entry.getAsJsonObject();if(data.kingdom(UUID.fromString(k.get("id").getAsString()))==null)throw new IllegalStateException("Initial natural kingdom missing");var v=village(UUID.fromString(k.get("capital").getAsString()));if(v==null)throw new IllegalStateException("Initial natural capital missing");initialCapitals.add(v);}
        var capital=initialCapitals.stream().min(Comparator.comparingDouble(v->v.getCenter().distSqr(level.getSharedSpawnPos()))).orElseThrow();anchor=capital.getId().uuid();
        observer=TestPlayerManager.spawn(level.getServer(),level,capital.getCenter().above(3));observer.setGameMode(GameType.CREATIVE);observer.setInvulnerable(true);channel=(EmbeddedChannel)field(field(observer.connection,"connection"),"channel");
        resumedAtElapsedTicks=observedTicks;baselineWall=System.nanoTime();phase="OBSERVE";
                    if(COLD && observer!=null){TestPlayerManager.remove();observer=null;channel=null;}
        var receipt=Map.of("savedGameTime",previousTick,"savedDayTime",previousDay,"originalBaseTick",baseTick,"originalBaseDayTime",baseDay,"elapsedWorldTicksAtResume",observedTicks,"lastCompletedDailySample",lastSnapshot,"anchor",anchor,"observerTickets",explorationTickets.size());write("resume-"+previousTick+".json",receipt);log("RESUMED",receipt);command("tick sprint 100000000");
    }
    private void visit(){
        if(stops>=route.size())throw new IllegalStateException("Exploration bound reached without two natural capitals and nearby independent villages");
        var xy=route.get(stops++);var spawn=level.getSharedSpawnPos();int x=spawn.getX()+xy[0],z=spawn.getZ()+xy[1];
        // The embedded connection has no graphical client driving a normal chunk view.
        // Keep the same surrounding21x21 view terrain resident explicitly, through ordinary ChunkEvent.Load.
        // A transient getChunk alone expires before the native post-tick spawn queue can inspect it.
        // No village/capital spawner, biome, terrain block or gameplay state is modified here.
        int cx=x>>4,cz=z>>4;
        var next=new HashSet<ChunkPos>();
        for(int dz=-10;dz<=10;dz++)for(int dx=-10;dx<=10;dx++){var chunk=new ChunkPos(cx+dx,cz+dz);next.add(chunk);if(!explorationTickets.contains(chunk))level.getChunkSource().addRegionTicket(EXPLORATION,chunk,2,chunk);}
        for(int dz=-10;dz<=10;dz++)for(int dx=-10;dx<=10;dx++)level.getChunk(cx+dx,cz+dz);
        for(var chunk:explorationTickets)if(!next.contains(chunk))level.getChunkSource().removeRegionTicket(EXPLORATION,chunk,2,chunk);
        explorationTickets.clear();explorationTickets.addAll(next);
        int y=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z)+2;teleport(new BlockPos(x,y,z));
    }
    private void teleport(BlockPos position){observer.teleportTo(level,position.getX()+0.5,position.getY(),position.getZ()+0.5,Set.of(),0,0);TestPlayerManager.confirmTeleport();}
    private void drainPackets(){if(channel!=null){Object message;while((message=channel.readOutbound())!=null)ReferenceCountUtil.release(message);}}
    private static Object field(Object instance,String name)throws Exception{for(Class<?> c=instance.getClass();c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(instance);}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(name);}
    private void command(String text){level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack(),text);}
    private Village village(UUID id){return VillageSavedData.get(level).getVillageManager().getVillage(new VillageId(id));}
    private List<Village> politicalVillages(){return VillageSavedData.get(level).getVillageManager().getAllVillages().stream().filter(v->{var type=ModCultures.getVillageType(v.getVillageTypeId());return type!=null&&!type.loneBuilding()&&!v.getBuildings().isEmpty();}).toList();}
    private static double horizontal(Village a,Village b){return Math.hypot(a.getCenter().getX()-b.getCenter().getX(),a.getCenter().getZ()-b.getCenter().getZ());}
    private void discover()throws Exception{
        var data=KingdomSavedData.get(level);
        for(var v:politicalVillages())if(knownVillages.add(v.getId().uuid()))append("generation.jsonl",villageRow(v,data));
    }
    private Map<String,Object> villageRow(Village v,KingdomSavedData data){
        var row=new LinkedHashMap<String,Object>();row.put("id",v.getId().uuid());row.put("name",v.getVillageName());row.put("type",v.getVillageTypeId().toString());row.put("culture",v.getCultureId().toString());row.put("x",v.getCenter().getX());row.put("y",v.getCenter().getY());row.put("z",v.getCenter().getZ());row.put("parent",v.getParentVillageId()==null?null:v.getParentVillageId().uuid());row.put("owner",data.owner(v.getId().uuid()));row.put("active",v.isActive());row.put("loaded",level.isLoaded(v.getCenter()));row.put("residentRecords",v.getVillagerRecords().size());row.put("centreLevel",v.getTownhall()==null?-1:v.getTownhall().getLevel());
        row.put("buildings",v.getBuildings().stream().map(b->Map.of("id",b.getId().uuid(),"plan",b.getPlanId().toString(),"level",b.getLevel(),"status",b.getStatus().name())).toList());
        row.put("relations",v.getRelations().entrySet().stream().collect(Collectors.toMap(e->e.getKey().uuid().toString(),Map.Entry::getValue)));row.put("gameTime",level.getGameTime());row.put("dayTime",level.getDayTime());return row;
    }
    private Map<String,Object> snapshot(int relativeDay)throws Exception{
        var data=KingdomSavedData.get(level);var row=new LinkedHashMap<String,Object>();row.put("relativeDay",relativeDay);row.put("gameTime",level.getGameTime());row.put("dayTime",level.getDayTime());row.put("bookedDay",data.day());row.put("baseTick",baseTick);row.put("baseDayTime",baseDay);row.put("observedTicks",observedTicks);row.put("observerCostNanos",observerCost);row.put("wallSeconds",(System.nanoTime()-wallStarted)/1e9);row.put("loadedChunks",level.getChunkSource().getLoadedChunksCount());row.put("forcedChunks",level.getForcedChunks().size());row.put("observerExplorationTickets",explorationTickets.size());row.put("players",level.players().size());
        row.put("villages",politicalVillages().stream().map(v->villageRow(v,data)).toList());var kingdoms=new ArrayList<Map<String,Object>>();
        for(var k:data.kingdoms()){
            var r=new LinkedHashMap<String,Object>();r.put("id",k.id());r.put("name",k.name());r.put("capital",k.capital());r.put("fallen",k.fallen());r.put("active",k.active());r.put("ethos",KingdomPersonality.forKingdom(level,k.id()).category().name());r.put("members",data.members(k.id()).stream().sorted().toList());r.put("owed",data.owed(k.id()));
            try{r.put("spendableCoins",RoyalTreasurySnapshot.forNpc(level,k.id()).spendableValue());}catch(RuntimeException unavailable){r.put("spendableCoins",null);r.put("coinReason",unavailable.getMessage());}
            var portfolio=new RoyalStrategyStore(level).portfolio(k.id());r.put("portfolio",portfolio);
            var project=portfolio.development();var need=project!=null && project.status()==RoyalStrategyStore.Status.CONDITIONAL
                    ?new RoyalNpcEconomyService.ProjectNeed(project.id(),project.target(),project.selection()):null;
            r.put("economicPreview",RoyalNpcEconomyService.automatic(level).inspect(k.id(),Math.max(0,level.getDayTime())/24000,need));kingdoms.add(r);
        }
        row.put("background",RoyalEconomicActivation.report(level));row.put("lastEconomicRuntime",RoyalNpcRuntime.last(level));
        row.put("effectiveBudget",RoyalNpcConfig.budgets());row.put("economicBackgroundEnabled",RoyalNpcConfig.BACKGROUND_ENABLED.get());
        row.put("newChunksDuringObservation",observedNewChunks.get());
        if(diagnostic && !reopen)row.put("measurementWallSeconds",(System.nanoTime()-baselineWall)/1e9);
        var paymentRoot=level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms/royal-payments");
        if(Files.isDirectory(paymentRoot))row.put("payments",new RoyalPaymentJournal(paymentRoot).entries().stream().sorted(Comparator.comparing(RoyalPaymentJournal.Entry::id)).map(p->Map.of("id",p.id(),"state",p.state().name(),"purpose",p.intent().getString("purpose"),"amount",p.intent().getLong("amount"))).toList());
        row.put("kingdoms",kingdoms);row.put("wars",data.wars());row.put("activeRaids",data.activeRaids());
        var root=level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms");
        if(Files.exists(root.resolve("royal-construction/orders.dat")))row.put("construction",new RoyalConstructionStore(root.resolve("royal-construction")).entries().stream().sorted(Comparator.comparing(e->e.intent().id())).map(e->Map.of("id",e.intent().id(),"kingdom",e.intent().kingdom(),"village",e.intent().village(),"plan",e.intent().planSet().toString(),"stage",e.stage().name(),"fee",e.intent().fee(),"reason",e.blocked().name())).toList());
        if(Files.exists(RoyalShipmentService.directory(level).resolve("shipments.dat")))row.put("shipments",new RoyalShipmentStore(RoyalShipmentService.directory(level)).entries().stream().sorted(Comparator.comparing(e->e.intent().id())).map(this::shipmentRow).toList());
        if(Files.exists(RoyalNpcDecisionStore.directory(level).resolve("decisions.dat")))row.put("economicDecisions",new RoyalNpcDecisionStore(RoyalNpcDecisionStore.directory(level)).entries().stream().sorted(Comparator.comparing(RoyalNpcDecisionStore.Decision::id)).toList());
        var history=HistoryHooks.store(level);row.put("historySize",history.size());var counts=new LinkedHashMap<String,Integer>();
        for(var kind:HistoryEvent.Kind.values()){int count=0;UUID cursor=null;do{var page=history.query(new HistoryStore.Query(null,kind,0,Long.MAX_VALUE,cursor,24));count+=page.events().size();for(var h:page.events())if(!reopen && knownHistory.add(h.id()))append("history.jsonl",h);cursor=page.next();}while(cursor!=null);counts.put(kind.name(),count);}row.put("historyCounts",counts);return row;
    }
    private Map<String,Object> shipmentRow(RoyalShipmentStore.Entry e) {
        var row=new LinkedHashMap<String,Object>();row.put("id",e.intent().id());row.put("kingdom",e.intent().kingdom());row.put("stage",e.stage().name());
        row.put("source",e.intent().source().village());row.put("target",e.intent().destination().village());row.put("product",e.intent().product().toString());
        row.put("quantity",e.intent().quantity());row.put("fee",e.intent().fee());row.put("collected",e.collected());row.put("delivered",e.delivered());row.put("held",e.escrow());row.put("returned",e.returned());row.put("due",e.due());return row;
    }
    private void snapshotDay(int day)throws Exception{
        var data=snapshot(day);append("daily.jsonl",data);write("latest.json",data);lastSnapshot=day;
        log("DAY",Map.of("relativeDay",day,"ticks",observedTicks,"kingdoms",((List<?>)data.get("kingdoms")).size(),"villages",((List<?>)data.get("villages")).size(),"history",data.get("historySize"),"loadedChunks",data.get("loadedChunks"),"wallSeconds",(System.nanoTime()-baselineWall)/1e9));
    }
    private void verifyReopen()throws Exception{
        var expected=JsonParser.parseString(Files.readString(output.resolve("final.json"))).getAsJsonObject();if(!expected.get("observationComplete").getAsBoolean()||expected.get("consecutiveTicks").getAsLong()!=2880000)throw new IllegalStateException("Observation not complete");
        var data=KingdomSavedData.get(level);for(var e:expected.getAsJsonArray("kingdoms")){var k=e.getAsJsonObject();var id=UUID.fromString(k.get("id").getAsString());var actual=data.kingdom(id);if(actual==null||actual.fallen()!=k.get("fallen").getAsBoolean()||!actual.capital().toString().equals(k.get("capital").getAsString()))throw new IllegalStateException("Kingdom reopen mismatch: "+id);var members=new HashSet<String>();k.getAsJsonArray("members").forEach(m->members.add(m.getAsString()));if(!data.members(id).stream().map(UUID::toString).collect(Collectors.toSet()).equals(members))throw new IllegalStateException("Member reopen mismatch");}
        if(data.day()!=expected.get("bookedDay").getAsLong()||HistoryHooks.store(level).size()!=expected.get("historySize").getAsInt())throw new IllegalStateException("Daily ledger/history reopen mismatch");
        var actual=JSON.toJsonTree(snapshot(120)).getAsJsonObject();
        for(var key:List.of("economicDecisions","construction","shipments","payments"))if(!Objects.equals(expected.get(key),actual.get(key)))throw new IllegalStateException("Economic record reopen mismatch: "+key);
        for(var entry:expected.getAsJsonArray("kingdoms")) {
            var before=entry.getAsJsonObject();var id=before.get("id").getAsString();
            var after=java.util.stream.StreamSupport.stream(actual.getAsJsonArray("kingdoms").spliterator(),false).map(com.google.gson.JsonElement::getAsJsonObject).filter(k->id.equals(k.get("id").getAsString())).findFirst().orElseThrow();
            if(!Objects.equals(before.get("portfolio"),after.get("portfolio")))throw new IllegalStateException("Economic portfolio reopen mismatch: "+id);
        }
        write("reopen.json",Map.of("sameWorldSeparateJvm",true,"kingdomMembershipAndCapitalsPreserved",true,"economicRecordsPreserved",true,"bookedDay",data.day(),"historySize",HistoryHooks.store(level).size(),"gameTime",level.getGameTime(),"dayTime",level.getDayTime()));phase="DONE";log("REOPEN_VERIFIED",Map.of("kingdoms",data.kingdoms().size(),"history",HistoryHooks.store(level).size()));level.getServer().halt(false);
    }
    private void append(String file,Object value)throws Exception{Files.writeString(output.resolve(file),JSON.toJson(value)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
    private void write(String file,Object value)throws Exception{Files.writeString(output.resolve(file),JSON.toJson(value)+"\n");}
    private void log(String kind,Object value){System.out.println("ECONOMY_120_"+kind+" "+JSON.toJson(value));}
}
