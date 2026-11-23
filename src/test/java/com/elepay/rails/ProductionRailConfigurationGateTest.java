package com.elepay.rails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;

/**
 * A credential set without an explicitly configured production API origin must never
 * send or report a financial action as successful.
 */
class ProductionRailConfigurationGateTest {
    private static final PaymentRail.Charge MOMO_CHARGE =
            new PaymentRail.Charge("no-rail-charge", 100L, "GHS", "momo",
                    "0240000000", "mtn", null, "Account Holder", "Test", null);

    private static PaymentRail.Payout payout(String type, String network) {
        return new PaymentRail.Payout("no-rail-payout", 100L, "GHS", type,
                network, "0240000000", "Account Holder", "Test");
    }

    private static void unavailable(PaymentRail.Result result) {
        assertThat(result.status()).isEqualTo(PaymentRail.Status.UNAVAILABLE);
        assertThat(result.railReference()).isNull();
        assertThat(result.message()).startsWith("RAIL_NOT_CONFIGURED:");
    }

    @Test
    void mtnWillNotTransferWithoutExplicitProductionOrigin() {
        var credentials = new MtnProductionRail.Credentials("user", "key", "subscription");
        var rail = new MtnProductionRail(null, null, (URI) null, credentials, credentials, null);
        assertThat(rail.isConfigured("charge")).isFalse();
        assertThat(rail.isConfigured("payout")).isFalse();
        unavailable(rail.charge(MOMO_CHARGE));
        unavailable(rail.payout(payout("momo", "mtn")));
        assertThatThrownBy(() -> MtnProductionRail.productionBase(""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("RAIL_NOT_CONFIGURED");
    }

    @Test
    void telecelWillNotTransferWithoutExplicitProductionOrigin() {
        var credentials = new TelecelCashProductionRail.Credentials("id", "secret", "merchant", "webhook");
        var rail = new TelecelCashProductionRail(null, null, (URI) null, credentials, null);
        assertThat(rail.isConfigured()).isFalse();
        unavailable(rail.charge(new PaymentRail.Charge("no-rail-charge", 100L, "GHS",
                "momo", "0240000000", "telecel", null, "Account Holder", "Test", null)));
        unavailable(rail.payout(payout("momo", "telecel")));
        assertThatThrownBy(() -> TelecelCashProductionRail.productionBase(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("RAIL_NOT_CONFIGURED");
    }

    @Test
    void atMoneyWillNotTransferWithoutExplicitProductionOrigin() {
        var credentials = new AirtelTigoProductionRail.Credentials("id", "secret", "merchant", "webhook");
        var rail = new AirtelTigoProductionRail(null, null, (URI) null, credentials, null);
        assertThat(rail.isConfigured()).isFalse();
        unavailable(rail.charge(new PaymentRail.Charge("no-rail-charge", 100L, "GHS",
                "momo", "0240000000", "airteltigo", null, "Account Holder", "Test", null)));
        unavailable(rail.payout(payout("momo", "airteltigo")));
        assertThatThrownBy(() -> AirtelTigoProductionRail.productionBase(""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("RAIL_NOT_CONFIGURED");
    }

    @Test
    void ghipssWillNotTransferWithoutExplicitProductionOrigin() {
        var credentials = new GhipssInstantPayRail.Credentials("institution", "key", "signing", "webhook");
        var rail = new GhipssInstantPayRail(null, null, (URI) null, credentials, null);
        assertThat(rail.isConfigured()).isFalse();
        unavailable(rail.charge(new PaymentRail.Charge("no-rail-charge", 100L, "GHS",
                "bank", "0000000000", "ghipss", null, "Account Holder", "Test", null)));
        unavailable(rail.payout(payout("bank", "ghipss")));
        assertThatThrownBy(() -> GhipssInstantPayRail.productionBase(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("RAIL_NOT_CONFIGURED");
    }
}
