package com.elepay.rails.iso20022;

import com.elepay.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Iso20022MessageFactoryTest extends PostgresTestBase {

    @Autowired
    private Iso20022MessageFactory isoFactory;

    @Autowired
    private JdbcClient db;

    @Test
    @DisplayName("Pillar 2: ISO 20022 pain.001.001.09 generation, schema conformance, and audit logging")
    void testPain001GenerationAndParsing() {
        var transfers = List.of(
                new Iso20022MessageFactory.Pain001CreditTransfer(
                        "E2E-SALARY-001",
                        250_000L, // 2,500.00 GHS
                        "GHS",
                        "CORP PAYROLL LTD",
                        "GH29GCBB00010001234567",
                        "KWAME MENSAH",
                        "GH29ECOB00010007654321",
                        "ECOBGHAC",
                        "November 2026 Executive Salary"
                ),
                new Iso20022MessageFactory.Pain001CreditTransfer(
                        "E2E-SALARY-002",
                        180_000L, // 1,800.00 GHS
                        "GHS",
                        "CORP PAYROLL LTD",
                        "GH29GCBB00010001234567",
                        "AMA AGYEMAN",
                        "GH29SCBL00010009876543",
                        "SCBLGHAC",
                        "November 2026 Engineering Salary"
                )
        );

        var message = isoFactory.buildPain001("GCBBGHAC", transfers);

        assertThat(message).isNotNull();
        assertThat(message.messageId()).startsWith("PAIN001-");
        assertThat(message.transactionCount()).isEqualTo(2);
        assertThat(message.controlSumPesewas()).isEqualTo(430_000L); // 4,300.00 GHS
        assertThat(message.payloadXml()).contains("pain.001.001.09");
        assertThat(message.payloadXml()).contains("<CtrlSum>4300.00</CtrlSum>");
        assertThat(message.payloadXml()).contains("<NbOfTxs>2</NbOfTxs>");
        assertThat(message.payloadXml()).contains("KWAME MENSAH");
        assertThat(message.payloadXml()).contains("AMA AGYEMAN");
        assertThat(message.payloadSha256()).isNotBlank();

        // Verify audit log row in iso20022_message_log
        long logCount = db.sql("SELECT count(*) FROM iso20022_message_log WHERE message_id = :mid")
                .param("mid", message.messageId())
                .query(Long.class)
                .single();
        assertThat(logCount).isEqualTo(1L);

        // Verify XML parsing
        var parsed = isoFactory.parsePain001(message.payloadXml());
        assertThat(parsed).hasSize(2);
        assertThat(parsed.get(0).endToEndId()).isEqualTo("E2E-SALARY-001");
        assertThat(parsed.get(0).amountPesewas()).isEqualTo(250_000L);
        assertThat(parsed.get(1).endToEndId()).isEqualTo("E2E-SALARY-002");
        assertThat(parsed.get(1).amountPesewas()).isEqualTo(180_000L);
    }

    @Test
    @DisplayName("Pillar 2: ISO 20022 pacs.008.001.08 interbank clearing message generation")
    void testPacs008Generation() {
        var transfers = List.of(
                new Iso20022MessageFactory.Pacs008InterbankTransfer(
                        "E2E-IB-1001",
                        "INSTR-1001",
                        50_000L, // 500.00 GHS
                        "GHS",
                        "GCBBGHAC",
                        "STANCHAC",
                        "GCB-ACC-01",
                        "STAN-ACC-02",
                        "SLEV"
                )
        );

        var message = isoFactory.buildPacs008("GCBBGHAC", "STANCHAC", transfers);

        assertThat(message).isNotNull();
        assertThat(message.messageId()).startsWith("PACS008-");
        assertThat(message.payloadXml()).contains("pacs.008.001.08");
        assertThat(message.payloadXml()).contains("<ClrSys><Prtry>GHIPSS</Prtry></ClrSys>");
        assertThat(message.payloadXml()).contains("<IntrBkSttlmAmt Ccy=\"GHS\">500.00</IntrBkSttlmAmt>");
    }

    @Test
    @DisplayName("Pillar 2: ISO 20022 camt.053.001.08 bank statement generation and audit logging")
    void testCamt053GenerationAndLogging() {
        var entries = List.of(
                new Iso20022MessageFactory.Camt053Entry(
                        "ENTRY-REF-001",
                        12_500L, // 125.00 GHS
                        "CRDT",
                        "2026-11-23",
                        "Merchant settlement disbursement"
                ),
                new Iso20022MessageFactory.Camt053Entry(
                        "ENTRY-REF-002",
                        2_500L, // 25.00 GHS
                        "DBIT",
                        "2026-11-23",
                        "Platform commission fee"
                )
        );

        var statement = new Iso20022MessageFactory.Camt053Statement(
                "STMT-2026-11-23-01",
                "GH29GCBB00010001234567",
                "GHS",
                50_000L,  // 500.00 GHS opening
                60_000L,  // 600.00 GHS closing
                entries
        );

        var message = isoFactory.buildCamt053("GCBBGHAC", statement);

        assertThat(message).isNotNull();
        assertThat(message.messageId()).startsWith("CAMT053-");
        assertThat(message.messageType()).isEqualTo("camt.053.001.08");
        assertThat(message.transactionCount()).isEqualTo(2);
        assertThat(message.controlSumPesewas()).isEqualTo(15_000L);
        assertThat(message.payloadXml()).contains("camt.053.001.08");
        assertThat(message.payloadXml()).contains("<BkToCstmrStmt>");
        assertThat(message.payloadXml()).contains("<Id>STMT-2026-11-23-01</Id>");
        assertThat(message.payloadXml()).contains("<Amt Ccy=\"GHS\">500.00</Amt>");
        assertThat(message.payloadXml()).contains("<Amt Ccy=\"GHS\">600.00</Amt>");
        assertThat(message.payloadXml()).contains("<BICFI>GCBBGHAC</BICFI>");
        assertThat(message.payloadXml()).contains("<EndToEndId>ENTRY-REF-001</EndToEndId>");
        assertThat(message.payloadSha256()).isNotBlank();

        // Verify audit log
        long logCount = db.sql("SELECT count(*) FROM iso20022_message_log WHERE message_id = :mid")
                .param("mid", message.messageId())
                .query(Long.class)
                .single();
        assertThat(logCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("Pillar 2: ISO 20022 parsing with XML namespace prefixes")
    void testNamespaceHandlingInParsing() {
        String namespacedXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Document xmlns:pain="urn:iso:std:iso:20022:tech:xsd:pain.001.001.09">
              <pain:CstmrCdtTrfInitn>
                <pain:CdtTrfTxInf>
                  <pain:PmtId><pain:EndToEndId>E2E-NS-001</pain:EndToEndId></pain:PmtId>
                  <pain:Amt><pain:InstdAmt Ccy="GHS">350.00</pain:InstdAmt></pain:Amt>
                  <pain:CdtrAgt><pain:FinInstnId><pain:BICFI>GCBBGHAC</pain:BICFI></pain:FinInstnId></pain:CdtrAgt>
                  <pain:Cdtr><pain:Nm>KOFI MENSAH</pain:Nm></pain:Cdtr>
                  <pain:CdtrAcct><pain:Id><pain:Othr><pain:Id>ACC-123456</pain:Id></pain:Othr></pain:Id></pain:CdtrAcct>
                  <pain:RmtInf><pain:Ustrd>Vendor Payment</pain:Ustrd></pain:RmtInf>
                </pain:CdtTrfTxInf>
              </pain:CstmrCdtTrfInitn>
            </Document>
            """;

        var parsed = isoFactory.parsePain001(namespacedXml);
        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).endToEndId()).isEqualTo("E2E-NS-001");
        assertThat(parsed.get(0).amountPesewas()).isEqualTo(35_000L);
        assertThat(parsed.get(0).creditorName()).isEqualTo("KOFI MENSAH");
        assertThat(parsed.get(0).currency()).isEqualTo("GHS");
    }

    @Test
    @DisplayName("Pillar 2: Reject non-positive amounts and verify XML escaping")
    void testValidationAndEscaping() {
        // Zero or negative amounts must fail
        var invalidTransfers = List.of(
                new Iso20022MessageFactory.Pain001CreditTransfer(
                        "E2E-INV-001", 0L, "GHS", "Debtor", "ACC1", "Creditor", "ACC2", "BIC1", "Remit"
                )
        );
        assertThatThrownBy(() -> isoFactory.buildPain001("GCBBGHAC", invalidTransfers))
                .hasMessageContaining("positive");

        // XML characters are properly escaped
        var validWithSpecialChars = List.of(
                new Iso20022MessageFactory.Pain001CreditTransfer(
                        "E2E-SPEC-001", 10_000L, "GHS", "Debtor & Co <Ltd>", "ACC1",
                        "Creditor \"A\" & 'B'", "ACC2", "BIC1", "Goods & Services"
                )
        );
        var msg = isoFactory.buildPain001("GCBBGHAC", validWithSpecialChars);
        assertThat(msg.payloadXml()).contains("Debtor &amp; Co &lt;Ltd&gt;");
        assertThat(msg.payloadXml()).contains("Creditor &quot;A&quot; &amp; &apos;B&apos;");
        assertThat(msg.payloadXml()).contains("Goods &amp; Services");
    }
}
