package com.elepay;

import static org.assertj.core.api.Assertions.assertThat;

import com.elepay.common.qr.EmvcoQr;
import org.junit.jupiter.api.Test;

class AdversarialCampaignRegressionTest {

    @Test
    void emvcoQrRejectsTrailingTlvSmugglingAfterCrc() {
        String validQr = EmvcoQr.builder()
                .merchantCode("123456")
                .merchantName("Legit Shop")
                .amount("100.00")
                .build();
        assertThat(EmvcoQr.decode(validQr).valid()).isTrue();
        assertThat(EmvcoQr.decode(validQr).amount()).isEqualTo("100.00");

        // Smuggle trailing tag 54 (amount 0.01) after valid 6304<CRC>
        String smuggled = validQr + "54040.01";
        var decoded = EmvcoQr.decode(smuggled);
        assertThat(decoded.valid()).isFalse();
    }

    @Test
    void emvcoQrRejectsDuplicateTopLevelTagsBeforeCrc() {
        String base = "00020101021226200010GH.ELEPAY01061234565204541153039365406100.0054040.015802GH5910Legit Shop6005Accra6304";
        String withCrc = base + EmvcoQr.calculateCrc(base);
        var decoded = EmvcoQr.decode(withCrc);
        assertThat(decoded.valid()).isFalse();
    }
}
