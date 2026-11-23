package com.elepay.compliance;

import com.elepay.common.ApiException;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Regulatory report generation engine (Algorithm 047).
 * Generates official compliance filings for Bank of Ghana (BoG) EMI monthly returns,
 * Financial Intelligence Centre (FIC) Cash Transaction Reports (CTR), and Suspicious Transaction Reports (STR).
 * Strictly escapes JSON export formatting to prevent malformed export files under adversarial/special characters.
 * Mandates dual-control maker-checker approval prior to regulatory submission.
 */
@Service
public class Reporting {

    public enum ReportType {
        BOG_EMI_MONTHLY,
        FIC_CTR,
        FIC_STR
    }

    public enum ReportStatus {
        DRAFT,
        APPROVED_FOR_FILING
    }

    public record RegulatoryReport(
            UUID id,
            String reportReference,
            ReportType reportType,
            String reportingPeriod,
            ReportStatus status,
            UUID preparedBy,
            UUID approvedBy,
            String contentPayload,
            Instant createdAt,
            Instant approvedAt
    ) {}

    public record BogEmiMetrics(
            long totalTransactionCount,
            long totalVolumePesewas,
            long activeWalletsCount,
            long floatBalancePesewas,
            int unresolvedDisputesCount
    ) {}

    public record CtrEntry(
            UUID transactionId,
            UUID customerId,
            String customerName,
            long amountPesewas,
            String counterparty,
            Instant timestamp
    ) {}

    public record StrEntry(
            String caseNumber,
            UUID customerId,
            String customerName,
            String suspiciousReason,
            long totalExposurePesewas,
            String evidenceSummary
    ) {}

    private final JdbcClient db;
    private final Map<UUID, RegulatoryReport> reportStore = new ConcurrentHashMap<>();

    public Reporting(JdbcClient db) {
        this.db = db;
    }

    /**
     * Escapes raw string into RFC 8259 compliant JSON string literal content.
     * Prevents export file corruption when user names, reasons, or notes contain quotes, backslashes, or control characters.
     */
    public static String escapeJson(String input) {
        if (input == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(input.length() + 16);
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < ' ') {
                        String hex = String.format("\\u%04x", (int) ch);
                        out.append(hex);
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        return out.toString();
    }

    public RegulatoryReport generateBogEmiMonthlyReport(YearMonth period, BogEmiMetrics metrics, UUID preparedBy) {
        Objects.requireNonNull(period, "period cannot be null");
        Objects.requireNonNull(metrics, "metrics cannot be null");
        Objects.requireNonNull(preparedBy, "preparedBy cannot be null");

        String ref = "BOG-EMI-" + period + "-" + System.currentTimeMillis() % 10_000;
        String jsonPayload = String.format("""
                {
                  "regulator": "Bank of Ghana",
                  "framework": "Payment Systems and Services Act (Act 987)",
                  "institution": "ELEPAY Ghana Limited",
                  "reportingPeriod": "%s",
                  "metrics": {
                    "totalTransactionCount": %d,
                    "totalVolumeGhs": %.2f,
                    "totalVolumePesewas": %d,
                    "activeWalletsCount": %d,
                    "floatBalanceGhs": %.2f,
                    "floatBalancePesewas": %d,
                    "unresolvedDisputesCount": %d
                  }
                }
                """,
                escapeJson(period.toString()),
                metrics.totalTransactionCount(),
                metrics.totalVolumePesewas() / 100.0,
                metrics.totalVolumePesewas(),
                metrics.activeWalletsCount(),
                metrics.floatBalancePesewas() / 100.0,
                metrics.floatBalancePesewas(),
                metrics.unresolvedDisputesCount());

        return createReport(ReportType.BOG_EMI_MONTHLY, ref, period.toString(), jsonPayload, preparedBy);
    }

    public RegulatoryReport generateFicCtrReport(String period, List<CtrEntry> entries, UUID preparedBy) {
        Objects.requireNonNull(entries, "entries cannot be null");
        Objects.requireNonNull(preparedBy, "preparedBy cannot be null");

        String ref = "FIC-CTR-" + period + "-" + System.currentTimeMillis() % 10_000;
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"regulator\": \"Financial Intelligence Centre (FIC) Ghana\",\n");
        sb.append("  \"reportType\": \"Cash Transaction Report (CTR)\",\n");
        sb.append("  \"statutoryThresholdGhs\": 20000.00,\n");
        sb.append("  \"period\": \"").append(escapeJson(period)).append("\",\n");
        sb.append("  \"recordCount\": ").append(entries.size()).append(",\n");
        sb.append("  \"transactions\": [\n");

        for (int i = 0; i < entries.size(); i++) {
            CtrEntry e = entries.get(i);
            sb.append("    {")
              .append("\"id\":\"").append(e.transactionId()).append("\",")
              .append("\"customer\":\"").append(e.customerId()).append("\",")
              .append("\"name\":\"").append(escapeJson(e.customerName())).append("\",")
              .append("\"counterparty\":\"").append(escapeJson(e.counterparty())).append("\",")
              .append(String.format("\"amountGhs\":%.2f,", e.amountPesewas() / 100.0))
              .append("\"amountPesewas\":").append(e.amountPesewas()).append(",")
              .append("\"timestamp\":\"").append(e.timestamp()).append("\"")
              .append("}");
            if (i < entries.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n}");

        return createReport(ReportType.FIC_CTR, ref, period, sb.toString(), preparedBy);
    }

    public RegulatoryReport generateFicStrReport(String period, List<StrEntry> entries, UUID preparedBy) {
        Objects.requireNonNull(entries, "entries cannot be null");
        Objects.requireNonNull(preparedBy, "preparedBy cannot be null");

        String ref = "FIC-STR-" + period + "-" + System.currentTimeMillis() % 10_000;
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"regulator\": \"Financial Intelligence Centre (FIC) Ghana\",\n");
        sb.append("  \"reportType\": \"Suspicious Transaction Report (STR)\",\n");
        sb.append("  \"statutoryBasis\": \"Anti-Money Laundering Act, 2020 (Act 1044)\",\n");
        sb.append("  \"period\": \"").append(escapeJson(period)).append("\",\n");
        sb.append("  \"suspiciousCases\": [\n");

        for (int i = 0; i < entries.size(); i++) {
            StrEntry e = entries.get(i);
            sb.append("    {")
              .append("\"caseNumber\":\"").append(escapeJson(e.caseNumber())).append("\",")
              .append("\"customer\":\"").append(e.customerId()).append("\",")
              .append("\"name\":\"").append(escapeJson(e.customerName())).append("\",")
              .append("\"reason\":\"").append(escapeJson(e.suspiciousReason())).append("\",")
              .append(String.format("\"exposureGhs\":%.2f,", e.totalExposurePesewas() / 100.0))
              .append("\"exposurePesewas\":").append(e.totalExposurePesewas()).append(",")
              .append("\"evidenceSummary\":\"").append(escapeJson(e.evidenceSummary())).append("\"")
              .append("}");
            if (i < entries.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n}");

        return createReport(ReportType.FIC_STR, ref, period, sb.toString(), preparedBy);
    }

    public RegulatoryReport approveReport(UUID reportId, UUID complianceOfficerId) {
        RegulatoryReport existing = reportStore.get(reportId);
        if (existing == null) {
            throw ApiException.notFound("Regulatory report not found: " + reportId);
        }

        // Maker-checker dual control rule: preparer cannot approve own report
        if (Objects.equals(existing.preparedBy(), complianceOfficerId)) {
            throw ApiException.conflict("maker_checker_violation", "Compliance officer cannot approve a report they prepared");
        }

        Instant now = Instant.now();
        RegulatoryReport approved = new RegulatoryReport(
                existing.id(),
                existing.reportReference(),
                existing.reportType(),
                existing.reportingPeriod(),
                ReportStatus.APPROVED_FOR_FILING,
                existing.preparedBy(),
                complianceOfficerId,
                existing.contentPayload(),
                existing.createdAt(),
                now
        );

        reportStore.put(reportId, approved);

        if (db != null) {
            try {
                db.sql("""
                    UPDATE regulatory_report
                    SET status = 'APPROVED_FOR_FILING', approved_by = ?, approved_at = ?
                    WHERE id = ?
                    """)
                    .params(complianceOfficerId, now, reportId)
                    .update();
            } catch (Exception ignored) {}
        }

        return approved;
    }

    private RegulatoryReport createReport(ReportType type, String ref, String period, String payload, UUID preparedBy) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        RegulatoryReport report = new RegulatoryReport(
                id, ref, type, period, ReportStatus.DRAFT, preparedBy, null, payload, now, null
        );

        reportStore.put(id, report);

        if (db != null) {
            try {
                db.sql("""
                    INSERT INTO regulatory_report
                    (id, report_reference, report_type, reporting_period, status, prepared_by, content_payload, created_at)
                    VALUES (?, ?, ?, ?, 'DRAFT', ?, ?, ?)
                    """)
                    .params(id, ref, type.name(), period, preparedBy, payload, now)
                    .update();
            } catch (Exception ignored) {}
        }

        return report;
    }

    public RegulatoryReport getReport(UUID reportId) {
        return reportStore.get(reportId);
    }
}
