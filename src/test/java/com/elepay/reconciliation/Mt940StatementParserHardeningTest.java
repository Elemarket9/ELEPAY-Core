package com.elepay.reconciliation;

import com.elepay.common.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Mt940StatementParserHardeningTest {

    @Test
    void rejectsClosingBalanceCurrencyMismatch() {
        Mt940StatementParser parser = new Mt940StatementParser(null, null);
        String mt940 = """
                :20:REF123
                :25:1010203040
                :28C:00001/001
                :60F:C260101GHS1000,00
                :61:2601010101C100,00NTRFNONREF//REF1
                :62F:C260101USD1100,00
                """;
        assertThatThrownBy(() -> parser.parse(mt940))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("does not match opening currency");
    }

    @Test
    void rejectsFractionalPesewasViaLongValueExact() {
        Mt940StatementParser parser = new Mt940StatementParser(null, null);
        String mt940 = """
                :20:REF124
                :25:1010203040
                :28C:00002/001
                :60F:C260101GHS1000,005
                :62F:C260101GHS1000,005
                """;
        assertThatThrownBy(() -> parser.parse(mt940))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void parsesEuropeanThousandsAndDecimalCommaCorrectly() {
        Mt940StatementParser parser = new Mt940StatementParser(null, null);
        String mt940 = """
                :20:REF125
                :25:1010203040
                :28C:00003/001
                :60F:C260101GHS1.234,56
                :62F:C260101GHS1.234,56
                """;
        var parsed = parser.parse(mt940);
        assertThat(parsed.openingBalancePesewas()).isEqualTo(123456L);
        assertThat(parsed.closingBalancePesewas()).isEqualTo(123456L);
    }
}
