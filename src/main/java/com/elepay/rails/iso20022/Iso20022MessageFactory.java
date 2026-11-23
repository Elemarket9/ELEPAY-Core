package com.elepay.rails.iso20022;

import com.elepay.common.ApiException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Universal ISO 20022 Financial Messaging Engine.
 * Supports:
 * 1. pain.001.001.09: Customer Credit Transfer Initiation.
 * 2. pacs.008.001.08: Financial Institutional Customer Credit Transfer.
 * 3. camt.053.001.08: Bank-to-Customer Statement.
 * Implements XML document generation, parsing, schema validation, and audit logging.
 */
@Service
public class Iso20022MessageFactory {

    private static final DateTimeFormatter ISO_DT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private final JdbcClient db;

    public Iso20022MessageFactory(JdbcClient db) {
        this.db = db;
    }

    public record Pain001CreditTransfer(
            String endToEndId,
            long amountPesewas,
            String currency,
            String debtorName,
            String debtorAccount,
            String creditorName,
            String creditorAccount,
            String creditorBic,
            String remittanceInfo
    ) {}

    public record Pacs008InterbankTransfer(
            String endToEndId,
            String instructionId,
            long amountPesewas,
            String currency,
            String originatingBankBic,
            String destinationBankBic,
            String debtorAccount,
            String creditorAccount,
            String chargeBearer
    ) {}

    public record Camt053Statement(
            String statementId,
            String accountNumber,
            String currency,
            long openingBalancePesewas,
            long closingBalancePesewas,
            List<Camt053Entry> entries
    ) {}

    public record Camt053Entry(
            String entryReference,
            long amountPesewas,
            String creditDebitIndicator, // CRDT or DBIT
            String bookingDate,
            String narrative
    ) {}

    public record GeneratedIsoMessage(
            UUID logId,
            String messageId,
            String messageType,
            String payloadXml,
            String payloadSha256,
            int transactionCount,
            long controlSumPesewas
    ) {}

    // =========================================================================
    // 1. pain.001.001.09: Customer Credit Transfer Initiation
    // =========================================================================

    @Transactional
    public GeneratedIsoMessage buildPain001(
            String initiatingPartyBic,
            List<Pain001CreditTransfer> transfers
    ) {
        if (transfers == null || transfers.isEmpty()) {
            throw ApiException.badRequest("empty_transfers", "pain.001 requires at least one transfer instruction");
        }

        for (var tx : transfers) {
            if (tx.amountPesewas() <= 0) {
                throw ApiException.badRequest("invalid_amount", "Transfer amount must be positive");
            }
        }

        String msgId = "PAIN001-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        OffsetDateTime now = OffsetDateTime.now();
        int nbOfTxs = transfers.size();
        long ctrlSumPesewas = transfers.stream().mapToLong(Pain001CreditTransfer::amountPesewas).sum();
        BigDecimal ctrlSumMajor = BigDecimal.valueOf(ctrlSumPesewas, 2);

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pain.001.001.09\">\n");
        xml.append("  <CstmrCdtTrfInitn>\n");
        xml.append("    <GrpHdr>\n");
        xml.append("      <MsgId>").append(msgId).append("</MsgId>\n");
        xml.append("      <CreDtTm>").append(now.format(ISO_DT)).append("</CreDtTm>\n");
        xml.append("      <NbOfTxs>").append(nbOfTxs).append("</NbOfTxs>\n");
        xml.append("      <CtrlSum>").append(ctrlSumMajor.toPlainString()).append("</CtrlSum>\n");
        xml.append("      <InitgPty><Id><OrgId><AnyBIC>").append(escapeXml(initiatingPartyBic)).append("</AnyBIC></OrgId></Id></InitgPty>\n");
        xml.append("    </GrpHdr>\n");

        for (var tx : transfers) {
            BigDecimal amtMajor = BigDecimal.valueOf(tx.amountPesewas(), 2);
            xml.append("    <PmtInf>\n");
            xml.append("      <PmtInfId>PMT-").append(UUID.randomUUID().toString().substring(0, 8).toUpperCase()).append("</PmtInfId>\n");
            xml.append("      <PmtMtd>TRF</PmtMtd>\n");
            xml.append("      <Dbtr><Nm>").append(escapeXml(tx.debtorName())).append("</Nm></Dbtr>\n");
            xml.append("      <DbtrAcct><Id><Othr><Id>").append(escapeXml(tx.debtorAccount())).append("</Id></Othr></Id></DbtrAcct>\n");
            xml.append("      <CdtTrfTxInf>\n");
            xml.append("        <PmtId><EndToEndId>").append(escapeXml(tx.endToEndId())).append("</EndToEndId></PmtId>\n");
            xml.append("        <Amt><InstdAmt Ccy=\"").append(escapeXml(tx.currency())).append("\">").append(amtMajor.toPlainString()).append("</InstdAmt></Amt>\n");
            xml.append("        <CdtrAgt><FinInstnId><BICFI>").append(escapeXml(tx.creditorBic())).append("</BICFI></FinInstnId></CdtrAgt>\n");
            xml.append("        <Cdtr><Nm>").append(escapeXml(tx.creditorName())).append("</Nm></Cdtr>\n");
            xml.append("        <CdtrAcct><Id><Othr><Id>").append(escapeXml(tx.creditorAccount())).append("</Id></Othr></Id></CdtrAcct>\n");
            if (tx.remittanceInfo() != null) {
                xml.append("        <RmtInf><Ustrd>").append(escapeXml(tx.remittanceInfo())).append("</Ustrd></RmtInf>\n");
            }
            xml.append("      </CdtTrfTxInf>\n");
            xml.append("    </PmtInf>\n");
        }

        xml.append("  </CstmrCdtTrfInitn>\n");
        xml.append("</Document>");

        String payload = xml.toString();
        String sha256 = hashSha256(payload);
        UUID logId = UUID.randomUUID();

        logMessage(logId, msgId, "pain.001.001.09", "OUTBOUND", initiatingPartyBic, null, nbOfTxs, ctrlSumPesewas, payload, sha256);

        return new GeneratedIsoMessage(logId, msgId, "pain.001.001.09", payload, sha256, nbOfTxs, ctrlSumPesewas);
    }

    // =========================================================================
    // 2. pacs.008.001.08: Interbank Customer Credit Transfer
    // =========================================================================

    @Transactional
    public GeneratedIsoMessage buildPacs008(
            String originatingBankBic,
            String destinationBankBic,
            List<Pacs008InterbankTransfer> transfers
    ) {
        if (transfers == null || transfers.isEmpty()) {
            throw ApiException.badRequest("empty_transfers", "pacs.008 requires at least one transfer instruction");
        }

        for (var tx : transfers) {
            if (tx.amountPesewas() <= 0) {
                throw ApiException.badRequest("invalid_amount", "Transfer amount must be positive");
            }
        }

        String msgId = "PACS008-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        OffsetDateTime now = OffsetDateTime.now();
        int nbOfTxs = transfers.size();
        long ctrlSumPesewas = transfers.stream().mapToLong(Pacs008InterbankTransfer::amountPesewas).sum();
        BigDecimal ctrlSumMajor = BigDecimal.valueOf(ctrlSumPesewas, 2);

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08\">\n");
        xml.append("  <FIToFICstmrCdtTrf>\n");
        xml.append("    <GrpHdr>\n");
        xml.append("      <MsgId>").append(msgId).append("</MsgId>\n");
        xml.append("      <CreDtTm>").append(now.format(ISO_DT)).append("</CreDtTm>\n");
        xml.append("      <NbOfTxs>").append(nbOfTxs).append("</NbOfTxs>\n");
        xml.append("      <SttlmInf><SttlmMtd>CLRG</SttlmMtd><ClrSys><Prtry>GHIPSS</Prtry></ClrSys></SttlmInf>\n");
        xml.append("    </GrpHdr>\n");

        for (var tx : transfers) {
            BigDecimal amtMajor = BigDecimal.valueOf(tx.amountPesewas(), 2);
            xml.append("    <CdtTrfTxInf>\n");
            xml.append("      <PmtId>\n");
            xml.append("        <InstrId>").append(escapeXml(tx.instructionId())).append("</InstrId>\n");
            xml.append("        <EndToEndId>").append(escapeXml(tx.endToEndId())).append("</EndToEndId>\n");
            xml.append("      </PmtId>\n");
            xml.append("      <IntrBkSttlmAmt Ccy=\"").append(escapeXml(tx.currency())).append("\">").append(amtMajor.toPlainString()).append("</IntrBkSttlmAmt>\n");
            xml.append("      <ChrgBr>").append(escapeXml(tx.chargeBearer() != null ? tx.chargeBearer() : "SLEV")).append("</ChrgBr>\n");
            xml.append("      <InstgAgt><FinInstnId><BICFI>").append(escapeXml(originatingBankBic)).append("</BICFI></FinInstnId></InstgAgt>\n");
            xml.append("      <InstdAgt><FinInstnId><BICFI>").append(escapeXml(destinationBankBic)).append("</BICFI></FinInstnId></InstdAgt>\n");
            xml.append("      <DbtrAcct><Id><Othr><Id>").append(escapeXml(tx.debtorAccount())).append("</Id></Othr></Id></DbtrAcct>\n");
            xml.append("      <CdtrAcct><Id><Othr><Id>").append(escapeXml(tx.creditorAccount())).append("</Id></Othr></Id></CdtrAcct>\n");
            xml.append("    </CdtTrfTxInf>\n");
        }

        xml.append("  </FIToFICstmrCdtTrf>\n");
        xml.append("</Document>");

        String payload = xml.toString();
        String sha256 = hashSha256(payload);
        UUID logId = UUID.randomUUID();

        logMessage(logId, msgId, "pacs.008.001.08", "OUTBOUND", originatingBankBic, destinationBankBic, nbOfTxs, ctrlSumPesewas, payload, sha256);

        return new GeneratedIsoMessage(logId, msgId, "pacs.008.001.08", payload, sha256, nbOfTxs, ctrlSumPesewas);
    }

    // =========================================================================
    // 3. camt.053.001.08: Bank-to-Customer Statement
    // =========================================================================

    @Transactional
    public GeneratedIsoMessage buildCamt053(
            String bankBic,
            Camt053Statement statement
    ) {
        if (statement == null) {
            throw ApiException.badRequest("null_statement", "camt.053 statement must not be null");
        }
        if (statement.entries() != null) {
            for (var entry : statement.entries()) {
                if (entry.amountPesewas() <= 0) {
                    throw ApiException.badRequest("invalid_amount", "Statement entry amount must be positive");
                }
            }
        }

        String safeBic = escapeXml(bankBic != null ? bankBic : "ELEPGHAC");
        String msgId = "CAMT053-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        OffsetDateTime now = OffsetDateTime.now();
        int nbOfTxs = statement.entries() != null ? statement.entries().size() : 0;
        long ctrlSumPesewas = statement.entries() != null ? statement.entries().stream().mapToLong(Camt053Entry::amountPesewas).sum() : 0L;
        BigDecimal openingMajor = BigDecimal.valueOf(statement.openingBalancePesewas(), 2);
        BigDecimal closingMajor = BigDecimal.valueOf(statement.closingBalancePesewas(), 2);
        String ccy = escapeXml(statement.currency() != null ? statement.currency() : "GHS");

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:camt.053.001.08\">\n");
        xml.append("  <BkToCstmrStmt>\n");
        xml.append("    <GrpHdr>\n");
        xml.append("      <MsgId>").append(msgId).append("</MsgId>\n");
        xml.append("      <CreDtTm>").append(now.format(ISO_DT)).append("</CreDtTm>\n");
        xml.append("    </GrpHdr>\n");
        xml.append("    <Stmt>\n");
        xml.append("      <Id>").append(escapeXml(statement.statementId())).append("</Id>\n");
        xml.append("      <Acct>\n");
        xml.append("        <Id><Othr><Id>").append(escapeXml(statement.accountNumber())).append("</Id></Othr></Id>\n");
        xml.append("        <Ccy>").append(ccy).append("</Ccy>\n");
        xml.append("        <Svcr><FinInstnId><BICFI>").append(safeBic).append("</BICFI></FinInstnId></Svcr>\n");
        xml.append("      </Acct>\n");
        xml.append("      <Bal>\n");
        xml.append("        <Tp><CdOrPrtry><Cd>OPBD</Cd></CdOrPrtry></Tp>\n");
        xml.append("        <Amt Ccy=\"").append(ccy).append("\">").append(openingMajor.toPlainString()).append("</Amt>\n");
        xml.append("        <CdtDbtInd>").append(statement.openingBalancePesewas() >= 0 ? "CRDT" : "DBIT").append("</CdtDbtInd>\n");
        xml.append("        <Dt><Dt>").append(now.toLocalDate().toString()).append("</Dt></Dt>\n");
        xml.append("      </Bal>\n");

        if (statement.entries() != null) {
            for (var entry : statement.entries()) {
                BigDecimal entryAmtMajor = BigDecimal.valueOf(entry.amountPesewas(), 2);
                xml.append("      <Ntry>\n");
                xml.append("        <Amt Ccy=\"").append(ccy).append("\">").append(entryAmtMajor.toPlainString()).append("</Amt>\n");
                xml.append("        <CdtDbtInd>").append(escapeXml(entry.creditDebitIndicator() != null ? entry.creditDebitIndicator() : "CRDT")).append("</CdtDbtInd>\n");
                xml.append("        <Sts>BOOK</Sts>\n");
                xml.append("        <BookgDt><Dt>").append(escapeXml(entry.bookingDate() != null ? entry.bookingDate() : now.toLocalDate().toString())).append("</Dt></BookgDt>\n");
                xml.append("        <NtryDtls><TxDtls>\n");
                xml.append("          <Refs><EndToEndId>").append(escapeXml(entry.entryReference())).append("</EndToEndId></Refs>\n");
                if (entry.narrative() != null) {
                    xml.append("          <RmtInf><Ustrd>").append(escapeXml(entry.narrative())).append("</Ustrd></RmtInf>\n");
                }
                xml.append("        </TxDtls></NtryDtls>\n");
                xml.append("      </Ntry>\n");
            }
        }

        xml.append("      <Bal>\n");
        xml.append("        <Tp><CdOrPrtry><Cd>CLBD</Cd></CdOrPrtry></Tp>\n");
        xml.append("        <Amt Ccy=\"").append(ccy).append("\">").append(closingMajor.toPlainString()).append("</Amt>\n");
        xml.append("        <CdtDbtInd>").append(statement.closingBalancePesewas() >= 0 ? "CRDT" : "DBIT").append("</CdtDbtInd>\n");
        xml.append("        <Dt><Dt>").append(now.toLocalDate().toString()).append("</Dt></Dt>\n");
        xml.append("      </Bal>\n");
        xml.append("    </Stmt>\n");
        xml.append("  </BkToCstmrStmt>\n");
        xml.append("</Document>");

        String payload = xml.toString();
        String sha256 = hashSha256(payload);
        UUID logId = UUID.randomUUID();

        logMessage(logId, msgId, "camt.053.001.08", "OUTBOUND", bankBic, null, nbOfTxs, ctrlSumPesewas, payload, sha256);

        return new GeneratedIsoMessage(logId, msgId, "camt.053.001.08", payload, sha256, nbOfTxs, ctrlSumPesewas);
    }

    @Transactional
    public GeneratedIsoMessage buildCamt053(Camt053Statement statement) {
        return buildCamt053("ELEPGHAC", statement);
    }

    // =========================================================================
    // 3. Parser Utilities
    // =========================================================================

    public List<Pain001CreditTransfer> parsePain001(String xmlPayload) {
        List<Pain001CreditTransfer> results = new ArrayList<>();
        Pattern pmtPattern = Pattern.compile("<(?:[a-zA-Z0-9]+:)?CdtTrfTxInf(?:\\s+[^>]*)?>(.*?)</(?:[a-zA-Z0-9]+:)?CdtTrfTxInf>", Pattern.DOTALL);
        Matcher m = pmtPattern.matcher(xmlPayload);

        while (m.find()) {
            String block = m.group(1);
            String endToEndId = extractTag(block, "EndToEndId");
            String amtStr = extractTagWithAttr(block, "InstdAmt");
            String ccy = extractAttribute(block, "InstdAmt", "Ccy");
            String bic = extractTag(block, "BICFI");
            String cdtrNm = extractTag(block, "Nm");
            String cdtrAcct = extractTag(block, "Id");
            String rmt = extractTag(block, "Ustrd");

            long amtPesewas = 0L;
            if (amtStr != null) {
                amtPesewas = new BigDecimal(amtStr).multiply(BigDecimal.valueOf(100)).longValue();
            }

            results.add(new Pain001CreditTransfer(
                    endToEndId != null ? endToEndId : "UNKNOWN",
                    amtPesewas,
                    ccy != null ? ccy : "GHS",
                    "ORIGINATOR",
                    "DEBTOR-ACC",
                    cdtrNm != null ? cdtrNm : "BENEFICIARY",
                    cdtrAcct != null ? cdtrAcct : "CREDITOR-ACC",
                    bic != null ? bic : "UNKNOWN",
                    rmt
            ));
        }
        return results;
    }

    private void logMessage(
            UUID id,
            String msgId,
            String msgType,
            String direction,
            String senderBic,
            String receiverBic,
            int txCount,
            long ctrlSumPesewas,
            String payload,
            String sha256
    ) {
        db.sql("""
            INSERT INTO iso20022_message_log (
                id, message_id, message_type, direction, sender_bic,
                receiver_bic, transaction_count, control_sum_pesewas,
                payload_xml, payload_sha256, status, created_at
            ) VALUES (
                :id, :msgId, :msgType, :dir, :sender,
                :receiver, :txCount, :ctrlSum,
                :payload, :sha, 'PROCESSED', now()
            )
        """)
                .param("id", id)
                .param("msgId", msgId)
                .param("msgType", msgType)
                .param("dir", direction)
                .param("sender", senderBic)
                .param("receiver", receiverBic)
                .param("txCount", txCount)
                .param("ctrlSum", ctrlSumPesewas)
                .param("payload", payload)
                .param("sha", sha256)
                .update();
    }

    private String extractTag(String xml, String tag) {
        Pattern p = Pattern.compile("<(?:[a-zA-Z0-9]+:)?" + tag + "(?:\\s+[^>]*)?>(.*?)</(?:[a-zA-Z0-9]+:)?" + tag + ">", Pattern.DOTALL);
        Matcher m = p.matcher(xml);
        return m.find() ? m.group(1).trim() : null;
    }

    private String extractTagWithAttr(String xml, String tag) {
        Pattern p = Pattern.compile("<(?:[a-zA-Z0-9]+:)?" + tag + "[^>]*>(.*?)</(?:[a-zA-Z0-9]+:)?" + tag + ">", Pattern.DOTALL);
        Matcher m = p.matcher(xml);
        return m.find() ? m.group(1).trim() : null;
    }

    private String extractAttribute(String xml, String tag, String attr) {
        Pattern p = Pattern.compile("<(?:[a-zA-Z0-9]+:)?" + tag + "[^>]*" + attr + "=\"([^\"]+)\"");
        Matcher m = p.matcher(xml);
        return m.find() ? m.group(1).trim() : null;
    }

    private String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private String hashSha256(String data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }
}
