"""Tier 3: Combo 2 — Customer Wallet + Commercial Checkout + Multi-Allocation Split.

Validates:
- Multi-party commercial checkout allocations
- Agreed merchant fee matrices (bps + fixed)
- Atomic double-entry single debit from customer wallet with multi-credit split
- Merchant multi-tenant isolation (404 on cross-merchant lookups)
- Repeat payment rejection
"""
from tests.e2e.framework.assertions import (
    assert_http_status,
    assert_ledger_integrity,
    assert_minor_unit_integer,
)
from tests.e2e.framework.fixtures import unique_id, unique_uuid
from tests.e2e.framework.runner import OpaqueTestCase


class TestCheckoutSplitCombo(OpaqueTestCase):
    """Test suite for marketplace multi-allocation split payments, fee recognition, and tenancy isolation."""

    def test_combo_2_marketplace_split_and_tenancy_isolation(self):
        """Combo 2: Onboard MP & 3 sellers -> Multi-line checkout -> Single wallet pay -> Balance proof -> Tenant 404."""
        # 1. Onboard marketplace and sellers
        mp = self.create_merchant("COMBO-MP", "marketplace", fee_bps=0)
        seller_a = self.create_merchant("Seller A", "merchant", fee_bps=150)
        seller_b = self.create_merchant("Seller B", "merchant", fee_bps=100)
        delivery = self.create_merchant("Courier D", "delivery", fee_bps=0)

        # Link sellers to marketplace
        for s in (seller_a, seller_b, delivery):
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

        # 2. Marketplace creates order with 3 allocations: 80.00, 50.00, 20.00 -> Total 150.00 GHS (15000 pesewas)
        order = self.client.call(
            "POST",
            f"/v1/merchants/{mp['id']}/checkouts",
            token=mp["ownerToken"],
            body={
                "orderReference": f"ELM-SPLIT-{unique_id()}",
                "allocations": [
                    {"merchantId": seller_a["id"], "kind": "sale", "amount": 8000},
                    {"merchantId": seller_b["id"], "kind": "sale", "amount": 5000},
                    {"merchantId": delivery["id"], "kind": "delivery", "amount": 2000},
                ],
            },
            idempotency_key=unique_uuid(),
            expected_status=200,
        )
        checkout_ref = order.body["reference"]

        # 3. Customer funds wallet with 15000 pesewas
        customer = self.enroll_customer("Marketplace Buyer")
        self.fund_wallet(customer["token"], amount=15000)

        # 4. Customer pays checkout via wallet
        pay_key = unique_uuid()
        paid_res = self.client.call(
            "POST",
            f"/v1/checkouts/{checkout_ref}/pay/wallet",
            token=customer["token"],
            idempotency_key=pay_key,
            expected_status=200,
        )
        self.assertEqual(paid_res.body.get("status"), "paid")

        # 5. Idempotent payment replay returns exact same receipt
        replay_res = self.client.call(
            "POST",
            f"/v1/checkouts/{checkout_ref}/pay/wallet",
            token=customer["token"],
            idempotency_key=pay_key,
            expected_status=200,
        )
        self.assertEqual(replay_res.body, paid_res.body)

        # 6. Repeat payment with a fresh key is rejected with HTTP 409
        repeat_res = self.client.call(
            "POST",
            f"/v1/checkouts/{checkout_ref}/pay/wallet",
            token=customer["token"],
            idempotency_key=unique_uuid(),
            expected_status=409,
        )
        self.assertEqual(repeat_res.status, 409)

        # 7. Verify balances:
        # Customer: 0 available
        wallet = self.client.call("GET", "/v1/wallet", token=customer["token"])
        avail = next(a["balance"] for a in wallet.body["accounts"] if a["bucket"] == "available")
        self.assertEqual(avail, 0)

        # Seller A: 8000 - 1.5%(120) = 7880 pending
        bal_a = self.client.call("GET", f"/v1/merchants/{seller_a['id']}/balances", token=seller_a["ownerToken"])
        self.assertEqual(bal_a.body.get("pending"), 7880)

        # Seller B: 5000 - 1.0%(50) = 4950 pending
        bal_b = self.client.call("GET", f"/v1/merchants/{seller_b['id']}/balances", token=seller_b["ownerToken"])
        self.assertEqual(bal_b.body.get("pending"), 4950)

        # Delivery: 2000 pending
        bal_d = self.client.call("GET", f"/v1/merchants/{delivery['id']}/balances", token=delivery["ownerToken"])
        self.assertEqual(bal_d.body.get("pending"), 2000)

        # 8. Multi-tenant isolation: Seller B cannot view Seller A's balances -> HTTP 404
        iso_res = self.client.call(
            "GET",
            f"/v1/merchants/{seller_a['id']}/balances",
            token=seller_b["ownerToken"],
            expected_status=404,
        )
        self.assertEqual(iso_res.status, 404)

        # 9. Verify zero ledger drift
        self.verify_ledger_integrity()
