package com.elepay.agents;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentOnboardingService {

    private final JdbcClient db;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AgentOnboardingService(JdbcClient db) {
        this.db = db;
    }

    public record AgentRegistration(
            String businessName,
            String proprietorName,
            String nationalId,
            String phone,
            String address,
            String city,
            String region,
            Double latitude,
            Double longitude,
            String initialPin,
            String kycTier
    ) {}

    public record AgentProfile(
            UUID id,
            String agentCode,
            String businessName,
            String proprietorName,
            String nationalId,
            String phone,
            String address,
            String city,
            String region,
            Double latitude,
            Double longitude,
            String kycTier,
            long maxSingleCashInPesewas,
            long maxSingleCashOutPesewas,
            long dailyVolumeLimitPesewas,
            String status,
            OffsetDateTime createdAt
    ) {}

    @Transactional
    public AgentProfile registerAgent(AgentRegistration reg) {
        if (reg.initialPin() == null || reg.initialPin().length() < 4) {
            throw new IllegalArgumentException("Agent PIN must be at least 4 digits");
        }

        String tier = (reg.kycTier() != null && !reg.kycTier().isBlank()) ? reg.kycTier().toUpperCase() : "TIER_1";

        long maxCashIn;
        long maxCashOut;
        long dailyLimit;

        switch (tier) {
            case "TIER_3" -> {
                maxCashIn = 2000000L;    // 20,000 GHS
                maxCashOut = 2000000L;   // 20,000 GHS
                dailyLimit = 20000000L;  // 200,000 GHS
            }
            case "TIER_2" -> {
                maxCashIn = 500000L;     // 5,000 GHS
                maxCashOut = 500000L;    // 5,000 GHS
                dailyLimit = 5000000L;   // 50,000 GHS
            }
            default -> { // TIER_1
                maxCashIn = 100000L;     // 1,000 GHS
                maxCashOut = 100000L;    // 1,000 GHS
                dailyLimit = 1000000L;   // 10,000 GHS
            }
        }

        UUID agentId = UUID.randomUUID();
        String agentCode = "AGT-" + agentId.toString().substring(0, 8).toUpperCase();
        String pinHash = passwordEncoder.encode(reg.initialPin());

        db.sql("""
            INSERT INTO agent_profile
            (id, agent_code, business_name, proprietor_name, national_id, phone,
             address, city, region, latitude, longitude, kyc_tier,
             max_single_cash_in_pesewas, max_single_cash_out_pesewas,
             daily_volume_limit_pesewas, pin_hash, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
        """).params(agentId, agentCode, reg.businessName(), reg.proprietorName(),
                reg.nationalId(), reg.phone(), reg.address(), reg.city(), reg.region(),
                reg.latitude(), reg.longitude(), tier, maxCashIn, maxCashOut, dailyLimit, pinHash)
        .update();

        // Create initial agent float account
        UUID floatId = UUID.randomUUID();
        db.sql("""
            INSERT INTO agent_float_account
            (id, agent_id, available_float_pesewas, reserved_float_pesewas,
             accrued_commission_pesewas, total_lifetime_commission_pesewas, currency)
            VALUES (?, ?, 0, 0, 0, 0, 'GHS')
        """).params(floatId, agentId)
        .update();

        return getAgentById(agentId).orElseThrow();
    }

    public boolean verifyPin(UUID agentId, String rawPin) {
        String pinHash = db.sql("SELECT pin_hash FROM agent_profile WHERE id = ?")
                .param(agentId)
                .query(String.class)
                .optional()
                .orElse(null);
        if (pinHash == null) return false;
        return passwordEncoder.matches(rawPin, pinHash);
    }

    public Optional<AgentProfile> getAgentById(UUID agentId) {
        return db.sql("""
            SELECT id, agent_code, business_name, proprietor_name, national_id, phone,
                   address, city, region, latitude, longitude, kyc_tier,
                   max_single_cash_in_pesewas, max_single_cash_out_pesewas,
                   daily_volume_limit_pesewas, status, created_at
            FROM agent_profile
            WHERE id = ?
        """).param(agentId)
        .query((rs, rowNum) -> new AgentProfile(
                rs.getObject("id", UUID.class),
                rs.getString("agent_code"),
                rs.getString("business_name"),
                rs.getString("proprietor_name"),
                rs.getString("national_id"),
                rs.getString("phone"),
                rs.getString("address"),
                rs.getString("city"),
                rs.getString("region"),
                rs.getBigDecimal("latitude") != null ? rs.getBigDecimal("latitude").doubleValue() : null,
                rs.getBigDecimal("longitude") != null ? rs.getBigDecimal("longitude").doubleValue() : null,
                rs.getString("kyc_tier"),
                rs.getLong("max_single_cash_in_pesewas"),
                rs.getLong("max_single_cash_out_pesewas"),
                rs.getLong("daily_volume_limit_pesewas"),
                rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class)
        )).optional();
    }

    public Optional<AgentProfile> getAgentByCode(String code) {
        return db.sql("""
            SELECT id, agent_code, business_name, proprietor_name, national_id, phone,
                   address, city, region, latitude, longitude, kyc_tier,
                   max_single_cash_in_pesewas, max_single_cash_out_pesewas,
                   daily_volume_limit_pesewas, status, created_at
            FROM agent_profile
            WHERE agent_code = ?
        """).param(code)
        .query((rs, rowNum) -> new AgentProfile(
                rs.getObject("id", UUID.class),
                rs.getString("agent_code"),
                rs.getString("business_name"),
                rs.getString("proprietor_name"),
                rs.getString("national_id"),
                rs.getString("phone"),
                rs.getString("address"),
                rs.getString("city"),
                rs.getString("region"),
                rs.getBigDecimal("latitude") != null ? rs.getBigDecimal("latitude").doubleValue() : null,
                rs.getBigDecimal("longitude") != null ? rs.getBigDecimal("longitude").doubleValue() : null,
                rs.getString("kyc_tier"),
                rs.getLong("max_single_cash_in_pesewas"),
                rs.getLong("max_single_cash_out_pesewas"),
                rs.getLong("daily_volume_limit_pesewas"),
                rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class)
        )).optional();
    }
}
