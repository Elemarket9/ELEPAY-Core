package com.elepay.wallet;

import com.elepay.common.References;
import com.elepay.security.ApprovedActionHandler;
import com.elepay.security.MakerCheckerService;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class PhoneChangeHandler implements ApprovedActionHandler {
    private final JdbcClient db;

    PhoneChangeHandler(JdbcClient db) {
        this.db = db;
    }

    @Override
    public boolean supports(String actionType) {
        return "PHONE_CHANGE".equals(actionType);
    }

    @Override
    public void execute(MakerCheckerService.Proposal proposal) {
        UUID id = UUID.fromString(String.valueOf(proposal.payload().get("customerId")));
        if ("true".equals(String.valueOf(proposal.payload().get("resetUssdPin")))) {
            db.sql("""
                    UPDATE customer SET ussd_pin_hash = NULL, ussd_pin_failures = 0, ussd_pin_total_failures = 0,
                           ussd_pin_hard_locked = false, ussd_locked_until = NULL WHERE id = :id""")
                    .param("id", id).update();
            return;
        }
        String phone = References.msisdn(String.valueOf(proposal.payload().get("phone")));
        String old = db.sql("SELECT phone FROM customer WHERE id = :id FOR UPDATE").param("id", id).query(String.class).single();
        db.sql("INSERT INTO customer_phone_history (customer_id, phone, changed_by) VALUES (:id, :phone, :by)")
                .param("id", id).param("phone", old).param("by", proposal.reviewedBy()).update();
        db.sql("UPDATE customer SET phone = :phone WHERE id = :id").param("phone", phone).param("id", id).update();
        db.sql("UPDATE recipient_confirmation SET status = 'revoked' WHERE recipient_customer_id = :id AND status = 'active'")
                .param("id", id).update();
    }
}
