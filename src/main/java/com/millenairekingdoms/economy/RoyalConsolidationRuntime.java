package com.millenairekingdoms.economy;

import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.politics.PoliticsService;
import com.mojang.logging.LogUtils;
import java.util.*;
import java.util.function.Function;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Bounded new maintenance and independent recovery; neither grants access to unloaded storage. */
public final class RoyalConsolidationRuntime {
    public record Report(int inspected, int committed, long elapsedNanos) {}
    private static final long RECOVERY_INTERVAL = 200;
    private static final long WARNING_INTERVAL = 1200;
    private static final Map<ServerLevel, Session> SESSIONS = new WeakHashMap<>();

    private static final class Session {
        UUID capitalCursor, recoveryCursor;
        long nextMaintenance, nextRecovery;
        Report last = new Report(0, 0, 0);
        final Map<String, Long> warnings = new HashMap<>();
    }

    private RoyalConsolidationRuntime() {}

    public static void onStarted(ServerStartedEvent event) {
        for (var level : event.getServer().getAllLevels()) {
            var session = SESSIONS.computeIfAbsent(level, ignored -> new Session());
            session.nextRecovery = level.getGameTime() + RECOVERY_INTERVAL;
            session.nextMaintenance = level.getGameTime() + RoyalConsolidationConfig.INTERVAL.get();
        }
    }

    public static void onStopped(ServerStoppedEvent event) {
        SESSIONS.keySet().removeIf(level -> level.getServer() == event.getServer());
    }

    public static void onTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        var session = SESSIONS.computeIfAbsent(level, ignored -> new Session());
        long now = level.getGameTime();
        if (now >= session.nextRecovery) {
            session.nextRecovery = now + RECOVERY_INTERVAL;
            recover(level);
        }
        if (now >= session.nextMaintenance) {
            session.nextMaintenance = now + RoyalConsolidationConfig.INTERVAL.get();
            if (RoyalConsolidationConfig.ENABLED.get()) step(level, RoyalConsolidationConfig.MAX_CAPITALS.get());
        }
    }

    public static Report step(ServerLevel level, int limit) {
        thread(level);
        if (limit < 1 || limit > 16) throw new IllegalArgumentException("Invalid consolidation budget");
        long start = System.nanoTime();
        var session = SESSIONS.computeIfAbsent(level, ignored -> new Session());
        var capitals = KingdomSavedData.get(level).kingdoms().stream()
                .filter(k -> k.active() && !k.fallen())
                .sorted(Comparator.comparing(KingdomSavedData.Kingdom::id)).toList();
        int inspected = Math.min(limit, capitals.size()), committed = 0;
        if (!capitals.isEmpty()) {
            int offset = nextIndex(capitals, session.capitalCursor, KingdomSavedData.Kingdom::id);
            var service = new RoyalConsolidationService(level);
            for (int n = 0; n < inspected; n++) {
                var kingdom = capitals.get((offset + n) % capitals.size());
                session.capitalCursor = kingdom.id();
                if (maintain(level, session, service, kingdom)) committed++;
            }
        }
        return session.last = new Report(inspected, committed, System.nanoTime() - start);
    }

    private static boolean maintain(ServerLevel level, Session session, RoyalConsolidationService service,
                                    KingdomSavedData.Kingdom kingdom) {
        String key = "capital/" + kingdom.id();
        var capital = PoliticsService.village(level, kingdom.capital());
        if (capital != null && !level.isLoaded(capital.getCenter())) {
            // Existing journal recovery has its own loop, even when new maintenance is disabled.
            session.warnings.remove(key);
            LogUtils.getLogger().debug("[Kingdoms] Treasury maintenance {} waits for loaded native storage", key);
            return false;
        }
        try {
            var result = service.consolidate(kingdom.id());
            if (result.status() == RoyalConsolidationService.Status.RECOVERY_REQUIRED) {
                warn(level, session, key, new IllegalStateException(result.reason()));
            } else session.warnings.remove(key);
            return result.status() == RoyalConsolidationService.Status.COMMITTED;
        } catch (RuntimeException failure) {
            warn(level, session, key, failure);
            return false;
        }
    }

    private static void recover(ServerLevel level) {
        var session = SESSIONS.computeIfAbsent(level, ignored -> new Session());
        try {
            var service = new RoyalConsolidationService(level);
            var entries = service.current().stream()
                    .filter(e -> e.intent().getString("dimension").equals(level.dimension().location().toString()))
                    .sorted(Comparator.comparing(RoyalPaymentJournal.Entry::id)).toList();
            if (entries.isEmpty()) return;
            int next = nextIndex(entries, session.recoveryCursor, RoyalPaymentJournal.Entry::id);
            var entry = entries.get(next);
            session.recoveryCursor = entry.id();
            service.recover(entry.id());
            session.warnings.remove("recovery");
        } catch (RuntimeException failure) {
            warn(level, session, "recovery", failure);
        }
    }

    private static <T> int nextIndex(List<T> sorted, UUID cursor, Function<T, UUID> id) {
        int index = 0;
        if (cursor != null) while (index < sorted.size() && id.apply(sorted.get(index)).compareTo(cursor) <= 0) index++;
        return index % sorted.size();
    }

    public static Report last(ServerLevel level) {
        var session = SESSIONS.get(level);
        return session == null ? new Report(0, 0, 0) : session.last;
    }

    private static void warn(ServerLevel level, Session session, String key, RuntimeException failure) {
        long now = level.getGameTime();
        var last = session.warnings.get(key);
        if (last == null || now - last >= WARNING_INTERVAL) {
            session.warnings.put(key, now);
            LogUtils.getLogger().warn("[Kingdoms] Conserving treasury maintenance {} deferred; original evidence retained: {}", key, failure.getMessage());
        }
    }

    private static void thread(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Treasury maintenance requires server thread");
    }
}
