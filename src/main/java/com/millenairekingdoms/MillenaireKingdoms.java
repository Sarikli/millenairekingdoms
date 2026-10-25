package com.millenairekingdoms;

import com.mojang.logging.LogUtils;
import com.millenairekingdoms.autonomy.AutonomyConfig;
import com.millenairekingdoms.autonomy.RaidOrderService;
import com.millenairekingdoms.capital.CapitalConfig;
import com.millenairekingdoms.gui.KingdomActions;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.network.KingdomNetwork;
import com.millenairekingdoms.command.KingdomCommands;
import com.millenairekingdoms.command.KingdomsCommands;
import com.millenairekingdoms.conquest.ConquestService;
import com.millenairekingdoms.politics.ChiefInteraction;
import com.millenairekingdoms.politics.KingdomDay;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.millenaire.Millenaire;
import org.slf4j.Logger;

/** Server-authoritative kingdom registry and conquest integration. */
@Mod(MillenaireKingdoms.MOD_ID)
public final class MillenaireKingdoms {
    public static final String MOD_ID = "millenairekingdoms";
    private static final Logger LOGGER = LogUtils.getLogger();

    public MillenaireKingdoms(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, CapitalConfig.SPEC);
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::loadComplete);
        modEventBus.addListener(KingdomNetwork::register);
        NeoForge.EVENT_BUS.addListener(KingdomsCommands::register);
        NeoForge.EVENT_BUS.addListener(KingdomCommands::register);
        NeoForge.EVENT_BUS.addListener(ChiefInteraction::onEntityInteract);
        NeoForge.EVENT_BUS.addListener(ConquestService::onLevelTick);
        NeoForge.EVENT_BUS.addListener(KingdomDay::onLevelTick);
        NeoForge.EVENT_BUS.addListener(RaidOrderService::onLevelTick);
        NeoForge.EVENT_BUS.addListener(KingdomActions::onLogout);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStartedEvent event) -> {
            for (var level : event.getServer().getAllLevels()) try { com.millenairekingdoms.history.HistoryHooks.recover(level); }
            catch (RuntimeException failure) { LOGGER.error("Historical archive recovery unavailable; original files preserved", failure); }
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppedEvent event) -> com.millenairekingdoms.history.HistoryHooks.clear(event.getServer()));
        NeoForge.EVENT_BUS.addListener(com.millenairekingdoms.construction.RoyalConstructionRuntime::onStarted);
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGH, false, net.neoforged.neoforge.event.tick.LevelTickEvent.Post.class, com.millenairekingdoms.construction.RoyalConstructionRuntime::onLevelTick);
        NeoForge.EVENT_BUS.addListener(com.millenairekingdoms.construction.RoyalConstructionRuntime::onStopped);
        NeoForge.EVENT_BUS.addListener(com.millenairekingdoms.shipping.RoyalShipmentRuntime::onStarted);
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST, false, net.neoforged.neoforge.event.tick.LevelTickEvent.Post.class, com.millenairekingdoms.shipping.RoyalShipmentRuntime::onLevelTick);
        NeoForge.EVENT_BUS.addListener(com.millenairekingdoms.shipping.RoyalShipmentRuntime::onStopped);
        NeoForge.EVENT_BUS.addListener(com.millenairekingdoms.autonomy.RoyalNpcRuntime::onStarted);
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGH, false, net.neoforged.neoforge.event.tick.LevelTickEvent.Post.class, com.millenairekingdoms.autonomy.RoyalNpcRuntime::onLevelTick);
        NeoForge.EVENT_BUS.addListener(com.millenairekingdoms.autonomy.RoyalNpcRuntime::onStopped);
        KingdomSavedData.defaultTruceDays = AutonomyConfig::truceDays;
        LOGGER.info("Millénaire Kingdoms {} initialized (modId={}).",
                modContainer.getModInfo().getVersion(), MOD_ID);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        // Run after mod construction; this verified Millénaire API only reads its version.
        event.enqueueWork(() -> LOGGER.info("Millénaire Kingdoms dependency verified: millenaire {}.",
                Millenaire.getModVersion()));
    }

    private void loadComplete(FMLLoadCompleteEvent event) {
        LOGGER.info("Millénaire Kingdoms mod loading complete. Kingdom and capital system enabled.");
    }
}
