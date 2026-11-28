package com.elepay.rails;

import com.elepay.common.ApiException;
import com.elepay.security.ApprovedActionHandler;
import com.elepay.security.MakerCheckerService;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Executes a dual-control-approved {@code OPERATING_ACCOUNT_REGISTER} proposal to register a
 * new ELEPAY Corporate Operating Bank Account for V87 earned-revenue sweeps.
 */
@Component
class OperatingAccountApprovalHandler implements ApprovedActionHandler {

    private final PlatformRevenueSweepService sweepService;

    OperatingAccountApprovalHandler(PlatformRevenueSweepService sweepService) {
        this.sweepService = sweepService;
    }

    @Override
    public boolean supports(String actionType) {
        return "OPERATING_ACCOUNT_REGISTER".equals(actionType);
    }

    @Override
    public void execute(MakerCheckerService.Proposal proposal) {
        if (proposal == null || proposal.payload() == null) {
            throw ApiException.badRequest("invalid_proposal_payload",
                    "OPERATING_ACCOUNT_REGISTER proposal payload is required");
        }
        Map<String, Object> payload = proposal.payload();
        String bankCode = stringField(payload, "bankCode");
        String accountNumber = stringField(payload, "accountNumber");
        String accountName = stringField(payload, "accountName");
        String currency = payload.get("currency") != null ? String.valueOf(payload.get("currency")) : "GHS";
        String proposedByEmail = stringFieldOrNull(payload, "verifiedByEmail");
        if (proposedByEmail == null) {
            proposedByEmail = stringFieldOrNull(payload, "_proposerEmail");
        }
        Set<String> proposerIds = MakerCheckerService.extractProposerIdentities(proposal);
        Set<String> approverIds = PlatformRevenueSweepService.currentApproverIdentityTokens(proposal.reviewedBy());
        if (PlatformRevenueSweepService.identitiesOverlap(proposerIds, approverIds)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "dual_control_identity_conflict",
                    "Operating account registration approver cannot be the same canonical operator as the proposer");
        }

        sweepService.registerOperatingAccount(
                bankCode,
                accountNumber,
                accountName,
                currency,
                proposal.proposedBy(),
                proposedByEmail,
                proposal.reviewedBy()
        );
    }

    private static String stringField(Map<String, Object> payload, String key) {
        Object val = payload.get(key);
        if (val == null || String.valueOf(val).isBlank()) {
            throw ApiException.badRequest("invalid_proposal_payload",
                    "OPERATING_ACCOUNT_REGISTER requires non-blank payload." + key);
        }
        return String.valueOf(val).trim();
    }

    private static String stringFieldOrNull(Map<String, Object> payload, String key) {
        Object val = payload.get(key);
        if (val == null) return null;
        String s = String.valueOf(val).trim();
        return s.isEmpty() ? null : s;
    }
}
