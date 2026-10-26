package com.millenairekingdoms.test;

import com.millenairekingdoms.atlas.*;
import com.millenairekingdoms.history.*;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.network.ChronicleRequestPayload;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Actual server snapshots over registered metadata; no claim about rendered pixels or natural generation. */
@PrefixGameTestTemplate(false)
public final class AtlasViewGameTests {
    private static final String NS="millenairekingdoms_atlas_tests";
    private static ChronicleRequestPayload request(String mode,UUID kingdom,UUID cursor,UUID event,UUID village){return new ChronicleRequestPayload(UUID.randomUUID(),1,mode,Optional.ofNullable(kingdom),"",0,Long.MAX_VALUE,Optional.ofNullable(cursor),"",Optional.ofNullable(event),Optional.ofNullable(village));}
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void ruinsAndInhabitedLoneStructuresNeverEnterPoliticalPagesOrPins(GameTestHelper h){boolean old=AtlasConfig.PUBLIC.get();try(var f=new PersonalityGameTests.Villages(h)){
        var village=f.add("norman/agricole");var ruin=f.add("indian/ruinedfort");var shrine=f.add("indian/loneshrine");var level=h.getLevel();var player=new Stage5Support.TestPlayer(level,"Village map observer");player.setPos(0,64,0);AtlasConfig.PUBLIC.set(true);
        var known=AtlasDiscovery.known(player);h.assertTrue(known.contains(ruin.getId().uuid()) && known.contains(shrine.getId().uuid()),"Even remembered/public native structures must pass the political filter");
        int loaded=level.getChunkSource().getLoadedChunksCount();var view=AtlasViews.build(player,request("ATLAS",null,null,null,null));
        h.assertTrue(view.getInt("total")==1 && view.getList("settlements",10).size()==1 && view.getList("settlements",10).getCompound(0).getUUID("id").equals(village.getId().uuid()) && !view.hasUUID("next"),"Only the real village contributes to markers, total and pagination; occupied lone buildings are excluded by native type");
        var event=HistoryEvent.create(HistoryEvent.Kind.VILLAGE_CONQUEST,1,24000,level.dimension().location().toString(),UUID.randomUUID().toString(),List.of(new HistoryEvent.Realm(UUID.randomUUID(),"Historical realm","millenaire:indian","GENERAL")),List.of(new HistoryEvent.Place(ruin.getId().uuid(),"Old ruin",true,ruin.getCenter().getX(),ruin.getCenter().getZ())),null,"","","RECORDED",Map.of());HistoryHooks.store(level).append(event);
        h.assertTrue(!AtlasViews.build(player,request("ATLAS",null,null,event.id(),ruin.getId().uuid())).contains("historicalPin") && loaded==level.getChunkSource().getLoadedChunksCount(),"A history deep link cannot restore a forbidden structure marker or load terrain");h.succeed();}finally{AtlasConfig.PUBLIC.set(old);}}
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void authoritativeSnapshotUsesNativeTypeIconsColorsAndRealCapitalIdentity(GameTestHelper h){try(var f=new PersonalityGameTests.Villages(h)){
        var a=f.add("norman/militaire");var b=f.add("norman/agricole");var k=f.kingdom(a);var data=KingdomSavedData.get(h.getLevel());data.attach(k.id(),b.getId().uuid());var player=new Stage5Support.TestPlayer(h.getLevel(),"Atlas ruler");data.claimRule(k.id(),player.getUUID(),"Atlas ruler",h.getLevel().getGameTime());
        var view=AtlasViews.build(player,request("ATLAS",null,null,null,null));var sites=view.getList("settlements",10);
        h.assertTrue(sites.size()==2 && sites.stream().filter(t->((CompoundTag)t).getBoolean("capital")).count()==1 && sites.stream().allMatch(t->((CompoundTag)t).getUUID("kingdom").equals(k.id())),"Actual membership and unique current capital determine marker ownership");
        for(var raw:sites){var tag=(CompoundTag)raw;h.assertTrue(net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(tag.getString("icon"))) && !tag.getString("typeName").isEmpty(),"Icon comes from the real native village-type item, with readable type name");}
        int color=AtlasColors.get(h.getLevel()).color(k.id());
        h.assertTrue(view.getList("kingdoms",10).getCompound(0).getInt("color")==color && sites.stream().allMatch(t->((CompoundTag)t).getInt("politicalColor")==color && ((CompoundTag)t).getString("kingdomName").equals(k.name())),"Capital and member with different native types use exactly the same persisted political color/name");h.succeed();}}
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void kingdomInformationSeparatesPublicFactsFromCurrentRulerEconomics(GameTestHelper h){try(var f=new PersonalityGameTests.Villages(h)){
        var k=f.kingdom(f.add("seljuk/religious_village_seljuks"));var data=KingdomSavedData.get(h.getLevel());var owner=new Stage5Support.TestPlayer(h.getLevel(),"Own atlas");var foreign=new Stage5Support.TestPlayer(h.getLevel(),"Foreign atlas");data.claimRule(k.id(),owner.getUUID(),"Own atlas",h.getLevel().getGameTime());
        var own=AtlasViews.build(owner,request("INFO",k.id(),null,null,null)).getCompound("info");var other=AtlasViews.build(foreign,request("INFO",k.id(),null,null,null)).getCompound("info");
        h.assertTrue(own.getBoolean("economicRights") && own.contains("income") && !other.contains("income") && !other.contains("owed") && !other.contains("treasury"),"Connection's current ruler alone receives economic fields");
        h.assertTrue(other.getString("name").equals(k.name()) && other.getString("ethos").equals("RELIGIOUS") && other.contains("founded") && other.getString("ruler").equals("Own atlas"),"Existing authoritative public profile/founding/government remain accessible");h.succeed();}}
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=2400)
    public static void actualCapitalCaptureChangesSnapshotOwnershipAndPreservesTheFallenArchive(GameTestHelper h){try(var f=new PersonalityGameTests.Villages(h)){
        var a=f.add("norman/militaire");var b=f.add("norman/ecclesiastique");var ka=f.kingdom(a);var kb=f.kingdom(b);var level=h.getLevel();var d=KingdomSavedData.get(level);var player=new Stage5Support.TestPlayer(level,"Capture viewer");level.getChunkAt(a.getCenter());level.getChunkAt(b.getCenter());player.setPos(a.getCenter().getX(),64,a.getCenter().getZ());AtlasDiscovery.known(player);
        var before=AtlasViews.build(player,request("ATLAS",null,null,null,null));d.declareWar(ka.id(),kb.id(),level.getGameTime());for(int n=0;n<2;n++){var raid=d.begin(a.getId().uuid(),b.getId().uuid(),level.getGameTime(),null);d.recordVictory(raid);d.finish(raid,KingdomSavedData.Outcome.LOST);}
        var after=AtlasViews.build(player,request("ATLAS",null,null,null,null));var captured=after.getList("settlements",10).stream().map(t->(CompoundTag)t).filter(t->t.getUUID("id").equals(b.getId().uuid())).findFirst().orElseThrow();
        h.assertTrue(!before.getString("revision").equals(after.getString("revision")) && captured.getUUID("kingdom").equals(ka.id()) && !captured.getBoolean("capital"),"Genuine political receipts change revision/current allegiance/former-capital marker");
        h.assertTrue(captured.getInt("politicalColor")==AtlasColors.get(level).color(ka.id()) && captured.getString("kingdomName").equals(ka.name()),"Conquest updates marker color and kingdom name immediately without restarting or altering village identity");
        h.assertTrue(AtlasViews.build(player,request("INFO",kb.id(),null,null,null)).getCompound("info").getBoolean("fallen"),"Conquered kingdom still has a selectable surviving historical archive");h.succeed();}}
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=2400)
    public static void snapshotPaginationIsBoundedAndPublicRevealRequiresServerOptIn(GameTestHelper h){boolean old=AtlasConfig.PUBLIC.get();try(var f=new PersonalityGameTests.Villages(h)){
        var ids=new HashSet<UUID>();for(int n=0;n<130;n++)ids.add(f.add("norman/agricole").getId().uuid());var player=new Stage5Support.TestPlayer(h.getLevel(),"Paged atlas");player.setPos(0,64,0);AtlasConfig.PUBLIC.set(false);var hidden=AtlasViews.build(player,request("ATLAS",null,null,null,null));h.assertTrue(hidden.getList("settlements",10).isEmpty(),"Far unvisited registered settlements stay hidden by default");
        AtlasConfig.PUBLIC.set(true);var first=AtlasViews.build(player,request("ATLAS",null,null,null,null));var second=AtlasViews.build(player,request("ATLAS",null,first.getUUID("next"),null,null));h.assertTrue(first.getList("settlements",10).size()==128 && second.getList("settlements",10).size()==2 && !second.hasUUID("next") && first.getString("revision").equals(second.getString("revision")),"Two bounded authoritative pages describe one unchanged130-site revision");
        h.assertTrue(first.getList("settlements",10).stream().noneMatch(raw->h.getLevel().isLoaded(new net.minecraft.core.BlockPos(((CompoundTag)raw).getInt("x"),64,((CompoundTag)raw).getInt("z")))),"Public metadata visibility still does not force distant chunks");h.succeed();}finally{AtlasConfig.PUBLIC.set(old);}}
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void historicalPinsRespectKnowledgeAndRemainSeparateFromCurrentBorders(GameTestHelper h){try(var f=new PersonalityGameTests.Villages(h)){
        var v=f.add("norman/artisans");var level=h.getLevel();var player=new Stage5Support.TestPlayer(level,"Historical cartographer");player.setPos(0,64,0);var realm=UUID.randomUUID();var e=HistoryEvent.create(HistoryEvent.Kind.VILLAGE_CONQUEST,81,1944000,level.dimension().location().toString(),UUID.randomUUID().toString(),List.of(new HistoryEvent.Realm(realm,"Old realm","millenaire:norman","ARTISAN")),List.of(new HistoryEvent.Place(v.getId().uuid(),"Historical village",true,v.getCenter().getX()-200,v.getCenter().getZ()-200)),null,"","","RECORDED",Map.of());HistoryHooks.store(level).append(e);
        var hidden=AtlasViews.build(player,request("ATLAS",null,null,e.id(),v.getId().uuid()));h.assertTrue(!hidden.contains("historicalPin"),"Knowing an event ID does not reveal undiscovered historical coordinates");
        level.getChunkAt(v.getCenter());player.setPos(v.getCenter().getX(),64,v.getCenter().getZ());var known=AtlasViews.build(player,request("ATLAS",null,null,e.id(),v.getId().uuid()));var pin=known.getCompound("historicalPin");var current=known.getList("settlements",10).getCompound(0);
        h.assertTrue(pin.getLong("day")==81 && pin.getInt("x")==v.getCenter().getX()-200 && current.getInt("x")==v.getCenter().getX() && !current.hasUUID("kingdom"),"Dated old event location and actual present independent settlement are distinct data");h.succeed();}}
    @GameTest(templateNamespace=NS,template="empty",timeoutTicks=1200)
    public static void treasuryInspectionDoesNotLoadAnUnknownConnectedChestHalf(GameTestHelper h)throws Exception{try(var f=new PersonalityGameTests.Villages(h)){
        var village=f.add("norman/militaire");var level=h.getLevel();var position=village.getCenter().offset(15,0,0);level.getChunkAt(position);
        var left=net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState().setValue(net.minecraft.world.level.block.ChestBlock.FACING,net.minecraft.core.Direction.NORTH).setValue(net.minecraft.world.level.block.ChestBlock.TYPE,net.minecraft.world.level.block.state.properties.ChestType.LEFT);level.setBlock(position,left,18);var other=position.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(left));
        var inventory=org.millenaire.building.BuildingInstance.class.getDeclaredField("inventory");inventory.setAccessible(true);inventory.set(village.getTownhall(),new org.millenaire.building.BuildingInventory(List.of(position)));var kingdom=f.kingdom(village);var player=new Stage5Support.TestPlayer(level,"Chest edge viewer");KingdomSavedData.get(level).claimRule(kingdom.id(),player.getUUID(),"Chest edge viewer",level.getGameTime());
        h.assertTrue(level.isLoaded(position) && !level.isLoaded(other),"A declared half is loaded while the adjacent chunk remains genuinely unknown");var view=AtlasViews.build(player,request("INFO",kingdom.id(),null,null,null)).getCompound("info");
        System.out.println("ATLAS_TREASURY_EDGE otherLoaded="+level.isLoaded(other)+" treasuryLoaded="+view.getBoolean("storageLoaded"));h.assertTrue(!level.isLoaded(other) && !view.getBoolean("storageLoaded") && !view.contains("treasury"),"Read-only atlas must defer incomplete double storage without native container resolution loading another chunk");
        level.getChunkAt(other);level.setBlock(other,left.setValue(net.minecraft.world.level.block.ChestBlock.TYPE,net.minecraft.world.level.block.state.properties.ChestType.RIGHT),18);
        ((net.minecraft.world.level.block.entity.ChestBlockEntity)level.getBlockEntity(position)).setItem(0,new net.minecraft.world.item.ItemStack(org.millenaire.item.ModItems.DENIER.get(),9));
        var loaded=AtlasViews.build(player,request("INFO",kingdom.id(),null,null,null)).getCompound("info");h.assertTrue(loaded.getBoolean("storageLoaded") && loaded.getLong("treasury")==9,"Once both halves are explicitly loaded, native scan still reports the real coins");h.succeed();}}
}
