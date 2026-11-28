package com.elepay.rails;

import com.elepay.common.ApiException;
import com.elepay.security.ApprovedActionHandler;
import com.elepay.security.MakerCheckerService;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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

        // Canonical dual-control check across NativeAuth HS256 (sub=email) and OIDC RS256 (sub=UUID)
        Set<String> proposerIds = MakerCheckerService.extractProposerIdentities(proposal);
        Set<String> approverIds = PlatformRevenueSweepService.currentApproverIdentityTokens(proposal.reviewedBy());
        if (PlatformRevenueSweepService.identitiesOverlap(proposerIds, approverIds)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "dual_control_identity_conflict",
                    "Revenue sweep approver cannot be the same canonical operator as the proposer");
        }

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

        long amountPesewas = parseWholePositiveLong(payload.get("amountPesewas"));
        String currency = payload.get("currency") != null ? String.valueOf(payload.get("currency")).trim() : "GHS";
        String rawIdem = stringOrNull(payload.get("idempotencyKey"));
        String idempotencyKey = rawIdem != null ? rawIdem : ("proposal:" + proposal.id());

        // Prevent payload spoofing: verify any display fields present in the proposal match the authoritative account
        var dest = sweepService.findOperatingAccount(operatingAccountId)
                .orElseThrow(() -> ApiException.notFound("Active operating account not found"));
        verifyOptionalField(payload, "bankCode", dest.bankCode());
        verifyOptionalField(payload, "accountNumber", dest.accountNumber());
        verifyOptionalField(payload, "accountName", dest.accountName());

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

    private static void verifyOptionalField(Map<String, Object> payload, String key, String expected) {
        String provided = stringOrNull(payload.get(key));
        if (provided != null && !provided.equals(expected)) {
            throw ApiException.badRequest("invalid_proposal_payload",
                    "REVENUE_SWEEP payload." + key + " does not match authoritative operating account");
        }
    }

    private static long parseWholePositiveLong(Object raw) {
        if (raw == null || raw instanceof Boolean) {
            throw ApiException.badRequest("invalid_proposal_payload",
                    "REVENUE_SWEEP requires a positive integer payload.amountPesewas");
        }
        try {
            BigDecimal bd = new BigDecimal(String.valueOf(raw).trim());
            long value = bd.longValueExact();
            if (value <= 0) {
                throw new ArithmeticException("non-positive");
            }
            return value;
        } catch (NumberFormatException | ArithmeticException e) {
            throw ApiException.badRequest("invalid_proposal_payload",
                    "REVENUE_SWEEP requires a positive integer payload.amountPesewas");
        }
    }

    private static String stringOrNull(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }
}
