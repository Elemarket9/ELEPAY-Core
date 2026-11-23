package com.elepay.identity;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PosLanOriginTest {
    @Test
    void cspOnlyAcceptsExactConfiguredTlsOrigin() {
        assertThat(SecurityConfig.checkedLanOrigin(null)).isEmpty();
        assertThat(SecurityConfig.checkedLanOrigin("https://kitchen.example.com:8443"))
                .isEqualTo(" https://kitchen.example.com:8443");
        assertThatThrownBy(() -> SecurityConfig.checkedLanOrigin("http://kitchen.example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SecurityConfig.checkedLanOrigin("https://kitchen.example.com/x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SecurityConfig.checkedLanOrigin("https://kitchen.example.com;script-src *"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
