package com.elepay.identity;

import com.elepay.common.TokenBucketRateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * First line of application-layer defence. Runs before Spring Security, authentication, JSON parsing or any
 * database work, so every rejection here is cheap for ELEPAY and wasted effort for the attacker.
 *
 * <ol>
 *   <li><b>Penalty box</b>: clients banned by {@link AbusePenaltyBox} get 429 immediately.</li>
 *   <li><b>Shape limits</b>: over-long URIs (414) and oversized bodies (413). Declared sizes are checked up
 *       front; chunked bodies are counted as they stream, so an endless body is cut off at the limit.</li>
 *   <li><b>Global per-client rate limit</b>: a token bucket per client address across every route, in
 *       addition to the tighter route limits in {@link RateLimitFilter}.</li>
 *   <li><b>Load shedding</b>: a cap on requests in flight. With virtual threads Tomcat accepts almost
 *       unlimited concurrency, which would let a flood queue thousands of requests on the database pool and
 *       slow everyone down. Past the cap, requests fail fast with 503 so admitted requests stay fast.</li>
 *   <li><b>Strikes</b>: 401, 403, 404 and 413 responses feed the penalty box, so credential brute forcing and
 *       route scanning escalate into bans.</li>
 * </ol>
 *
 * <p>Health probes are exempt so orchestration can always see the instance. Volumetric network floods must
 * be absorbed upstream (CDN / scrubbing / edge proxy); see {@code docs/runbooks/ddos-defence.md}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class EdgeProtectionFilter extends OncePerRequestFilter {

    private final boolean enabled;
    private final long maxBodyBytes;
    private final int maxUriLength;
    private final TokenBucketRateLimiter perClient;
    private final Semaphore inFlight;
    private final int maxInFlight;
    @Nullable private final Semaphore callbackInFlight;
    private final int callbackMaxInFlight;
    private final boolean exemptCallbacksFromShedding;
    private final long admissionWaitMillis;
    private final AbusePenaltyBox penaltyBox;
    @Nullable private final MeterRegistry meters;
    private final String trustedProxies;

    public EdgeProtectionFilter(boolean enabled,
                                long maxBodyBytes,
                                int maxUriLength,
                                int perClientBurst,
                                int perClientPerMinute,
                                int maxInFlight,
                                long admissionWaitMillis,
                                AbusePenaltyBox penaltyBox,
                                @Nullable StringRedisTemplate redis,
                                @Nullable MeterRegistry meters) {
        this(enabled, maxBodyBytes, maxUriLength, perClientBurst, perClientPerMinute,
                maxInFlight, admissionWaitMillis, penaltyBox, redis, meters, "");
    }

    public EdgeProtectionFilter(boolean enabled,
                                long maxBodyBytes,
                                int maxUriLength,
                                int perClientBurst,
                                int perClientPerMinute,
                                int maxInFlight,
                                long admissionWaitMillis,
                                AbusePenaltyBox penaltyBox,
                                @Nullable StringRedisTemplate redis,
                                @Nullable MeterRegistry meters,
                                String trustedProxies) {
        this(enabled, maxBodyBytes, maxUriLength, perClientBurst, perClientPerMinute,
                maxInFlight, 200, true, admissionWaitMillis, penaltyBox, redis, meters, trustedProxies);
    }

    @Autowired
    public EdgeProtectionFilter(@Value("${elepay.edge.enabled:true}") boolean enabled,
                                @Value("${elepay.edge.max-body-bytes:1048576}") long maxBodyBytes,
                                @Value("${elepay.edge.max-uri-length:2048}") int maxUriLength,
                                @Value("${elepay.edge.per-client.burst:300}") int perClientBurst,
                                @Value("${elepay.edge.per-client.per-minute:1200}") int perClientPerMinute,
                                @Value("${elepay.edge.max-in-flight:400}") int maxInFlight,
                                @Value("${elepay.edge.callback-max-in-flight:200}") int callbackMaxInFlight,
                                @Value("${elepay.edge.exempt-callbacks-from-shedding:true}") boolean exemptCallbacksFromShedding,
                                @Value("${elepay.edge.admission-wait-ms:50}") long admissionWaitMillis,
                                AbusePenaltyBox penaltyBox,
                                @Nullable StringRedisTemplate redis,
                                @Nullable MeterRegistry meters,
                                @Value("${elepay.security.trusted-proxies:}") String trustedProxies) {
        this.enabled = enabled;
        this.maxBodyBytes = maxBodyBytes;
        this.maxUriLength = maxUriLength;
        this.perClient = TokenBucketRateLimiter.perMinute(perClientBurst, perClientPerMinute, redis);
        this.maxInFlight = Math.max(1, maxInFlight);
        this.inFlight = new Semaphore(this.maxInFlight);
        this.callbackMaxInFlight = Math.max(0, callbackMaxInFlight);
        this.callbackInFlight = this.callbackMaxInFlight > 0 ? new Semaphore(this.callbackMaxInFlight) : null;
        this.exemptCallbacksFromShedding = exemptCallbacksFromShedding;
        this.admissionWaitMillis = Math.max(0, admissionWaitMillis);
        this.penaltyBox = penaltyBox;
        this.meters = meters;
        this.trustedProxies = trustedProxies == null ? "" : trustedProxies;
        if (meters != null) {
            meters.gauge("elepay.edge.in_flight", this, EdgeProtectionFilter::inFlight);
            meters.gauge("elepay.edge.callback_in_flight", this, EdgeProtectionFilter::callbackInFlight);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !enabled || uri.startsWith("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String client = ClientIpResolver.resolve(request, trustedProxies);

        long banned = penaltyBox.banRemainingSeconds(client);
        if (banned > 0) {
            reject(response, 429, "temporarily_blocked", "Too many suspicious requests. Try again later.", banned, "banned");
            return;
        }

        String uri = request.getRequestURI();
        String query = request.getQueryString();
        if (uri.length() + (query == null ? 0 : query.length() + 1) > maxUriLength) {
            penaltyBox.strike(client, 3);
            reject(response, 414, "uri_too_long", "Request URI is too long.", 0, "uri_too_long");
            return;
        }

        long declared = request.getContentLengthLong();
        if (declared > maxBodyBytes) {
            penaltyBox.strike(client, 3);
            reject(response, 413, "payload_too_large", "Request body is too large.", 0, "body_too_large");
            return;
        }

        boolean callbackExempt = exemptCallbacksFromShedding && isRailCallback(request.getMethod(), uri);
        if (!callbackExempt && !penaltyBox.isExempt(client) && !perClient.tryAcquire("edge:" + client)) {
            penaltyBox.strike(client, 1);
            reject(response, 429, "rate_limited", "Too many requests. Slow down.", 10, "client_rate");
            return;
        }

        // Signed rail callbacks use a dedicated in-flight budget (or bypass shedding if configured <= 0)
        // so merchant API bursts saturating the general pool never shed inbound payment confirmations.
        Semaphore budget = callbackExempt ? callbackInFlight : inFlight;
        if (budget != null) {
            boolean admitted;
            try {
                admitted = admissionWaitMillis == 0 ? budget.tryAcquire()
                        : budget.tryAcquire(admissionWaitMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                admitted = false;
            }
            if (!admitted) {
                reject(response, 503, "overloaded", "Service is busy. Retry shortly.", 1,
                        callbackExempt ? "callback_shed" : "shed");
                return;
            }
        }

        CappedRequest capped = new CappedRequest(request, maxBodyBytes);
        try {
            chain.doFilter(capped, response);
        } catch (ServletException | IOException | RuntimeException e) {
            if (!capped.overflowed()) throw e;
            // The body exceeded the limit while streaming (chunked upload).
        } finally {
            if (budget != null) {
                budget.release();
            }
        }
        if (capped.overflowed()) {
            penaltyBox.strike(client, 3);
            if (!response.isCommitted()) {
                response.reset();
                reject(response, 413, "payload_too_large", "Request body is too large.", 0, "body_too_large");
            }
            return;
        }

        int weight = switch (response.getStatus()) {
            case 401 -> 4;
            case 403 -> 2;
            case 404, 405 -> 1;
            default -> 0;
        };
        if (weight > 0) {
            long ban = penaltyBox.strike(client, weight);
            if (ban > 0) count("ban_issued");
        }
    }

    /** Signed inbound rail/partner callback routes exempt from general load shedding. */
    static boolean isRailCallback(String method, String uri) {
        if (!"POST".equals(method) || uri == null) return false;
        return (uri.startsWith("/v1/rails/") && (uri.endsWith("/callbacks") || uri.contains("/callbacks/")))
                || (uri.startsWith("/v1/fx/partners/") && (uri.endsWith("/callbacks") || uri.endsWith("/inbound")));
    }

    /** Requests currently admitted in the general pool (for metrics and tests). */
    public int inFlight() {
        return maxInFlight - inFlight.availablePermits();
    }

    /** Callback requests currently admitted in the dedicated callback pool (for metrics and tests). */
    public int callbackInFlight() {
        return callbackInFlight == null ? 0 : callbackMaxInFlight - callbackInFlight.availablePermits();
    }

    /** Configured general in-flight budget. */
    public int maxInFlight() {
        return maxInFlight;
    }

    private void reject(HttpServletResponse response, int status, String code, String message, long retryAfter, String reason)
            throws IOException {
        count(reason);
        response.setStatus(status);
        if (retryAfter > 0) response.setHeader("Retry-After", String.valueOf(retryAfter));
        response.setHeader("Connection", status == 503 ? "keep-alive" : "close");
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\",\"detail\":\"" + message + "\",\"message\":\"" + message + "\",\"status\":" + status + "}");
    }

    private void count(String reason) {
        if (meters != null) meters.counter("elepay.edge.rejected", "reason", reason).increment();
    }

    /** Thrown when a streamed body passes the size limit. */
    static final class BodyTooLargeException extends IOException {
        BodyTooLargeException() {
            super("Request body exceeds the configured limit");
        }
    }

    /** Wraps the request so the body cannot be read past {@code limit} bytes, whatever Content-Length says. */
    static final class CappedRequest extends HttpServletRequestWrapper {
        private final long limit;
        private CappedStream stream;

        CappedRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        boolean overflowed() {
            return stream != null && stream.overflowed;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) stream = new CappedStream(super.getInputStream(), limit);
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String enc = getCharacterEncoding();
            Charset cs = enc == null ? StandardCharsets.UTF_8 : Charset.forName(enc);
            return new BufferedReader(new InputStreamReader(getInputStream(), cs));
        }
    }

    static final class CappedStream extends ServletInputStream {
        private final ServletInputStream in;
        private final long limit;
        private long count;
        boolean overflowed;

        CappedStream(ServletInputStream in, long limit) {
            this.in = in;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) advance(1);
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = in.read(buf, off, len);
            if (n > 0) advance(n);
            return n;
        }

        private void advance(long n) throws BodyTooLargeException {
            count += n;
            if (count > limit) {
                overflowed = true;
                throw new BodyTooLargeException();
            }
        }

        @Override public boolean isFinished() { return in.isFinished(); }
        @Override public boolean isReady() { return in.isReady(); }
        @Override public void setReadListener(ReadListener listener) { in.setReadListener(listener); }
    }
}
