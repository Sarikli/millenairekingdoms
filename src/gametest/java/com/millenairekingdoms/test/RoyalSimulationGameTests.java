package com.millenairekingdoms.test;

import com.millenairekingdoms.autonomy.*;
import com.millenairekingdoms.construction.*;
import com.millenairekingdoms.economy.*;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.personality.KingdomPersonality;
import com.millenairekingdoms.shipping.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.*;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.building.*;
import org.millenaire.culture.ModCultures;
import org.millenaire.entity.VillagerSpawnFactory;
import org.millenaire.item.ModItems;
import org.millenaire.village.*;

/** Controlled real services/inventories over400 daily bookings and4 JVMs. Clock skipping NEVER completes NPC work. */
@PrefixGameTestTemplate(false)
public final class RoyalSimulationGameTests {
    private static final String[] TYPES={"norman/militaire","indian/agricole","seljuk/religious_village_seljuks","byzantines/tradingvillage","japanese/seiji"};
    private static final int DAYS=100;
    private record Scenario(RoyalShipmentSupport.Realm realm,CompoundTag stats){}
    @GameTest(templateNamespace="millenairekingdoms_royal_simulation_tests",template="empty",timeoutTicks=120_000)
    public static void fourHundredDaysConserveRealCurrencyCargoAndBoundNpcDecisionsAcrossFourJvms(GameTestHelper h)throws Exception{
        var level=h.getLevel();var data=KingdomSavedData.get(level);int epoch=Integer.getInteger("kingdoms.tests.simulationEpoch",-1);h.assertTrue(epoch>=0 && epoch<4,"Four explicit independent JVM epochs");
        var root=level.getServer().getWorldPath(LevelResource.ROOT);var marker=root.resolve("royal-simulation.dat");var all=new ArrayList<Scenario>();var random=RandomSource.create(120000+epoch);
        if(epoch==0){for(int type=0;type<TYPES.length;type++)for(int wealth=0;wealth<2;wealth++)all.add(create(h,type,wealth,all.size()));}
        else{
            var saved=NbtIo.readCompressed(marker,NbtAccounter.unlimitedHeap());h.assertTrue(saved.getInt("completedDay")==epoch*DAYS && data.day()==epoch*DAYS,"Previous epoch was acknowledged and daily ledger reopened without repair");
            for(var entry:saved.getList("realms",Tag.TAG_COMPOUND)){var tag=(CompoundTag)entry;all.add(new Scenario(RoyalShipmentSupport.restore(h,tag,true),tag));}
            for(var scenario:all)conservation(h,scenario,data);
        }
        h.assertTrue(all.size()==10 && all.stream().map(s->KingdomPersonality.forKingdom(level,s.realm.kingdom()).category()).distinct().count()==5,"Ten saved kingdoms retain all five exact native capital personalities");
        boolean construction=RoyalConstructionConfig.TRACKING.get(),shipping=RoyalShipmentConfig.TRACKING.get();var clock=(ServerLevelData)level.getLevelData();long originalGame=clock.getGameTime(),originalDay=level.getDayTime();var times=new ArrayList<Long>();var runtimeTimes=new ArrayList<Long>();
        var csv=root.resolve("royal-simulation.csv");if(epoch==0)Files.writeString(csv,"day,kingdom,type,initial,earned,coins,owed,fees,attempts,constructions,warConstructions,shipments,delivered,held,returned,currentWar,militaryWeight\n");
        try{
            RoyalConstructionConfig.TRACKING.set(true);RoyalShipmentConfig.TRACKING.set(true);
            for(int day=epoch*DAYS+1;day<=(epoch+1)*DAYS;day++){
                clock.setGameTime((long)day*EconomyService.DAY_TICKS);level.setDayTime((long)day*EconomyService.DAY_TICKS);
                if(day==40)for(int n=0;n<10;n+=2)data.declareWar(all.get(n).realm.kingdom(),all.get(n+1).realm.kingdom(),clock.getGameTime());
                if(day==160)for(var war:List.copyOf(data.wars()))if(war.status()==KingdomSavedData.WarStatus.ACTIVE)data.peace(war.id());
                if(day==240)collapse(h,all.get(0).realm,all.get(9).realm);
                if(day==300)all.get(7).realm.ruler();
                if(day==60)for(var scenario:all)builder(h,scenario.realm);
                var income=new HashMap<UUID,Long>();for(var scenario:all)income.put(scenario.realm.kingdom(),EconomyService.dailyIncome(level,data,scenario.realm.kingdom()));
                h.assertTrue(EconomyService.runDay(level,data,day),"Actual unchanged daily tax is booked exactly once");
                for(var scenario:all)scenario.stats.putLong("earned",scenario.stats.getLong("earned")+income.get(scenario.realm.kingdom()));
                var report=RoyalNpcRuntime.step(level,2);runtimeTimes.add(report.elapsedNanos());h.assertTrue(report.inspected()<=2 && report.attempts()<=2,"Default periodic economic step never exceeds two kingdoms/attempts");
                for(var scenario:all){
                    long started=System.nanoTime();RoyalNpcEconomyService.automatic(level).evaluate(scenario.realm.kingdom(),day,random);times.add(System.nanoTime()-started);
                    for(var entry:scenario.realm.store().entries())if(entry.intent().kingdom().equals(scenario.realm.kingdom()) && !entry.terminal())for(int n=0;n<8 && !scenario.realm.store().load(entry.intent().id()).terminal();n++)scenario.realm.service().observe(entry.intent().id());
                    conservation(h,scenario,data);
                }
                if(day%20==0 || day==61 || day==160 || day==240 || day==300)for(var scenario:all)row(csv,day,scenario,data);
            }
            var saved=new CompoundTag();saved.putInt("completedDay",(epoch+1)*DAYS);var entries=new ListTag();for(var scenario:all){var tag=scenario.realm.marker();for(var key:scenario.stats.getAllKeys())tag.put(key,scenario.stats.get(key).copy());entries.add(tag);for(var village:List.of(scenario.realm.capital(),scenario.realm.source(),scenario.realm.target()))village.setActive(false);}saved.put("realms",entries);NbtIo.writeCompressed(saved,marker);
            for(var scenario:all)for(var uuid:scenario.realm.capital().getVillagerUuids())if(level.getEntity(uuid) instanceof org.millenaire.entity.MillVillager actual)actual.discard();
            VillageSavedData.get(level).setDirty();data.setDirty();level.getServer().saveEverything(true,true,true);
            System.out.println("ROYAL_SIMULATION_EPOCH_PASS epoch="+epoch+" days="+((epoch+1)*DAYS)+" realms=10 cultures=5 personalities=5 evaluateCount="+times.size()+" evaluateP50us="+percentile(times,.5)/1000+" evaluateP95us="+percentile(times,.95)/1000+" evaluateMaxus="+Collections.max(times)/1000+" runtimeP95us="+percentile(runtimeTimes,.95)/1000+" runtimeMaxus="+Collections.max(runtimeTimes)/1000+" actualPhysicalCompletionsClaimed=0");
            if(epoch==3){h.assertTrue(data.kingdom(all.get(9).realm.kingdom()).fallen() && !data.npcGoverned(all.get(7).realm.kingdom()),"Conquest/collapse and player rule survive subsequent separate JVMs");}
            h.succeed();
        }finally{RoyalConstructionConfig.TRACKING.set(construction);RoyalShipmentConfig.TRACKING.set(shipping);clock.setGameTime(Math.max(clock.getGameTime(),originalGame));level.setDayTime(Math.max(level.getDayTime(),originalDay));RoyalNpcRuntime.onStopped(new net.neoforged.neoforge.event.server.ServerStoppedEvent(level.getServer()));}
    }
    private static Scenario create(GameTestHelper h,int type,int wealth,int index){
        var level=h.getLevel();var nativeType=ModCultures.getVillageType(ResourceLocation.parse("millenaire:"+TYPES[type]));var origin=new BlockPos(12_000_000+index*8192,-50,90000);
        Village capital=village(h,nativeType.id(),origin),source=village(h,nativeType.id(),origin.offset(512,0,0)),target=village(h,nativeType.id(),origin.offset(1024,0,0));
        var data=KingdomSavedData.get(level);var k=data.create("Simulation "+type+" "+wealth,capital.getId().uuid());data.attach(k.id(),source.getId().uuid());data.attach(k.id(),target.getId().uuid());var r=new RoyalShipmentSupport.Realm(level,k.id(),capital,source,target);
        int initial=wealth==1?4096:0;if(initial>0)add(h,r,capital,ModItems.DENIER_OR.get(),1);
        var options=RoyalConstructionCatalog.forNpc(level,k.id(),target.getId().uuid()).options();
        var need=options.stream().filter(o->o.requiredMaterials().entrySet().stream().anyMatch(e->e.getValue()>0 && e.getValue()<=64)).min(Comparator.comparingInt(o->o.requiredMaterials().values().stream().mapToInt(Integer::intValue).sum())).orElseThrow();
        var shortage=need.requiredMaterials().entrySet().stream().filter(e->e.getValue()>0 && e.getValue()<=64).sorted(Map.Entry.comparingByKey()).findFirst().orElseThrow();Item item=AnywoodHelper.isAnywood(shortage.getKey())?Items.CHERRY_LOG:BuiltInRegistries.ITEM.getOptional(shortage.getKey()).orElseThrow();
        for(var entry:need.requiredMaterials().entrySet())if(!entry.getKey().equals(shortage.getKey()))add(h,r,target,AnywoodHelper.isAnywood(entry.getKey())?Items.CHERRY_LOG:BuiltInRegistries.ITEM.getOptional(entry.getKey()).orElseThrow(),entry.getValue());
        target.setPendingProject(new Village.PendingProject(need.planSet(),need.variant(),0,false,null));
        int retained=RoyalVillageStock.forNpc(level,k.id(),source.getId().uuid()).availability(item).retained();add(h,r,source,item,retained+shortage.getValue()+32);
        var bank=new HashMap<ResourceLocation,Integer>();var roles=EnumSet.noneOf(RoyalInvestmentPolicy.Role.class);
        for(var option:RoyalConstructionCatalog.forNpc(level,k.id(),capital.getId().uuid()).options().stream().sorted(Comparator.comparingInt(o->o.requiredMaterials().values().stream().mapToInt(Integer::intValue).sum())).toList()){
            var role=RoyalInvestmentPolicy.role(option.planSet());if(role==RoyalInvestmentPolicy.Role.UNCLASSIFIED || role==RoyalInvestmentPolicy.Role.CIVIC || !roles.add(role))continue;
            option.requiredMaterials().forEach((product,quantity)->bank.merge(product,quantity,Math::max));
        }
        int generic=bank.getOrDefault(AnywoodHelper.ANYWOOD_LOG,0);bank.remove(AnywoodHelper.ANYWOOD_LOG);if(generic>0)bank.merge(ResourceLocation.parse("minecraft:cherry_log"),generic,Math::addExact);
        bank.forEach((product,quantity)->add(h,r,capital,BuiltInRegistries.ITEM.getOptional(product).orElseThrow(),quantity));
        var stats=new CompoundTag();stats.putLong("initial",initial);stats.putLong("earned",0);stats.putString("product",BuiltInRegistries.ITEM.getKey(item).toString());stats.putInt("goods",RoyalNpcEconomyGameTests.count(r,capital,item)+RoyalNpcEconomyGameTests.count(r,source,item)+RoyalNpcEconomyGameTests.count(r,target,item));
        h.assertTrue(!roles.isEmpty(),"Native legal culture-specific resourced fixture choices exist");System.out.println("ROYAL_SIMULATION_FIXTURE index="+index+" type="+nativeType.id()+" funds="+initial+" availableRoles="+roles+" need="+need.planSet()+" quantity="+shortage.getValue()+" suppliedCargo="+stats.getInt("goods"));return new Scenario(r,stats);
    }
    private static Village village(GameTestHelper h,ResourceLocation typeId,BlockPos center){
        var level=h.getLevel();var type=ModCultures.getVillageType(typeId);var slot=type.layout().stream().filter(s->s.role().equals("centre") || s.role().equals("townhall")).findFirst().orElseThrow();var set=ModCultures.getBuildingPlanSet(slot.plan());var variant=set.variants().keySet().stream().sorted().findFirst().orElseThrow();var plan=set.getLevel(variant,0);
        var village=new Village(VillageId.random(),type.culture(),type.id(),center);village.setVillageName("Simulation "+UUID.randomUUID());var hall=new BuildingInstance(BuildingId.random(),plan.planId(),center,Rotation.NONE,BuildingInstance.Status.COMPLETE,set.id(),variant,0);hall.resolveSpecialPoints(ModCultures.getBuildingPlan(plan.planId()));hall.initInventory();village.addBuilding(hall);
        for(var position:hall.getInventory().getChestPositions()){level.getChunkAt(position);level.setBlock(position,Blocks.CHEST.defaultBlockState(),2);}level.getChunkAt(center);level.setBlock(center.below(),Blocks.STONE.defaultBlockState(),2);VillageSavedData.get(level).getVillageManager().addVillage(village);RoyalShipmentSupport.activateVillage(level,village);return village;
    }
    private static void add(GameTestHelper h,RoyalShipmentSupport.Realm r,Village village,Item item,int quantity){h.assertTrue(village.getTownhall().getInventory().add(r.level(),item,quantity)==quantity,"Declared finite fixture materials fit genuine native storage: "+item+" x"+quantity);}
    private static void builder(GameTestHelper h,RoyalShipmentSupport.Realm r){
        var type=ModCultures.getAllVillagerTypes().values().stream().filter(t->t.culture().equals(r.capital().getCultureId()) && !t.isChild() && t.goals().contains(ResourceLocation.parse("millenaire:build"))).sorted(Comparator.comparing(t->t.id().toString())).findFirst().orElseThrow();
        var npc=VillagerSpawnFactory.spawnInVillage(r.level(),r.capital(),type.id(),r.capital().getCenter().above(),r.capital().getTownhall().getId());h.assertTrue(npc!=null && npc.isAlive() && r.level().getEntity(npc.getUUID())==npc,"Day60 declared availability input is an actual original factory builder, never a fake NPC or completed structure");
    }
    private static void collapse(GameTestHelper h,RoyalShipmentSupport.Realm attacker,RoyalShipmentSupport.Realm victim){var data=KingdomSavedData.get(attacker.level());data.declareWar(attacker.kingdom(),victim.kingdom(),attacker.level().getGameTime());for(var target:List.of(victim.from(),victim.to(),victim.capital().getId().uuid()))for(int n=0;n<2;n++){var raid=data.begin(attacker.from(),target,attacker.level().getGameTime(),null);data.recordVictory(raid);data.finish(raid,KingdomSavedData.Outcome.LOST);}h.assertTrue(data.kingdom(victim.kingdom()).fallen(),"Controlled genuine political receipts apply two-win members and60-percent capital collapse; not a combat AI claim");}
    private static void conservation(GameTestHelper h,Scenario s,KingdomSavedData data){
        var r=s.realm;var payments=r.payments().entries().stream().filter(e->e.intent().getUUID("kingdom").equals(r.kingdom())).toList();long fees=payments.stream().filter(e->e.state()!=RoyalPaymentJournal.State.REFUNDED).mapToLong(e->e.intent().getLong("amount")).sum();
        h.assertTrue(r.coins()+data.owed(r.kingdom())==s.stats.getLong("initial")+s.stats.getLong("earned")-fees,"Every physical denier plus owed liability equals finite initial coins + actual taxes - nonrefunded fees, kingdom="+r.kingdom());
        var item=BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(s.stats.getString("product"))).orElseThrow();int held=r.store().entries().stream().filter(e->e.intent().kingdom().equals(r.kingdom())).mapToInt(RoyalShipmentStore.Entry::escrow).sum();
        h.assertTrue(RoyalNpcEconomyGameTests.count(r,r.capital(),item)+RoyalNpcEconomyGameTests.count(r,r.source(),item)+RoyalNpcEconomyGameTests.count(r,r.target(),item)+held==s.stats.getInt("goods"),"Physical capital+source+target+held cargo is conserved through all days/JVMs/governments");
    }
    private static void row(Path csv,int day,Scenario s,KingdomSavedData data)throws Exception{
        var r=s.realm;var decisions=new RoyalNpcDecisionStore(RoyalNpcDecisionStore.directory(r.level())).entries().stream().filter(d->d.kingdom().equals(r.kingdom())).toList();var shipping=r.store().entries().stream().filter(e->e.intent().kingdom().equals(r.kingdom())).toList();long fees=r.payments().entries().stream().filter(e->e.intent().getUUID("kingdom").equals(r.kingdom()) && e.state()!=RoyalPaymentJournal.State.REFUNDED).mapToLong(e->e.intent().getLong("amount")).sum();
        var context=data.kingdom(r.kingdom()).fallen()?new RoyalInvestmentPolicy.Context(KingdomPersonality.forKingdom(r.level(),r.kingdom()),false,false):RoyalInvestmentPolicy.context(r.level(),data,data.kingdom(r.kingdom()),data.kingdom(r.kingdom()).capital());
        Files.writeString(csv,day+","+r.kingdom()+","+r.capital().getVillageTypeId()+","+s.stats.getLong("initial")+","+s.stats.getLong("earned")+","+r.coins()+","+data.owed(r.kingdom())+","+fees+","+decisions.size()+","+decisions.stream().filter(d->d.choice().kind()==RoyalNpcDecisionStore.Kind.CONSTRUCTION).count()+","+decisions.stream().filter(d->d.choice().kind()==RoyalNpcDecisionStore.Kind.CONSTRUCTION && d.reason().contains("war=true")).count()+","+shipping.size()+","+shipping.stream().filter(e->e.stage()==RoyalShipmentStore.Stage.DELIVERED).count()+","+shipping.stream().mapToInt(RoyalShipmentStore.Entry::escrow).sum()+","+shipping.stream().mapToInt(RoyalShipmentStore.Entry::returned).sum()+","+(data.activeWar(r.kingdom())!=null)+","+RoyalInvestmentPolicy.weight(context,RoyalInvestmentPolicy.Role.MILITARY,RoyalNpcConfig.weights())+"\n",StandardOpenOption.APPEND);
    }
    private static long percentile(List<Long> values,double p){var sorted=values.stream().sorted().toList();return sorted.get(Math.min(sorted.size()-1,(int)(p*sorted.size())));}
    private RoyalSimulationGameTests(){}
}
