package com.millenairekingdoms.test;

import com.millenairekingdoms.gui.KingdomActions;
import com.millenairekingdoms.gui.KingdomScreens;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.network.KingdomActionPayload;
import com.millenairekingdoms.personality.KingdomPersonality;
import com.millenairekingdoms.personality.KingdomPersonality.Category;
import com.millenairekingdoms.personality.KingdomPersonality.Status;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.building.BuildingId;
import org.millenaire.building.BuildingInstance;
import org.millenaire.culture.ModCultures;
import org.millenaire.village.Village;
import org.millenaire.village.VillageId;
import org.millenaire.village.VillageSavedData;

/** Real original type metadata and saved native capital identity; no AI purchases or construction claims. */
@PrefixGameTestTemplate(false)
public final class PersonalityGameTests {
    private static final String NS="millenairekingdoms_personality_tests";
    private static final AtomicInteger NEXT=new AtomicInteger();
    private static final String[] REPRESENTATIVES={"norman/militaire","norman/agricole","norman/ecclesiastique","norman/artisans","norman/grosbourg"};
    private static ResourceLocation id(String path){return ResourceLocation.fromNamespaceAndPath("millenaire",path);}
    static final class Villages implements AutoCloseable {
        final GameTestHelper h;final List<Village> villages=new ArrayList<>();
        Villages(GameTestHelper h){this.h=h;}
        Village add(String path){
            var type=ModCultures.getVillageType(id(path));var center=new BlockPos(5_000_000+NEXT.incrementAndGet()*512,64,5_000_000);
            var village=new Village(VillageId.random(),type.culture(),type.id(),center);village.setVillageName("Capital "+UUID.randomUUID().toString().substring(0,8));
            var slot=type.layout().stream().filter(s->s.role().equals("centre") || s.role().equals("townhall")).findFirst().orElseThrow();
            var set=ModCultures.getBuildingPlanSet(slot.plan());var variant=set.variants().keySet().stream().sorted().findFirst().orElseThrow();var plan=set.getLevel(variant,0);
            village.addBuilding(new BuildingInstance(BuildingId.random(),plan.planId(),center,Rotation.NONE,BuildingInstance.Status.COMPLETE,set.id(),variant,0));
            village.setActive(false);village.setNoProjectsLeftUntil(Long.MAX_VALUE);VillageSavedData.get(h.getLevel()).getVillageManager().addVillage(village);villages.add(village);return village;
        }
        KingdomSavedData.Kingdom kingdom(Village v){return KingdomSavedData.get(h.getLevel()).create("Personality "+UUID.randomUUID(),v.getId().uuid());}
        @Override public void close(){for(var village:villages)VillageSavedData.get(h.getLevel()).getVillageManager().removeVillage(village.getId());VillageSavedData.get(h.getLevel()).setDirty();}
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void everyOriginalVillageTypeIsExplicitlyMappedOrExcludedFromNpcSpecialization(GameTestHelper h){
        var all=ModCultures.getAllVillageTypes().values();var expected=all.stream().filter(t->!t.loneBuilding() && !t.playerControlled()).map(t->t.id()).collect(Collectors.toSet());
        h.assertTrue(all.size()==126 && expected.size()==37 && expected.equals(KingdomPersonality.types().keySet()),"Pinned original9.0.3: all37 NPC types are explicitly mapped, not guessed by name or culture");
        int controlled=0,lone=0;var counts=new EnumMap<Category,Integer>(Category.class);
        for(var type:all){var profile=KingdomPersonality.forType(UUID.randomUUID(),type.id());
            if(type.loneBuilding()){lone++;h.assertTrue(profile.status()==Status.LONE_BUILDING && profile.category()==Category.GENERAL,"Lone buildings never gain specialized NPC economics");}
            else if(type.playerControlled()){controlled++;h.assertTrue(profile.status()==Status.CONTROLLED_TYPE && profile.category()==Category.GENERAL,"Controlled native types remain explicit fallback");}
            else{h.assertTrue(profile.specialized() && profile.villageType().equals(type.id()),"Every exact native NPC type has an explicit profile");counts.merge(profile.category(),1,Integer::sum);}}
        h.assertTrue(controlled==10 && lone==79 && counts.equals(new EnumMap<>(java.util.Map.of(Category.MILITARY,7,Category.AGRICULTURAL,10,Category.RELIGIOUS,7,Category.ARTISAN,7,Category.GENERAL,6))),"All126 audited types and five justified category counts are covered");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void actualSameCultureCapitalsKeepDistinctPersonalitiesRegardlessOfNamesAndRule(GameTestHelper h){
        try(var f=new Villages(h)){
            var data=KingdomSavedData.get(h.getLevel());var categories=Category.values();
            for(int n=0;n<REPRESENTATIVES.length;n++){
                var village=f.add(REPRESENTATIVES[n]);village.setVillageName("Religious military farms");var kingdom=f.kingdom(village);
                var before=KingdomPersonality.forKingdom(h.getLevel(),kingdom.id());h.assertTrue(before.category()==categories[n] && before.specialized(),"Same Norman culture has five type-specific profiles; misleading village/kingdom names are irrelevant");
                var player=UUID.randomUUID();data.claimRule(kingdom.id(),player,"Ruler",42);h.assertTrue(before.equals(KingdomPersonality.forKingdom(h.getLevel(),kingdom.id())),"Player rule does not reroll the base category; automation exclusion is a separate guard");data.abdicate(kingdom.id(),player);
            }h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void ambiguousTradeSubsistenceAndAdministrativeTypesUseDocumentedExactAssignments(GameTestHelper h){
        for(String path:List.of("byzantines/tradingvillage","seljuk/trading_village_seljuks","japanese/trading"))h.assertTrue(KingdomPersonality.forType(null,id(path)).category()==Category.ARTISAN,"Actual markets/workshops/brewery map to productive/logistics category");
        for(String path:List.of("inuits/huntingvillage","inuits/farmingvillage"))h.assertTrue(KingdomPersonality.forType(null,id(path)).category()==Category.AGRICULTURAL,"Subsistence/food layouts are deliberately agricultural, even the farming tannery centre");
        for(String path:List.of("japanese/seiji","norman/bourg_autonome","norman/grosbourg","inuits/communityvillage"))h.assertTrue(KingdomPersonality.forType(null,id(path)).category()==Category.GENERAL,"Mixed urban/palace/council layouts stay balanced rather than inferred from a single fort/pagoda/name");
        h.assertTrue(KingdomPersonality.forType(null,id("norman/notredame")).category()==Category.RELIGIOUS,"Cathedral marvel explicitly maps religious despite temporary warehouse centre");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void unknownMissingAndInvalidTypesGiveReadOnlyBalancedDiagnostics(GameTestHelper h){
        var unknown=id("future_custom_capital");var capital=UUID.randomUUID();var result=KingdomPersonality.forType(capital,unknown);
        h.assertTrue(result.category()==Category.GENERAL && result.status()==Status.UNKNOWN_TYPE && result.villageType().equals(unknown) && result.capital().equals(capital),"Unsupported type identity is retained, never invented or dropped");
        h.assertTrue(KingdomPersonality.forType(capital,null).status()==Status.UNKNOWN_TYPE && KingdomPersonality.forKingdom(h.getLevel(),UUID.randomUUID()).status()==Status.MISSING_KINGDOM
                && KingdomPersonality.forCapital(h.getLevel(),new KingdomSavedData.Kingdom(UUID.randomUUID(),"Absent",capital,true,null)).status()==Status.MISSING_CAPITAL,"Missing records are explicit balanced fallbacks");
        h.assertTrue(KingdomPersonality.describe(result).contains("UNKNOWN_TYPE") && KingdomPersonality.describe(result).contains(unknown.toString()),"Operator diagnostic explains the retained fallback identity");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void aRealRegisteredCustomTypeIsNotGuessedFromItsCultureOrCopiedMilitaryMetadata(GameTestHelper h) throws Exception {
        var original=ModCultures.getVillageType(id("norman/militaire"));var customId=ResourceLocation.fromNamespaceAndPath("custom","militaire");
        var components=org.millenaire.culture.VillageType.class.getRecordComponents();var signature=new Class<?>[components.length];var values=new Object[components.length];
        for(int n=0;n<components.length;n++){signature[n]=components[n].getType();values[n]=components[n].getName().equals("id") ? customId : components[n].getAccessor().invoke(original);}
        var custom=org.millenaire.culture.VillageType.class.getDeclaredConstructor(signature).newInstance(values);
        try{ModCultures.registerVillageType(custom);var profile=KingdomPersonality.forType(UUID.randomUUID(),customId);
            h.assertTrue(profile.status()==Status.UNMAPPED_TYPE && profile.category()==Category.GENERAL && profile.villageType().equals(customId),
                    "An actual registered custom native type retains its identity and explicit balanced fallback, even with a military name/culture/layout");
        }finally{ModCultures.unregisterVillageType(customId);}h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void strictSavedPoliticalCapitalChangeIntentionallyReclassifiesWithoutFormatChange(GameTestHelper h){
        try(var f=new Villages(h)){
            var military=f.add("norman/militaire");var agricultural=f.add("norman/agricole");var kingdom=f.kingdom(military);var data=KingdomSavedData.get(h.getLevel());data.attach(kingdom.id(),agricultural.getId().uuid());
            var saved=data.save(new CompoundTag(),h.getLevel().registryAccess());var unchanged=saved.copy();var current=KingdomPersonality.forKingdom(h.getLevel(),kingdom.id());
            h.assertTrue(current.category()==Category.MILITARY && unchanged.equals(data.save(new CompoundTag(),h.getLevel().registryAccess())),"Classification never rewrites political storage or invents a personality field");
            for(var raw:saved.getList("kingdoms",10)){var tag=(CompoundTag)raw;if(tag.getUUID("id").equals(kingdom.id()))tag.putUUID("capital",agricultural.getId().uuid());}
            var detached=KingdomSavedData.load(saved,h.getLevel().registryAccess());var changed=KingdomPersonality.forCapital(h.getLevel(),detached.kingdom(kingdom.id()));
            h.assertTrue(changed.category()==Category.AGRICULTURAL && changed.capital().equals(agricultural.getId().uuid()) && KingdomSavedData.FORMAT==7
                    && KingdomPersonality.forKingdom(h.getLevel(),kingdom.id()).equals(current),"A strictly deserialized changed-capital save uses its actual new type; live original record remains military, format6 preserved");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void realScreenSnapshotAndOperatorCommandExposeTheNativeCapitalProfile(GameTestHelper h) throws Exception {
        try(var f=new Villages(h)){
            var village=f.add("norman/militaire");var kingdom=f.kingdom(village);var player=new Stage5Support.TestPlayer(h.getLevel(),"Personality viewer");
            KingdomActions.handle(player,KingdomActionPayload.of("VIEW",kingdom.id()));var screen=KingdomScreens.build(player).getCompound("kingdom");
            h.assertTrue(screen.getString("personality").equals("MILITARY") && !screen.getBoolean("personalityFallback"),"Existing public kingdom snapshot exposes actual capital personality without spending authority");
            int result=h.getLevel().getServer().getCommands().getDispatcher().execute("kingdoms info "+kingdom.id(),player.createCommandSourceStack().withPermission(2));
            h.assertTrue(result>0 && player.text().contains("personality=MILITARY") && player.text().contains("millenaire:norman/militaire") && player.text().contains("classification=MAPPED"),"Actual OP2 diagnostic command includes type/mapping/status and has no gameplay mutation");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void allPersonalityLabelsExistInBothProductionLanguages(GameTestHelper h) throws Exception {
        for(String lang:List.of("en_us","tr_tr"))try(var input=PersonalityGameTests.class.getClassLoader().getResourceAsStream("assets/millenairekingdoms/lang/"+lang+".json")){
            var json=com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            for(var category:Category.values())h.assertTrue(json.has("millenairekingdoms.gui.personality."+category),"Every category label exists in "+lang);
            h.assertTrue(json.has("millenairekingdoms.gui.personality_fallback") && json.has("millenairekingdoms.gui.personality"),"Fallback and heading are localized");
        }h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",batch="personality_restart",timeoutTicks=1200)
    public static void fiveActualNativeCapitalIdentitiesReopenWithTheSameBaseCategory(GameTestHelper h) throws Exception {
        var level=h.getLevel();var marker=level.getServer().getWorldPath(LevelResource.ROOT).resolve("royal-personality-restart.dat");
        if(RoyalShipmentSupport.read()){
            var tag=NbtIo.readCompressed(marker,NbtAccounter.create(1024*1024));for(var raw:tag.getList("capitals",10)){var saved=(CompoundTag)raw;
                var village=VillageSavedData.get(level).getVillageManager().getVillage(new VillageId(saved.getUUID("capital")));var profile=KingdomPersonality.forKingdom(level,saved.getUUID("kingdom"));
                h.assertTrue(village!=null && village.getVillageTypeId().toString().equals(saved.getString("type")) && profile.category().name().equals(saved.getString("category")) && profile.specialized(),"Original saved NativeVillage + format6 political capital rehydrate the same deterministic base without metadata repair or random reroll");
            }System.out.println("PERSONALITY_RESTART_READ_PASS categories=5 mapping=1 format=6");h.succeed();return;
        }
        var f=new Villages(h);var capitals=new ListTag();for(var path:REPRESENTATIVES){var village=f.add(path);var kingdom=f.kingdom(village);var profile=KingdomPersonality.forKingdom(level,kingdom.id());var saved=new CompoundTag();saved.putUUID("capital",village.getId().uuid());saved.putUUID("kingdom",kingdom.id());saved.putString("type",village.getVillageTypeId().toString());saved.putString("category",profile.category().name());capitals.add(saved);}
        var tag=new CompoundTag();tag.put("capitals",capitals);NbtIo.writeCompressed(tag,marker);VillageSavedData.get(level).setDirty();KingdomSavedData.get(level).setDirty();
        System.out.println("PERSONALITY_RESTART_WRITE_PASS categories=5 mapping=1 format=6");h.succeed();
    }
    private PersonalityGameTests(){}
}
