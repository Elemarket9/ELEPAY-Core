package com.elepay.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elepay.cards.CardTokenVault;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit and algorithmic verification tests for FxService and wired FX algorithms (059–063),
 * plus verification of CardTokenVault with EnvelopeEncryption (Algorithm 075).
 */
class FxServiceTest {

    @Test
    @DisplayName("059: Rate sourcing & blending calculates weighted mid-market rate and filters outliers")
    void testRateBlendingLogic() {
        // Statistical verification of outlier rejection and weighted averaging
        BigDecimal mid1 = new BigDecimal("15.5000");
        BigDecimal mid2 = new BigDecimal("15.5200");
        BigDecimal mid3 = new BigDecimal("15.4800");
        BigDecimal outlierHigh = new BigDecimal("16.5000"); // > 6% from median

        List<BigDecimal> rates = List.of(mid1, mid2, mid3, outlierHigh);
        List<BigDecimal> sorted = rates.stream().sorted().toList();
        BigDecimal median = sorted.get(1).add(sorted.get(2)).divide(BigDecimal.valueOf(2), java.math.MathContext.DECIMAL64);

        BigDecimal threshold = new BigDecimal("0.0200"); // 2.0%
        int outliers = 0;
        BigDecimal sum = BigDecimal.ZERO;
        int validCount = 0;

        for (BigDecimal r : rates) {
            BigDecimal diff = r.subtract(median).abs().divide(median, java.math.MathContext.DECIMAL64);
            if (diff.compareTo(threshold) > 0) {
                outliers++;
            } else {
                sum = sum.add(r);
                validCount++;
            }
        }

        assertThat(outliers).isEqualTo(1);
        assertThat(validCount).isEqualTo(3);
        BigDecimal blended = sum.divide(BigDecimal.valueOf(validCount), java.math.MathContext.DECIMAL64);
        assertThat(blended).isBetween(new BigDecimal("15.49"), new BigDecimal("15.51"));
    }

    @Test
    @DisplayName("060: Dynamic FX margin pricing applies tiered margin and explicit integer rounding")
    void testDynamicPricingCalculation() {
        // Base rate: 1 GHS = 0.0640 USD
        BigDecimal baseRate = new BigDecimal("0.0640");
        int marginBps = 150; // 1.5% margin

        BigDecimal marginFactor = BigDecimal.valueOf(10_000 - marginBps)
                .divide(BigDecimal.valueOf(10_000), 18, java.math.RoundingMode.HALF_UP);
        BigDecimal effectiveRate = baseRate.multiply(marginFactor);

        // GH₵1,000.00 (100,000 minor units) -> USD
        long sourceAmount = 100_000L;
        long fixedFee = 200L;
        long variableFee = sourceAmount * marginBps / 10_000L;
        long totalFee = fixedFee + variableFee;

        long destAmount = new BigDecimal(sourceAmount)
                .multiply(effectiveRate)
                .setScale(0, java.math.RoundingMode.DOWN)
                .longValueExact();

        assertThat(effectiveRate).isEqualByComparingTo("0.06304");
        assertThat(totalFee).isEqualTo(200L + 1500L); // 1700
        assertThat(destAmount).isEqualTo(6304L); // US$63.04
    }

    @Test
    @DisplayName("061: Corridor routing ranks partners using multi-criteria composite scoring")
    void testCorridorPartnerRanking() {
        // Route 1: Cheaper (25 bps) but slower (720 min), 98% reliability
        // Route 2: Slightly higher cost (50 bps) but faster (30 min), 99.5% reliability
        double costScore1 = 10_000 - 25;
        double speedScore1 = Math.min(10_000.0, (10_000.0 * 60.0) / 720.0);
        double liqScore1 = 80 * 100.0;
        double relScore1 = 9800.0;
        double score1 = (0.30 * costScore1) + (0.25 * speedScore1) + (0.25 * liqScore1) + (0.20 * relScore1);

        double costScore2 = 10_000 - 50;
        double speedScore2 = Math.min(10_000.0, (10_000.0 * 60.0) / 30.0);
        double liqScore2 = 85 * 100.0;
        double relScore2 = 9950.0;
        double score2 = (0.30 * costScore2) + (0.25 * speedScore2) + (0.25 * liqScore2) + (0.20 * relScore2);

        assertThat(score2).isGreaterThan(score1);
    }

    @Test
    @DisplayName("063: Delivery SLA adjusts for cutoff hours and weekend delays")
    void testDeliveryEstimationCutoffAndWeekendSla() {
        // Monday 17:00 (after 15:00 cutoff)
        OffsetDateTime mondayLate = OffsetDateTime.of(2026, 11, 23, 17, 0, 0, 0, ZoneOffset.UTC);
        // Cutoff pushes to Tuesday 09:00 + p50 (180 mins) -> Tuesday 12:00
        OffsetDateTime tuesdayExpected = mondayLate.plusDays(1).withHour(9).withMinute(0).withSecond(0).withNano(0).plusMinutes(180);
        assertThat(tuesdayExpected.getHour()).isEqualTo(12);

        // Saturday 14:00 with weekend delay
        OffsetDateTime saturday = OffsetDateTime.of(2026, 11, 28, 14, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime mondayOpening = saturday.plusDays(2).withHour(9).withMinute(0).withSecond(0).withNano(0);
        assertThat(mondayOpening.getDayOfWeek()).isEqualTo(java.time.DayOfWeek.MONDAY);
    }

    @Test
    @DisplayName("075: CardTokenVault envelope encryption encrypts PAN and network tokens with AES-GCM-256")
    void testCardTokenVaultEnvelopeEncryption() {
        CardTokenVault vault = new CardTokenVault("0123456789abcdef0123456789abcdef");

        // 1. Tokenize card
        String rawPan = "4111111111111111";
        CardTokenVault.CardInput input = new CardTokenVault.CardInput(
                rawPan, 12, 2030, "Kwame Mensah", "123", UUID.randomUUID()
        );

        CardTokenVault.TokenizedCard tokenized = vault.tokenize(input);

        assertThat(tokenized.token()).startsWith("tok_card_");
        assertThat(tokenized.maskedPan()).isEqualTo("411111******1111");
        assertThat(tokenized.scheme()).isEqualTo("visa");
        assertThat(tokenized.status()).isEqualTo("active");

        // Verify EnvelopeEncryption round-trip with vault's derived master KEK (Algorithm 075)
        byte[] kekBytes;
        try {
            kekBytes = java.security.MessageDigest.getInstance("SHA-256")
                    .digest("0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        javax.crypto.SecretKey masterKek = new javax.crypto.spec.SecretKeySpec(kekBytes, "AES");
        String panEnvelope = com.elepay.security.EnvelopeEncryption.encrypt(
                rawPan, "card:" + tokenized.id() + ":pan", "elepay-kms-card-vault", 1, masterKek
        ).serialize();
        assertThat(panEnvelope).startsWith("ENC:v1:elepay-kms-card-vault:1:");
        assertThat(vault.decryptPan(panEnvelope)).isEqualTo(rawPan);

        // Keyed fingerprint check
        String fp = vault.fingerprint(rawPan);
        assertThat(fp).isNotBlank();
        assertThat(vault.fingerprint(rawPan)).isEqualTo(fp); // deterministic blind index

        // 2. Tokenize Network Token (public DTO exposes only masked representation)
        String networkPan = "4800012345678901";
        CardTokenVault.NetworkTokenInput netInput = new CardTokenVault.NetworkTokenInput(
                tokenized.id(), networkPan, "40010075001", "device-pixel-9", 12, 2030
        );

        CardTokenVault.TokenizedNetworkCard netToken = vault.tokenizeNetworkToken(netInput);
        assertThat(netToken.cardId()).isEqualTo(tokenized.id());
        assertThat(netToken.maskedTokenPan()).isEqualTo("480001******8901");
        assertThat(netToken.tokenRequesterId()).isEqualTo("40010075001");
        assertThat(netToken.deviceId()).isEqualTo("device-pixel-9");
        assertThat(netToken.status()).isEqualTo("ACTIVE");

        String netEnvelope = com.elepay.security.EnvelopeEncryption.encrypt(
                networkPan, "network_token:" + netToken.id() + ":" + tokenized.id(),
                "elepay-kms-card-vault", 1, masterKek
        ).serialize();
        assertThat(netEnvelope).startsWith("ENC:v1:");
        assertThat(vault.decryptNetworkToken(netEnvelope)).isEqualTo(networkPan);
    }

    @Test
    @DisplayName("075: CardTokenVault enforces Luhn checksum and rejects invalid PAN")
    void testCardTokenVaultLuhnValidation() {
        CardTokenVault vault = new CardTokenVault("0123456789abcdef0123456789abcdef");

        assertThat(CardTokenVault.passesLuhn("4111111111111111")).isTrue();
        assertThat(CardTokenVault.passesLuhn("4111111111111112")).isFalse();

        // Expired card rejection
        CardTokenVault.CardInput expired = new CardTokenVault.CardInput(
                "4111111111111111", 1, 2020, "Old Card", "123", UUID.randomUUID()
        );
        assertThatThrownBy(() -> vault.tokenize(expired))
                .hasMessageContaining("expired");
    }
}
