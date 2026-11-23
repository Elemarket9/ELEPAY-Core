package com.elepay.identity;

import com.elepay.common.TokenBucketRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-client rate limits on the routes attackers abuse without an account (OWASP ASVS 2.4, PCI DSS 6.4):
 * card-number testing through tokenization, account enumeration through recipient lookups, and floods of
 * the public checkout and USSD endpoints. Signed rail callbacks are not limited. Limits are per instance;
 * put a shared limit at the edge as well.
 */
@Component
class RateLimitFilter extends OncePerRequestFilter {
    record Rule(String method, String prefix, String name, TokenBucketRateLimiter limiter) {
        boolean matches(HttpServletRequest r) {
            String path = RequestPaths.normalized(r);
            return path != null && (method == null || method.equals(r.getMethod())) && path.startsWith(prefix);
        }
    }

    private final List<Rule> rules;
    private final boolean enabled;
    private final boolean sharedRequired;

    @org.springframework.beans.factory.annotation.Autowired
    RateLimitFilter(@Value("${elepay.rate-limits.enabled:true}") boolean enabled,
                    @Value("${elepay.rate-limits.multiplier:1}") int multiplier,
                    org.springframework.data.redis.core.StringRedisTemplate redis,
                    @Value("${elepay.rate-limits.shared-required:false}") boolean sharedRequired) {
        this.enabled = enabled;
        this.sharedRequired = sharedRequired;
        int m = Math.max(1, multiplier);
        this.rules = List.of(
                new Rule("POST", "/v1/rails/card/tokens", "card-tokenize", TokenBucketRateLimiter.perMinute(10 * m, 10 * m, redis)),
                new Rule(null, "/v1/wallet/recipients/lookup", "recipient-lookup", TokenBucketRateLimiter.perMinute(20 * m, 20 * m, redis)),
                new Rule(null, "/v1/public/", "public", TokenBucketRateLimiter.perMinute(120 * m, 120 * m, redis)),
                new Rule("POST", "/v1/ussd/", "ussd", TokenBucketRateLimiter.perMinute(300 * m, 300 * m, redis)));
    }

    RateLimitFilter(boolean enabled, int multiplier) { this(enabled, multiplier, null, false); }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (enabled) {
            for (Rule rule : rules) {
                if (rule.matches(request)) {
                    // getRemoteAddr is the real client only when the ingress is a trusted proxy (forward-headers=native).
                    String key = rule.name() + ":" + request.getRemoteAddr();
                    if (!(sharedRequired ? rule.limiter().tryAcquireShared(key) : rule.limiter().tryAcquire(key))) {
                        response.setStatus(429);
                        response.setHeader("Retry-After", "60");
                        response.setContentType("application/json");
                        response.getWriter().write("{\"code\":\"rate_limited\",\"message\":\"Too many requests. Try again in a minute.\"}");
                        return;
                    }
                    break;
                }
            }
        }
        chain.doFilter(request, response);
    }
}
