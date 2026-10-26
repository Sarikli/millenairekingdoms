package com.millenairekingdoms.test;

import com.millenairekingdoms.autonomy.AutonomyConfig;
import com.millenairekingdoms.capital.FoundingConfig;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

@Mod("millenairekingdoms_tests")
public final class ConquestTestMod {
    public ConquestTestMod(IEventBus bus) {
        bus.addListener((RegisterGameTestsEvent event) -> {
            event.register(ConquestGameTests.class);
            event.register(HistoryGameTests.class);
            event.register(HistoryCommitGameTests.class);
            event.register(HistoryWorldLiveGameTests.class);
            event.register(HistoryCrashGameTests.class);
            event.register(NarrativeGameTests.class);
            event.register(LocalizationGameTests.class);
            event.register(GeographyDisabledGameTests.class);
            event.register(ChronicleViewGameTests.class);
            event.register(AtlasGameTests.class);
            event.register(AtlasViewGameTests.class);
            event.register(AtlasSyncGameTests.class);
            event.register(ChronicleIntegrationGameTests.class);
            event.register(KingdomGameTests.class);
            event.register(WarGameTests.class);
            event.register(CapitalGameTests.class);
            event.register(RulerGameTests.class);
            event.register(LedgerGameTests.class);
            event.register(PoliticsGameTests.class);
            event.register(EconomyGameTests.class);
            event.register(DiplomacyGameTests.class);
            event.register(NaturalCapitalGameTests.class);
            event.register(AutonomyGameTests.class);
            event.register(MaintenanceGameTests.class);
            event.register(MaintenanceDataGameTests.class);
            event.register(TreasuryGameTests.class);
            event.register(GuiGameTests.class);
            event.register(FoundingGameTests.class);
            event.register(RoyalCoinGameTests.class);
            event.register(RoyalJournalGameTests.class);
            event.register(RoyalSnapshotGameTests.class);
            event.register(RoyalPaymentGameTests.class);
            event.register(RoyalCrashGameTests.class);
            event.register(RoyalPriceGameTests.class);
            event.register(RoyalCatalogGameTests.class);
            event.register(RoyalOrderStoreGameTests.class);
            event.register(RoyalVillagePersistenceGameTests.class);
            event.register(RoyalRequestGameTests.class);
            event.register(RoyalCancellationGameTests.class);
            event.register(RoyalGovernanceGameTests.class);
            event.register(RoyalRealConstructionGameTests.class);
            event.register(RoyalMonitorGameTests.class);
            event.register(RoyalRuntimeGameTests.class);
            event.register(RoyalOrderCrashGameTests.class);
            event.register(RoyalBuildingCrashGameTests.class);
            event.register(RoyalRecoveryGameTests.class);
            event.register(RoyalSiteGameTests.class);
            event.register(RoyalLiveRuntimeGameTests.class);
            event.register(RoyalWaitingLiveGameTests.class);
        event.register(RoyalUpgradeGameTests.class);
        event.register(RoyalUpgradeLiveGameTests.class);
        event.register(CapitalProductionGameTests.class);
            event.register(RoyalConstructionGuiGameTests.class);
            event.register(RoyalGoodsGameTests.class);
            event.register(RoyalStockGameTests.class);
            event.register(RoyalReceiptGameTests.class);
            event.register(RoyalShipmentGameTests.class);
            event.register(RoyalCargoGameTests.class);
            event.register(RoyalReservationGameTests.class);
            event.register(RoyalCargoCrashGameTests.class);
            event.register(RoyalIssuedShipmentGameTests.class);
            event.register(RoyalShipmentServiceGameTests.class);
            event.register(RoyalShipmentCrashGameTests.class);
            event.register(RoyalShipmentRuntimeGameTests.class);
            event.register(RoyalShipmentLiveGameTests.class);
            event.register(RoyalShipmentGuiGameTests.class);
            event.register(PersonalityGameTests.class);
            event.register(RoyalPolicyGameTests.class);
            event.register(RoyalNpcBudgetGameTests.class);
            event.register(RoyalNpcEconomyGameTests.class);
            event.register(RoyalNpcRuntimeGameTests.class);
            event.register(RoyalStrategyGameTests.class);
            event.register(RoyalNpcLiveGameTests.class);
            event.register(RoyalNpcBuilderLiveGameTests.class);
            event.register(RoyalIntegrationGameTests.class);
            event.register(RoyalSimulationGameTests.class);
        });
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOW, false, net.neoforged.neoforge.event.tick.LevelTickEvent.Post.class, event -> {
            if(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level && Boolean.getBoolean("kingdoms.tests.liveShipments"))RoyalShipmentLiveGameTests.fixtureTick(level);
        });
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGHEST, false, net.neoforged.neoforge.event.tick.LevelTickEvent.Post.class, event -> {
            if(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level && Boolean.getBoolean("kingdoms.tests.liveNpcEconomy"))RoyalNpcLiveGameTests.fixtureTick(level);
        });
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOW, false, net.neoforged.neoforge.event.tick.LevelTickEvent.Post.class, event -> {
            if(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level && Boolean.getBoolean("kingdoms.tests.liveNpcEconomy"))RoyalNpcLiveGameTests.fixtureTick(level);
        });
        // Tests share one world: the scheduled NPC politics and founding must not decide for other tests' villages. The
        // stage-6.2 and 1.1.0 tests call the same code directly, with explicit settings.
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> {
            AutonomyConfig.ENABLED.set(Boolean.getBoolean("kingdoms.tests.liveNpcEconomy") || Boolean.getBoolean("kingdoms.tests.historyWorld"));
            FoundingConfig.ENABLED.set(false);
            boolean liveConstruction=Boolean.getBoolean("kingdoms.tests.liveConstruction");
            com.millenairekingdoms.construction.RoyalConstructionConfig.TRACKING.set(liveConstruction);
            com.millenairekingdoms.shipping.RoyalShipmentConfig.TRACKING.set(Boolean.getBoolean("kingdoms.tests.liveShipments"));
            System.out.println("KINGDOMS_TESTS: scheduled NPC autonomy="+AutonomyConfig.current().autonomy()+", founding disabled for the isolated test world");
            System.out.println("KINGDOMS_TESTS: scheduled existing construction tracking="+liveConstruction);
        });
    }
}
