package com.elepay.risk;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Algorithm 031: Promotion and referral abuse detection.
 * Analyzes referral relationships using graph cycle detection (DFS) and detects
 * Sybil accounts, shared-device collusion, and repeated promotional incentive exploitation.
 */
@Service
public class PromotionAbuseService {

    private final JdbcClient db;

    public PromotionAbuseService(JdbcClient db) {
        this.db = db;
    }

    public enum ReferralStatus {
        ALLOWED,
        FLAGGED_FOR_REVIEW,
        REJECTED_ABUSE
    }

    public record ReferralEdge(
            String referrerId,
            String refereeId,
            String campaignId,
            String deviceIdHash,
            String ipAddress,
            long rewardAmountMinorUnits
    ) {}

    public record ReferralAbuseResult(
            String referrerId,
            String refereeId,
            boolean isCircular,
            boolean isSybilCluster,
            int abuseScore,
            ReferralStatus status,
            List<String> detectedAnomalies
    ) {}

    @Transactional
    public ReferralAbuseResult evaluateAndRecord(ReferralEdge edge) {
        OffsetDateTime now = OffsetDateTime.now();
        List<String> anomalies = new ArrayList<>();
        int abuseScore = 0;
        boolean circular = false;
        boolean sybil = false;

        // 1. Direct self-referral
        if (edge.referrerId().equalsIgnoreCase(edge.refereeId())) {
            abuseScore = 100;
            circular = true;
            anomalies.add("DIRECT_SELF_REFERRAL");
        }

        // 2. Cycle detection in referral graph: does refereeId already point back to referrerId?
        if (!circular) {
            circular = detectsGraphCycle(edge.refereeId(), edge.referrerId(), new HashSet<>());
            if (circular) {
                abuseScore += 80;
                anomalies.add("CIRCULAR_REFERRAL_CYCLE_DETECTED");
            }
        }

        // 3. Shared device or IP collusion between referrer and referee
        if (edge.deviceIdHash() != null && !edge.deviceIdHash().isBlank()) {
            Integer deviceMatch = db.sql("""
                SELECT COUNT(*) FROM risk_referral_graph
                WHERE device_id_hash = :hash
                  AND (referrer_id = :referee OR referee_id = :referee OR referrer_id = :referrer)
            """)
                    .param("hash", edge.deviceIdHash())
                    .param("referee", edge.refereeId())
                    .param("referrer", edge.referrerId())
                    .query(Integer.class)
                    .single();

            if (deviceMatch != null && deviceMatch > 0) {
                abuseScore += 60;
                sybil = true;
                anomalies.add("SHARED_DEVICE_BETWEEN_REFERRER_AND_REFEREE");
            }
        }

        // 4. Sybil star-cluster detection: Referrer burst activity
        Integer recentReferrals = db.sql("""
            SELECT COUNT(*) FROM risk_referral_graph
            WHERE referrer_id = :referrer
              AND campaign_id = :campaign
              AND created_at >= :t24h
        """)
                .param("referrer", edge.referrerId())
                .param("campaign", edge.campaignId())
                .param("t24h", now.minusHours(24))
                .query(Integer.class)
                .single();

        int referrals24h = (recentReferrals != null ? recentReferrals : 0);
        if (referrals24h >= 15) {
            abuseScore += 45;
            sybil = true;
            anomalies.add("HIGH_VOLUME_SYBIL_BURST (" + referrals24h + " referrals in 24h)");
        } else if (referrals24h >= 8) {
            abuseScore += 20;
            anomalies.add("ELEVATED_REFERRAL_BURST (" + referrals24h + " in 24h)");
        }

        int finalScore = Math.min(100, abuseScore);
        ReferralStatus status;
        if (finalScore >= 80) {
            status = ReferralStatus.REJECTED_ABUSE;
        } else if (finalScore >= 40) {
            status = ReferralStatus.FLAGGED_FOR_REVIEW;
        } else {
            status = ReferralStatus.ALLOWED;
        }

        // Persist edge
        db.sql("""
            INSERT INTO risk_referral_graph (
                id, referrer_id, referee_id, campaign_id, device_id_hash,
                ip_address, reward_amount_minor_units, is_circular, is_sybil_cluster,
                abuse_score, status, created_at
            ) VALUES (
                :id, :referrer, :referee, :campaign, :deviceHash,
                :ip, :reward, :circular, :sybil,
                :score, :status, :now
            )
        """)
                .param("id", UUID.randomUUID())
                .param("referrer", edge.referrerId())
                .param("referee", edge.refereeId())
                .param("campaign", edge.campaignId())
                .param("deviceHash", edge.deviceIdHash())
                .param("ip", edge.ipAddress())
                .param("reward", edge.rewardAmountMinorUnits())
                .param("circular", circular)
                .param("sybil", sybil)
                .param("score", finalScore)
                .param("status", status.name())
                .param("now", now)
                .update();

        return new ReferralAbuseResult(
                edge.referrerId(),
                edge.refereeId(),
                circular,
                sybil,
                finalScore,
                status,
                anomalies
        );
    }

    /**
     * Traverses edges starting from startNode to see if targetNode is reachable (indicating a cycle).
     */
    private boolean detectsGraphCycle(String currentNode, String targetNode, Set<String> visited) {
        if (currentNode.equalsIgnoreCase(targetNode)) {
            return true;
        }
        if (!visited.add(currentNode)) {
            return false;
        }

        List<String> nextNodes = db.sql("""
            SELECT referee_id FROM risk_referral_graph
            WHERE referrer_id = :node
            LIMIT 20
        """)
                .param("node", currentNode)
                .query(String.class)
                .list();

        for (String next : nextNodes) {
            if (detectsGraphCycle(next, targetNode, visited)) {
                return true;
            }
        }
        return false;
    }
}
