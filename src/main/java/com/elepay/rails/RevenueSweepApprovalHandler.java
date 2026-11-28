package com.elepay.rails;

import com.elepay.common.ApiException;
import com.elepay.security.ApprovedActionHandler;
import com.elepay.security.MakerCheckerService;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Executes a dual-control-approved {@code REVENUE_SWEEP} proposal to sweep earned ELEPAY
 * platform revenue from the Custodian Trust Account to a pre-approved corporate operating account.
 */
@Component
class RevenueSweepApprovalHandler implements ApprovedActionHandler {

    private final PlatformRevenueSweepService sweepService;

    RevenueSweepApprovalHandler(PlatformRevenueSweepService sweepService) {
        this.sweepService = sweepService;
    }

    @Override
    public boolean supports(String actionType) {
        return "REVENUE_SWEEP".equals(actionType);
    }

    @Override
    public void execute(MakerCheckerService.Proposal proposal) {
        if (proposal == null || proposal.payload() == null) {
            throw ApiException.badRequest("invalid_proposal_payload", "REVENUE_SWEEP proposal payload is required");
        }
        Map<String, Object> payload = proposal.payload();
        UUID operatingAccountId;
        try {
            Object rawOpId = payload.get("operatingAccountId");
            if (rawOpId == null || String.valueOf(rawOpId).isBlank()) {
                throw new IllegalArgumentException("missing");
            }
            operatingAccountId = UUID.fromString(String.valueOf(rawOpId).trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_proposal_payload",
                    "REVENUE_SWEEP requires a valid UUID payload.operatingAccountId");
        }
        long amountPesewas;
        try {
            Object rawAmt = payload.get("amountPesewas");
            if (rawAmt == null) throw new NumberFormatException("null");
            amountPesewas = rawAmt instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(rawAmt).trim());
            if (amountPesewas <= 0) throw new NumberFormatException("non-positive");
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("invalid_proposal_payload",
                    "REVENUE_SWEEP requires a positive integer payload.amountPesewas");
        }
        String currency = payload.get("currency") != null ? String.valueOf(payload.get("currency")) : "GHS";
        String idempotencyKey = payload.get("idempotencyKey") != null
                ? String.valueOf(payload.get("idempotencyKey"))
                : "proposal:" + proposal.id();

        sweepService.executeSweep(
                operatingAccountId,
                amountPesewas,
                currency,
                idempotencyKey,
                proposal.proposedBy(),
                proposal.reviewedBy(),
                proposal.id()
        );
    }
}
