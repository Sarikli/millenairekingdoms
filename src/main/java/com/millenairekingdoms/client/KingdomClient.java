package com.millenairekingdoms.client;

import com.millenairekingdoms.network.KingdomActionPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.network.PacketDistributor;

/** Client-only entry points; loaded only on a physical client when the server sends a screen snapshot. */
public final class KingdomClient {
    private KingdomClient() {}

    public static void show(CompoundTag view) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof KingdomScreen screen) screen.update(view);
        else minecraft.setScreen(new KingdomScreen(view));
    }
    static void send(KingdomActionPayload request) { PacketDistributor.sendToServer(request); }
}
