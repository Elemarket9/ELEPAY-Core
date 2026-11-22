"""Tier 4: Scenario 3 — Adverse Network Recovery & Chaos Resilience.

Simulates network instability and asynchronous recovery:
1. Rail submission enters pending/processing state
2. Repeat submission detection returns original attempt without double-charging
3. Status query recovery resolves pending transaction
4. Replayed duplicate and out-of-order callbacks handled idempotently
5. Ledger integrity maintained across transient failures
"""
import json
from tests.e2e.framework.assertions import (
    assert_http_status,
    assert_ledger_integrity,
    sign_rail_callback,
)
from tests.e2e.framework.fixtures import unique_id, unique_uuid
from tests.e2e.framework.runner import OpaqueTestCase


class TestNetworkChaosRecoveryScenario(OpaqueTestCase):
    """Test suite executing chaos resilience drills, dropped connections, and callback reorder storms."""

    def test_adverse_network_recovery_and_callback_resilience(self):
        """Scenario 3: Simulated rail delay -> Repeat attempt resume -> Ops recovery -> Duplicate callback assault."""
        merchant = self.create_merchant("Resilient Merchant", "merchant")
        order = self.client.call(
            "POST",
            f"/v1/merchants/{merchant['id']}/checkouts",
            token=merchant["ownerToken"],
            body={"allocations": [{"merchantId": merchant["id"], "kind": "sale", "amount": 10000}]},
            idempotency_key=unique_uuid(),
            expected_status=200,
        )
        checkout_ref = order.body["reference"]

        # 1. Customer initiates MoMo checkout (phone ending in 3 stays pending until status query or callback)
        pay_payload = {"method": "momo", "phone": "0245550003", "network": "mtn"}
        first_attempt = self.client.call(
            "POST",
            f"/v1/public/checkouts/{checkout_ref}/pay",
            body=pay_payload,
            idempotency_key=unique_uuid(),
            expected_status=200,
        )
        attempt_id = first_attempt.body.get("attemptId")
        self.assertIsNotNone(attempt_id)
        self.assertIn(first_attempt.body.get("status"), ("processing", "pending"))

        # 2. Resubmission check: Customer re-submits pay request
        resumed_attempt = self.client.call(
            "POST",
            f"/v1/public/checkouts/{checkout_ref}/pay",
            body=pay_payload,
            idempotency_key=unique_uuid(),
            expected_status=200,
        )
        # Resumed attempt returns the same attemptId without creating duplicate rail charges
        self.assertEqual(resumed_attempt.body.get("attemptId"), attempt_id)

        # 3. Ops status query recovery sweep resolves pending rail transaction
        self.client.call("POST", "/v1/ops/rails/recover", token=self.ops_token, expected_status=200)

        # 4. Out-of-order and duplicate callback assault
        cb_succeeded = json.dumps({
            "event": "transaction.succeeded",
            "data": {
                "reference": attempt_id,
                "status": "succeeded",
                "amount": 10000,
                "currency": "GHS",
            },
        })
        sig_succeeded = sign_rail_callback(self.config.rail_secret, cb_succeeded)

        # Replay duplicate callback multiple times
        for _ in range(2):
            self.client.call(
                "POST",
                "/v1/rails/sandbox/callbacks",
                body=cb_succeeded,
                headers={"x-sandbox-signature": sig_succeeded},
                expected_status=200,
            )

        # Replay stale pending callback
        cb_pending = json.dumps({
            "event": "transaction.pending",
            "data": {
                "reference": attempt_id,
                "status": "pending",
                "amount": 10000,
                "currency": "GHS",
            },
        })
        sig_pending = sign_rail_callback(self.config.rail_secret, cb_pending)
        self.client.call(
            "POST",
            "/v1/rails/sandbox/callbacks",
            body=cb_pending,
            headers={"x-sandbox-signature": sig_pending},
            expected_status=200,
        )

        # Checkout must remain in paid state
        status_res = self.client.call("GET", f"/v1/public/checkouts/{checkout_ref}/status", expected_status=200)
        self.assertEqual(status_res.body.get("status"), "paid")

        # 5. Ledger integrity holds with 0 drift
        self.verify_ledger_integrity()
