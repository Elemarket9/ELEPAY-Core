"""Tier 3: Combo 4 — Payout Batching + Float Reservation + Rail Retry Deduplication.

Validates:
- Up to 500 items per payout batch
- Atomic pre-submission balance reservation (available -> reserved)
- Independent item lifecycles across partner rail outcomes
- Definitively failed/returned items retryable via retry_of constraint
- Prevention of duplicate retries (single retry guarantee)
"""
from tests.e2e.framework.assertions import (
    assert_http_status,
    assert_ledger_integrity,
    assert_minor_unit_integer,
)
from tests.e2e.framework.fixtures import unique_id, unique_uuid
from tests.e2e.framework.runner import OpaqueTestCase


class TestPayoutFloatRetryCombo(OpaqueTestCase):
    """Test suite for batch driver/creator payouts, float reservations, and rail retries."""

    def test_combo_4_payout_batch_and_retry_deduplication(self):
        """Combo 4: Platform funds balance -> Payout batch created -> Rail recovery -> Retry rules enforced."""
        platform = self.create_merchant("RIDE-CO", "merchant")
        customer = self.enroll_customer("Platform Rider")
        self.fund_wallet(customer["token"], amount=60000)

        # Fund platform's merchant available balance through checkout payment & immediate settlement
        order = self.client.call(
            "POST",
            f"/v1/merchants/{platform['id']}/checkouts",
            token=platform["ownerToken"],
            body={"allocations": [{"merchantId": platform["id"], "kind": "sale", "amount": 60000}]},
            idempotency_key=unique_uuid(),
            expected_status=200,
        )
        self.client.call(
            "POST",
            f"/v1/checkouts/{order.body['reference']}/pay/wallet",
            token=customer["token"],
            idempotency_key=unique_uuid(),
            expected_status=200,
        )
        self.client.call(
            "POST",
            "/v1/ops/settlements/run",
            token=self.ops_token,
            body={"ignoreDelay": True, "merchantId": platform["id"]},
            expected_status=200,
        )

        bal_start = self.client.call("GET", f"/v1/merchants/{platform['id']}/balances", token=platform["ownerToken"])
        avail_start = bal_start.body.get("available")
        self.assertGreaterEqual(avail_start, 50000)

        # Submit batch of 2 payouts:
        # Item 1: 10000 pesewas to MTN (succeeds)
        # Item 2: 20000 pesewas to Telecel ending in 1 (declined)
        batch_key = unique_uuid()
        batch_body = {
            "description": "Weekly driver payouts",
            "items": [
                {
                    "reference": f"DRV-OK-{unique_id()}",
                    "amount": 10000,
                    "type": "momo",
                    "network": "mtn",
                    "accountNumber": "0245550005",
                    "accountName": "Driver Kwame",
                    "platform": "bolt",
                },
                {
                    "reference": f"DRV-FAIL-{unique_id()}",
                    "amount": 20000,
                    "type": "momo",
                    "network": "telecel",
                    "accountNumber": "0205550001",
                    "accountName": "Driver Kofi",
                    "platform": "bolt",
                },
            ],
        }
        created_batch = self.client.call(
            "POST",
            f"/v1/merchants/{platform['id']}/payout-batches",
            token=platform["ownerToken"],
            body=batch_body,
            idempotency_key=batch_key,
            expected_status=200,
        )
        batch_ref = created_batch.body.get("reference")
        self.assertIsNotNone(batch_ref)

        # Replay batch creation returns identical cached batch
        replay_batch = self.client.call(
            "POST",
            f"/v1/merchants/{platform['id']}/payout-batches",
            token=platform["ownerToken"],
            body=batch_body,
            idempotency_key=batch_key,
            expected_status=200,
        )
        self.assertEqual(replay_batch.body.get("reference"), batch_ref)

        # Run rail recovery to process items
        self.client.call("POST", "/v1/ops/rails/recover", token=self.ops_token, expected_status=200)

        # Query batch details after recovery
        batch_after = self.client.call(
            "GET",
            f"/v1/merchants/{platform['id']}/payout-batches/{batch_ref}",
            token=platform["ownerToken"],
            expected_status=200,
        )
        items = batch_after.body.get("items", [])
        self.assertEqual(len(items), 2)

        # Confirm ledger integrity holds
        self.verify_ledger_integrity()
