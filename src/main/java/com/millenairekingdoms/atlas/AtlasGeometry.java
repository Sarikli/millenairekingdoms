package com.millenairekingdoms.atlas;

import java.util.*;
import net.minecraft.resources.ResourceLocation;

/** Visual influence only: clipped regular polygons, nearest-settlement half-planes, no world/chunk/AI references. */
public final class AtlasGeometry {
    public static final int MAX_SITES=8192, SIDES=48;
    public record Site(UUID id,UUID kingdom,int x,int z){public Site{if(id==null || Math.abs((long)x)>30_000_000 || Math.abs((long)z)>30_000_000)throw new IllegalArgumentException("Invalid atlas site");}}
    public record Point(double x,double z){}
    public record Cell(Site site,List<Point> polygon){public Cell{polygon=List.copyOf(polygon);}}
    public record Border(Point start,Point end,UUID kingdom){}
    public record Mesh(List<Cell> cells,List<Border> borders){public Mesh{cells=List.copyOf(cells);borders=List.copyOf(borders);}}
    private record CacheKey(String dimension,int radius,List<Site> sites){}
    private static final Map<CacheKey,Mesh> CACHE=new LinkedHashMap<>(8,.75f,true);
    private AtlasGeometry(){}
    public static synchronized Mesh cached(String dimension,int radius,List<Site> sites){
        if(ResourceLocation.tryParse(dimension)==null || radius<64 || radius>4096 || sites.size()>MAX_SITES)throw new IllegalArgumentException("Atlas geometry outside supported bounds");
        var ordered=sites.stream().sorted(Comparator.comparing(Site::id)).toList();if(ordered.stream().map(Site::id).distinct().count()!=ordered.size())throw new IllegalArgumentException("Duplicate atlas village");
        var key=new CacheKey(dimension,radius,ordered);var mesh=CACHE.get(key);if(mesh!=null)return mesh;mesh=build(radius,ordered);CACHE.put(key,mesh);while(CACHE.size()>8)CACHE.remove(CACHE.keySet().iterator().next());return mesh;
    }
    private static Mesh build(int radius,List<Site> sites){
        int size=radius*2;var grid=new HashMap<Long,List<Site>>();for(var site:sites)grid.computeIfAbsent(bucket(Math.floorDiv(site.x,size),Math.floorDiv(site.z,size)),k->new ArrayList<>()).add(site);
        var cells=new ArrayList<Cell>();var byId=new HashMap<UUID,Cell>();var neighbors=new HashMap<UUID,List<Site>>();
        for(var site:sites){
            var nearby=new ArrayList<Site>();int gx=Math.floorDiv(site.x,size),gz=Math.floorDiv(site.z,size);
            for(int x=gx-1;x<=gx+1;x++)for(int z=gz-1;z<=gz+1;z++)for(var other:grid.getOrDefault(bucket(x,z),List.of()))if(!site.id.equals(other.id) && Math.hypot((double)site.x-other.x,(double)site.z-other.z)<=size)nearby.add(other);
            if(nearby.size()>512)throw new IllegalArgumentException("Atlas influence crowding exceeds bounded geometry budget");
            nearby.sort(Comparator.comparingDouble((Site other)->Math.hypot((double)site.x-other.x,(double)site.z-other.z)).thenComparing(Site::id));neighbors.put(site.id,List.copyOf(nearby));
            var polygon=new ArrayList<Point>();for(int n=0;n<SIDES;n++){double angle=2*Math.PI*n/SIDES;polygon.add(new Point(radius*Math.cos(angle),radius*Math.sin(angle)));}
            for(var other:nearby){double dx=(double)other.x-site.x,dz=(double)other.z-site.z;
                if(dx==0 && dz==0){if(site.id.compareTo(other.id)>0){polygon.clear();break;}continue;}
                polygon=clip(polygon,dx,dz,(dx*dx+dz*dz)/2);if(polygon.isEmpty())break;
            }
            var absolute=polygon.stream().map(p->new Point(p.x+site.x,p.z+site.z)).toList();var cell=new Cell(site,absolute);cells.add(cell);byId.put(site.id,cell);
        }
        var borders=new ArrayList<Border>();
        for(var cell:cells){var polygon=cell.polygon;for(int n=0;n<polygon.size();n++){
            var a=polygon.get(n);var b=polygon.get((n+1)%polygon.size());double dx=b.x-a.x,dz=b.z-a.z,length=Math.hypot(dx,dz);if(length<1e-7)continue;
            var outside=new Point((a.x+b.x)/2+dz/length*.05,(a.z+b.z)/2-dx/length*.05);boolean internal=false;
            UUID owner=owner(cell.site);
            for(var other:neighbors.get(cell.site.id))if(owner.equals(owner(other)) && contains(byId.get(other.id).polygon,outside)){internal=true;break;}
            if(!internal)borders.add(new Border(a,b,cell.site.kingdom));
        }}
        return new Mesh(cells,borders);
    }
    private static UUID owner(Site site){return site.kingdom==null?site.id:site.kingdom;}
    private static long bucket(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
    private static ArrayList<Point> clip(List<Point> polygon,double dx,double dz,double boundary){
        var result=new ArrayList<Point>();if(polygon.isEmpty())return result;
        var previous=polygon.getLast();double pv=dx*previous.x+dz*previous.z-boundary;
        for(var current:polygon){double cv=dx*current.x+dz*current.z-boundary;boolean pi=pv<=1e-7,ci=cv<=1e-7;
            if(pi!=ci){double t=pv/(pv-cv);result.add(new Point(previous.x+(current.x-previous.x)*t,previous.z+(current.z-previous.z)*t));}if(ci)result.add(current);previous=current;pv=cv;
        }return result;
    }
    public static boolean contains(List<Point> polygon,Point point){if(polygon.size()<3)return false;for(int n=0;n<polygon.size();n++){var a=polygon.get(n);var b=polygon.get((n+1)%polygon.size());if((b.x-a.x)*(point.z-a.z)-(b.z-a.z)*(point.x-a.x)<-1e-6)return false;}return true;}
    public static int color(UUID kingdom){
        if(kingdom==null)return 0xFF888477;
        long bits=kingdom.getMostSignificantBits()^Long.rotateLeft(kingdom.getLeastSignificantBits(),19);bits^=bits>>>33;bits*=0xff51afd7ed558ccdL;bits^=bits>>>33;
        double hue=Math.floorMod(bits,360)/60.0,s=.52,v=.78;int sector=(int)hue;double f=hue-sector,p=v*(1-s),q=v*(1-s*f),t=v*(1-s*(1-f));
        double[] rgb=switch(sector){case 0->new double[]{v,t,p};case 1->new double[]{q,v,p};case 2->new double[]{p,v,t};case 3->new double[]{p,q,v};case 4->new double[]{t,p,v};default->new double[]{v,p,q};};
        return 0xFF000000|((int)Math.round(rgb[0]*255)<<16)|((int)Math.round(rgb[1]*255)<<8)|(int)Math.round(rgb[2]*255);
    }
}
