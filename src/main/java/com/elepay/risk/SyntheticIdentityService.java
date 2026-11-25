package com.elepay.risk;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Algorithm 033: Synthetic identity detection.
 * Identifies inconsistent identity evidence, fragmented PII reuse across disparate profiles,
 * and recycled phone/ID numbers without claiming uncalibrated certainty.
 */
@Service
public class SyntheticIdentityService {

    private final JdbcClient db;
    private final ObjectMapper objectMapper;

    public SyntheticIdentityService(JdbcClient db) {
        this(db, new ObjectMapper());
    }

    @Autowired
    public SyntheticIdentityService(JdbcClient db, @Autowired(required = false) ObjectMapper objectMapper) {
        this.db = db;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public record IdentityProfile(
            String actorId,
            String nationalIdHash,
            String tinHash,
            String phoneNumber,
            String fullName,
            LocalDate dateOfBirth,
            String addressHash,
            boolean isNewNationalIdIssue
    ) {}

    public record SyntheticIdentityResult(
            String actorId,
            int syntheticRiskScore,
            String riskVerdict,
            List<String> inconsistencies,
            OffsetDateTime assessedAt
    ) {}

    @Transactional
    public SyntheticIdentityResult assessIdentity(IdentityProfile profile) {
        OffsetDateTime now = OffsetDateTime.now();
        List<String> inconsistencies = new ArrayList<>();
        int score = 0;

        // 1. Cross-reference National ID: Is this national ID linked to a different name or DOB?
        var conflictingNid = db.sql("""
            SELECT full_name, date_of_birth
            FROM risk_synthetic_identities
            WHERE national_id_hash = :nid
              AND actor_id <> :actorId
            LIMIT 5
        """)
                .param("nid", profile.nationalIdHash())
                .param("actorId", profile.actorId())
                .query((rs, rowNum) -> new Object() {
                    String name = rs.getString("full_name");
                    LocalDate dob = rs.getObject("date_of_birth", LocalDate.class);
                })
                .list();

        for (var conf : conflictingNid) {
            if (!conf.name.equalsIgnoreCase(profile.fullName())) {
                score += 45;
                inconsistencies.add("NATIONAL_ID_NAME_MISMATCH: Same national ID registered to '" + conf.name + "'");
            }
            if (conf.dob != null && !conf.dob.equals(profile.dateOfBirth())) {
                score += 40;
                inconsistencies.add("NATIONAL_ID_DOB_MISMATCH: Same national ID with divergent date of birth");
            }
        }

        // 2. Cross-reference Phone Number: Is this phone number associated with multiple disparate national IDs?
        Integer distinctNidsOnPhone = db.sql("""
            SELECT COUNT(DISTINCT national_id_hash)
            FROM risk_synthetic_identities
            WHERE phone_number = :phone
              AND national_id_hash <> :nid
        """)
                .param("phone", profile.phoneNumber())
                .param("nid", profile.nationalIdHash())
                .query(Integer.class)
                .single();

        int otherNids = (distinctNidsOnPhone != null ? distinctNidsOnPhone : 0);
        if (otherNids >= 2) {
            score += 35;
            inconsistencies.add("PHONE_NUMBER_MULTIPLEXING: Phone number linked to " + (otherNids + 1) + " distinct national IDs");
        } else if (otherNids == 1) {
            score += 15;
            inconsistencies.add("PHONE_NUMBER_SHARED: Phone number seen on another national ID");
        }

        // 3. Newly issued ID on older adult profile and invalid birth dates
        LocalDate today = LocalDate.now();
        if (profile.dateOfBirth() != null && profile.dateOfBirth().isAfter(today)) {
            score += 60;
            inconsistencies.add("FUTURE_DATE_OF_BIRTH: Date of birth is in the future");
        }
        int ageYears = profile.dateOfBirth() != null && !profile.dateOfBirth().isAfter(today)
                ? Period.between(profile.dateOfBirth(), today).getYears()
                : 0;
        if (profile.isNewNationalIdIssue() && ageYears >= 35) {
            score += 15;
            inconsistencies.add("NEW_ID_ON_MATURE_PROFILE: First-time national ID issuance on mature adult");
        }

        int finalScore = Math.min(100, score);
        String verdict;
        if (finalScore >= 70) {
            verdict = "HIGH_RISK_SYNTHETIC";
        } else if (finalScore >= 35) {
            verdict = "SUSPICIOUS";
        } else {
            verdict = "NORMAL";
        }

        String jsonInconsistencies;
        try {
            jsonInconsistencies = objectMapper.writeValueAsString(inconsistencies);
        } catch (JacksonException e) {
            jsonInconsistencies = "[]";
        }

        // Persist
        db.sql("""
            INSERT INTO risk_synthetic_identities (
                id, actor_id, national_id_hash, tin_hash, phone_number,
                full_name, date_of_birth, address_hash, inconsistencies_json,
                synthetic_risk_score, risk_verdict, assessed_at
            ) VALUES (
                :id, :actorId, :nid, :tin, :phone,
                :name, :dob, :addr, :inconsistencies,
                :score, :verdict, :now
            )
        """)
                .param("id", UUID.randomUUID())
                .param("actorId", profile.actorId())
                .param("nid", profile.nationalIdHash())
                .param("tin", profile.tinHash())
                .param("phone", profile.phoneNumber())
                .param("name", profile.fullName())
                .param("dob", profile.dateOfBirth())
                .param("addr", profile.addressHash())
                .param("inconsistencies", jsonInconsistencies)
                .param("score", finalScore)
                .param("verdict", verdict)
                .param("now", now)
                .update();

        return new SyntheticIdentityResult(
                profile.actorId(),
                finalScore,
                verdict,
                inconsistencies,
                now
        );
    }
}
