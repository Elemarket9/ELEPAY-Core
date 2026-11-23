package com.elepay.identity;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class EdgeProtectionFilterTest {

    private final AtomicLong clock = new AtomicLong(1_000_000L);

    private AbusePenaltyBox box(int threshold, String... exempt) {
        return new AbusePenaltyBox(true, threshold, Duration.ofMinutes(10), List.of(exempt), null, clock::get);
    }

    private EdgeProtectionFilter filter(AbusePenaltyBox box, long maxBody, int burst, int maxInFlight) {
        return new EdgeProtectionFilter(true, maxBody, 2048, burst, burst, maxInFlight, 0, box, null, null);
    }

    private static MockHttpServletRequest req(String ip, String uri) {
        var r = new MockHttpServletRequest("POST", uri);
        r.setRemoteAddr(ip);
        return r;
    }

    private static final FilterChain OK = (rq, rs) -> ((HttpServletResponse) rs).setStatus(200);

    @Test
    void declaredOversizedBodyIsRejectedBeforeTheApplication() throws Exception {
        var f = filter(box(100), 1_024, 100, 10);
        var r = req("10.0.0.1", "/v1/payments");
        r.setContent(new byte[2_048]);
        var res = new MockHttpServletResponse();
        boolean[] reached = {false};
        f.doFilter(r, res, (a, b) -> reached[0] = true);
        assertThat(res.getStatus()).isEqualTo(413);
        assertThat(reached[0]).isFalse();
    }

    @Test
    void streamedBodyIsCutOffAtTheLimitEvenWithoutContentLength() throws Exception {
        var f = filter(box(100), 1_024, 100, 10);
        var r = new MockHttpServletRequest("POST", "/v1/payments") {
            @Override public long getContentLengthLong() { return -1; } // chunked: size unknown up front
            @Override public int getContentLength() { return -1; }
        };
        r.setRemoteAddr("10.0.0.2");
        r.setContent(new byte[10_000]);
        var res = new MockHttpServletResponse();
        f.doFilter(r, res, (rq, rs) -> rq.getInputStream().readAllBytes());
        assertThat(res.getStatus()).isEqualTo(413);
    }

    @Test
    void overlongUriIsRejected() throws Exception {
        var f = filter(box(100), 1_024, 100, 10);
        var res = new MockHttpServletResponse();
        f.doFilter(req("10.0.0.3", "/v1/" + "a".repeat(3_000)), res, OK);
        assertThat(res.getStatus()).isEqualTo(414);
    }

    @Test
    void perClientRateLimitAppliesAcrossAllRoutes() throws Exception {
        var f = filter(box(1_000), 1_024, 5, 10);
        int limited = 0;
        for (int i = 0; i < 8; i++) {
            var res = new MockHttpServletResponse();
            f.doFilter(req("10.0.0.4", "/v1/route" + i), res, OK);
            if (res.getStatus() == 429) limited++;
        }
        assertThat(limited).isEqualTo(3);
        // A different client is unaffected.
        var other = new MockHttpServletResponse();
        f.doFilter(req("10.0.0.5", "/v1/route"), other, OK);
        assertThat(other.getStatus()).isEqualTo(200);
    }

    @Test
    void repeatedAuthFailuresEscalateIntoABanThatSkipsTheApplication() throws Exception {
        var f = filter(box(20), 1_024, 1_000, 10);
        FilterChain unauthorized = (rq, rs) -> ((HttpServletResponse) rs).setStatus(401);
        for (int i = 0; i < 5; i++) { // 5 x 401 = 20 points
            f.doFilter(req("10.0.0.6", "/v1/wallet"), new MockHttpServletResponse(), unauthorized);
        }
        var res = new MockHttpServletResponse();
        boolean[] reached = {false};
        f.doFilter(req("10.0.0.6", "/v1/wallet"), res, (a, b) -> reached[0] = true);
        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(res.getHeader("Retry-After")).isEqualTo("60");
        assertThat(reached[0]).isFalse();
    }

    @Test
    void loadSheddingFailsFastWhenInFlightCapIsReached() throws Exception {
        var f = filter(box(1_000), 1_024, 1_000, 1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> {
                f.doFilter(req("10.0.1.1", "/v1/slow"), new MockHttpServletResponse(), (rq, rs) -> {
                    entered.countDown();
                    try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                });
                return null;
            });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var res = new MockHttpServletResponse();
            f.doFilter(req("10.0.1.2", "/v1/fast"), res, OK);
            assertThat(res.getStatus()).isEqualTo(503);
            assertThat(res.getHeader("Retry-After")).isEqualTo("1");
        } finally {
            release.countDown();
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
        assertThat(f.inFlight()).isZero();
    }

    @Test
    void healthProbesAreNeverBlocked() throws Exception {
        var box = box(1);
        box.strike("10.0.0.7", 10);
        var f = filter(box, 1_024, 1, 1);
        var r = new MockHttpServletRequest("GET", "/actuator/health/liveness");
        r.setRemoteAddr("10.0.0.7");
        var res = new MockHttpServletResponse();
        f.doFilter(r, res, OK);
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void exemptPartnerAddressesAreNeverBannedOrRateLimited() throws Exception {
        var f = filter(box(4, "196.201.0.0/16"), 1_024, 1, 10);
        for (int i = 0; i < 10; i++) {
            var res = new MockHttpServletResponse();
            f.doFilter(req("196.201.33.4", "/v1/rails/mtn/callbacks/x"), res, (rq, rs) -> ((HttpServletResponse) rs).setStatus(401));
            assertThat(res.getStatus()).isEqualTo(401);
        }
    }

    @Test
    void rejectionBodiesAreSmallJson() throws Exception {
        var f = filter(box(100), 10, 100, 10);
        var r = req("10.0.0.8", "/v1/x");
        r.setContent("x".repeat(100).getBytes(StandardCharsets.UTF_8));
        var res = new MockHttpServletResponse();
        f.doFilter(r, res, OK);
        assertThat(res.getContentType()).isEqualTo("application/json");
        assertThat(res.getContentAsString()).contains("payload_too_large").hasSizeLessThan(200);
    }
}
