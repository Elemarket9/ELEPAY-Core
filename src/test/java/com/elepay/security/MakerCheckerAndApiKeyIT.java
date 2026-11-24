package com.elepay.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elepay.PostgresTestBase;
import com.elepay.TestApi;
import com.elepay.common.ApiException;
import com.elepay.identity.ApiKeys;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class MakerCheckerAndApiKeyIT extends PostgresTestBase {

    @Autowired
    private MakerCheckerService makerChecker;

    @Autowired
    private ApiKeys apiKeys;

    @Autowired
    private JdbcClient db;

    @Test
    void feeScheduleChangeUpdatesMerchantOnApproval() {
        UUID merchantId = UUID.randomUUID();
        String code = String.format("%06d", Math.floorMod(merchantId.hashCode(), 900_000) + 100_000);
        db.sql("""
                INSERT INTO merchant (id, legal_name, trading_name, kind, status, contact_phone, merchant_code, fee_bps, fee_fixed, created_by)
                VALUES (:id, 'Fee Test Ltd', 'Fee Test', 'merchant', 'active', :phone, :code, 100, 50, 'maker-1')""")
                .param("id", merchantId)
                .param("phone", TestApi.uniquePhone())
                .param("code", code)
                .update();

        var proposal = makerChecker.propose(
                "FEE_SCHEDULE_CHANGE",
                "merchant",
                merchantId.toString(),
                merchantId,
                Map.of("feeBps", 250, "feeFixed", 150, "settlementDelayHours", 12),
                "Approved tier upgrade",
                "ops-maker-1"
        );

        var approved = makerChecker.approve(proposal.id(), "ops-checker-2", "LGTM");
        assertThat(approved.status()).isEqualTo("APPROVED");

        Integer bps = db.sql("SELECT fee_bps FROM merchant WHERE id = :id").param("id", merchantId).query(Integer.class).single();
        Long fixed = db.sql("SELECT fee_fixed FROM merchant WHERE id = :id").param("id", merchantId).query(Long.class).single();
        assertThat(bps).isEqualTo(250);
        assertThat(fixed).isEqualTo(150L);
    }

    @Test
    void approveRollsBackApprovedStatusWhenHandlerThrowsApiException() {
        UUID missingMerchantId = UUID.randomUUID();
        var proposal = makerChecker.propose(
                "FEE_SCHEDULE_CHANGE",
                "merchant",
                missingMerchantId.toString(),
                missingMerchantId,
                Map.of("feeBps", 300),
                "Should fail on missing merchant",
                "ops-maker-1"
        );

        assertThatThrownBy(() -> makerChecker.approve(proposal.id(), "ops-checker-2", "try"))
                .isInstanceOf(ApiException.class);

        var reloaded = makerChecker.getProposal(proposal.id());
        assertThat(reloaded.status()).isEqualTo("PENDING");
        assertThat(reloaded.reviewedBy()).isNull();
    }

    @Test
    void selfApprovalIsBlockedAndStillPersistsAuditRecord() {
        UUID merchantId = UUID.randomUUID();
        var proposal = makerChecker.propose(
                "FEE_SCHEDULE_CHANGE",
                "merchant",
                merchantId.toString(),
                merchantId,
                Map.of("feeBps", 200),
                "Self approval test",
                "ops-self-actor"
        );

        assertThatThrownBy(() -> makerChecker.approve(proposal.id(), "ops-self-actor", "self"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("cannot approve their own proposal");

        Integer count = db.sql("""
                SELECT count(*) FROM audit_log
                WHERE action = 'maker_checker.self_approval_blocked'
                  AND subject = :sub""")
                .param("sub", "proposal:" + proposal.id())
                .query(Integer.class)
                .single();
        assertThat(count).isGreaterThanOrEqualTo(1);
    }

    @Test
    void secretApiKeysCannotBeCreatedWithWithdrawOrPayoutsScopes() {
        UUID merchantId = UUID.randomUUID();
        assertThatThrownBy(() -> apiKeys.create(
                merchantId, "bad-secret", List.of("view", "withdraw"), "secret", List.of(), "test-user"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Secret keys cannot hold withdraw or payouts scopes");

        assertThatThrownBy(() -> apiKeys.create(
                merchantId, "bad-secret-2", List.of("payouts"), "secret", List.of(), "test-user"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Secret keys cannot hold withdraw or payouts scopes");
    }
}
