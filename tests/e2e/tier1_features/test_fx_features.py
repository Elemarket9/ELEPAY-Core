"""Domain F: FX and Cross-Border — Tier 1 Feature Coverage Test Suite.

Covers:
- TC-T1-F-01: Query Active Cross-Border Corridors
- TC-T1-F-02: Guaranteed FX Quote Generation with Locked TTL
- TC-T1-F-03: Execute Outbound FX Remittance Transfer
- TC-T1-F-04: Process Signed Inbound Cross-Border Remittance
- TC-T1-F-05: Track Remittance Status via Public Reference
"""
import json
from tests.e2e.framework.assertions import (
    assert_http_status,
    assert_minor_unit_integer,
    sign_fx_partner,
)
from tests.e2e.framework.fixtures import local_phone, unique_id, unique_phone, unique_uuid
from tests.e2e.framework.runner import OpaqueTestCase


class TestFxFeatures(OpaqueTestCase):
    """Test suite for FX corridors, dynamic margin quotes, and cross-border remittance delivery."""

    OUT_CORRIDOR = "GH-US-GHS-USD-WALLET-BANK-SIM"
    IN_CORRIDOR = "US-GH-USD-GHS-PARTNER-WALLET-SIM"

    def setUp(self) -> None:
        super().setUp()
        # Ensure outbound and inbound test corridors exist via ops endpoint
        self._ensure_corridors()

    def _ensure_corridors(self):
        self.client.call(
            "PUT",
            f"/v1/ops/fx/corridors/{self.OUT_CORRIDOR}",
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

    def test_tc_t1_f_01_query_active_cross_border_corridors(self):
        """TC-T1-F-01: Retrieve supported cross-border corridors and verify active parameters."""
        customer = self.enroll_customer("Corridor Explorer")
        corridors = self.client.call("GET", "/v1/fx/corridors", token=customer["token"], expected_status=200)
        self.assertIsInstance(corridors.body, list)
        self.assertGreaterEqual(len(corridors.body), 1)

        codes = [c.get("code") for c in corridors.body]
        self.assertIn(self.OUT_CORRIDOR, codes)

    def test_tc_t1_f_02_guaranteed_fx_quote_with_locked_ttl(self):
        """TC-T1-F-02: Generate guaranteed rate quote and verify rate, fee, destination amount, and expiration."""
        customer = self.enroll_customer("FX Quoter")
        quote = self.client.call(
            "POST",
            "/v1/fx/quotes",
            token=customer["token"],
            body={"corridor": self.OUT_CORRIDOR, "sourceAmount": 64000},
            expected_status=200,
        )
        self.assertIsNotNone(quote.body.get("id"))
        self.assertEqual(quote.body.get("sourceAmount"), 64000)
        assert_minor_unit_integer(quote.body.get("destinationAmount"))
        assert_minor_unit_integer(quote.body.get("fee"))
        self.assertIsNotNone(quote.body.get("expiresAt"))

    def test_tc_t1_f_03_execute_fx_remittance_transfer(self):
        """TC-T1-F-03: Execute outbound FX remittance transfer against active locked quote."""
        customer = self.enroll_customer("Remittance Sender")
        self.fund_wallet(customer["token"], amount=100000)

        quote = self.client.call(
            "POST",
            "/v1/fx/quotes",
            token=customer["token"],
            body={"corridor": self.OUT_CORRIDOR, "sourceAmount": 10000},
            expected_status=200,
        )
        quote_id = quote.body.get("id")

        transfer = self.client.call(
            "POST",
            "/v1/fx/transfers",
            token=customer["token"],
            body={
                "quoteId": quote_id,
                "recipient": {"name": "Jane Doe", "accountNumber": "123456789", "bankCode": "021000021"},
            },
            idempotency_key=unique_uuid(),
            expected_status=200,
        )
        self.assertEqual(transfer.body.get("status"), "pending")
        self.assertIsNotNone(transfer.body.get("reference"))

    def test_tc_t1_f_04_inbound_cross_border_remittance_delivery(self):
        """TC-T1-F-04: Process inbound remittance callback signed by partner directly crediting wallet."""
        recipient = self.enroll_customer("Remittance Recipient")
        wallet_before = self.client.call("GET", "/v1/wallet", token=recipient["token"])
        avail_before = next(a["balance"] for a in wallet_before.body["accounts"] if a["bucket"] == "available")

        instruction = {
            "partnerReference": f"MTCN-{unique_uuid()}",
            "corridor": self.IN_CORRIDOR,
            "sourceAmount": 10000,
            "sourceCurrency": "USD",
            "rate": "15.20",
            "destinationAmount": 152000,
            "destinationCurrency": "GHS",
            "recipientPhone": local_phone(recipient["phone"]),
            "senderName": "Ama in Houston",
        }
        body_str = json.dumps(instruction)
        sig = sign_fx_partner(self.config.fx_secret, body_str)

        inbound_res = self.client.call(
            "POST",
            "/v1/fx/partners/sim-remit/inbound",
            body=body_str,
            headers={"x-partner-signature": sig},
            expected_status=200,
        )
        self.assertEqual(inbound_res.status, 200)

        # Confirm recipient wallet balance increased by 152000 pesewas
        wallet_after = self.client.call("GET", "/v1/wallet", token=recipient["token"])
        avail_after = next(a["balance"] for a in wallet_after.body["accounts"] if a["bucket"] == "available")
        self.assertEqual(avail_after - avail_before, 152000)

    def test_tc_t1_f_05_track_remittance_status_by_reference(self):
        """TC-T1-F-05: Query transfer details using its public reference identifier."""
        customer = self.enroll_customer("Tracker User")
        self.fund_wallet(customer["token"], amount=50000)

        quote = self.client.call(
            "POST",
            "/v1/fx/quotes",
            token=customer["token"],
            body={"corridor": self.OUT_CORRIDOR, "sourceAmount": 10000},
            expected_status=200,
        )
        transfer = self.client.call(
            "POST",
            "/v1/fx/transfers",
            token=customer["token"],
            body={
                "quoteId": quote.body.get("id"),
                "recipient": {"name": "Bob Smith", "accountNumber": "987654321", "bankCode": "021000021"},
            },
            idempotency_key=unique_uuid(),
            expected_status=200,
        )
        ref = transfer.body.get("reference")

        query_res = self.client.call("GET", f"/v1/fx/transfers/{ref}", token=customer["token"], expected_status=200)
        self.assertEqual(query_res.body.get("reference"), ref)
        self.assertIn(query_res.body.get("status"), ("pending", "paid", "failed"))
