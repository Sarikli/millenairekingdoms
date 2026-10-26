package com.millenairekingdoms.shipping;

import com.mojang.logging.LogUtils;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.slf4j.Logger;

/** Bounded existing-job round robin. No new NPC decisions, remote chunk loading or speculative data repair. */
public final class RoyalShipmentRuntime {
    private static final Logger LOGGER=LogUtils.getLogger();
    private static final Map<ServerLevel,Session> SESSIONS=new WeakHashMap<>();
    private static final class Session {UUID cursor;long nextTick;final Map<UUID,Long> diagnostics=new HashMap<>();}
    private RoyalShipmentRuntime(){}
    public static void onStarted(ServerStartedEvent event){
        for(var level:event.getServer().getAllLevels()){
            var session=SESSIONS.computeIfAbsent(level,key->new Session());session.nextTick=level.getGameTime()+RoyalShipmentConfig.INTERVAL.get();
            if(!Files.exists(RoyalShipmentService.directory(level).resolve("shipments.dat")))continue;
            try{new RoyalShipmentStore(RoyalShipmentService.directory(level)).entries();}
            catch(RuntimeException failure){session.nextTick=Long.MAX_VALUE;LOGGER.error("[Kingdoms] Shipment startup index unavailable in {}; tracking paused with original data preserved.",level.dimension().location(),failure);}
        }
    }
    /** Registered after native level bookkeeping so each new delta reads the latest native stock/authority. */
    public static void onLevelTick(LevelTickEvent.Post event){
        if(!(event.getLevel() instanceof ServerLevel level) || !RoyalShipmentConfig.TRACKING.get())return;
        var session=SESSIONS.computeIfAbsent(level,key->new Session());long now=level.getGameTime();if(now<session.nextTick)return;
        session.nextTick=now+RoyalShipmentConfig.INTERVAL.get();
        if(!Files.exists(RoyalShipmentService.directory(level).resolve("shipments.dat")))return;
        try{step(level,RoyalShipmentConfig.MAX_PER_STEP.get());}
        catch(RuntimeException failure){session.nextTick=Math.max(session.nextTick,now+1200);LOGGER.error("[Kingdoms] Shipment index unavailable; retry delayed, no speculative reset in {}.",level.dimension().location(),failure);}
    }
    public static int step(ServerLevel level,int limit){
        if(!level.getServer().isSameThread() || limit<1 || limit>16)throw new IllegalArgumentException("Invalid shipment tracking budget/server thread");
        var directory=RoyalShipmentService.directory(level);if(!Files.exists(directory.resolve("shipments.dat")))return 0;
        var dimension=level.dimension().location().toString();var entries=new RoyalShipmentStore(directory).entries().stream()
                .filter(e->e.intent().dimension().equals(dimension) && !e.terminal() && e.stage()!=RoyalShipmentStore.Stage.RECOVERY_REQUIRED)
                .sorted(Comparator.comparing(e->e.intent().id())).toList();
        var session=SESSIONS.computeIfAbsent(level,key->new Session());session.diagnostics.keySet().retainAll(entries.stream().map(e->e.intent().id()).collect(java.util.stream.Collectors.toSet()));
        if(entries.isEmpty())return 0;int start=0;
        if(session.cursor!=null){while(start<entries.size() && entries.get(start).intent().id().compareTo(session.cursor)<=0)start++;start%=entries.size();}
        int count=Math.min(limit,entries.size());var service=new RoyalShipmentService(level);
        for(int offset=0;offset<count;offset++){
            var entry=entries.get((start+offset)%entries.size());var id=entry.intent().id();session.cursor=id;
            try{service.observe(id);session.diagnostics.remove(id);}
            catch(RuntimeException failure){long now=level.getGameTime();var last=session.diagnostics.get(id);if(last==null || now-last>=1200){session.diagnostics.put(id,now);LOGGER.warn("[Kingdoms] Shipment {} remains pending ({}): {}",id,entry.stage(),failure.getMessage());}}
        }return count;
    }
    public static void onStopped(ServerStoppedEvent event){SESSIONS.keySet().removeIf(level->level.getServer()==event.getServer());RoyalShipmentService.clearRuntime(event.getServer());}
}
