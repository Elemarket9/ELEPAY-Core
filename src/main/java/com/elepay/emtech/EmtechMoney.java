package com.elepay.emtech;

import java.math.BigDecimal;

/**
 * Pesewas are integers. Emtech wants major-unit decimals. Dividing in
 * {@code double} cannot represent 0.10 exactly, so the conversion stays in
 * {@link BigDecimal} with scale 2 (one pesewa is exactly 0.01).
 */
public final class EmtechMoney {
    private EmtechMoney() {}

    public static BigDecimal ghs(long pesewas) {
        if (pesewas < 0) throw new IllegalArgumentException("pesewas must be zero or positive");
        return BigDecimal.valueOf(pesewas, 2);
    }
}
