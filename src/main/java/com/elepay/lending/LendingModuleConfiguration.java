package com.elepay.lending;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/**
 * Self-contained module boundary for Lending (loan origination, contracts, amortization, repayment sweeps).
 *
 * <p>Controlled independently via {@code elepay.modules.lending.enabled} and still subject to
 * {@link com.elepay.common.DormantModules} when {@code elepay.dormant-modules.enabled=false}.
 */
@Configuration
@ConditionalOnProperty(
        prefix = "elepay.modules.lending",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
@ComponentScan(
        basePackages = "com.elepay.lending",
        excludeFilters = @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class)
)
public class LendingModuleConfiguration {
}
