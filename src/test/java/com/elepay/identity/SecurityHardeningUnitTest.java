package com.elepay.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.elepay.common.Audit;
import com.elepay.common.OpsQueue;
import com.elepay.common.SecretProtector;
import jakarta.servlet.FilterChain;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

class SecurityHardeningUnitTest {

    @Test
    void filterRegistrationBeansDisableAutoServletRegistration() {
        var config = new SecurityConfig();
        var edge = mock(EdgeProtectionFilter.class);
        var ks = mock(Stage1KillSwitchFilter.class);
        var rl = mock(RateLimitFilter.class);
        var rs = mock(RequestSigningFilter.class);

        assertThat(config.edgeProtectionFilterRegistration(edge).isEnabled()).isFalse();
        assertThat(config.stage1KillSwitchFilterRegistration(ks).isEnabled()).isFalse();
        assertThat(config.rateLimitFilterRegistration(rl).isEnabled()).isFalse();
        assertThat(config.requestSigningFilterRegistration(rs).isEnabled()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void killSwitchServiceFallsBackToModuleStatesOnRedisOutageWhileKeepingPayoutsFailClosed() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(KillSwitchService.REDIS_MODULE_PREFIX + "pos")).thenReturn("true");

        KillSwitchService ks = new KillSwitchService(null, null, null, redis, true, true, true, true, true, true);
        // Reset controlsUnreadable for unit test without DB
        ReflectionTestUtils.setField(ks, "controlsUnreadable", false);

        // First call observes Redis healthy
        assertThat(ks.isModuleEnabled("pos")).isTrue();

        // Simulate Redis outage
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("Redis connection refused"));

        // Enabled Stage 1 module falls back to moduleStates=true (no 404 self-DoS)
        assertThat(ks.isModuleEnabled("pos")).isTrue();
        // Disabled Stage 1 module stays false
        assertThat(ks.isModuleEnabled("lending")).isFalse();
        // Global payout kill switch remains fail-closed (true) on Redis outage after healthy observation
        assertThat(ks.isGlobalFrozen()).isTrue();
    }

    @Test
    void apiKeyAuthenticationMapsScopesToGrantedAuthorities() {
        var auth = new ApiKeyAuthentication(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("view", "collect"), "sec_123", "secret", List.of());

        assertThat(auth.getAuthorities())
                .contains(new SimpleGrantedAuthority("ROLE_merchant_api"))
                .contains(new SimpleGrantedAuthority("SCOPE_view"))
                .contains(new SimpleGrantedAuthority("SCOPE_collect"));
    }

    @Test
    void posDevicePathsAllowlistStrictlyEnforced() {
        assertThat(PosDevicePaths.allowed("POST", "/v1/pos/orders")).isTrue();
        assertThat(PosDevicePaths.allowed("GET", "/v1/pos/carts/123")).isTrue();
        assertThat(PosDevicePaths.allowed("POST", "/v1/pos/shifts/open")).isTrue();
        assertThat(PosDevicePaths.allowed("POST", "/v1/pos/cashiers/auth")).isTrue();
        assertThat(PosDevicePaths.allowed("POST", "/v1/pos/terminals/term-1/events")).isTrue();
        assertThat(PosDevicePaths.allowed("POST", "/v1/pos/terminals/term-1/nfc-tap")).isTrue();
        assertThat(PosDevicePaths.allowed("POST", "/v1/pos/sync")).isTrue();

        assertThat(PosDevicePaths.allowed("POST", "/v1/pos/terminals/pair")).isFalse();
        assertThat(PosDevicePaths.allowed("POST", "/v1/merchants/" + UUID.randomUUID() + "/withdrawals")).isFalse();
        assertThat(PosDevicePaths.allowed("GET", "/v1/ops/kill-switches")).isFalse();
    }

    @Test
    void apiKeyFilterEnforcesScopesPayoutNonGetAndPosTerminalPaths() throws Exception {
        ApiKeys apiKeys = mock(ApiKeys.class);
        var filter = new SecurityConfig.ApiKeyFilter(apiKeys, "");
        UUID merchantId = UUID.randomUUID();

        // 1. view-only secret key blocked on refund/collect mutations
        var viewOnlyAuth = new ApiKeyAuthentication(
                UUID.randomUUID(), merchantId, Set.of("view"), "sec_1", "secret", List.of());
        when(apiKeys.authenticate("sk_live_viewonly")).thenReturn(java.util.Optional.of(viewOnlyAuth));
        when(apiKeys.isIpAllowed(viewOnlyAuth, "10.1.1.1")).thenReturn(true);

        var req1 = new MockHttpServletRequest("POST", "/v1/merchants/" + merchantId + "/refunds");
        req1.setRemoteAddr("10.1.1.1");
        req1.addHeader("X-Elepay-Api-Key", "sk_live_viewonly");
        var res1 = new MockHttpServletResponse();
        filter.doFilter(req1, res1, new MockFilterChain());
        assertThat(res1.getStatus()).isEqualTo(403);
        assertThat(res1.getContentAsString()).contains("insufficient_scope");

        // 2. secret key blocked on PATCH/DELETE payout routes
        var fullSecretAuth = new ApiKeyAuthentication(
                UUID.randomUUID(), merchantId, Set.of("view", "collect", "refund"), "sec_2", "secret", List.of());
        when(apiKeys.authenticate("sk_live_full")).thenReturn(java.util.Optional.of(fullSecretAuth));
        when(apiKeys.isIpAllowed(fullSecretAuth, "10.1.1.1")).thenReturn(true);

        var req2 = new MockHttpServletRequest("PATCH", "/v1/merchants/" + merchantId + "/withdrawals/123");
        req2.setRemoteAddr("10.1.1.1");
        req2.addHeader("X-Elepay-Api-Key", "sk_live_full");
        var res2 = new MockHttpServletResponse();
        filter.doFilter(req2, res2, new MockFilterChain());
        assertThat(res2.getStatus()).isEqualTo(403);
        assertThat(res2.getContentAsString()).contains("payout_key_required");

        // 3. pos_terminal key blocked outside PosDevicePaths
        var posAuth = new ApiKeyAuthentication(
                UUID.randomUUID(), merchantId, Set.of("collect", "view"), "sec_3", "pos_terminal", List.of());
        when(apiKeys.authenticate("pos_live_12345678_abcdef")).thenReturn(java.util.Optional.of(posAuth));
        when(apiKeys.isIpAllowed(posAuth, "10.1.1.1")).thenReturn(true);

        var req3 = new MockHttpServletRequest("POST", "/v1/merchants/" + merchantId + "/refunds");
        req3.setRemoteAddr("10.1.1.1");
        req3.addHeader("X-Elepay-Api-Key", "pos_live_12345678_abcdef");
        var res3 = new MockHttpServletResponse();
        filter.doFilter(req3, res3, new MockFilterChain());
        assertThat(res3.getStatus()).isEqualTo(403);
        assertThat(res3.getContentAsString()).contains("pos_terminal_key_restricted");
    }

    @Test
    void edgeProtectionFilterUsesClientIpResolverForTrustedProxy() throws Exception {
        AbusePenaltyBox box = new AbusePenaltyBox(true, 5, 300L, List.of(), null);
        var filter = new EdgeProtectionFilter(true, 1024, 2048, 100, 100, 10, 0, box, null, null, "10.0.0.0/8");

        var req = new MockHttpServletRequest("GET", "/v1/checkouts");
        req.setRemoteAddr("10.0.0.5");
        req.addHeader("X-Forwarded-For", "198.51.100.77, 10.0.0.5");
        var res = new MockHttpServletResponse();

        FilterChain chain = (r, s) -> ((MockHttpServletResponse) s).setStatus(401);
        filter.doFilter(req, res, chain);
        filter.doFilter(req, res, chain); // 2 * 4 = 8 strikes >= 5 threshold

        // Real client IP is banned, NOT the LB IP 10.0.0.5
        assertThat(box.banRemainingSeconds("198.51.100.77")).isGreaterThan(0);
        assertThat(box.banRemainingSeconds("10.0.0.5")).isZero();
    }

    @Test
    void requestSigningFilterRequiresV2ForPrivilegedOpsAndAdminMutationsWhenEnforced() throws Exception {
        String secret = "platform-secret-at-least-32-chars-long";
        var filter = new RequestSigningFilter(secret, true, new DistributedNonceStore(), null);
        ReflectionTestUtils.setField(filter, "requireV2", true);

        var req = new MockHttpServletRequest("POST", "/v1/ops/kill-switch/global");
        var res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("missing_signature");
    }

    @Test
    void distributedSessionServiceDoesNotSwallowRevocationFailures() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.delete(anyString())).thenThrow(new RuntimeException("Redis down"));
        var service = new DistributedSessionService(null, redis, new com.fasterxml.jackson.databind.ObjectMapper());

        assertThatThrownBy(() -> service.revokeSession("sess_abc"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Redis");
    }
}
