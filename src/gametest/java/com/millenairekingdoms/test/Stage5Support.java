package com.millenairekingdoms.test;

import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.millenaire.building.BuildingId;
import org.millenaire.building.BuildingInstance;
import org.millenaire.building.BuildingInventory;
import org.millenaire.culture.ModCultures;
import org.millenaire.village.Village;
import org.millenaire.village.VillageId;
import org.millenaire.village.VillageSavedData;

/** Real Millénaire villages, town halls and chests for the stage-5 tests; players are server-side test players
 * whose commands go through the real dispatcher with permission 0. */
final class Stage5Support {
    static final String NS = "millenairekingdoms_stage5_tests";
    private Stage5Support() {}

    /** A server player that records the chat it receives instead of sending packets. */
    static final class TestPlayer extends FakePlayer {
        final List<Component> messages = new ArrayList<>();
        TestPlayer(ServerLevel level, String name) { super(level, new GameProfile(UUID.randomUUID(), name)); }
        @Override public void sendSystemMessage(Component component, boolean overlay) { messages.add(component); }
        @Override public void sendSystemMessage(Component component) { messages.add(component); }
        String text() { StringBuilder b = new StringBuilder(); messages.forEach(m -> b.append(m.getString()).append('\n')); return b.toString(); }
    }

    /** A real Millénaire village whose town hall is the given 0-based plan level and status, with one chest as its storage. */
    static Village village(GameTestHelper h, int x, int z, String name, int townhallLevel, BuildingInstance.Status status) throws Exception {
        ServerLevel level = h.getLevel();
        if (level.getDayTime() <= 0) level.setDayTime(1000);
        var villageType = ModCultures.getAllVillageTypes().values().stream().filter(v -> !v.loneBuilding()).findFirst().orElseThrow();
        BlockPos center = h.absolutePos(new BlockPos(x, 1, z));
        level.setBlockAndUpdate(center, Blocks.CHEST.defaultBlockState());
        var village = new Village(VillageId.random(), villageType.culture(), villageType.id(), center);
        village.setVillageName(name);
        var townhall = new BuildingInstance(BuildingId.random(), villageType.id(), center, Rotation.NONE, status, null, "a", townhallLevel);
        Field inventory = BuildingInstance.class.getDeclaredField("inventory");
        inventory.setAccessible(true);
        inventory.set(townhall, new BuildingInventory(List.of(center)));
        village.addBuilding(townhall);
        VillageSavedData.get(level).getVillageManager().addVillage(village);
        return village;
    }
    static Village village(GameTestHelper h, int x, int z, String name) throws Exception {
        return village(h, x, z, name, 0, BuildingInstance.Status.COMPLETE);
    }
    static ChestBlockEntity chest(GameTestHelper h, Village village) { return (ChestBlockEntity) h.getLevel().getBlockEntity(village.getCenter()); }
    static void remove(GameTestHelper h, Village... villages) {
        var manager = VillageSavedData.get(h.getLevel()).getVillageManager();
        for (Village v : villages) manager.removeVillage(v.getId());
    }
    static KingdomSavedData.Kingdom kingdom(GameTestHelper h, Village capital) {
        return KingdomSavedData.get(h.getLevel()).create("S5 " + capital.getVillageName() + " " + capital.getId().uuid(), capital.getId().uuid());
    }
    /** Sets Millénaire's combined reputation exactly by adjusting only the village layer, as Millénaire's own add does. */
    static void reputation(GameTestHelper h, Village village, UUID player, int value) {
        village.addReputation(player, value - village.getCombinedReputation(h.getLevel(), player));
    }
    static void relation(Village a, Village b, int value) { a.setRelation(b.getId(), value); b.setRelation(a.getId(), value); }
    static TestPlayer player(GameTestHelper h, String name, Village at) {
        var p = new TestPlayer(h.getLevel(), name);
        moveTo(p, at, 0);
        return p;
    }
    /** Places the player straight above the village centre, so the distance to it is exactly `up` blocks while
     * capitals of concurrently running tests (beside it on the same height) stay farther away. */
    static void moveTo(TestPlayer p, Village at, int up) {
        BlockPos c = at.getCenter(); p.moveTo(c.getX() + 0.5, c.getY() + 0.5 + up, c.getZ() + 0.5);
    }
    /** Runs a command exactly as the player would type it; 1 = accepted, 0 = refused by the rules, -1 = syntax/permission. */
    static int run(TestPlayer p, String command) {
        try { return p.serverLevel().getServer().getCommands().getDispatcher().execute(command, p.createCommandSourceStack()); }
        catch (CommandSyntaxException e) { return -1; }
    }
    static boolean hasButton(List<Component> messages, String command) {
        for (Component m : messages) if (hasButton(m, command)) return true;
        return false;
    }
    private static boolean hasButton(Component c, String command) {
        ClickEvent click = c.getStyle().getClickEvent();
        if (click != null && click.getAction() == ClickEvent.Action.RUN_COMMAND && click.getValue().equals(command)) return true;
        for (Component child : c.getSiblings()) if (hasButton(child, command)) return true;
        return false;
    }
    static CompoundTag millenaire(GameTestHelper h) {
        return VillageSavedData.get(h.getLevel()).save(new CompoundTag(), h.getLevel().registryAccess()).copy();
    }
    static void rejected(GameTestHelper h, Runnable action) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        h.assertTrue(rejected, "Invalid operation must be rejected");
    }
}
