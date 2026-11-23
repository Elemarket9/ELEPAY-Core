package com.elepay.risk;

import com.elepay.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MultiWindowVelocityTest extends PostgresTestBase {

    @Autowired
    private MultiWindowVelocityEngine velocityEngine;

    @Test
    @DisplayName("Multi-window velocity: single normal transaction allowed")
    void testSingleTransactionAllowed() {
        String phone = "+23324" + (1000000 + (int)(Math.random() * 8999999));
        var eval = velocityEngine.recordAndEvaluate(
                MultiWindowVelocityEngine.DimensionType.PHONE,
                phone,
                5000L, // 50.00 GHS
                OffsetDateTime.now()
        );

        assertThat(eval).isNotNull();
        assertThat(eval.decision()).isEqualTo(RiskDecision.ALLOW);
        assertThat(eval.compositeScore()).isLessThan(50.0);
        assertThat(eval.windowSummaries()).hasSize(5);
    }

    @Test
    @DisplayName("Multi-window velocity: rapid burst triggers reject")
    void testRapidBurstRejection() {
        String cardFingerprint = "CARD-" + UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();

        // Send 10 rapid transactions in 1 minute window (limit is 5)
        MultiWindowVelocityEngine.VelocityEvaluation lastEval = null;
        for (int i = 0; i < 10; i++) {
            lastEval = velocityEngine.recordAndEvaluate(
                    MultiWindowVelocityEngine.DimensionType.CARD_FINGERPRINT,
                    cardFingerprint,
                    30_000L, // 300.00 GHS each -> 3,000 GHS (limit is 2,000 GHS)
                    now
            );
        }

        assertThat(lastEval).isNotNull();
        assertThat(lastEval.decision()).isEqualTo(RiskDecision.REJECT);
        assertThat(lastEval.triggeredRules()).isNotEmpty();
        assertThat(lastEval.compositeScore()).isGreaterThanOrEqualTo(100.0);
    }
}
