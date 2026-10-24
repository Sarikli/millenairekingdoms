package com.millenairekingdoms.autonomy;

import com.millenairekingdoms.economy.EconomyService;
import com.millenairekingdoms.economy.RoyalNpcDecisionStore;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.personality.KingdomPersonality;
import com.millenairekingdoms.politics.PoliticsService;
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

/** Bounded active-NPC round robin; no remote chunks, startup purchases or replayed unissued proposals. */
public final class RoyalNpcRuntime {
    private static final Logger LOGGER=LogUtils.getLogger();
    private static final Map<ServerLevel,Session> SESSIONS=new WeakHashMap<>();
    public record Report(int inspected,int attempts,long elapsedNanos){}
    private static final class Session {UUID cursor;long nextTick,lastError=Long.MIN_VALUE;Report last=new Report(0,0,0);final Map<UUID,Long> errors=new HashMap<>();}
    private RoyalNpcRuntime(){}
    public static void onStarted(ServerStartedEvent event){
        for(var level:event.getServer().getAllLevels()){
            var session=SESSIONS.computeIfAbsent(level,key->new Session());
            try{
                session.nextTick=level.getGameTime()+RoyalNpcConfig.cadence().intervalTicks();var path=RoyalNpcDecisionStore.directory(level);
                if(Files.exists(path.resolve("decisions.dat")))new RoyalNpcDecisionStore(path).entries();
            }catch(RuntimeException unsupported){session.nextTick=Long.MAX_VALUE;LOGGER.error("[Kingdoms] NPC economy startup unavailable in {}; no automatic spending, original history preserved.",level.dimension().location(),unsupported);}
        }
    }
    /** HIGH runs before native growth's normal-priority slot selection. It never replaces an existing native project;
     * an occasional justified royal request may fill an idle slot, then the original engine owns placement/builders. */
    public static void onLevelTick(LevelTickEvent.Post event){
        if(!(event.getLevel() instanceof ServerLevel level))return;var session=SESSIONS.computeIfAbsent(level,key->new Session());long now=level.getGameTime();if(now<session.nextTick)return;
        session.nextTick=now+1200;
        try{
            if(!AutonomyConfig.current().autonomy() || !RoyalNpcConfig.budgets().enabled())return;
            var cadence=RoyalNpcConfig.cadence();session.nextTick=now+cadence.intervalTicks();step(level,cadence.maximumKingdoms());
        }catch(RuntimeException failure){
            session.nextTick=Math.max(session.nextTick,now+1200);
            if(session.lastError==Long.MIN_VALUE || now-session.lastError>=1200){session.lastError=now;LOGGER.error("[Kingdoms] NPC economy check unavailable in {}; retry delayed, no speculative reset.",level.dimension().location(),failure);}
        }
    }
    /** Explicit test/diagnostic step has a supplied budget; the production entrypoint additionally honors master autonomy. */
    public static Report step(ServerLevel level,int limit){
        if(!level.getServer().isSameThread() || limit<1 || limit>16)throw new IllegalArgumentException("Invalid NPC economic server-thread budget");long started=System.nanoTime();var session=SESSIONS.computeIfAbsent(level,key->new Session());
        var data=KingdomSavedData.get(level);var kingdoms=data.kingdoms().stream().filter(k->k.active() && !k.fallen() && data.npcGoverned(k.id()))
                .filter(k->{var capital=PoliticsService.village(level,k.capital());return capital!=null && capital.getTownhall()!=null && capital.getOwnerUUID()==null && capital.isActive() && level.isLoaded(capital.getCenter()) && KingdomPersonality.forCapital(level,k).specialized();})
                .sorted(Comparator.comparing(KingdomSavedData.Kingdom::id)).toList();
        session.errors.keySet().retainAll(kingdoms.stream().map(KingdomSavedData.Kingdom::id).collect(java.util.stream.Collectors.toSet()));
        if(kingdoms.isEmpty())return session.last=new Report(0,0,System.nanoTime()-started);
        var dimension=level.dimension().location().toString();long day=Math.max(0,level.getDayTime())/EconomyService.DAY_TICKS;
        var due=new HashMap<UUID,Long>();var path=RoyalNpcDecisionStore.directory(level);
        if(Files.exists(path.resolve("decisions.dat")))for(var d:new RoyalNpcDecisionStore(path).entries())if(d.dimension().equals(dimension))due.merge(d.kingdom(),d.nextDay(),Math::max);
        int start=0;if(session.cursor!=null){while(start<kingdoms.size() && kingdoms.get(start).id().compareTo(session.cursor)<=0)start++;start%=kingdoms.size();}
        int inspected=Math.min(limit,kingdoms.size()),attempts=0;var service=RoyalNpcEconomyService.automatic(level);
        for(int offset=0;offset<inspected;offset++){
            var kingdom=kingdoms.get((start+offset)%kingdoms.size());var id=kingdom.id();session.cursor=id;if(day<due.getOrDefault(id,0L))continue;
            try{var result=service.evaluate(id,day,level.getRandom());if(result.attempt()!=null)attempts++;session.errors.remove(id);}
            catch(RuntimeException failure){long now=level.getGameTime();var last=session.errors.get(id);if(last==null || now-last>=1200){session.errors.put(id,now);LOGGER.warn("[Kingdoms] NPC economy {} remains deferred: {}",id,failure.getMessage());}}
        }
        return session.last=new Report(inspected,attempts,System.nanoTime()-started);
    }
    public static Report last(ServerLevel level){var session=SESSIONS.get(level);return session==null ? new Report(0,0,0) : session.last;}
    public static void onStopped(ServerStoppedEvent event){SESSIONS.keySet().removeIf(level->level.getServer()==event.getServer());}
}
