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
 * Verifies that ELEPAY Core boots cleanly in payments-core-only mode with
 * {@code elepay.modules.merchanttools.enabled=false}, loading zero POS, Restaurant/KDS,
 * Inventory, CRM, Accounting, or Payroll beans into the Spring context.
 */
@TestPropertySource(properties = "elepay.modules.merchanttools.enabled=false")
class MerchantToolsModuleIsolationIT extends PostgresTestBase {

    @Autowired ApplicationContext context;
    @Autowired Ledger ledger;
    @Autowired Payouts payouts;
    @Autowired CustodianBankBackstopService custodianBackstop;
    @Autowired PlatformRevenueSweepService revenueSweepService;
    @Autowired ConditionalSettlementReleaseEngine settlementReleaseEngine;

    @Test
    void paymentsCoreBootsWithoutAnyMerchantToolsBeans() {
        List<String> leakedBeans = Arrays.stream(context.getBeanDefinitionNames())
                .filter(name -> {
                    Class<?> type = context.getType(name);
                    if (type == null) return false;
                    String pkg = type.getPackageName();
                    return pkg.equals("com.elepay.merchanttools")
                            || pkg.startsWith("com.elepay.merchanttools.");
                })
                .toList();

        assertThat(leakedBeans)
                .as("No com.elepay.merchanttools beans should be loaded when elepay.modules.merchanttools.enabled=false")
                .isEmpty();

        assertThat(ledger.integrity().ok()).isTrue();
        assertThat(payouts).isNotNull();
        assertThat(custodianBackstop).isNotNull();
        assertThat(revenueSweepService).isNotNull();
        assertThat(settlementReleaseEngine).isNotNull();
    }
}
