package com.elepay.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.elepay.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

@DisplayName("Algorithm 031: Promotion and Referral Abuse Detection")
class PromoAbuseServiceTest extends PostgresTestBase {

    @Autowired PromotionAbuseService promotionAbuseService;
    @Autowired PromoAbuseService promoAbuseService;

    @Test
    @DisplayName("Algorithm 031: Self-referral is immediately flagged and rejected")
    void testSelfReferralRejected() {
        String user = "user-" + UUID.randomUUID();
        var edge = new PromotionAbuseService.ReferralEdge(user, user, "PROMO2026", "dev-hash-1", "127.0.0.1", 1000);
        var result = promotionAbuseService.evaluateAndRecord(edge);

        assertThat(result.isCircular()).isTrue();
        assertThat(result.status()).isEqualTo(PromotionAbuseService.ReferralStatus.REJECTED_ABUSE);
        assertThat(result.abuseScore()).isGreaterThanOrEqualTo(80);
    }

    @Test
    @DisplayName("Algorithm 031: Circular referral chain (A -> B -> C -> A) is detected via recursive CTE without N+1 queries")
    void testCircularReferralCycleDetected() {
        String userA = "user-cycle-A-" + UUID.randomUUID();
        String userB = "user-cycle-B-" + UUID.randomUUID();
        String userC = "user-cycle-C-" + UUID.randomUUID();

        // Step 1: A refers B
        var edge1 = new PromotionAbuseService.ReferralEdge(userA, userB, "PROMO2026", "dev-A", "10.0.0.1", 1000);
        var res1 = promotionAbuseService.evaluateAndRecord(edge1);
        assertThat(res1.isCircular()).isFalse();

        // Step 2: B refers C
        var edge2 = new PromotionAbuseService.ReferralEdge(userB, userC, "PROMO2026", "dev-B", "10.0.0.2", 1000);
        var res2 = promotionAbuseService.evaluateAndRecord(edge2);
        assertThat(res2.isCircular()).isFalse();

        // Step 3: C refers A (Completing the cycle A -> B -> C -> A!)
        var edge3 = new PromotionAbuseService.ReferralEdge(userC, userA, "PROMO2026", "dev-C", "10.0.0.3", 1000);
        var res3 = promoAbuseService.evaluateAndRecord(edge3);
        assertThat(res3.isCircular()).isTrue();
        assertThat(res3.status()).isEqualTo(PromotionAbuseService.ReferralStatus.REJECTED_ABUSE);
        assertThat(res3.detectedAnomalies()).contains("CIRCULAR_REFERRAL_CYCLE_DETECTED");
    }

    @Test
    @DisplayName("Algorithm 031: Shared device between referrer and referee is detected as Sybil cluster")
    void testSharedDeviceSybilCluster() {
        String devHash = "shared-device-" + UUID.randomUUID();
        String user1 = "user-dev1-" + UUID.randomUUID();
        String user2 = "user-dev2-" + UUID.randomUUID();

        // Register edge from user1 with devHash
        promotionAbuseService.evaluateAndRecord(new PromotionAbuseService.ReferralEdge(
                user1, "someone-else-" + UUID.randomUUID(), "PROMO2026", devHash, "10.0.0.1", 500));

        // Now user2 refers user1 using the exact same device hash
        var edgeCollusion = new PromotionAbuseService.ReferralEdge(user2, user1, "PROMO2026", devHash, "10.0.0.1", 500);
        var resCollusion = promotionAbuseService.evaluateAndRecord(edgeCollusion);

        assertThat(resCollusion.isSybilCluster()).isTrue();
        assertThat(resCollusion.detectedAnomalies()).contains("SHARED_DEVICE_BETWEEN_REFERRER_AND_REFEREE");
    }
}
