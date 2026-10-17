package com.millenairekingdoms.test;

import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.conquest.ConquestSavedData;
import com.millenairekingdoms.conquest.ConquestService;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.millenaire.building.BuildingId;
import org.millenaire.building.BuildingInstance;
import org.millenaire.building.BuildingInventory;
import org.millenaire.combat.raid.RaidManager;
import org.millenaire.commerce.TradeGoodsLoader;
import org.millenaire.culture.ModCultures;
import org.millenaire.village.Village;
import org.millenaire.village.VillageId;
import org.millenaire.village.VillageSavedData;
import org.millenaire.village.VillagerRecord;

/** Exercises transformed Millénaire methods with real records, chests, registries and server data.
 * Combat outcomes are made deterministic with zero defenders or killed raider records;
 * these are not tests of NPC pathfinding or a full naturally played battle.
 */
@PrefixGameTestTemplate(false)
public final class ConquestGameTests {
    private static final String NS = "millenairekingdoms_tests";

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void normalRaidStillLootsAndCleansUp(GameTestHelper helper) throws Exception {
        String origin = ConquestService.class.getProtectionDomain().getCodeSource().getLocation().toString();
        helper.assertTrue(origin.contains("/mods/millenairekingdoms.jar"), "Tests must exercise the packaged addon JAR, not source output");
        System.out.println("KINGDOMS_TEST_ARTIFACT: " + origin);
        var f = fixture(helper);
        try {
            normalStart(f);
            invoke("endRaid", f);
            helper.assertTrue(f.sourceChest.getItem(0).getCount() > 0, "Normal raid must actually transfer goods");
            helper.assertTrue(f.targetChest.countItem(f.item) < 64, "Normal target chest must lose goods");
            helper.assertTrue(data(f).wins(f.campaign) == 0, "Normal raids must not award conquest progress");
            assertCleanup(helper, f, "success;");
            helper.succeed();
        } finally { remove(f); }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void conquestTwoWinsNoLootNoOwnershipChange(GameTestHelper helper) throws Exception {
        var f = fixture(helper);
        UUID owner = UUID.randomUUID();
        f.target.setOwner(owner, "Test owner");
        try {
            for (int expected = 1; expected <= 2; expected++) {
                ConquestService.start(f.level, f.attacker.getId().uuid(), f.target.getId().uuid(), null);
                if (expected == 2) {
                    CompoundTag before = VillageSavedData.get(f.level).save(new CompoundTag(), f.level.registryAccess()).copy();
                    invoke("applyLooting", f); // Check only the political transaction, before normal raid cleanup.
                    helper.assertTrue(before.equals(VillageSavedData.get(f.level).save(new CompoundTag(), f.level.registryAccess())),
                            "Political transfer must not change any serialized Millenaire data");
                }
                invoke("endRaid", f);
                helper.assertTrue(data(f).owner(f.target.getId().uuid()).equals(expected == 1 ? f.kingdomB : f.kingdomA),
                        "First win keeps allegiance; second win transfers to attacking kingdom");
                helper.assertTrue(data(f).wins(f.campaign) == expected, "Exactly one point per conquest win");
                // Millénaire may put its negation wand in an unlocked chest; measure the loot good itself.
                helper.assertTrue(f.targetChest.countItem(f.item) == 64 && f.sourceChest.countItem(f.item) == 0, "Conquest must transfer no goods");
                helper.assertTrue(data(f).lastResult(f.target.getId().uuid()).outcome() == KingdomSavedData.Outcome.WON, "Victory result");
                assertCleanup(helper, f, "success;");
            }
            helper.assertTrue(owner.equals(f.target.getOwnerUUID()) && f.target.getParentVillageId() == null, "Ownership and parent must stay unchanged");
            expectRejected(helper, () -> ConquestService.start(f.level, f.attacker.getId().uuid(), f.target.getId().uuid(), null));
            helper.succeed();
        } finally { remove(f); }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void failedConquestHasNoProgressAndCleansUp(GameTestHelper helper) throws Exception {
        var f = fixture(helper);
        try {
            ConquestService.start(f.level, f.attacker.getId().uuid(), f.target.getId().uuid(), null);
            f.target.getVillagerRecords().values().forEach(r -> r.setKilled(true));
            // Real battle-status path: no living attackers -> real endRaid -> failure and cleanup.
            Method check = RaidManager.class.getDeclaredMethod("checkBattleStatus", Village.class, ServerLevel.class);
            check.setAccessible(true);
            check.invoke(null, f.target, f.level);
            helper.assertTrue(data(f).wins(f.campaign) == 0, "Defeat must not award progress");
            helper.assertTrue(data(f).lastResult(f.target.getId().uuid()).outcome() == KingdomSavedData.Outcome.LOST, "Failure result retained");
            helper.assertTrue(f.targetChest.countItem(f.item) == 64 && f.sourceChest.countItem(f.item) == 0, "Defeat must not loot");
            assertCleanup(helper, f, "failure;");
            helper.succeed();
        } finally { remove(f); }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void duplicateSuccessSurvivesSerialization(GameTestHelper helper) throws Exception {
        var f = fixture(helper);
        try {
            ConquestService.start(f.level, f.attacker.getId().uuid(), f.target.getId().uuid(), null);
            helper.assertTrue(((Map<?, ?>) invoke("applyLooting", f)).isEmpty(), "First success suppresses loot");
            CompoundTag saved = data(f).save(new CompoundTag(), f.level.registryAccess());
            f.level.getDataStorage().set(KingdomSavedData.NAME, KingdomSavedData.load(saved, f.level.registryAccess()));
            helper.assertTrue(((Map<?, ?>) invoke("applyLooting", f)).isEmpty(), "Repeated success still suppresses loot after reload");
            helper.assertTrue(data(f).wins(f.campaign) == 1, "Serialized result marker prevents double counting");
            invoke("endRaid", f);
            helper.assertTrue(data(f).wins(f.campaign) == 1, "endRaid must not count the same win again");
            helper.assertTrue(f.targetChest.countItem(f.item) == 64 && f.sourceChest.countItem(f.item) == 0, "Duplicate callbacks never loot");
            helper.succeed();
        } finally { remove(f); }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 400)
    public static void progressAndActiveMarkerPersistOnDisk(GameTestHelper helper) throws Exception {
        var f = fixture(helper);
        ConquestService.start(f.level, f.attacker.getId().uuid(), f.target.getId().uuid(), null);
        invoke("applyLooting", f);
        var expected = data(f).raid(f.target.getId().uuid());
        Field folder = DimensionDataStorage.class.getDeclaredField("dataFolder");
        folder.setAccessible(true);
        Path path = ((java.io.File) folder.get(f.level.getDataStorage())).toPath();
        f.level.getDataStorage().save();
        // NeoForge writes SavedData on its IO worker. Retry until the file is visible.
        helper.succeedWhen(() -> {
            var storage = new DimensionDataStorage(path.toFile(), f.level.getServer().getFixerUpper(), f.level.registryAccess());
            var loaded = storage.get(KingdomSavedData.factory(), KingdomSavedData.NAME);
            helper.assertTrue(loaded != null && loaded.wins(f.campaign) == 1, "World .dat must retain progress");
            helper.assertTrue(expected.equals(loaded.raid(f.target.getId().uuid())), "World .dat must retain raid identity and processed flag");
            helper.assertTrue(!loaded.recordVictory(expected), "Disk reload must not double-count the same raid");
            data(f).finish(expected, KingdomSavedData.Outcome.LOST);
            remove(f);
        });
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void staleMarkerDoesNotCaptureNormalRaid(GameTestHelper helper) throws Exception {
        var f = fixture(helper);
        try {
            ConquestService.start(f.level, f.attacker.getId().uuid(), f.target.getId().uuid(), null);
            // Same villages but a different start tick must not be classified as this conquest.
            f.attacker.setRaidStartGameTime(f.attacker.getRaidStartGameTime() + 1);
            invoke("endRaid", f);
            helper.assertTrue(f.targetChest.countItem(f.item) < 64, "Different raid identity must retain normal loot");
            helper.assertTrue(data(f).wins(f.campaign) == 0, "Stale marker must never earn progress");
            ConquestService.reconcile(f.level);
            helper.assertTrue(data(f).raid(f.target.getId().uuid()) == null, "Stale marker must retire");
            helper.succeed();
        } finally { remove(f); }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void invalidAndConcurrentStartsAreRejected(GameTestHelper helper) throws Exception {
        var f = fixture(helper);
        try {
            expectRejected(helper, () -> ConquestService.start(f.level, f.attacker.getId().uuid(), f.attacker.getId().uuid(), null));
            expectRejected(helper, () -> ConquestService.start(f.level, UUID.randomUUID(), f.target.getId().uuid(), null));
            f.raider.setHireState(UUID.randomUUID(), f.level.getGameTime() + 1000);
            expectRejected(helper, () -> ConquestService.start(f.level, f.attacker.getId().uuid(), f.target.getId().uuid(), null));
            f.raider.setHireState(null, 0);
            f.target.setParentVillageId(f.attacker.getId());
            expectRejected(helper, () -> ConquestService.start(f.level, f.attacker.getId().uuid(), f.target.getId().uuid(), null));
            f.target.setParentVillageId(null);
            f.target.setRaidTarget(VillageId.random());
            expectRejected(helper, () -> ConquestService.start(f.level, f.attacker.getId().uuid(), f.target.getId().uuid(), null));
            f.target.clearRaid();
            ConquestService.start(f.level, f.attacker.getId().uuid(), f.target.getId().uuid(), null);
            expectRejected(helper, () -> ConquestService.start(f.level, f.attacker.getId().uuid(), f.target.getId().uuid(), null));
            helper.assertTrue(data(f).activeRaids().stream().filter(r -> r.target().equals(f.target.getId().uuid())).count() == 1,
                    "One active conquest per target");
            invoke("endRaid", f);
            helper.succeed();
        } finally { remove(f); }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void commandsRequireOperatorAndAcceptRealUuids(GameTestHelper helper) throws Exception {
        var f = fixture(helper, Politics.NO_WAR);
        try {
            var dispatcher = f.level.getServer().getCommands().getDispatcher();
            var source = f.level.getServer().createCommandSourceStack().withLevel(f.level);
            var root = dispatcher.getRoot().getChild("kingdoms");
            helper.assertTrue(!root.canUse(source.withPermission(0)), "Non-operator must be denied");
            helper.assertTrue(root.canUse(source.withPermission(2)), "Operator level 2 must be allowed");
            helper.assertTrue(dispatcher.execute("kingdoms test villages", source) == 1, "Village listing must work");
            for (String cmd : List.of("kingdoms list", "kingdoms info " + f.kingdomA,
                    "kingdoms village " + f.target.getId().uuid(), "kingdoms active " + f.kingdomA + " false",
                    "kingdoms active " + f.kingdomA + " true"))
                helper.assertTrue(dispatcher.execute(cmd, source) == 1, "Operator command works: " + cmd);
            helper.assertTrue(dispatcher.execute("kingdoms attach " + f.kingdomA + " " + f.target.getId().uuid(), source) == 0,
                    "Attachment command rejects an already assigned village");
            String conquest = "kingdoms test conquest " + f.attacker.getId().uuid() + " " + f.target.getId().uuid();
            helper.assertTrue(dispatcher.execute(conquest, source) == 0, "Command refuses a kingdom village without war");
            helper.assertTrue(dispatcher.execute("kingdoms war declare " + f.kingdomA + " " + f.kingdomB, source) == 1, "War command");
            helper.assertTrue(dispatcher.execute(conquest, source) == 1, "Command must accept plain VillageId UUIDs and start a real raid");
            invoke("endRaid", f);
            helper.assertTrue(dispatcher.execute("kingdoms test status " + f.target.getId().uuid(), source) == 1, "Status command must work");
            helper.succeed();
        } finally { remove(f); }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void kingdomVillageNeedsAttackerSideOfActiveWar(GameTestHelper helper) throws Exception {
        var f = fixture(helper, Politics.NO_WAR);
        try {
            var data = data(f);
            CompoundTag villages = millenaire(f), kingdoms = data.save(new CompoundTag(), f.level.registryAccess());
            expectRejected(helper, () -> ConquestService.start(f.level, f.attacker.getId().uuid(), f.target.getId().uuid(), null));
            helper.assertTrue(villages.equals(millenaire(f)) && kingdoms.equals(data.save(new CompoundTag(), f.level.registryAccess())),
                    "Rejected start without war has no Millenaire or political side effect");
            var reverse = data.declareWar(f.kingdomB, f.kingdomA, f.level.getGameTime());
            villages = millenaire(f);
            expectRejected(helper, () -> ConquestService.start(f.level, f.attacker.getId().uuid(), f.target.getId().uuid(), null));
            helper.assertTrue(villages.equals(millenaire(f)) && f.attacker.getRaidTarget() == null && !f.target.isUnderAttack(),
                    "Defending kingdom cannot conquer the war attacker's village");
            data.peace(reverse.id());
            var war = data.declareWar(f.kingdomA, f.kingdomB, f.level.getGameTime());
            var raid = ConquestService.start(f.level, f.attacker.getId().uuid(), f.target.getId().uuid(), null);
            helper.assertTrue(war.id().equals(raid.campaign().warId()), "Attacking side starts a war-bound raid");
            expectRejected(helper, () -> data.peace(war.id()));
            invoke("endRaid", f);
            helper.assertTrue(data.wins(raid.campaign()) == 1 && data.owner(f.target.getId().uuid()).equals(f.kingdomB), "1/2 after first win");
            assertCleanup(helper, f, "success;");
            helper.succeed();
        } finally { remove(f); }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void independentVillageConqueredByOneRealRaid(GameTestHelper helper) throws Exception {
        var f = fixture(helper, Politics.INDEPENDENT);
        UUID owner = UUID.randomUUID();
        f.target.setOwner(owner, "Test owner");
        try {
            helper.assertTrue(data(f).activeWar(f.kingdomA) == null && f.campaign.warId() == null, "No war is needed");
            ConquestService.start(f.level, f.attacker.getId().uuid(), f.target.getId().uuid(), null);
            CompoundTag before = millenaire(f);
            helper.assertTrue(((Map<?, ?>) invoke("applyLooting", f)).isEmpty(), "Conquest success suppresses loot");
            helper.assertTrue(before.equals(millenaire(f)), "Political transfer must not change any serialized Millenaire data");
            invoke("endRaid", f);
            var result = data(f).lastResult(f.target.getId().uuid());
            helper.assertTrue(data(f).owner(f.target.getId().uuid()).equals(f.kingdomA) && result.transferred()
                    && data(f).wins(f.campaign) == 1, "One real victory conquers an independent village");
            helper.assertTrue(f.targetChest.countItem(f.item) == 64 && f.sourceChest.countItem(f.item) == 0, "Conquest must transfer no goods");
            helper.assertTrue(owner.equals(f.target.getOwnerUUID()) && f.target.getParentVillageId() == null, "Ownership and parent must stay unchanged");
            assertCleanup(helper, f, "success;");
            expectRejected(helper, () -> ConquestService.start(f.level, f.attacker.getId().uuid(), f.target.getId().uuid(), null));
            helper.succeed();
        } finally { remove(f); }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void failedIndependentRaidKeepsVillageUnassigned(GameTestHelper helper) throws Exception {
        var f = fixture(helper, Politics.INDEPENDENT);
        try {
            ConquestService.start(f.level, f.attacker.getId().uuid(), f.target.getId().uuid(), null);
            f.target.getVillagerRecords().values().forEach(r -> r.setKilled(true));
            Method check = RaidManager.class.getDeclaredMethod("checkBattleStatus", Village.class, ServerLevel.class);
            check.setAccessible(true);
            check.invoke(null, f.target, f.level);
            helper.assertTrue(data(f).owner(f.target.getId().uuid()) == null && data(f).wins(f.campaign) == 0
                    && data(f).lastResult(f.target.getId().uuid()).outcome() == KingdomSavedData.Outcome.LOST, "Failure transfers nothing");
            helper.assertTrue(f.targetChest.countItem(f.item) == 64 && f.sourceChest.countItem(f.item) == 0, "Defeat must not loot");
            assertCleanup(helper, f, "failure;");
            helper.succeed();
        } finally { remove(f); }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void capitalFallsAfterSixtyPercentAndTwoRealRaids(GameTestHelper helper) throws Exception {
        var f = fixture(helper); // B's only non-capital village is the target: 1 of 1 must be held.
        UUID owner = UUID.randomUUID();
        f.capital.setOwner(owner, "Capital owner");
        UUID attacker = f.attacker.getId().uuid(), target = f.target.getId().uuid(), capital = f.capital.getId().uuid();
        try {
            var data = data(f); var war = data.activeWar(f.kingdomA);
            // Stage 5.1A: political rulers live only in kingdom data; the defender's ruler is also its capital's Millénaire owner.
            CompoundTag beforeRulers = millenaire(f); UUID victorRuler = UUID.randomUUID();
            data.claimRule(f.kingdomA, victorRuler, "Victor", f.level.getGameTime());
            data.claimRule(f.kingdomB, owner, "Capital owner", f.level.getGameTime());
            helper.assertTrue(beforeRulers.equals(millenaire(f)) && owner.equals(f.capital.getOwnerUUID()), "13. Claiming rule changes no Millénaire data");
            CompoundTag villages = millenaire(f), kingdoms = data.save(new CompoundTag(), f.level.registryAccess());
            expectRejected(helper, () -> ConquestService.start(f.level, attacker, capital, null));
            helper.assertTrue(villages.equals(millenaire(f)) && kingdoms.equals(data.save(new CompoundTag(), f.level.registryAccess()))
                    && f.attacker.getRaidTarget() == null && !f.capital.isUnderAttack(), "1/18. Capital below 60% is refused with no side effect");
            for (int i = 0; i < 2; i++) { ConquestService.start(f.level, attacker, target, null); invoke("endRaid", f); }
            helper.assertTrue(f.kingdomA.equals(data.owner(target)) && data.capitalOpen(war), "2. Holding 1 of 1 opens the capital");
            for (int expected = 1; expected <= 2; expected++) {
                var raid = ConquestService.start(f.level, attacker, capital, null);
                helper.assertTrue(war.id().equals(raid.campaign().warId()), "Capital raid is bound to the active war");
                CompoundTag before = millenaire(f);
                helper.assertTrue(((Map<?, ?>) invoke("applyLooting", f, f.capital)).isEmpty(), "14. Capital conquest suppresses loot");
                helper.assertTrue(before.equals(millenaire(f)), "12. Political result changes no serialized Millénaire data");
                invoke("endRaid", f, f.capital);
                helper.assertTrue(data.capitalWins(war) == expected, "Exactly one capital point per win");
                helper.assertTrue(f.capitalChest.countItem(f.item) == 64 && f.sourceChest.countItem(f.item) == 0, "14. No goods moved");
                assertCleanup(helper, f, f.capital, "success;");
                if (expected == 1) helper.assertTrue(f.kingdomB.equals(data.owner(capital)) && data.kingdom(f.kingdomB).active()
                        && data.war(war.id()).status() == KingdomSavedData.WarStatus.ACTIVE, "7. Capital holds at 1/2");
            }
            var fallen = data.kingdom(f.kingdomB);
            helper.assertTrue(fallen.fallen() && data.members(f.kingdomB).isEmpty() && f.kingdomA.equals(data.owner(capital))
                    && data.war(war.id()).status() == KingdomSavedData.WarStatus.CONQUERED, "8/9. Capital fell and kingdom B collapsed");
            helper.assertTrue(data.kingdom(f.kingdomA).capital().equals(attacker) && data.isCapital(attacker) && !data.isCapital(capital),
                    "11. Victor keeps its capital; the old capital is a normal village");
            helper.assertTrue(owner.equals(f.capital.getOwnerUUID()) && f.capital.getParentVillageId() == null
                    && "Test capital".equals(f.capital.getVillageName()), "12. Millénaire owner, parent and name unchanged");
            helper.assertTrue(data.ruler(f.kingdomB) == null && data.ruledBy(owner) == null && victorRuler.equals(data.ruler(f.kingdomA).player()),
                    "5.1A 8/9. Collapse removed the defender's ruler and kept the victor's");
            expectRejected(helper, () -> ConquestService.start(f.level, attacker, capital, null));
            data.clearRuler(f.kingdomA); // Frees the shared world data for later tests.
            helper.succeed();
        } finally { remove(f); }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void zeroMemberCapitalFallsToTwoRealRaids(GameTestHelper helper) throws Exception {
        var f = fixture(helper, Politics.INDEPENDENT); // Kingdom B has only its capital.
        UUID attacker = f.attacker.getId().uuid(), capital = f.capital.getId().uuid();
        try {
            var data = data(f);
            expectRejected(helper, () -> ConquestService.start(f.level, attacker, capital, null)); // No war.
            var war = data.declareWar(f.kingdomA, f.kingdomB, f.level.getGameTime());
            helper.assertTrue(war.initialDefenderMembers().isEmpty() && data.capitalOpen(war), "3. Zero members: capital open at once");
            for (int i = 0; i < 2; i++) {
                ConquestService.start(f.level, attacker, capital, null);
                invoke("endRaid", f, f.capital);
                assertCleanup(helper, f, f.capital, "success;");
            }
            helper.assertTrue(data.kingdom(f.kingdomB).fallen() && f.kingdomA.equals(data.owner(capital))
                    && data.owner(f.target.getId().uuid()) == null && f.capitalChest.countItem(f.item) == 64, "Capital fell without loot");
            helper.succeed();
        } finally { remove(f); }
    }

    private enum Politics { NONE, INDEPENDENT, NO_WAR, WAR }
    private static CompoundTag millenaire(Fixture f) {
        return VillageSavedData.get(f.level).save(new CompoundTag(), f.level.registryAccess()).copy();
    }

    private record Fixture(ServerLevel level, Village attacker, Village target, VillagerRecord raider,
                           ChestBlockEntity sourceChest, ChestBlockEntity targetChest, Item item, Village capital,
                           ChestBlockEntity capitalChest, UUID kingdomA, UUID kingdomB, KingdomSavedData.Campaign campaign) {}

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void progressSurvivesServerRestart(GameTestHelper helper) {
        var data = KingdomSavedData.get(helper.getLevel());
        UUID target = UUID.fromString("d9a8fabc-198f-410b-9eaa-a49bd1dfe100");
        UUID attacker = UUID.fromString("d9a8fabc-198f-410b-9eaa-a49bd1dfe101");
        UUID capital = UUID.fromString("d9a8fabc-198f-410b-9eaa-a49bd1dfe102");
        UUID partial = UUID.fromString("d9a8fabc-198f-410b-9eaa-a49bd1dfe103");
        UUID independent = UUID.fromString("d9a8fabc-198f-410b-9eaa-a49bd1dfe104");
        if ("read".equals(System.getProperty("kingdoms.tests.persistence"))) {
            helper.assertTrue(data.owner(target).equals(data.owner(attacker)) && !data.owner(target).equals(data.owner(capital))
                    && data.lastResult(target).transferred() && data.wins(data.lastResult(target).campaign()) == 2,
                    "Political transfer, receipt and completed campaign must survive a full server process restart");
            helper.assertTrue(data.wins(data.campaign(attacker, partial)) == 1, "Partial war progress survives restart");
            helper.assertTrue(data.owner(independent).equals(data.owner(attacker)) && data.lastResult(independent).transferred(),
                    "Independent 1/1 transfer survives restart");
            System.out.println("KINGDOMS_RESTART_READ_PASS: war transfer=2/2, war partial=1/2, independent=1/1, capital/membership/receipt retained");
        } else {
            if (data.owner(attacker) == null) {
                var attackers = data.create("Restart attackers", attacker);
                var defenders = data.create("Restart defenders", capital);
                data.attach(defenders.id(), target); data.attach(defenders.id(), partial);
                data.declareWar(attackers.id(), defenders.id(), helper.getLevel().getGameTime());
                for (int i = 0; i < 2; i++) {
                    var raid = data.begin(attacker, target, helper.getLevel().getGameTime(), null);
                    data.recordVictory(raid); data.finish(raid, KingdomSavedData.Outcome.LOST);
                }
                for (UUID village : List.of(partial, independent)) {
                    var raid = data.begin(attacker, village, helper.getLevel().getGameTime(), null);
                    data.recordVictory(raid); data.finish(raid, KingdomSavedData.Outcome.LOST);
                }
            }
            data.setDirty();
            System.out.println("KINGDOMS_RESTART_WRITE: war transfer=2/2, war partial=1/2, independent=1/1; read assertion runs in the next JVM");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void realCloneEntityIsRemovedOnFinish(GameTestHelper helper) throws Exception {
        var f = fixture(helper);
        try {
            ConquestService.start(f.level, f.attacker.getId().uuid(), f.target.getId().uuid(), null);
            var record = f.target.getVillagerRecords().values().iterator().next();
            var entity = org.millenaire.entity.ModEntities.MILL_VILLAGER.get().create(f.level);
            helper.assertTrue(entity != null, "Real Millénaire entity must be constructible");
            entity.setUUID(record.getUuid());
            entity.setVillageId(f.target.getId());
            entity.setVillagerTypeId(record.getVillagerTypeId());
            entity.setRaiderEntity(true);
            entity.moveTo(f.target.getCenter().getX() + 0.5, f.target.getCenter().getY() + 1, f.target.getCenter().getZ() + 0.5);
            helper.assertTrue(f.level.addFreshEntity(entity), "Clone entity must be added to the server world");
            invoke("endRaid", f);
            helper.assertTrue(entity.isRemoved(), "Original Millénaire cleanup must discard the real clone entity");
            assertCleanup(helper, f, "success;");
            helper.succeed();
        } finally { remove(f); }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void kingdomCommandsCreateAttachAndRejectCapitalRaid(GameTestHelper helper) throws Exception {
        var f = fixture(helper, Politics.NONE);
        try {
            var dispatcher = f.level.getServer().getCommands().getDispatcher();
            var source = f.level.getServer().createCommandSourceStack().withLevel(f.level);
            helper.assertTrue(dispatcher.execute("kingdoms create " + f.attacker.getId().uuid() + " Command A " + f.attacker.getId().uuid(), source) == 1,
                    "Create command accepts a real Millenaire capital");
            helper.assertTrue(dispatcher.execute("kingdoms create " + f.capital.getId().uuid() + " Command B " + f.capital.getId().uuid(), source) == 1,
                    "Create second kingdom");
            UUID b = data(f).owner(f.capital.getId().uuid());
            helper.assertTrue(dispatcher.execute("kingdoms attach " + b + " " + f.target.getId().uuid(), source) == 1, "Attach a real village");
            helper.assertTrue(dispatcher.execute("kingdoms war declare " + data(f).owner(f.attacker.getId().uuid()) + " " + b, source) == 1,
                    "Capital stays protected even during an active war");
            helper.assertTrue(dispatcher.execute("kingdoms test conquest " + f.attacker.getId().uuid() + " " + f.capital.getId().uuid(), source) == 0,
                    "Capital raid must be rejected through production command/service");
            helper.assertTrue(f.attacker.getRaidTarget() == null && !f.capital.isUnderAttack(), "Rejected capital raid causes no Millenaire side effects");
            helper.succeed();
        } finally { remove(f); }
    }

    @GameTest(templateNamespace = NS, template = "empty", timeoutTicks = 200)
    public static void legacyActiveRaidStillSuppressesLootAndCleansUp(GameTestHelper helper) throws Exception {
        var f = fixture(helper); var original = data(f);
        try {
            normalStart(f);
            var legacy = new ConquestSavedData();
            legacy.begin(f.attacker.getId().uuid(), f.target.getId().uuid(), f.attacker.getRaidStartGameTime(), null);
            var migrated = KingdomSavedData.fromLegacy(legacy.save(new CompoundTag(), f.level.registryAccess()), f.level.registryAccess());
            f.level.getDataStorage().set(KingdomSavedData.NAME, migrated);
            invoke("endRaid", f);
            helper.assertTrue(f.targetChest.countItem(f.item) == 64 && f.sourceChest.countItem(f.item) == 0, "Legacy active conquest still suppresses real loot");
            helper.assertTrue(migrated.lastResult(f.target.getId().uuid()).outcome() == KingdomSavedData.Outcome.LEGACY_WON
                    && migrated.progress(f.target.getId().uuid()).isEmpty(), "Legacy win produces only an archived result");
            assertCleanup(helper, f, "success;");
            helper.succeed();
        } finally { f.level.getDataStorage().set(KingdomSavedData.NAME, original); remove(f); }
    }

    private static Fixture fixture(GameTestHelper helper) throws Exception { return fixture(helper, Politics.WAR); }
    private static Fixture fixture(GameTestHelper helper, Politics politics) throws Exception {
        ServerLevel level = helper.getLevel();
        // GameTest starts at day zero; production deliberately rejects that sentinel.
        if (level.getDayTime() <= 0) level.setDayTime(1000);
        var type = ModCultures.getAllVillagerTypes().values().stream()
                .filter(t -> t.isRaider() && t.baseAttackStrength() > 0 && !TradeGoodsLoader.getGoods(t.culture()).isEmpty())
                .findFirst().orElseThrow();
        var villageType = ModCultures.getAllVillageTypes().values().stream().filter(v -> !v.loneBuilding()).findFirst().orElseThrow();
        Item item = TradeGoodsLoader.getGoods(type.culture()).stream()
                .filter(g -> g.targetQuantity() > 0 && g.resolveItem() != null).findFirst().orElseThrow().resolveItem();
        BlockPos a = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos b = helper.absolutePos(new BlockPos(5, 1, 1));
        BlockPos c = helper.absolutePos(new BlockPos(5, 1, 5));
        for (BlockPos pos : List.of(a, b, c)) level.setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState());
        var ac = (ChestBlockEntity) level.getBlockEntity(a);
        var bc = (ChestBlockEntity) level.getBlockEntity(b);
        var cc = (ChestBlockEntity) level.getBlockEntity(c);
        bc.setItem(0, new ItemStack(item, 64));
        cc.setItem(0, new ItemStack(item, 64));
        var attacker = new Village(VillageId.random(), type.culture(), villageType.id(), a);
        var target = new Village(VillageId.random(), type.culture(), villageType.id(), b);
        attacker.setVillageName("Test attacker"); target.setVillageName("Test target");
        var capital = new Village(VillageId.random(), type.culture(), villageType.id(), c);
        capital.setVillageName("Test capital");
        for (Village village : List.of(attacker, target, capital)) {
            var building = new BuildingInstance(BuildingId.random(), villageType.id(), village.getCenter(), Rotation.NONE, BuildingInstance.Status.COMPLETE);
            Field inventory = BuildingInstance.class.getDeclaredField("inventory");
            inventory.setAccessible(true);
            inventory.set(building, new BuildingInventory(List.of(village.getCenter())));
            village.addBuilding(building);
            VillageSavedData.get(level).getVillageManager().addVillage(village);
        }
        var raider = new VillagerRecord(UUID.randomUUID(), type.id(), attacker.getTownhall().getId());
        attacker.addVillager(raider);
        if (politics == Politics.NONE) return new Fixture(level, attacker, target, raider, ac, bc, item, capital, cc, null, null, null);
        var data = KingdomSavedData.get(level);
        var kingdomA = data.create("Attackers " + attacker.getId().uuid(), attacker.getId().uuid());
        var kingdomB = data.create("Defenders " + target.getId().uuid(), capital.getId().uuid());
        if (politics != Politics.INDEPENDENT) data.attach(kingdomB.id(), target.getId().uuid());
        if (politics == Politics.WAR) data.declareWar(kingdomA.id(), kingdomB.id(), level.getGameTime());
        return new Fixture(level, attacker, target, raider, ac, bc, item, capital, cc, kingdomA.id(), kingdomB.id(),
                politics == Politics.NO_WAR ? null : data.campaign(attacker.getId().uuid(), target.getId().uuid()));
    }

    private static KingdomSavedData data(Fixture f) { return KingdomSavedData.get(f.level); }
    private static void normalStart(Fixture f) {
        RaidManager.planRaid(f.attacker, f.target, f.level);
        RaidManager.startRaidForced(f.attacker, f.target, f.level);
    }
    private static Object invoke(String name, Fixture f) throws Exception { return invoke(name, f, f.target); }
    private static Object invoke(String name, Fixture f, Village target) throws Exception {
        Method method = RaidManager.class.getDeclaredMethod(name, Village.class, Village.class, ServerLevel.class);
        method.setAccessible(true);
        return method.invoke(null, f.attacker, target, f.level);
    }
    private static void assertCleanup(GameTestHelper helper, Fixture f, String historyPrefix) { assertCleanup(helper, f, f.target, historyPrefix); }
    private static void assertCleanup(GameTestHelper helper, Fixture f, Village target, String historyPrefix) {
        helper.assertTrue(f.attacker.getRaidTarget() == null && f.attacker.getRaidStartGameTime() == 0, "Attacker raid state must clear");
        helper.assertTrue(!target.isUnderAttack() && target.getVillagerRecords().isEmpty(), "Target flag and clone records must clear");
        helper.assertTrue(!f.raider.isAwayRaiding(), "Original NPC must be released");
        helper.assertTrue(f.attacker.getRaidsPerformed().getLast().startsWith(historyPrefix), "Normal history must be retained");
        helper.assertTrue(target.getRaidsSuffered().getLast().startsWith(historyPrefix), "Target history must be retained");
    }
    private static void expectRejected(GameTestHelper helper, Runnable action) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        helper.assertTrue(rejected, "Unsafe or duplicate start must be rejected");
    }
    private static void remove(Fixture f) {
        var manager = VillageSavedData.get(f.level).getVillageManager();
        manager.removeVillage(f.attacker.getId());
        manager.removeVillage(f.target.getId());
        manager.removeVillage(f.capital.getId());
        ConquestService.reconcile(f.level);
    }
}
