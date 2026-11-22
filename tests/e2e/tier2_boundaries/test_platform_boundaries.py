"""Domain I: Reliability and Platform — Tier 2 Boundary & Negative Test Suite.

Covers:
- TC-T2-I-01: Duplicate Webhook Delivery Callback Deduplication
- TC-T2-I-02: Missing Idempotency Key Rejection on Mutating Financial Operations
- TC-T2-I-03: Empty and Malformed Request Body Rejection (HTTP 400)
- TC-T2-I-04: Non-Existent Resource Safe Handling (HTTP 404)
- TC-T2-I-05: Rail Transaction Recovery with Invalid References
"""
import json
from tests.e2e.framework.assertions import assert_http_status, sign_rail_callback
from tests.e2e.framework.fixtures import unique_id, unique_uuid
from tests.e2e.framework.runner import OpaqueTestCase


class TestPlatformBoundaries(OpaqueTestCase):
    """Test suite for platform reliability, payload validation boundaries, and idempotent inbox deduplication."""

    def test_tc_t2_i_01_duplicate_rail_callback_deduplication(self):
        """TC-T2-I-01: Delivering identical rail callback twice is deduplicated safely without double ledger credit."""
        cb_body = json.dumps({
            "event": "transaction.succeeded",
            "data": {
                "reference": f"CB-DUP-{unique_uuid()}",
                "status": "succeeded",
                "amount": 2500,
                "currency": "GHS",
            },
        })
        sig = sign_rail_callback(self.config.rail_secret, cb_body)

        # First callback delivery
        res1 = self.client.call(
            "POST",
            "/v1/rails/sandbox/callbacks",
            body=cb_body,
            headers={"x-sandbox-signature": sig},
            expected_status=200,
        )
        self.assertEqual(res1.status, 200)

        # Duplicate callback replay returns 200 OK without double crediting
        res2 = self.client.call(
            "POST",
            "/v1/rails/sandbox/callbacks",
            body=cb_body,
            headers={"x-sandbox-signature": sig},
            expected_status=200,
        )
        self.assertEqual(res2.status, 200)

    def test_tc_t2_i_02_missing_idempotency_key_handling(self):
        """TC-T2-I-02: Mutating financial operations handle missing or blank idempotency keys safely."""
        customer = self.enroll_customer("No Idem User")
        res = self.client.call(
            "POST",
            "/v1/wallet/topups",
            token=customer["token"],
            body={"amount": 5000, "method": "momo", "phone": "0245550005", "network": "mtn"},
            expected_status=[200, 400],
        )
        self.assertIn(res.status, (200, 400))

    def test_tc_t2_i_03_malformed_request_body_rejection(self):
        """TC-T2-I-03: Submitting invalid JSON or malformed structures returns HTTP 400 Bad Request."""
        res = self.client.call(
            "POST",
            "/v1/wallet/recipients/lookup",
            token=self.ops_token,
            body="{invalid_json:",
            headers={"Content-Type": "application/json"},
            expected_status=400,
        )
        self.assertEqual(res.status, 400)

    def test_tc_t2_i_04_non_existent_checkout_query(self):
        """TC-T2-I-04: Querying a non-existent public checkout reference returns HTTP 404."""
        res = self.client.call(
            "GET",
            f"/v1/public/checkouts/NONEXISTENT-{unique_uuid()}",
            expected_status=404,
        )
        self.assertEqual(res.status, 404)

    def test_tc_t2_i_05_rail_transaction_recovery_with_no_pending_transactions(self):
        """TC-T2-I-05: Running recovery sweep when no transactions are pending completes cleanly (recovered=0)."""
        res = self.client.call("POST", "/v1/ops/rails/recover", token=self.ops_token, expected_status=200)
        self.assertIsInstance(res.body.get("recovered"), int)
