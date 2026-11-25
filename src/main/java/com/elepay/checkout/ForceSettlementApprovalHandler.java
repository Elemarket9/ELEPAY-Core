package com.elepay.checkout;

import com.elepay.security.ApprovedActionHandler;
import com.elepay.security.MakerCheckerService;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Executes an approved FORCE_SETTLEMENT proposal (settlement inside the delay window). */
@Component
class ForceSettlementApprovalHandler implements ApprovedActionHandler {
    private final CheckoutService checkouts;

    ForceSettlementApprovalHandler(CheckoutService checkouts) {
        this.checkouts = checkouts;
    }

    @Override
    public boolean supports(String actionType) {
        return "FORCE_SETTLEMENT".equals(actionType);
    }

    @Override
    public void execute(MakerCheckerService.Proposal proposal) {
        Object raw = proposal.payload().get("merchantId");
        UUID merchantId = raw == null || String.valueOf(raw).isBlank() ? null : UUID.fromString(String.valueOf(raw));
        checkouts.settleDue(true, merchantId);
    }
}
