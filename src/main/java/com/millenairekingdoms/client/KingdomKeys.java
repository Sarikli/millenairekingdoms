package com.millenairekingdoms.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.slf4j.Logger;

/**
 * The rebindable "open kingdom management" key (default K). A press sends the player's own `/kingdom gui` command, so
 * the server runs exactly the command's checks and opens the screen with the usual snapshot; nothing new on the server.
 * Only in game (no chat, inventory or other screen open), and one request per press: holding the key (key repeat) waits
 * for its release.
 */
public final class KingdomKeys {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final KeyMapping.Category CATEGORY =
            new KeyMapping.Category(net.minecraft.resources.Identifier.fromNamespaceAndPath("millenairekingdoms", "main"));
    static final String COMMAND = "kingdom gui";
    public static final KeyMapping OPEN = new KeyMapping("key.millenairekingdoms.open_kingdom", InputConstants.KEY_K, CATEGORY);
    /** A press was used; set until the bound key is physically released. */
    private static boolean held;

    private KingdomKeys() {}

    public static void register(RegisterKeyMappingsEvent event) {
        event.registerCategory(CATEGORY);
        event.register(OPEN);
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean pressed = false;
        while (OPEN.consumeClick())
            pressed = true;
        if (held)
            held = physicallyDown(minecraft);
        if (!pressed || held)
            return;
        held = true;
        if (minecraft.gui.screen() != null || minecraft.player == null || false)
            return;
        ClientPacketListener connection = minecraft.getConnection();
        // The server sends only the commands this player may run; without it (no addon on the server) nothing is sent.
        if (connection == null || !commandAvailable(connection))
            return;
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
        if (key.equals(InputConstants.UNKNOWN))
            return false;
        // portage 26.3 : plus de GLFW (SDL) ; l'état du clavier se lit sans poignée de fenêtre
        return key.getType() == InputConstants.Type.KEYBOARD ? InputConstants.isKeyDown(key.getValue()) : OPEN.isDown();
    }
}
