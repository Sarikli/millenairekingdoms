package com.millenairekingdoms.util;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** A refused political action with a translatable message (English fallback in getMessage, so command and log output
 * stay readable). Existing services throw plain IllegalArgumentException; callers treat both the same way. */
public final class KingdomException extends IllegalArgumentException {
    private final transient String key, fallback;
    private final transient Object[] args;

    /** fallback uses %s placeholders only, as Minecraft's translation strings do. */
    public KingdomException(String key, String fallback, Object... args) {
        super(String.format(java.util.Locale.ROOT, fallback, args));
        this.key = key; this.fallback = fallback; this.args = args;
    }
    public MutableComponent component() {
        return Component.translatableWithFallback(key, fallback, args);
    }
    public static MutableComponent of(RuntimeException e) {
        return e instanceof KingdomException k ? k.component() : Component.literal(e.getMessage() == null ? e.toString() : e.getMessage());
    }
    public static void check(boolean condition, String key, String fallback, Object... args) {
        if (!condition) throw new KingdomException(key, fallback, args);
    }
}
