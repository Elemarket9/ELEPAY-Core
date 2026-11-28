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

    @Autowired
    private org.springframework.jdbc.core.simple.JdbcClient db;

    @Test
    @DisplayName("GhIPSS clearing lifecycle: proxy registration, transfer initiation, and ledger settlement")
    void testGhipssClearingLifecycle() {
        // 1. Proxy Pay registration & lookup
        String phone = "+23324" + (1000000 + (int)(Math.random() * 8999999));
        ghipssService.registerProxy(
                GhipssClearingService.ProxyType.PHONE,
                phone,
                "TST_040",
                "1010001234567",
                "Kofi Mensah"
        );

        var proxyOpt = ghipssService.resolveProxy(GhipssClearingService.ProxyType.PHONE, phone);
        assertThat(proxyOpt).isPresent();
        assertThat(proxyOpt.get().institutionCode()).isEqualTo("TST_040");
        assertThat(proxyOpt.get().accountNumber()).isEqualTo("1010001234567");

        // 2. Initiate Interbank Transfer
        long amountPesewas = 100_000L; // 1,000.00 GHS
        var session = ghipssService.initiateTransfer(
                "ELEPAY_030",
                "TST_040",
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

        // 5. Query by RRN and STAN
        var byRrnStan = ghipssService.findByRrnAndStan(session.rrn(), session.stan());
        assertThat(byRrnStan).isPresent();
        assertThat(byRrnStan.get().sessionId()).isEqualTo(session.sessionId());
    }

    @Test
    @DisplayName("Unique constraint on (rrn, stan) rejects duplicate records")
    void testUniqueRrnStanConstraint() {
        var session = ghipssService.initiateTransfer(
                "ELEPAY_030",
                "TST_040",
                "1010009999999",
                "Aba Mansa",
                GhipssClearingService.ProxyType.PHONE,
                "+233240001122",
                50_000L,
                "GHS",
                "pacs.008"
        );

        // Attempt direct insertion of duplicate (rrn, stan) pair
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> {
            db.sql("""
                INSERT INTO ghipss_clearing_record (
                    id, session_id, clearing_cycle, originating_institution_code,
                    destination_institution_code, destination_account_number, destination_account_name,
                    amount_pesewas, currency, iso_message_type, stan, rrn, status, created_at, updated_at
                ) VALUES (
                    :id, :sessionId, 'CYCLE_1', 'ELEPAY_030',
                    'TST_040', '1010009999999', 'Duplicate Test',
                    50000, 'GHS', 'pacs.008', :stan, :rrn, 'PENDING', now(), now()
                )
            """)
                    .param("id", UUID.randomUUID())
                    .param("sessionId", "GIP-DUP-" + UUID.randomUUID().toString().substring(0, 8))
                    .param("stan", session.stan())
                    .param("rrn", session.rrn())
                    .update();
        }).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
