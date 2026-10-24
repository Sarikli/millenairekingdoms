package com.millenairekingdoms.test;

import static com.millenairekingdoms.test.Stage5Support.*;

import com.millenairekingdoms.economy.RoyalTreasurySnapshot;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.building.BuildingInstance;
import org.millenaire.building.BuildingInventory;
import org.millenaire.item.ModItems;

/** Actual town-hall chests and existing ruler/kingdom rules, without financial world mutation. */
@PrefixGameTestTemplate(false)
public final class RoyalSnapshotGameTests {
    private static final String NS="millenairekingdoms_royal_economy_tests";

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void owedTaxIsNotPhysicalSpendingMoney(GameTestHelper h) throws Exception {
        var v=village(h,1,1,"Royal physical coins");
        try {
            var k=kingdom(h,v); var data=KingdomSavedData.get(h.getLevel());
            data.startDay(Math.max(0,data.day()+1),Map.of(k.id(),10_000L));
            chest(h,v).setItem(0,new ItemStack(ModItems.DENIER.get(),7));
            var quote=RoyalTreasurySnapshot.forNpc(h.getLevel(),k.id());
            h.assertTrue(quote.spendableValue()==7 && data.owed(k.id())>=10_000,"Only delivered real loose coins can be quoted");
            rejected(h,()->quote.planDebit(8)); h.succeed();
        } finally { remove(h,v); }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void playerAndNpcRulerPermissionsAreRechecked(GameTestHelper h) throws Exception {
        var v=village(h,1,1,"Royal permissions");
        try {
            var k=kingdom(h,v); var data=KingdomSavedData.get(h.getLevel());
            var before=RoyalTreasurySnapshot.forNpc(h.getLevel(),k.id()); var ruler=player(h,"Royal ruler",v);
            reputation(h,v,ruler.getUUID(),32768); data.claimRule(k.id(),ruler.getUUID(),"Royal ruler",1);
            rejected(h,()->before.requireUnchanged()); rejected(h,()->RoyalTreasurySnapshot.forNpc(h.getLevel(),k.id()));
            var quote=RoyalTreasurySnapshot.forPlayer(ruler);
            h.assertTrue(quote.kingdom().equals(k.id()),"Real player ruler can quote their own treasury");
            var outsider=player(h,"Royal outsider",v); rejected(h,()->RoyalTreasurySnapshot.forPlayer(outsider));
            reputation(h,v,ruler.getUUID(),0); rejected(h,()->quote.requireUnchanged());
            h.succeed();
        } finally { remove(h,v); }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void absentInactiveAndFallenKingdomsCannotQuote(GameTestHelper h) throws Exception {
        rejected(h,()->RoyalTreasurySnapshot.forNpc(h.getLevel(),UUID.randomUUID()));
        var victim=village(h,1,1,"Royal victim"); var attacker=village(h,5,5,"Royal attacker");
        try {
            var v=kingdom(h,victim); var a=kingdom(h,attacker); var d=KingdomSavedData.get(h.getLevel());
            d.setActive(v.id(),false); rejected(h,()->RoyalTreasurySnapshot.forNpc(h.getLevel(),v.id())); d.setActive(v.id(),true);
            var before=RoyalTreasurySnapshot.forNpc(h.getLevel(),v.id()); d.declareWar(a.id(),v.id(),1);
            for(int i=0;i<2;i++) { var raid=d.begin(a.capital(),v.capital(),1,null); d.recordVictory(raid); d.finish(raid,KingdomSavedData.Outcome.LOST); }
            h.assertTrue(d.kingdom(v.id()).fallen(),"Fixture actually conquered the capital");
            rejected(h,()->RoyalTreasurySnapshot.forNpc(h.getLevel(),v.id())); rejected(h,()->before.requireUnchanged()); h.succeed();
        } finally { remove(h,victim,attacker); }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void unloadedStorageDoesNotForceChunks(GameTestHelper h) throws Exception {
        var v=village(h,1,1,"Royal unloaded");
        try {
            var k=kingdom(h,v); var level=h.getLevel(); var far=new BlockPos(8_000_000,64,8_000_000);
            h.assertTrue(!level.isLoaded(far),"Fixture starts with a genuinely unloaded chunk");
            inventory(v,List.of(v.getCenter(),far)); var forced=level.getForcedChunks().size();
            rejected(h,()->RoyalTreasurySnapshot.forNpc(level,k.id()));
            h.assertTrue(!level.isLoaded(far) && forced==level.getForcedChunks().size(),"Failed quote neither loads nor forces the missing chunk"); h.succeed();
        } finally { remove(h,v); }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void doubleChestHalvesAreIncludedExactlyOnce(GameTestHelper h) throws Exception {
        var v=village(h,1,1,"Royal double chest");
        try {
            var k=kingdom(h,v); var level=h.getLevel(); var pos=v.getCenter();
            var left=Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE,ChestType.LEFT);
            var other=pos.relative(ChestBlock.getConnectedDirection(left));
            level.setBlock(pos,left,2); level.setBlock(other,left.setValue(ChestBlock.TYPE,ChestType.RIGHT),2);
            inventory(v,List.of(pos,other));
            var first=(net.minecraft.world.level.block.entity.ChestBlockEntity)level.getBlockEntity(pos);
            var second=(net.minecraft.world.level.block.entity.ChestBlockEntity)level.getBlockEntity(other);
            first.setItem(0,new ItemStack(ModItems.DENIER_OR.get())); second.setItem(0,new ItemStack(ModItems.DENIER_ARGENT.get()));
            var quote=RoyalTreasurySnapshot.forNpc(level,k.id());
            h.assertTrue(quote.positions().size()==2 && quote.slots().size()==54 && quote.spendableValue()==4160,
                    "Both physical halves counted once despite both metadata positions listing the pair");
            inventory(v,List.of(pos));
            h.assertTrue(RoyalTreasurySnapshot.forNpc(level,k.id()).spendableValue()==4160,"A metadata entry for only one half still includes its partner"); h.succeed();
        } finally { remove(h,v); }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void changedOrReplacedChestInvalidatesTheQuote(GameTestHelper h) throws Exception {
        var v=village(h,1,1,"Royal stale");
        try {
            var k=kingdom(h,v); var level=h.getLevel(); chest(h,v).setItem(0,new ItemStack(ModItems.DENIER.get(),10));
            var quote=RoyalTreasurySnapshot.forNpc(level,k.id()); quote.slots().getFirst().setCount(1);
            h.assertTrue(quote.spendableValue()==10 && chest(h,v).getItem(0).getCount()==10,"Caller cannot mutate quote or real chest through returned stacks");
            chest(h,v).setItem(0,new ItemStack(ModItems.DENIER.get(),9)); rejected(h,()->quote.requireUnchanged());
            var replacementQuote=RoyalTreasurySnapshot.forNpc(level,k.id());
            level.setBlockAndUpdate(v.getCenter(),Blocks.AIR.defaultBlockState()); level.setBlockAndUpdate(v.getCenter(),Blocks.CHEST.defaultBlockState());
            chest(h,v).setItem(0,new ItemStack(ModItems.DENIER.get(),9));
            rejected(h,()->replacementQuote.requireUnchanged()); h.succeed();
        } finally { remove(h,v); }
    }

    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=200)
    public static void treasuryAccessOffTheServerThreadIsRejected(GameTestHelper h) throws Exception {
        var v=village(h,1,1,"Royal thread");
        try {
            var k=kingdom(h,v); var level=h.getLevel();
            boolean refused=CompletableFuture.supplyAsync(()->{
                try { RoyalTreasurySnapshot.forNpc(level,k.id()); return false; }
                catch(IllegalStateException expected) { return true; }
            }).join();
            h.assertTrue(refused,"An asynchronous request cannot even scan physical treasury storage"); h.succeed();
        } finally { remove(h,v); }
    }

    private static void inventory(org.millenaire.village.Village village,List<BlockPos> positions) throws Exception {
        Field field=BuildingInstance.class.getDeclaredField("inventory"); field.setAccessible(true);
        field.set(village.getTownhall(),new BuildingInventory(positions));
    }
    private static void rejected(GameTestHelper h,Runnable action) {
        boolean refused=false; try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){refused=true;}
        h.assertTrue(refused,"Invalid/stale/unauthorized treasury quote must be rejected without mutation");
    }
}
