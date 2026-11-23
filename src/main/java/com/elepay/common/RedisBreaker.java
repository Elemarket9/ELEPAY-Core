package com.elepay.common;

import java.util.function.LongSupplier;

/**
 * Skips Redis for a cool-down period after a failure. Without it, a Redis that hangs instead of refusing
 * connections would add the full client timeout to every request that consults a shared limit, which turns
 * a Redis incident into a self-inflicted denial of service. While open, callers use their local fallback.
 */
public final class RedisBreaker {
    private final long coolDownMillis;
    private final LongSupplier clock;
    private volatile long openUntil;

    public RedisBreaker(long coolDownMillis) {
        this(coolDownMillis, System::currentTimeMillis);
    }

    public RedisBreaker(long coolDownMillis, LongSupplier clock) {
        this.coolDownMillis = coolDownMillis;
        this.clock = clock;
    }

    public boolean allow() {
        return clock.getAsLong() >= openUntil;
    }

    public void failure() {
        openUntil = clock.getAsLong() + coolDownMillis;
    }
}
