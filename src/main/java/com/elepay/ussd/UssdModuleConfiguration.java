package com.elepay.ussd;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/**
 * Self-contained module boundary for USSD channel sessions and menus.
 *
 * <p>Controlled independently via {@code elepay.modules.ussd.enabled}.
 */
@Configuration
@ConditionalOnProperty(
        prefix = "elepay.modules.ussd",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
@ComponentScan(
        basePackages = "com.elepay.ussd",
        excludeFilters = @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class)
)
public class UssdModuleConfiguration {
}
