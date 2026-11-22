"""Domain A: Ledger and Money — Tier 1 Feature Coverage Test Suite.

Covers:
- TC-T1-A-01: Double-Entry Balanced Internal Wallet Transfer
- TC-T1-A-02: Multi-Allocation Split Payment with Fee Attribution
- TC-T1-A-03: Proportional Fee-Reversing Allocation Refund
- TC-T1-A-04: Balance Snapshot Reconstruction & Statement Export
- TC-T1-A-05: Public Fee Calculation & Tiered Margin Quotation
"""
import uuid
from tests.e2e.framework.assertions import (
    assert_http_status,
    assert_minor_unit_integer,
)
from tests.e2e.framework.fixtures import local_phone, unique_id, unique_uuid
from tests.e2e.framework.runner import OpaqueTestCase


class TestLedgerFeatures(OpaqueTestCase):
    """Test suite for core ledger and double-entry money movement happy paths."""

    def test_tc_t1_a_01_standard_double_entry_wallet_transfer(self):
        """TC-T1-A-01: Verify balanced debit and credit on confirmed wallet transfer."""
        sender = self.enroll_customer("Kofi Sender")
        recipient = self.enroll_customer("Ama Recipient")
        self.fund_wallet(sender["token"], amount=15000)

        # Lookup recipient to get confirmation ID
        lookup_res = self.client.call(
            "POST",
            "/v1/wallet/recipients/lookup",
            token=sender["token"],
            body={"rail": "wallet", "phone": local_phone(recipient["phone"])},
            expected_status=200,
        )
        self.assertEqual(lookup_res.body.get("status"), "verified")
        confirmation_id = lookup_res.body.get("confirmationId")
        self.assertIsNotNone(confirmation_id)

        # Preview transfer
        preview_res = self.client.call(
            "POST",
            "/v1/wallet/transfers/preview",
            token=sender["token"],
            body={"confirmationId": confirmation_id, "amount": 5000},
            expected_status=200,
        )
        self.assertEqual(preview_res.body.get("amount"), 5000)
        self.assertEqual(preview_res.body.get("total"), 5000)

        # Execute confirmed transfer
        transfer_key = unique_uuid()
        transfer_res = self.client.call(
            "POST",
            "/v1/wallet/transfers/confirmed",
            token=sender["token"],
            body={"confirmationId": confirmation_id, "amount": 5000},
            idempotency_key=transfer_key,
            expected_status=200,
        )
        self.assertEqual(transfer_res.body.get("status"), "completed")
        self.assertEqual(transfer_res.body.get("amount"), 5000)

        # Verify sender available balance decreased by 5000 (15000 - 5000 = 10000)
        sender_wallet = self.client.call("GET", "/v1/wallet", token=sender["token"], expected_status=200)
        sender_avail = next(a["balance"] for a in sender_wallet.body["accounts"] if a["bucket"] == "available")
        self.assertEqual(sender_avail, 10000)

        # Verify recipient available balance increased by 5000
        recipient_wallet = self.client.call("GET", "/v1/wallet", token=recipient["token"], expected_status=200)
        recipient_avail = next(a["balance"] for a in recipient_wallet.body["accounts"] if a["bucket"] == "available")
        self.assertEqual(recipient_avail, 5000)

    def test_tc_t1_a_02_multi_allocation_split_payment_with_fee(self):
        """TC-T1-A-02: Single customer wallet payment split across multiple merchants with fee attribution."""
        mp = self.create_merchant("ELEMARKET", "marketplace", fee_bps=0)
        seller_a = self.create_merchant("Seller A", "merchant", fee_bps=150)
        seller_b = self.create_merchant("Seller B", "merchant", fee_bps=100)
        delivery = self.create_merchant("ELESEND", "delivery", fee_bps=0)

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

        # Create multi-allocation order: 8000 + 5000 + 2000 = 15000 pesewas
        order_key = unique_uuid()
        order = self.client.call(
            "POST",
            f"/v1/merchants/{mp['id']}/checkouts",
            token=mp["ownerToken"],
            body={
                "orderReference": f"ORD-{self.run_id}-{unique_id()}",
                "allocations": [
                    {"merchantId": seller_a["id"], "kind": "sale", "amount": 8000},
                    {"merchantId": seller_b["id"], "kind": "sale", "amount": 5000},
                    {"merchantId": delivery["id"], "kind": "delivery", "amount": 2000},
                ],
            },
            idempotency_key=order_key,
            expected_status=200,
        )
        checkout_ref = order.body["reference"]

        # Enroll customer and fund 15000 pesewas
        customer = self.enroll_customer("Shopper")
        self.fund_wallet(customer["token"], amount=15000)

        # Execute wallet payment
        pay_key = unique_uuid()
        paid = self.client.call(
            "POST",
            f"/v1/checkouts/{checkout_ref}/pay/wallet",
            token=customer["token"],
            idempotency_key=pay_key,
            expected_status=200,
        )
        self.assertEqual(paid.body.get("status"), "paid")

        # Assert balances
        # Seller A (150 bps on 8000 = 120 fee -> pending 7880)
        bal_a = self.client.call("GET", f"/v1/merchants/{seller_a['id']}/balances", token=seller_a["ownerToken"])
        self.assertEqual(bal_a.body.get("pending"), 7880)

        # Seller B (100 bps on 5000 = 50 fee -> pending 4950)
        bal_b = self.client.call("GET", f"/v1/merchants/{seller_b['id']}/balances", token=seller_b["ownerToken"])
        self.assertEqual(bal_b.body.get("pending"), 4950)

        # Delivery D (0 bps on 2000 = 0 fee -> pending 2000)
        bal_d = self.client.call("GET", f"/v1/merchants/{delivery['id']}/balances", token=delivery["ownerToken"])
        self.assertEqual(bal_d.body.get("pending"), 2000)

        # Customer available balance is now 0
        cust_bal = self.client.call("GET", "/v1/wallet", token=customer["token"])
        cust_avail = next(a["balance"] for a in cust_bal.body["accounts"] if a["bucket"] == "available")
        self.assertEqual(cust_avail, 0)

    def test_tc_t1_a_03_proportional_fee_reversing_allocation_refund(self):
        """TC-T1-A-03: Line-item refund restores customer funds and reverses merchant fee proportionally."""
        mp = self.create_merchant("ELEMARKET", "marketplace", fee_bps=0)
        seller = self.create_merchant("Sole Seller", "merchant", fee_bps=100)

        self.client.call(
            "POST",
            f"/v1/merchants/{mp['id']}/sellers",
            token=mp["ownerToken"],
            body={"merchantCode": seller["merchantCode"]},
            expected_status=200,
        )
        self.client.call(
            "POST",
            f"/v1/merchants/{seller['id']}/marketplaces/{mp['id']}/accept",
            token=seller["ownerToken"],
            expected_status=200,
        )

        order = self.client.call(
            "POST",
            f"/v1/merchants/{mp['id']}/checkouts",
            token=mp["ownerToken"],
            body={
                "orderReference": f"ORD-REF-{unique_id()}",
                "allocations": [{"merchantId": seller["id"], "kind": "sale", "amount": 10000}],
            },
            idempotency_key=unique_uuid(),
            expected_status=200,
        )
        checkout_ref = order.body["reference"]

        customer = self.enroll_customer("Refund Buyer")
        self.fund_wallet(customer["token"], amount=10000)

        self.client.call(
            "POST",
            f"/v1/checkouts/{checkout_ref}/pay/wallet",
            token=customer["token"],
            idempotency_key=unique_uuid(),
            expected_status=200,
        )

        # Execute full line refund on allocation line 0
        refund_res = self.client.call(
            "POST",
            f"/v1/merchants/{seller['id']}/checkouts/{checkout_ref}/allocations/0/refunds",
            token=seller["ownerToken"],
            body={"amount": 10000, "reason": "Customer cancellation"},
            idempotency_key=unique_uuid(),
            expected_status=200,
        )
        self.assertEqual(refund_res.body.get("feeReturned"), 100)  # 1% of 10000 = 100

        # Customer wallet is refunded back to 10000
        cust_bal = self.client.call("GET", "/v1/wallet", token=customer["token"])
        cust_avail = next(a["balance"] for a in cust_bal.body["accounts"] if a["bucket"] == "available")
        self.assertEqual(cust_avail, 10000)

        # Merchant pending balance drops back to 0
        seller_bal = self.client.call("GET", f"/v1/merchants/{seller['id']}/balances", token=seller["ownerToken"])
        self.assertEqual(seller_bal.body.get("pending"), 0)

    def test_tc_t1_a_04_balance_snapshot_reconstruction_statement(self):
        """TC-T1-A-04: Retrieve customer statement and verify running balance matches current available balance."""
        customer = self.enroll_customer("Statement Holder")
        self.fund_wallet(customer["token"], amount=12000)

        statement_res = self.client.call("GET", "/v1/wallet/statement", token=customer["token"], expected_status=200)
        records = statement_res.body if isinstance(statement_res.body, list) else []
        self.assertGreaterEqual(len(records), 1)

        # Last entry's running balance should match wallet available balance
        last_entry = records[-1]
        self.assertEqual(last_entry.get("runningBalance"), 12000)
        self.assertEqual(last_entry.get("currency"), "GHS")
        assert_minor_unit_integer(last_entry.get("runningBalance"))

    def test_tc_t1_a_05_public_fee_calculation_and_tiered_margin(self):
        """TC-T1-A-05: Query public fee quote API and verify arithmetic relation (total == amount + fee)."""
        amount = 10000  # 100.00 GHS

        # MoMo method
        momo_quote = self.client.call(
            "GET",
            f"/v1/public/fees/quote?amount={amount}&method=momo",
            expected_status=200,
        )
        fee = momo_quote.body.get("fee")
        total = momo_quote.body.get("total")
        self.assertEqual(total, amount + fee)
        assert_minor_unit_integer(fee)
        assert_minor_unit_integer(total)

        # Wallet payment method (no customer surcharge)
        wallet_quote = self.client.call(
            "GET",
            f"/v1/public/fees/quote?amount={amount}&method=wallet",
            expected_status=200,
        )
        self.assertEqual(wallet_quote.body.get("fee"), 0)
        self.assertEqual(wallet_quote.body.get("total"), amount)
