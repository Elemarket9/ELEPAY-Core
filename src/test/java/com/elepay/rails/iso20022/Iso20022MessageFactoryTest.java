package com.elepay.rails.iso20022;

import com.elepay.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
}
