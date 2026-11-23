package com.elepay.payout;

import com.elepay.security.ApprovedActionHandler;
import com.elepay.security.MakerCheckerService;
import org.springframework.stereotype.Component;

@Component
class VelocityCeilingHandler implements ApprovedActionHandler {
    private final PayoutVelocityService velocity;

    VelocityCeilingHandler(PayoutVelocityService velocity) {
        this.velocity = velocity;
    }

    @Override
    public boolean supports(String actionType) {
        return "VELOCITY_CEILING_CHANGE".equals(actionType);
    }

    @Override
    public void execute(MakerCheckerService.Proposal proposal) {
        var payload = proposal.payload();
        velocity.updateCeilings(longOf(payload.get("hourly")), longOf(payload.get("daily")),
                longOf(payload.get("single")), Boolean.parseBoolean(String.valueOf(payload.get("autoFreeze"))),
                proposal.reviewedBy());
    }

    private static long longOf(Object value) {
        if (value instanceof Number n) return n.longValue();
        return Long.parseLong(String.valueOf(value));
    }
}
