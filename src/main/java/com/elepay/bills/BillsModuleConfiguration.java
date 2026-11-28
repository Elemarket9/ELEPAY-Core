package com.elepay.bills;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/**
 * Self-contained module boundary for Bills (utility, airtime, and biller payments).
 *
 * <p>Controlled independently via {@code elepay.modules.bills.enabled} and still subject to
 * {@link com.elepay.common.DormantModules} when {@code elepay.dormant-modules.enabled=false}.
 */
@Configuration
@ConditionalOnProperty(
        prefix = "elepay.modules.bills",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
@ComponentScan(
        basePackages = "com.elepay.bills",
        excludeFilters = @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class)
)
public class BillsModuleConfiguration {
}
