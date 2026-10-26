package com.millenairekingdoms.test;

import com.millenairekingdoms.atlas.*;
import com.millenairekingdoms.autonomy.*;
import com.millenairekingdoms.conquest.ConquestService;
import com.millenairekingdoms.history.*;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.network.ChronicleRequestPayload;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.culture.ModCultures;
import org.millenaire.entity.MillVillager;
import org.millenaire.item.ModItems;
import org.millenaire.village.*;
import org.millenaire.world.VillageSpawner;

/** Original generated villages/residents. Fixture tickets are removed before the unattended, real-calendar phase. */
@PrefixGameTestTemplate(false)
public final class NativeWarLiveGameTests {
    private static final Map<ServerLevel,Probe> ACTIVE=new WeakHashMap<>();
    public static void onDeath(LivingDeathEvent event){if(event.getEntity().level() instanceof ServerLevel level && event.getEntity() instanceof MillVillager && event.getSource().getEntity() instanceof MillVillager){var probe=ACTIVE.get(level);if(probe!=null && probe.physical && probe.raid!=null && probe.villages.size()>=2 && event.getEntity().blockPosition().distSqr(probe.villages.get(1).getCenter())<256*256)probe.combatDeaths++;}}
    @GameTest(templateNamespace="millenairekingdoms_native_war_tests",template="empty",timeoutTicks=700000)
    public static void generatedKingdomsConductUnattendedWarAndConquestAcrossRealCalendarAndJvm(GameTestHelper h){start(h,false);}
    @GameTest(templateNamespace="millenairekingdoms_native_battle_tests",template="empty",timeoutTicks=60000)
    public static void loadedOriginalVillagersFightARealNativeBattleAndKeepItsResultAfterReopen(GameTestHelper h){start(h,true);}
    private static void start(GameTestHelper h,boolean physical){var probe=new Probe(h,physical);ACTIVE.put(h.getLevel(),probe);next(h,probe);}
    // onEachTick preallocates every future callback up to timeout, quadratic for a many-day test.
    private static void next(GameTestHelper h,Probe probe){h.runAfterDelay(1,()->{try{probe.tick();}catch(Exception failure){probe.close();throw new IllegalStateException("Native military runtime: "+probe.diagnostic(),failure);}if(!probe.closed)next(h,probe);});}
    private static final class Probe {
        final GameTestHelper h;final ServerLevel level;final boolean read,physical,daylight,oldCampaigns,oldEconomics;final Path marker;
        final List<BlockPos> anchors=new ArrayList<>();final List<Village> villages=new ArrayList<>();
        UUID a,b,raid;int phase,ticks,combatDeaths;long coldTick=-1,baseDay,startTick;boolean closed;int aColor;
        Probe(GameTestHelper h,boolean physical){this.h=h;level=h.getLevel();this.physical=physical;read=RoyalShipmentSupport.read();marker=level.getServer().getWorldPath(LevelResource.ROOT).resolve(physical?"native-battle.dat":"native-unattended-war.dat");daylight=level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);oldCampaigns=AutonomyConfig.CAMPAIGNS.get();oldEconomics=RoyalNpcConfig.ECONOMIC_ENABLED.get();AutonomyConfig.CAMPAIGNS.set(!physical);RoyalNpcConfig.ECONOMIC_ENABLED.set(false);level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(true,level.getServer());level.getServer().tickRateManager().requestGameToSprint(physical?58000:690000);}
        void tick()throws Exception{
            if(closed)return;if(++ticks>(physical?59000:695000)){h.fail("Native military runtime timed out: "+diagnostic());close();return;}
            if(phase==0){
                if(read){var saved=HistoryFiles.read(marker);a=saved.getUUID("a");b=saved.getUUID("b");baseDay=saved.getLong("day");aColor=saved.getInt("aColor");for(var raw:saved.getList("villages",10)){var id=((CompoundTag)raw).getUUID("id");var v=VillageSavedData.get(level).getVillageManager().getVillage(new VillageId(id));h.assertTrue(v!=null,"Original generated village records reopen without plan or population repair");villages.add(v);anchors.add(v.getCenter());}
                    if(physical){raid=saved.getUUID("raid");var receipt=KingdomSavedData.get(level).receipt(raid);h.assertTrue(receipt!=null && receipt.outcome().name().equals(saved.getString("outcome")) && saved.getInt("combatDeaths")>0 && !new StrategicBattleStore(level).owns(raid) && !villages.get(1).isUnderAttack(),"Actual NPC-caused native deaths, native cleanup and one physical outcome survive separate JVM; no strategic substitution");System.out.println("NATIVE_BATTLE_REOPEN operation="+raid+" outcome="+receipt.outcome()+" observedNpcCombatDeaths="+saved.getInt("combatDeaths"));close();h.succeed();return;}
                    var data=KingdomSavedData.get(level);h.assertTrue(data.wars().stream().anyMatch(w->w.attackerKingdom().equals(a) && w.defenderKingdom().equals(b)) && data.members(b).contains(villages.get(1).getId().uuid()),"Real automatic war/first victory and original defender ownership reopen before final conquest");RoyalNpcConfig.ECONOMIC_ENABLED.set(true);phase=4;return;
                }
                var origin=new BlockPos(440000,-60,480000);anchors.add(origin);anchors.add(origin.offset(1500,0,0));if(!physical){anchors.add(origin.offset(0,0,1500));anchors.add(origin.offset(1500,0,1500));anchors.add(origin.offset(3000,0,1500));}level.setDayTime(6000);force(true);phase=1;return;
            }
            if(phase==1){for(var anchor:anchors)if(!level.hasChunksAt(anchor.offset(-120,0,-120),anchor.offset(120,0,120)))return;
                var types=List.of("norman/militaire","norman/ecclesiastique","norman/hameau_agricole","norman/hameau_artisans","norman/hameau_abbatiale");var manager=VillageSavedData.get(level).getVillageManager();
                for(int n=0;n<anchors.size();n++){var before=manager.getAllVillages().stream().map(Village::getId).collect(java.util.stream.Collectors.toSet());var type=ModCultures.getVillageType(ResourceLocation.parse("millenaire:"+types.get(n)));var anchor=anchors.get(n);var surface=anchor.atY(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,anchor.getX(),anchor.getZ()));var failure=VillageSpawner.spawnVillage(level,surface,type,0);h.assertTrue(failure==null,"Original full native village generation: "+(failure==null?types.get(n):failure.getString()));villages.add(manager.getAllVillages().stream().filter(v->!before.contains(v.getId()) && v.getVillageTypeId().equals(type.id()) && v.getParentVillageId()==null).findFirst().orElseThrow());}
                var data=KingdomSavedData.get(level);a=data.create("Autonomous native A",villages.get(0).getId().uuid()).id();b=data.create("Autonomous native B",villages.get(1).getId().uuid()).id();if(!physical)data.attach(a,villages.get(2).getId().uuid());baseDay=Math.max(0,data.day());for(var v:villages){v.setForceActive(true);v.setActive(true);}phase=2;return;
            }
            if(phase==2){for(var v:villages){v.setForceActive(true);v.setActive(true);}if(villages.stream().anyMatch(v->v.getVillagerUuids().stream().filter(id->level.getEntity(id) instanceof MillVillager entity && entity.isAlive()).count()<3))return;
                for(var v:villages)v.syncRecords(level);var source=villages.get(0);var target=villages.get(1);var attack=MilitarySnapshot.capture(level,source);var defense=MilitarySnapshot.capture(level,target);
                if(attack.attack()<=0 || defense.defense()<=0 || attack.raiders().stream().anyMatch(u->!(level.getEntity(u.id()) instanceof MillVillager)) || defense.defenders().stream().anyMatch(u->!(level.getEntity(u.id()) instanceof MillVillager)))return;
                h.assertTrue(Military.problem(source,target)==null,"Actual generated military/native equipment must meet original readiness: "+Military.problem(source,target));
                System.out.println("NATIVE_MILITARY_READY mode="+(physical?"PHYSICAL":"UNATTENDED")+" populations="+villages.stream().map(v->v.getVillagerUuids().size()).toList()+" nativePower="+attack.attack()+"/"+defense.defense()+" raiders="+attack.raiders().size()+" defenders="+defense.defenders().size());
                if(physical){var data=KingdomSavedData.get(level);data.declareWar(a,b,level.getGameTime());var order=data.addOrder(a,target.getId().uuid(),source.getId().uuid(),null,level.getGameTime(),level.getGameTime(),level.getGameTime()+24000);raid=order.id();phase=3;return;}
                for(var capital:villages.subList(0,2)){int added=capital.getTownhall().getInventory().add(level,ModItems.DENIER_ARGENT.get(),16);h.assertTrue(added==16,"Legitimate fixture starting funds are real native silver coins, not virtual balance");capital.getTownhall().getInventory().invalidateCache();}
                source.setRelation(target.getId(),-100);target.setRelation(source.getId(),-100);AtlasColors.get(level).ensure(List.of(a,b));aColor=AtlasColors.get(level).color(a);
                for(var v:villages)v.setForceActive(false);force(false);RoyalNpcConfig.ECONOMIC_ENABLED.set(true);phase=4;return;
            }
            if(phase==3){for(var v:villages){v.setForceActive(true);v.setActive(true);}var data=KingdomSavedData.get(level);var active=data.raid(villages.get(1).getId().uuid());
                if(active!=null){raid=active.id();if(startTick==0)startTick=active.startGameTime();h.assertTrue(!new StrategicBattleStore(level).owns(raid),"Active original NPC raid never acquires strategic ownership");}
                var receipt=raid==null?null:data.receipt(raid);if(receipt!=null && active==null){h.assertTrue(combatDeaths>0 && level.getGameTime()-startTick<ConquestService.MILLENAIRE_RAID_TIMEOUT && (receipt.outcome()==KingdomSavedData.Outcome.WON || receipt.outcome()==KingdomSavedData.Outcome.LOST),"Native entities must actually kill one another and resolve through combat before the frozen-raid timeout: "+diagnostic());
                    save(receipt.outcome().name());System.out.println("NATIVE_BATTLE_COMPLETE operation="+raid+" nativeNpcCombatDeaths="+combatDeaths+" elapsed="+(level.getGameTime()-startTick)+" outcome="+receipt.outcome());close();h.succeed();return;}
            }
            if(phase==4){var data=KingdomSavedData.get(level);boolean cold=villages.stream().allMatch(v->!level.isLoaded(v.getCenter()));if(cold && coldTick<0)coldTick=level.getGameTime();
                h.assertTrue(level.getServer().getPlayerList().getPlayers().isEmpty(),"Unattended acceptance contains zero connected players");
                if(ticks%50!=0)return;
                var wars=data.wars().stream().filter(w->w.attackerKingdom().equals(a) && w.defenderKingdom().equals(b)).toList();var strategic=events().stream().filter(e->e.kind()==HistoryEvent.Kind.STRATEGIC_BATTLE).toList();
                if(ticks%12000==0)System.out.println("NATIVE_UNATTENDED_PROGRESS "+diagnostic()+" strategicResults="+strategic.size()+" mainNanos="+StrategicBattleService.lastNanos(level));
                if(!read && cold && List.of(a,b).stream().allMatch(id->{var plan=new RoyalStrategyStore(level).read(id);return plan!=null && plan.metadataObservedTick()>=coldTick;}) && strategic.stream().anyMatch(e->e.outcome().equals("ATTACKER_VICTORY")) && !data.kingdom(b).fallen()){
                    h.assertTrue(!wars.isEmpty() && data.members(b).contains(villages.get(1).getId().uuid()),"An actual automatic first military victory cannot prematurely conquer the capital");
                    checkColdPlanning();save("");System.out.println("NATIVE_UNATTENDED_WRITE coldTick="+coldTick+" actualDay="+data.day()+" wars="+wars.size()+" strategicResults="+strategic.size()+" firstVictoryBeforeConquest=true");close();h.succeed();return;
                }
                if(read && cold && data.kingdom(b).fallen()){
                    h.assertTrue(data.owner(villages.get(1).getId().uuid()).equals(a) && strategic.stream().filter(e->e.outcome().equals("ATTACKER_VICTORY")).count()>=2 && strategic.stream().map(HistoryEvent::id).distinct().count()==strategic.size(),"Normal unloaded runtime reaches legitimate two-win capital conquest/collapse with no duplicate battle Chronicle");
                    h.assertTrue(AtlasColors.get(level).color(a)==aColor,"Original kingdom color survives real native-world restart and conquest");checkAtlas();save("");System.out.println("NATIVE_UNATTENDED_REOPEN actualDay="+data.day()+" wars="+wars.size()+" strategicResults="+strategic.size()+" legitimateCapitalConquest=true players=0 forcedVillageChunks=0");close();h.succeed();
                }
            }
        }
        void checkColdPlanning(){for(var id:List.of(a,b)){var plan=new RoyalStrategyStore(level).read(id);h.assertTrue(plan!=null && plan.metadataObservedTick()>=coldTick && plan.reason().length()>0,"Actual native kingdoms continue documented economic evaluation after natural unloading without reading remembered treasury as current money");}checkAtlas();}
        void checkAtlas(){boolean publicBefore=AtlasConfig.PUBLIC.get();AtlasConfig.PUBLIC.set(true);try{var player=new Stage5Support.TestPlayer(level,"Native political observer");int before=level.getChunkSource().getLoadedChunksCount();long start=System.nanoTime();var request=new ChronicleRequestPayload(UUID.randomUUID(),1,"ATLAS",Optional.empty(),"",0,Long.MAX_VALUE,Optional.empty(),"",Optional.empty());var view=AtlasViews.build(player,request);int color=AtlasColors.get(level).color(a);h.assertTrue(view.getList("settlements",10).stream().map(t->(CompoundTag)t).filter(t->t.hasUUID("kingdom") && t.getUUID("kingdom").equals(a)).allMatch(t->t.getInt("politicalColor")==color) && before==level.getChunkSource().getLoadedChunksCount(),"Real current/conquered village snapshots share political identity without terrain or forced chunk loading");System.out.println("NATIVE_WORLD_ATLAS snapshotMicros="+(System.nanoTime()-start)/1000+" total="+view.getInt("total")+" noChunkLoad=true");}finally{AtlasConfig.PUBLIC.set(publicBefore);}}
        List<HistoryEvent> events(){var result=new LinkedHashMap<UUID,HistoryEvent>();for(var realm:List.of(a,b)){UUID cursor=null;do{var page=HistoryHooks.store(level).query(new HistoryStore.Query(realm,null,0,Long.MAX_VALUE,cursor,24));for(var event:page.events())result.put(event.id(),event);cursor=page.next();}while(cursor!=null);}return List.copyOf(result.values());}
        void save(String outcome){var saved=new CompoundTag();saved.putUUID("a",a);saved.putUUID("b",b);saved.putLong("day",Math.max(0,KingdomSavedData.get(level).day()));saved.putInt("aColor",aColor);var list=new ListTag();for(var v:villages){var t=new CompoundTag();t.putUUID("id",v.getId().uuid());list.add(t);}saved.put("villages",list);if(raid!=null)saved.putUUID("raid",raid);saved.putInt("combatDeaths",combatDeaths);saved.putString("outcome",outcome);VillageSavedData.get(level).setDirty();level.getServer().saveEverything(true,true,true);HistoryFiles.write(marker,saved);}
        void force(boolean value){for(var anchor:anchors){var c=new ChunkPos(anchor);for(int x=-8;x<=8;x++)for(int z=-8;z<=8;z++)level.setChunkForced(c.x+x,c.z+z,value);}}
        String diagnostic(){var data=KingdomSavedData.get(level);return "phase="+phase+" ticks="+ticks+" day="+data.day()+" coldTick="+coldTick+" nativeDeaths="+combatDeaths+" wars="+data.wars().size()+" sourceLoaded="+(villages.isEmpty()?"pending":level.isLoaded(villages.getFirst().getCenter()));}
        void close(){if(closed)return;closed=true;ACTIVE.remove(level);for(var v:villages)v.setForceActive(false);force(false);AutonomyConfig.CAMPAIGNS.set(oldCampaigns);RoyalNpcConfig.ECONOMIC_ENABLED.set(oldEconomics);level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(daylight,level.getServer());level.getServer().tickRateManager().stopSprinting();VillageSavedData.get(level).setDirty();}
    }
}
