package com.millenairekingdoms.test;

import com.millenairekingdoms.construction.RoyalVillagePersistence;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.village.Village;

/** Real original SavedData worker/files. These metadata barriers do not assert payment or genuine completed construction. */
@PrefixGameTestTemplate(false)
public final class RoyalVillagePersistenceGameTests {
    private static final String NS="millenairekingdoms_royal_construction_tests";

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void actualPendingAndRemovalAreAcknowledgedByOriginalSaveWorker(GameTestHelper h) throws Exception {
        try(var f=RoyalCatalogGameTests.eligible(h)) {
            var option=f.catalog().options().getFirst(); var pending=new Village.PendingProject(option.planSet(),option.variant(),0,false,null);
            var barrier=new RoyalVillagePersistence(); var id=f.village().getId().uuid();
            f.village().setPendingProject(pending); barrier.pending(f.level(),id,pending);
            h.assertTrue(Files.isRegularFile(file(f.level())) && f.village().getPendingProject()==pending,"Actual original worker persisted the exact untouched pending project");
            refused(h,()->barrier.absentPending(f.level(),id));
            f.village().setPendingProject(null); barrier.absentPending(f.level(),id);
            h.assertTrue(f.village().getOwnerUUID()==null,"Acknowledgement never takes ownership or inserts/removes the live project itself"); h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void staleFileCannotProvePendingOrBuildingAcceptance(GameTestHelper h) throws Exception {
        try(var f=RoyalCatalogGameTests.eligible(h)) {
            var actual=new RoyalVillagePersistence(); var village=f.village(); var id=village.getId().uuid(); var building=village.getTownhall();
            actual.building(f.level(),id,building); // Acknowledge metadata only, never a claim that this fixture was built by NPCs.
            var stale=new RoyalVillagePersistence() { @Override protected void flush(ServerLevel level) {} };
            var origin=building.getOrigin(); building.setOrigin(origin.offset(1,0,0));
            try { refused(h,()->stale.building(f.level(),id,building)); } finally { building.setOrigin(origin); }
            var option=f.catalog().options().getFirst(); var pending=new Village.PendingProject(option.planSet(),option.variant(),0,false,null);
            village.setPendingProject(pending); refused(h,()->stale.pending(f.level(),id,pending));
            actual.pending(f.level(),id,pending); village.setPendingProject(null); refused(h,()->stale.absentPending(f.level(),id));
            h.assertTrue(village.getPendingProject()==null,"Failed read-back does not speculate or modify live engine work"); h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void futureAndCorruptOriginalVillageFilesArePreservedBeforeSave(GameTestHelper h) throws Exception {
        try(var f=RoyalCatalogGameTests.eligible(h)) {
            var option=f.catalog().options().getFirst(); var pending=new Village.PendingProject(option.planSet(),option.variant(),0,false,null);
            f.village().setPendingProject(pending); var barrier=new RoyalVillagePersistence(); var id=f.village().getId().uuid(); barrier.pending(f.level(),id,pending);
            IOUtilities.waitUntilIOWorkerComplete(); var file=file(f.level()); byte[] original=Files.readAllBytes(file);
            try {
                var root=new CompoundTag(); var data=new CompoundTag(); data.putInt("version",999); data.put("villages",new ListTag()); root.put("data",data); NbtIo.writeCompressed(root,file);
                byte[] future=Files.readAllBytes(file); refused(h,()->barrier.pending(f.level(),id,pending));
                h.assertTrue(Arrays.equals(future,Files.readAllBytes(file)),"Future original village data is rejected before triggering a downgrade save");
                data.putInt("version",6); var wrongList=new ListTag(); wrongList.add(net.minecraft.nbt.StringTag.valueOf("not a village")); data.put("villages",wrongList); NbtIo.writeCompressed(root,file);
                byte[] wrongType=Files.readAllBytes(file); refused(h,()->barrier.pending(f.level(),id,pending));
                h.assertTrue(Arrays.equals(wrongType,Files.readAllBytes(file)),"Wrong-typed original village list is preserved before any save");
                Files.write(file,new byte[]{1,2,3}); byte[] corrupt=Files.readAllBytes(file); refused(h,()->barrier.pending(f.level(),id,pending));
                h.assertTrue(Arrays.equals(corrupt,Files.readAllBytes(file)),"Corrupt original is preserved, not overwritten from cached memory");
            } finally { Files.write(file,original); }
            h.succeed();
        }
    }

    private static Path file(ServerLevel level) { return DimensionType.getStorageFolder(level.dimension(),level.getServer().getWorldPath(LevelResource.ROOT)).resolve("data/millenaire_villages.dat"); }
    private static void refused(GameTestHelper h,Runnable action) {
        boolean rejected=false; try { action.run(); } catch(IllegalArgumentException|IllegalStateException expected) { rejected=true; }
        h.assertTrue(rejected,"Unacknowledged/future/corrupt domain data cannot prove order acceptance");
    }
}
