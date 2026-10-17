package com.millenairekingdoms.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

/** The rebindable "open kingdom management" key (default K). A press sends the player's own `/kingdom gui` command, so
 * the server runs exactly the command's checks and opens the screen with the usual snapshot; nothing new on the server.
 * Only in game (no chat, inventory or other screen open), and one request per press: holding the key (key repeat) waits
 * for its release. */
public final class KingdomKeys {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String CATEGORY = "key.categories.millenairekingdoms";
    static final String COMMAND = "kingdom gui";
    public static final KeyMapping OPEN = new KeyMapping("key.millenairekingdoms.open_kingdom", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, CATEGORY);
    /** A press was used; set until the bound key is physically released. */
    private static boolean held;

    private KingdomKeys() {}

    public static void register(RegisterKeyMappingsEvent event) { event.register(OPEN); }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean pressed = false;
        while (OPEN.consumeClick()) pressed = true;
        if (held) held = physicallyDown(minecraft);
        if (!pressed || held) return;
        held = true;
        if (minecraft.screen != null || minecraft.player == null || minecraft.getOverlay() != null) return;
        ClientPacketListener connection = minecraft.getConnection();
        // The server sends only the commands this player may run; without it (no addon on the server) nothing is sent.
        if (connection == null || !commandAvailable(connection)) return;
        connection.sendCommand(COMMAND);
        LOGGER.debug("[Kingdoms] Kingdom management key: sent /{}.", COMMAND);
    }

    private static boolean commandAvailable(ClientPacketListener connection) {
        var kingdom = connection.getCommands().getRoot().getChild("kingdom");
        return kingdom != null && kingdom.getChild("gui") != null;
    }

    /** Opening a screen clears KeyMapping's own down state, so the release is read from the device itself. */
    private static boolean physicallyDown(Minecraft minecraft) {
        InputConstants.Key key = OPEN.getKey();
        if (key.equals(InputConstants.UNKNOWN)) return false;
        long window = minecraft.getWindow().getWindow();
        return switch (key.getType()) {
            case KEYSYM -> InputConstants.isKeyDown(window, key.getValue());
            case MOUSE -> GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS;
            case SCANCODE -> OPEN.isDown();
        };
    }
}
