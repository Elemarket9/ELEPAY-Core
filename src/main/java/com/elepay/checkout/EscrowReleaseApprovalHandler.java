package com.elepay.checkout;

import com.elepay.common.ApiException;
import com.elepay.security.ApprovedActionHandler;
import com.elepay.security.MakerCheckerService;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Executes an approved ESCROW_RELEASE proposal for a checkout allocation reserve. */
@Component
class EscrowReleaseApprovalHandler implements ApprovedActionHandler {
    private final CheckoutService checkouts;

    EscrowReleaseApprovalHandler(CheckoutService checkouts) {
        this.checkouts = checkouts;
    }

    @Override
    public boolean supports(String actionType) {
        return "ESCROW_RELEASE".equals(actionType);
    }

    @Override
    public void execute(MakerCheckerService.Proposal proposal) {
        if (proposal == null) {
            throw ApiException.badRequest("invalid_proposal_payload", "ESCROW_RELEASE proposal is required");
        }
        UUID merchantId = proposal.merchantId();
        if (merchantId == null) {
            throw ApiException.badRequest("invalid_proposal_payload", "ESCROW_RELEASE requires merchantId");
        }
        Map<String, Object> payload = proposal.payload() != null ? proposal.payload() : Map.of();
        Object rawReference = payload.get("reference");
        String reference = rawReference == null ? "" : String.valueOf(rawReference).trim();
        if (reference.isEmpty() || "null".equalsIgnoreCase(reference)) {
            throw ApiException.badRequest("invalid_proposal_payload", "ESCROW_RELEASE requires non-blank payload.reference");
        }
        int line = requireNonNegativeInt(payload.get("line"), "line");
        long amount = requirePositiveLong(payload.get("amount"), "amount");
        Object rawReason = payload.get("reason");
        String reason = (rawReason == null || "null".equalsIgnoreCase(String.valueOf(rawReason).trim()))
                ? proposal.reason()
                : String.valueOf(rawReason);
        String actor = (proposal.reviewedBy() != null && !proposal.reviewedBy().isBlank())
                ? proposal.reviewedBy()
                : proposal.proposedBy();
        checkouts.releaseReserve(merchantId, reference, line, amount, reason, actor, "proposal:" + proposal.id());
    }

    private static int requireNonNegativeInt(Object value, String field) {
        Long whole = parseWholeLong(value);
        if (whole == null || whole < 0 || whole > Integer.MAX_VALUE) {
            throw ApiException.badRequest("invalid_proposal_payload",
                    "ESCROW_RELEASE requires non-negative integer payload." + field);
        }
        return whole.intValue();
    }

    private static long requirePositiveLong(Object value, String field) {
        Long whole = parseWholeLong(value);
        if (whole == null || whole <= 0) {
            throw ApiException.badRequest("invalid_proposal_payload",
                    "ESCROW_RELEASE requires positive integer payload." + field);
        }
        return whole;
    }

    private static Long parseWholeLong(Object value) {
        if (value == null) return null;
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        if (value instanceof BigInteger bi) {
            try {
                return bi.longValueExact();
            } catch (ArithmeticException e) {
                return null;
            }
        }
        if (value instanceof BigDecimal bd) {
            try {
                return bd.longValueExact();
            } catch (ArithmeticException e) {
                return null;
            }
        }
        if (value instanceof Float || value instanceof Double) {
            double d = ((Number) value).doubleValue();
            if (!Double.isFinite(d)) return null;
            try {
                return BigDecimal.valueOf(d).longValueExact();
            } catch (ArithmeticException e) {
                return null;
            }
        }
        if (value instanceof Number n) {
            try {
                return new BigDecimal(n.toString()).longValueExact();
            } catch (RuntimeException e) {
                return null;
            }
        }
        if (value instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed)) return null;
            try {
                return Long.parseLong(trimmed);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
