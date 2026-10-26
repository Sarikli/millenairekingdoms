package com.millenairekingdoms.atlas;

import com.millenairekingdoms.history.HistoryFiles;
import com.millenairekingdoms.history.HistoryHooks;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiConsumer;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;

/** Persistent presentation identity only. Never reads terrain or changes political ownership. */
public final class AtlasColors {
    public static final int NEUTRAL=0xFF888888;
    private static final long MAX_BYTES=4_194_304;
    private static final Map<ServerLevel,AtlasColors> SESSIONS=new WeakHashMap<>();
    // All colors the existing candidate policy can choose:360 integral hues and its five saturation/value pairs.
    private static final int[] PALETTE=palette();
    private static final Map<Integer,Integer> PALETTE_INDEX=index();
    private final Path file;
    private final BiConsumer<Path,CompoundTag> writer;
    private final Map<UUID,Integer> colors=new TreeMap<>();
    private int[] nearest;
    private boolean reloadRequired;
    public AtlasColors(Path file){this(file,(path,tag)->HistoryFiles.write(path,tag,MAX_BYTES));}
    AtlasColors(Path file,BiConsumer<Path,CompoundTag> writer){
        this.file=file;this.writer=writer;
        if(!Files.exists(file))return;
        var tag=HistoryFiles.read(file,MAX_BYTES);HistoryFiles.keys(tag,Set.of("format","colors"));
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
        if(reloadRequired){
            var saved=new AtlasColors(file);
            if(colors.entrySet().stream().anyMatch(e->!Objects.equals(saved.colors.get(e.getKey()),e.getValue())))throw new IllegalStateException("Previously acknowledged atlas identities changed; preserve both observations");
            colors.clear();colors.putAll(saved.colors);nearest=null;
        }
        var missing=kingdoms.stream().distinct().filter(id->!colors.containsKey(id)).sorted().toList();
        if(missing.isEmpty() && !reloadRequired)return;
        if((long)colors.size()+missing.size()>AtlasSnapshotAssembler.MAX_SITES)throw new IllegalStateException("Atlas identity limit reached");
        var updated=new TreeMap<>(colors);int[] nextNearest=nearest;
        if(!missing.isEmpty()){
            nextNearest=nearest==null?distances(colors.values()):nearest.clone();
            for(var id:missing){
                int chosen=seedColor(id),best=-1;
                // Exactly the previous72-candidate/maximin/tie policy. Cached distances avoid quadratic full-registry scans.
                for(int n=0;n<72;n++){
                    int candidate=n==0?seedColor(id):rgb(Math.floorMod(id.hashCode()+n*137,360),n%2==0?.60:.48,n%3==0?.88:.74);
                    int distance=nextNearest[PALETTE_INDEX.get(candidate)];
                    if(distance>best){best=distance;chosen=candidate;}if(distance>=6400)break;
                }
                updated.put(id,chosen);include(nextNearest,chosen);
            }
        }
        var tag=new CompoundTag();tag.putInt("format",1);var list=new ListTag();
        updated.forEach((id,color)->{var e=new CompoundTag();e.putUUID("id",id);e.putInt("color",color);list.add(e);});tag.put("colors",list);
        try{writer.accept(file,tag);}catch(RuntimeException failure){reloadRequired=true;throw failure;}
        colors.clear();colors.putAll(updated);nearest=nextNearest;reloadRequired=false;
    }
    public int color(UUID id){if(reloadRequired)throw new IllegalStateException("Atlas color acknowledgement incomplete; retry allocation before publication");if(id==null)return NEUTRAL;var color=colors.get(id);if(color==null)throw new IllegalArgumentException("Unallocated political color");return color;}
    public static int seedColor(UUID id){
        long bits=id.getMostSignificantBits()^Long.rotateLeft(id.getLeastSignificantBits(),19);bits^=bits>>>33;bits*=0xff51afd7ed558ccdL;bits^=bits>>>33;
        return rgb(Math.floorMod(bits,360),.52,.78);
    }
    private static int[] palette(){var values=new LinkedHashSet<Integer>();for(int h=0;h<360;h++){values.add(rgb(h,.52,.78));for(double s:new double[]{.60,.48})for(double v:new double[]{.88,.74})values.add(rgb(h,s,v));}return values.stream().mapToInt(Integer::intValue).toArray();}
    private static Map<Integer,Integer> index(){var result=new HashMap<Integer,Integer>();for(int n=0;n<PALETTE.length;n++)result.put(PALETTE[n],n);return Map.copyOf(result);}
    private static int[] distances(Collection<Integer> colors){var values=new int[PALETTE.length];Arrays.fill(values,Integer.MAX_VALUE);for(int color:colors)include(values,color);return values;}
    private static void include(int[] nearest,int color){for(int n=0;n<PALETTE.length;n++)nearest[n]=Math.min(nearest[n],distance(PALETTE[n],color));}
    private static int distance(int a,int b){int r=(a>>16&255)-(b>>16&255),g=(a>>8&255)-(b>>8&255),v=(a&255)-(b&255);return r*r+g*g+v*v;}
    private static int rgb(double degrees,double s,double v){double hue=degrees/60,f=hue-(int)hue,p=v*(1-s),q=v*(1-s*f),t=v*(1-s*(1-f));
        double[] c=switch((int)hue){case 0->new double[]{v,t,p};case 1->new double[]{q,v,p};case 2->new double[]{p,v,t};case 3->new double[]{p,q,v};case 4->new double[]{t,p,v};default->new double[]{v,p,q};};
        return 0xFF000000|((int)Math.round(c[0]*255)<<16)|((int)Math.round(c[1]*255)<<8)|(int)Math.round(c[2]*255);
    }
}
