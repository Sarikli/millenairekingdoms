package com.millenairekingdoms.construction;

import com.millenairekingdoms.construction.RoyalConstructionStore.Stage;
import com.millenairekingdoms.construction.RoyalConstructionStore.Blocked;
import com.mojang.logging.LogUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.slf4j.Logger;

/** Bounded round-robin existing-order recovery. Never invents a new NPC commission or forces remote chunks. */
public final class RoyalConstructionRuntime {
    private static final Logger LOGGER=LogUtils.getLogger();
    private static final Map<ServerLevel,Session> SESSIONS=new WeakHashMap<>();
    private static final class Session {
        UUID cursor;
        long nextTick;
        final Map<UUID,Long> diagnostics=new HashMap<>();
    }
    private RoyalConstructionRuntime() {}

    public static void onStarted(ServerStartedEvent event) {
        for(var level:event.getServer().getAllLevels()) {
            if(!Files.exists(directory(level).resolve("orders.dat"))) continue;
            try {
                int count=new RoyalConstructionService(level).quarantineInterruptedPublications();
                if(count>0) LOGGER.warn("[Kingdoms] Preserved {} interrupted royal publications in {} for explicit recovery; no refund/reissue or engine deletion.",count,level.dimension().location());
            } catch(RuntimeException failure) {
                // No economic work is allowed when the original index cannot be inspected. Preserve it and diagnose.
                var session=SESSIONS.computeIfAbsent(level,key->new Session()); session.nextTick=Long.MAX_VALUE;
                LOGGER.error("[Kingdoms] Royal construction startup recovery failed in {}; tracking remains paused; original data preserved.",level.dimension().location(),failure);
            }
        }
    }
    public static void onLevelTick(LevelTickEvent.Post event) {
        if(!(event.getLevel() instanceof ServerLevel level) || !RoyalConstructionConfig.TRACKING.get()) return;
        long now=level.getGameTime(); var session=SESSIONS.computeIfAbsent(level,key->new Session());
        if(now<session.nextTick) return;
        session.nextTick=now+RoyalConstructionConfig.INTERVAL.get();
        if(!Files.exists(directory(level).resolve("orders.dat"))) return;
        try { step(level,RoyalConstructionConfig.MAX_PER_STEP.get()); }
        catch(RuntimeException failure) {
            session.nextTick=Math.max(session.nextTick,now+1200);
            LOGGER.error("[Kingdoms] Royal construction index unavailable in {}; retry delayed; no speculative state reset.",level.dimension().location(),failure);
        }
    }

    /** Immediate bounded step for the same server scheduler, also exercised directly by integration tests. */
    public static int step(ServerLevel level,int limit) {
        if(!level.getServer().isSameThread() || limit<1 || limit>16) throw new IllegalArgumentException("Invalid server construction tracking budget");
        if(!Files.exists(directory(level).resolve("orders.dat"))) return 0;
        var orders=new RoyalConstructionStore(directory(level)); String dimension=level.dimension().location().toString();
        // A persisted OP decision replaces its order's ordinary job, sharing the same total budget and cursor.
        var decisions=new HashMap<UUID,UUID>();
        if(Files.exists(RoyalRecoveryService.directory(level).resolve("decisions.dat"))) {
            for(var decision:new RoyalRecoveryStore(RoyalRecoveryService.directory(level)).entries()) {
                if(decision.state()==RoyalRecoveryStore.State.AUTHORIZED && decision.intent().order().dimension().equals(dimension))
                    decisions.put(decision.intent().order().id(),decision.intent().id());
            }
        }
        var entries=orders.entries().stream().filter(e->e.intent().dimension().equals(dimension)
                        && (decisions.containsKey(e.intent().id()) || !e.terminal() && e.stage()!=Stage.RECOVERY_REQUIRED))
                .sorted(Comparator.comparing(e->e.intent().id())).toList();
        var session=SESSIONS.computeIfAbsent(level,key->new Session());
        session.diagnostics.keySet().retainAll(entries.stream().map(e->e.intent().id()).collect(java.util.stream.Collectors.toSet()));
        if(entries.isEmpty()) return 0;
        int start=0;
        if(session.cursor!=null) {
            while(start<entries.size() && entries.get(start).intent().id().compareTo(session.cursor)<=0) start++;
            start%=entries.size();
        }
        var service=new RoyalConstructionService(level); int count=Math.min(limit,entries.size());
        for(int offset=0;offset<count;offset++) {
            var entry=entries.get((start+offset)%entries.size()); UUID id=entry.intent().id(); session.cursor=id;
            try {
                if(decisions.containsKey(id)) {
                    new RoyalRecoveryService(level).resumeAuthorized(decisions.get(id)); session.diagnostics.remove(id); continue;
                }
                entry=service.observe(id);
                if(entry.stage()==Stage.CANCELLING && entry.blocked()==Blocked.AUTHORITY) service.settleRetired(id);
                else if(entry.stage()==Stage.REQUESTED || entry.stage()==Stage.PAID || entry.stage()==Stage.QUEUING || entry.stage()==Stage.CANCELLING) {
                    if(entry.intent().actor()==null) service.retryNpc(entry.intent().kingdom(),id);
                    else {
                        var player=level.getServer().getPlayerList().getPlayer(entry.intent().actor());
                        if(player!=null && player.serverLevel()==level) service.retryPlayer(player,id);
                    }
                }
                session.diagnostics.remove(id);
            } catch(RuntimeException failure) {
                long now=level.getGameTime(); Long last=session.diagnostics.get(id);
                if(last==null || now-last>=1200) {
                    session.diagnostics.put(id,now);
                    LOGGER.warn("[Kingdoms] Royal construction {} remains pending ({}): {}",id,entry.stage(),failure.getMessage());
                }
            }
        }
        return count;
    }
    public static void onStopped(ServerStoppedEvent event) {
        SESSIONS.keySet().removeIf(level->level.getServer()==event.getServer());
        RoyalConstructionService.clearRuntime(event.getServer());
    }
    private static Path directory(ServerLevel level) { return level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms/royal-construction"); }
}
