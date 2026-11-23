package com.elepay.merchanttools.pos;

import com.elepay.PostgresTestBase;
import com.elepay.ledger.Ledger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PosDukptAndOfflineTest extends PostgresTestBase {

    @Autowired
    private PosDukptKeyManagementService dukptService;

    @Autowired
    private PosOfflineTransactionEngine offlineEngine;

    @Autowired
    private Ledger ledger;

    @Autowired
    private JdbcClient db;

    @Test
    @DisplayName("Pillar 3: POS ANSI X9.24 DUKPT key management, PIN block encryption, and offline store-and-forward batch capture")
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

        // 4. Offline store-and-forward: valid transaction within 50 GHS floor limit (35 GHS)
        var offline1 = offlineEngine.processOfflineTransaction(new PosOfflineTransactionEngine.OfflineTransactionRequest(
                terminalId, merchantId, "000001", 3_500L, "GHS", "5411", "402234******7890", "051", OffsetDateTime.now()
        ));
        assertThat(offline1.approved()).isTrue();
        assertThat(offline1.authCode()).startsWith("OFF-");
        assertThat(offline1.status()).isEqualTo("STORED");

        // 5. Offline transaction exceeding floor limit (75 GHS > 50 GHS) -> Declined
        var offlineExceeded = offlineEngine.processOfflineTransaction(new PosOfflineTransactionEngine.OfflineTransactionRequest(
                terminalId, merchantId, "000002", 7_500L, "GHS", "5411", "402234******7890", "051", OffsetDateTime.now()
        ));
        assertThat(offlineExceeded.approved()).isFalse();
        assertThat(offlineExceeded.declineReason()).isEqualTo("OFFLINE_FLOOR_LIMIT_EXCEEDED");

        // 6. Duplicate STAN replay attack prevention
        var offlineReplay = offlineEngine.processOfflineTransaction(new PosOfflineTransactionEngine.OfflineTransactionRequest(
                terminalId, merchantId, "000001", 2_000L, "GHS", "5411", "402234******7890", "051", OffsetDateTime.now()
        ));
        assertThat(offlineReplay.approved()).isFalse();
        assertThat(offlineReplay.declineReason()).isEqualTo("DUPLICATE_OFFLINE_STAN_REPLAY");

        // 7. Synchronize offline batch with double-entry ledger capture
        var syncResult = offlineEngine.syncTerminalBatch(terminalId);
        assertThat(syncResult).isNotNull();
        assertThat(syncResult.syncedCount()).isEqualTo(1);
        assertThat(syncResult.totalAmountPesewas()).isEqualTo(3_500L);
        assertThat(syncResult.outcomes().get(0).syncStatus()).isEqualTo("SYNCED");
        assertThat(syncResult.outcomes().get(0).ledgerTxId()).isNotNull();

        // Verify merchant account balance reflects the synced offline settlement capture
        long merchantBalance = ledger.balance(Ledger.code("merchant", merchantId, "available", Ledger.GHS));
        assertThat(merchantBalance).isEqualTo(3_500L);
    }
}
