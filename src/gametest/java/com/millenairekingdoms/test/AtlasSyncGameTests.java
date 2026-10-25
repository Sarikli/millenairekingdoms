package com.millenairekingdoms.test;

import com.millenairekingdoms.atlas.*;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Snapshot-model tests; no graphical rendering/input claim. */
@PrefixGameTestTemplate(false)
public final class AtlasSyncGameTests {
    private static final String NS="millenairekingdoms_atlas_tests";
    private static final UUID REV=new UUID(88,1);
    static CompoundTag page(int first,int count,int total,boolean more){
        var t=new CompoundTag();t.putString("revision",REV.toString());t.putString("dimension","minecraft:overworld");t.putInt("radius",500);t.putInt("total",total);t.putLong("observedTime",100);
        var sites=new ListTag();for(int n=first;n<first+count;n++){var s=new CompoundTag();s.putUUID("id",new UUID(89,n));s.putInt("x",n*100);s.putInt("z",0);for(var key:List.of("name","typeName"))s.putString(key,"Village "+n);s.putString("type","millenaire:norman/agricole");s.putString("culture","millenaire:norman");s.putString("ethos","AGRICULTURAL");s.putString("icon","minecraft:wheat");s.putBoolean("capital",false);sites.add(s);}t.put("settlements",sites);t.put("kingdoms",new ListTag());t.put("wars",new ListTag());if(more)t.putUUID("next",new UUID(89,first+count-1));return t;
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void partialPagesNeverBecomeARenderableMixedFrame(GameTestHelper h){
        var assembler=new AtlasSnapshotAssembler();var first=page(1,2,3,true);h.assertTrue(assembler.accept(first,null)==null,"No frame is published while a revision is incomplete");var frame=assembler.accept(page(3,1,3,false),new UUID(89,2));
        h.assertTrue(frame!=null && frame.settlements().size()==3 && frame.radius()==500 && frame.revision().equals(REV.toString()),"Exactly one complete revision becomes renderable");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void changedRevisionDimensionOrCursorRequiresFreshAssembly(GameTestHelper h){
        for(int failure=0;failure<3;failure++){var assembler=new AtlasSnapshotAssembler();assembler.accept(page(1,2,3,true),null);var changed=page(3,1,3,false);if(failure==0)changed.putString("revision",UUID.randomUUID().toString());if(failure==1)changed.putString("dimension","minecraft:the_nether");UUID cursor=failure==2?new UUID(89,1):new UUID(89,2);HistoryGameTests.refused(h,()->assembler.accept(changed,cursor));}
        h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void duplicatesTruncationAndOversizedCoordinatesCannotReachTheRenderer(GameTestHelper h){
        var duplicate=page(1,2,2,false);duplicate.getList("settlements",10).getCompound(1).putUUID("id",new UUID(89,1));HistoryGameTests.refused(h,()->new AtlasSnapshotAssembler().accept(duplicate,null));
        HistoryGameTests.refused(h,()->new AtlasSnapshotAssembler().accept(page(1,1,2,false),null));var far=page(1,1,1,false);far.getList("settlements",10).getCompound(0).putInt("x",30_000_001);HistoryGameTests.refused(h,()->new AtlasSnapshotAssembler().accept(far,null));h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void completeFrameHasNoAliasesToPagesOrReturnedTags(GameTestHelper h){
        var page=page(1,1,1,false);var frame=new AtlasSnapshotAssembler().accept(page,null);page.getList("settlements",10).getCompound(0).putInt("x",999);frame.settlements().getFirst().putInt("x",888);
        h.assertTrue(frame.settlements().getFirst().getInt("x")==100,"Immutable frame protects accepted coordinates from partial buffers/UI mutation");h.succeed();
    }
}
