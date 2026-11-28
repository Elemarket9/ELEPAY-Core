package com.elepay.rails;

import com.elepay.identity.Actor;
import org.springframework.web.bind.annotation.*;

@RestController
class SettlementCreditEndpoint {
    private final SettlementFunding funding;
    SettlementCreditEndpoint(SettlementFunding funding) { this.funding = funding; }

    @PostMapping("/v1/ops/custodian/credits/{reference}/sync")
    void sync(@PathVariable String reference) {
        Actor.current().requireFinance();
        funding.confirmCredit(reference);
    }

    @PostMapping("/v1/ops/custodian/payouts/{reference}/reconcile")
    void reconcile(@PathVariable String reference) {
        Actor.current().requireFinance();
        funding.reconcileDebit(reference);
    }
}
