"""Domain B: Payment Processing — Tier 1 Feature Coverage Test Suite.

Covers:
- TC-T1-B-01: Mobile Money Wallet Top-Up with Rail Recovery
- TC-T1-B-02: Guest Checkout Payment via Rail and Signed Callback
- TC-T1-B-03: Dynamic EMVCo QR Code Generation & CRC16 Validation
- TC-T1-B-04: Card Payment Flow with Tokenization Boundary
- TC-T1-B-05: Unknown-Outcome Rail Recovery Sweep
"""
import json
from tests.e2e.framework.assertions import (
    assert_http_status,
    crc16_ccitt,
    sign_rail_callback,
)
from tests.e2e.framework.fixtures import unique_id, unique_uuid
from tests.e2e.framework.runner import OpaqueTestCase


class TestPaymentFeatures(OpaqueTestCase):
    """Test suite for payment processing entry points and simulated rail gateways."""

    def test_tc_t1_b_01_momo_wallet_topup_and_recovery(self):
        """TC-T1-B-01: Initiate MoMo wallet funding, submit to rail, and confirm through recovery."""
        customer = self.enroll_customer("Topup User")
        topup_key = unique_uuid()

        topup_res = self.client.call(
            "POST",
            "/v1/wallet/topups",
            token=customer["token"],
            body={
                "amount": 25000,
                "method": "momo",
                "phone": "0245550005",
                "network": "mtn",
            },
            idempotency_key=topup_key,
            expected_status=200,
        )
        self.assertIn(topup_res.body.get("status"), ("pending", "succeeded"))

        # Trigger rail recovery to advance simulated transaction to succeeded
        self.client.call("POST", "/v1/ops/rails/recover", token=self.ops_token, expected_status=200)

        # Confirm wallet balance credited
        wallet = self.client.call("GET", "/v1/wallet", token=customer["token"])
        avail = next(a["balance"] for a in wallet.body["accounts"] if a["bucket"] == "available")
        self.assertEqual(avail, 25000)

    def test_tc_t1_b_02_guest_checkout_payment_with_signed_callback(self):
        """TC-T1-B-02: Pay checkout via guest MoMo flow and dispatch valid signed callback."""
        merchant = self.create_merchant("Electronics Shop", "merchant", fee_bps=100)

        order = self.client.call(
            "POST",
            f"/v1/merchants/{merchant['id']}/checkouts",
            token=merchant["ownerToken"],
            body={
                "orderReference": f"ORD-PAY-{unique_id()}",
                "allocations": [{"merchantId": merchant["id"], "kind": "sale", "amount": 8000}],
            },
            idempotency_key=unique_uuid(),
            expected_status=200,
        )
        ref = order.body["reference"]

        # Guest initiates MoMo payment
        pay_res = self.client.call(
            "POST",
            f"/v1/public/checkouts/{ref}/pay",
            body={"method": "momo", "phone": "0245550003", "network": "mtn"},
            idempotency_key=unique_uuid(),
            expected_status=200,
        )
        attempt_id = pay_res.body.get("attemptId")
        self.assertIsNotNone(attempt_id)

        # Dispatch signed callback
        cb_body = json.dumps({
            "event": "transaction.succeeded",
            "data": {
                "reference": attempt_id,
                "status": "succeeded",
                "amount": 8000,
                "currency": "GHS",
            },
        })
        cb_sig = sign_rail_callback(self.config.rail_secret, cb_body)
        cb_res = self.client.call(
            "POST",
            "/v1/rails/sandbox/callbacks",
            body=cb_body,
            headers={"x-sandbox-signature": cb_sig},
            expected_status=200,
        )
        self.assertEqual(cb_res.status, 200)

        # Checkout status is now paid
        status_res = self.client.call("GET", f"/v1/public/checkouts/{ref}/status", expected_status=200)
        self.assertEqual(status_res.body.get("status"), "paid")

    def test_tc_t1_b_03_dynamic_emvco_qr_code_generation(self):
        """TC-T1-B-03: Generate dynamic merchant EMVCo QR code and verify payload tags and CRC16 checksum."""
        merchant = self.create_merchant("Supermarket QR", "merchant")
        qr_res = self.client.call("GET", f"/v1/merchants/{merchant['id']}/qr", token=merchant["ownerToken"], expected_status=200)
        
        qr_payload = qr_res.body.get("qrPayload")
        self.assertIsNotNone(qr_payload)
        self.assertTrue(qr_payload.startswith("000201"))  # EMVCo Tag 00 format indicator
        self.assertIn("6304", qr_payload)  # CRC Tag 63

        # Verify CRC16 checksum
        payload_prefix, expected_crc = qr_payload.rsplit("6304", 1)
        calculated_crc = crc16_ccitt(payload_prefix + "6304")
        self.assertEqual(expected_crc, calculated_crc)

    def test_tc_t1_b_04_card_payment_tokenization_boundary(self):
        """TC-T1-B-04: Card payment flow creates processing attempt without leaking raw card credentials."""
        merchant = self.create_merchant("Bookstore", "merchant")
        order = self.client.call(
            "POST",
            f"/v1/merchants/{merchant['id']}/checkouts",
            token=merchant["ownerToken"],
            body={"allocations": [{"merchantId": merchant["id"], "kind": "sale", "amount": 5000}]},
            idempotency_key=unique_uuid(),
            expected_status=200,
        )
        ref = order.body["reference"]

        card_pay = self.client.call(
            "POST",
            f"/v1/public/checkouts/{ref}/pay",
            body={"method": "card", "email": "shopper@example.com"},
            idempotency_key=unique_uuid(),
            expected_status=200,
        )
        self.assertIn(card_pay.body.get("status"), ("processing", "pending", "paid"))
        # Security invariant: Raw PAN or CVV must not be echoed in response
        body_str = json.dumps(card_pay.body)
        self.assertNotIn("pan", body_str.lower())
        self.assertNotIn("cvv", body_str.lower())

    def test_tc_t1_b_05_unknown_outcome_rail_recovery_sweep(self):
        """TC-T1-B-05: Ops rail recovery sweep queries pending provider transactions and updates status."""
        rec_res = self.client.call("POST", "/v1/ops/rails/recover", token=self.ops_token, expected_status=200)
        self.assertIsInstance(rec_res.body.get("recovered"), int)
        self.assertGreaterEqual(rec_res.body.get("recovered"), 0)
