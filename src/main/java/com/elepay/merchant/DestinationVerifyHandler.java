package com.elepay.merchant;

import com.elepay.security.ApprovedActionHandler;
import com.elepay.security.MakerCheckerService;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class DestinationVerifyHandler implements ApprovedActionHandler {
    private final JdbcClient db;

    DestinationVerifyHandler(JdbcClient db) {
        this.db = db;
    }

    @Override
    public boolean supports(String actionType) {
        return "DESTINATION_OPS_VERIFY".equals(actionType);
    }

    @Override
    public void execute(MakerCheckerService.Proposal proposal) {
        UUID merchant = UUID.fromString(String.valueOf(proposal.payload().get("merchantId")));
        UUID destination = UUID.fromString(String.valueOf(proposal.payload().get("destinationId")));
        db.sql("""
                UPDATE settlement_destination SET verification = 'verified', verified_by = :by,
                       verified_name = coalesce(verified_name, account_name),
                       oob_verification_status = 'confirmed', updated_at = now()
                WHERE id = :d AND owner_type = 'merchant' AND owner_id = :m AND status = 'active'""")
                .param("by", "ops:" + proposal.reviewedBy()).param("d", destination).param("m", merchant).update();
    }
}
