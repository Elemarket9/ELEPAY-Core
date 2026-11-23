package com.elepay.identity;

import com.elepay.security.ApprovedActionHandler;
import com.elepay.security.MakerCheckerService;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class KillSwitchApprovalHandler implements ApprovedActionHandler {
    private final KillSwitchService killSwitchService;

    KillSwitchApprovalHandler(KillSwitchService killSwitchService) {
        this.killSwitchService = killSwitchService;
    }

    @Override
    public boolean supports(String actionType) {
        return "MERCHANT_UNFREEZE".equals(actionType) || "MODULE_ENABLE".equals(actionType);
    }

    @Override
    public void execute(MakerCheckerService.Proposal proposal) {
        if ("MERCHANT_UNFREEZE".equals(proposal.actionType())) {
            UUID merchant = proposal.merchantId();
            killSwitchService.setMerchantFrozen(merchant, false, proposal.reason(), proposal.reviewedBy());
            return;
        }
        Object module = proposal.payload().get("module");
        killSwitchService.setModuleEnabled(String.valueOf(module), true, proposal.reason(), proposal.reviewedBy());
    }
}
