package com.elepay.rails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

@DisplayName("Algorithms 011, 012, 013: Direct Partner Rail Adapters & State Machine")
class PartnerRailAdaptersTest {

    private JdbcClient db;
    private ObjectMapper json;
    private NetworkHealthDetector health;
    private MtnMomoRail mtnRail;
    private TelecelCashRail telecelRail;
    private AirtelTigoRail airtelTigoRail;
    private GhipssRail ghipssRail;
    private CardAcquirerRail cardRail;
    private CardTokenVault vault;
    private ThreeDSecureService threeDs;

    @BeforeEach
    void setUp() {
        db = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        when(db.sql(anyString()).param(anyString(), any()).update()).thenReturn(1);

        json = new ObjectMapper();
        health = mock(NetworkHealthDetector.class);

        mtnRail = new MtnMomoRail(db, json, health, "test-mtn-secret");
        telecelRail = new TelecelCashRail(db, json, health, "test-telecel-secret");
        airtelTigoRail = new AirtelTigoRail(db, json, health, "test-airteltigo-secret");
        ghipssRail = new GhipssRail(db, json, health, "test-ghipss-secret");

        vault = mock(CardTokenVault.class);
        threeDs = mock(ThreeDSecureService.class);
        when(threeDs.initiate(any())).thenReturn(new ThreeDSecureService.ThreeDSSession(
                UUID.randomUUID(), "3ds_test", "REF123", 5000L, "GHS", "tok_123",
                "frictionless", "authenticated", "DS-1", "05", "cavv", true, 10, null, null
        ));

        cardRail = new CardAcquirerRail(db, json, vault, threeDs, health, "test-card-secret");
    }

    @Test
    @DisplayName("Algorithm 011: Direct Partner Rail names follow specification")
    void partnerRailNames() {
        assertThat(mtnRail.name()).isEqualTo("mtn");
        assertThat(telecelRail.name()).isEqualTo("telecel");
        assertThat(airtelTigoRail.name()).isEqualTo("airteltigo");
        assertThat(ghipssRail.name()).isEqualTo("ghipss");
        assertThat(cardRail.name()).isEqualTo("card");
    }

    @Test
    @DisplayName("Contractual partner fees match fee matrices")
    void verifiesPartnerFeeSchedules() {
        // MTN: 1% capped at 1000 pesewas
        assertThat(MtnMomoRail.calculateFee(50_000L)).isEqualTo(500L); // 500 pesewas
        assertThat(MtnMomoRail.calculateFee(200_000L)).isEqualTo(1000L); // capped at 1000

        // Telecel: 0.75% capped at 750 pesewas
        assertThat(TelecelCashRail.calculateFee(50_000L)).isEqualTo(375L);
        assertThat(TelecelCashRail.calculateFee(200_000L)).isEqualTo(750L); // capped at 750

        // AirtelTigo: 0.50% capped at 500 pesewas
        assertThat(AirtelTigoRail.calculateFee(50_000L)).isEqualTo(250L);
        assertThat(AirtelTigoRail.calculateFee(200_000L)).isEqualTo(500L); // capped at 500

        // GhIPSS: flat fee 50 pesewas
        assertThat(GhipssRail.FLAT_FEE_PESEWAS).isEqualTo(50L);

        // Card Acquirer: 1.5% fee
        assertThat(CardAcquirerRail.calculateFee(10_000L)).isEqualTo(150L);
    }

    @Test
    @DisplayName("Algorithm 013: Webhook callback cryptographic HMAC validation")
    void validatesHmacSignatures() {
        String body = json.writeValueAsString(Map.of(
                "event", "charge.success",
                "data", Map.of("reference", "EPC123456", "status", "SUCCESSFUL", "amount", 5000L, "currency", "GHS")
        ));

        // Valid MTN signature succeeds
        String validSig = mtnRail.sign(body);
        PaymentRail.Callback callback = mtnRail.verifyCallback(Map.of("x-mtn-signature", validSig), body);
        assertThat(callback.result().status()).isEqualTo(PaymentRail.Status.SUCCEEDED);
        assertThat(callback.result().amount()).isEqualTo(5000L);

        // Tampered body or invalid signature throws InvalidCallbackException
        assertThatThrownBy(() -> mtnRail.verifyCallback(Map.of("x-mtn-signature", "invalid_signature_hex"), body))
                .isInstanceOf(PaymentRail.InvalidCallbackException.class);
    }

    @Test
    @DisplayName("Algorithm 012: Unknown outcome recovery handles timeout transitions")
    void handlesTimeoutsDeterministically() {
        // MSISDN ending in '2' simulates timeout where rail received request (Status.UNKNOWN)
        PaymentRail.Charge timeoutCharge = new PaymentRail.Charge(
                "EPC-TIMEOUT-1", 10000L, "GHS", "momo", "0240000002",
                "mtn", "user@elepay.com", "Test User", "Payment", null
        );
        PaymentRail.Result result = mtnRail.charge(timeoutCharge);
        assertThat(result.status()).isEqualTo(PaymentRail.Status.UNKNOWN);
    }

    @Test
    @DisplayName("Algorithm 012: Lost transaction before rail reception returns UNKNOWN")
    void handlesLostTransactions() {
        // MSISDN ending in '4' simulates lost connection before rail received request
        PaymentRail.Charge lostCharge = new PaymentRail.Charge(
                "EPC-LOST-1", 10000L, "GHS", "momo", "0240000004",
                "mtn", "user@elepay.com", "Test User", "Payment", null
        );
        PaymentRail.Result result = mtnRail.charge(lostCharge);
        assertThat(result.status()).isEqualTo(PaymentRail.Status.UNKNOWN);
    }
}
