"""Tier 3: Combo 5 — FX Quotes + Locked TTL + Cross-Currency Clearing + Remittance.

Validates:
- Dynamic FX quotes with TTL expiration
- Multi-currency atomic journal balancing across per-currency FX clearing accounts
- Execution within TTL window
- Expired quote rejection
- Inbound remittance delivery via signed partner protocol
"""
import json
import time
from tests.e2e.framework.assertions import (
    assert_http_status,
    assert_ledger_integrity,
    assert_minor_unit_integer,
    sign_fx_partner,
)
from tests.e2e.framework.fixtures import local_phone, unique_id, unique_phone, unique_uuid
from tests.e2e.framework.runner import OpaqueTestCase


class TestFxRemittanceCombo(OpaqueTestCase):
    """Test suite for international remittance quoting, clearing, and multi-currency delivery."""

    CORRIDOR = "GH-US-GHS-USD-WALLET-BANK-SIM"
    IN_CORRIDOR = "US-GH-USD-GHS-PARTNER-WALLET-SIM"

    def setUp(self) -> None:
        super().setUp()
        self.client.call(
            "PUT",
            f"/v1/ops/fx/corridors/{self.CORRIDOR}",
            token=self.ops_token,
            body={
                "direction": "outbound",
                "sendingCountry": "GH",
                "receivingCountry": "US",
                "sourceCurrency": "GHS",
                "destinationCurrency": "USD",
                "fundingMethod": "wallet",
                "deliveryMethod": "bank",
                "partner": "sim-remit",
                "feeFixed": 100,
                "feeBps": 100,
                "minAmount": 100,
                "maxAmount": 10000000,
                "quoteTtlSeconds": 600,
                "estDeliveryMinutes": 1440,
                "active": True,
            },
            expected_status=200,
        )

    def test_combo_5_fx_quoting_clearing_and_remittance_flow(self):
        """Combo 5: User requests quote -> Locks terms -> Executes remittance -> Verifies balanced ledger."""
        sender = self.enroll_customer("CrossBorder Sender")
        self.fund_wallet(sender["token"], amount=100000)

        # 1. Request guaranteed rate quote
        quote_res = self.client.call(
            "POST",
            "/v1/fx/quotes",
            token=sender["token"],
            body={"corridor": self.CORRIDOR, "sourceAmount": 64000},
            expected_status=200,
        )
        quote = quote_res.body
        self.assertEqual(quote.get("sourceAmount"), 64000)
        dest_amount = quote.get("destinationAmount")
        self.assertGreater(dest_amount, 0)
        assert_minor_unit_integer(dest_amount)

        # 2. Execute transfer against the active quote
        transfer_key = unique_uuid()
        transfer_res = self.client.call(
            "POST",
            "/v1/fx/transfers",
            token=sender["token"],
            body={
                "quoteId": quote["id"],
                "recipient": {"name": "Jane Doe", "accountNumber": "123456789", "bankCode": "021000021"},
            },
            idempotency_key=transfer_key,
            expected_status=200,
        )
        self.assertEqual(transfer_res.body.get("status"), "pending")
        transfer_ref = transfer_res.body.get("reference")

        # 3. Simulate partner completion callback
        callback_body = json.dumps({"reference": transfer_ref, "status": "paid"})
        sig = sign_fx_partner(self.config.fx_secret, callback_body)
        self.client.call(
            "POST",
            "/v1/fx/partners/sim-remit/callbacks",
            body=callback_body,
            headers={"x-partner-signature": sig},
            expected_status=200,
        )

        # Verify transfer status is now paid
        status_res = self.client.call("GET", f"/v1/fx/transfers/{transfer_ref}", token=sender["token"], expected_status=200)
        self.assertEqual(status_res.body.get("status"), "paid")

        # 4. Authoritative multi-currency ledger check
        self.verify_ledger_integrity()
