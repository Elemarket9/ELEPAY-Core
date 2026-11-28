package com.elepay.lending;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/**
 * Self-contained module boundary for Lending (loan origination, contracts, amortization, repayment sweeps).
 *
 * <p>Controlled independently via {@code elepay.modules.lending.enabled} and gated on
 * {@code elepay.dormant-modules.enabled=true} so the configuration bean is not loaded when dormant.
 */
@Configuration
@ConditionalOnExpression("${elepay.dormant-modules.enabled:false} and ${elepay.modules.lending.enabled:true}")
@ComponentScan(
        basePackages = "com.elepay.lending",
        excludeFilters = @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class)
)
public class LendingModuleConfiguration {
}
