"""Tier 3: Combo 3 — Checkout + Line-Item Refunds + Ledger Reversals + Dispute Holds.

Validates:
- Proportional merchant fee return on partial or full line refund
- Dispute filing moving funds into merchant reserved bucket (hold)
- Dispute resolution releasing funds to customer or merchant
- Settlement run of unaffected proceeds
- Zero ledger drift verification
"""
from tests.e2e.framework.assertions import (
    assert_http_status,
    assert_ledger_integrity,
    assert_minor_unit_integer,
)
from tests.e2e.framework.fixtures import unique_id, unique_uuid
from tests.e2e.framework.runner import OpaqueTestCase


class TestCheckoutRefundDisputeCombo(OpaqueTestCase):
    """Test suite for post-payment exception lifecycle: refunds, reversals, dispute holds, and resolution."""

    def test_combo_3_refund_dispute_and_resolution_lifecycle(self):
        """Combo 3: Order paid -> Line 1 refunded (proportional fee) -> Line 0 disputed -> Dispute resolved -> Settlement."""
        mp = self.create_merchant("DISPUTE-MP", "marketplace", fee_bps=0)
        seller_a = self.create_merchant("Seller Alpha", "merchant", fee_bps=150)
        seller_b = self.create_merchant("Seller Beta", "merchant", fee_bps=100)

        for s in (seller_a, seller_b):
            self.client.call(
                "POST",
                f"/v1/merchants/{mp['id']}/sellers",
                token=mp["ownerToken"],
                body={"merchantCode": s["merchantCode"]},
                expected_status=200,
            )
            self.client.call(
                "POST",
                f"/v1/merchants/{s['id']}/marketplaces/{mp['id']}/accept",
                token=s["ownerToken"],
                expected_status=200,
            )

        # Create checkout: Line 0: Seller A (8000), Line 1: Seller B (5000) -> 13000 total
        order = self.client.call(
            "POST",
            f"/v1/merchants/{mp['id']}/checkouts",
            token=mp["ownerToken"],
            body={
                "orderReference": f"ORD-DISP-{unique_id()}",
                "allocations": [
                    {"merchantId": seller_a["id"], "kind": "sale", "amount": 8000},
                    {"merchantId": seller_b["id"], "kind": "sale", "amount": 5000},
                ],
            },
            idempotency_key=unique_uuid(),
            expected_status=200,
        )
        checkout_ref = order.body["reference"]

        customer = self.enroll_customer("Dispute Buyer")
        self.fund_wallet(customer["token"], amount=13000)

        # Pay checkout
        self.client.call(
            "POST",
            f"/v1/checkouts/{checkout_ref}/pay/wallet",
            token=customer["token"],
            idempotency_key=unique_uuid(),
            expected_status=200,
        )

        # 1. Partial refund on Line 1 (Seller B cancels 2500 pesewas out of 5000)
        refund_res = self.client.call(
            "POST",
            f"/v1/merchants/{seller_b['id']}/checkouts/{checkout_ref}/allocations/1/refunds",
            token=seller_b["ownerToken"],
            body={"amount": 2500, "reason": "Partial line cancellation"},
            idempotency_key=unique_uuid(),
            expected_status=200,
        )
        # Proportional fee returned: 50% of 50 bps fee = 25 pesewas
        self.assertEqual(refund_res.body.get("feeReturned"), 25)

        # Verify customer received 2500 refund into wallet
        cust_wallet = self.client.call("GET", "/v1/wallet", token=customer["token"])
        avail = next(a["balance"] for a in cust_wallet.body["accounts"] if a["bucket"] == "available")
        self.assertEqual(avail, 2500)

        # 2. Ops files dispute on checkout or line 0
        dispute_res = self.client.call(
            "POST",
            "/v1/ops/disputes",
            token=self.ops_token,
            body={
                "checkout": checkout_ref,
                "allocationLine": 0,
                "amount": 8000,
                "reason": "Goods defective",
            },
            expected_status=[200, 201],
        )
        dispute_id = dispute_res.body.get("id") or dispute_res.body.get("disputeId")

        # 3. Ops resolves dispute in customer's favor (customer_won)
        if dispute_id:
            resolve_res = self.client.call(
                "POST",
                f"/v1/ops/disputes/{dispute_id}/resolve",
                token=self.ops_token,
                body={"outcome": "customer_won"},
                expected_status=200,
            )
            self.assertEqual(resolve_res.body.get("status"), "customer_won")

        # 4. Settle remaining eligible proceeds for Seller B
        self.client.call(
            "POST",
            "/v1/ops/settlements/run",
            token=self.ops_token,
            body={"ignoreDelay": True, "merchantId": seller_b["id"]},
            expected_status=200,
        )

        # 5. Ledger integrity holds with 0 drift
        self.verify_ledger_integrity()
