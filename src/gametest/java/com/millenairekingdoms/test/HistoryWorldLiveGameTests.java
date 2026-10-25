package com.millenairekingdoms.test;

import com.millenairekingdoms.autonomy.*;
import com.millenairekingdoms.history.*;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.construction.RoyalConstructionStore;
import com.millenairekingdoms.shipping.*;
import java.util.*;
import java.nio.file.Path;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.culture.ModCultures;
import org.millenaire.entity.MillVillager;
import org.millenaire.village.*;
import org.millenaire.world.VillageSpawner;

/** Original full villages + actual production LevelTick politics/economy/history. Fixture accelerates the CALENDAR
 * at noon, not gameTime/native work; new combat campaigns are disabled only to isolate diplomacy/peace history.
 * No direct political decision, history append, victory, commission, shipment or domain stepping after setup. */
@PrefixGameTestTemplate(false)
public final class HistoryWorldLiveGameTests {
    @GameTest(templateNamespace="millenairekingdoms_history_world_tests",template="empty",timeoutTicks=120000)
    public static void livingNativeWorldRecordsItsOwnPoliticalTransitionsAcrossCalendarAndJvmRestart(GameTestHelper h){
        var p=new Probe(h);h.onEachTick(()->{try{p.tick();}catch(Exception e){p.close();throw new IllegalStateException("Living historical world: "+p.diagnostic(),e);}});
    }
    private static final class Probe {
        final GameTestHelper h;final ServerLevel level;final boolean read,daylight,campaigns;final Path marker;
        final List<BlockPos> anchors=new ArrayList<>();final List<Village> villages=new ArrayList<>();
        UUID a,b;long baseDay,targetDay;int phase,ticks,advanced,lastAdvance;boolean closed;
        Probe(GameTestHelper h){this.h=h;level=h.getLevel();read=RoyalShipmentSupport.read();marker=level.getServer().getWorldPath(LevelResource.ROOT).resolve("history-world.dat");daylight=level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);campaigns=AutonomyConfig.CAMPAIGNS.get();level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,level.getServer());AutonomyConfig.CAMPAIGNS.set(false);level.getServer().tickRateManager().requestGameToSprint(120000);}
        void tick()throws Exception{
            if(closed)return;if(++ticks>119000){h.fail("Living history world timeout: "+diagnostic());close();return;}
            if(phase==0){
                if(read){var saved=HistoryFiles.read(marker);a=saved.getUUID("a");b=saved.getUUID("b");baseDay=saved.getLong("day");h.assertTrue(KingdomSavedData.get(level).day()==baseDay,"Last booked day reloads before another simulation day");
                    for(var raw:saved.getList("villages",10)){var entry=(CompoundTag)raw;var v=VillageSavedData.get(level).getVillageManager().getVillage(new VillageId(entry.getUUID("id")));h.assertTrue(v!=null,"Original full village metadata rehydrates without repair");villages.add(v);anchors.add(v.getCenter());}
                }else{anchors.add(new BlockPos(340000,-60,380000));anchors.add(anchors.getFirst().offset(1500,0,0));anchors.add(anchors.getFirst().offset(600,0,600));level.setDayTime(6000);}
                force(true);phase=1;return;
            }
            if(phase==1){for(var anchor:anchors)if(!level.hasChunksAt(anchor.offset(-120,0,-120),anchor.offset(120,0,120)))return;
                if(!read){var types=List.of("norman/militaire","norman/ecclesiastique","norman/agricole");var manager=VillageSavedData.get(level).getVillageManager();
                    for(int n=0;n<3;n++){var before=manager.getAllVillages().stream().map(Village::getId).collect(java.util.stream.Collectors.toSet());var type=ModCultures.getVillageType(ResourceLocation.parse("millenaire:"+types.get(n)));var anchor=anchors.get(n);var surface=anchor.atY(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,anchor.getX(),anchor.getZ()));var failure=VillageSpawner.spawnVillage(level,surface,type,0);h.assertTrue(failure==null,"Original native world baseline spawns: "+(failure==null?types.get(n):failure.getString()));villages.add(manager.getAllVillages().stream().filter(v->!before.contains(v.getId()) && v.getVillageTypeId().equals(type.id()) && v.getParentVillageId()==null).findFirst().orElseThrow());}
                    var d=KingdomSavedData.get(level);a=d.create("Living annals A",villages.get(0).getId().uuid()).id();b=d.create("Living annals B",villages.get(1).getId().uuid()).id();
                    relation(villages.get(0),villages.get(1),-100);relation(villages.get(0),villages.get(2),100);relation(villages.get(1),villages.get(2),-100);baseDay=Math.max(0,d.day());
                }
                targetDay=baseDay+80;for(var v:villages){v.setForceActive(true);v.setActive(true);}phase=2;return;
            }
            for(var v:villages){v.setForceActive(true);v.setActive(true);}
            if(phase==2){if(villages.stream().anyMatch(v->v.getVillagerUuids().stream().filter(id->level.getEntity(id) instanceof MillVillager).count()<3))return;
                for(var v:villages)v.syncRecords(level);h.assertTrue(villages.stream().allMatch(v->v.getVillagerRecords().values().stream().anyMatch(r->!r.isKilled())),"Each full native village has actual saved living resident entities, not invented records");
                System.out.println("HISTORY_WORLD_READY read="+read+" day="+baseDay+" populations="+villages.stream().map(v->v.getVillagerUuids().size()).toList()+" raiders="+Military.availableRaiders(villages.get(0))+" militaryReady="+Military.anyReady(level,KingdomSavedData.get(level),a));check();phase=3;lastAdvance=ticks;return;
            }
            if(phase==3){var d=KingdomSavedData.get(level);
                if(advanced>0 && d.day()<baseDay+advanced)return;
                if(advanced>0 && ticks-lastAdvance>=24)check();
                if(advanced>=80 && ticks-lastAdvance>=80 && (advanced==240 || ready())){
                    long wars=d.wars().stream().filter(w->inScenario(w.attackerKingdom()) && inScenario(w.defenderKingdom())).count();long peaces=d.wars().stream().filter(w->inScenario(w.attackerKingdom()) && inScenario(w.defenderKingdom()) && w.status()==KingdomSavedData.WarStatus.PEACE).count();
                    h.assertTrue(wars>0 && peaces>0 && d.owner(villages.get(2).getId().uuid())!=null,"Actual production events must generate war, peace and peaceful joining, not synthetic history fixtures: "+diagnostic());
                    for(var v:villages)v.syncRecords(level);var saved=new CompoundTag();saved.putUUID("a",a);saved.putUUID("b",b);saved.putLong("day",d.day());var ids=new ListTag();for(var v:villages){var tag=new CompoundTag();tag.putUUID("id",v.getId().uuid());ids.add(tag);}saved.put("villages",ids);HistoryFiles.write(marker,saved);close();level.getServer().saveEverything(true,true,true);net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
                    System.out.println("HISTORY_WORLD_PASS read="+read+" bookedDay="+d.day()+" actualServerTicks="+ticks+" calendarDays="+advanced+" totalCalendarDays="+d.day()+" wars="+wars+" peaces="+peaces+" independentOwner="+d.owner(villages.get(2).getId().uuid()));h.succeed();return;
                }
                if(advanced<240 && ticks-lastAdvance>=80){advanced++;lastAdvance=ticks;level.setDayTime((baseDay+advanced)*24000+6000);}
            }
        }
        boolean ready(){var d=KingdomSavedData.get(level);return d.wars().stream().anyMatch(w->inScenario(w.attackerKingdom()) && inScenario(w.defenderKingdom()) && w.status()==KingdomSavedData.WarStatus.PEACE) && d.owner(villages.get(2).getId().uuid())!=null;}
        boolean inScenario(UUID id){return id.equals(a)||id.equals(b);}
        void check(){var d=KingdomSavedData.get(level);var events=events();var wars=d.wars().stream().filter(w->inScenario(w.attackerKingdom()) && inScenario(w.defenderKingdom())).toList();
            for(var war:wars){h.assertTrue(events.stream().filter(e->e.kind()==HistoryEvent.Kind.WAR && e.source().equals(war.id().toString())).count()==1,"Every actual automatic war has exactly one shared source history event");long peaces=events.stream().filter(e->e.kind()==HistoryEvent.Kind.PEACE && e.source().equals(war.id().toString())).count();h.assertTrue(peaces==(war.status()==KingdomSavedData.WarStatus.PEACE?1:0),"No missing/duplicate/false peace transition");}
            for(var e:events)if(e.kind()==HistoryEvent.Kind.WAR || e.kind()==HistoryEvent.Kind.PEACE){var war=d.war(UUID.fromString(e.source()));h.assertTrue(war!=null && e.realms().getFirst().id().equals(war.attackerKingdom()) && e.realms().get(1).id().equals(war.defenderKingdom()),"Narrative record preserves actual political declaration direction");}
            var independent=villages.get(2).getId().uuid();var joins=events.stream().filter(e->e.kind()==HistoryEvent.Kind.INCORPORATION && e.places().stream().anyMatch(p->p.id().equals(independent))).toList();h.assertTrue(joins.size()==(d.owner(independent)==null?0:1),"Peaceful incorporation exists exactly when actual membership changed");
            h.assertTrue(events.stream().map(HistoryEvent::id).distinct().count()==events.size(),"No duplicate IDs across actual events/reload");
        }
        List<HistoryEvent> events(){var out=new LinkedHashMap<UUID,HistoryEvent>();for(var realm:List.of(a,b)){UUID cursor=null;do{var page=HistoryHooks.store(level).query(new HistoryStore.Query(realm,null,0,Long.MAX_VALUE,cursor,24));for(var e:page.events())out.put(e.id(),e);cursor=page.next();}while(cursor!=null);}return List.copyOf(out.values());}
        String diagnostic(){var d=KingdomSavedData.get(level);return "phase="+phase+" ticks="+ticks+" advanced="+advanced+" bookedDay="+d.day()+" wars="+d.wars().size()+" relations="+(villages.size()<2?"pending":villages.get(0).getRelation(villages.get(1).getId()))+" readiness="+(a==null?"pending":Military.anyReady(level,d,a));}
        void force(boolean add){for(var anchor:anchors){var c=new ChunkPos(anchor);for(int x=-8;x<=8;x++)for(int z=-8;z<=8;z++)level.setChunkForced(c.x+x,c.z+z,add);}}
        void close(){if(closed)return;closed=true;for(var v:villages){v.syncRecords(level);v.setForceActive(false);v.setActive(false);VillageChunkLoader.releaseVillageChunks(level,v.getCenter(),v.getLoadedChunks());v.setChunksForceLoaded(false);v.setLoadedChunks(Set.of());}force(false);AutonomyConfig.CAMPAIGNS.set(campaigns);level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(daylight,level.getServer());level.getServer().tickRateManager().stopSprinting();VillageSavedData.get(level).setDirty();}
        static void relation(Village a,Village b,int relation){a.setRelation(b.getId(),relation);b.setRelation(a.getId(),relation);}
    }
}
