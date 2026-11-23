package com.elepay.identity;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Escalating temporary bans for clients that behave like attackers.
 *
 * <p>Each suspicious response (failed authentication, forbidden access, probing for routes that do not
 * exist, oversized payloads) adds weighted strike points to the client. Crossing the threshold inside the
 * window bans the client, and every repeat ban within 24 hours of the previous ban ending lasts longer:
 * 1 min, 5 min, 30 min, 2 h, 24 h.
 * A banned client is refused by {@link EdgeProtectionFilter} before authentication, so brute forcing API keys
 * or signatures costs the attacker a ban while costing ELEPAY one Redis lookup and no database work.
 *
 * <p>State is shared through Redis when available (bans apply on every node) and falls back to a bounded
 * in-memory table, so a Redis outage weakens but never disables the protection.
 *
 * <p><strong>Shared IPs.</strong> Ghanaian mobile carriers place many subscribers behind carrier-grade NAT,
 * so one address can be thousands of customers. Thresholds are deliberately generous and configurable, and
 * partner callback addresses can be exempted with {@code elepay.abuse.exempt-addresses}.
 */
@Component
public class AbusePenaltyBox {

    /** Ban length for the n-th ban within 24 hours (last value repeats). */
    static final List<Duration> BAN_LADDER = List.of(
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2), Duration.ofHours(24));

    private static final Duration LEVEL_MEMORY = Duration.ofHours(24);
    static final int MAX_LOCAL_CLIENTS = 200_000;

    // KEYS: strikes, ban, level. ARGV: weight, windowSeconds, threshold, ladder seconds...
    private static final RedisScript<Long> STRIKE_SCRIPT = new DefaultRedisScript<>("""
            local s = redis.call('INCRBY', KEYS[1], ARGV[1])
            if s == tonumber(ARGV[1]) then redis.call('EXPIRE', KEYS[1], ARGV[2]) end
            if s < tonumber(ARGV[3]) then return 0 end
            local lvl = redis.call('INCR', KEYS[3])
            local idx = math.min(lvl, #ARGV - 3)
            local secs = tonumber(ARGV[3 + idx])
            -- remember the offence level for 24 h after the ban ends, not after it starts
            redis.call('EXPIRE', KEYS[3], secs + 86400)
            redis.call('SET', KEYS[2], '1', 'EX', secs)
            redis.call('DEL', KEYS[1])
            return secs
            """, Long.class);

    private final boolean enabled;
    private final int threshold;
    private final Duration window;
    private final List<CidrBlock> exempt;
    @Nullable private final StringRedisTemplate redis;
    private final LongSupplier clockMillis;
    private final ConcurrentHashMap<String, LocalState> local = new ConcurrentHashMap<>();
    private final com.elepay.common.RedisBreaker redisBreaker = new com.elepay.common.RedisBreaker(5_000);

    @Autowired
    public AbusePenaltyBox(@Value("${elepay.abuse.enabled:true}") boolean enabled,
                           @Value("${elepay.abuse.strike-threshold:60}") int threshold,
                           @Value("${elepay.abuse.window-seconds:600}") long windowSeconds,
                           @Value("${elepay.abuse.exempt-addresses:}") List<String> exemptAddresses,
                           @Nullable StringRedisTemplate redis) {
        this(enabled, threshold, Duration.ofSeconds(windowSeconds), exemptAddresses, redis, System::currentTimeMillis);
    }

    AbusePenaltyBox(boolean enabled, int threshold, Duration window, List<String> exemptAddresses,
                    @Nullable StringRedisTemplate redis, LongSupplier clockMillis) {
        this.enabled = enabled;
        this.threshold = Math.max(1, threshold);
        this.window = Objects.requireNonNull(window);
        this.exempt = exemptAddresses == null ? List.of()
                : exemptAddresses.stream().filter(a -> a != null && !a.isBlank()).map(CidrBlock::parse).toList();
        this.redis = redis;
        this.clockMillis = clockMillis;
    }

    public boolean isExempt(String client) {
        return !enabled || client == null || exempt.stream().anyMatch(b -> b.contains(client));
    }

    /** Remaining ban in seconds, or 0 when the client may proceed. */
    public long banRemainingSeconds(String client) {
        if (isExempt(client)) return 0;
        if (redis != null && redisBreaker.allow()) {
            try {
                Long ttl = redis.getExpire(banKey(client));
                if (ttl != null && ttl > 0) return ttl;
                if (ttl != null) return localBanRemaining(client); // -2 = no ban in Redis; still honour local
            } catch (RuntimeException redisUnavailable) {
                redisBreaker.failure(); // fall through to local state
            }
        }
        return localBanRemaining(client);
    }

    /**
     * Adds strike points. Returns the ban length in seconds if this strike triggered a ban, otherwise 0.
     */
    public long strike(String client, int weight) {
        if (isExempt(client) || weight <= 0) return 0;
        if (redis != null && redisBreaker.allow()) {
            try {
                Long secs = redis.execute(STRIKE_SCRIPT, List.of(strikesKey(client), banKey(client), levelKey(client)),
                        ladderArgs(weight));
                if (secs != null) return secs;
            } catch (RuntimeException redisUnavailable) {
                redisBreaker.failure(); // fall through to local state
            }
        }
        return localStrike(client, weight);
    }

    int localClientCount() {
        return local.size();
    }

    private Object[] ladderArgs(int weight) {
        Object[] args = new Object[3 + BAN_LADDER.size()];
        args[0] = String.valueOf(weight);
        args[1] = String.valueOf(window.toSeconds());
        args[2] = String.valueOf(threshold);
        for (int i = 0; i < BAN_LADDER.size(); i++) args[3 + i] = String.valueOf(BAN_LADDER.get(i).toSeconds());
        return args;
    }

    private long localBanRemaining(String client) {
        LocalState st = local.get(client);
        if (st == null) return 0;
        synchronized (st) {
            long remaining = st.bannedUntil - clockMillis.getAsLong();
            return remaining > 0 ? Math.max(1, (remaining + 999) / 1000) : 0;
        }
    }

    private long localStrike(String client, int weight) {
        long now = clockMillis.getAsLong();
        LocalState st = local.get(client);
        if (st == null) {
            if (local.size() >= MAX_LOCAL_CLIENTS) {
                local.entrySet().removeIf(e -> e.getValue().idle(now));
            }
            if (local.size() >= MAX_LOCAL_CLIENTS) {
                return 0; // table saturated; Redis or the edge proxy must carry the load
            }
            st = local.computeIfAbsent(client, k -> new LocalState());
        }
        synchronized (st) {
            if (now - st.windowStart > window.toMillis()) {
                st.windowStart = now;
                st.points = 0;
            }
            if (st.level > 0 && now - st.bannedUntil > LEVEL_MEMORY.toMillis()) {
                st.level = 0;
            }
            st.points += weight;
            if (st.points < threshold) return 0;
            st.level++;
            Duration ban = BAN_LADDER.get(Math.min(st.level, BAN_LADDER.size()) - 1);
            st.bannedUntil = now + ban.toMillis();
            st.points = 0;
            return ban.toSeconds();
        }
    }

    private static String strikesKey(String c) { return "elepay:abuse:strikes:" + c; }
    private static String banKey(String c) { return "elepay:abuse:ban:" + c; }
    private static String levelKey(String c) { return "elepay:abuse:level:" + c; }

    private final class LocalState {
        long windowStart = clockMillis.getAsLong();
        int points;
        int level;
        long bannedUntil;

        synchronized boolean idle(long now) {
            return bannedUntil <= now && now - windowStart > window.toMillis() && now - bannedUntil > LEVEL_MEMORY.toMillis();
        }
    }
}
