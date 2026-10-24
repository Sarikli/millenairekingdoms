package com.millenairekingdoms.test;

import com.millenairekingdoms.economy.RoyalPaymentService;
import com.millenairekingdoms.economy.RoyalPaymentJournal.State;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.shipping.RoyalShipmentConfig;
import com.millenairekingdoms.shipping.RoyalShipmentStore.*;
import com.millenairekingdoms.shipping.RoyalVillageStock;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.building.BuildingInstance;
import org.millenaire.item.ModItems;

/** Real bound stock/fee endpoints; route/time domain integration is a later gate. FakePlayer exercises authority only. */
@PrefixGameTestTemplate(false)
public final class RoyalIssuedShipmentGameTests {
    private static final String NS="millenairekingdoms_royal_shipment_tests";
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void issuedNpcStockKeepsActualSurplusAndRejectsNewPlayerAgency(GameTestHelper h)throws Exception{
        try(var f=RoyalPaymentGameTests.persistentFixture(h,"issued-npc")){
            stock(f);var intent=intent(f,null,0);var view=RoyalVillageStock.forIssued(f.level(),intent,intent.source());
            var available=view.availability(Items.SPRUCE_LOG);var ordinary=RoyalVillageStock.forNpc(f.level(),f.kingdom(),f.village().getId().uuid()).availability(Items.SPRUCE_LOG);
            System.out.println("ROYAL_ISSUED_NPC_STOCK "+available+" ordinary="+ordinary);
            h.assertTrue(available.physical()==96 && available.equals(ordinary),"Issued NPC mandate must preserve the SAME actual native surplus/reserves as current authorized stock");
            ruler(f,42);refused(h,view::requireUnchanged);refused(h,()->RoyalVillageStock.forIssued(f.level(),intent,intent.source()));h.assertTrue(f.chest().getItem(0).getCount()==64,"Issued NPC agency cannot become a player ruler's new extraction");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void issuedOfflinePlayerUsesOriginalUuidTenureAndReputation(GameTestHelper h)throws Exception{
        try(var f=RoyalPaymentGameTests.persistentFixture(h,"issued-offline")){
            stock(f);var ruler=ruler(f,42);var intent=intent(f,ruler.getUUID(),42);
            h.assertTrue(f.level().getServer().getPlayerList().getPlayer(ruler.getUUID())==null,"Fixture actor is actually absent from the online player list");
            var issued=RoyalVillageStock.forIssued(f.level(),intent,intent.source()).availability(Items.SPRUCE_LOG);var ordinary=RoyalVillageStock.forPlayer(ruler,f.village().getId().uuid()).availability(Items.SPRUCE_LOG);
            System.out.println("ROYAL_ISSUED_PLAYER_STOCK "+issued+" ordinary="+ordinary);
            h.assertTrue(issued.physical()==96 && issued.equals(ordinary),"Offline issued UUID must preserve exactly the native reserves of the currently authorized original ruler");
            refused(h,()->RoyalVillageStock.forNpc(f.level(),f.kingdom(),f.village().getId().uuid()));
            var wrongTenure=copy(intent,intent.source(),intent.capital(),ruler.getUUID(),41);refused(h,()->RoyalVillageStock.forIssued(f.level(),wrongTenure,wrongTenure.source()));
            f.village().addReputation(ruler.getUUID(),-f.village().getCombinedReputation(f.level(),ruler.getUUID()));refused(h,()->RoyalVillageStock.forIssued(f.level(),intent,intent.source()));h.assertTrue(f.chest().getItem(0).getCount()==64,"Lost reputation never changes stock or impersonates NPC agency");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void returnViewKeepsOriginalVillageAfterRulerRetirementButNotKingdomLoss(GameTestHelper h)throws Exception{
        try(var f=RoyalPaymentGameTests.persistentFixture(h,"issued-return")){
            stock(f);var original=ruler(f,42);var intent=intent(f,original.getUUID(),42);var data=KingdomSavedData.get(f.level());data.clearRuler(f.kingdom());data.claimRule(f.kingdom(),UUID.randomUUID(),"Successor",43);
            refused(h,()->RoyalVillageStock.forIssued(f.level(),intent,intent.source()));var returned=RoyalVillageStock.forReturn(f.level(),intent);
            h.assertTrue(returned.endpoint().equals(intent.source()) && returned.slots().stream().mapToInt(ItemStack::getCount).sum()==96,"Read-only return view preserves exact original member/storage without granting old spending agency");
            data.setActive(f.kingdom(),false);refused(h,returned::requireUnchanged);refused(h,()->RoyalVillageStock.forReturn(f.level(),intent));h.assertTrue(f.chest().getItem(0).getCount()==64,"Fallen/inactive original kingdom cannot redirect held cargo");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void issuedEndpointsRejectAlteredTownhallOwnerCenterTypeCapitalAndDimension(GameTestHelper h)throws Exception{
        try(var f=RoyalPaymentGameTests.persistentFixture(h,"issued-bindings")){
            stock(f);var intent=intent(f,null,0);var endpoint=intent.source();
            for(var wrong:java.util.List.of(new Endpoint(endpoint.village(),endpoint.type(),UUID.randomUUID(),endpoint.center(),null),new Endpoint(endpoint.village(),endpoint.type(),endpoint.townhall(),endpoint.center(),UUID.randomUUID()),
                    new Endpoint(endpoint.village(),endpoint.type(),endpoint.townhall(),endpoint.center().offset(1,0,0),null),new Endpoint(endpoint.village(),ResourceLocation.parse("unknown:village"),endpoint.townhall(),endpoint.center(),null))){
                var changed=copy(intent,wrong,intent.capital(),null,0);refused(h,()->RoyalVillageStock.forIssued(f.level(),changed,wrong));refused(h,()->RoyalVillageStock.forReturn(f.level(),changed));
            }
            var moved=copy(intent,endpoint,UUID.randomUUID(),null,0);refused(h,()->RoyalVillageStock.forIssued(f.level(),moved,endpoint));
            var foreign=new Intent(intent.id(),"minecraft:the_nether",intent.kingdom(),intent.capital(),null,0,intent.source(),intent.destination(),intent.product(),40,7,100,1000);refused(h,()->RoyalVillageStock.forIssued(f.level(),foreign,endpoint));h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void shipmentFeeEndpointsBindOriginalPurposeReferenceCapitalActorAndAmount(GameTestHelper h)throws Exception{
        try(var f=RoyalPaymentGameTests.persistentFixture(h,"issued-fee-binding")){
            coins(f);var id=UUID.randomUUID();var payments=f.service();payments.payNpc(f.kingdom(),id,RoyalPaymentService.Purpose.SHIPMENT_FEE,id,7);var capital=f.village().getId().uuid();
            h.assertTrue(payments.inspectShipment(id,f.kingdom(),capital,null,7).state()==State.PAID && f.value()==121,"Actual original7 transport deniers are removed before a fee receipt exists");
            refused(h,()->payments.inspectShipment(id,f.kingdom(),capital,null,8));refused(h,()->payments.inspectShipment(id,f.kingdom(),UUID.randomUUID(),null,7));refused(h,()->payments.inspectShipment(id,f.kingdom(),capital,UUID.randomUUID(),7));
            h.assertTrue(payments.acknowledgeShipment(id,f.kingdom(),capital,null,7).state()==State.COMMITTED && f.value()==121,"Existing bound fee commits without another charge");
            var construction=UUID.randomUUID();payments.payNpc(f.kingdom(),construction,RoyalPaymentService.Purpose.CONSTRUCTION,construction,1);
            refused(h,()->payments.inspectShipment(construction,f.kingdom(),capital,null,1));refused(h,()->payments.settleRetiredShipment(construction,f.kingdom(),capital,null,1,true));
            payments.refundNpc(f.kingdom(),construction);h.assertTrue(f.value()==121,"Wrong-purpose receipt cannot become shipment payment/refund");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void acceptedShipmentFeeAcknowledgesWithoutPhysicalStorageOrNewSpending(GameTestHelper h)throws Exception{
        try(var f=RoyalPaymentGameTests.persistentFixture(h,"issued-fee-ack")){
            coins(f);var id=UUID.randomUUID();var payments=f.service();payments.payNpc(f.kingdom(),id,RoyalPaymentService.Purpose.SHIPMENT_FEE,id,7);var field=BuildingInstance.class.getDeclaredField("inventory");field.setAccessible(true);var inventory=field.get(f.village().getTownhall());field.set(f.village().getTownhall(),null);
            try{
                h.assertTrue(payments.acknowledgeShipment(id,f.kingdom(),f.village().getId().uuid(),null,7).state()==State.COMMITTED && f.value()==121,"Existing acceptance acknowledgement needs no chunks/storage or new debit");
                h.assertTrue(payments.settleRetiredShipment(id,f.kingdom(),f.village().getId().uuid(),null,7,true).state()==State.COMMITTED && f.value()==121,"Committed logistics cannot become a refund after retirement");
            }finally{field.set(f.village().getTownhall(),inventory);}h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void retiredOfflineShipmentRefundUsesOnlyActualOriginalFeeAndEligibleCapital(GameTestHelper h)throws Exception{
        try(var f=RoyalPaymentGameTests.persistentFixture(h,"issued-fee-refund")){
            coins(f);var ruler=ruler(f,42);var id=UUID.randomUUID();var payments=f.service();payments.payPlayer(ruler,id,RoyalPaymentService.Purpose.SHIPMENT_FEE,id,7);var data=KingdomSavedData.get(f.level());data.clearRuler(f.kingdom());
            refused(h,()->payments.refundPlayer(ruler,id));refused(h,()->payments.settleRetiredShipment(id,f.kingdom(),f.village().getId().uuid(),ruler.getUUID(),8,true));
            data.setActive(f.kingdom(),false);refused(h,()->payments.settleRetiredShipment(id,f.kingdom(),f.village().getId().uuid(),ruler.getUUID(),7,true));h.assertTrue(f.value()==121 && f.journal().load(id).state()==State.PAID,"Unavailable old capital preserves original fee liability instead of redirecting/inventing refund");
            data.setActive(f.kingdom(),true);h.assertTrue(payments.settleRetiredShipment(id,f.kingdom(),f.village().getId().uuid(),ruler.getUUID(),7,true).state()==State.REFUNDED && f.value()==128,"Existing original fee refunds with original offline UUID, no NPC impersonation");
            payments.settleRetiredShipment(id,f.kingdom(),f.village().getId().uuid(),ruler.getUUID(),7,true);h.assertTrue(f.value()==128,"Same retired refund is never paid twice");h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void distanceTravelFormulaIsPositiveBoundedAndAcceptedDurationIsImmutable(GameTestHelper h){
        var transit=RoyalShipmentConfig.Transit.DEFAULTS;
        h.assertTrue(transit.duration(0)==1200 && transit.duration(1)==1800 && transit.duration(128)==1800 && transit.duration(129)==2400,"Default1-minute staging plus30 seconds per started128 horizontal blocks");
        long accepted=transit.duration(128);var changed=new RoyalShipmentConfig.Transit(2400,1200);h.assertTrue(accepted==1800 && changed.duration(128)==3600,"New settings affect new duration calculations, not a previously quoted immutable value");
        refused(h,()->transit.duration(-1));refused(h,()->transit.duration(Long.MAX_VALUE));refused(h,()->new RoyalShipmentConfig.Transit(0,600));refused(h,()->new RoyalShipmentConfig.Transit(1200,0));h.succeed();
    }
    private static Intent intent(RoyalPaymentGameTests.Fixture f,UUID actor,long since){var village=f.village();var source=new Endpoint(village.getId().uuid(),village.getVillageTypeId(),village.getTownhall().getId().uuid(),village.getCenter(),village.getOwnerUUID());var destination=new Endpoint(UUID.randomUUID(),source.type(),UUID.randomUUID(),source.center().offset(64,0,0),null);return new Intent(UUID.randomUUID(),f.level().dimension().location().toString(),f.kingdom(),village.getId().uuid(),actor,since,source,destination,ResourceLocation.parse("minecraft:spruce_log"),40,7,100,1000);}
    private static Intent copy(Intent i,Endpoint source,UUID capital,UUID actor,long since){return new Intent(i.id(),i.dimension(),i.kingdom(),capital,actor,since,source,i.destination(),i.product(),i.quantity(),i.fee(),i.issued(),i.travelTicks());}
    private static void stock(RoyalPaymentGameTests.Fixture f){f.village().setActive(true);f.village().setNoProjectsLeftUntil(Long.MAX_VALUE);f.level().getChunkAt(f.village().getCenter());f.village().getTownhall().getInventory().loadChunks(f.level());f.chest().setItem(0,new ItemStack(Items.SPRUCE_LOG,64));f.chest().setItem(1,new ItemStack(Items.SPRUCE_LOG,32));}
    private static void coins(RoyalPaymentGameTests.Fixture f){f.chest().setItem(0,new ItemStack(ModItems.DENIER_ARGENT.get(),2));}
    private static FakePlayer ruler(RoyalPaymentGameTests.Fixture f,long since){var ruler=new FakePlayer(f.level(),new GameProfile(UUID.randomUUID(),"Issued ruler"));f.village().addReputation(ruler.getUUID(),40000-f.village().getCombinedReputation(f.level(),ruler.getUUID()));KingdomSavedData.get(f.level()).claimRule(f.kingdom(),ruler.getUUID(),"Issued ruler",since);return ruler;}
    private static void refused(GameTestHelper h,Runnable action){boolean rejected=false;try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){rejected=true;}h.assertTrue(rejected,"Changed mandate/endpoint/fee or invalid duration must be rejected before mutation");}
}
