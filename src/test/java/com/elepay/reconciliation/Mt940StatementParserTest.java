package com.elepay.reconciliation;

import com.elepay.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class Mt940StatementParserTest extends PostgresTestBase {

    @Autowired
    private Mt940StatementParser parser;

    private static final String SAMPLE_MT940 = """
            :20:GCB-STAT-20260301
            :25:1010001234567
            :28C:00045/001
            :60F:C260301GHS10000,00
            :61:2603010301CR1500,50NTRFNONREF//TX-REF-001
            :86:GHIPSS INSTANT PAY TRANSFER FROM KOFI
            :61:2603010301DR500,00NTRFNONREF//TX-REF-002
            :86:MERCHANT SETTLEMENT PAYOUT
            :62F:C260301GHS11000,50
            -
            """;

    @Test
    @DisplayName("Parse standard SWIFT MT940 bank statement and ingest into batch tables")
    void testParseAndIngestMt940() {
        var parsed = parser.parse(SAMPLE_MT940);
        assertThat(parsed).isNotNull();
        assertThat(parsed.accountNumber()).isEqualTo("1010001234567");
        assertThat(parsed.statementNumber()).isEqualTo("00045");
        assertThat(parsed.openingBalancePesewas()).isEqualTo(1_000_000L); // 10,000.00 GHS
        assertThat(parsed.closingBalancePesewas()).isEqualTo(1_100_050L); // 11,000.50 GHS
        assertThat(parsed.lines()).hasSize(2);

        // Check line 1
        var line1 = parsed.lines().get(0);
        assertThat(line1.debitCreditMark()).isEqualTo("C");
        assertThat(line1.amountPesewas()).isEqualTo(150_050L);
        assertThat(line1.bankReference()).isEqualTo("TX-REF-001");
        assertThat(line1.supplementaryDetails()).contains("KOFI");

        // Check line 2
        var line2 = parsed.lines().get(1);
        assertThat(line2.debitCreditMark()).isEqualTo("D");
        assertThat(line2.amountPesewas()).isEqualTo(50_000L);
        assertThat(line2.bankReference()).isEqualTo("TX-REF-002");

        // Ingest into database
        var ingestion = parser.ingestStatement("GCB", SAMPLE_MT940);
        assertThat(ingestion.batchId()).isNotNull();
        assertThat(ingestion.totalLines()).isEqualTo(2);
        assertThat(ingestion.status()).isEqualTo("INGESTED");
    }
}
