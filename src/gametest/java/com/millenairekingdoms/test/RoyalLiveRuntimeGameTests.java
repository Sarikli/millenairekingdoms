package com.millenairekingdoms.test;

import com.millenairekingdoms.construction.RoyalConstructionConfig;
import com.millenairekingdoms.construction.RoyalConstructionService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Isolated actual LevelTick event gameplay. No explicit scheduler step or observer drives this fixture. */
@PrefixGameTestTemplate(false)
public final class RoyalLiveRuntimeGameTests {
    @GameTest(templateNamespace="millenairekingdoms_royal_live_tests",template="empty",timeoutTicks=120_000)
    public static void scheduledRealNpcConstructionPersistsAndFinishesAcrossSeparateJvm(GameTestHelper h) {
        h.assertTrue(RoyalConstructionConfig.TRACKING.get() && RoyalConstructionConfig.INTERVAL.get()==100
                && RoyalConstructionConfig.MAX_PER_STEP.get()==2,"Isolated event gameplay uses actual enabled production default tracking settings");
        System.out.println("ROYAL_LIVE_RUNTIME_SETTINGS enabled="+RoyalConstructionConfig.TRACKING.get()+" interval="+RoyalConstructionConfig.INTERVAL.get()+" budget="+RoyalConstructionConfig.MAX_PER_STEP.get());
        var probe=new RoyalRealConstructionGameTests.Probe(h,"read".equals(System.getProperty("kingdoms.tests.persistence")),new RoyalConstructionService(h.getLevel()),true);
        h.onEachTick(()->{
            try {probe.tick();}
            catch(Exception failure) {probe.close();throw new IllegalStateException("Actual scheduled runtime fixture: "+probe.diagnostic(),failure);}
        });
    }
}
