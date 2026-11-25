package com.elepay.common;

import java.io.IOException;
import java.util.List;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;

/**
 * Keeps modules that are not part of the launch scope out of the running application.
 *
 * <p>These packages have no HTTP endpoints or scheduled jobs and nothing live depends on them, but their
 * beans still load into production. Some of them (credit, lending, repayment sweeps over merchant
 * balances, cross-merchant settlement netting) would need a Bank of Ghana Digital Credit Services licence
 * or conflict with Notice BG/GOV/SEC/2026/43 on merchant collection funds. They stay in the codebase but
 * are not instantiated unless {@code elepay.dormant-modules.enabled=true}.
 *
 * <p>Deliberately NOT dormant (launch scope): cards and the card rail, point of sale, and the AI/ML fraud
 * and risk engine ({@code com.elepay.risk}). The AI customer features ({@code com.elepay.growth}) and the
 * AI forecasting models (in {@code com.elepay.credit}) are off for launch.
 */
public final class DormantModules implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    public static final String PROPERTY = "elepay.dormant-modules.enabled";

    /** Packages excluded from component scanning when dormant modules are disabled. */
    public static final List<String> PACKAGES = List.of(
            "com.elepay.credit.",
            "com.elepay.growth.",
            "com.elepay.lending.",
            "com.elepay.enterprise.",
            "com.elepay.bills.",
            "com.elepay.agents.",
            "com.elepay.settlement.");

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        boolean enabled = context.getEnvironment().getProperty(PROPERTY, Boolean.class, false);
        if (!enabled) {
            context.getBeanFactory().registerSingleton("dormantModulesExcludeFilter", new ExcludeFilter());
        }
    }

    public static boolean isDormant(String className) {
        return className != null && PACKAGES.stream().anyMatch(className::startsWith);
    }

    static final class ExcludeFilter extends TypeExcludeFilter {
        @Override
        public boolean match(MetadataReader reader, MetadataReaderFactory factory) throws IOException {
            return isDormant(reader.getClassMetadata().getClassName());
        }

        @Override public boolean equals(Object o) { return o instanceof ExcludeFilter; }
        @Override public int hashCode() { return ExcludeFilter.class.hashCode(); }
    }
}
