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
        });
        // Tests share one world: the scheduled NPC politics and founding must not decide for other tests' villages. The
        // stage-6.2 and 1.1.0 tests call the same code directly, with explicit settings.
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> {
            AutonomyConfig.ENABLED.set(false);
            FoundingConfig.ENABLED.set(false);
            com.millenairekingdoms.construction.RoyalConstructionConfig.TRACKING.set(false);
            System.out.println("KINGDOMS_TESTS: scheduled NPC autonomy and kingdom founding disabled for the shared test world");
        });
    }
}
