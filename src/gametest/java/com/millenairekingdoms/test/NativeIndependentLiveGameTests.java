package com.millenairekingdoms.test;

import com.millenairekingdoms.autonomy.*;
import com.millenairekingdoms.history.*;
import com.millenairekingdoms.kingdom.KingdomSavedData;
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
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.culture.ModCultures;
import org.millenaire.entity.MillVillager;
import org.millenaire.village.*;
import org.millenaire.world.VillageSpawner;

/** Original native generation fixture, then ordinary production decisions/dice with no connected player. */
@PrefixGameTestTemplate(false)
public final class NativeIndependentLiveGameTests {
    @GameTest(templateNamespace="millenairekingdoms_independent_native_tests",template="empty",timeoutTicks=2_000_000)
    public static void originalUnloadedIndependentVillageIsAutonomouslyConqueredAndReopens(GameTestHelper h){next(new Probe(h));}

    private static void next(Probe probe){
        probe.h.runAfterDelay(1,()->{
            try{probe.tick();}catch(Exception failure){probe.close();throw new IllegalStateException("Original independent strategic fixture: "+probe.diagnostic(),failure);}
            if(!probe.closed)next(probe);
        });
    }

    private static final class Probe {
        final GameTestHelper h;
        final ServerLevel level;
        final Path marker;
        final boolean daylight, economics;
        final List<BlockPos> anchors=List.of(new BlockPos(540000,-60,580000),new BlockPos(541500,-60,580000));
        final List<Village> villages=new ArrayList<>();
        boolean closed;
        int phase,ticks;
        UUID kingdom;
        long coldTick=-1,previousTick=-1,previousDay=-1;

        Probe(GameTestHelper h){
            this.h=h;level=h.getLevel();marker=level.getServer().getWorldPath(LevelResource.ROOT).resolve("native-independent-strategic.dat");
            daylight=level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);economics=RoyalNpcConfig.ECONOMIC_ENABLED.get();
            level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(true,level.getServer());RoyalNpcConfig.ECONOMIC_ENABLED.set(false);
            level.getServer().tickRateManager().requestGameToSprint(1_990_000);
        }

        void tick()throws Exception{
            if(++ticks>1_990_000){close();h.fail("No default-probability independent conquest before bounded deadline: "+diagnostic());return;}
            if(phase==0){
                if(RoyalShipmentSupport.read()){
                    var saved=HistoryFiles.read(marker);var data=KingdomSavedData.get(level);var id=saved.getUUID("operation");var op=new StrategicBattleStore(level).read(id);
                    for(var raw:saved.getList("villages",10)){var row=(CompoundTag)raw;var nativeVillage=VillageSavedData.get(level).getVillageManager().getVillage(new VillageId(row.getUUID("id")));h.assertTrue(nativeVillage!=null && nativeVillage.getVillageTypeId().toString().equals(row.getString("type")),"Original generated village identities/types reopen without population repair");}
                    h.assertTrue(data.owner(saved.getUUID("target")).equals(saved.getUUID("kingdom")) && data.receipt(id).transferred() && op.order().warId()==null && op.status()==StrategicBattleStore.Status.RESOLVED && HistoryHooks.store(level).get(op.narrative().id()).equals(op.narrative()),"Actual autonomous independent conquest, exact roll and Chronicle persist across a separate JVM");
                    StrategicBattleService.resume(level,new StrategicBattleStore(level),op);h.assertTrue(data.wins(op.campaign())==1,"Completed independent conquest cannot be credited again");
                    System.out.println("NATIVE_INDEPENDENT_REOPEN operation="+id+" actualDay="+data.day()+" preservedTransfer=true players="+level.players().size());close();h.succeed();return;
                }
                level.setDayTime(6000);force(true);phase=1;return;
            }
            if(phase==1){
                for(var anchor:anchors)if(!level.hasChunksAt(anchor.offset(-120,0,-120),anchor.offset(120,0,120)))return;
                var manager=VillageSavedData.get(level).getVillageManager();var types=List.of("norman/militaire","norman/ecclesiastique");
                for(int n=0;n<anchors.size();n++){
                    var before=manager.getAllVillages().stream().map(Village::getId).collect(java.util.stream.Collectors.toSet());var type=ModCultures.getVillageType(ResourceLocation.parse("millenaire:"+types.get(n)));var anchor=anchors.get(n);var surface=anchor.atY(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,anchor.getX(),anchor.getZ()));
                    var failure=VillageSpawner.spawnVillage(level,surface,type,0);h.assertTrue(failure==null,"Original native generation must succeed: "+failure);
                    villages.add(manager.getAllVillages().stream().filter(v->!before.contains(v.getId()) && v.getParentVillageId()==null && v.getVillageTypeId().equals(type.id())).findFirst().orElseThrow());
                }
                kingdom=KingdomSavedData.get(level).create("Native independent conquest fixture",villages.getFirst().getId().uuid()).id();phase=2;
            }
            if(phase==2){
                for(var v:villages){v.setForceActive(true);v.setActive(true);}
                if(villages.stream().anyMatch(v->v.getVillagerUuids().stream().filter(id->level.getEntity(id) instanceof MillVillager entity && entity.isAlive()).count()<3))return;
                for(var v:villages)v.syncRecords(level);
                var a=MilitarySnapshot.capture(level,villages.get(0));var b=MilitarySnapshot.capture(level,villages.get(1));
                if(a.attack()<=0 || b.defense()<=0 || Military.problem(villages.get(0),villages.get(1))!=null)return;
                h.assertTrue(b.owner()==null && a.raiders().stream().allMatch(u->level.getEntity(u.id()) instanceof MillVillager) && b.defenders().stream().allMatch(u->level.getEntity(u.id()) instanceof MillVillager),"Both capabilities come from actual original spawned NPCs, not supplied soldier records");
                System.out.println("NATIVE_INDEPENDENT_READY nativePower="+a.attack()+"/"+b.defense()+" populations="+villages.stream().map(v->v.getVillagerRecords().size()).toList()+" defaultCampaignChance="+AutonomyService.campaignChance(level,KingdomSavedData.get(level).kingdom(kingdom),false,AutonomyConfig.current()));
                villages.get(0).setRelation(villages.get(1).getId(),-100);villages.get(1).setRelation(villages.get(0).getId(),-100);
                for(var v:villages)v.setForceActive(false);force(false);phase=3;return;
            }
            if(phase==3){
                h.assertTrue(level.getServer().getPlayerList().getPlayers().isEmpty(),"Automatic independent conquest has zero connected players");
                boolean cold=villages.stream().noneMatch(v->level.isLoaded(v.getCenter()));
                if(coldTick<0){if(!cold)return;coldTick=level.getGameTime();previousTick=coldTick;previousDay=level.getDayTime();return;}
                h.assertTrue(level.getGameTime()==previousTick+1 && level.getDayTime()==previousDay+1,"Measured interval contains real successive ticks and no test clock skips");previousTick=level.getGameTime();previousDay=level.getDayTime();
                var data=KingdomSavedData.get(level);var target=villages.get(1).getId().uuid();
                if(ticks%24000==0)System.out.println("NATIVE_INDEPENDENT_PROGRESS "+diagnostic());
                if(!kingdom.equals(data.owner(target)))return;
                var event=HistoryHooks.store(level).query(new HistoryStore.Query(kingdom,HistoryEvent.Kind.STRATEGIC_BATTLE,0,Long.MAX_VALUE,null,24,target)).events().stream().filter(e->e.outcome().equals("ATTACKER_VICTORY") && "true".equals(e.facts().get("independentTarget"))).findFirst().orElseThrow();
                var id=UUID.fromString(event.source());var receipt=data.receipt(id);var op=new StrategicBattleStore(level).read(id);
                h.assertTrue(cold && receipt!=null && receipt.transferred() && op.order().warId()==null && data.wins(op.campaign())==1 && data.activeRaids().isEmpty() && event.realms().size()==1,"Ordinary production political/order handlers complete one real unloaded independent campaign with no fictitious defender government or native duplicate");
                var saved=new CompoundTag();saved.putUUID("operation",id);saved.putUUID("kingdom",kingdom);saved.putUUID("target",target);var list=new ListTag();for(var v:villages){var row=new CompoundTag();row.putUUID("id",v.getId().uuid());row.putString("type",v.getVillageTypeId().toString());list.add(row);}saved.put("villages",list);VillageSavedData.get(level).setDirty();level.getServer().saveEverything(true,true,true);HistoryFiles.write(marker,saved);
                System.out.println("NATIVE_INDEPENDENT_COMPLETE operation="+id+" actualDay="+data.day()+" realColdTicks="+(level.getGameTime()-coldTick)+" noDirectEvaluator=true noForcedOutcome=true players=0");close();h.succeed();
            }
        }

        void force(boolean value){for(var anchor:anchors){var c=new ChunkPos(anchor);for(int x=-8;x<=8;x++)for(int z=-8;z<=8;z++)level.setChunkForced(c.x+x,c.z+z,value);}}
        String diagnostic(){return "phase="+phase+" ticks="+ticks+" worldDay="+KingdomSavedData.get(level).day()+" coldTick="+coldTick+" loaded="+villages.stream().map(v->level.isLoaded(v.getCenter())).toList();}
        void close(){if(closed)return;closed=true;for(var v:villages)v.setForceActive(false);if(!RoyalShipmentSupport.read())force(false);RoyalNpcConfig.ECONOMIC_ENABLED.set(economics);level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(daylight,level.getServer());level.getServer().tickRateManager().stopSprinting();VillageSavedData.get(level).setDirty();}
    }
}
