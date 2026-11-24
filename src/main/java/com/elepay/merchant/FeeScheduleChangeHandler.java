package com.elepay.merchant;

import com.elepay.common.ApiException;
import com.elepay.security.ApprovedActionHandler;
import com.elepay.security.MakerCheckerService;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Executes approved {@code FEE_SCHEDULE_CHANGE} maker-checker proposals by updating
 * the merchant's fee schedule (and optional settlement delay / review status).
 */
@Component
class FeeScheduleChangeHandler implements ApprovedActionHandler {
    private final JdbcClient db;

    FeeScheduleChangeHandler(JdbcClient db) {
        this.db = db;
    }

    @Override
    public boolean supports(String actionType) {
        return "FEE_SCHEDULE_CHANGE".equals(actionType);
    }

    @Override
    public void execute(MakerCheckerService.Proposal proposal) {
        UUID merchantId = proposal.merchantId() != null
                ? proposal.merchantId()
                : UUID.fromString(proposal.resourceId());
        Map<String, Object> payload = proposal.payload() != null ? proposal.payload() : Map.of();

        Integer feeBps = parseInt(payload.get("feeBps"));
        Long feeFixed = parseLong(payload.get("feeFixed"));
        Integer settlementDelayHours = parseInt(payload.get("settlementDelayHours"));
        String decision = payload.get("decision") == null ? null : String.valueOf(payload.get("decision"));
        String status = null;
        if (decision != null && !"null".equals(decision) && !decision.isBlank()) {
            status = switch (decision) {
                case "approve" -> "active";
                case "reject" -> "rejected";
                case "suspend" -> "suspended";
                default -> null;
            };
        }

        int updated = db.sql("""
                UPDATE merchant
                SET status = coalesce(:status, status),
                    fee_bps = coalesce(:bps, fee_bps),
                    fee_fixed = coalesce(:fixed, fee_fixed),
                    settlement_delay_hours = coalesce(:delay, settlement_delay_hours),
                    reviewed_by = coalesce(:by, reviewed_by),
                    reviewed_at = now(),
                    review_note = coalesce(:note, review_note)
                WHERE id = :id""")
                .param("status", status)
                .param("bps", feeBps)
                .param("fixed", feeFixed)
                .param("delay", settlementDelayHours)
                .param("by", proposal.reviewedBy())
                .param("note", proposal.reviewNote() != null ? proposal.reviewNote() : proposal.reason())
                .param("id", merchantId)
                .update();

        if (updated == 0) {
            throw ApiException.notFound("Merchant not found for FEE_SCHEDULE_CHANGE: " + merchantId);
        }
    }

    private static Integer parseInt(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        String s = String.valueOf(value).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) return null;
        return Integer.parseInt(s);
    }

    private static Long parseLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        String s = String.valueOf(value).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) return null;
        return Long.parseLong(s);
    }
}
