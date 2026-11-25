package com.elepay.rails;

import com.elepay.common.ApiException;
import com.elepay.identity.Actor;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operations endpoints for live MTN Ghana OpenAPI float balance and subscriber account validation
 * (MTN MoMo OpenAPI UAT Sheets 4 & 5).
 */
@RestController
@RequestMapping("/v1/ops/mtn")
class MtnOpsController {
    private final Optional<MtnProductionRail> mtnRail;

    MtnOpsController(Optional<MtnProductionRail> mtnRail) {
        this.mtnRail = mtnRail;
    }

    @GetMapping("/balance")
    public MtnProductionRail.AccountBalance balance(@RequestParam(defaultValue = "payout") String direction) {
        Actor actor = Actor.current();
        if (!actor.canManageFinance() && !actor.canFreezeRisk() && !actor.isOpsAdmin()) {
            throw ApiException.forbidden("Finance, Risk/Compliance, or Ops Admin role required for MTN float balance");
        }
        return mtnRail
                .map(rail -> rail.getAccountBalance(direction))
                .orElseGet(() -> new MtnProductionRail.AccountBalance(
                        false, 0L, "GHS", "MTN production rail is not enabled in this environment"));
    }

    @GetMapping("/validate-account")
    public MtnProductionRail.AccountValidation validateAccount(
            @RequestParam(defaultValue = "payout") String direction,
            @RequestParam String msisdn) {
        Actor.current().requireOps();
        return mtnRail
                .map(rail -> rail.validateAccountHolder(direction, msisdn))
                .orElseGet(() -> new MtnProductionRail.AccountValidation(
                        false, false, msisdn, "MTN production rail is not enabled in this environment"));
    }
}
