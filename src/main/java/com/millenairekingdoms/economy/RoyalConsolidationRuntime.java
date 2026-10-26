package com.millenairekingdoms.economy;

import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.mojang.logging.LogUtils;
import java.util.*;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** One bounded recovery job/200 ticks plus bounded new maintenance/1200 ticks. No force-loading or client proximity. */
public final class RoyalConsolidationRuntime {
    public record Report(int inspected,int committed,long elapsedNanos){}
    private static final Map<ServerLevel,Session> SESSIONS=new WeakHashMap<>();
    private static final class Session{UUID capitalCursor,recoveryCursor;long nextMaintenance,nextRecovery;Report last=new Report(0,0,0);final Map<String,Long> warnings=new HashMap<>();}
    private RoyalConsolidationRuntime(){}
    public static void onStarted(ServerStartedEvent event){for(var level:event.getServer().getAllLevels()){var session=SESSIONS.computeIfAbsent(level,l->new Session());session.nextRecovery=level.getGameTime()+200;session.nextMaintenance=level.getGameTime()+RoyalConsolidationConfig.INTERVAL.get();}}
    public static void onStopped(ServerStoppedEvent event){SESSIONS.keySet().removeIf(l->l.getServer()==event.getServer());}
    public static void onTick(LevelTickEvent.Post event){if(!(event.getLevel() instanceof ServerLevel level))return;var s=SESSIONS.computeIfAbsent(level,l->new Session());long now=level.getGameTime();
        if(now>=s.nextRecovery){s.nextRecovery=now+200;recover(level);}
        if(now>=s.nextMaintenance){s.nextMaintenance=now+RoyalConsolidationConfig.INTERVAL.get();if(RoyalConsolidationConfig.ENABLED.get())step(level,RoyalConsolidationConfig.MAX_CAPITALS.get());}
    }
    public static Report step(ServerLevel level,int limit){
        thread(level);if(limit<1 || limit>16)throw new IllegalArgumentException("Invalid consolidation budget");long start=System.nanoTime();var s=SESSIONS.computeIfAbsent(level,l->new Session());var data=KingdomSavedData.get(level);var capitals=data.kingdoms().stream().filter(k->k.active() && !k.fallen()).sorted(Comparator.comparing(KingdomSavedData.Kingdom::id)).toList();int inspected=Math.min(limit,capitals.size()),done=0;
        if(!capitals.isEmpty()){int offset=0;if(s.capitalCursor!=null){while(offset<capitals.size() && capitals.get(offset).id().compareTo(s.capitalCursor)<=0)offset++;offset%=capitals.size();}var service=new RoyalConsolidationService(level);
            for(int n=0;n<inspected;n++){var k=capitals.get((offset+n)%capitals.size());s.capitalCursor=k.id();try{var result=service.consolidate(k.id());if(result.status()==RoyalConsolidationService.Status.COMMITTED)done++;s.warnings.remove("capital/"+k.id());}catch(RuntimeException failure){warn(level,s,"capital/"+k.id(),failure);}}
        }
        return s.last=new Report(inspected,done,System.nanoTime()-start);
    }
    private static void recover(ServerLevel level){
        var s=SESSIONS.computeIfAbsent(level,l->new Session());try{
            var service=new RoyalConsolidationService(level);var entries=service.current().stream().filter(e->e.intent().getString("dimension").equals(level.dimension().location().toString())).sorted(Comparator.comparing(RoyalPaymentJournal.Entry::id)).toList();if(entries.isEmpty())return;int start=0;
            if(s.recoveryCursor!=null){while(start<entries.size() && entries.get(start).id().compareTo(s.recoveryCursor)<=0)start++;start%=entries.size();}var entry=entries.get(start);s.recoveryCursor=entry.id();service.recover(entry.id());s.warnings.remove("recovery");
        }catch(RuntimeException failure){warn(level,s,"recovery",failure);}
    }
    public static Report last(ServerLevel level){var s=SESSIONS.get(level);return s==null?new Report(0,0,0):s.last;}
    private static void warn(ServerLevel level,Session s,String key,RuntimeException failure){long now=level.getGameTime();var last=s.warnings.get(key);if(last==null || now-last>=1200){s.warnings.put(key,now);LogUtils.getLogger().warn("[Kingdoms] Conserving treasury maintenance {} deferred; original evidence retained: {}",key,failure.getMessage());}}
    private static void thread(ServerLevel level){if(!level.getServer().isSameThread())throw new IllegalStateException("Treasury maintenance requires server thread");}
}
