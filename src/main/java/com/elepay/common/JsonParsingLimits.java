package com.elepay.common;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.core.StreamReadConstraints;

/**
 * Tight JSON parser limits, applied before any Jackson factory is created (registered in
 * {@code META-INF/spring.factories}). Jackson's defaults (1,000 nesting levels, 20 MB strings, unlimited
 * document size) let one crafted request burn CPU and memory; ELEPAY's API never needs anything close.
 *
 * <ul>
 *   <li>nesting depth 64: rejects "billion brackets" stack and CPU attacks,</li>
 *   <li>document 8 MiB and 1,000,000 tokens: generous enough for partner responses parsed by outbound
 *       clients; inbound requests are already capped at 1 MiB by {@code EdgeProtectionFilter},</li>
 *   <li>strings 1 MiB, field names 1 KiB, numbers 100 digits: rejects giant-number and giant-string DoS.</li>
 * </ul>
 */
public final class JsonParsingLimits implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    public static final StreamReadConstraints CONSTRAINTS = StreamReadConstraints.builder()
            .maxNestingDepth(64)
            .maxDocumentLength(8_388_608L)
            .maxTokenCount(1_000_000L)
            .maxStringLength(1_048_576)
            .maxNameLength(1_024)
            .maxNumberLength(100)
            .build();

    static {
        StreamReadConstraints.overrideDefaultStreamReadConstraints(CONSTRAINTS);
    }

    /** Applies the limits; safe to call repeatedly. */
    public static void apply() {
        StreamReadConstraints.overrideDefaultStreamReadConstraints(CONSTRAINTS);
    }

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        apply();
    }
}
