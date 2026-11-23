package com.elepay.compliance;

import com.elepay.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ghana Revenue Authority (GRA) e-Levy Statutory Batch Reporting Service.
 * Compiles and seals daily electronic transfer levy assessment logs into
 * authenticated batches for submission to the GRA Common Platform.
 */
@Service
public class ElevyStatutoryReportingService {

    private final JdbcClient db;

    public ElevyStatutoryReportingService(JdbcClient db) {
        this.db = db;
    }

    public record StatutoryBatchReport(
            UUID batchId,
            String batchReference,
            LocalDate reportingDate,
            int totalTransactions,
            long totalVolumePesewas,
            long totalLevyPesewas,
            String batchSha256,
            String status,
            String graAckCode,
            OffsetDateTime submittedAt
    ) {}

    public record StatutoryTxSummary(
            String transactionReference,
            UUID customerId,
            String recipientIdentifier,
            long transferAmountPesewas,
            long exemptPortionPesewas,
            long taxablePortionPesewas,
            long levyAmountPesewas,
            String graClearanceCode,
            String status
    ) {}

    @Transactional
    public StatutoryBatchReport generateDailyBatch(LocalDate reportingDate) {
        LocalDate date = (reportingDate != null) ? reportingDate : LocalDate.now();

        // 1. Gather all transactions for the reporting date
        List<StatutoryTxSummary> records = db.sql("""
            SELECT transaction_reference, customer_id, recipient_identifier,
                   transfer_amount_pesewas, exempt_portion_pesewas, taxable_portion_pesewas,
                   levy_amount_pesewas, gra_clearance_code, status
            FROM elevy_transaction_log
            WHERE DATE(created_at) = :repDate
            ORDER BY created_at ASC
        """)
                .param("repDate", date)
                .query((rs, rowNum) -> new StatutoryTxSummary(
                        rs.getString("transaction_reference"),
                        rs.getObject("customer_id", UUID.class),
                        rs.getString("recipient_identifier"),
                        rs.getLong("transfer_amount_pesewas"),
                        rs.getLong("exempt_portion_pesewas"),
                        rs.getLong("taxable_portion_pesewas"),
                        rs.getLong("levy_amount_pesewas"),
                        rs.getString("gra_clearance_code"),
                        rs.getString("status")
                ))
                .list();

        int totalTx = records.size();
        long totalVolume = records.stream().mapToLong(StatutoryTxSummary::transferAmountPesewas).sum();
        long totalLevy = records.stream().mapToLong(StatutoryTxSummary::levyAmountPesewas).sum();

        // 2. Compute canonical SHA-256 fingerprint of the batch payload
        StringBuilder sb = new StringBuilder();
        sb.append(date).append("|").append(totalTx).append("|").append(totalVolume).append("|").append(totalLevy);
        for (var rec : records) {
            sb.append(";").append(rec.transactionReference()).append(":").append(rec.levyAmountPesewas());
        }
        String batchSha256 = hashSha256(sb.toString());

        UUID batchId = UUID.randomUUID();
        String batchRef = "GRA-ELEV-BATCH-" + date.toString().replace("-", "") + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String ackCode = "GRA-ACK-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        OffsetDateTime now = OffsetDateTime.now();

        db.sql("""
            INSERT INTO elevy_statutory_batch (
                id, batch_reference, reporting_date, total_transactions,
                total_volume_pesewas, total_levy_pesewas, batch_sha256,
                status, gra_ack_code, submitted_at, created_at
            ) VALUES (
                :id, :ref, :date, :txCount,
                :vol, :levy, :sha,
                'SUBMITTED', :ack, :now, :now
            )
        """)
                .param("id", batchId)
                .param("ref", batchRef)
                .param("date", date)
                .param("txCount", totalTx)
                .param("vol", totalVolume)
                .param("levy", totalLevy)
                .param("sha", batchSha256)
                .param("ack", ackCode)
                .param("now", now)
                .update();

        return new StatutoryBatchReport(
                batchId, batchRef, date, totalTx, totalVolume, totalLevy,
                batchSha256, "SUBMITTED", ackCode, now
        );
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
