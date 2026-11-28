package com.elepay.rails;

import com.elepay.security.ApprovedActionHandler;
import com.elepay.security.MakerCheckerService;
import org.springframework.stereotype.Component;

@Component
class CustodianApprovalHandler implements ApprovedActionHandler {
    private final CustodianBankBackstopService custodian;
    private final OperatingAccountApprovalHandler operatingAccountHandler;

    CustodianApprovalHandler(
            CustodianBankBackstopService custodian,
            OperatingAccountApprovalHandler operatingAccountHandler
    ) {
        this.custodian = custodian;
        this.operatingAccountHandler = operatingAccountHandler;
    }

    @Override
    public boolean supports(String actionType) {
        return "CUSTODIAN_CONFIG".equals(actionType);
    }

    @Override
    public void execute(MakerCheckerService.Proposal proposal) {
        var payload = proposal.payload();
        if (payload != null && "REGISTER_OPERATING_ACCOUNT".equals(String.valueOf(payload.get("operation")))) {
            operatingAccountHandler.execute(proposal);
            return;
        }
        custodian.updateConfig(num(payload.get("dailyOutflowCapPesewas")), num(payload.get("perPayoutCapPesewas")),
                num(payload.get("minReserveFloorPesewas")), num(payload.get("lastReconciledBankBalancePesewas")),
                bool(payload.get("outflowFrozen")), proposal.reviewedBy());
    }

    private static Long num(Object value) {
        if (value == null || "null".equals(String.valueOf(value))) return null;
        return Long.valueOf(String.valueOf(value));
    }

    private static Boolean bool(Object value) {
        if (value == null || "null".equals(String.valueOf(value))) return null;
        return Boolean.valueOf(String.valueOf(value));
    }
}
