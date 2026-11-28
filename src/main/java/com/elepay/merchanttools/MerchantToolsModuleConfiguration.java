package com.elepay.merchanttools;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/**
 * Self-contained module boundary for Merchant Tools (POS, Restaurant/KDS, Inventory,
 * CRM/Loyalty, Merchant Accounting, and Payroll).
 *
 * <p>ELEPAY Core has zero runtime or compile-time dependencies on this package. Setting
 * {@code elepay.modules.merchanttools.enabled=false} runs ELEPAY in pure payments-core mode
 * without loading any merchant-tools beans or HTTP endpoints into the Spring context.
 */
@Configuration
@ConditionalOnProperty(
        prefix = "elepay.modules.merchanttools",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
@ComponentScan(
        basePackages = "com.elepay.merchanttools",
        excludeFilters = @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class)
)
public class MerchantToolsModuleConfiguration {
}
