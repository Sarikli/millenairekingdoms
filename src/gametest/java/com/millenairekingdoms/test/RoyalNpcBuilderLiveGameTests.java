package com.millenairekingdoms.test;

import com.millenairekingdoms.autonomy.AutonomyConfig;
import com.millenairekingdoms.autonomy.RoyalNpcConfig;
import com.millenairekingdoms.construction.RoyalConstructionConfig;
import com.millenairekingdoms.construction.RoyalConstructionService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Original full village/residents plus actual automatic economic selection and physical native construction/restart. */
@PrefixGameTestTemplate(false)
public final class RoyalNpcBuilderLiveGameTests {
    @GameTest(templateNamespace="millenairekingdoms_royal_npc_builder_tests",template="empty",timeoutTicks=120_000)
    public static void automaticEconomicCommissionUsesOriginalBuildersAndSurvivesPhysicalPartialRestart(GameTestHelper h){
        h.assertTrue(AutonomyConfig.current().autonomy() && RoyalNpcConfig.cadence().intervalTicks()==1200 && RoyalNpcConfig.cadence().maximumKingdoms()==2
                && RoyalConstructionConfig.TRACKING.get() && RoyalConstructionConfig.INTERVAL.get()==100 && RoyalConstructionConfig.MAX_PER_STEP.get()==2,"Original automatic NPC and construction tracking defaults are enabled");
        var probe=new RoyalRealConstructionGameTests.Probe(h,RoyalShipmentSupport.read(),new RoyalConstructionService(h.getLevel()),true,true);probe.kingdomName="Automatic NPC builders";
        h.onEachTick(()->{try{probe.tick();}catch(Exception failure){probe.close();throw new IllegalStateException("Automatic economic/native builder fixture: "+probe.diagnostic(),failure);}});
    }
    private RoyalNpcBuilderLiveGameTests(){}
}
