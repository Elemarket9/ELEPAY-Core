package com.elepay.rails;

import com.elepay.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GhipssClearingServiceTest extends PostgresTestBase {

    @Autowired
    private GhipssClearingService ghipssService;

    @Test
    @DisplayName("GhIPSS clearing lifecycle: proxy registration, transfer initiation, and ledger settlement")
    void testGhipssClearingLifecycle() {
        // 1. Proxy Pay registration & lookup
        String phone = "+23324" + (1000000 + (int)(Math.random() * 8999999));
        ghipssService.registerProxy(
                GhipssClearingService.ProxyType.PHONE,
                phone,
                "GCB_040",
                "1010001234567",
                "Kofi Mensah"
        );

        var proxyOpt = ghipssService.resolveProxy(GhipssClearingService.ProxyType.PHONE, phone);
        assertThat(proxyOpt).isPresent();
        assertThat(proxyOpt.get().institutionCode()).isEqualTo("GCB_040");
        assertThat(proxyOpt.get().accountNumber()).isEqualTo("1010001234567");

        // 2. Initiate Interbank Transfer
        long amountPesewas = 100_000L; // 1,000.00 GHS
        var session = ghipssService.initiateTransfer(
                "ELEPAY_030",
                "GCB_040",
                proxyOpt.get().accountNumber(),
                proxyOpt.get().accountName(),
                GhipssClearingService.ProxyType.PHONE,
                phone,
                amountPesewas,
                "GHS",
                "pacs.008"
        );

        assertThat(session).isNotNull();
        assertThat(session.sessionId()).startsWith("GIP-");
        assertThat(session.status()).isEqualTo("PENDING");
        assertThat(session.stan()).hasSize(6);
        assertThat(session.rrn()).hasSize(12);
        assertThat(session.amountPesewas()).isEqualTo(100_000L);

        // 3. Settle transfer and assert double-entry ledger posting
        var settled = ghipssService.settleTransfer(session.sessionId());
        assertThat(settled.status()).isEqualTo("SETTLED");
        assertThat(settled.ledgerTxId()).isNotNull();

        // 4. Query session
        var queried = ghipssService.getSession(session.sessionId());
        assertThat(queried).isPresent();
        assertThat(queried.get().status()).isEqualTo("SETTLED");
        assertThat(queried.get().ledgerTxId()).isEqualTo(settled.ledgerTxId());
    }
}
