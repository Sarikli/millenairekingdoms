package com.millenairekingdoms.test;

import com.millenairekingdoms.autonomy.AutonomyConfig;
import com.millenairekingdoms.autonomy.AutonomyService;
import com.millenairekingdoms.autonomy.RoyalInvestmentPolicy;
import com.millenairekingdoms.autonomy.RoyalInvestmentPolicy.Role;
import com.millenairekingdoms.autonomy.RoyalNpcConfig;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.personality.KingdomPersonality;
import com.millenairekingdoms.personality.KingdomPersonality.Category;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.culture.ModCultures;

/** Explicit plan metadata/functions and real war/profile context. This primitive gate claims no NPC economic purchase. */
@PrefixGameTestTemplate(false)
public final class RoyalPolicyGameTests {
    private static final String NS="millenairekingdoms_royal_policy_tests";
    private static final String[] TYPES={"norman/militaire","norman/agricole","norman/ecclesiastique","norman/artisans","norman/grosbourg"};
    private static ResourceLocation id(String path){return ResourceLocation.fromNamespaceAndPath("millenaire",path);}
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void everyExplicitInvestmentFunctionExistsInTheActualPinnedNativeCatalog(GameTestHelper h){
        var cultures=new java.util.HashSet<ResourceLocation>();var counts=new java.util.EnumMap<Role,Integer>(Role.class);var invalid=new java.util.ArrayList<ResourceLocation>();
        for(var entry:RoyalInvestmentPolicy.buildings().entrySet()){
            var set=ModCultures.getBuildingPlanSet(entry.getKey());
            if(set==null || set.isTownHall() || set.isSubBuilding() || set.parentBuilding()!=null)invalid.add(entry.getKey());
            if(set!=null)cultures.add(set.culture());counts.merge(entry.getValue(),1,Integer::sum);
        }
        h.assertTrue(invalid.isEmpty(),"Every explicit function must be a real top-level non-townhall native set; invalid="+invalid);
        h.assertTrue(cultures.size()==7 && counts.keySet().containsAll(List.of(Role.MILITARY,Role.AGRICULTURAL,Role.RELIGIOUS,Role.ARTISAN,Role.CIVIC)),"Actual original seven-culture mapping covers all functional investment classes");
        h.assertTrue(RoyalInvestmentPolicy.role(id("unknown_guard_farm_mosque"))==Role.UNCLASSIFIED && RoyalInvestmentPolicy.weight(Category.MILITARY,Role.UNCLASSIFIED,true,true,RoyalNpcConfig.weights())==0,"Unknown content is never classified or commissioned by misleading name substrings");
        System.out.println("ROYAL_POLICY_NATIVE_FUNCTIONS count="+RoyalInvestmentPolicy.buildings().size()+" roles="+counts);h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void nativeRaiderOrPatrolTagsDoNotMisclassifyProductiveLumbermenAndForges(GameTestHelper h){
        h.assertTrue(ModCultures.getVillagerType(id("norman/lumberman")).isRaider() && ModCultures.getBuildingPlanSet(id("norman/forge")).hasTag("patrol"),"Original native data actually contains these ambiguous military-looking flags");
        h.assertTrue(RoyalInvestmentPolicy.role(id("norman/lumbermanhut"))==Role.ARTISAN && RoyalInvestmentPolicy.role(id("norman/forge"))==Role.ARTISAN
                && RoyalInvestmentPolicy.role(id("norman/guardhouse"))==Role.MILITARY && RoyalInvestmentPolicy.role(id("norman/armoury"))==Role.MILITARY,"Explicit actual functions distinguish general production from dedicated military capacity");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void everyPersonalityGetsStrictWarMilitaryBoostAndReturnsExactlyToPeaceWeights(GameTestHelper h){
        var settings=RoyalNpcConfig.weights();
        for(var base:Category.values()){
            int peace=RoyalInvestmentPolicy.weight(base,Role.MILITARY,false,false,settings),war=RoyalInvestmentPolicy.weight(base,Role.MILITARY,true,false,settings),border=RoyalInvestmentPolicy.weight(base,Role.MILITARY,true,true,settings);
            h.assertTrue(war>peace && war-peace==settings.warMilitary() && border-war==settings.borderMilitary(),"War and threatened-border weights rise for EVERY personality: "+base);
            h.assertTrue(war>RoyalInvestmentPolicy.weight(base,Role.AGRICULTURAL,false,false,settings) && RoyalInvestmentPolicy.weight(base,Role.MILITARY,false,false,settings)==peace,"Default wartime military priority exceeds a peaceful specialization and later returns to its exact baseline");
            h.assertTrue(RoyalInvestmentPolicy.weight(base,Role.ARTISAN,true,false,settings)==RoyalInvestmentPolicy.weight(base,Role.ARTISAN,false,false,settings),"War does not silently reroll base or erase productive alternatives");
        }h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void actualActiveWarAndSavedNearbyEnemyPositionsDriveContextWithoutChunkLoads(GameTestHelper h){
        try(var f=new PersonalityGameTests.Villages(h)){
            var data=KingdomSavedData.get(h.getLevel());
            for(var path:TYPES){
                var own=f.add(path);var enemy=f.add("norman/grosbourg");var k=f.kingdom(own);var other=f.kingdom(enemy);var base=KingdomPersonality.forCapital(h.getLevel(),k);
                var peace=RoyalInvestmentPolicy.context(h.getLevel(),data,k,own.getId().uuid());h.assertTrue(!peace.war() && !peace.threatened(),"Peace context has no invented war/border boost");
                var war=data.declareWar(k.id(),other.id(),h.getLevel().getGameTime());var active=RoyalInvestmentPolicy.context(h.getLevel(),data,k,own.getId().uuid());
                h.assertTrue(active.war() && active.threatened() && active.profile().equals(base) && !h.getLevel().isLoaded(own.getCenter()) && !h.getLevel().isLoaded(enemy.getCenter()),"Actual active war and nearby saved enemy metadata raise priority without loading either capital");
                h.assertTrue(RoyalInvestmentPolicy.weight(active,Role.MILITARY,RoyalNpcConfig.weights())>RoyalInvestmentPolicy.weight(peace,Role.MILITARY,RoyalNpcConfig.weights()),"Real war affects each native personality");
                data.peace(war.id(),KingdomSavedData.NPC_PEACE_REASON,0);h.assertTrue(RoyalInvestmentPolicy.context(h.getLevel(),data,k,own.getId().uuid()).equals(peace),"Ending the actual war restores the exact peaceful context and base");
            }h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void actualMilitaryCapitalHasHigherBoundedDeclarationAndCampaignDrawsThanBalanced(GameTestHelper h){
        try(var f=new PersonalityGameTests.Villages(h)){
            var military=f.kingdom(f.add("norman/militaire"));var general=f.kingdom(f.add("norman/grosbourg"));var target=f.kingdom(f.add("norman/agricole"));var settings=AutonomyConfig.Settings.DEFAULTS;
            double a=AutonomyService.declareChance(h.getLevel(),KingdomSavedData.get(h.getLevel()),military,target,settings),b=AutonomyService.declareChance(h.getLevel(),KingdomSavedData.get(h.getLevel()),general,target,settings);
            h.assertTrue(a>b && a<1 && b>0,"Otherwise equal actual capital metadata gives military a greater probabilistic declaration draw");
            h.assertTrue(AutonomyService.campaignChance(h.getLevel(),military,false,settings)>AutonomyService.campaignChance(h.getLevel(),general,false,settings)
                    && AutonomyService.campaignChance(h.getLevel(),military,true,settings)>AutonomyService.campaignChance(h.getLevel(),general,true,settings),"Both existing independent and directional wartime campaign draws use the actual personality factor");
            for(var category:Category.values())h.assertTrue(RoyalInvestmentPolicy.aggression(0.15,category,RoyalNpcConfig.weights())>0 && RoyalInvestmentPolicy.aggression(1,category,RoyalNpcConfig.weights())<=0.95
                    && RoyalInvestmentPolicy.aggression(0,category,RoyalNpcConfig.weights())==0,"No personality becomes permanently passive or bypasses a disabled draw; success is never certain");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void worldGameplayConfigChangesRelativeWeightsAndBoundedFactorsWithoutInventingActions(GameTestHelper h){
        int old=RoyalNpcConfig.WAR_MILITARY.get();double factor=RoyalNpcConfig.MILITARY_AGGRESSION.get();
        try{RoyalNpcConfig.WAR_MILITARY.set(12);RoyalNpcConfig.MILITARY_AGGRESSION.set(2.0);var settings=RoyalNpcConfig.weights();
            for(var category:Category.values())h.assertTrue(RoyalInvestmentPolicy.weight(category,Role.MILITARY,true,false,settings)-RoyalInvestmentPolicy.weight(category,Role.MILITARY,false,false,settings)==12,"Actual per-world config controls every personality wartime boost");
            h.assertTrue(RoyalInvestmentPolicy.aggression(0.2,Category.MILITARY,settings)==0.4 && RoyalInvestmentPolicy.weight(Category.MILITARY,Role.UNCLASSIFIED,true,true,settings)==0,"Actual config changes existing eligible chances, never creates unknown building opportunities");
        }finally{RoyalNpcConfig.WAR_MILITARY.set(old);RoyalNpcConfig.MILITARY_AGGRESSION.set(factor);}h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void existingOperatorAutonomyDiagnosticReportsActualProfileWarWeightsAndFactor(GameTestHelper h) throws Exception {
        try(var f=new PersonalityGameTests.Villages(h)){
            var k=f.kingdom(f.add("norman/agricole"));var player=new Stage5Support.TestPlayer(h.getLevel(),"Policy viewer");
            int result=h.getLevel().getServer().getCommands().getDispatcher().execute("kingdoms autonomy explain "+k.id(),player.createCommandSourceStack().withPermission(2));
            h.assertTrue(result>0 && player.text().contains("base=AGRICULTURAL") && player.text().contains("war=false") && player.text().contains("militaryWeight=1") && player.text().contains("agriculturalWeight=4") && player.text().contains("aggressionFactor=0.85"),"Actual existing OP2 diagnostic exposes explainable peaceful priorities without economic side effects");h.succeed();
        }
    }
    private RoyalPolicyGameTests(){}
}
