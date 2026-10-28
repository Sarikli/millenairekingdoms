package com.millenairekingdoms.test;

import com.millenairekingdoms.autonomy.*;
import com.millenairekingdoms.economy.RoyalNpcDecisionStore;
import com.millenairekingdoms.history.HistoryFiles;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.shipping.RoyalShipmentStore;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Explicit storage/terrain setup, then zero-player ordinary scheduling. Not a natural-world economic observation. */
@PrefixGameTestTemplate(false)
public final class EconomicBackgroundGameTests {
    private static final String NS="millenairekingdoms_economic_background_tests";
    private static final Map<ServerLevel,Integer> NEW_CHUNKS=new WeakHashMap<>();
    public static void onChunkLoad(ChunkEvent.Load event) {
        if(event.isNewChunk() && event.getLevel() instanceof ServerLevel level && NEW_CHUNKS.containsKey(level))NEW_CHUNKS.merge(level,1,Integer::sum);
    }

    @GameTest(templateNamespace=NS,template="empty",batch="background_authority",timeoutTicks=1000)
    public static void actualMilitaryOrderPreemptsAnEconomicLeaseWithoutEditingTheOrder(GameTestHelper h) {
        RoyalNpcConfig.BACKGROUND_ENABLED.set(true);var r=RoyalShipmentSupport.realm(h,"economic-military-priority");
        RoyalNpcEconomyGameTests.shortage(h,r);r.capital().setActive(false);
        new RoyalStrategyService(r.level()).step(r.kingdom(),10,RandomSource.create(2));RoyalEconomicActivation.offer(r.level(),r.kingdom());
        h.assertTrue(RoyalEconomicActivation.ownsLease(r.level()),"Bounded metadata preflight exists before the military request");
        var independent=RoyalShipmentSupport.village(r.level(),"Military destination",r.target().getCenter().offset(512,0,0));
        var data=KingdomSavedData.get(r.level());long now=r.level().getGameTime();
        var order=data.addOrder(r.kingdom(),independent.getId().uuid(),r.from(),null,now,now+1000,now+3000);
        h.runAfterDelay(2,()->{
            h.assertTrue(!RoyalEconomicActivation.ownsLease(r.level()) && RoyalEconomicActivation.report(r.level()).ownedTickets()==0
                    && order.equals(data.order(order.id())),"Real military order preempts economic activation without being consumed, replaced or progressed by it");
            data.removeOrder(order.id());org.millenaire.village.VillageSavedData.get(r.level()).getVillageManager().removeVillage(independent.getId());r.close();h.succeed();
        });
    }

    @GameTest(templateNamespace=NS,template="empty",batch="background_guard",timeoutTicks=1000)
    public static void missingTerrainAndChangedAuthorityNeverCreateEconomicTickets(GameTestHelper h) {
        var r=RoyalShipmentSupport.realm(h,"background-missing-terrain");RoyalNpcEconomyGameTests.shortage(h,r);r.capital().setActive(false);
        new RoyalStrategyService(r.level()).step(r.kingdom(),10,RandomSource.create(1));RoyalEconomicActivation.offer(r.level(),r.kingdom());
        h.assertTrue(RoyalEconomicActivation.ownsLease(r.level()),"Known work begins a bounded status-only preflight");
        h.runAfterDelay(200,()->{
            h.assertTrue(!RoyalEconomicActivation.ownsLease(r.level()) && RoyalEconomicActivation.report(r.level()).ownedTickets()==0
                    && RoyalEconomicActivation.report(r.level()).reason().contains("FULL terrain unavailable"),
                    "Absent ungenerated terrain rejects the lease before any ticket, generation, fee or cargo");
            h.assertTrue(new RoyalNpcDecisionStore(RoyalNpcDecisionStore.directory(r.level())).entries().stream().noneMatch(d->d.kingdom().equals(r.kingdom())) && r.store().entries().stream().noneMatch(e->e.intent().kingdom().equals(r.kingdom()))
                    && !r.capital().isForceActive() && !r.source().isForceActive() && !r.target().isForceActive(),
                    "Preflight does not invent money/items or set original persistent force-active flags");
            r.ruler();RoyalEconomicActivation.offer(r.level(),r.kingdom());
            h.assertTrue(!RoyalEconomicActivation.ownsLease(r.level()),"A player government cannot request an economic lease");r.close();h.succeed();
        });
    }

    @GameTest(templateNamespace=NS,template="empty",batch="background_live",timeoutTicks=60000)
    public static void coldPhysicalSupplyUsesOnlyEconomicTicketsAndReopensWithoutDuplicateFee(GameTestHelper h) {
        var level=h.getLevel();var marker=level.getServer().getWorldPath(LevelResource.ROOT).resolve("economic-background-restart.dat");
        RoyalNpcConfig.BACKGROUND_ENABLED.set(false);RoyalNpcConfig.ECONOMIC_ENABLED.set(false);
        class Probe implements Runnable {
            RoyalShipmentSupport.Realm realm; final List<ChunkPos> terrain=new ArrayList<>();
            int cursor,ticks,phase;long cold=-1;UUID order;int quantity,sourceAfter;long fee;
            Probe() {
                if(RoyalShipmentSupport.read()) {
                    var tag=HistoryFiles.read(marker);realm=RoyalShipmentSupport.restore(h,tag.getCompound("realm"),false);
                    order=tag.getUUID("order");quantity=tag.getInt("quantity");sourceAfter=tag.getInt("sourceAfter");fee=tag.getLong("fee");
                    h.assertTrue(realm.store().load(order).stage()==RoyalShipmentStore.Stage.IN_TRANSIT,"Exact real cargo reopens in a separate JVM before delivery");phase=1;
                } else {
                    var base=new BlockPos(900000,-50,920000);
                    var cap=RoyalShipmentSupport.village(level,"Cold economy capital",base);
                    var source=RoyalShipmentSupport.village(level,"Cold economy source",base.offset(64,0,0));
                    var target=RoyalShipmentSupport.village(level,"Cold economy target",base.offset(128,0,0));
                    var data=KingdomSavedData.get(level);var k=data.create("Cold economy fixture",cap.getId().uuid());data.attach(k.id(),source.getId().uuid());data.attach(k.id(),target.getId().uuid());
                    realm=new RoyalShipmentSupport.Realm(level,k.id(),cap,source,target);RoyalNpcEconomyGameTests.shortage(h,realm);
                    var needed=new LinkedHashSet<ChunkPos>();
                    for(var village:List.of(cap,source,target)) {
                        for(var pos:village.computeVillageChunks())for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)needed.add(new ChunkPos(pos.x+x,pos.z+z));
                        int radius=org.millenaire.culture.ModCultures.getVillageType(village.getVillageTypeId()).radius();var center=village.getCenter();
                        for(int x=(center.getX()-radius)>>4;x<=(center.getX()+radius)>>4;x++)for(int z=(center.getZ()-radius)>>4;z<=(center.getZ()+radius)>>4;z++)needed.add(new ChunkPos(x,z));
                    }
                    terrain.addAll(needed);
                }
                level.getServer().tickRateManager().stopSprinting();
            }
            public void run() {
                if(++ticks>59000 || phase<2 && ticks>2000){cleanup();h.fail("Bounded background supply timeout phase="+phase+" cold="+cold+" report="+RoyalEconomicActivation.report(level));return;}
                if(ticks%200==0 && phase<2)System.out.println("ECONOMIC_BACKGROUND_SETUP phase="+phase+" cursor="+cursor+"/"+terrain.size()+" ticks="+ticks+" loaded="+List.of(realm.capital(),realm.source(),realm.target()).stream().map(v->level.isLoaded(v.getCenter())+"/active="+v.isActive()+"/nativeForced="+v.isChunksForceLoaded()).toList()+" forced="+level.getForcedChunks().size());
                if(phase==0) {
                    for(int n=0;n<4 && cursor<terrain.size();n++){var pos=terrain.get(cursor++);level.getChunk(pos.x,pos.z);}
                    if(cursor==terrain.size()){realm.save();phase=1;}
                } else if(phase==1) {
                    if(List.of(realm.capital(),realm.source(),realm.target()).stream().noneMatch(v->level.isLoaded(v.getCenter()))) {
                        if(cold<0)cold=level.getGameTime();
                        // Allow vanilla's time-budgeted pending protochunk unload/save queue to drain before tick sprint.
                        // Center-unloaded alone does not mean the surrounding generation dependencies reached storage.
                        if(level.getGameTime()-cold<200){h.runAfterDelay(1,()->run());return;}
                        realm.save();level.getServer().tickRateManager().requestGameToSprint(59000);NEW_CHUNKS.put(level,0);RoyalNpcConfig.ECONOMIC_ENABLED.set(true);RoyalNpcConfig.BACKGROUND_ENABLED.set(true);phase=2;
                        System.out.println("ECONOMIC_BACKGROUND_COLD tick="+cold+" preparedChunks="+terrain.size()+" players="+level.players().size());
                    }
                } else {
                    h.assertTrue(level.getServer().getPlayerList().getPlayers().isEmpty(),"No player or fixture force-activation keeps these villages running");
                    var rows=realm.store().entries().stream().filter(e->e.intent().kingdom().equals(realm.kingdom())).toList();
                    if(rows.size()>1){cleanup();h.fail("Original shortage produced duplicate cargo domains");return;}
                    if(!rows.isEmpty()) {
                        var row=rows.getFirst();
                        if(!RoyalShipmentSupport.read() && row.stage()==RoyalShipmentStore.Stage.IN_TRANSIT) {
                            var product=net.minecraft.core.registries.BuiltInRegistries.ITEM.get(row.intent().product());
                            h.assertTrue(RoyalEconomicActivation.ownsLease(level) && row.escrow()>0 && realm.coins()==256-row.intent().fee()
                                    && !realm.capital().isForceActive() && !realm.source().isForceActive() && !realm.target().isForceActive() && NEW_CHUNKS.get(level)==0,
                                    "Ordinary production events activate cold real storage, pay one fee and collect physical cargo without generating terrain or setting native force flags");
                            var tag=new CompoundTag();tag.put("realm",realm.marker());tag.putUUID("order",row.intent().id());tag.putInt("quantity",row.escrow());tag.putInt("sourceAfter",RoyalNpcEconomyGameTests.count(realm,realm.source(),product));tag.putLong("fee",row.intent().fee());realm.save();HistoryFiles.write(marker,tag);
                            System.out.println("ECONOMIC_BACKGROUND_WRITE coldTicks="+(level.getGameTime()-cold)+" fee="+row.intent().fee()+" cargo="+row.escrow()+" generated="+NEW_CHUNKS.get(level)+" report="+RoyalEconomicActivation.report(level));
                            releasedSuccess(false);return;
                        }
                        if(RoyalShipmentSupport.read() && row.stage()==RoyalShipmentStore.Stage.DELIVERED) {
                            var product=net.minecraft.core.registries.BuiltInRegistries.ITEM.get(row.intent().product());
                            h.assertTrue(row.intent().id().equals(order) && row.delivered()==quantity && row.escrow()==0 && realm.coins()==256-fee
                                    && RoyalNpcEconomyGameTests.count(realm,realm.source(),product)==sourceAfter && RoyalNpcEconomyGameTests.count(realm,realm.target(),product)==quantity
                                    && NEW_CHUNKS.get(level)==0,"Normal events deliver exact reopened cargo with no duplicate debit or source withdrawal and no generated terrain");
                            System.out.println("ECONOMIC_BACKGROUND_REOPEN fee="+fee+" delivered="+quantity+" newChunks="+NEW_CHUNKS.get(level)+" coldTicks="+(level.getGameTime()-cold));
                            releasedSuccess(true);return;
                        }
                    }
                    if(ticks%3000==0)System.out.println("ECONOMIC_BACKGROUND_PROGRESS phase="+phase+" cold="+cold+" rows="+rows+" report="+RoyalEconomicActivation.report(level));
                }
                h.runAfterDelay(1,()->run());
            }
            void releasedSuccess(boolean removeRealm) {
                cleanup();h.runAfterDelay(2,()->{h.assertTrue(!RoyalEconomicActivation.ownsLease(level) && RoyalEconomicActivation.report(level).ownedTickets()==0,
                        "Disabling work releases only the addon's temporary economic lease, with no persisted forced chunks");if(removeRealm)realm.close();h.succeed();});
            }
            void cleanup() {NEW_CHUNKS.remove(level);RoyalNpcConfig.ECONOMIC_ENABLED.set(true);RoyalNpcConfig.BACKGROUND_ENABLED.set(false);level.getServer().tickRateManager().stopSprinting();}
        }
        h.runAfterDelay(1,new Probe());
    }
}
