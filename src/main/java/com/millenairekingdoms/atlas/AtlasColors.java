package com.millenairekingdoms.atlas;

import com.millenairekingdoms.history.HistoryFiles;
import com.millenairekingdoms.history.HistoryHooks;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;

/** Persistent presentation identity only. Never reads terrain or changes political ownership. */
public final class AtlasColors {
    public static final int NEUTRAL=0xFF888888;
    private static final Map<ServerLevel,AtlasColors> SESSIONS=new WeakHashMap<>();
    private final Path file;
    private final Map<UUID,Integer> colors=new TreeMap<>();
    public AtlasColors(Path file){
        this.file=file;
        if(!Files.exists(file))return;
        var tag=HistoryFiles.read(file);HistoryFiles.keys(tag,Set.of("format","colors"));
        if(tag.getInt("format")!=1 || !tag.contains("colors",Tag.TAG_LIST))throw new IllegalArgumentException("Unsupported atlas colors; original file preserved");
        var list=(ListTag)tag.get("colors");
        if(!list.isEmpty() && list.getElementType()!=Tag.TAG_COMPOUND)throw new IllegalArgumentException("Invalid atlas color list");
        if(list.size()>AtlasSnapshotAssembler.MAX_SITES)throw new IllegalArgumentException("Too many atlas identities");
        for(var raw:list){var entry=(CompoundTag)raw;HistoryFiles.keys(entry,Set.of("id","color"));
            if(!entry.hasUUID("id") || !entry.contains("color",Tag.TAG_INT) || entry.getInt("color")>>>24!=255 || colors.putIfAbsent(entry.getUUID("id"),entry.getInt("color"))!=null)throw new IllegalArgumentException("Invalid atlas identity; original file preserved");
        }
    }
    public static AtlasColors get(ServerLevel level){
        if(!level.getServer().isSameThread())throw new IllegalStateException("Atlas colors require server thread");
        return SESSIONS.computeIfAbsent(level,l->new AtlasColors(HistoryHooks.directory(l).resolve("political-colors.dat")));
    }
    /** Allocate all registered kingdoms, not just this viewer's visible subset. Established colors never change. */
    public void ensure(Collection<UUID> kingdoms){
        var updated=new TreeMap<>(colors);
        for(var id:kingdoms.stream().sorted().toList())if(!updated.containsKey(id)){
            if(updated.size()>=AtlasSnapshotAssembler.MAX_SITES)throw new IllegalStateException("Atlas identity limit reached");
            int chosen=seedColor(id),best=-1;
            // Deterministic maximin choice. A crowded palette may repeat close colors, never recolors old identities.
            for(int n=0;n<72;n++){
                int candidate=n==0?seedColor(id):rgb(Math.floorMod(id.hashCode()+n*137,360),n%2==0?.60:.48,n%3==0?.88:.74);
                int distance=updated.values().stream().mapToInt(c->distance(c,candidate)).min().orElse(Integer.MAX_VALUE);
                if(distance>best){best=distance;chosen=candidate;}if(distance>=6400)break;
            }
            updated.put(id,chosen);
        }
        if(updated.equals(colors))return;
        var tag=new CompoundTag();tag.putInt("format",1);var list=new ListTag();
        updated.forEach((id,color)->{var e=new CompoundTag();e.putUUID("id",id);e.putInt("color",color);list.add(e);});tag.put("colors",list);
        HistoryFiles.write(file,tag);colors.clear();colors.putAll(updated);
    }
    public int color(UUID id){if(id==null)return NEUTRAL;var color=colors.get(id);if(color==null)throw new IllegalArgumentException("Unallocated political color");return color;}
    public static int seedColor(UUID id){
        long bits=id.getMostSignificantBits()^Long.rotateLeft(id.getLeastSignificantBits(),19);bits^=bits>>>33;bits*=0xff51afd7ed558ccdL;bits^=bits>>>33;
        return rgb(Math.floorMod(bits,360),.52,.78);
    }
    private static int distance(int a,int b){int r=(a>>16&255)-(b>>16&255),g=(a>>8&255)-(b>>8&255),v=(a&255)-(b&255);return r*r+g*g+v*v;}
    private static int rgb(double degrees,double s,double v){double hue=degrees/60,f=hue-(int)hue,p=v*(1-s),q=v*(1-s*f),t=v*(1-s*(1-f));
        double[] c=switch((int)hue){case 0->new double[]{v,t,p};case 1->new double[]{q,v,p};case 2->new double[]{p,v,t};case 3->new double[]{p,q,v};case 4->new double[]{t,p,v};default->new double[]{v,p,q};};
        return 0xFF000000|((int)Math.round(c[0]*255)<<16)|((int)Math.round(c[1]*255)<<8)|(int)Math.round(c[2]*255);
    }
}
