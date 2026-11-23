package com.elepay.merchanttools.accounting;

import com.elepay.common.ApiException;
import com.elepay.merchanttools.MerchantToolAccess;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** The merchant's own books: statements built from posted journal entries, not estimates. */
@RestController
@RequestMapping("/v1/accounting")
public class AccountingController {
    private final MerchantToolAccess access;
    private final MerchantGeneralLedgerService ledger;

    public AccountingController(MerchantToolAccess access, MerchantGeneralLedgerService ledger) {
        this.access = access;
        this.ledger = ledger;
    }

    @GetMapping("/merchant/{merchantId}/profit-and-loss")
    public MerchantGeneralLedgerService.ProfitAndLossReport profitAndLoss(@PathVariable UUID merchantId,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        access.manage(merchantId);
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.withDayOfMonth(1) : from;
        if (start.isAfter(end)) throw ApiException.badRequest("period_invalid", "Start date is after end date");
        ledger.initializeStandardChartOfAccounts(merchantId);
        return ledger.generateProfitAndLoss(merchantId, start, end);
    }

    @GetMapping("/merchant/{merchantId}/balance-sheet")
    public MerchantGeneralLedgerService.BalanceSheetReport balanceSheet(@PathVariable UUID merchantId,
            @RequestParam(required = false) LocalDate asOf) {
        access.manage(merchantId);
        ledger.initializeStandardChartOfAccounts(merchantId);
        return ledger.generateBalanceSheet(merchantId, asOf == null ? LocalDate.now() : asOf);
    }

    @GetMapping("/merchant/{merchantId}/trial-balance")
    public MerchantGeneralLedgerService.TrialBalanceReport trialBalance(@PathVariable UUID merchantId,
            @RequestParam(required = false) LocalDate asOf) {
        access.manage(merchantId);
        ledger.initializeStandardChartOfAccounts(merchantId);
        return ledger.generateTrialBalance(merchantId, asOf == null ? LocalDate.now() : asOf);
    }
}
