package com.millenairekingdoms.test;

import com.millenairekingdoms.construction.RoyalConstructionStore;
import com.millenairekingdoms.construction.RoyalConstructionStore.Blocked;
import com.millenairekingdoms.construction.RoyalConstructionStore.Intent;
import com.millenairekingdoms.construction.RoyalConstructionStore.Stage;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Atomic order metadata only. No payment, queue insertion, builders or finished-world construction is claimed. */
@PrefixGameTestTemplate(false)
public final class RoyalOrderStoreGameTests {
    private static final String NS="millenairekingdoms_royal_construction_tests";
    private static final ResourceLocation TYPE=ResourceLocation.parse("millenaire:byzantines/chorio");
    private static final ResourceLocation SET=ResourceLocation.parse("millenaire:byzantines/archives");
    private static final ResourceLocation PLAN=ResourceLocation.parse("millenaire:byzantines/archives_a_0");
    private static Intent intent(UUID id,UUID village,long fee,String variant) {
        return new Intent(id,"minecraft:overworld",new UUID(80,1),new UUID(80,2),village,null,0,TYPE,SET,PLAN,variant,fee,100,Set.of(new UUID(80,3)));
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void versionThreeIntentMigratesOnlyOnMutationAndNewWaitingStateCannotMasqueradeAsLegacy(GameTestHelper h) throws Exception {
        var path=directory(h);var store=new RoyalConstructionStore(path);var original=intent(UUID.randomUUID(),UUID.randomUUID(),64,"a");store.reserve(original);
        var file=path.resolve("orders.dat");var tag=NbtIo.readCompressed(file,net.minecraft.nbt.NbtAccounter.unlimitedHeap());tag.putInt("format",3);NbtIo.writeCompressed(tag,file);var bytes=Files.readAllBytes(file);
        h.assertTrue(store.load(original.id()).intent().equals(original) && Arrays.equals(bytes,Files.readAllBytes(file)),"Format3 reads preserve every bound field and original bytes");
        store.reserve(intent(UUID.randomUUID(),UUID.randomUUID(),64,"a"));var migrated=NbtIo.readCompressed(file,net.minecraft.nbt.NbtAccounter.unlimitedHeap());
        h.assertTrue(migrated.getInt("format")==4 && store.load(original.id()).intent().equals(original),"A locked mutation upgrades to4 without rebinding legacy intent");
        tag.putInt("format",3);var entry=tag.getList("orders",Tag.TAG_COMPOUND).getCompound(0);entry.putString("stage","WAITING");entry.getCompound("intent").putUUID("actor",UUID.randomUUID());entry.getCompound("intent").putLong("rulerSince",1);
        NbtIo.writeCompressed(tag,file);bytes=Files.readAllBytes(file);refused(h,()->store.load(original.id()));
        h.assertTrue(Arrays.equals(bytes,Files.readAllBytes(file)),"A new waiting state marked as legacy is rejected without rewriting it");h.succeed();
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void firstPublicationExcludesPrecedingNativeBuildingsWithoutChangingFinancialIdentity(GameTestHelper h) throws Exception {
        var store=new RoyalConstructionStore(directory(h));var original=intent(UUID.randomUUID(),UUID.randomUUID(),64,"a");store.reserve(original);
        var method=RoyalConstructionStore.class.getDeclaredMethod("beginPublication",UUID.class,Set.class);method.setAccessible(true);
        boolean refused=false;try{method.invoke(store,original.id(),Set.of());}catch(java.lang.reflect.InvocationTargetException expected){refused=expected.getCause() instanceof IllegalStateException;}
        h.assertTrue(refused && store.load(original.id()).stage()==Stage.REQUESTED,"Unpaid requests cannot establish publication provenance");
        store.change(original.id(),Stage.REQUESTED,Stage.PAID,Blocked.NONE,null);var nativeBuilding=UUID.randomUUID();
        var published=(RoyalConstructionStore.Entry)method.invoke(store,original.id(),Set.of(nativeBuilding));var i=published.intent();
        h.assertTrue(published.stage()==Stage.QUEUING && i.originalBuildings().containsAll(original.originalBuildings()) && i.originalBuildings().contains(nativeBuilding)
                && i.id().equals(original.id()) && i.actor()==original.actor() && i.kingdom().equals(original.kingdom()) && i.capital().equals(original.capital()) && i.plan().equals(original.plan()) && i.variant().equals(original.variant()) && i.fee()==64 && i.issuedGameTime()==100,"The atomic pre-publication baseline includes old and newly completed native identities while preserving every payment/plan binding");
        refused=false;try{method.invoke(store,original.id(),Set.of(UUID.randomUUID()));}catch(java.lang.reflect.InvocationTargetException expected){refused=expected.getCause() instanceof IllegalStateException;}
        h.assertTrue(refused && store.load(original.id()).equals(published),"Published/uncertain work can never refresh its identity evidence again");h.succeed();
    }

    private static Path directory(GameTestHelper h) { return h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("royal-order-tests").resolve(UUID.randomUUID().toString()); }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void completeOrderIntentIsFrozenAndReplayCannotRepriceOrReset(GameTestHelper h) {
        var store=new RoyalConstructionStore(directory(h)); var id=UUID.randomUUID(); var village=UUID.randomUUID(); var request=intent(id,village,64,"a");
        store.reserve(request); store.change(id,Stage.REQUESTED,Stage.PAID,Blocked.NONE,null);
        h.assertTrue(store.reserve(request).stage()==Stage.PAID,"Same intent cannot reset paid order metadata");
        refused(h,()->store.reserve(intent(id,village,65,"a"))); refused(h,()->store.reserve(intent(id,village,64,"b")));
        refused(h,()->store.reserve(intent(id,UUID.randomUUID(),64,"a")));
        h.assertTrue(store.load(id).intent().fee()==64 && store.load(id).intent().variant().equals("a"),"Stored quote survives fee/variant/village conflicts"); h.succeed();
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void oneActiveOrderPerVillageIsAtomicEvenAcrossKingdomChanges(GameTestHelper h) {
        var path=directory(h); var store=new RoyalConstructionStore(path); var village=UUID.randomUUID(); var first=intent(UUID.randomUUID(),village,64,"a");
        store.reserve(first); var second=intent(UUID.randomUUID(),village,64,"a");
        refused(h,()->new RoyalConstructionStore(path).reserve(second));
        var captured=new Intent(second.id(),second.dimension(),UUID.randomUUID(),UUID.randomUUID(),village,null,0,TYPE,SET,PLAN,"a",64,101,Set.of());
        refused(h,()->store.reserve(captured));
        store.change(first.id(),Stage.REQUESTED,Stage.CANCELLING,Blocked.AUTHORITY,null); refused(h,()->store.reserve(second));
        store.change(first.id(),Stage.CANCELLING,Stage.CANCELLED,Blocked.NONE,null);
        h.assertTrue(store.reserve(second).stage()==Stage.REQUESTED && store.reserve(first).stage()==Stage.CANCELLED,"Terminal cancellation releases only the village reservation, never resets old request"); h.succeed();
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void lifecycleCannotJumpToCompletionOrClaimAnOldBuilding(GameTestHelper h) {
        var store=new RoyalConstructionStore(directory(h)); var request=intent(UUID.randomUUID(),UUID.randomUUID(),64,"a"); var id=request.id(); store.reserve(request);
        refused(h,()->store.change(id,Stage.REQUESTED,Stage.COMPLETED,Blocked.NONE,UUID.randomUUID()));
        refused(h,()->store.change(id,Stage.REQUESTED,Stage.PAID,Blocked.NONE,request.originalBuildings().iterator().next()));
        store.change(id,Stage.REQUESTED,Stage.PAID,Blocked.NONE,null); store.change(id,Stage.PAID,Stage.QUEUING,Blocked.NONE,null);
        store.change(id,Stage.QUEUING,Stage.QUEUED,Blocked.MATERIALS,null);
        refused(h,()->store.change(id,Stage.QUEUED,Stage.IN_PROGRESS,Blocked.NONE,null));
        var building=UUID.randomUUID(); store.change(id,Stage.QUEUED,Stage.IN_PROGRESS,Blocked.NONE,building);
        refused(h,()->store.change(id,Stage.IN_PROGRESS,Stage.COMPLETED,Blocked.NONE,UUID.randomUUID()));
        store.change(id,Stage.IN_PROGRESS,Stage.COMPLETED,Blocked.NONE,building);
        h.assertTrue(store.reserve(request).stage()==Stage.COMPLETED,"Historical completed metadata is a tombstone, never another request");
        refused(h,()->store.change(id,Stage.COMPLETED,Stage.REQUESTED,Blocked.NONE,building)); h.succeed();
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void futureCorruptAndUnknownOrderDataAreNeverOverwritten(GameTestHelper h) throws Exception {
        var path=directory(h); var store=new RoyalConstructionStore(path); var file=path.resolve("orders.dat"); var request=intent(UUID.randomUUID(),UUID.randomUUID(),64,"a");
        var future=new CompoundTag(); future.putInt("format",999); future.putString("futureData","preserve"); NbtIo.writeCompressed(future,file);
        byte[] original=Files.readAllBytes(file); refused(h,()->store.reserve(request));
        h.assertTrue(Arrays.equals(original,Files.readAllBytes(file)),"Future schema remains byte-for-byte unchanged");
        var wrongType=new CompoundTag(); wrongType.putInt("format",1); var strings=new ListTag(); strings.add(StringTag.valueOf("not an order")); wrongType.put("orders",strings); NbtIo.writeCompressed(wrongType,file);
        original=Files.readAllBytes(file); refused(h,()->store.reserve(request)); h.assertTrue(Arrays.equals(original,Files.readAllBytes(file)),"Wrong list type never becomes an empty index");
        var unknown=new CompoundTag(); unknown.putInt("format",1); unknown.put("orders",new ListTag()); unknown.putString("unknownExtension","preserve"); NbtIo.writeCompressed(unknown,file);
        original=Files.readAllBytes(file); refused(h,()->store.reserve(request)); h.assertTrue(Arrays.equals(original,Files.readAllBytes(file)),"Unknown fields are preserved rather than discarded by typed re-encoding");
        Files.write(file,new byte[]{1,2,3}); original=Files.readAllBytes(file); refused(h,()->store.entries());
        h.assertTrue(Arrays.equals(original,Files.readAllBytes(file)),"Corrupt original is retained for diagnosis"); h.succeed();
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void realFileLockAndDefensiveBuildingSnapshotProtectReservation(GameTestHelper h) throws Exception {
        var path=directory(h); var store=new RoyalConstructionStore(path); var source=new HashSet<UUID>(); source.add(new UUID(80,3));
        var base=intent(UUID.randomUUID(),UUID.randomUUID(),64,"a");
        var request=new Intent(base.id(),"overworld",base.kingdom(),base.capital(),base.village(),null,0,TYPE,SET,PLAN,"a",64,100,source); source.clear();
        h.assertTrue(request.dimension().equals("minecraft:overworld") && request.originalBuildings().size()==1,"Dimension aliases canonicalize and mutable caller sets cannot alter the bound original buildings");
        try(var channel=FileChannel.open(path.resolve(".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.lock()) {
            refused(h,()->store.reserve(request)); h.assertTrue(!Files.exists(path.resolve("orders.dat")),"Actual contending file lock prevents reservation before any charge");
        }
        store.reserve(request); boolean frozen=false; try { store.load(request.id()).intent().originalBuildings().clear(); } catch(UnsupportedOperationException expected) { frozen=true; }
        h.assertTrue(frozen && store.load(request.id()).intent().originalBuildings().size()==1,"Returned order has no mutable reference back into its snapshot"); h.succeed();
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void unresolvedAndProgressMetadataReopenWithoutResetInSeparateJvm(GameTestHelper h) {
        var path=h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("royal-orders-restart"); var store=new RoyalConstructionStore(path);
        var pending=intent(new UUID(81,1),new UUID(82,1),64,"a"); var progress=intent(new UUID(81,2),new UUID(82,2),128,"a"); var building=new UUID(83,1);
        if("read".equals(System.getProperty("kingdoms.tests.persistence"))) {
            h.assertTrue(store.load(pending.id()).stage()==Stage.REQUESTED && store.load(progress.id()).stage()==Stage.IN_PROGRESS
                    && building.equals(store.load(progress.id()).building()) && store.load(progress.id()).intent().fee()==128,"Original intent/stage/building/price survives independent JVM replacement");
            h.assertTrue(store.reserve(progress).stage()==Stage.IN_PROGRESS,"Restart retry never reissues an in-progress record");
            System.out.println("ROYAL_ORDER_STORE_RESTART_READ_PASS");
        } else {
            store.reserve(pending); store.reserve(progress); store.change(progress.id(),Stage.REQUESTED,Stage.PAID,Blocked.NONE,null);
            store.change(progress.id(),Stage.PAID,Stage.QUEUING,Blocked.NONE,null); store.change(progress.id(),Stage.QUEUING,Stage.QUEUED,Blocked.MATERIALS,null);
            store.change(progress.id(),Stage.QUEUED,Stage.IN_PROGRESS,Blocked.NONE,building); System.out.println("ROYAL_ORDER_STORE_RESTART_WRITE_PASS");
        }
        h.succeed();
    }

    private static void refused(GameTestHelper h,Runnable action) {
        boolean rejected=false; try { action.run(); } catch(IllegalArgumentException|IllegalStateException expected) { rejected=true; }
        h.assertTrue(rejected,"Invalid/conflicting/busy/ambiguous order metadata must fail closed");
    }
}
