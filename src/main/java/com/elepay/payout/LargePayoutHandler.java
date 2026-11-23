package com.elepay.payout;

import com.elepay.security.ApprovedActionHandler;
import com.elepay.security.MakerCheckerService;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Component
class LargePayoutHandler implements ApprovedActionHandler {
    private final MerchantPayouts payouts;

    LargePayoutHandler(@Lazy MerchantPayouts payouts) {
        this.payouts = payouts;
    }

    @Override
    public boolean supports(String actionType) {
        return "LARGE_PAYOUT".equals(actionType);
    }

    @Override
    public void execute(MakerCheckerService.Proposal proposal) {
        var payload = proposal.payload();
        if (payload.get("destinationId") == null) return;
        UUID merchant = UUID.fromString(String.valueOf(payload.get("merchantId")));
        long amount = payload.get("amount") instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(payload.get("amount")));
        UUID destination = UUID.fromString(String.valueOf(payload.get("destinationId")));
        String key = String.valueOf(payload.get("idempotencyKey"));
        payouts.executeWithdrawal(merchant, key, new MerchantPayouts.Withdrawal(amount, destination), proposal.reviewedBy());
    }
}
