package com.elepay.security;

import com.elepay.common.ApiException;
import com.elepay.identity.Actor;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Sensitive operations are proposed by the authenticated caller and executed only after a
 * different {@code ops_admin} approves the proposal. Approver names are never taken from the body.
 */
@Service
public class DualControl {
    private final MakerCheckerService makerChecker;
    private final boolean enforced;

    public DualControl(MakerCheckerService makerChecker,
                       @Value("${elepay.security.dual-control.enforced:true}") boolean enforced) {
        this.makerChecker = makerChecker;
        this.enforced = enforced;
    }

    public boolean enforced() {
        return enforced;
    }

    /**
     * When dual control is on, records a proposal and stops the caller. The approver completes it
     * through the maker-checker approve endpoint.
     */
    public void hold(String actionType, String resourceType, String resourceId, UUID merchantId,
                     Map<String, Object> payload, String reason, Actor actor) {
        if (!enforced) return;
        var proposal = makerChecker.propose(actionType, resourceType, resourceId, merchantId, payload, reason, actor.subject());
        throw new ApiException(HttpStatus.ACCEPTED, "pending_approval",
                "A different operations administrator must approve proposal " + proposal.id());
    }
}
