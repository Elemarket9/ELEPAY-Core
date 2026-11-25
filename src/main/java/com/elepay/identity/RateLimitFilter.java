package com.elepay.identity;

import com.elepay.common.TokenBucketRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-client rate limits on the routes attackers abuse without an account (OWASP ASVS 2.4, PCI DSS 6.4):
 * card-number testing through tokenization, account enumeration through recipient lookups, and floods of
 * the public checkout and USSD endpoints. Signed rail callbacks are not limited. Limits are per instance;
 * put a shared limit at the edge as well.
 *
 * <p>Layered L7 abuse resistance on top of the targeted rules:
 * <ul>
 *   <li>an oversized-body shed that rejects with 413 before the body is read or auth/DB are touched;</li>
 *   <li>a coarse per-client-IP ceiling (stricter for unauthenticated/public traffic than for credentialed
 *       API traffic), a per-credential ceiling, and a per-instance global ceiling — all cheap flood
 *       breakers with generous defaults so legitimate bursts and idempotent retries are not broken.</li>
 * </ul>
 *
 * <p>This is application-layer (L7) protection only: it sheds connection-cheap abuse and enumeration.
 * Volumetric L3/L4 DDoS must be absorbed at the edge (CDN / WAF / Cloudflare / AWS Shield + Kubernetes
 * ingress), which this filter does not and cannot replace.
 *
 * <p>Fail-safe: when a shared (Redis-backed) limit is required but Redis is unavailable, the underlying
 * limiter fails closed (denies) — this is a payments system, so there is no fail-open path.
 */
@Component
class RateLimitFilter extends OncePerRequestFilter {
    record Rule(String method, String prefix, String name, TokenBucketRateLimiter limiter) {
        boolean matches(HttpServletRequest r, String path) {
            return path != null && (method == null || method.equals(r.getMethod())) && path.startsWith(prefix);
        }
    }

    private final List<Rule> rules;
    private final boolean enabled;
    private final boolean sharedRequired;
    private final String trustedProxies;
    private final long maxBodyBytes;

    // Coarse flood breakers, layered on top of the targeted rules above.
    private final TokenBucketRateLimiter unauthPerIp;
    private final TokenBucketRateLimiter authPerIp;
    private final TokenBucketRateLimiter perCredential;
    private final TokenBucketRateLimiter global;

    @org.springframework.beans.factory.annotation.Autowired
    RateLimitFilter(@Value("${elepay.rate-limits.enabled:true}") boolean enabled,
                    @Value("${elepay.rate-limits.multiplier:1}") int multiplier,
                    org.springframework.data.redis.core.StringRedisTemplate redis,
                    @Value("${elepay.rate-limits.shared-required:false}") boolean sharedRequired,
                    @Value("${elepay.security.trusted-proxies:}") String trustedProxies,
                    @Value("${elepay.rate-limits.max-body-bytes:2097152}") long maxBodyBytes,
                    @Value("${elepay.rate-limits.unauth-per-ip-per-min:300}") int unauthPerIpPerMin,
                    @Value("${elepay.rate-limits.auth-per-ip-per-min:1200}") int authPerIpPerMin,
                    @Value("${elepay.rate-limits.per-credential-per-min:3000}") int perCredentialPerMin,
                    @Value("${elepay.rate-limits.global-per-min:60000}") int globalPerMin) {
        this.enabled = enabled;
        this.sharedRequired = sharedRequired;
        this.trustedProxies = trustedProxies == null ? "" : trustedProxies;
        this.maxBodyBytes = maxBodyBytes;
        int m = Math.max(1, multiplier);
        this.rules = List.of(
                new Rule("POST", "/v1/rails/card/tokens", "card-tokenize", TokenBucketRateLimiter.perMinute(10 * m, 10 * m, redis)),
                new Rule(null, "/v1/wallet/recipients/lookup", "recipient-lookup", TokenBucketRateLimiter.perMinute(20 * m, 20 * m, redis)),
                new Rule(null, "/v1/public/", "public", TokenBucketRateLimiter.perMinute(120 * m, 120 * m, redis)),
                new Rule("POST", "/v1/ussd/", "ussd", TokenBucketRateLimiter.perMinute(300 * m, 300 * m, redis)));
        // Capacity == per-minute allowance, so a client may burst up to a full minute's budget (idempotent
        // retries are not penalised) and then refills steadily. Defaults are deliberately generous: these
        // break volumetric floods, not the carefully tuned targeted rules above.
        this.unauthPerIp = TokenBucketRateLimiter.perMinute(unauthPerIpPerMin * m, unauthPerIpPerMin * m, redis);
        this.authPerIp = TokenBucketRateLimiter.perMinute(authPerIpPerMin * m, authPerIpPerMin * m, redis);
        this.perCredential = TokenBucketRateLimiter.perMinute(perCredentialPerMin * m, perCredentialPerMin * m, redis);
        this.global = TokenBucketRateLimiter.perMinute(globalPerMin * m, globalPerMin * m, redis);
    }

    RateLimitFilter(boolean enabled, int multiplier) {
        this(enabled, multiplier, null, false, "", 2097152, 300, 1200, 3000, 60000);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!enabled) {
            chain.doFilter(request, response);
            return;
        }

        // 1. Shed oversized bodies before the body is read or auth/DB are touched. Content-Length is header
        //    metadata (no I/O). Chunked requests with no declared length (-1) fall through to the servlet
        //    container's own max-request-size guard.
        if (maxBodyBytes > 0) {
            long declared = request.getContentLengthLong();
            if (declared > maxBodyBytes) {
                reject(response, 413, "{\"code\":\"payload_too_large\",\"detail\":\"Request body is too large.\",\"message\":\"Request body is too large.\",\"status\":413}");
                return;
            }
        }

        String path = RequestPaths.normalized(request);

        // Signed rail/partner callbacks must never be throttled or delayed: delivery is at-least-once and
        // deduplicated downstream. Let them pass without consuming any limiter (also keeps the money
        // confirmation hot path free of extra round-trips).
        if (path != null && isSignedCallback(request.getMethod(), path)) {
            chain.doFilter(request, response);
            return;
        }

        String clientIp = ClientIpResolver.resolve(request, trustedProxies);

        // 2. Targeted per-route abuse rules (keyed on the resolved client IP).
        if (path != null) {
            for (Rule rule : rules) {
                if (rule.matches(request, path)) {
                    if (!acquire(rule.limiter(), rule.name() + ":" + clientIp)) {
                        tooManyRequests(response);
                        return;
                    }
                    break;
                }
            }
        }

        // 3. Coarse, layered flood breakers.
        boolean credentialed = request.getHeader("X-Elepay-Api-Key") != null || hasBearer(request);
        TokenBucketRateLimiter ipLimiter = credentialed ? authPerIp : unauthPerIp;
        if (!acquire(ipLimiter, "ip:" + clientIp)) {
            tooManyRequests(response);
            return;
        }
        String credential = credentialKey(request);
        if (credential != null && !acquire(perCredential, "cred:" + credential)) {
            tooManyRequests(response);
            return;
        }
        if (!acquire(global, "global")) {
            tooManyRequests(response);
            return;
        }

        chain.doFilter(request, response);
    }

    /** Fail-safe acquire: honours the existing shared-required policy (Redis-backed limiter fails closed). */
    private boolean acquire(TokenBucketRateLimiter limiter, String key) {
        return sharedRequired ? limiter.tryAcquireShared(key) : limiter.tryAcquire(key);
    }

    private static boolean hasBearer(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        return auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7);
    }

    /**
     * A stable, non-reversible key derived from the caller's credential. The raw API key / bearer token is a
     * secret and is never used as (or placed into) a limiter key; it is hashed first.
     */
    private static String credentialKey(HttpServletRequest request) {
        String apiKey = request.getHeader("X-Elepay-Api-Key");
        if (apiKey != null && !apiKey.isBlank()) return hash(apiKey);
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7) && auth.length() > 7) {
            return hash(auth.substring(7));
        }
        return null;
    }

    private static String hash(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < 16; i++) sb.append(Character.forDigit((digest[i] >> 4) & 0xF, 16))
                    .append(Character.forDigit(digest[i] & 0xF, 16));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Signed, deduplicated inbound callbacks that must not be rate limited (see {@link SecurityConfig}). */
    private static boolean isSignedCallback(String method, String path) {
        if (!"POST".equals(method)) return false;
        // Match the SecurityConfig permitAll pattern "/v1/rails/*/callbacks/**", which covers both the
        // bare ".../callbacks" form and ".../callbacks/<provider-suffix>". The earlier check only matched
        // "/callbacks/" (with a trailing segment), so a bare ".../callbacks" wrongly fell through to the
        // coarse per-IP ceiling and got throttled.
        return (path.startsWith("/v1/rails/") && (path.endsWith("/callbacks") || path.contains("/callbacks/")))
                || (path.startsWith("/v1/fx/partners/") && (path.endsWith("/callbacks") || path.endsWith("/inbound")));
    }

    private void tooManyRequests(HttpServletResponse response) throws IOException {
        reject(response, 429, "{\"code\":\"rate_limited\",\"detail\":\"Too many requests. Try again in a minute.\",\"message\":\"Too many requests. Try again in a minute.\",\"status\":429}");
    }

    private void reject(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        if (status == 429) response.setHeader("Retry-After", "60");
        response.setContentType("application/json");
        response.getWriter().write(body);
    }
}
