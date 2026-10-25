package com.millenairekingdoms.test;

import com.millenairekingdoms.economy.RoyalEconomyConfig;
import com.millenairekingdoms.economy.RoyalPaymentJournal.State;
import com.millenairekingdoms.gui.KingdomActions;
import com.millenairekingdoms.gui.KingdomScreens;
import com.millenairekingdoms.gui.RoyalShipmentView;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.network.KingdomActionPayload;
import com.millenairekingdoms.network.KingdomNetwork;
import com.millenairekingdoms.shipping.RoyalShipmentConfig;
import com.millenairekingdoms.shipping.RoyalShipmentService;
import com.millenairekingdoms.shipping.RoyalShipmentStore;
import com.millenairekingdoms.shipping.RoyalShipmentStore.Stage;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.building.BuildingInstance;
import org.millenaire.building.BuildingInventory;
import org.millenaire.item.ModItems;
import org.millenaire.village.VillageSavedData;

/** Real server action/offer security and physical shipment state. No graphical or authenticated-client claim. */
@PrefixGameTestTemplate(false)
public final class RoyalShipmentGuiGameTests {
    private static final String NS="millenairekingdoms_royal_shipment_gui_tests";
    private static final ResourceLocation CHERRY=ResourceLocation.parse("minecraft:cherry_log");
    private record Fixture(RoyalShipmentSupport.Realm realm,ServerPlayer player) implements AutoCloseable {
        CompoundTag frame(){return KingdomScreens.build(player).getCompound("shipments");}
        @Override public void close(){RoyalShipmentView.forget(player.getUUID());KingdomScreens.forget(player.getUUID());realm.close();}
    }
    private static Fixture fixture(GameTestHelper h,String name){
        var realm=RoyalShipmentSupport.realm(h,name);var player=new Stage5Support.TestPlayer(h.getLevel(),name);
        realm.capital().addReputation(player.getUUID(),40000-realm.capital().getCombinedReputation(h.getLevel(),player.getUUID()));
        KingdomSavedData.get(h.getLevel()).claimRule(realm.kingdom(),player.getUUID(),name,h.getLevel().getGameTime());
        var f=new Fixture(realm,player);select(f);return f;
    }
    private static void select(Fixture f){
        send(f.player,KingdomActionPayload.of("SHIPMENT_SOURCE",f.realm.from()));
        send(f.player,KingdomActionPayload.of("SHIPMENT_TARGET",f.realm.to()));
        send(f.player,new KingdomActionPayload("SHIPMENT_PRODUCT",Optional.empty(),Optional.empty(),0,CHERRY.toString()));
        send(f.player,new KingdomActionPayload("SHIPMENT_QUANTITY",Optional.empty(),Optional.empty(),40,""));
    }
    private static void send(ServerPlayer player,KingdomActionPayload request){
        try{var field=KingdomActions.class.getDeclaredField("LAST");field.setAccessible(true);((Map<?,?>)field.get(null)).remove(player.getUUID());}
        catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
        KingdomActions.handle(player,request);
    }
    private static UUID offer(Fixture f){return f.frame().getUUID("offer");}
    private static void order(Fixture f,UUID id){send(f.player,KingdomActionPayload.of("SHIPMENT_ORDER",id));}
    private static void resume(Fixture f,UUID id){send(f.player,KingdomActionPayload.of("SHIPMENT_RESUME",id));}
    private static void cancel(Fixture f,UUID id){send(f.player,KingdomActionPayload.of("SHIPMENT_CANCEL",id));}
    private static CompoundTag current(Fixture f){return f.frame().getList("orders",10).getCompound(0);}

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void previewBindsStableOffersToActualProtectedGoodsCostAndTimeWithoutMutation(GameTestHelper h){
        try(var f=fixture(h,"shipping-preview")){
            var nativeBefore=VillageSavedData.get(h.getLevel()).save(new CompoundTag(),h.getLevel().registryAccess()).copy();
            var frame=f.frame();h.assertTrue(frame.getInt("physical")==96 && frame.getInt("available")==80 && frame.getInt("retained")==16
                    && frame.getLong("fee")==9 && frame.getLong("travelTicks")==3600 && frame.getLong("spendable")==128 && frame.getBoolean("canOrder"),"Preview uses real native stock/reserves, route512, quantity40 and treasury128");
            var reserve=frame.getCompound("reserve");h.assertTrue(reserve.getInt("minimum")==16 && reserve.getInt("target")==0 && reserve.getInt("construction")==0 && reserve.getInt("residents")==0 && reserve.getInt("nativeProtected")==0,"Readonly stock breakdown explains the actual16 minimum separately from native/project/household needs");
            h.assertTrue(frame.getUUID("offer").equals(offer(f)) && f.realm.store().load(frame.getUUID("offer"))==null
                    && nativeBefore.equals(VillageSavedData.get(h.getLevel()).save(new CompoundTag(),h.getLevel().registryAccess()))
                    && f.realm.goods(f.realm.source())==96 && f.realm.goods(f.realm.target())==4 && f.realm.coins()==128,"Repeated preview neither reserves nor moves goods/coins/native work");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void repeatedPayloadIgnoresForgedClientFieldsAndChargesOnlyOnceWithSharedRateLimit(GameTestHelper h){
        try(var f=fixture(h,"shipping-replay")){
            var id=offer(f);send(f.player,new KingdomActionPayload("SHIPMENT_ORDER",Optional.of(id),Optional.of(UUID.randomUUID()),Long.MIN_VALUE,"minecraft:diamond"));
            h.assertTrue(f.realm.store().load(id).intent().quantity()==40 && f.realm.store().load(id).intent().product().equals(CHERRY) && f.realm.coins()==119,"Client cannot change server quantity/product/fee/agency through extra payload fields");
            h.assertTrue(KingdomActions.handle(f.player,KingdomActionPayload.of("SHIPMENT_CANCEL",id))==null,"Shared four-tick payload throttle applies to shipments");
            order(f,id);order(f,id);var e=f.realm.store().load(id);
            h.assertTrue(e.collected()==40 && e.stage()==Stage.IN_TRANSIT && f.realm.goods(f.realm.source())==56 && f.realm.goods(f.realm.target())==4 && e.escrow()==40 && f.realm.coins()==119,"Repeated existing ID advances only its bound physical shipment with one real fee and conservation100");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void changedFeeRejectsBeforeIntentAndDebit(GameTestHelper h){
        try(var f=fixture(h,"shipping-price")){
            var id=offer(f);int old=RoyalEconomyConfig.SHIPMENT_BASE_FEE.get();
            try{RoyalEconomyConfig.SHIPMENT_BASE_FEE.set(old+1);order(f,id);h.assertTrue(f.realm.store().load(id)==null && f.realm.coins()==128 && f.realm.goods(f.realm.source())==96,"A stale server price cannot create an intent or charge anything");}
            finally{RoyalEconomyConfig.SHIPMENT_BASE_FEE.set(old);}h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void changedTravelTermsRejectBeforeIntentAndDebit(GameTestHelper h){
        try(var f=fixture(h,"shipping-duration")){
            var id=offer(f);int old=RoyalShipmentConfig.BASE_TRAVEL.get();
            try{RoyalShipmentConfig.BASE_TRAVEL.set(old+1);order(f,id);h.assertTrue(f.realm.store().load(id)==null && f.realm.coins()==128,"Unaccepted shipment cannot silently change the displayed duration");}
            finally{RoyalShipmentConfig.BASE_TRAVEL.set(old);}h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void changedEndpointOwnershipInvalidatesOfferBeforeIntentAndDebit(GameTestHelper h){
        try(var f=fixture(h,"shipping-endpoint")){
            var id=offer(f);f.realm.target().setOwner(UUID.randomUUID(),"Changed local owner");order(f,id);
            h.assertTrue(f.realm.store().load(id)==null && f.realm.coins()==128 && f.realm.goods(f.realm.source())==96,
                    "Changed physical destination mandate cannot be rebound under an old server offer even when membership, price and duration stay identical");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void expiredOrClockReversedNewOfferCannotSpend(GameTestHelper h){
        try(var f=fixture(h,"shipping-expiry")){
            var id=offer(f);var clock=(ServerLevelData)h.getLevel().getLevelData();long now=clock.getGameTime();
            try{clock.setGameTime(now+1201);order(f,id);h.assertTrue(f.realm.store().load(id)==null && f.realm.coins()==128,"New expired request rejects before intent/debit");
                var later=offer(f);clock.setGameTime(now);order(f,later);h.assertTrue(f.realm.store().load(later)==null && f.realm.coins()==128,"An offer from a future clock is invalidated");}
            finally{clock.setGameTime(now);}h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void acceptedOfferReplaysAfterCacheLossExpiryAndConfigChangesWithFrozenTerms(GameTestHelper h){
        try(var f=fixture(h,"shipping-durable")){
            var id=offer(f);order(f,id);RoyalShipmentView.forget(f.player.getUUID());int fee=RoyalEconomyConfig.SHIPMENT_BASE_FEE.get();int ticks=RoyalShipmentConfig.BASE_TRAVEL.get();
            try{RoyalEconomyConfig.SHIPMENT_BASE_FEE.set(fee+10);RoyalShipmentConfig.BASE_TRAVEL.set(ticks+20);order(f,id);var e=f.realm.store().load(id);
                h.assertTrue(e.intent().fee()==9 && e.intent().travelTicks()==3600 && e.collected()==40 && f.realm.coins()==119 && f.realm.goods(f.realm.source())==56,"Saved full immutable intent survives an empty offer cache and changed prices/duration without another payment");}
            finally{RoyalEconomyConfig.SHIPMENT_BASE_FEE.set(fee);RoyalShipmentConfig.BASE_TRAVEL.set(ticks);}h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void guestsForeignVillagesUnknownOffersAndInvalidSelectionsGrantNoSpending(GameTestHelper h){
        try(var f=fixture(h,"shipping-forgery");var foreign=fixture(h,"shipping-other")){
            var id=offer(f);var guest=new Stage5Support.TestPlayer(h.getLevel(),"Shipping guest");send(guest,KingdomActionPayload.of("SHIPMENT_ORDER",id));
            var guestView=RoyalShipmentView.build(guest,f.realm.kingdom());h.assertTrue(!guestView.contains("spendable") && !guestView.contains("offer"),"Guests see no ruler-only offers/funds");
            send(f.player,KingdomActionPayload.of("SHIPMENT_SOURCE",foreign.realm.from()));order(f,UUID.randomUUID());
            for(long amount:new long[]{0,-1,Long.MIN_VALUE,Long.MAX_VALUE,65537})send(f.player,new KingdomActionPayload("SHIPMENT_QUANTITY",Optional.empty(),Optional.empty(),amount,""));
            send(f.player,new KingdomActionPayload("SHIPMENT_PRODUCT",Optional.empty(),Optional.empty(),0,"millenaire:denier"));
            h.assertTrue(f.frame().getUUID("source").equals(f.realm.from()) && f.frame().getInt("quantity")==40 && f.frame().getString("product").equals(CHERRY.toString())
                    && f.realm.store().load(id)==null && f.realm.coins()==128 && foreign.realm.coins()==128,"Forged source/quantity/product and unknown offer cannot modify the valid selection or charge either kingdom");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void changingSourceOrQuantityInvalidatesOldOfferAndKeepsDistinctMemberEndpoints(GameTestHelper h){
        try(var f=fixture(h,"shipping-select")){
            var id=offer(f);send(f.player,new KingdomActionPayload("SHIPMENT_QUANTITY",Optional.empty(),Optional.empty(),20,""));order(f,id);
            h.assertTrue(f.realm.store().load(id)==null && f.frame().getInt("quantity")==20,"Changed quantity cannot reuse a stale offer");
            send(f.player,KingdomActionPayload.of("SHIPMENT_SOURCE",f.realm.to()));var frame=f.frame();
            h.assertTrue(frame.getUUID("source").equals(f.realm.to()) && !frame.getUUID("source").equals(frame.getUUID("target")) && f.realm.coins()==128,"Choosing the previous target as source selects another member destination without payment");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void currentSurplusIsRecheckedAndInsufficientStockStillHasAnHonestPricePreview(GameTestHelper h){
        try(var f=fixture(h,"shipping-stock")){
            var id=offer(f);f.realm.chest(f.realm.source()).setItem(0,ItemStack.EMPTY);order(f,id);var frame=f.frame();
            h.assertTrue(f.realm.store().load(id)==null && frame.getInt("physical")==32 && frame.getInt("available")==16 && frame.getLong("fee")==9
                    && !frame.getBoolean("canOrder") && frame.getString("reason").equals("INSUFFICIENT_STOCK") && f.realm.coins()==128,"Native activity invalidates surplus before reservation/payment while preview still reports price/time and actual quantities");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void unpaidIntentCanResumeAfterDepositAndPreDispatchCancellationRefundsExactly(GameTestHelper h){
        try(var f=fixture(h,"shipping-poor")){
            f.realm.chest(f.realm.capital()).clearContent();var frame=f.frame();var id=frame.getUUID("offer");h.assertTrue(!frame.getBoolean("canOrder") && frame.getString("reason").equals("INSUFFICIENT_FUNDS"),"Actual funds disable shipment button");
            order(f,id);h.assertTrue(f.realm.store().load(id).stage()==Stage.PREPARING && f.realm.coins()==0 && f.realm.goods(f.realm.source())==96,"Forged enabled button retains only unpaid intent, without free goods or fee");
            f.realm.chest(f.realm.capital()).setItem(0,new ItemStack(ModItems.DENIER_ARGENT.get(),2));resume(f,id);resume(f,id);cancel(f,id);cancel(f,id);
            var entry=f.realm.store().load(id);h.assertTrue(entry.stage()==Stage.CANCELLED && entry.returned()==40 && entry.escrow()==0 && f.realm.coins()==128 && f.realm.goods(f.realm.source())==96 && f.realm.goods(f.realm.target())==4,"Real paid and collected goods return before exact refund, repeated cancellation never mints goods/coins");
            h.assertTrue(!offer(f).equals(id),"Used terminal ID rotates to a new bounded offer; original remains a tombstone");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void authorityRetirementCannotBeRevivedByOldOrNewRulerPayload(GameTestHelper h){
        try(var f=fixture(h,"shipping-tenure")){
            var id=offer(f);order(f,id);var data=KingdomSavedData.get(h.getLevel());data.abdicate(f.realm.kingdom(),f.player.getUUID());resume(f,id);
            var next=new Stage5Support.TestPlayer(h.getLevel(),"Next shipper");f.realm.capital().addReputation(next.getUUID(),40000-f.realm.capital().getCombinedReputation(h.getLevel(),next.getUUID()));data.claimRule(f.realm.kingdom(),next.getUUID(),"Next shipper",42);
            send(next,KingdomActionPayload.of("SHIPMENT_SOURCE",f.realm.from()));send(next,KingdomActionPayload.of("SHIPMENT_RESUME",id));var view=RoyalShipmentView.build(next,f.realm.kingdom());var e=f.realm.store().load(id);
            h.assertTrue(e.cancellationRequested() && f.realm.coins()==119 && f.realm.goods(f.realm.source())==96 && !view.getList("orders",10).getCompound(0).getBoolean("canResume"),"Neither actor can revive another tenure or turn its fee into new spending; automatic original cancellation remains pending");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void unloadedDestinationCanBeQuotedButMissingSourceStorageNeverForcesChunks(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"shipping-loading")){
            f.realm.target().setActive(false);h.assertTrue(f.frame().getBoolean("canOrder"),"Destination inactivity does not force chunks or forbid issuing a valid deferred route");
            var hall=f.realm.source().getTownhall();var original=hall.getInventory();var field=BuildingInstance.class.getDeclaredField("inventory");field.setAccessible(true);var positions=new java.util.ArrayList<>(original.getChestPositions());var remote=new BlockPos(8_000_000,64,8_000_000);positions.add(remote);
            try{field.set(hall,new BuildingInventory(positions));var view=f.frame();h.assertTrue(!view.getBoolean("canOrder") && !view.hasUUID("offer") && view.getString("reason").equals("SOURCE_UNAVAILABLE") && !h.getLevel().isLoaded(remote) && f.realm.coins()==128,"Unavailable source preview grants no stale offer and never loads a remote storage chunk");}
            finally{field.set(hall,original);}h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void fullDestinationStatusAndRetryPreserveActualCargoAndCommittedFee(GameTestHelper h){
        try(var f=fixture(h,"shipping-full")){
            var id=offer(f);order(f,id);resume(f,id);resume(f,id);resume(f,id);var entry=f.realm.store().load(id);
            var clock=(ServerLevelData)h.getLevel().getLevelData();long now=clock.getGameTime();
            try{clock.setGameTime(entry.due());for(var pos:f.realm.target().getTownhall().getInventory().getChestPositions()){var chest=(net.minecraft.world.level.block.entity.ChestBlockEntity)h.getLevel().getBlockEntity(pos);for(int slot=0;slot<27;slot++)chest.setItem(slot,new ItemStack(Items.COBBLESTONE,64));}
                resume(f,id);resume(f,id);var status=current(f);h.assertTrue(status.getString("stage").equals("AWAITING_DELIVERY") && status.getString("blocked").equals("FULL") && status.getInt("held")==40 && status.getInt("delivered")==0 && !status.getBoolean("refundable") && f.realm.coins()==119,"Full receiving storage reports real held liability and the earned nonrefundable transport fee");
                f.realm.chest(f.realm.target()).setItem(0,ItemStack.EMPTY);resume(f,id);resume(f,id);var finalStatus=current(f);
                h.assertTrue(finalStatus.getString("stage").equals("DELIVERED") && finalStatus.getInt("held")==0 && finalStatus.getInt("delivered")==40 && !finalStatus.getBoolean("canResume") && f.realm.goods(f.realm.source())==56 && f.realm.goods(f.realm.target())==40 && f.realm.coins()==119,"Payload retry completes exact held cargo with no new fee after actual capacity opens; fixture intentionally replaced initial target4");
            }finally{clock.setGameTime(now);}h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void futureShipmentDataIsUnknownAndOldOfferCannotOverwriteItOrCharge(GameTestHelper h) throws Exception {
        try(var f=fixture(h,"shipping-future")){
            var id=offer(f);var file=RoyalShipmentService.directory(h.getLevel()).resolve("shipments.dat");byte[] original=Files.exists(file) ? Files.readAllBytes(file) : null;var tag=new CompoundTag();tag.putInt("format",999);tag.put("shipments",new ListTag());
            try{NbtIo.writeCompressed(tag,file);var bytes=Files.readAllBytes(file);var view=f.frame();order(f,id);
                h.assertTrue(!view.getBoolean("stateKnown") && !view.hasUUID("offer") && !view.getBoolean("canOrder") && view.getString("reason").equals("SERVICE_UNAVAILABLE") && Arrays.equals(bytes,Files.readAllBytes(file)) && f.realm.coins()==128,"Unknown root remains byte-identical and blocks stale offer spending; never report no shipments as known");}
            finally{if(original==null)Files.delete(file);else Files.write(file,original);}h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void boundedShipmentPayloadAndEveryStateHaveEnglishAndTurkishLabels(GameTestHelper h) throws Exception {
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),h.getLevel().registryAccess());
        try{var payload=new KingdomActionPayload("SHIPMENT_QUANTITY",Optional.of(UUID.randomUUID()),Optional.empty(),Long.MAX_VALUE,CHERRY.toString());KingdomActionPayload.CODEC.encode(buffer,payload);
            h.assertTrue(KingdomActionPayload.CODEC.decode(buffer).equals(payload) && KingdomNetwork.VERSION.equals("5"),"Shared version5 bounded wire codec preserves shipment IDs/quantity with server-side validation");}
        finally{buffer.release();}
        for(String language:List.of("en_us","tr_tr"))try(var input=RoyalShipmentGuiGameTests.class.getClassLoader().getResourceAsStream("assets/millenairekingdoms/lang/"+language+".json")){
            var json=com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            for(var stage:Stage.values())h.assertTrue(json.has("millenairekingdoms.gui.shipment.stage."+stage.name()),"Every stage translated: "+language+" "+stage);
            for(var blocked:RoyalShipmentStore.Blocked.values())h.assertTrue(json.has("millenairekingdoms.gui.shipment.blocked."+blocked.name()),"Every blocked reason translated: "+language+" "+blocked);
            h.assertTrue(json.has("millenairekingdoms.gui.shipment.stage.CANCELLING") && json.has("millenairekingdoms.gui.shipment.earned_fee_note") && json.has("millenairekingdoms.gui.msg.shipment_offer_expired"),"Cancellation and offer policies are explicit in both languages");
            for(var key:List.of("stock_summary","stock_details","insufficient_stock_detail"))h.assertTrue(json.has("millenairekingdoms.gui.shipment."+key),"Actual reservation figures are explained in both languages: "+key);
        }h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",batch="shipping_gui_restart",timeoutTicks=1200)
    public static void actualCollectedPlayerShipmentReplaysInSeparateJvmWithEmptyOfferCache(GameTestHelper h) throws Exception {
        var level=h.getLevel();var marker=level.getServer().getWorldPath(LevelResource.ROOT).resolve("royal-shipment-gui-restart.dat");
        if(RoyalShipmentSupport.read()){
            var tag=NbtIo.readCompressed(marker,NbtAccounter.create(1024*1024));try(var realm=RoyalShipmentSupport.restore(h,tag,true)){
                var player=new FakePlayer(level,new GameProfile(tag.getUUID("player"),"Reopened shipper"));var id=tag.getUUID("order");var before=realm.store().load(id);
                h.assertTrue(before.stage()==Stage.COLLECTING && before.collected()==40 && realm.goods(realm.source())==56 && realm.goods(realm.target())==4 && realm.coins()==119,"Actual native cargo and original player-bound PAID receipt reopen without repair");
                send(player,KingdomActionPayload.of("SHIPMENT_ORDER",id));var after=realm.store().load(id);
                send(player,KingdomActionPayload.of("SHIPMENT_SOURCE",realm.from()));var frame=RoyalShipmentView.build(player,realm.kingdom());
                h.assertTrue(after.stage()==Stage.IN_TRANSIT && after.intent().fee()==9 && after.intent().travelTicks()==3600 && after.intent().actor().equals(player.getUUID()) && after.escrow()==40 && realm.coins()==119 && frame.getList("orders",10).getCompound(0).getUUID("id").equals(id),"New JVM uses durable full intent rather than expired/missing offer; exact cargo40 and fee9 remain conserved");
                System.out.println("ROYAL_SHIPMENT_GUI_RESTART_READ_PASS source=56 target=4 held=40 capital=119");h.succeed();return;
            }
        }
        var f=fixture(h,"shipping-restart");var id=offer(f);order(f,id);resume(f,id);var tag=f.realm.marker();tag.putUUID("player",f.player.getUUID());tag.putUUID("order",id);NbtIo.writeCompressed(tag,marker);f.realm.save();
        f.realm.capital().setActive(false);f.realm.source().setActive(false);f.realm.target().setActive(false);VillageSavedData.get(level).setDirty();
        h.assertTrue(f.realm.store().load(id).collected()==40 && f.realm.payments().load(id).state()==State.PAID,"Write fixture saved actual collected40 and real fee9");
        System.out.println("ROYAL_SHIPMENT_GUI_RESTART_WRITE_PASS source=56 target=4 held=40 capital=119");h.succeed();
    }
    @GameTest(templateNamespace=NS,template="empty",batch="shipping_norman_stock",timeoutTicks=1200)
    public static void normanReligious1047CobblestoneAllows23DespiteDefault40AndSurvivesCollectedRestart(GameTestHelper h)throws Exception{
        var level=h.getLevel();var manager=VillageSavedData.get(level).getVillageManager();var marker=level.getServer().getWorldPath(LevelResource.ROOT).resolve("royal-norman-cobble.dat");
        RoyalShipmentSupport.Realm realm;ServerPlayer player;org.millenaire.village.Village source;
        if(RoyalShipmentSupport.read()){
            var tag=NbtIo.readCompressed(marker,NbtAccounter.unlimitedHeap());realm=RoyalShipmentSupport.restore(h,tag,true);player=new FakePlayer(level,new GameProfile(tag.getUUID("player"),"Norman cobble reopen"));source=manager.getVillage(new org.millenaire.village.VillageId(tag.getUUID("norman")));
            h.assertTrue(source!=null && source.getTownhall()!=null,"Saved original Norman religious source rehydrates without repair");RoyalShipmentSupport.activateVillage(level,source);var id=tag.getUUID("order");
            h.assertTrue(realm.store().load(id).escrow()==23 && source.getTownhall().getInventory().getCount(level,Items.COBBLESTONE)==1024,"Separate JVM retains23 real collected stones and1024 protected physical stones");
            realm.service().cancelPlayer(player,id);for(int n=0;n<8 && !realm.store().load(id).terminal();n++)realm.service().observe(id);
            h.assertTrue(realm.store().load(id).stage()==Stage.CANCELLED && realm.store().load(id).escrow()==0 && realm.coins()==128,"Predispatch return/refund preserves every original coin/stone across JVMs");
        }else{
            var fixture=fixture(h,"Norman cobble source");realm=fixture.realm;player=fixture.player;
            var type=org.millenaire.culture.ModCultures.getVillageType(ResourceLocation.parse("millenaire:norman/ecclesiastique"));var slot=type.layout().stream().filter(s->s.role().equals("centre")).findFirst().orElseThrow();var set=org.millenaire.culture.ModCultures.getBuildingPlanSet(slot.plan());var variant=set.variants().keySet().stream().sorted().findFirst().orElseThrow();var def=set.getLevel(variant,0);var origin=realm.source().getCenter().offset(0,0,1024);
            source=new org.millenaire.village.Village(org.millenaire.village.VillageId.random(),type.culture(),type.id(),origin);source.setVillageName("Existing Norman religious storage");
            var hall=new BuildingInstance(org.millenaire.building.BuildingId.random(),def.planId(),origin,net.minecraft.world.level.block.Rotation.NONE,BuildingInstance.Status.COMPLETE,set.id(),variant,0);hall.resolveSpecialPoints(org.millenaire.culture.ModCultures.getBuildingPlan(def.planId()));hall.initInventory();source.addBuilding(hall);
            for(var position:hall.getInventory().getChestPositions()){level.getChunkAt(position);level.setBlock(position,net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState(),2);}manager.addVillage(source);RoyalShipmentSupport.activateVillage(level,source);KingdomSavedData.get(level).attach(realm.kingdom(),source.getId().uuid());
            h.assertTrue(hall.getInventory().add(level,Items.COBBLESTONE,1047)==1047,"Declared1047 ordinary cobblestones enter genuine original Norman townhall storage");
        }
        RoyalShipmentView.forget(player.getUUID());RoyalShipmentView.selectVillage(player,source.getId().uuid(),true);RoyalShipmentView.selectVillage(player,realm.to(),false);RoyalShipmentView.selectProduct(player,ResourceLocation.parse("minecraft:cobblestone"));
        var stock=com.millenairekingdoms.shipping.RoyalVillageStock.forPlayer(player,source.getId().uuid()).availability(Items.COBBLESTONE);var frame=RoyalShipmentView.build(player,realm.kingdom());
        System.out.println("ROYAL_NORMAN_COBBLE_"+(RoyalShipmentSupport.read()?"REOPEN":"WRITE")+" stock="+stock+" quantity="+frame.getInt("quantity")+" canOrder="+frame.getBoolean("canOrder")+" reason="+frame.getString("reason"));
        h.assertTrue(stock.physical()==1047 && stock.target()==1024 && stock.construction()==0 && stock.residents()==0 && stock.retained()==1024 && stock.available()==23,"No-job Norman source protects exactly1024, never all1047 merely because the world reopened");
        h.assertTrue(frame.getInt("quantity")==40 && frame.getInt("available")==23 && !frame.getBoolean("canOrder") && frame.getString("reason").equals("INSUFFICIENT_STOCK"),"Default40 exceeds the genuine23 surplus but must not be presented as zero transferable stock");
        var reserve=frame.getCompound("reserve");h.assertTrue(reserve.getInt("target")==1024 && reserve.getInt("construction")==0 && reserve.getInt("residents")==0 && reserve.getInt("minimum")==16 && reserve.getInt("nativeProtected")==1024,"Norman diagnostic snapshot exposes every genuine contributor after loading the old native records");
        int minimum=com.millenairekingdoms.shipping.RoyalShipmentConfig.MINIMUM_REMAINDER.get();
        try{
            com.millenairekingdoms.shipping.RoyalShipmentConfig.MINIMUM_REMAINDER.set(2048);RoyalShipmentView.quantity(player,1);var protectedAll=RoyalShipmentView.build(player,realm.kingdom());
            h.assertTrue(protectedAll.getInt("physical")==1047 && protectedAll.getInt("available")==0 && protectedAll.getInt("retained")==2048 && !protectedAll.getBoolean("canOrder") && protectedAll.getCompound("reserve").getInt("minimum")==2048 && protectedAll.getCompound("reserve").getInt("construction")==0,"A genuine zero without construction remains enforced and its configured cause is explicit; no false promised1 item or weakened reserve");
        }finally{com.millenairekingdoms.shipping.RoyalShipmentConfig.MINIMUM_REMAINDER.set(minimum);}
        RoyalShipmentView.quantity(player,1);h.assertTrue(RoyalShipmentView.build(player,realm.kingdom()).getBoolean("canOrder"),"Applying one stone is genuinely eligible despite the disabled default40 offer");
        if(!RoyalShipmentSupport.read()){
            RoyalShipmentView.quantity(player,23);frame=RoyalShipmentView.build(player,realm.kingdom());h.assertTrue(frame.getBoolean("canOrder"),"Full23 surplus is authorized with the same untouched1024 reserve");var id=frame.getUUID("offer");RoyalShipmentView.order(player,id);realm.service().observe(id);
            h.assertTrue(realm.store().load(id).escrow()==23 && source.getTownhall().getInventory().getCount(level,Items.COBBLESTONE)==1024,"The actual accepted player shipment collects23, never touches the protected1024");
            var tag=realm.marker();tag.putUUID("norman",source.getId().uuid());tag.putUUID("player",player.getUUID());tag.putUUID("order",id);NbtIo.writeCompressed(tag,marker);realm.save();for(var v:List.of(realm.capital(),realm.source(),realm.target(),source))v.setActive(false);VillageSavedData.get(level).setDirty();
        }else{RoyalShipmentView.forget(player.getUUID());realm.close();manager.removeVillage(source.getId());}
        h.succeed();
    }
    private RoyalShipmentGuiGameTests(){}
}
