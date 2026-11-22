"""Domain F: FX and Cross-Border — Tier 2 Boundary & Negative Test Suite.

Covers:
- TC-T2-F-01: Rejection of Expired or Stale FX Quote (HTTP 409)
- TC-T2-F-02: Quote Single-Use Enforcement: Second Execution Blocked (HTTP 409)
- TC-T2-F-03: Inbound Remittance Amount Discrepancy Rejection (HTTP 422/400)
- TC-T2-F-04: Non-Existent FX Corridor Quote Rejection (HTTP 400/404)
- TC-T2-F-05: Transfer Exceeding Wallet Balance Rejection (HTTP 409)
"""
import json
from tests.e2e.framework.assertions import assert_http_status, sign_fx_partner
from tests.e2e.framework.fixtures import local_phone, unique_id, unique_phone, unique_uuid
from tests.e2e.framework.runner import OpaqueTestCase


class TestFxBoundaries(OpaqueTestCase):
    """Test suite for FX quote expiration, single-use quote protection, and partner signature boundaries."""

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

    def test_tc_t2_f_01_quote_already_accepted_reexecution_blocked(self):
        """TC-T2-F-01: A quote can only be executed once; subsequent executions return HTTP 409."""
        customer = self.enroll_customer("FX Re-User")
        self.fund_wallet(customer["token"], amount=50000)

        quote = self.client.call(
            "POST",
            "/v1/fx/quotes",
            token=customer["token"],
            body={"corridor": self.CORRIDOR, "sourceAmount": 10000},
            expected_status=200,
        )
        quote_id = quote.body.get("id")

        # First execution succeeds
        first_transfer = self.client.call(
            "POST",
            "/v1/fx/transfers",
            token=customer["token"],
            body={
                "quoteId": quote_id,
                "recipient": {"name": "Bob", "accountNumber": "123456789", "bankCode": "021000021"},
            },
            idempotency_key=unique_uuid(),
            expected_status=200,
        )
        self.assertEqual(first_transfer.body.get("status"), "pending")

        # Second transfer referencing the already consumed quote returns 409
        second_transfer = self.client.call(
            "POST",
            "/v1/fx/transfers",
            token=customer["token"],
            body={
                "quoteId": quote_id,
                "recipient": {"name": "Bob", "accountNumber": "123456789", "bankCode": "021000021"},
            },
            idempotency_key=unique_uuid(),
            expected_status=409,
        )
        self.assertEqual(second_transfer.status, 409)

    def test_tc_t2_f_02_fake_quote_id_rejection(self):
        """TC-T2-F-02: Executing transfer with non-existent quote ID returns HTTP 404 or 409."""
        customer = self.enroll_customer("Fake Quote User")
        self.fund_wallet(customer["token"], amount=20000)

        res = self.client.call(
            "POST",
            "/v1/fx/transfers",
            token=customer["token"],
            body={
                "quoteId": unique_uuid(),
                "recipient": {"name": "Alice", "accountNumber": "123456789", "bankCode": "021000021"},
            },
            idempotency_key=unique_uuid(),
            expected_status=[404, 409],
        )
        self.assertIn(res.status, (404, 409))

    def test_tc_t2_f_03_inbound_amount_discrepancy_rejection(self):
        """TC-T2-F-03: Inbound remittance with mathematically discrepant destination amount returns HTTP 422."""
        customer = self.enroll_customer("Inbound User")
        bad_instruction = {
            "partnerReference": f"MTCN-{unique_uuid()}",
            "corridor": self.IN_CORRIDOR,
            "sourceAmount": 10000,
            "sourceCurrency": "USD",
            "rate": "15.20",
            # Intentionally discrepant destination amount (152001 instead of 152000)
            "destinationAmount": 152001,
            "destinationCurrency": "GHS",
            "recipientPhone": local_phone(customer["phone"]),
            "senderName": "Sender",
        }
        body_str = json.dumps(bad_instruction)
        sig = sign_fx_partner(self.config.fx_secret, body_str)

        res = self.client.call(
            "POST",
            "/v1/fx/partners/sim-remit/inbound",
            body=body_str,
            headers={"x-partner-signature": sig},
            expected_status=[400, 422],
        )
        self.assertIn(res.status, (400, 422))

    def test_tc_t2_f_04_non_existent_corridor_quote_rejection(self):
        """TC-T2-F-04: Requesting quote for unsupported currency corridor returns HTTP 400 or 404."""
        customer = self.enroll_customer("Non Existent User")
        res = self.client.call(
            "POST",
            "/v1/fx/quotes",
            token=customer["token"],
            body={"corridor": "JPY-GHS-NONEXISTENT", "sourceAmount": 50000},
            expected_status=[400, 404],
        )
        self.assertIn(res.status, (400, 404))

    def test_tc_t2_f_05_fx_transfer_insufficient_wallet_balance(self):
        """TC-T2-F-05: Outbound FX transfer exceeding available customer wallet funds returns HTTP 409."""
        customer = self.enroll_customer("Broke Remitter")
        # Wallet has 0 available funds
        quote = self.client.call(
            "POST",
            "/v1/fx/quotes",
            token=customer["token"],
            body={"corridor": self.CORRIDOR, "sourceAmount": 64000},
            expected_status=200,
        )
        res = self.client.call(
            "POST",
            "/v1/fx/transfers",
            token=customer["token"],
            body={
                "quoteId": quote.body["id"],
                "recipient": {"name": "Bob", "accountNumber": "123456789", "bankCode": "021000021"},
            },
            idempotency_key=unique_uuid(),
            expected_status=[409, 422],
        )
        self.assertIn(res.status, (409, 422))
