package com.elepay.enterprise.connectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elepay.PostgresTestBase;
import com.elepay.common.ApiException;
import com.elepay.enterprise.wallet.*;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Enterprise Universal Connector Framework Automated Verification Suite.
 */
class EnterpriseConnectorsTest extends PostgresTestBase {

    /** Isolated test connector wrapping an in-memory wallet double. */
    private ResilientWalletConnector walletConnector;

    private InMemoryModeAWalletSimulator walletSimulator;

    @Autowired
    private ConnectorWebhookSigner webhookSigner;

    @Autowired
    private SftpReconHandler reconHandler;

    @Autowired
    private EnterpriseTokenService tokenService;

    @Autowired
    private HmacWebhookVerifier webhookVerifier;

    private String testSecret;

    @BeforeEach
    void setUp() {
        testSecret = "marketplace_webhook_secret_key_2026_gh";
        walletSimulator = new InMemoryModeAWalletSimulator(tokenService, webhookVerifier);
        walletConnector = new ResilientWalletConnector(walletSimulator, webhookSigner, reconHandler, 0L);
    }

    @Test
    @DisplayName("Circuit Breaker Lifecycle: CLOSED -> failure threshold -> OPEN -> HALF_OPEN -> CLOSED")
    void testCircuitBreakerStateTransitions() {
        MutableClock mutableClock = new MutableClock(Instant.now());
        CircuitBreaker cb = new CircuitBreaker("test-cb", 3, 5000L, mutableClock);

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(cb.allowRequest()).isTrue();

        // 1st and 2nd failure: remains CLOSED
        cb.recordFailure();
        cb.recordFailure();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // 3rd failure: trips to OPEN
        cb.recordFailure();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(cb.allowRequest()).isFalse();

        // Advance clock past 5000ms cooldown
        mutableClock.advanceMillis(5100L);

        // State moves to HALF_OPEN
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(cb.allowRequest()).isTrue();

        // Record 2 consecutive successes to re-close
        cb.recordSuccess();
        cb.recordSuccess();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(cb.allowRequest()).isTrue();
    }

    @Test
    @DisplayName("Rate Limiter: Allows within capacity and limits when exhausted")
    void testRateLimiterTokenBucket() {
        RateLimiter limiter = new RateLimiter("test-limiter", 3L); // 3 req/min

        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();

        // 4th request in same window is rejected
        assertThat(limiter.tryAcquire()).isFalse();
    }

    @Test
    @DisplayName("Webhook HMAC-SHA256 Signing & Verification: Enforces signatures and rejects timestamp drift")
    void testWebhookSignatureAndDriftProtection() {
        long now = Instant.now().getEpochSecond();
        String payload = "{\"event\":\"PAYMENT_CAPTURED\",\"orderRef\":\"MARKETPLACE-GH-9988\"}";

        String signature = webhookSigner.sign(testSecret, now, payload);
        assertThat(signature).isNotNull().isNotBlank();

        // Valid signature verifies
        boolean valid = webhookSigner.verify(testSecret, now, payload, signature);
        assertThat(valid).isTrue();

        // Tampered payload fails
        boolean tampered = webhookSigner.verify(testSecret, now, payload + " ", signature);
        assertThat(tampered).isFalse();

        // Wrong secret fails
        boolean wrongSecret = webhookSigner.verify("wrong_secret", now, payload, signature);
        assertThat(wrongSecret).isFalse();

        // Replay attack with expired timestamp (> 300 seconds drift) fails
        long staleTimestamp = now - 305L;
        String staleSig = webhookSigner.sign(testSecret, staleTimestamp, payload);
        boolean staleValid = webhookSigner.verify(testSecret, staleTimestamp, payload, staleSig);
        assertThat(staleValid).isFalse();
    }

    @Test
    @DisplayName("SFTP Continuous Reconciliation: Generates and cryptographically validates batch files")
    void testSftpBatchReconciliationFileGenerationAndParsing() {
        String batchId = "RECON-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        LocalDate today = LocalDate.now();
        UUID m1 = UUID.randomUUID();
        UUID m2 = UUID.randomUUID();

        List<SftpReconRecord> records = List.of(
                new SftpReconRecord("TX-1", "EXT-1", m1, "PAYMENT", 30_000L, 27_000L, 3_000L, "SETTLED", today),
                new SftpReconRecord("TX-2", "EXT-2", m2, "PAYMENT", 25_000L, 22_000L, 3_000L, "SETTLED", today),
                new SftpReconRecord("TX-3", "EXT-3", m1, "REFUND", 5_000L, 4_500L, 500L, "REFUNDED", today)
        );

        String fileContent = reconHandler.generateReconFile(batchId, today, "GHS", records);
        assertThat(fileContent).contains("HEADER|" + batchId);
        assertThat(fileContent).contains("TRAILER|3|60000|53500|6500|");

        // Parse and validate
        SftpReconBatch batch = reconHandler.parseReconFile(fileContent);
        assertThat(batch).isNotNull();
        assertThat(batch.batchId()).isEqualTo(batchId);
        assertThat(batch.totalRecords()).isEqualTo(3);
        assertThat(batch.totalGrossPesewas()).isEqualTo(60_000L);
        assertThat(batch.totalNetPesewas()).isEqualTo(53_500L);
        assertThat(batch.totalFeePesewas()).isEqualTo(6_500L);
        assertThat(batch.records()).hasSize(3);

        // Corrupted file fails checksum verification
        String corrupted = fileContent.replace("60000", "60001");
        assertThatThrownBy(() -> reconHandler.parseReconFile(corrupted))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("test wallet Connector Adapter: Integrates wallet Mode A operations and connector framework")
    void testResilientWalletConnectorIntegration() {
        assertThat(walletConnector.getConnectorId()).isEqualTo("test-wallet-v1");
        assertThat(walletConnector.getConnectorType()).isEqualTo("EXTERNAL_WALLET");
        assertThat(walletConnector.getCircuitBreaker()).isNotNull();
        assertThat(walletConnector.getRateLimiter()).isNotNull();

        // Seed customer balance and test query
        String customerId = "marketplace_cust_test_01";
        walletSimulator.seedCustomerBalance(customerId, 200_000L); // GH₵ 2,000.00

        var balanceQuery = walletConnector.queryWalletBalance(new ExternalBalanceQueryRequest(
                "partner_marketplace", customerId, "GHS"
        ));
        assertThat(balanceQuery.availableBalanceMinorUnits()).isEqualTo(200_000L);
    }

    /** Mutable clock for deterministic testing of timeouts. */
    private static class MutableClock extends Clock {
        private Instant current;
        private final ZoneId zone = ZoneId.of("UTC");

        public MutableClock(Instant initial) {
            this.current = initial;
        }

        public void advanceMillis(long millis) {
            this.current = this.current.plusMillis(millis);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
