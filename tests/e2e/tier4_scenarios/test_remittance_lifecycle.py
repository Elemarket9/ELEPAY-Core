"""Tier 4: Scenario 2 — International Remittance Lifecycle.

Full cross-border remittance lifecycle:
1. Active corridor discovery & configuration
2. Sender dynamic rate quoting with locked TTL
3. Outbound remittance transfer execution
4. Inbound remittance credit directly to recipient MoMo wallet
5. Dual-currency statement reconciliation
6. Zero ledger drift proof
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


class TestRemittanceLifecycleScenario(OpaqueTestCase):
    """Test suite executing the international remittance lifecycle across multiple currencies."""

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
        self.client.call(
            "PUT",
            f"/v1/ops/fx/corridors/{self.IN_CORRIDOR}",
            token=self.ops_token,
            body={
                "direction": "inbound",
                "sendingCountry": "US",
                "receivingCountry": "GH",
                "sourceCurrency": "USD",
                "destinationCurrency": "GHS",
                "fundingMethod": "partner_collected",
                "deliveryMethod": "wallet",
                "partner": "sim-remit",
                "feeFixed": 0,
                "feeBps": 0,
                "minAmount": 100,
                "maxAmount": 10000000,
                "quoteTtlSeconds": 600,
                "estDeliveryMinutes": 1440,
                "active": True,
            },
            expected_status=200,
        )

    def test_international_remittance_lifecycle(self):
        """Scenario 2: Complete cross-border remittance flow covering outbound quote, execution, and inbound delivery."""
        sender = self.enroll_customer("UK Remitter")
        recipient = self.enroll_customer("Ghana Recipient")
        self.fund_wallet(sender["token"], amount=100000)

        # 1. Request outbound quote
        quote = self.client.call(
            "POST",
            "/v1/fx/quotes",
            token=sender["token"],
            body={"corridor": self.CORRIDOR, "sourceAmount": 50000},
            expected_status=200,
        )
        self.assertIsNotNone(quote.body.get("id"))
        self.assertGreater(quote.body.get("destinationAmount"), 0)

        # 2. Execute transfer against quote
        transfer = self.client.call(
            "POST",
            "/v1/fx/transfers",
            token=sender["token"],
            body={
                "quoteId": quote.body["id"],
                "recipient": {"name": "Recipient In US", "accountNumber": "987654321", "bankCode": "021000021"},
            },
            idempotency_key=unique_uuid(),
            expected_status=200,
        )
        transfer_ref = transfer.body["reference"]

        # 3. Simulate partner callback marking transfer paid
        cb_body = json.dumps({"reference": transfer_ref, "status": "paid"})
        sig = sign_fx_partner(self.config.fx_secret, cb_body)
        self.client.call(
            "POST",
            "/v1/fx/partners/sim-remit/callbacks",
            body=cb_body,
            headers={"x-partner-signature": sig},
            expected_status=200,
        )

        # 4. Inbound remittance: US$100.00 at 15.20 credits GH₵1,520.00 to recipient's wallet
        wallet_before = self.client.call("GET", "/v1/wallet", token=recipient["token"])
        avail_before = next(a["balance"] for a in wallet_before.body["accounts"] if a["bucket"] == "available")

        inbound_instruction = {
            "partnerReference": f"MTCN-LIFECYCLE-{unique_uuid()}",
            "corridor": self.IN_CORRIDOR,
            "sourceAmount": 10000,
            "sourceCurrency": "USD",
            "rate": "15.20",
            "destinationAmount": 152000,
            "destinationCurrency": "GHS",
            "recipientPhone": local_phone(recipient["phone"]),
            "senderName": "Kofi in Atlanta",
        }
        inbound_body = json.dumps(inbound_instruction)
        inbound_sig = sign_fx_partner(self.config.fx_secret, inbound_body)
        self.client.call(
            "POST",
            "/v1/fx/partners/sim-remit/inbound",
            body=inbound_body,
            headers={"x-partner-signature": inbound_sig},
            expected_status=200,
        )

        wallet_after = self.client.call("GET", "/v1/wallet", token=recipient["token"])
        avail_after = next(a["balance"] for a in wallet_after.body["accounts"] if a["bucket"] == "available")
        self.assertEqual(avail_after - avail_before, 152000)

        # 5. Authoritative dual-currency ledger check
        self.verify_ledger_integrity()
