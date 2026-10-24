package com.millenairekingdoms.test;

import com.millenairekingdoms.autonomy.RoyalNpcRuntime;
import com.millenairekingdoms.autonomy.RoyalNpcEconomyService;
import com.millenairekingdoms.economy.RoyalNpcDecisionStore;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Explicit bounded step/startup and actual OP commands; automatic real-event behavior is a separate isolated gate. */
@PrefixGameTestTemplate(false)
public final class RoyalNpcRuntimeGameTests {
    private static final String NS="millenairekingdoms_royal_npc_runtime_tests";
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void boundedFairStepChargesOnlyOneThenTwoCurrentNpcNeedsAndNeverDecidesForAPlayer(GameTestHelper h){
        var realms=new ArrayList<RoyalShipmentSupport.Realm>();
        try{
            for(int n=0;n<4;n++){var r=RoyalShipmentSupport.realm(h,"npc-runtime-"+n);RoyalNpcEconomyGameTests.shortage(h,r);realms.add(r);}realms.get(3).ruler();
            var first=RoyalNpcRuntime.step(h.getLevel(),1);h.assertTrue(first.inspected()==1 && first.attempts()==1 && realms.stream().filter(r->r.coins()==247).count()==1,"Explicit one-kingdom budget permits exactly one actual normal9 fee");
            var second=RoyalNpcRuntime.step(h.getLevel(),2);h.assertTrue(second.inspected()==2 && second.attempts()==2 && realms.subList(0,3).stream().allMatch(r->r.coins()==247) && realms.get(3).coins()==256,"Round robin covers all three NPCs within1+2, excluding the current player ruler");
            var more=RoyalNpcRuntime.step(h.getLevel(),2);h.assertTrue(more.attempts()==0 && realms.subList(0,3).stream().allMatch(r->r.coins()==247),"Persisted day cooldowns prevent repeated spending while the cursor continues fairly");
            var before=new RoyalNpcDecisionStore(RoyalNpcDecisionStore.directory(h.getLevel())).entries();RoyalNpcRuntime.onStarted(new ServerStartedEvent(h.getLevel().getServer()));
            h.assertTrue(before.equals(new RoyalNpcDecisionStore(RoyalNpcDecisionStore.directory(h.getLevel())).entries()) && realms.subList(0,3).stream().allMatch(r->r.coins()==247),"Startup validates only and never replays/advances/spends an existing proposal");h.succeed();
        }finally{for(var r:realms)r.close();RoyalNpcRuntime.onStopped(new ServerStoppedEvent(h.getLevel().getServer()));}
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void unknownStartupHistoryStaysByteIdenticalAndCannotBeTurnedIntoNewNpcAttempts(GameTestHelper h) throws Exception {
        try(var r=RoyalShipmentSupport.realm(h,"npc-runtime-future")){
            RoyalNpcEconomyGameTests.shortage(h,r);var path=RoyalNpcDecisionStore.directory(h.getLevel()).resolve("decisions.dat");Files.createDirectories(path.getParent());byte[] original=Files.exists(path) ? Files.readAllBytes(path) : null;
            var future=new CompoundTag();future.putInt("format",999);future.put("decisions",new ListTag());
            try{net.minecraft.nbt.NbtIo.writeCompressed(future,path);var bytes=Files.readAllBytes(path);RoyalNpcRuntime.onStarted(new ServerStartedEvent(h.getLevel().getServer()));boolean failed=false;
                try{RoyalNpcRuntime.step(h.getLevel(),2);}catch(IllegalArgumentException|IllegalStateException expected){failed=true;}
                h.assertTrue(failed && r.coins()==256 && Arrays.equals(bytes,Files.readAllBytes(path)),"Unsupported startup history pauses new decisions without erasing bytes, charging or pretending an empty registry");
            }finally{if(original==null)Files.delete(path);else Files.write(path,original);RoyalNpcRuntime.onStopped(new ServerStoppedEvent(h.getLevel().getServer()));}h.succeed();
        }
    }
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void realEconomicExplainAndHistoryAreReadonlyOp2Commands(GameTestHelper h) throws Exception {
        try(var r=RoyalShipmentSupport.realm(h,"npc-op-diagnostics")){
            var setup=RoyalNpcEconomyGameTests.shortage(h,r);var player=new Stage5Support.TestPlayer(h.getLevel(),"NPC economics operator");var dispatcher=h.getLevel().getServer().getCommands().getDispatcher();
            boolean refused=false;try{dispatcher.execute("kingdoms economy explain "+r.kingdom(),player.createCommandSourceStack().withPermission(0));}catch(com.mojang.brigadier.exceptions.CommandSyntaxException expected){refused=true;}
            h.assertTrue(refused,"Ordinary players cannot invoke the operator economic diagnostics");
            var before=org.millenaire.village.VillageSavedData.get(h.getLevel()).save(new CompoundTag(),h.getLevel().registryAccess());
            int explanation=dispatcher.execute("kingdoms economy explain "+r.kingdom(),player.createCommandSourceStack().withPermission(2));
            int history=dispatcher.execute("kingdoms economy status "+r.kingdom(),player.createCommandSourceStack().withPermission(2));
            h.assertTrue(explanation==1 && history==1 && player.text().contains("Justified candidates=") && player.text().contains("SHIPMENT") && player.text().contains("masterAutonomy=false")
                    && r.coins()==256 && r.target().getPendingProject()==setup.pending() && before.equals(org.millenaire.village.VillageSavedData.get(h.getLevel()).save(new CompoundTag(),h.getLevel().registryAccess())),"Actual OP2 commands show real need/fee/config without advancing a project or spending");
            h.assertTrue(new RoyalNpcDecisionStore(RoyalNpcDecisionStore.directory(h.getLevel())).entries().stream().noneMatch(d->d.kingdom().equals(r.kingdom())),"Readonly explanation never creates a decision/approval");h.succeed();
        }
    }
    private RoyalNpcRuntimeGameTests(){}
}
