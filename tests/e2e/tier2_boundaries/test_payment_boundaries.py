"""Domain B: Payment Processing — Tier 2 Boundary & Negative Test Suite.

Covers:
- TC-T2-B-01: Concurrent Checkout Attempts Spending Identical Balance (Serialized Row Lock)
- TC-T2-B-02: Repeat Payment Attempt on Already-Paid Checkout (HTTP 409)
- TC-T2-B-03: Tampered Rail Callback Signature Rejection (HTTP 401/403)
- TC-T2-B-04: Payment Confirmation on Cancelled Checkout (HTTP 409)
- TC-T2-B-05: Unsupported Mobile Money Network Rejection (HTTP 400)
"""
import concurrent.futures
import json
from tests.e2e.framework.assertions import assert_http_status
from tests.e2e.framework.fixtures import unique_id, unique_uuid
from tests.e2e.framework.runner import OpaqueTestCase


class TestPaymentBoundaries(OpaqueTestCase):
    """Test suite for payment processing boundaries, race condition handling, and callback security."""

    def test_tc_t2_b_01_concurrent_checkout_attempts_on_same_balance(self):
        """TC-T2-B-01: Two concurrent checkouts competing for the same wallet balance result in exactly one 200 and one 409."""
        merchant_a = self.create_merchant("Shop A", "merchant")
        merchant_b = self.create_merchant("Shop B", "merchant")

        # Create two separate orders, each for 10000 pesewas
        order_a = self.client.call(
            "POST",
            f"/v1/merchants/{merchant_a['id']}/checkouts",
            token=merchant_a["ownerToken"],
            body={"allocations": [{"merchantId": merchant_a["id"], "kind": "sale", "amount": 10000}]},
            idempotency_key=unique_uuid(),
            expected_status=200,
        )
        order_b = self.client.call(
            "POST",
            f"/v1/merchants/{merchant_b['id']}/checkouts",
            token=merchant_b["ownerToken"],
            body={"allocations": [{"merchantId": merchant_b["id"], "kind": "sale", "amount": 10000}]},
            idempotency_key=unique_uuid(),
            expected_status=200,
        )

        # Fund customer with only 10000 pesewas (sufficient for only one order)
        customer = self.enroll_customer("Race Competitor")
        self.fund_wallet(customer["token"], amount=10000)

        ref_a = order_a.body["reference"]
        ref_b = order_b.body["reference"]

        def pay_checkout(ref):
            try:
                res = self.client.call(
                    "POST",
                    f"/v1/checkouts/{ref}/pay/wallet",
                    token=customer["token"],
                    idempotency_key=unique_uuid(),
                )
                return res.status
            except Exception as e:
                return 500

        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
            fut_a = executor.submit(pay_checkout, ref_a)
            fut_b = executor.submit(pay_checkout, ref_b)
            statuses = sorted([fut_a.result(), fut_b.result()])

        # Invariant: Exactly one payment succeeded (200), the other was rejected (409)
        self.assertEqual(statuses, [200, 409])

        # Final wallet balance must be exactly 0 (no unauthorized overdraft)
        wallet = self.client.call("GET", "/v1/wallet", token=customer["token"])
        avail = next(a["balance"] for a in wallet.body["accounts"] if a["bucket"] == "available")
        self.assertEqual(avail, 0)

    def test_tc_t2_b_02_repeat_payment_attempt_on_already_paid_checkout(self):
        """TC-T2-B-02: Attempting to pay an already settled checkout reference with a new key returns HTTP 409."""
        merchant = self.create_merchant("Retailer", "merchant")
        order = self.client.call(
            "POST",
            f"/v1/merchants/{merchant['id']}/checkouts",
            token=merchant["ownerToken"],
            body={"allocations": [{"merchantId": merchant["id"], "kind": "sale", "amount": 5000}]},
            idempotency_key=unique_uuid(),
            expected_status=200,
        )
        ref = order.body["reference"]

        customer = self.enroll_customer("Repeat Payer")
        self.fund_wallet(customer["token"], amount=20000)

        # First payment succeeds
        self.client.call(
            "POST",
            f"/v1/checkouts/{ref}/pay/wallet",
            token=customer["token"],
            idempotency_key=unique_uuid(),
            expected_status=200,
        )

        # Fresh payment attempt on the paid checkout returns 409
        second_pay = self.client.call(
            "POST",
            f"/v1/checkouts/{ref}/pay/wallet",
            token=customer["token"],
            idempotency_key=unique_uuid(),
            expected_status=409,
        )
        self.assertEqual(second_pay.status, 409)

    def test_tc_t2_b_03_tampered_rail_callback_signature_rejection(self):
        """TC-T2-B-03: Post rail callback with forged HMAC signature; must return HTTP 401 or 403."""
        cb_body = json.dumps({
            "event": "transaction.succeeded",
            "data": {
                "reference": f"FORGED-{unique_uuid()}",
                "status": "succeeded",
                "amount": 50000,
                "currency": "GHS",
            },
        })
        forged_sig = "deadbeef010203040506070809badcafefeed00"
        cb_res = self.client.call(
            "POST",
            "/v1/rails/sandbox/callbacks",
            body=cb_body,
            headers={"x-sandbox-signature": forged_sig},
            expected_status=[401, 403],
        )
        self.assertIn(cb_res.status, (401, 403))

    def test_tc_t2_b_04_payment_confirmation_on_cancelled_checkout(self):
        """TC-T2-B-04: Attempting to pay a cancelled checkout order returns HTTP 409 Conflict."""
        merchant = self.create_merchant("Cancelled Store", "merchant")
        order = self.client.call(
            "POST",
            f"/v1/merchants/{merchant['id']}/checkouts",
            token=merchant["ownerToken"],
            body={"allocations": [{"merchantId": merchant["id"], "kind": "sale", "amount": 6000}]},
            idempotency_key=unique_uuid(),
            expected_status=200,
        )
        ref = order.body["reference"]

        # Cancel the order
        self.client.call(
            "POST",
            f"/v1/merchants/{merchant['id']}/checkouts/{ref}/cancel",
            token=merchant["ownerToken"],
            expected_status=200,
        )

        customer = self.enroll_customer("Buyer")
        self.fund_wallet(customer["token"], amount=10000)

        # Attempt to pay cancelled order
        pay_cancelled = self.client.call(
            "POST",
            f"/v1/checkouts/{ref}/pay/wallet",
            token=customer["token"],
            idempotency_key=unique_uuid(),
            expected_status=409,
        )
        self.assertEqual(pay_cancelled.status, 409)

    def test_tc_t2_b_05_unsupported_momo_network_rejection(self):
        """TC-T2-B-05: Submitting an uncontracted or unsupported network name returns HTTP 400 Bad Request."""
        merchant = self.create_merchant("Network Store", "merchant")
        order = self.client.call(
            "POST",
            f"/v1/merchants/{merchant['id']}/checkouts",
            token=merchant["ownerToken"],
            body={"allocations": [{"merchantId": merchant["id"], "kind": "sale", "amount": 3000}]},
            idempotency_key=unique_uuid(),
            expected_status=200,
        )
        ref = order.body["reference"]

        bad_network_res = self.client.call(
            "POST",
            f"/v1/public/checkouts/{ref}/pay",
            body={"method": "momo", "phone": "0241234567", "network": "unsupported_foreign_network"},
            idempotency_key=unique_uuid(),
            expected_status=[400, 422],
        )
        self.assertIn(bad_network_res.status, (400, 422))
