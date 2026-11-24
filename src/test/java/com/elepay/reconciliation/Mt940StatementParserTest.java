package com.elepay.reconciliation;

import com.elepay.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

        // Ingest into database (unique statement number per run for persistent test DB)
        String uniqueMt940 = SAMPLE_MT940.replace("00045/001",
                String.format("%05d/001", Math.abs(java.util.UUID.randomUUID().hashCode()) % 90000 + 10000));
        var ingestion = parser.ingestStatement("GCB", uniqueMt940);
        assertThat(ingestion.batchId()).isNotNull();
        assertThat(ingestion.totalLines()).isEqualTo(2);
        assertThat(ingestion.status()).isEqualTo("INGESTED");
    }

    @Test
    @DisplayName("Fractional pesewas are rejected and lineCounter maintains strict sequence with malformed lines")
    void testFractionalPesewasAndSequentialLineCounterWithMalformedLines() {
        String mt940WithFractional = """
                :20:GCB-FRACTIONAL
                :25:1010005555555
                :28C:00005/001
                :60F:C260301GHS5000,00
                :61:2603010301CR1500,556NTRFNONREF//LINE-1-REF
                :62F:C260301GHS6500,56
                -
                """;
        assertThatThrownBy(() -> parser.parse(mt940WithFractional))
                .isInstanceOf(ArithmeticException.class);

        String mt940WithMalformed = """
                :20:GCB-MALFORMED-SEQ
                :25:1010005555555
                :28C:00005/001
                :60F:C260301GHS5000,00
                :61:2603010301CR1500,56NTRFNONREF//LINE-1-REF
                :86:EXACT PESEWAS LINE 1
                :61:MALFORMED NON-STANDARD STATEMENT LINE WITHOUT TAGS
                :61:2603010301CR200,44NTRFNONREF//LINE-3-REF
                :62F:C260301GHS6701,00
                -
                """;

        var parsed = parser.parse(mt940WithMalformed);
        assertThat(parsed.lines()).hasSize(3);

        // Line 1: 1500,56 * 100 = 150056 pesewas
        assertThat(parsed.lines().get(0).lineNumber()).isEqualTo(1);
        assertThat(parsed.lines().get(0).amountPesewas()).isEqualTo(150056L);

        // Line 2: fallback line should have line number 2
        assertThat(parsed.lines().get(1).lineNumber()).isEqualTo(2);

        // Line 3: 200,44 * 100 = 20044 pesewas, line number 3
        assertThat(parsed.lines().get(2).lineNumber()).isEqualTo(3);
        assertThat(parsed.lines().get(2).amountPesewas()).isEqualTo(20044L);
    }

    @Test
    @DisplayName("Regression: Duplicate MT940 ingestion is rejected with 409 Conflict")
    void testDuplicateMt940IngestionRejected() {
        String s = java.util.UUID.randomUUID().toString().substring(0, 8);
        String uniqueMt940 = """
                :20:GCB-DUP-%s
                :25:1010%s
                :28C:00099/001
                :60F:C260301GHS1000,00
                :61:2603010301CR100,00NTRFNONREF//DUP-LINE-%s
                :62F:C260301GHS1100,00
                -
                """.formatted(s, s, s);
        var first = parser.ingestStatement("GCB", uniqueMt940);
        assertThat(first.batchId()).isNotNull();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> parser.ingestStatement("GCB", uniqueMt940))
                .isInstanceOf(com.elepay.common.ApiException.class)
                .hasMessageContaining("already been ingested");
    }

    @Test
    @DisplayName("Regression: Malformed :61: line is flagged ERR and rejected on ingestStatement")
    void testMalformedLineFlaggedAndRejectedOnIngest() {
        String malformed = """
                :20:GCB-MALFORMED
                :25:1010001111111
                :28C:00088/001
                :60F:C260301GHS1000,00
                :61:MALFORMED_LINE_WITHOUT_VALID_FORMAT
                :62F:C260301GHS1000,00
                -
                """;
        var parsed = parser.parse(malformed);
        assertThat(parsed.lines().get(0).transactionTypeCode()).isEqualTo("ERR");
        assertThat(parsed.lines().get(0).supplementaryDetails()).startsWith("PARSE_ERROR:");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> parser.ingestStatement("GCB", malformed))
                .isInstanceOf(com.elepay.common.ApiException.class)
                .hasMessageContaining("Malformed :61:");
    }

    @Test
    @DisplayName("Regression: Dec->Jan year rollover in parseDate4 assigns previous year to December entryDate")
    void testDecemberToJanuaryYearRollover() {
        String rollover = """
                :20:GCB-ROLLOVER
                :25:1010001230000
                :28C:00001/001
                :60F:C260102GHS1000,00
                :61:2601021231CR250,00NTRFNONREF//ROLL-1
                :62F:C260102GHS1250,00
                -
                """;
        var parsed = parser.parse(rollover);
        assertThat(parsed.lines()).hasSize(1);
        assertThat(parsed.lines().get(0).valueDate()).isEqualTo(java.time.LocalDate.of(2026, 1, 2));
        assertThat(parsed.lines().get(0).entryDate()).isEqualTo(java.time.LocalDate.of(2025, 12, 31));
    }
}
