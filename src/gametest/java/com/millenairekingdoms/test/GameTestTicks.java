package com.millenairekingdoms.test;

import net.minecraft.gametest.framework.GameTestHelper;

/** The helper's onEachTick preallocates timeoutTicks tasks and scans them each tick. Keep one pending callback.
 * Assertions, world/calendar ticks, production events, native work and original test timeouts stay unchanged. */
final class GameTestTicks {
    private GameTestTicks() {}
    static void each(GameTestHelper helper,Runnable action) {
        helper.runAfterDelay(1,()->{action.run();each(helper,action);});
    }
}
