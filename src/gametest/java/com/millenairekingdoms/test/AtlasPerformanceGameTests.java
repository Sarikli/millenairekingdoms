package com.millenairekingdoms.test;

import com.millenairekingdoms.atlas.AtlasColors;
import com.millenairekingdoms.history.HistoryHooks;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Actual production allocator and persisted file, no graphical FPS claim or timing-based flaky pass criterion. */
@PrefixGameTestTemplate(false)
public final class AtlasPerformanceGameTests {
    @GameTest(templateNamespace="millenairekingdoms_atlas_tests",template="empty",timeoutTicks=1200)
    public static void largeColorRegistryKeepsExactIdentityAndMeasuresColdWarmCost(GameTestHelper h)throws Exception{
        var file=HistoryHooks.directory(h.getLevel()).resolve("color-performance-"+UUID.randomUUID()+".dat");var colors=new AtlasColors(file);var ids=new ArrayList<UUID>();for(int n=0;n<8192;n++)ids.add(new UUID(200,n));
        long start=System.nanoTime();colors.ensure(ids.subList(0,500));long fiveHundred=System.nanoTime()-start;try(var input=AtlasPerformanceGameTests.class.getResourceAsStream("/atlas-colors-stage5-500.dat")){var golden=net.minecraft.nbt.NbtIo.readCompressed(input,net.minecraft.nbt.NbtAccounter.create(1_048_576));var list=golden.getList("colors",10);h.assertTrue(list.size()==500,"Golden is the genuine old allocator500-identity persisted file");for(var raw:list){var e=(net.minecraft.nbt.CompoundTag)raw;h.assertTrue(colors.color(e.getUUID("id"))==e.getInt("color"),"Optimized allocator exactly preserves previous deterministic selection/tie policy");}}var existing=new HashMap<UUID,Integer>();for(var id:ids.subList(0,500))existing.put(id,colors.color(id));start=System.nanoTime();colors.ensure(ids);long large=System.nanoTime()-start;start=System.nanoTime();colors.ensure(ids);long warm=System.nanoTime()-start;var reopen=new AtlasColors(file);
        h.assertTrue(existing.entrySet().stream().allMatch(e->colors.color(e.getKey())==e.getValue() && reopen.color(e.getKey())==e.getValue()),"Growing registry to8192 never changes any established political color and saved registry reopens exactly");for(var id:ids)h.assertTrue(colors.color(id)==reopen.color(id),"Every supported registered identity persists without terrain access");
        System.out.println("ATLAS_COLOR_PERFORMANCE registry500Micros="+fiveHundred/1000+" grow8192Micros="+large/1000+" unchanged8192Micros="+warm/1000+" exactPersisted=true");h.succeed();
    }
    @GameTest(templateNamespace="millenairekingdoms_atlas_tests",template="empty",timeoutTicks=1200)
    public static void failedAcknowledgementReloadsDurableColorsBeforeAllocatingAnotherIdentity(GameTestHelper h)throws Exception{
        var seen=new HashMap<Integer,UUID>();UUID low=null,high=null;for(int n=1;n<2000;n++){var id=new UUID(201,n);var old=seen.putIfAbsent(AtlasColors.seedColor(id),id);if(old!=null){low=old;high=id;break;}}h.assertTrue(low!=null,"A real candidate seed collision exists");var file=HistoryHooks.directory(h.getLevel()).resolve("color-lost-ack-"+UUID.randomUUID()+".dat");var failures=new java.util.concurrent.atomic.AtomicInteger();java.util.function.BiConsumer<java.nio.file.Path,net.minecraft.nbt.CompoundTag> writer=(p,t)->{com.millenairekingdoms.history.HistoryFiles.write(p,t,4_194_304);if(failures.getAndIncrement()==0)throw new IllegalStateException("TEST lost acknowledgement AFTER actual atomic color rename");};var constructor=AtlasColors.class.getDeclaredConstructor(java.nio.file.Path.class,java.util.function.BiConsumer.class);constructor.setAccessible(true);var colors=constructor.newInstance(file,writer);var first=high;HistoryGameTests.refused(h,()->colors.ensure(List.of(first)));int committed=new AtlasColors(file).color(high);HistoryGameTests.refused(h,()->colors.color(first));colors.ensure(List.of(low,high));h.assertTrue(colors.color(high)==committed && colors.color(low)!=committed && new AtlasColors(file).color(high)==committed,"Retry reloads the durable higher UUID before inserting a lower colliding UUID; no unacknowledged publication or persisted recoloring");h.succeed();
    }

}
