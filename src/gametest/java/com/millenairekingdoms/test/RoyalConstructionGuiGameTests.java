package com.millenairekingdoms.test;

import com.millenairekingdoms.construction.RoyalConstructionCatalog;
import com.millenairekingdoms.construction.RoyalConstructionStore;
import com.millenairekingdoms.construction.RoyalConstructionRuntime;
import com.millenairekingdoms.construction.RoyalConstructionService;
import com.millenairekingdoms.construction.RoyalConstructionStore.Blocked;
import com.millenairekingdoms.construction.RoyalConstructionStore.Stage;
import com.millenairekingdoms.economy.RoyalEconomyConfig;
import com.millenairekingdoms.economy.RoyalPaymentService;
import com.millenairekingdoms.economy.RoyalPaymentJournal;
import com.millenairekingdoms.economy.RoyalTreasurySnapshot;
import com.millenairekingdoms.gui.KingdomActions;
import com.millenairekingdoms.gui.KingdomScreens;
import com.millenairekingdoms.gui.RoyalConstructionView;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.network.KingdomActionPayload;
import com.millenairekingdoms.network.KingdomNetwork;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.building.BuildingInstance;
import org.millenaire.building.BuildingInventory;
import org.millenaire.building.BuildingId;
import org.millenaire.culture.ModCultures;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Rotation;
import org.millenaire.item.ModItems;
import org.millenaire.village.Village;
import org.millenaire.village.VillageId;
import org.millenaire.village.VillageSavedData;

/** Actual player action handlers, read-only snapshots, real physical coins/original pending, codec and reload. */
@PrefixGameTestTemplate(false)
public final class RoyalConstructionGuiGameTests {
    private static final String NS="millenairekingdoms_royal_gui_tests";

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void previewShowsNativePlansAndStableServerOffersWithoutWorldMutation(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"gui-preview")) {
            var before=VillageSavedData.get(f.f.level()).save(new CompoundTag(),f.f.level().registryAccess()).copy();
            var frame=frame(f.player);var offer=first(frame);var again=first(frame(f.player));
            h.assertTrue(offer.getUUID("offer").equals(again.getUUID("offer")) && offer.getLong("fee")==64 && offer.getBoolean("canOrder")
                    && !offer.getString("name").isBlank() && frame.getLong("spendable")==128 && frame.getBoolean("available"),"Repeated read-only snapshots retain one bounded server offer and actual spendable funds");
            h.assertTrue(before.equals(VillageSavedData.get(f.f.level()).save(new CompoundTag(),f.f.level().registryAccess()))
                    && f.f.village().getPendingProject()==null && f.f.value()==128,"Preview never pays, publishes engine work or changes native villages");h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void offeredPlayerPurchaseAndRepeatedPayloadUseOnlyServerFeeAndOneOrder(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"gui-purchase")) {
            var offer=first(frame(f.player));var request=order(f,offer,Long.MIN_VALUE);send(f.player,request);var pending=f.f.village().getPendingProject();
            send(f.player,request);var current=store(f.f.level()).load(offer.getUUID("offer"));
            h.assertTrue(current.stage()==Stage.QUEUED && current.intent().actor().equals(f.player.getUUID()) && current.intent().fee()==64
                    && f.f.value()==64 && f.f.village().getPendingProject()==pending && receipt(f,current).state()==RoyalPaymentJournal.State.COMMITTED,
                    "Forged client amount cannot set price; identical payload retains the same immutable order and one actual charge");
            var snapshot=frame(f.player).getCompound("order");h.assertTrue(snapshot.getUUID("id").equals(current.intent().id()) && snapshot.getBoolean("canCancel")
                    && snapshot.getString("stage").equals("QUEUED") && snapshot.getLong("fee")==64,"Screen exposes exact acknowledged stage, original fee and server cancellation permission");
            h.assertTrue(KingdomActions.handle(f.player,KingdomActionPayload.of("CONSTRUCTION_CANCEL",current.intent().id()))==null,"Shared action rate limit applies to economic requests too");h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void priceChangedAfterPreviewRejectsBeforeIntentAndDebit(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"gui-stale-price")) {
            var offer=first(frame(f.player));var previous=RoyalEconomyConfig.CONSTRUCTION_FEE.get();
            try {
                RoyalEconomyConfig.CONSTRUCTION_FEE.set(128);send(f.player,order(f,offer,1));
                h.assertTrue(f.f.value()==128 && f.f.village().getPendingProject()==null && store(f.f.level()).load(offer.getUUID("offer"))==null
                        && new RoyalPaymentService(f.f.level()).inspectPlayer(f.player,offer.getUUID("offer"))==null,"Expired price is rejected before reserving intent, accepting work or charging funds");
            } finally {RoyalEconomyConfig.CONSTRUCTION_FEE.set(previous);}
            h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void expiredNewOfferRejectsButSavedOrderReplaysAfterExpiryAndPriceChange(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"gui-offer-expiry")) {
            var offer=first(frame(f.player));var clock=(ServerLevelData)f.f.level().getLevelData();var now=clock.getGameTime();
            try {clock.setGameTime(now+1201);send(f.player,order(f,offer,0));h.assertTrue(store(f.f.level()).load(offer.getUUID("offer"))==null && f.f.value()==128,"Unknown expired offer cannot become a paid order");}
            finally {clock.setGameTime(now);}
            var current=first(frame(f.player));var request=order(f,current,0);send(f.player,request);var pending=f.f.village().getPendingProject();var previous=RoyalEconomyConfig.CONSTRUCTION_FEE.get();
            try {
                RoyalConstructionView.forget(f.player.getUUID());RoyalEconomyConfig.CONSTRUCTION_FEE.set(256);clock.setGameTime(now+2000);send(f.player,request);
                h.assertTrue(f.f.value()==64 && f.f.village().getPendingProject()==pending && store(f.f.level()).load(current.getUUID("offer")).intent().fee()==64,
                        "A durable existing order ignores offer expiry/new prices and never loses its original quote or charges again");
            } finally {RoyalEconomyConfig.CONSTRUCTION_FEE.set(previous);clock.setGameTime(now);}
            h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void guestWrongVillageAndUnknownTokenCannotSpendOrPublish(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"gui-forged");var other=fixture(h,"gui-foreign")) {
            var offer=first(frame(f.player));var guest=new Stage5Support.TestPlayer(f.f.level(),"Guest builder");
            send(guest,order(f,offer,0));send(f.player,new KingdomActionPayload("CONSTRUCTION_ORDER",Optional.of(other.f.village().getId().uuid()),Optional.of(offer.getUUID("offer")),0,offer.getString("plan")));
            send(f.player,new KingdomActionPayload("CONSTRUCTION_ORDER",Optional.of(f.f.village().getId().uuid()),Optional.of(UUID.randomUUID()),0,offer.getString("plan")));
            h.assertTrue(f.f.value()==128 && other.f.value()==128 && f.f.village().getPendingProject()==null && other.f.village().getPendingProject()==null
                    && store(f.f.level()).load(offer.getUUID("offer"))==null,"Actor, village and server offer are checked before new money or work");
            h.assertTrue(!frame(guest).contains("plans") && !frame(guest).contains("spendable"),"Guest preview contains no ruler-only treasury or offers");h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void staleRulershipAndNewRulerCannotReuseAnotherActorsOrder(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"gui-abdication")) {
            var offer=first(frame(f.player));send(f.player,order(f,offer,0));var pending=f.f.village().getPendingProject();var data=KingdomSavedData.get(f.f.level());
            data.abdicate(f.f.kingdom(),f.player.getUUID());send(f.player,KingdomActionPayload.of("CONSTRUCTION_RESUME",offer.getUUID("offer")));
            var next=new Stage5Support.TestPlayer(f.f.level(),"New builder ruler");f.f.village().addReputation(next.getUUID(),32768-f.f.village().getCombinedReputation(f.f.level(),next.getUUID()));data.claimRule(f.f.kingdom(),next.getUUID(),"New builder ruler",42);
            send(next,KingdomActionPayload.of("CONSTRUCTION_RESUME",offer.getUUID("offer")));
            h.assertTrue(store(f.f.level()).load(offer.getUUID("offer")).stage()==Stage.CANCELLING && f.f.value()==64 && f.f.village().getPendingProject()==pending,
                    "Old and new screen actors cannot revive or rebind an authority-retired order; native work and earned fee stay intact");h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void existingNaturalProjectAcceptsOneUnpaidWaitingOrderWithoutReplacingNativeWork(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"gui-natural")) {
            var c=RoyalConstructionCatalog.forPlayer(f.player,f.f.village().getId().uuid()).options().getFirst();var pending=new Village.PendingProject(c.planSet(),c.variant(),0,false,null);f.f.village().setPendingProject(pending);
            var frame=frame(f.player);var offer=first(frame);h.assertTrue(offer.getBoolean("canOrder") && frame.getBoolean("naturalPending"),"Screen offers a waiting mandate behind actual native work");
            var before=VillageSavedData.get(f.f.level()).save(new CompoundTag(),f.f.level().registryAccess()).copy();
            send(f.player,order(f,offer,0));send(f.player,order(f,offer,0));var entry=store(f.f.level()).load(offer.getUUID("offer"));
            h.assertTrue(entry.stage()==Stage.WAITING && entry.blocked()==Blocked.NATURAL_PROJECT && receipt(f,entry)==null
                    && f.f.village().getPendingProject()==pending && f.f.value()==128 && before.equals(VillageSavedData.get(f.f.level()).save(new CompoundTag(),f.f.level().registryAccess())),"Waiting/replay retains one unpaid mandate and byte-equal native data, no project replacement or coins removed");
            var another=first(frame(f.player));h.assertTrue(!another.getBoolean("canOrder") && another.getString("reason").equals("ORDER_PENDING"),"One active royal slot includes the waiting mandate");
            send(f.player,order(f,another,0));h.assertTrue(store(f.f.level()).entries().stream().filter(e->e.intent().village().equals(f.f.village().getId().uuid()) && !e.terminal()).count()==1 && f.f.value()==128,"A forged second offer cannot allocate a second waiting order or debit money");h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void insufficientFundsKeepSavedRequestWhichPayloadCanResumeAfterDeposit(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"gui-poor")) {
            f.f.chest().setItem(0,ItemStack.EMPTY);var offer=first(frame(f.player));h.assertTrue(!offer.getBoolean("canOrder") && offer.getString("reason").equals("INSUFFICIENT_FUNDS"),"Actual spendable funds determine disabled purchase");
            send(f.player,order(f,offer,0));var entry=store(f.f.level()).load(offer.getUUID("offer"));
            h.assertTrue(entry.stage()==Stage.REQUESTED && f.f.value()==0 && f.f.village().getPendingProject()==null,"Failed debit keeps unpaid intent, never a free accepted order");
            f.f.chest().setItem(0,new ItemStack(ModItems.DENIER_ARGENT.get(),2));send(f.player,KingdomActionPayload.of("CONSTRUCTION_RESUME",entry.intent().id()));
            h.assertTrue(store(f.f.level()).load(entry.intent().id()).stage()==Stage.QUEUED && f.f.value()==64 && f.f.village().getPendingProject()!=null,"Resume acts on the same saved unpaid intent after real deposit");h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void cancellationPayloadRetainsAcceptedPatronageAndUnpaidCancellationMintsNothing(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"gui-cancel-paid");var unpaid=fixture(h,"gui-cancel-unpaid")) {
            var offer=first(frame(f.player));send(f.player,order(f,offer,0));var pending=f.f.village().getPendingProject();send(f.player,KingdomActionPayload.of("CONSTRUCTION_CANCEL",offer.getUUID("offer")));send(f.player,KingdomActionPayload.of("CONSTRUCTION_CANCEL",offer.getUUID("offer")));
            h.assertTrue(store(f.f.level()).load(offer.getUUID("offer")).stage()==Stage.CANCELLED && f.f.value()==64 && f.f.village().getPendingProject()==pending,"Accepted cancel/replay only stops royal tracking and never refunds or erases native work");
            unpaid.f.chest().setItem(0,ItemStack.EMPTY);var quote=first(frame(unpaid.player));send(unpaid.player,order(unpaid,quote,0));send(unpaid.player,KingdomActionPayload.of("CONSTRUCTION_CANCEL",quote.getUUID("offer")));
            h.assertTrue(store(unpaid.f.level()).load(quote.getUUID("offer")).stage()==Stage.CANCELLED && unpaid.f.value()==0 && unpaid.f.village().getPendingProject()==null,"Unpaid cancellation cannot invent a refund");
            var fresh=first(frame(unpaid.player));h.assertTrue(!fresh.getUUID("offer").equals(quote.getUUID("offer")),"New order gets a fresh server offer while the old cancelled UUID stays a tombstone");
            unpaid.f.chest().setItem(0,new ItemStack(ModItems.DENIER_ARGENT.get(),2));send(unpaid.player,order(unpaid,fresh,0));
            h.assertTrue(store(unpaid.f.level()).load(fresh.getUUID("offer")).stage()==Stage.QUEUED && unpaid.f.value()==64,"A new valid purchase works immediately after unpaid cancellation without reviving its old identity");h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void changedPlanCannotReuseAlreadyBoundOfferOrderIdentity(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"gui-collision")) {
            var offers=frame(f.player).getList("plans",Tag.TAG_COMPOUND);h.assertTrue(offers.size()>1,"Actual culture supplies distinct legal choices");var first=offers.getCompound(0);var second=offers.getCompound(1);
            send(f.player,order(f,first,0));var pending=f.f.village().getPendingProject();send(f.player,new KingdomActionPayload("CONSTRUCTION_ORDER",Optional.of(f.f.village().getId().uuid()),Optional.of(first.getUUID("offer")),0,second.getString("plan")));
            h.assertTrue(store(f.f.level()).load(first.getUUID("offer")).intent().planSet().toString().equals(first.getString("plan")) && f.f.village().getPendingProject()==pending && f.f.value()==64,"Colliding payload cannot change the bound village/plan/price or spend again");h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void unavailableTreasuryPreviewDoesNotForceRemoteChunks(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"gui-unloaded")) {
            var townhall=f.f.village().getTownhall();var field=BuildingInstance.class.getDeclaredField("inventory");field.setAccessible(true);var original=townhall.getInventory();var positions=new ArrayList<>(original.getChestPositions());var remote=new BlockPos(8_000_000,64,8_000_000);positions.add(remote);
            try {
                field.set(townhall,new BuildingInventory(positions));var frame=frame(f.player);var offer=first(frame);
                h.assertTrue(!frame.getBoolean("treasuryAvailable") && !offer.getBoolean("canOrder") && offer.getString("reason").equals("TREASURY_UNAVAILABLE")
                        && !f.f.level().isLoaded(remote),"Whole screen snapshot postpones unavailable storage and does not force the distant chest chunk");
            } finally {field.set(townhall,original);}
            h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void unknownFutureOrderIndexShowsUnknownStateAndRemainsByteIdentical(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"gui-future-orders")) {
            var offer=first(frame(f.player));var file=root(f.f.level()).resolve("data/millenairekingdoms/royal-construction/orders.dat");
            java.nio.file.Files.createDirectories(file.getParent());byte[] original=java.nio.file.Files.exists(file) ? java.nio.file.Files.readAllBytes(file) : null;
            var future=new CompoundTag();future.putInt("format",999);future.put("orders",new net.minecraft.nbt.ListTag());
            try {
                NbtIo.writeCompressed(future,file);var bytes=java.nio.file.Files.readAllBytes(file);var view=frame(f.player);
                h.assertTrue(!view.getBoolean("stateKnown") && !view.contains("plans") && view.getString("reason").equals("SERVICE_UNAVAILABLE"),
                        "Unreadable future index is unknown, never falsely presented as no order or an enabled offer");
                send(f.player,order(f,offer,0));
                h.assertTrue(java.util.Arrays.equals(bytes,java.nio.file.Files.readAllBytes(file)) && f.f.value()==128 && f.f.village().getPendingProject()==null,
                        "Player retry cannot overwrite future data or charge funds while the order index is unavailable");
            } finally {if(original==null)java.nio.file.Files.delete(file);else java.nio.file.Files.write(file,original);}
            h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void boundedActionCodecAndAllConstructionLabelsExistInEnglishAndTurkish(GameTestHelper h) throws Exception {
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),h.getLevel().registryAccess());
        try {
            var payload=new KingdomActionPayload("CONSTRUCTION_ORDER",Optional.of(UUID.randomUUID()),Optional.of(UUID.randomUUID()),Long.MIN_VALUE,"millenaire:"+"a".repeat(100));
            KingdomActionPayload.CODEC.encode(buffer,payload);h.assertTrue(KingdomActionPayload.CODEC.decode(buffer).equals(payload) && KingdomNetwork.VERSION.equals("5"),"Actual bounded wire codec preserves server offer IDs and longer real registry identifiers");
            boolean rejected=false;try{KingdomActionPayload.CODEC.encode(buffer,new KingdomActionPayload("CONSTRUCTION_ORDER",Optional.empty(),Optional.empty(),0,"a".repeat(129)));}catch(RuntimeException expected){rejected=true;}
            h.assertTrue(rejected,"Oversized wire identifiers are rejected");
        } finally {buffer.release();}
        for(String language:List.of("en_us","tr_tr")) {
            try(var input=RoyalConstructionGuiGameTests.class.getClassLoader().getResourceAsStream("assets/millenairekingdoms/lang/"+language+".json")) {
                h.assertTrue(input!=null,"Production translation resource exists");var json=com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                for(var stage:Stage.values())h.assertTrue(json.has("millenairekingdoms.gui.construction.stage."+stage.name()),"Every order state is translated: "+language+" "+stage);
                for(var blocked:RoyalConstructionStore.Blocked.values())h.assertTrue(json.has("millenairekingdoms.gui.construction.blocked."+blocked.name()),"Every blocked reason is translated: "+language+" "+blocked);
                h.assertTrue(!json.get("millenairekingdoms.gui.msg.error").getAsString().contains("nothing was changed") && !json.get("millenairekingdoms.gui.msg.error").getAsString().contains("hiçbir şey değişmedi"),"Partial durable writes are not falsely reported as unchanged");
            }
        }
        h.succeed();
    }

    @GameTest(templateNamespace=NS,template="empty",batch="royal_gui_restart",timeoutTicks=1200)
    public static void realPaidPlayerActionReplaysWithEmptyOfferCacheInSeparateJvm(GameTestHelper h) throws Exception {
        var level=h.getLevel();var marker=root(level).resolve("royal-gui-restart.dat");
        if("read".equals(System.getProperty("kingdoms.tests.persistence"))) {
            var tag=NbtIo.readCompressed(marker,NbtAccounter.create(1024*1024));var village=VillageSavedData.get(level).getVillageManager().getVillage(new VillageId(tag.getUUID("village")));
            h.assertTrue(village!=null && village.getPendingProject()!=null,"Actual original paid player pending reloads without metadata repair");for(var pos:village.getTownhall().getInventory().getChestPositions())level.getChunkAt(pos);
            var player=new FakePlayer(level,new GameProfile(tag.getUUID("player"),"Reopened builder ruler"));var id=tag.getUUID("order");var old=store(level).load(id);
            send(player,new KingdomActionPayload("CONSTRUCTION_ORDER",Optional.of(tag.getUUID("village")),Optional.of(id),1,old.intent().planSet().toString()));
            h.assertTrue(store(level).load(id).stage()==Stage.QUEUED && RoyalTreasurySnapshot.forPlayer(player).spendableValue()==64 && frame(player).getCompound("order").getUUID("id").equals(id),"Empty new-JVM offer cache does not invalidate durable identity, reprice or recharge saved work");
            System.out.println("ROYAL_GUI_RESTART_READ_PASS order="+id+" remaining=64");h.succeed();return;
        }
        var f=fixture(h,"gui-restart");var offer=first(frame(f.player));send(f.player,order(f,offer,0));var tag=new CompoundTag();tag.putUUID("village",f.f.village().getId().uuid());tag.putUUID("player",f.player.getUUID());tag.putUUID("order",offer.getUUID("offer"));NbtIo.writeCompressed(tag,marker);
        f.f.village().setActive(false);VillageSavedData.get(level).setDirty();System.out.println("ROYAL_GUI_RESTART_WRITE_PASS order="+offer.getUUID("offer")+" remaining="+f.f.value());h.succeed();
    }


    @GameTest(templateNamespace=NS,template="empty",batch="royal_wait_cancel",timeoutTicks=1200)
    public static void waitingCancelIsFreeAndReplaysCannotEraseNativeWorkOrReviveTheOrder(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"waiting-cancel")) {
            var choice=RoyalConstructionCatalog.forPlayer(f.player,f.f.village().getId().uuid()).options().getFirst();var nativeWork=new Village.PendingProject(choice.planSet(),choice.variant(),0,false,null);f.f.village().setPendingProject(nativeWork);
            var offer=first(frame(f.player));send(f.player,order(f,offer,0));var id=offer.getUUID("offer");
            send(f.player,KingdomActionPayload.of("CONSTRUCTION_CANCEL",id));send(f.player,KingdomActionPayload.of("CONSTRUCTION_CANCEL",id));send(f.player,order(f,offer,0));
            var entry=store(f.f.level()).load(id);h.assertTrue(entry.stage()==Stage.CANCELLED && entry.cancellationFrom()==Stage.WAITING && receipt(f,entry)==null && f.f.value()==128
                    && f.f.village().getPendingProject()==nativeWork,"Unpaid waiting cancellation/replays neither debit/refund coins nor clear original native work, and the UUID stays cancelled");h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",batch="royal_wait_active",timeoutTicks=1200)
    public static void activeNativeTaskMustFinishBeforeTheSchedulerStartsTheRoyalOrder(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"waiting-active")) {
            var choice=RoyalConstructionCatalog.forPlayer(f.player,f.f.village().getId().uuid()).options().getFirst();
            var nativeWork=new Village.PendingProject(choice.planSet(),choice.variant(),0,false,null);f.f.village().setPendingProject(nativeWork);
            var offers=frame(f.player).getList("plans",Tag.TAG_COMPOUND);CompoundTag offer=null;for(var raw:offers)if(((CompoundTag)raw).getString("plan").equals(choice.planSet().toString()))offer=(CompoundTag)raw;
            h.assertTrue(offer!=null,"A repeatable legal native candidate is exposed");send(f.player,order(f,offer,0));var id=offer.getUUID("offer");
            // Metadata-only boundary fixture; genuine resident/material completion is tested separately.
            var nativeBuilding=f.f.village().getTownhall();nativeBuilding.setStatus(BuildingInstance.Status.UPGRADING);
            f.f.village().setPendingProject(null);
            runWaitingScheduler(f.player,id);h.assertTrue(store(f.f.level()).load(id).stage()==Stage.WAITING && f.f.value()==128 && nativeBuilding.isBeingBuilt(),"Empty pending slot does not preempt an existing ordinary construction task");
            nativeBuilding.setStatus(BuildingInstance.Status.COMPLETE);runWaitingScheduler(f.player,id);var accepted=store(f.f.level()).load(id);
            h.assertTrue(accepted.stage()==Stage.QUEUED && accepted.building()==null && accepted.intent().originalBuildings().contains(nativeBuilding.getId().uuid()) && f.f.value()==64
                    && nativeBuilding.getStatus()==BuildingInstance.Status.COMPLETE && f.f.village().getPendingProject()!=null && receipt(f,accepted).state()==RoyalPaymentJournal.State.COMMITTED,"Actual bounded scheduler pays once only after the native upgrade finishes and publishes a new royal request: "+accepted+" funds="+f.f.value()+" baseline="+accepted.intent().originalBuildings()+" pending="+f.f.village().getPendingProject()+"");
            new RoyalConstructionService(f.f.level()).observe(id);h.assertTrue(store(f.f.level()).load(id).stage()==Stage.QUEUED && store(f.f.level()).load(id).building()==null,"An existing native town hall never fulfills a new royal building order");h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",batch="royal_wait_funds",timeoutTicks=1200)
    public static void waitingNeedsLoadedVillageAndRealFundsAndRetainsTheOriginalQuote(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"waiting-funds")) {
            var choice=RoyalConstructionCatalog.forPlayer(f.player,f.f.village().getId().uuid()).options().getFirst();f.f.village().setPendingProject(new Village.PendingProject(choice.planSet(),choice.variant(),0,false,null));f.f.chest().setItem(0,ItemStack.EMPTY);
            var offer=first(frame(f.player));h.assertTrue(offer.getBoolean("canOrder"),"Future unpaid waiting mandate does not require money before the current native work finishes");send(f.player,order(f,offer,0));var id=offer.getUUID("offer");var service=new RoyalConstructionService(f.f.level());
            f.f.village().setPendingProject(null);f.f.village().setActive(false);h.assertTrue(service.retryPlayer(f.player,id).blocked()==Blocked.UNLOADED && f.f.value()==0,"Inactive village defers without a debit");
            f.f.village().setActive(true);h.assertTrue(service.retryPlayer(f.player,id).blocked()==Blocked.INSUFFICIENT_FUNDS && store(f.f.level()).load(id).stage()==Stage.WAITING && receipt(f,store(f.f.level()).load(id))==null,"No real coins leaves a visible unpaid waiting mandate");
            var old=RoyalEconomyConfig.CONSTRUCTION_FEE.get();try {
                RoyalEconomyConfig.CONSTRUCTION_FEE.set(128);f.f.chest().setItem(0,new ItemStack(ModItems.DENIER_ARGENT.get(),1));
                var accepted=service.retryPlayer(f.player,id);service.retryPlayer(f.player,id);
                h.assertTrue(accepted.stage()==Stage.QUEUED && accepted.intent().fee()==64 && f.f.value()==0 && receipt(f,accepted).amount()==64 && receipt(f,accepted).state()==RoyalPaymentJournal.State.COMMITTED,"Later funds pay exactly the reviewed64 quote once, even after config price rises to128");
            } finally {RoyalEconomyConfig.CONSTRUCTION_FEE.set(old);}h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",batch="royal_wait_retire",timeoutTicks=1200)
    public static void waitingAuthorityRetiresBeforeReclaimWithoutFeesOrNativeChanges(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"waiting-retire")) {
            var choice=RoyalConstructionCatalog.forPlayer(f.player,f.f.village().getId().uuid()).options().getFirst();var nativeWork=new Village.PendingProject(choice.planSet(),choice.variant(),0,false,null);f.f.village().setPendingProject(nativeWork);
            var offer=first(frame(f.player));send(f.player,order(f,offer,0));var id=offer.getUUID("offer");var data=KingdomSavedData.get(f.f.level());data.clearRuler(f.f.kingdom());
            var retired=store(f.f.level()).load(id);h.assertTrue(retired.stage()==Stage.CANCELLING && retired.cancellationFrom()==Stage.WAITING,"Governance publication retires the unpaid waiting mandate first");
            new RoyalConstructionService(f.f.level()).settleRetired(id);data.claimRule(f.f.kingdom(),f.player.getUUID(),"Reclaimed builder ruler",43);send(f.player,order(f,offer,0));
            h.assertTrue(store(f.f.level()).load(id).stage()==Stage.CANCELLED && f.f.value()==128 && f.f.village().getPendingProject()==nativeWork,"Reclaim cannot revive the old mandate, spend fees or erase original work");h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",batch="royal_wait_invalid",timeoutTicks=1200)
    public static void nativeCompletionThatExhaustsThePlanLimitLeavesRoyalWorkUnpaid(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"waiting-invalid")) {
            var choice=RoyalConstructionCatalog.forPlayer(f.player,f.f.village().getId().uuid()).options().stream().filter(c->ModCultures.getBuildingPlanSet(c.planSet()).maxCount()>0).findFirst().orElseThrow();
            f.f.village().setPendingProject(new Village.PendingProject(choice.planSet(),choice.variant(),0,false,null));CompoundTag offer=null;
            for(var raw:frame(f.player).getList("plans",Tag.TAG_COMPOUND))if(((CompoundTag)raw).getString("plan").equals(choice.planSet().toString()))offer=(CompoundTag)raw;
            send(f.player,order(f,offer,0));var id=offer.getUUID("offer");int limit=ModCultures.getBuildingPlanSet(choice.planSet()).maxCount();
            for(int n=0;n<limit;n++)f.f.village().addBuilding(new BuildingInstance(BuildingId.random(),choice.plan(),f.f.village().getCenter().offset(n+8,0,8),Rotation.NONE,BuildingInstance.Status.COMPLETE,choice.planSet(),choice.variant(),0));
            f.f.village().setPendingProject(null);var result=new RoyalConstructionService(f.f.level()).retryPlayer(f.player,id);
            h.assertTrue(result.stage()==Stage.WAITING && result.blocked()==Blocked.MISSING_PLAN && receipt(f,result)==null && f.f.value()==128 && f.f.village().getPendingProject()==null,"Changed native eligibility is rechecked before money/publication; capped buildings are not duplicated");h.succeed();
        }
    }

    @GameTest(templateNamespace=NS,template="empty",batch="royal_wait_restart",timeoutTicks=1200)
    public static void unpaidWaitingSurvivesSeparateJvmAndAutomaticallyPublishesAfterNativeCompletion(GameTestHelper h) throws Exception {
        var level=h.getLevel();var marker=root(level).resolve("royal-waiting-restart.dat");
        if("read".equals(System.getProperty("kingdoms.tests.persistence"))) {
            var tag=NbtIo.readCompressed(marker,NbtAccounter.create(1024*1024));var village=VillageSavedData.get(level).getVillageManager().getVillage(new VillageId(tag.getUUID("village")));var id=tag.getUUID("order");
            var natural=new Village.PendingProject(ResourceLocation.parse(tag.getString("nativePlan")),tag.getString("nativeVariant"),0,false,null);
            h.assertTrue(village!=null && natural.equals(village.getPendingProject()) && store(level).load(id).stage()==Stage.WAITING,"Original native pending and unpaid royal wait reopen without repairs");
            for(var pos:village.getTownhall().getInventory().getChestPositions())level.getChunkAt(pos);village.setActive(true);var player=new FakePlayer(level,new GameProfile(tag.getUUID("player"),"Waiting ruler"));
            h.assertTrue(RoyalTreasurySnapshot.forPlayer(player).spendableValue()==128 && new RoyalPaymentService(level).inspectPlayer(player,id)==null,"Restart cannot charge an unpaid queued mandate");
            var set=ModCultures.getBuildingPlanSet(natural.planSetId());var def=set.getLevel(natural.variant(),0);var completed=new BuildingInstance(BuildingId.random(),def.planId(),village.getCenter().offset(24,0,24),Rotation.NONE,BuildingInstance.Status.COMPLETE,set.id(),natural.variant(),0);
            village.addBuilding(completed);village.setPendingProject(null);h.assertTrue(new RoyalConstructionService(level).observe(id).blocked()==Blocked.RULER_UNAVAILABLE,"No offline actor is impersonated for a new debit");
            runWaitingScheduler(player,id);var accepted=store(level).load(id);send(player,new KingdomActionPayload("CONSTRUCTION_ORDER",Optional.of(village.getId().uuid()),Optional.of(id),0,accepted.intent().planSet().toString()));
            h.assertTrue(accepted.stage()==Stage.QUEUED && accepted.intent().originalBuildings().contains(completed.getId().uuid()) && RoyalTreasurySnapshot.forPlayer(player).spendableValue()==64
                    && new RoyalPaymentService(level).inspectPlayer(player,id).state()==RoyalPaymentJournal.State.COMMITTED,"Original actor return automatically revalidates, publishes and pays once across JVMs, excluding prior native completion");
            System.out.println("ROYAL_WAITING_RESTART_READ_PASS waiting->QUEUED one64fee remaining64");h.succeed();return;
        }
        var f=fixture(h,"waiting-restart");var choices=RoyalConstructionCatalog.forPlayer(f.player,f.f.village().getId().uuid()).options();h.assertTrue(choices.size()>1,"Two genuine native choices are available");
        var natural=choices.get(1);f.f.village().setPendingProject(new Village.PendingProject(natural.planSet(),natural.variant(),0,false,null));var offer=first(frame(f.player));send(f.player,order(f,offer,0));var id=offer.getUUID("offer");
        h.assertTrue(store(level).load(id).stage()==Stage.WAITING && f.f.value()==128 && receipt(f,store(level).load(id))==null,"Actual player action persists unpaid waiting before the original project finishes");
        var tag=new CompoundTag();tag.putUUID("village",f.f.village().getId().uuid());tag.putUUID("player",f.player.getUUID());tag.putUUID("order",id);tag.putString("nativePlan",natural.planSet().toString());tag.putString("nativeVariant",natural.variant());NbtIo.writeCompressed(tag,marker);
        f.f.village().setActive(false);VillageSavedData.get(level).setDirty();System.out.println("ROYAL_WAITING_RESTART_WRITE_PASS WAITING noFee remaining128 nativePendingPreserved");h.succeed();
    }

    /** Test-only online-player lookup; no NPCs, network login or fabricated financial receipts. */
    private static void runWaitingScheduler(ServerPlayer player,UUID id) throws Exception {
        var field=net.minecraft.server.players.PlayerList.class.getDeclaredField("playersByUUID");field.setAccessible(true);
        @SuppressWarnings("unchecked") var online=(Map<UUID,ServerPlayer>)field.get(player.getServer().getPlayerList());var old=online.put(player.getUUID(),player);
        try {for(int n=0;n<4;n++)RoyalConstructionRuntime.step(player.serverLevel(),16);}
        finally {if(old==null)online.remove(player.getUUID());else online.put(player.getUUID(),old);}
    }

    private record Fixture(RoyalPaymentGameTests.Fixture f,Stage5Support.TestPlayer player) implements AutoCloseable {
        @Override public void close(){RoyalConstructionView.forget(player.getUUID());KingdomScreens.forget(player.getUUID());f.close();}
    }
    private static Fixture fixture(GameTestHelper h,String name) {
        var f=RoyalPaymentGameTests.persistentFixture(h,name);f.village().setActive(true);f.chest().setItem(0,new ItemStack(ModItems.DENIER_ARGENT.get(),2));var player=new Stage5Support.TestPlayer(f.level(),"Builder ruler");
        f.village().addReputation(player.getUUID(),32768-f.village().getCombinedReputation(f.level(),player.getUUID()));KingdomSavedData.get(f.level()).claimRule(f.kingdom(),player.getUUID(),"Builder ruler",42);return new Fixture(f,player);
    }
    private static CompoundTag frame(ServerPlayer p){return KingdomScreens.build(p).getCompound("construction");}
    private static CompoundTag first(CompoundTag frame){return frame.getList("plans",Tag.TAG_COMPOUND).getCompound(0);}
    private static KingdomActionPayload order(Fixture f,CompoundTag offer,long amount){return new KingdomActionPayload("CONSTRUCTION_ORDER",Optional.of(f.f.village().getId().uuid()),Optional.of(offer.getUUID("offer")),amount,offer.getString("plan"));}
    private static Component send(ServerPlayer p,KingdomActionPayload payload){try{var field=KingdomActions.class.getDeclaredField("LAST");field.setAccessible(true);((Map<?,?>)field.get(null)).remove(p.getUUID());}catch(Exception e){throw new IllegalStateException(e);}return KingdomActions.handle(p,payload);}
    private static Path root(ServerLevel l){return l.getServer().getWorldPath(LevelResource.ROOT);}
    private static RoyalConstructionStore store(ServerLevel l){return new RoyalConstructionStore(root(l).resolve("data/millenairekingdoms/royal-construction"));}
    private static RoyalPaymentService.Receipt receipt(Fixture f,RoyalConstructionStore.Entry e){return new RoyalPaymentService(f.f.level()).inspectPlayer(f.player,e.intent().id());}
}
