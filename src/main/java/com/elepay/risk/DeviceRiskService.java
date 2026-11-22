package com.elepay.risk;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Algorithm 025: Device risk signals.
 * Collects privacy-conscious client fingerprints, calculates confidence score vs risk score,
 * and detects multi-accounting / device anomaly signals.
 */
@Service
public class DeviceRiskService {

    private final JdbcClient db;

    public DeviceRiskService(JdbcClient db) {
        this.db = db;
    }

    public record DeviceSignals(
            String deviceIdHash,
            String actorId,
            String ipAddress,
            String userAgent,
            String platform,
            String os,
            boolean isEmulator,
            boolean isRooted,
            boolean isVpnProxy
    ) {}

    public record DeviceRiskResult(
            String deviceIdHash,
            int confidenceScore,
            int riskScore,
            boolean isMultiAccount,
            int distinctAccountsLast24h,
            boolean isNewDeviceForActor,
            List<String> riskFactors
    ) {}

    @Transactional
    public DeviceRiskResult evaluateAndRecord(DeviceSignals signals) {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime t24h = now.minusHours(24);

        // 1. Calculate Confidence Score (0-100)
        // High confidence requires robust hardware/canvas hash and clear platform headers
        int confidence = 40;
        if (signals.deviceIdHash() != null && signals.deviceIdHash().length() >= 32) {
            confidence += 30;
        }
        if (signals.userAgent() != null && !signals.userAgent().isBlank()) {
            confidence += 15;
        }
        if (signals.platform() != null && !signals.platform().isBlank()) {
            confidence += 15;
        }
        confidence = Math.min(100, confidence);

        // 2. Multi-accounting check: How many distinct actors have used this device in 24h?
        Integer distinctActors = db.sql("""
            SELECT COUNT(DISTINCT actor_id)
            FROM risk_device_fingerprints
            WHERE device_id_hash = :hash
              AND last_seen_at >= :t24h
        """)
                .param("hash", signals.deviceIdHash())
                .param("t24h", t24h)
                .query(Integer.class)
                .single();

        int accounts24h = (distinctActors == null ? 0 : distinctActors);

        // Check if device is new for this actor
        Integer seenCount = db.sql("""
            SELECT seen_count
            FROM risk_device_fingerprints
            WHERE device_id_hash = :hash
              AND actor_id = :actorId
        """)
                .param("hash", signals.deviceIdHash())
                .param("actorId", signals.actorId())
                .query(Integer.class)
                .optional()
                .orElse(0);

        boolean isNewForActor = (seenCount == 0);
        int totalAccounts = accounts24h + (isNewForActor ? 1 : 0);

        // 3. Calculate Risk Score (0-100) and risk factors
        int risk = 5; // baseline
        List<String> factors = new ArrayList<>();

        if (signals.isEmulator()) {
            risk += 40;
            factors.add("DEVICE_EMULATOR_DETECTED");
        }
        if (signals.isRooted()) {
            risk += 30;
            factors.add("DEVICE_ROOTED_OR_JAILBROKEN");
        }
        if (signals.isVpnProxy()) {
            risk += 25;
            factors.add("ANONYMOUS_VPN_OR_PROXY");
        }
        if (totalAccounts >= 3) {
            risk += 35;
            factors.add("MULTI_ACCOUNTING_CLUSTER_DETECTED (" + totalAccounts + " accounts)");
        } else if (totalAccounts == 2 && isNewForActor) {
            risk += 15;
            factors.add("SHARED_DEVICE_WARNING");
        }
        if (isNewForActor) {
            risk += 10;
            factors.add("NEW_DEVICE_FOR_ACCOUNT");
        }

        risk = Math.min(100, risk);
        boolean isMultiAccount = totalAccounts >= 3;

        // 4. Upsert fingerprint
        db.sql("""
            INSERT INTO risk_device_fingerprints (
                id, device_id_hash, actor_id, ip_address, user_agent, platform, os,
                is_emulator, is_rooted, is_vpn_proxy, confidence_score, risk_score,
                first_seen_at, last_seen_at, seen_count
            ) VALUES (
                :id, :hash, :actorId, :ip, :ua, :platform, :os,
                :emulator, :rooted, :vpn, :confidence, :risk,
                :now, :now, 1
            )
            ON CONFLICT (device_id_hash, actor_id) DO UPDATE SET
                ip_address = EXCLUDED.ip_address,
                user_agent = EXCLUDED.user_agent,
                confidence_score = EXCLUDED.confidence_score,
                risk_score = EXCLUDED.risk_score,
                last_seen_at = EXCLUDED.last_seen_at,
                seen_count = risk_device_fingerprints.seen_count + 1
        """)
                .param("id", UUID.randomUUID())
                .param("hash", signals.deviceIdHash())
                .param("actorId", signals.actorId())
                .param("ip", signals.ipAddress())
                .param("ua", signals.userAgent())
                .param("platform", signals.platform())
                .param("os", signals.os())
                .param("emulator", signals.isEmulator())
                .param("rooted", signals.isRooted())
                .param("vpn", signals.isVpnProxy())
                .param("confidence", confidence)
                .param("risk", risk)
                .param("now", now)
                .update();

        return new DeviceRiskResult(
                signals.deviceIdHash(),
                confidence,
                risk,
                isMultiAccount,
                accounts24h,
                isNewForActor,
                factors
        );
    }
}
