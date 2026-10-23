package com.millenairekingdoms.test;

import com.millenairekingdoms.construction.RoyalConstructionCatalog;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.mojang.authlib.GameProfile;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.building.BuildingId;
import org.millenaire.building.BuildingInstance;
import org.millenaire.culture.ModCultures;
import org.millenaire.culture.VillageType;
import org.millenaire.village.Village;
import org.millenaire.village.VillageId;
import org.millenaire.village.VillageSavedData;

/** Original 9.0.3 eligibility metadata and real political authority. Does NOT claim completed NPC construction. */
@PrefixGameTestTemplate(false)
public final class RoyalCatalogGameTests {
    private static final String NS="millenairekingdoms_royal_construction_tests";
    private static final AtomicInteger NEXT=new AtomicInteger();

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void originalNpcVillageTypesOfferOnlyRealCulturalNewBuildings(GameTestHelper h) throws Exception {
        int types=0,offers=0;
        for(var type:ModCultures.getAllVillageTypes().values().stream().filter(t->!t.loneBuilding() && !t.playerControlled())
                .sorted(Comparator.comparing(t->t.id().toString())).toList()) {
            try(var f=fixture(h,type)) {
                var catalog=f.catalog(); types++;
                h.assertTrue(f.village.getOwnerUUID()==null && f.village.getPendingProject()==null,"Catalog never takes control or schedules work");
                for(var option:catalog.options()) {
                    var set=ModCultures.getBuildingPlanSet(option.planSet()); var plan=ModCultures.getBuildingPlan(option.plan());
                    h.assertTrue(set!=null && plan!=null && set.culture().equals(type.culture()) && plan.culture().equals(type.culture())
                            && !set.isTownHall() && !set.isSubBuilding() && set.getLevel(option.variant(),0).planId().equals(option.plan()),
                            "Offered new building is an original legal culture plan, never an upgrade or invented template"); offers++;
                }
            }
        }
        h.assertTrue(types>20 && offers>20,"Exercise the original content catalog across cultures, not a synthetic single-plan mock");
        System.out.println("ROYAL_CATALOG_ORIGINAL_CONTENT types="+types+" offers="+offers); h.succeed();
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void actualPlacementCooldownAndExistingCountsRemoveChoices(GameTestHelper h) throws Exception {
        try(var f=eligible(h)) {
            var choice=f.catalog().options().getFirst();
            f.village.addPlacementCooldown(choice.planSet(),h.getLevel().getGameTime()+1600);
            h.assertTrue(f.catalog().options().stream().noneMatch(o->o.planSet().equals(choice.planSet())),"Engine placement cooldown excludes the offered plan");
            f.village.addPlacementCooldown(choice.planSet(),0);
            int added=0;
            while(f.catalog().options().stream().anyMatch(o->o.planSet().equals(choice.planSet())) && added<128) {
                // Completed metadata models the engine's existing-count input; this is not a construction success fixture.
                f.village.addBuilding(new BuildingInstance(BuildingId.random(),choice.plan(),f.village.getCenter().offset(++added*2,0,0),Rotation.NONE,
                        BuildingInstance.Status.COMPLETE,choice.planSet(),choice.variant(),0));
            }
            h.assertTrue(added>0 && added<128 && f.catalog().options().stream().noneMatch(o->o.planSet().equals(choice.planSet())),
                    "Existing layout/plan counts eventually prohibit another copy through the engine's normal eligibility"); h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void naturalPendingWorkIsReportedAndNeverOverwritten(GameTestHelper h) throws Exception {
        try(var f=eligible(h)) {
            var choice=f.catalog().options().getFirst();
            var pending=new Village.PendingProject(choice.planSet(),choice.variant(),0,false,null); f.village.setPendingProject(pending);
            int buildings=f.village.getBuildings().size(); var catalog=f.catalog();
            h.assertTrue(catalog.naturalProjectPending() && f.village.getPendingProject()==pending && f.village.getBuildings().size()==buildings
                    && f.village.getOwnerUUID()==null,"Preview preserves natural pending project, building records and NPC ownership");
            boolean immutable=false; try { catalog.options().clear(); } catch(UnsupportedOperationException expected) { immutable=true; }
            h.assertTrue(immutable,"Caller cannot mutate frozen choices");
            if(!catalog.options().isEmpty()) {
                immutable=false; try { catalog.options().getFirst().requiredMaterials().clear(); } catch(UnsupportedOperationException expected) { immutable=true; }
                h.assertTrue(immutable,"Caller cannot erase the actual plan's resource requirements");
            }
            h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void currentRulerMembershipAndNpcOwnershipAreRechecked(GameTestHelper h) throws Exception {
        try(var f=eligible(h);var other=eligible(h)) {
            refused(h,()->RoyalConstructionCatalog.forNpc(f.level,other.kingdom,f.village.getId().uuid()));
            var p=new FakePlayer(f.level,new GameProfile(UUID.randomUUID(),"Royal catalog ruler"));
            f.village.addReputation(p.getUUID(),32768-f.village.getCombinedReputation(f.level,p.getUUID()));
            var data=KingdomSavedData.get(f.level); data.claimRule(f.kingdom,p.getUUID(),"Royal catalog ruler",1);
            refused(h,()->f.catalog());
            h.assertTrue(RoyalConstructionCatalog.forPlayer(p,f.village.getId().uuid()).kingdom().equals(f.kingdom),"Current real ruler gets their own member's catalog");
            f.village.addReputation(p.getUUID(),-f.village.getCombinedReputation(f.level,p.getUUID()));
            refused(h,()->RoyalConstructionCatalog.forPlayer(p,f.village.getId().uuid()));
            data.abdicate(f.kingdom,p.getUUID()); f.village.setOwner(p.getUUID(),"Local owner"); refused(h,()->f.catalog());
            h.assertTrue(f.village.getOwnerUUID().equals(p.getUUID()),"Catalog rejects local control without clearing or taking ownership"); h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void unloadedPreviewDoesNotForceChunksAndAsyncAccessIsRejected(GameTestHelper h) throws Exception {
        try(var f=eligible(h)) {
            var center=f.village.getCenter(); h.assertTrue(!f.level.isLoaded(center),"Fixture starts in an actually unloaded remote chunk");
            int forced=f.level.getForcedChunks().size(); var catalog=f.catalog();
            h.assertTrue(!catalog.active() && !catalog.options().isEmpty() && !f.level.isLoaded(center) && forced==f.level.getForcedChunks().size(),
                    "Read-only eligibility preview uses metadata without loading/forcing remote terrain");
            h.assertTrue(CompletableFuture.supplyAsync(()->{
                try { f.catalog(); return false; } catch(IllegalStateException expected) { return true; }
            }).join(),"Worker threads cannot inspect mutable server political/growth state"); h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void originalCatalogAndPoliticalMetadataReopenInSeparateJvm(GameTestHelper h) throws Exception {
        Path file=h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("royal-catalog-restart.dat");
        if("read".equals(System.getProperty("kingdoms.tests.persistence"))) {
            var tag=NbtIo.readCompressed(file,NbtAccounter.create(1024*1024));
            var catalog=RoyalConstructionCatalog.forNpc(h.getLevel(),tag.getUUID("kingdom"),tag.getUUID("village"));
            h.assertTrue(!catalog.options().isEmpty() && !catalog.naturalProjectPending(),"Original saved village/type/kingdom metadata reopens without fixture reconstruction");
            System.out.println("ROYAL_CATALOG_RESTART_READ_PASS choices="+catalog.options().size()); h.succeed(); return;
        }
        var f=eligible(h); // Retain only this fixture for the reload JVM; no fake/finished world building is placed.
        var tag=new CompoundTag(); tag.putUUID("kingdom",f.kingdom); tag.putUUID("village",f.village.getId().uuid()); NbtIo.writeCompressed(tag,file);
        VillageSavedData.get(f.level).setDirty(); KingdomSavedData.get(f.level).setDirty();
        h.assertTrue(!f.catalog().options().isEmpty(),"Write JVM stores a real eligible original village type");
        System.out.println("ROYAL_CATALOG_RESTART_WRITE_PASS"); h.succeed();
    }

    private record Fixture(ServerLevel level,Village village,UUID kingdom) implements AutoCloseable {
        RoyalConstructionCatalog.Catalog catalog() { return RoyalConstructionCatalog.forNpc(level,kingdom,village.getId().uuid()); }
        @Override public void close() { VillageSavedData.get(level).getVillageManager().removeVillage(village.getId()); }
    }
    private static Fixture fixture(GameTestHelper h,VillageType type) {
        var level=h.getLevel(); var center=new BlockPos(7_000_000+NEXT.incrementAndGet()*512,64,7_000_000);
        var village=new Village(VillageId.random(),type.culture(),type.id(),center); village.setVillageName("Royal catalog");
        var hall=type.layout().stream().filter(s->s.role().equals("centre") || s.role().equals("townhall")).findFirst();
        if(hall.isPresent()) {
            var set=ModCultures.getBuildingPlanSet(hall.get().plan());
            if(set!=null && !set.variants().isEmpty()) {
                var variant=set.variants().keySet().stream().sorted().findFirst().orElseThrow(); var def=set.getLevel(variant,0);
                if(def!=null) village.addBuilding(new BuildingInstance(BuildingId.random(),def.planId(),center,Rotation.NONE,BuildingInstance.Status.COMPLETE,set.id(),variant,0));
            }
        }
        VillageSavedData.get(level).getVillageManager().addVillage(village);
        var kingdom=KingdomSavedData.get(level).create("Royal catalog "+UUID.randomUUID(),village.getId().uuid());
        return new Fixture(level,village,kingdom.id());
    }
    private static Fixture eligible(GameTestHelper h) {
        for(var type:ModCultures.getAllVillageTypes().values().stream().filter(t->!t.loneBuilding() && !t.playerControlled())
                .sorted(Comparator.comparing(t->t.id().toString())).toList()) {
            var f=fixture(h,type); if(!f.catalog().options().isEmpty()) return f; f.close();
        }
        throw new IllegalStateException("No original NPC village has a normal new-building candidate");
    }
    private static void refused(GameTestHelper h,Runnable action) {
        boolean rejected=false; try { action.run(); } catch(IllegalArgumentException|IllegalStateException expected) { rejected=true; }
        h.assertTrue(rejected,"Wrong ruler/membership/local ownership must reject royal construction preview");
    }
}
