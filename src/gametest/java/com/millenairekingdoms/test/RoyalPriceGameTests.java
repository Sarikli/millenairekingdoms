package com.millenairekingdoms.test;

import static com.millenairekingdoms.test.Stage5Support.*;
import com.millenairekingdoms.economy.EconomyService;
import com.millenairekingdoms.economy.RoyalEconomyConfig;
import com.millenairekingdoms.economy.RoyalPaymentJournal.State;
import com.millenairekingdoms.economy.RoyalPaymentService;
import com.millenairekingdoms.economy.RoyalPrices;
import com.millenairekingdoms.economy.RoyalTreasurySnapshot;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.building.BuildingInstance;
import org.millenaire.item.ModItems;

@PrefixGameTestTemplate(false)
public final class RoyalPriceGameTests {
    private static final String NS="millenairekingdoms_royal_economy_tests";

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void defaultPatronageSpendsRealFundsAndKeepsTaxFormula(GameTestHelper h) throws Exception {
        var c=village(h,1,1,"Royal price capital");
        var m=village(h,5,5,"Royal price member",1,BuildingInstance.Status.COMPLETE);
        try {
            var k=kingdom(h,c); var data=KingdomSavedData.get(h.getLevel()); data.attach(k.id(),m.getId().uuid());
            long income=EconomyService.dailyIncome(h.getLevel(),data,k.id());
            h.assertTrue(income==12 && RoyalPrices.construction(RoyalEconomyConfig.Settings.DEFAULTS)==64,
                    "Default64-denier patronage stays fixed; RC.8 capital4 plus level2 member8 yields12 per day");
            chest(h,c).setItem(0,new ItemStack(ModItems.DENIER_ARGENT.get())); var id=UUID.randomUUID(); var service=new RoyalPaymentService(h.getLevel());
            h.assertTrue(service.payNpc(k.id(),id,RoyalPaymentService.Purpose.CONSTRUCTION,UUID.randomUUID(),64).state()==State.PAID
                    && RoyalTreasurySnapshot.forNpc(h.getLevel(),k.id()).spendableValue()==0,"Default patronage actually removes its real silver coin");
            service.commitNpc(k.id(),id);
            h.assertTrue(EconomyService.dailyIncome(h.getLevel(),data,k.id())==12 && data.owed(k.id())==0,"Administrative charge neither changes taxation nor creates owed income"); h.succeed();
        } finally { remove(h,c,m); }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void shipmentFeeRoundsDistanceAndLoadWithoutFreeOrOverflowPrices(GameTestHelper h) {
        var defaults=RoyalEconomyConfig.Settings.DEFAULTS;
        h.assertTrue(RoyalPrices.shipment(defaults,64,256)==7 && RoyalPrices.shipment(defaults,65,257)==9,
                "Started route/load bands charge the next band at each boundary");
        h.assertTrue(RoyalPrices.shipment(new RoyalEconomyConfig.Settings(1,1,0,0),1,0)==1,"Even zero scaling retains a positive administration fee");
        invalid(h,()->RoyalPrices.shipment(defaults,0,1)); invalid(h,()->RoyalPrices.shipment(defaults,1,-1));
        invalid(h,()->RoyalPrices.shipment(new RoyalEconomyConfig.Settings(1,1,Integer.MAX_VALUE,Integer.MAX_VALUE),Long.MAX_VALUE,Long.MAX_VALUE));
        invalid(h,()->new RoyalEconomyConfig.Settings(0,1,1,1)); invalid(h,()->new RoyalEconomyConfig.Settings(1,0,1,1));
        h.succeed();
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void serverPriceSettingsAreFrozenForEachAcceptedQuote(GameTestHelper h) {
        int previous=RoyalEconomyConfig.CONSTRUCTION_FEE.get();
        try {
            RoyalEconomyConfig.CONSTRUCTION_FEE.set(64); var accepted=RoyalEconomyConfig.current();
            RoyalEconomyConfig.CONSTRUCTION_FEE.set(128);
            h.assertTrue(RoyalPrices.construction(accepted)==64 && RoyalPrices.construction(RoyalEconomyConfig.current())==128,
                    "Config reload changes new quotes while accepted immutable settings keep the original price"); h.succeed();
        } finally { RoyalEconomyConfig.CONSTRUCTION_FEE.set(previous); }
    }

    private static void invalid(GameTestHelper h,Runnable action) {
        boolean rejected=false; try { action.run(); } catch(IllegalArgumentException expected) { rejected=true; }
        h.assertTrue(rejected,"Invalid/free/overflowing royal price must be refused");
    }
}
