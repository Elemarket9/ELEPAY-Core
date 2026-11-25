package com.elepay.checkout;

import com.elepay.rails.RailTransactions;
import com.elepay.security.ApprovedActionHandler;
import com.elepay.security.MakerCheckerService;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class DisputeApprovalHandler implements ApprovedActionHandler {
    private final CheckoutService checkouts;
    private final RailTransactions rails;

    DisputeApprovalHandler(CheckoutService checkouts, RailTransactions rails) {
        this.checkouts = checkouts;
        this.rails = rails;
    }

    @Override
    public boolean supports(String actionType) {
        return "DISPUTE_RESOLUTION".equals(actionType);
    }

    @Override
    public void execute(MakerCheckerService.Proposal proposal) {
        UUID id = UUID.fromString(String.valueOf(proposal.payload().get("disputeId")));
        String outcome = String.valueOf(proposal.payload().get("outcome"));
        Object rawNote = proposal.payload().get("note");
        // Older proposals stored String.valueOf(null) == "null".
        String note = rawNote == null || "null".equals(rawNote) ? null : String.valueOf(rawNote);
        checkouts.resolveDispute(proposal.reviewedBy(), id, outcome, note);
        // approve() is transactional: submit the rail refund only once the approval commits.
        checkouts.disputeRefundRailTx(id).ifPresent(tx -> AfterCommit.run(() -> rails.submit(tx)));
    }
}
