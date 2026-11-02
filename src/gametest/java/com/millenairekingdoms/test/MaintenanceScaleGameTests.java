package com.millenairekingdoms.test;

import com.millenairekingdoms.economy.RoyalConsolidationRuntime;
import com.millenairekingdoms.economy.RoyalConsolidationService;
import com.millenairekingdoms.history.HistoryFiles;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.village.*;

/** Cold metadata scheduler benchmark, not a500-populated-village or storage-commit TPS claim. */
@PrefixGameTestTemplate(false)
public final class MaintenanceScaleGameTests {
    @GameTest(templateNamespace="millenairekingdoms_maintenance_tests",template="empty",timeoutTicks=1200)
    public static void coldCapitalBudgetsRemainReadOnlyAndPersistTheirIdentities(GameTestHelper h)throws Exception{
        int maximumCapitals=Integer.getInteger("kingdoms.tests.scaleCapitals",50);if(maximumCapitals!=50 && maximumCapitals!=500)throw new IllegalArgumentException("Only documented50/500 metadata fixtures");var level=h.getLevel();var marker=level.getServer().getWorldPath(LevelResource.ROOT).resolve("maintenance-scale.dat");var forced=Set.copyOf(level.getForcedChunks());var data=KingdomSavedData.get(level);
        if(RoyalShipmentSupport.read()){
            var saved=HistoryFiles.read(marker);
            for(var raw:saved.getList("capitals",10)){
                var row=(CompoundTag)raw;var village=VillageSavedData.get(level).getVillageManager().getVillage(new VillageId(row.getUUID("village")));
                h.assertTrue(village!=null && data.kingdom(row.getUUID("kingdom")).capital().equals(village.getId().uuid()) && !level.isLoaded(village.getCenter()),"All saved native metadata/political identities reopen without making physical storage available");
            }
            h.assertTrue(!Files.exists(RoyalConsolidationService.directory(level)),"Read-only cold maintenance created no financial files");h.succeed();return;
        }
        var fixture=new PersonalityGameTests.Villages(h);var saved=new CompoundTag();var capitals=new ListTag();int count=0;
        for(int target:maximumCapitals==500?List.of(5,50,500):List.of(5,50)){
            while(count<target){var village=fixture.add("norman/artisans");var kingdom=fixture.kingdom(village);var row=new CompoundTag();row.putUUID("village",village.getId().uuid());row.putUUID("kingdom",kingdom.id());capitals.add(row);count++;}
            long total=0,maximum=0;int passes=(target+15)/16;
            for(int n=0;n<passes;n++){var report=RoyalConsolidationRuntime.step(level,16);total+=report.elapsedNanos();maximum=Math.max(maximum,report.elapsedNanos());h.assertTrue(report.inspected()<=16 && report.committed()==0,"Only the bounded metadata budget is visited, with no available physical treasury");}
            System.out.println("MAINTENANCE_METADATA_SCALE capitals="+target+" passes="+passes+" totalMicros="+total/1000+" maxPassMicros="+maximum/1000+" players="+level.players().size());
        }
        try(var input=RoyalConsolidationRuntime.class.getResourceAsStream("/com/millenairekingdoms/economy/RoyalConsolidationRuntime.class")){
            System.out.println("MAINTENANCE_RUNTIME_CLASS sha256="+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()))+" codeSource="+RoyalConsolidationRuntime.class.getProtectionDomain().getCodeSource().getLocation());
        }
        h.assertTrue(forced.equals(level.getForcedChunks()) && fixture.villages.stream().noneMatch(v->level.isLoaded(v.getCenter())) && !Files.exists(RoyalConsolidationService.directory(level)),"Cold metadata scanning never loads chunks or creates financial intents");saved.put("capitals",capitals);HistoryFiles.write(marker,saved);VillageSavedData.get(level).setDirty();data.setDirty();h.succeed();
    }
}
