package com.millenairekingdoms.atlas;

import java.util.*;

/** Bounded screen-space collision layout; markers remain visible/selectable even when a label is omitted. */
public final class AtlasLabels {
    public record Box(int left,int top,int right,int bottom){
        public boolean overlaps(Box b){return left<b.right && right>b.left && top<b.bottom && bottom>b.top;}
    }
    private final Map<Long,List<Box>> grid=new HashMap<>();
    public void reserve(Box box){
        for(int x=Math.floorDiv(box.left,64);x<=Math.floorDiv(box.right,64);x++)for(int y=Math.floorDiv(box.top,64);y<=Math.floorDiv(box.bottom,64);y++)grid.computeIfAbsent(key(x,y),k->new ArrayList<>()).add(box);
    }
    public boolean add(Box box){
        for(int x=Math.floorDiv(box.left,64);x<=Math.floorDiv(box.right,64);x++)for(int y=Math.floorDiv(box.top,64);y<=Math.floorDiv(box.bottom,64);y++)
            if(grid.getOrDefault(key(x,y),List.of()).stream().anyMatch(box::overlaps))return false;
        reserve(box);
        return true;
    }
    private static long key(int x,int y){return (long)x<<32|(y&0xffffffffL);}
    public AtlasLabels(){}
}
