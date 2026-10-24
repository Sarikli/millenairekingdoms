package com.millenairekingdoms.network;

import com.millenairekingdoms.gui.KingdomActions;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Two payloads, both handled on the main thread (NeoForge's default): the server's screen snapshot and the client's requests. */
public final class KingdomNetwork {
    public static final String VERSION = "3";
    private KingdomNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(VERSION);
        registrar.playToClient(KingdomViewPayload.TYPE, KingdomViewPayload.CODEC, KingdomNetwork::onView);
        registrar.playToServer(KingdomActionPayload.TYPE, KingdomActionPayload.CODEC, KingdomNetwork::onAction);
    }

    /** Runs only on a client; the client class is loaded only then, never on a dedicated server. */
    private static void onView(KingdomViewPayload payload, IPayloadContext context) {
        com.millenairekingdoms.client.KingdomClient.show(payload.view());
    }
    private static void onAction(KingdomActionPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) KingdomActions.handle(player, payload);
    }
}
