package com.millenairekingdoms.test;

import com.millenairekingdoms.shipping.RoyalShipmentRuntime;
import com.millenairekingdoms.shipping.RoyalShipmentService;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class RoyalShipmentRuntimeGameTests {
    @GameTest(templateNamespace="millenairekingdoms_royal_shipment_runtime_tests",template="empty",timeoutTicks=1200)
    public static void existingJobsHaveBoundedFairBudgetAndUnknownIndexNeverMutatesThem(GameTestHelper h)throws Exception{
        try(var a=RoyalShipmentSupport.realm(h,"Budget A");var b=RoyalShipmentSupport.realm(h,"Budget B");var c=RoyalShipmentSupport.realm(h,"Budget C")){
            var realms=List.of(a,b,c);var ids=List.of(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());for(int i=0;i<3;i++)RoyalShipmentSupport.fastRequest(realms.get(i),null,ids.get(i));
            RoyalShipmentRuntime.onStarted(new ServerStartedEvent(h.getLevel().getServer()));h.assertTrue(a.coins()==119 && b.coins()==119 && c.coins()==119 && a.goods(a.source())==96,"Startup validates only, no new spending/pickup");
            for(int i=1;i<=3;i++){h.assertTrue(RoyalShipmentRuntime.step(h.getLevel(),1)==1,"One-job budget is exact");int picked=0;for(int n=0;n<3;n++)if(realms.get(n).store().load(ids.get(n)).collected()==40)picked++;h.assertTrue(picked==i,"Round robin advances each eligible original job before revisiting it");}
            h.assertTrue(RoyalShipmentRuntime.step(h.getLevel(),2)==2,"Two-job budget is exact");int dispatched=0;for(int n=0;n<3;n++)if(realms.get(n).store().load(ids.get(n)).dispatched())dispatched++;h.assertTrue(dispatched==2,"Third job is deferred by total pass budget");
            var file=RoyalShipmentService.directory(h.getLevel()).resolve("shipments.dat");var original=Files.readAllBytes(file);var tag=NbtIo.readCompressed(file,NbtAccounter.unlimitedHeap());tag.putInt("format",999);NbtIo.writeCompressed(tag,file);var future=Files.readAllBytes(file);boolean refused=false;
            try{try{RoyalShipmentRuntime.step(h.getLevel(),1);}catch(IllegalStateException expected){refused=true;}h.assertTrue(refused && java.util.Arrays.equals(future,Files.readAllBytes(file)) && a.goods(a.source())==56 && b.goods(b.source())==56 && c.goods(c.source())==56,"Unknown index preserves physical inventories and bytes");}
            finally{Files.write(file,original);}
            RoyalShipmentRuntime.onStarted(new ServerStartedEvent(h.getLevel().getServer()));for(int n=0;n<3;n++){realms.get(n).service().cancelNpc(realms.get(n).kingdom(),ids.get(n));realms.get(n).service().observe(ids.get(n));}
            h.assertTrue(RoyalShipmentRuntime.step(h.getLevel(),2)==0,"Resolved history is never new work");h.succeed();
        }
    }
}
