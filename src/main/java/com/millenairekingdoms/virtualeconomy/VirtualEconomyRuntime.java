package com.millenairekingdoms.virtualeconomy;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.millenaire.village.Village;
import org.millenaire.village.VillageSavedData;
import org.slf4j.Logger;

/**
 * Bounded round-robin pass on the server thread: unloaded villages catch up their missed days, loaded villages drain
 * their buffer and hand paid projects to the native builders. Never loads chunks or scans blocks.
 */
public final class VirtualEconomyRuntime {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<ServerLevel, Session> SESSIONS = new WeakHashMap<>();
    private static final class Session {
        UUID cursor;
        long nextTick;
        final Map<UUID, Long> diagnostics = new HashMap<>();
    }
    private VirtualEconomyRuntime() {}

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level))
            return;
        var settings = VirtualEconomyConfig.current();
        if (!settings.enabled())
            return;
        var session = SESSIONS.computeIfAbsent(level, key -> new Session());
        long now = level.getGameTime();
        if (now < session.nextTick)
            return;
        session.nextTick = now + settings.interval();
        step(level, settings, session);
    }

    /** One bounded pass; also called directly by tests. Returns villages visited. */
    public static int step(ServerLevel level, VirtualEconomyConfig.Settings settings) {
        return step(level, settings, SESSIONS.computeIfAbsent(level, key -> new Session()));
    }

    private static int step(ServerLevel level, VirtualEconomyConfig.Settings settings, Session session) {
        if (!level.getServer().isSameThread())
            throw new IllegalStateException("Virtual economy requires the server thread");
        var villages = new ArrayList<Village>();
        for (var village : VillageSavedData.get(level).getVillageManager().getAllVillages())
            if (VirtualEconomyService.eligible(village))
                villages.add(village);
        villages.sort(Comparator.comparing(v -> v.getId().uuid()));
        forgetRemoved(level, villages);
        if (villages.isEmpty())
            return 0;
        int start = 0;
        if (session.cursor != null) {
            while (start < villages.size() && villages.get(start).getId().uuid().compareTo(session.cursor) <= 0)
                start++;
            start %= villages.size();
        }
        int count = Math.min(settings.maxPerStep(), villages.size());
        long today = VirtualEconomyService.today(level);
        for (int i = 0; i < count; i++) {
            var village = villages.get((start + i) % villages.size());
            var id = village.getId().uuid();
            session.cursor = id;
            try {
                visit(level, village, today, settings);
                session.diagnostics.remove(id);
            } catch (RuntimeException failure) {
                Long last = session.diagnostics.get(id);
                if (last == null || level.getGameTime() - last >= 12_000) {
                    session.diagnostics.put(id, level.getGameTime());
                    LOGGER.warn("[Kingdoms] Virtual economy of village {} deferred: {}", id, failure.toString());
                }
            }
        }
        return count;
    }

    static void visit(ServerLevel level, Village village, long today, VirtualEconomyConfig.Settings settings) {
        if (VirtualEconomyService.isUnloaded(level, village)) {
            VirtualEconomyService.catchUp(level, village, today, settings);
            return;
        }
        VirtualEconomyService.markLoaded(level, village, today);
        VirtualEconomyService.drain(level, village);
        VirtualEconomyService.handoff(level, village, settings);
        VirtualEconomyService.expireBoosts(level, village);
    }

    private static void forgetRemoved(ServerLevel level, java.util.List<Village> villages) {
        var data = VirtualEconomySavedData.get(level);
        var known = new HashSet<UUID>();
        for (var v : villages)
            known.add(v.getId().uuid());
        var manager = VillageSavedData.get(level).getVillageManager();
        for (var state : data.all())
            if (!known.contains(state.village())
                    && manager.getVillage(new org.millenaire.village.VillageId(state.village())) == null)
                data.forget(state.village()); // village destroyed: its unloaded surplus goes with it
        for (var boost : java.util.List.copyOf(data.boosts().values()))
            if (manager.getVillage(new org.millenaire.village.VillageId(boost.village())) == null)
                data.removeBoost(boost.building());
    }

    public static void onStopped(ServerStoppedEvent event) {
        SESSIONS.keySet().removeIf(level -> level.getServer() == event.getServer());
        VirtualConstructionBoosts.clear();
    }
}
