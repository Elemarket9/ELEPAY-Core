package com.elepay.merchanttools;

import static org.assertj.core.api.Assertions.assertThat;

import com.elepay.PostgresTestBase;
import com.elepay.enterprise.settlement.ConditionalSettlementReleaseEngine;
import com.elepay.ledger.Ledger;
import com.elepay.payout.Payouts;
import com.elepay.rails.CustodianBankBackstopService;
import com.elepay.rails.PlatformRevenueSweepService;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/**
 * Verifies that ELEPAY Core boots cleanly in pure payments-core mode when all 7 optional
 * product modules ({@code merchanttools}, {@code lending}, {@code credit}, {@code ussd},
 * {@code growth}, {@code bills}, {@code agents}) are disabled, loading zero beans from any
 * of those packages while core payments, ledger, payouts, custodian, and settlement remain active.
 */
@TestPropertySource(properties = {
        "elepay.modules.merchanttools.enabled=false",
        "elepay.modules.lending.enabled=false",
        "elepay.modules.credit.enabled=false",
        "elepay.modules.ussd.enabled=false",
        "elepay.modules.growth.enabled=false",
        "elepay.modules.bills.enabled=false",
        "elepay.modules.agents.enabled=false"
})
class MerchantToolsModuleIsolationIT extends PostgresTestBase {

    private static final List<String> OPTIONAL_PREFIXES = List.of(
            "com.elepay.merchanttools",
            "com.elepay.lending",
            "com.elepay.credit",
            "com.elepay.ussd",
            "com.elepay.growth",
            "com.elepay.bills",
            "com.elepay.agents"
    );

    @Autowired ApplicationContext context;
    @Autowired Ledger ledger;
    @Autowired Payouts payouts;
    @Autowired CustodianBankBackstopService custodianBackstop;
    @Autowired PlatformRevenueSweepService revenueSweepService;
    @Autowired ConditionalSettlementReleaseEngine settlementReleaseEngine;

    @Test
    void paymentsCoreBootsWithoutAnyOptionalProductModuleBeans() {
        List<String> leakedBeans = Arrays.stream(context.getBeanDefinitionNames())
                .filter(name -> {
                    Class<?> type = context.getType(name);
                    if (type == null) return false;
                    String pkg = type.getPackageName();
                    return OPTIONAL_PREFIXES.stream()
                            .anyMatch(prefix -> pkg.equals(prefix) || pkg.startsWith(prefix + "."));
                })
                .toList();

        assertThat(leakedBeans)
                .as("No optional product module beans should be loaded when disabled")
                .isEmpty();

        assertThat(ledger.integrity().ok()).isTrue();
        assertThat(payouts).isNotNull();
        assertThat(custodianBackstop).isNotNull();
        assertThat(revenueSweepService).isNotNull();
        assertThat(settlementReleaseEngine).isNotNull();
    }
}
