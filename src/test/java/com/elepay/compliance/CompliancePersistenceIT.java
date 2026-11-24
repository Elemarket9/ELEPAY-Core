package com.elepay.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elepay.PostgresTestBase;
import com.elepay.TestApi;
import com.elepay.common.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class CompliancePersistenceIT extends PostgresTestBase {

    @Autowired
    private JdbcClient db;

    @Test
    void sanctionsAndPepScreeningReadFromPostgresAcrossRestarts() {
        UUID sanctionId = UUID.randomUUID();
        String sanctionedName = "Kofi Darko Sanctioned " + sanctionId.toString().substring(0, 6);
        db.sql("""
                INSERT INTO sanctions_list (id, list_code, version, entity_name, entity_type, soundex_code, nationality, remarks, active)
                VALUES (:id, 'FIC_GHANA', '2026.1', :name, 'INDIVIDUAL', :sdx, 'GH', 'Seeded directly in DB', true)""")
                .param("id", sanctionId)
                .param("name", sanctionedName)
                .param("sdx", SanctionsScreeningService.computeSoundex(sanctionedName))
                .update();

        SanctionsScreeningService freshSanctions = new SanctionsScreeningService(db);
        var matches = freshSanctions.screenName(sanctionedName);
        assertThat(matches).anyMatch(m -> m.matchedEntry().id().equals(sanctionId));

        UUID pepId = UUID.randomUUID();
        String pepName = "Ama Serwaa Pep " + pepId.toString().substring(0, 6);
        db.sql("""
                INSERT INTO pep_directory (id, full_name, position_title, jurisdiction, pep_tier, soundex_code, active)
                VALUES (:id, :name, 'Minister', 'GH', 'TIER_1', :sdx, true)""")
                .param("id", pepId)
                .param("name", pepName)
                .param("sdx", SanctionsScreeningService.computeSoundex(pepName))
                .update();

        PepScreeningService freshPep = new PepScreeningService(db);
        var pepCase = freshPep.screenCustomer(UUID.randomUUID(), pepName);
        assertThat(pepCase).isNotNull();
        assertThat(pepCase.matchedPepId()).isEqualTo(pepId);
    }

    @Test
    void retentionLegalHoldPersistsAcrossRestartsAndRedactionUpdatesCustomerTable() {
        UUID customerId = UUID.randomUUID();
        UUID officerId = UUID.randomUUID();
        db.sql("""
                INSERT INTO customer (id, subject, phone, display_name, email, kyc_tier, status)
                VALUES (:id, :sub, :phone, 'Kwame Real Name', 'kwame@example.com', 1, 'active')""")
                .param("id", customerId)
                .param("sub", "sub-" + customerId)
                .param("phone", TestApi.uniquePhone())
                .update();

        RetentionRedactionService writer = new RetentionRedactionService(db);
        var hold = writer.applyLegalHold(customerId, "CASE-99", "Subpoena", officerId);

        // Fresh instance simulates process restart
        RetentionRedactionService reader = new RetentionRedactionService(db);
        assertThat(reader.hasActiveLegalHold(customerId)).isTrue();

        Instant oldClosed = Instant.now().minus(Duration.ofDays(3650));
        assertThatThrownBy(() -> reader.redactCustomerPii(customerId, oldClosed, officerId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("active legal hold");

        reader.releaseLegalHold(hold.holdId());
        assertThat(reader.hasActiveLegalHold(customerId)).isFalse();

        var result = reader.redactCustomerPii(customerId, oldClosed, officerId);
        assertThat(result.successful()).isTrue();

        String displayName = db.sql("SELECT display_name FROM customer WHERE id = :id").param("id", customerId).query(String.class).single();
        String email = db.sql("SELECT email FROM customer WHERE id = :id").param("id", customerId).query(String.class).optional().orElse(null);
        String status = db.sql("SELECT status FROM customer WHERE id = :id").param("id", customerId).query(String.class).single();
        assertThat(displayName).isEqualTo("REDACTED");
        assertThat(email).isNull();
        assertThat(status).isEqualTo("closed");
    }
}
