package com.elepay.credit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/**
 * Self-contained module boundary for Credit (underwriting, scoring, float forecasting, dynamic limits).
 *
 * <p>Controlled independently via {@code elepay.modules.credit.enabled} and gated on
 * {@code elepay.dormant-modules.enabled=true} so the configuration bean is not loaded when dormant.
 */
@Configuration
@ConditionalOnExpression("${elepay.dormant-modules.enabled:false} and ${elepay.modules.credit.enabled:true}")
@ComponentScan(
        basePackages = "com.elepay.credit",
        excludeFilters = @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class)
)
public class CreditModuleConfiguration {
}
