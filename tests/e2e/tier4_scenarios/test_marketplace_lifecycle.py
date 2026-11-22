"""Tier 4: Scenario 1 — Complete Ghana Marketplace Lifecycle.

Full multi-actor end-to-end journey:
1. Marketplace onboarding & seller invitations
2. Multi-line commercial checkout creation
3. Customer wallet funding via MoMo topup & rail recovery
4. Split checkout payment execution & duplicate prevention
5. Line refund & fee return
6. Delivery confirmation and settlement run
7. Merchant proceeds withdrawal to settlement destination
8. Final authoritative ledger drift verification
"""
from tests.e2e.framework.assertions import (
    assert_http_status,
    assert_ledger_integrity,
    assert_minor_unit_integer,
)
from tests.e2e.framework.fixtures import unique_id, unique_uuid
from tests.e2e.framework.runner import OpaqueTestCase


class TestMarketplaceLifecycleScenario(OpaqueTestCase):
    """Test suite executing the full Ghana commercial marketplace multi-actor lifecycle."""

    def test_complete_ghana_marketplace_lifecycle(self):
        """Scenario 1: Full real-world journey from merchant onboarding to bank withdrawal with zero ledger drift."""
        # --- Stage 1: Marketplace and Seller Onboarding ---
        mp = self.create_merchant("ELEMARKET", "marketplace", fee_bps=0)
        seller_a = self.create_merchant("Accra Kicks", "merchant", fee_bps=150)
        seller_b = self.create_merchant("Tema Wear", "merchant", fee_bps=200)
        delivery = self.create_merchant("ELESEND", "delivery", fee_bps=0)

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

        # --- Stage 2: Multi-Party Commercial Checkout Creation ---
        # Line 0: Accra Kicks (Shoes) = GH₵120.00 (12,000 pesewas)
        # Line 1: Tema Wear (Shirt)   = GH₵60.00  (6,000 pesewas)
        # Line 2: ELESEND (Delivery)  = GH₵20.00  (2,000 pesewas)
        # Total Subtotal = GH₵200.00 (20,000 pesewas)
        order = self.client.call(
            "POST",
            f"/v1/merchants/{mp['id']}/checkouts",
            token=mp["ownerToken"],
            body={
                "orderReference": f"ELM-MKT-ORDER-{self.run_id}",
                "allocations": [
                    {"merchantId": seller_a["id"], "kind": "sale", "amount": 12000, "description": "Shoes"},
                    {"merchantId": seller_b["id"], "kind": "sale", "amount": 6000, "description": "Shirt"},
                    {"merchantId": delivery["id"], "kind": "delivery", "amount": 2000, "description": "Courier Delivery"},
                ],
            },
            idempotency_key=unique_uuid(),
            expected_status=200,
        )
        checkout_ref = order.body["reference"]

        # --- Stage 3: Customer Wallet Funding ---
        kwame = self.enroll_customer("Kwame Mensah")
        self.fund_wallet(kwame["token"], amount=20000)

        # Confirm funded balance
        kwame_wallet = self.client.call("GET", "/v1/wallet", token=kwame["token"])
        avail = next(a["balance"] for a in kwame_wallet.body["accounts"] if a["bucket"] == "available")
        self.assertEqual(avail, 20000)

        # --- Stage 4: Checkout Wallet Payment ---
        pay_key = unique_uuid()
        paid_res = self.client.call(
            "POST",
            f"/v1/checkouts/{checkout_ref}/pay/wallet",
            token=kwame["token"],
            idempotency_key=pay_key,
            expected_status=200,
        )
        self.assertEqual(paid_res.body.get("status"), "paid")

        # Duplicate payment attempt blocked
        repeat_res = self.client.call(
            "POST",
            f"/v1/checkouts/{checkout_ref}/pay/wallet",
            token=kwame["token"],
            idempotency_key=unique_uuid(),
            expected_status=409,
        )
        self.assertEqual(repeat_res.status, 409)

        # --- Stage 5: Line 1 Refund (Seller B cancelled) ---
        refund_res = self.client.call(
            "POST",
            f"/v1/merchants/{seller_b['id']}/checkouts/{checkout_ref}/allocations/1/refunds",
            token=seller_b["ownerToken"],
            body={"amount": 6000, "reason": "Size out of stock"},
            idempotency_key=unique_uuid(),
            expected_status=200,
        )
        # 200 bps on 6000 = 120 fee returned
        self.assertEqual(refund_res.body.get("feeReturned"), 120)

        # Kwame wallet refunded 6000 pesewas
        kwame_wallet_after = self.client.call("GET", "/v1/wallet", token=kwame["token"])
        kwame_avail = next(a["balance"] for a in kwame_wallet_after.body["accounts"] if a["bucket"] == "available")
        self.assertEqual(kwame_avail, 6000)

        # --- Stage 6: Delivery Confirmation and Settlement Run ---
        # Marketplace lines are held until the marketplace confirms delivery.
        self.client.call(
            "POST",
            f"/v1/merchants/{mp['id']}/checkouts/{checkout_ref}/deliveries",
            token=mp["ownerToken"],
            body={},
            expected_status=200,
        )
        # Settle seller A and delivery
        self.client.call(
            "POST",
            "/v1/ops/settlements/run",
            token=self.ops_token,
            body={"ignoreDelay": True, "merchantId": seller_a["id"]},
            expected_status=200,
        )
        self.client.call(
            "POST",
            "/v1/ops/settlements/run",
            token=self.ops_token,
            body={"ignoreDelay": True, "merchantId": delivery["id"]},
            expected_status=200,
        )

        # Seller A (12000 - 1.5%(180) = 11820 available)
        bal_a = self.client.call("GET", f"/v1/merchants/{seller_a['id']}/balances", token=seller_a["ownerToken"])
        self.assertEqual(bal_a.body.get("available"), 11820)
        self.assertEqual(bal_a.body.get("pending"), 0)

        # Delivery (2000 available)
        bal_d = self.client.call("GET", f"/v1/merchants/{delivery['id']}/balances", token=delivery["ownerToken"])
        self.assertEqual(bal_d.body.get("available"), 2000)
        self.assertEqual(bal_d.body.get("pending"), 0)

        # --- Stage 7: Proceeds Withdrawal to Bank / MoMo ---
        dest_a = self.client.call(
            "POST",
            f"/v1/merchants/{seller_a['id']}/settlement-destinations",
            token=seller_a["ownerToken"],
            body={
                "type": "momo",
                "network": "mtn",
                "accountNumber": "0245550005",
                "accountName": seller_a["tradingName"],
            },
            expected_status=200,
        )
        self.client.call(
            "POST",
            f"/v1/merchants/{seller_a['id']}/withdrawals",
            token=seller_a["ownerToken"],
            body={"amount": 11820, "destinationId": dest_a.body["id"]},
            idempotency_key=unique_uuid(),
            expected_status=200,
        )

        dest_d = self.client.call(
            "POST",
            f"/v1/merchants/{delivery['id']}/settlement-destinations",
            token=delivery["ownerToken"],
            body={
                "type": "momo",
                "network": "mtn",
                "accountNumber": "0245550005",
                "accountName": delivery["tradingName"],
            },
            expected_status=200,
        )
        self.client.call(
            "POST",
            f"/v1/merchants/{delivery['id']}/withdrawals",
            token=delivery["ownerToken"],
            body={"amount": 2000, "destinationId": dest_d.body["id"]},
            idempotency_key=unique_uuid(),
            expected_status=200,
        )

        # Trigger rail recovery to complete withdrawals
        self.client.call("POST", "/v1/ops/rails/recover", token=self.ops_token, expected_status=200)

        # Confirm balances are 0
        final_a = self.client.call("GET", f"/v1/merchants/{seller_a['id']}/balances", token=seller_a["ownerToken"])
        self.assertEqual(final_a.body.get("available"), 0)
        self.assertEqual(final_a.body.get("pending"), 0)
        self.assertEqual(final_a.body.get("reserved"), 0)

        # --- Stage 8: Final Authoritative Ledger Drift Verification ---
        self.verify_ledger_integrity()
