package com.elepay.payout;

import com.elepay.security.ApprovedActionHandler;
import com.elepay.security.MakerCheckerService;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
class LargePayoutHandler implements ApprovedActionHandler {
    private final MerchantPayouts payouts;
    private final ObjectMapper json;

    LargePayoutHandler(@Lazy MerchantPayouts payouts, ObjectMapper json) {
        this.payouts = payouts;
        this.json = json;
    }

    @Override
    public boolean supports(String actionType) {
        return "LARGE_PAYOUT".equals(actionType);
    }

    @Override
    public void execute(MakerCheckerService.Proposal proposal) {
        var payload = proposal.payload();
        if ("payout-retry".equals(proposal.resourceType())) {
            UUID merchant = UUID.fromString(String.valueOf(payload.get("merchantId")));
            String reference = String.valueOf(payload.get("reference"));
            String key = String.valueOf(payload.get("idempotencyKey"));
            payouts.executeRetry(merchant, reference, key, proposal.reviewedBy());
            return;
        }
        if ("payout-batch".equals(proposal.resourceType()) || payload.containsKey("request")) {
            UUID merchant = UUID.fromString(String.valueOf(payload.get("merchantId")));
            String key = String.valueOf(payload.get("idempotencyKey"));
            MerchantPayouts.BatchInput batchInput = json.convertValue(payload.get("request"), MerchantPayouts.BatchInput.class);
            payouts.executeBatch(merchant, key, batchInput, proposal.reviewedBy());
            return;
        }
        if (payload.get("destinationId") == null) return;
        UUID merchant = UUID.fromString(String.valueOf(payload.get("merchantId")));
        long amount = payload.get("amount") instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(payload.get("amount")));
        UUID destination = UUID.fromString(String.valueOf(payload.get("destinationId")));
        String key = String.valueOf(payload.get("idempotencyKey"));
        payouts.executeWithdrawal(merchant, key, new MerchantPayouts.Withdrawal(amount, destination), proposal.reviewedBy());
    }
}
