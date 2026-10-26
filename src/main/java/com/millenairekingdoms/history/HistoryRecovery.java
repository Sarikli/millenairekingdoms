package com.millenairekingdoms.history;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Own bounded outbox rotation, never financial/domain replay, native entity work or an archive/chunk scan. */
public final class HistoryRecovery {
    private static final Map<ServerLevel,Session> SESSIONS=new WeakHashMap<>();
    private static final class Session{String cursor="";long next;final Map<Path,Long> warnings=new HashMap<>();}
    private HistoryRecovery(){}
    public static void onTick(LevelTickEvent.Post event){if(!(event.getLevel() instanceof ServerLevel level))return;var session=SESSIONS.computeIfAbsent(level,l->new Session());if(level.getGameTime()<session.next)return;session.next=level.getGameTime()+200;recover(level,4);}
    public static void clear(net.minecraft.server.MinecraftServer server){SESSIONS.keySet().removeIf(l->l.getServer()==server);}
    public static int recover(ServerLevel level,int budget){
        var session=SESSIONS.computeIfAbsent(level,l->new Session());var paths=new ArrayList<Path>();
        for(var name:List.of("pending","candidates","domain-pending")){var folder=HistoryHooks.directory(level).resolve(name);if(!Files.exists(folder))continue;
            try(var files=Files.list(folder)){paths.addAll(files.filter(p->p.toString().endsWith(".dat")).limit(name.equals("candidates")?257:129).toList());}catch(IOException e){LogUtils.getLogger().warn("[Kingdoms] History outbox directory unavailable; originals preserved: {}",folder);}
        }
        paths.sort(Comparator.comparing(p->p.getParent().getFileName()+"/"+p.getFileName()));if(paths.isEmpty()){session.warnings.clear();return 0;}
        session.warnings.keySet().retainAll(new HashSet<>(paths));int start=0;while(start<paths.size() && key(paths.get(start)).compareTo(session.cursor)<=0)start++;start%=paths.size();int count=Math.min(Math.max(0,budget),paths.size());
        for(int n=0;n<count;n++){var path=paths.get((start+n)%paths.size());session.cursor=key(path);try{HistoryHooks.recoverOne(level,path);session.warnings.remove(path);}catch(RuntimeException failure){long now=level.getGameTime();var last=session.warnings.get(path);if(last==null || now-last>=1200){session.warnings.put(path,now);LogUtils.getLogger().warn("[Kingdoms] History outbox retained without gameplay replay: {} ({})",path.getFileName(),failure.getMessage());}}}return count;
    }
    private static String key(Path p){return p.getParent().getFileName()+"/"+p.getFileName();}
}
