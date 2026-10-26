package com.millenairekingdoms.test;
import com.millenairekingdoms.economy.*;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.politics.PoliticsService;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.building.BuildingInstance;
import org.millenaire.village.*;

@PrefixGameTestTemplate(false)
public final class CapitalProductionGameTests {
    private static long value(net.minecraft.world.level.block.entity.ChestBlockEntity chest){var slots=new java.util.ArrayList<net.minecraft.world.item.ItemStack>();for(int n=0;n<chest.getContainerSize();n++)slots.add(chest.getItem(n));return RoyalCoinPlan.spendableValue(slots);}
    @GameTest(templateNamespace=Stage5Support.NS,template="empty",timeoutTicks=1200,batch="capital_production")
    public static void standaloneCapitalProducesByCompletedLevelAndInactiveDoesNot(GameTestHelper h)throws Exception{
        var one=Stage5Support.village(h,1,1,"Producing level1",0,BuildingInstance.Status.COMPLETE);var three=Stage5Support.village(h,4,4,"Producing level3",2,BuildingInstance.Status.COMPLETE);
        try{var d=new KingdomSavedData();var a=d.create("Capital one",one.getId().uuid());var b=d.create("Capital three",three.getId().uuid());d.startDay(0,Map.of());
            h.assertTrue(d.members(a.id()).size()==1 && EconomyService.dailyIncome(h.getLevel(),d,a.id())==4 && EconomyService.dailyIncome(h.getLevel(),d,b.id())==12,"A capital earns4 per completed centre level without attached villages");
            EconomyService.runDay(h.getLevel(),d,1);h.assertTrue(value(Stage5Support.chest(h,one))==4 && value(Stage5Support.chest(h,three))==12,"Production is actual original coins in original storage");
            d.setActive(a.id(),false);EconomyService.runDay(h.getLevel(),d,2);h.assertTrue(value(Stage5Support.chest(h,one))==4 && value(Stage5Support.chest(h,three))==24,"Inactive capital stops producing while active capital continues once per day");h.succeed();
        }finally{Stage5Support.remove(h,one,three);}
    }
    @GameTest(templateNamespace=Stage5Support.NS,template="empty",timeoutTicks=1200,batch="capital_production_restart")
    public static void originalCapitalCoinsPersistAndSameDayCannotProduceTwice(GameTestHelper h)throws Exception{
        var level=h.getLevel();var data=KingdomSavedData.get(level);var marker=level.getServer().getWorldPath(LevelResource.ROOT).resolve("capital-production-restart.dat");
        if(RoyalShipmentSupport.read()){
            var tag=NbtIo.readCompressed(marker,NbtAccounter.unlimitedHeap());var village=PoliticsService.village(level,tag.getUUID("village"));var k=tag.getUUID("kingdom");h.assertTrue(village!=null && data.owner(village.getId().uuid()).equals(k),"Original native capital/registry reload without repair");for(var pos:village.getTownhall().getInventory().getChestPositions())level.getChunkAt(pos);data.setActive(k,true);
            h.assertTrue(RoyalTreasurySnapshot.forNpc(level,k).spendableValue()==4 && !EconomyService.runDay(level,data,data.day()) && RoyalTreasurySnapshot.forNpc(level,k).spendableValue()==4,"Saved actual4 coins remain and the persisted day cannot mint again");
            EconomyService.runDay(level,data,data.day()+1);h.assertTrue(RoyalTreasurySnapshot.forNpc(level,k).spendableValue()==8 && data.owed(k)==0,"Next real booked day produces exactly4 more, not a backlog multiplier");data.setActive(k,false);h.succeed();return;
        }
        var bank=RoyalPaymentGameTests.persistentFixture(h,"capital-production");EconomyService.runDay(level,data,Math.max(0,data.day())+1);h.assertTrue(RoyalTreasurySnapshot.forNpc(level,bank.kingdom()).spendableValue()==4 && data.members(bank.kingdom()).size()==1,"Fresh original capital alone produced real4 deniers");
        data.setActive(bank.kingdom(),false);var tag=new CompoundTag();tag.putUUID("kingdom",bank.kingdom());tag.putUUID("village",bank.village().getId().uuid());NbtIo.writeCompressed(tag,marker);VillageSavedData.get(level).setDirty();h.succeed();
    }
}
