package com.millenairekingdoms.autonomy;

import com.millenairekingdoms.economy.EconomyService;
import com.millenairekingdoms.economy.RoyalNpcDecisionStore;
import com.millenairekingdoms.construction.RoyalConstructionCatalog;
import com.millenairekingdoms.construction.RoyalConstructionConfig;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.personality.KingdomPersonality;
import com.millenairekingdoms.politics.PoliticsService;
import com.mojang.logging.LogUtils;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.slf4j.Logger;
import org.millenaire.village.Village;

/** Bounded NPC round robin, including unloaded capitals; no startup purchases; separate bounded activation owns only expiring tickets. */
public final class RoyalNpcRuntime {
    private static final Logger LOGGER=LogUtils.getLogger();
    private static final Map<ServerLevel,Session> SESSIONS=new WeakHashMap<>();
    public record Report(int inspected,int attempts,long elapsedNanos){}
    private static final int MAX_HANDOFFS=256;
    private static final class Handoff {
        final UUID kingdom,plan,target;
        boolean armed=true,running;
        long lastDiagnostic=-1200;
        Handoff(RoyalStrategyStore.Plan p){kingdom=p.kingdom();plan=p.id();target=p.target();}
    }
    private static final class Session {
        UUID cursor;long nextTick,lastError=Long.MIN_VALUE,handoffTick=Long.MIN_VALUE;int handoffsThisTick;Report last=new Report(0,0,0);
        final Map<UUID,Long> errors=new HashMap<>();
        final LinkedHashMap<UUID,Handoff> handoffs=new LinkedHashMap<>();
    }
    private RoyalNpcRuntime(){}
    public static void onStarted(ServerStartedEvent event){
        for(var level:event.getServer().getAllLevels()){
            var session=SESSIONS.computeIfAbsent(level,key->new Session());
            try{
                session.nextTick=level.getGameTime()+RoyalNpcConfig.cadence().intervalTicks();var path=RoyalNpcDecisionStore.directory(level);
                if(Files.exists(path.resolve("decisions.dat")))new RoyalNpcDecisionStore(path).entries();
                // Rebuild metadata hints before the first native growth tick, without startup spending/chunk access.
                var store=new RoyalStrategyStore(level);var data=KingdomSavedData.get(level);
                for(var k:data.kingdoms().stream().filter(k->k.active() && !k.fallen() && data.npcGoverned(k.id())).sorted(Comparator.comparing(KingdomSavedData.Kingdom::id)).limit(MAX_HANDOFFS).toList())
                    try{watchDevelopment(level,k.id(),store.read(k.id(),RoyalStrategyStore.Lane.DEVELOPMENT));}
                    catch(RuntimeException invalid){LOGGER.warn("[Kingdoms] NPC handoff metadata unavailable for {}; original plan retained: {}",k.id(),invalid.getMessage());}
            }catch(RuntimeException unsupported){session.nextTick=Long.MAX_VALUE;LOGGER.error("[Kingdoms] NPC economy startup unavailable in {}; no automatic spending, original history preserved.",level.dimension().location(),unsupported);}
        }
    }
    /** A bounded disposable hint, never a new authorization or a replacement for the persisted plan. */
    static void watchDevelopment(ServerLevel level,UUID kingdom,RoyalStrategyStore.Plan plan){
        var session=SESSIONS.computeIfAbsent(level,key->new Session());Handoff previous=null;
        for(var iterator=session.handoffs.values().iterator();iterator.hasNext();){var old=iterator.next();if(old.kingdom.equals(kingdom)){previous=old;iterator.remove();}}
        if(plan==null || plan.logistics() || plan.status()!=RoyalStrategyStore.Status.CONDITIONAL)return;
        var next=previous!=null && previous.plan.equals(plan.id()) && previous.target.equals(plan.target())?previous:new Handoff(plan);
        session.handoffs.put(next.target,next);
        while(session.handoffs.size()>MAX_HANDOFFS)session.handoffs.remove(session.handoffs.keySet().iterator().next());
    }
    /** Retry the exact existing unpaid NPC mandate once when native work vacates its slot. The idle path has no
     * disk/village scan. The normal policy/domain paths still validate current resources, builder, fee and authority. */
    public static void beforeNativeGrowth(ServerLevel level,Village village){
        if(!level.getServer().isSameThread())throw new IllegalStateException("Native economic handoff requires the server thread");
        if(!RoyalConstructionConfig.TRACKING.get() || !AutonomyConfig.current().autonomy() || !RoyalNpcConfig.budgets().enabled())return;
        var session=SESSIONS.get(level);if(session==null || session.nextTick==Long.MAX_VALUE)return;
        var handoff=session.handoffs.get(village.getId().uuid());if(handoff==null || handoff.running)return;
        if(RoyalConstructionCatalog.naturalWorkPending(village)){handoff.armed=true;return;}
        if(!handoff.armed)return;
        long tick=level.getGameTime();if(session.handoffTick!=tick){session.handoffTick=tick;session.handoffsThisTick=0;}
        if(session.handoffsThisTick>=RoyalNpcConfig.cadence().maximumKingdoms())return;
        session.handoffsThisTick++;
        handoff.armed=false;handoff.running=true;
        try{
            var plan=new RoyalStrategyStore(level).read(handoff.kingdom,RoyalStrategyStore.Lane.DEVELOPMENT);
            if(plan==null || plan.status()!=RoyalStrategyStore.Status.CONDITIONAL || !handoff.plan.equals(plan.id()) || !handoff.target.equals(plan.target())){
                session.handoffs.remove(handoff.target,handoff);return;
            }
            new RoyalStrategyService(level).step(handoff.kingdom,Math.max(0,level.getDayTime())/EconomyService.DAY_TICKS,level.getRandom());
        }catch(RuntimeException failure){
            long now=level.getGameTime();if(now-handoff.lastDiagnostic>=1200){handoff.lastDiagnostic=now;LOGGER.warn("[Kingdoms] Unpaid NPC handoff {} deferred; original work remains authoritative: {}",handoff.plan,failure.getMessage());}
        }finally{handoff.running=false;}
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
                .filter(k->{var capital=PoliticsService.village(level,k.capital());return capital!=null && capital.getTownhall()!=null && capital.getOwnerUUID()==null && KingdomPersonality.forCapital(level,k).specialized();})
                .sorted(Comparator.comparing(KingdomSavedData.Kingdom::id)).toList();
        session.errors.keySet().retainAll(kingdoms.stream().map(KingdomSavedData.Kingdom::id).collect(java.util.stream.Collectors.toSet()));
        if(kingdoms.isEmpty())return session.last=new Report(0,0,System.nanoTime()-started);
        var dimension=level.dimension().location().toString();long day=Math.max(0,level.getDayTime())/EconomyService.DAY_TICKS;
        // Validate the old financial authorization history even when every kingdom is unloaded.
        var path=RoyalNpcDecisionStore.directory(level);if(Files.exists(path.resolve("decisions.dat")))new RoyalNpcDecisionStore(path).entries();
        int start=0;if(session.cursor!=null){while(start<kingdoms.size() && kingdoms.get(start).id().compareTo(session.cursor)<=0)start++;start%=kingdoms.size();}
        int inspected=Math.min(limit,kingdoms.size()),attempts=0;var service=new RoyalStrategyService(level);
        for(int offset=0;offset<inspected;offset++){
            var kingdom=kingdoms.get((start+offset)%kingdoms.size());var id=kingdom.id();session.cursor=id;
            try{var result=service.step(id,day,level.getRandom());if(result!=null && result.attempt()!=null)attempts++;RoyalEconomicActivation.offer(level,id);session.errors.remove(id);}
            catch(RuntimeException failure){long now=level.getGameTime();var last=session.errors.get(id);if(last==null || now-last>=1200){session.errors.put(id,now);LOGGER.warn("[Kingdoms] NPC economy {} remains deferred: {}",id,failure.getMessage());}}
        }
        return session.last=new Report(inspected,attempts,System.nanoTime()-started);
    }
    public static Report last(ServerLevel level){var session=SESSIONS.get(level);return session==null ? new Report(0,0,0) : session.last;}
    public static void onStopped(ServerStoppedEvent event){SESSIONS.keySet().removeIf(level->level.getServer()==event.getServer());}
}
