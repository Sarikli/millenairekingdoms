package com.millenairekingdoms.client;

import com.millenairekingdoms.atlas.geography.GeographySnapshot;
import net.minecraft.client.Minecraft;

/** One bounded observed cache across retained atlas/history screens, scoped to the live connection/dimension. */
final class ClientGeography {
    private static final GeographySnapshot SNAPSHOT=new GeographySnapshot();
    private static java.lang.ref.WeakReference<Object> connection=new java.lang.ref.WeakReference<>(null);
    private ClientGeography(){}
    static GeographySnapshot snapshot(){
        var minecraft=Minecraft.getInstance();var current=minecraft.getConnection();String dimension=minecraft.level==null?"minecraft:overworld":minecraft.level.dimension().location().toString();
        if(current==null || connection.get()!=current || !SNAPSHOT.dimension().equals(dimension)){
            SNAPSHOT.clear(dimension);connection=new java.lang.ref.WeakReference<>(current);
        }
        return SNAPSHOT;
    }
}
