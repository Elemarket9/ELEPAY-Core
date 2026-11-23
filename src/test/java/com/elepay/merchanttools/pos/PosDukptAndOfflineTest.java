package com.elepay.merchanttools.pos;

import com.elepay.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PosDukptAndOfflineTest extends PostgresTestBase {

    @Autowired
    private PosDukptKeyManagementService dukptService;

    @Autowired
    private PosOfflineTransactionEngine offlineEngine;

    @Autowired
    private JdbcClient db;

    @Test
    @DisplayName("Pillar 3: POS ANSI X9.24 DUKPT key management and fail-closed offline card acceptance")
    void testPosDukptAndOfflineLifecycle() {
        String terminalId = "TID-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        UUID merchantId = UUID.randomUUID();

        // 1. Provision terminal
        var profile = dukptService.provisionTerminal(terminalId, merchantId, "BDK-GH-01");
        assertThat(profile).isNotNull();
        assertThat(profile.terminalId()).isEqualTo(terminalId);
        assertThat(profile.currentKsn()).hasSize(20);
        assertThat(profile.transactionCounter()).isZero();

        // 2. Step transaction keys sequentially: each key must be unique and non-reusable
        var key1 = dukptService.stepTransactionKey(terminalId);
        var key2 = dukptService.stepTransactionKey(terminalId);

        assertThat(key1.transactionCounter()).isEqualTo(1L);
        assertThat(key2.transactionCounter()).isEqualTo(2L);
        assertThat(key1.ksn()).isNotEqualTo(key2.ksn());
        assertThat(key1.transactionKeyHex()).isNotEqualTo(key2.transactionKeyHex());

        // 3. Encrypt PIN block Format 0
        var pinRes = dukptService.encryptPinBlock(terminalId, "1234", "4022341234567890");
        assertThat(pinRes).isNotNull();
        assertThat(pinRes.encryptedPinBlock()).hasSize(16);
        assertThat(pinRes.transactionCounter()).isEqualTo(3L);

        // Non-numeric PIN must be rejected
        assertThatThrownBy(() -> dukptService.encryptPinBlock(terminalId, "12A4", "4022341234567890"))
                .hasMessageContaining("numeric");

        // Re-provisioning an existing terminal must preserve and reflect persistent transaction counter
        var reProfile = dukptService.provisionTerminal(terminalId, merchantId, "BDK-GH-01");
        assertThat(reProfile.transactionCounter()).isEqualTo(3L);

        // An offline card request must not fabricate acquirer authorization or merchant funds.
        var declined = offlineEngine.processOfflineTransaction(
                new PosOfflineTransactionEngine.OfflineTransactionRequest(
                        terminalId, merchantId, "000001", 3_500L, "GHS", "5411",
                        "402234******7890", "051", OffsetDateTime.now()));
        assertThat(declined.approved()).isFalse();
        assertThat(declined.authCode()).isNull();
        assertThat(declined.declineReason()).isEqualTo("RAIL_NOT_CONFIGURED");

        var excessive = offlineEngine.processOfflineTransaction(
                new PosOfflineTransactionEngine.OfflineTransactionRequest(
                        terminalId, merchantId, "000002", 7_500L, "GHS", "5411",
                        "402234******7890", "051", OffsetDateTime.now()));
        assertThat(excessive.declineReason()).isEqualTo("OFFLINE_FLOOR_LIMIT_EXCEEDED");
        assertThatThrownBy(() -> offlineEngine.syncTerminalBatch(terminalId))
                .isInstanceOf(com.elepay.common.ApiException.class)
                .hasMessageContaining("requires independently verified acquirer evidence");
        assertThat(db.sql("SELECT count(*) FROM pos_offline_transaction_queue WHERE terminal_id = :terminal")
                .param("terminal", terminalId).query(Long.class).single()).isZero();
    }
}
