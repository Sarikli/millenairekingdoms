package com.millenairekingdoms.politics;

import com.millenairekingdoms.autonomy.AutonomyConfig;
import com.millenairekingdoms.autonomy.AutonomyService;
import com.millenairekingdoms.capital.DynamicFounding;
import com.millenairekingdoms.capital.FoundingConfig;
import com.millenairekingdoms.economy.EconomyService;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Once per Minecraft day (dayTime / 24000), in one place: income, relation drift and border tension, NPC politics.
 * Checked once a second, never scanning villages per tick; the persisted day marker makes each day run once. */
public final class KingdomDay {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final long DELIVERY_RETRY_TICKS = 1200;
    private KingdomDay() {}

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getGameTime() % 20 != 0) return;
        var data = KingdomSavedData.get(level);
        long day = Math.max(0, level.getDayTime()) / EconomyService.DAY_TICKS;
        // A new world or an upgraded save starts counting today; the first payment comes at the next day start.
        if (data.day() < 0) data.startDay(day, Map.of());
        else if (day > data.day()) run(level, data, day);
        else if (level.getGameTime() % DELIVERY_RETRY_TICKS == 0 && !data.owed().isEmpty()) EconomyService.deliverAll(level, data);
    }

    /** Each new day pays and drifts relations once; several days skipped at once (for example with /time add) count as one. */
    public static boolean run(ServerLevel level, KingdomSavedData data, long day) { return run(level, data, day, AutonomyConfig.current()); }
    /** Stage 6.2: border tension every day, NPC political decisions every decisionIntervalDays (the next decision day is
     * stored, so restarts and skipped days never repeat or multiply an evaluation). */
    public static boolean run(ServerLevel level, KingdomSavedData data, long day, AutonomyConfig.Settings settings) {
        return run(level, data, day, settings, FoundingConfig.current(), level.getRandom());
    }
    /** 1.1.0: after NPC politics, developed independent villages may found kingdoms when the day starts a new founding
     * period. Fixed order on a shared day: joining (and every other NPC decision) first, founding second, so a village
     * that joined a kingdom is no longer independent and a newly founded capital is never a joining candidate the same day.
     * random drives every chance of the day (the world's random in play; a fixed or seeded one in tests). */
    public static boolean run(ServerLevel level, KingdomSavedData data, long day, AutonomyConfig.Settings settings,
                              FoundingConfig.Settings founding, RandomSource random) {
        long previous = data.day();
        if (!EconomyService.runDay(level, data, day)) return false;
        // Politics never stop the world: an unexpected error is logged and the day (already booked) is not repeated.
        try {
            DiplomacyService.drift(level, data, random);
            DiplomacyService.border(level, data, random);
        } catch (RuntimeException e) { LOGGER.error("[Kingdoms] Daily relation changes of day {} failed; they are skipped.", day, e); }
        if (settings.autonomy()) {
            if (data.nextDecisionDay() >= 0 && day >= data.nextDecisionDay()) {
                try { AutonomyService.evaluate(level, data, day, random, settings); }
                catch (RuntimeException e) { LOGGER.error("[Kingdoms] NPC political evaluation of day {} failed; the next one is scheduled.", day, e); }
            }
            if (data.nextDecisionDay() < 0 || day >= data.nextDecisionDay()) data.scheduleDecision(day + settings.decisionDays());
        }
        try { DynamicFounding.onDay(level, data, previous, day, founding, random); }
        catch (RuntimeException e) { LOGGER.error("[Kingdoms] Kingdom founding evaluation of day {} failed; the next period evaluates again.", day, e); }
        return true;
    }
}
