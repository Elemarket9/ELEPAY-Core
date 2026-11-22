"""Domain A: Ledger and Money — Tier 2 Boundary & Negative Test Suite.

Covers:
- TC-T2-A-01: Idempotency Collision with Altered Amount (HTTP 409)
- TC-T2-A-02: Overdraft Attempt on Available Balance (HTTP 409 Insufficient Funds)
- TC-T2-A-03: Excessive Refund Exceeding Original Allocation (HTTP 409)
- TC-T2-A-04: Duplicate Reversal of Already Reversed Transaction (HTTP 409)
- TC-T2-A-05: Zero and Negative Monetary Amount Submission (HTTP 400/422)
"""
from tests.e2e.framework.assertions import assert_http_status
from tests.e2e.framework.fixtures import local_phone, unique_id, unique_uuid
from tests.e2e.framework.runner import OpaqueTestCase


class TestLedgerBoundaries(OpaqueTestCase):
    """Test suite for ledger invariants, boundary conditions, and negative balance protection."""

    def test_tc_t2_a_01_idempotency_collision_with_altered_amount(self):
        """TC-T2-A-01: Re-submitting an identical idempotency key with modified payload returns HTTP 409 Conflict."""
        sender = self.enroll_customer("Collision Sender")
        recipient = self.enroll_customer("Collision Recipient")
        self.fund_wallet(sender["token"], amount=20000)

        # Obtain valid confirmation token
        lookup = self.client.call(
            "POST",
            "/v1/wallet/recipients/lookup",
            token=sender["token"],
            body={"rail": "wallet", "phone": local_phone(recipient["phone"])},
            expected_status=200,
        )
        conf_id = lookup.body.get("confirmationId")

        idem_key = f"collision-key-{unique_uuid()}"
        payload_1 = {"confirmationId": conf_id, "amount": 5000}

        # First request succeeds with 200 OK
        res1 = self.client.call(
            "POST",
            "/v1/wallet/transfers/confirmed",
            token=sender["token"],
            body=payload_1,
            idempotency_key=idem_key,
            expected_status=200,
        )
        self.assertEqual(res1.body.get("status"), "completed")

        # Second request with identical key but changed amount (5001) MUST fail with 409 Conflict
        payload_altered = {"confirmationId": conf_id, "amount": 5001}
        res2 = self.client.call(
            "POST",
            "/v1/wallet/transfers/confirmed",
            token=sender["token"],
            body=payload_altered,
            idempotency_key=idem_key,
            expected_status=409,
        )
        self.assertEqual(res2.status, 409)

    def test_tc_t2_a_02_overdraft_attempt_insufficient_funds(self):
        """TC-T2-A-02: Attempting to spend more funds than available in customer wallet triggers HTTP 409."""
        sender = self.enroll_customer("Overdraft Target")
        recipient = self.enroll_customer("Overdraft Beneficiary")
        # Fund only 5000 pesewas
        self.fund_wallet(sender["token"], amount=5000)

        lookup = self.client.call(
            "POST",
            "/v1/wallet/recipients/lookup",
            token=sender["token"],
            body={"rail": "wallet", "phone": local_phone(recipient["phone"])},
            expected_status=200,
        )
        conf_id = lookup.body.get("confirmationId")

        # Attempt to transfer 10000 pesewas (exceeds 5000 available)
        res = self.client.call(
            "POST",
            "/v1/wallet/transfers/confirmed",
            token=sender["token"],
            body={"confirmationId": conf_id, "amount": 10000},
            idempotency_key=unique_uuid(),
            expected_status=[409, 422],
        )
        self.assertIn(res.status, (409, 422))

        # Invariant check: Balance remains untouched at 5000
        wallet = self.client.call("GET", "/v1/wallet", token=sender["token"])
        avail = next(a["balance"] for a in wallet.body["accounts"] if a["bucket"] == "available")
        self.assertEqual(avail, 5000)

    def test_tc_t2_a_03_excessive_refund_rejection(self):
        """TC-T2-A-03: Attempting to refund an amount greater than the allocation line fails with HTTP 409."""
        merchant = self.create_merchant("Refund Merchant", "merchant", fee_bps=100)
        order = self.client.call(
            "POST",
            f"/v1/merchants/{merchant['id']}/checkouts",
            token=merchant["ownerToken"],
            body={"allocations": [{"merchantId": merchant["id"], "kind": "sale", "amount": 8000}]},
            idempotency_key=unique_uuid(),
            expected_status=200,
        )
        ref = order.body["reference"]

        customer = self.enroll_customer("Buyer")
        self.fund_wallet(customer["token"], amount=8000)

        self.client.call(
            "POST",
            f"/v1/checkouts/{ref}/pay/wallet",
            token=customer["token"],
            idempotency_key=unique_uuid(),
            expected_status=200,
        )

        # Attempt refund of 8001 pesewas on an 8000 pesewa allocation
        refund_overflow = self.client.call(
            "POST",
            f"/v1/merchants/{merchant['id']}/checkouts/{ref}/allocations/0/refunds",
            token=merchant["ownerToken"],
            body={"amount": 8001, "reason": "Overflow refund attempt"},
            idempotency_key=unique_uuid(),
            expected_status=409,
        )
        self.assertEqual(refund_overflow.status, 409)

    def test_tc_t2_a_04_duplicate_reversal_already_reversed(self):
        """TC-T2-A-04: Attempting to reverse a transaction that has already been fully reversed returns HTTP 409."""
        merchant = self.create_merchant("Reversal Merchant", "merchant", fee_bps=100)
        order = self.client.call(
            "POST",
            f"/v1/merchants/{merchant['id']}/checkouts",
            token=merchant["ownerToken"],
            body={"allocations": [{"merchantId": merchant["id"], "kind": "sale", "amount": 5000}]},
            idempotency_key=unique_uuid(),
            expected_status=200,
        )
        ref = order.body["reference"]

        customer = self.enroll_customer("Reversal Buyer")
        self.fund_wallet(customer["token"], amount=5000)

        self.client.call(
            "POST",
            f"/v1/checkouts/{ref}/pay/wallet",
            token=customer["token"],
            idempotency_key=unique_uuid(),
            expected_status=200,
        )

        # First full refund succeeds
        self.client.call(
            "POST",
            f"/v1/merchants/{merchant['id']}/checkouts/{ref}/allocations/0/refunds",
            token=merchant["ownerToken"],
            body={"amount": 5000, "reason": "Full return"},
            idempotency_key=unique_uuid(),
            expected_status=200,
        )

        # Second refund attempt on already reversed line returns 409
        dup_refund = self.client.call(
            "POST",
            f"/v1/merchants/{merchant['id']}/checkouts/{ref}/allocations/0/refunds",
            token=merchant["ownerToken"],
            body={"amount": 1, "reason": "Second refund attempt"},
            idempotency_key=unique_uuid(),
            expected_status=409,
        )
        self.assertEqual(dup_refund.status, 409)

    def test_tc_t2_a_05_zero_and_negative_amount_rejection(self):
        """TC-T2-A-05: Submitting zero or negative monetary amount is rejected by contract boundary validation."""
        customer = self.enroll_customer("Negative Tester")
        # Attempt topup with negative amount
        neg_res = self.client.call(
            "POST",
            "/v1/wallet/topups",
            token=customer["token"],
            body={"amount": -500, "method": "momo", "phone": "0245550005", "network": "mtn"},
            idempotency_key=unique_uuid(),
            expected_status=[400, 422],
        )
        self.assertIn(neg_res.status, (400, 422))

        # Attempt topup with zero amount
        zero_res = self.client.call(
            "POST",
            "/v1/wallet/topups",
            token=customer["token"],
            body={"amount": 0, "method": "momo", "phone": "0245550005", "network": "mtn"},
            idempotency_key=unique_uuid(),
            expected_status=[400, 422],
        )
        self.assertIn(zero_res.status, (400, 422))
