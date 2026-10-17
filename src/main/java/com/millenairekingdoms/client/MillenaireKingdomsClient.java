package com.millenairekingdoms.client;

import com.millenairekingdoms.MillenaireKingdoms;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

/** Client-only entry point: FML constructs it only on a physical client, so a dedicated server never loads these classes. */
@Mod(value = MillenaireKingdoms.MOD_ID, dist = Dist.CLIENT)
public final class MillenaireKingdomsClient {
    public MillenaireKingdomsClient(IEventBus modEventBus) {
        modEventBus.addListener(KingdomKeys::register);
        NeoForge.EVENT_BUS.addListener(KingdomKeys::onClientTick);
    }
}
