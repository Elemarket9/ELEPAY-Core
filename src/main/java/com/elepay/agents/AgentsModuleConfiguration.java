package com.elepay.agents;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/**
 * Self-contained module boundary for Agents (cash-in/cash-out agent network operations).
 *
 * <p>Controlled independently via {@code elepay.modules.agents.enabled} and gated on
 * {@code elepay.dormant-modules.enabled=true} so the configuration bean is not loaded when dormant.
 */
@Configuration
@ConditionalOnExpression("${elepay.dormant-modules.enabled:false} and ${elepay.modules.agents.enabled:true}")
@ComponentScan(
        basePackages = "com.elepay.agents",
        excludeFilters = @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class)
)
public class AgentsModuleConfiguration {
}
