"""Domain E: Reconciliation and Treasury — Tier 1 Feature Coverage Test Suite.

Covers:
- TC-T1-E-01: Multi-Currency Statement Reconciliation against Partner Lines
- TC-T1-E-02: Continuous Mathematical Ledger Reconstruction Check
- TC-T1-E-03: Scheduled Merchant Settlement Batch Execution
- TC-T1-E-04: Merchant Settlement History Query
- TC-T1-E-05: Operational Queue Exception Item Resolution
"""
import time
from tests.e2e.framework.assertions import (
    assert_http_status,
    assert_ledger_integrity,
)
from tests.e2e.framework.fixtures import unique_id, unique_uuid
from tests.e2e.framework.runner import OpaqueTestCase


class TestReconciliationFeatures(OpaqueTestCase):
    """Test suite for ledger reconciliation, settlement batching, and treasury operations."""

    def test_tc_t1_e_01_multi_currency_statement_reconciliation(self):
        """TC-T1-E-01: Reconcile partner statement lines with internal ledger records."""
        today = time.strftime("%Y-%m-%d", time.gmtime())
        recon_payload = {
            "period": today,
            "lines": [
                {
                    "reference": "UNKNOWN-E2E-REF-01",
                    "sourceCurrency": "USD",
                    "sourceAmount": 100,
                    "destinationCurrency": "GHS",
                    "destinationAmount": 1520,
                    "status": "paid",
                }
            ],
        }
        res = self.client.call(
            "POST",
            "/v1/ops/fx/reconcile/sim-remit",
            token=self.ops_token,
            body=recon_payload,
            expected_status=200,
        )
        self.assertIsInstance(res.body, list)
        self.assertGreaterEqual(len(res.body), 1)
        self.assertEqual(res.body[0].get("result"), "missing_internal")

    def test_tc_t1_e_02_continuous_ledger_reconstruction_check(self):
        """TC-T1-E-02: Verify zero accounts with drift, zero unbalanced transactions, and net-zero per currency."""
        integrity = self.verify_ledger_integrity()
        self.assertEqual(integrity.get("accountsWithDrift"), 0)
        self.assertEqual(integrity.get("unbalancedTransactions"), 0)
        for curr, net in integrity.get("netByCurrency", {}).items():
            self.assertEqual(net, 0, f"Net balance for currency {curr} is not zero: {net}")

    def test_tc_t1_e_03_scheduled_merchant_settlement_batch(self):
        """TC-T1-E-03: Scheduled settlement run moves mature proceeds from pending to available balance."""
        merchant = self.create_merchant("Settled Merchant", "merchant", fee_bps=100)
        customer = self.enroll_customer("Settlement Payer")
        self.fund_wallet(customer["token"], amount=20000)

        # Checkout of 10000 pesewas (100 fee -> 9900 pending)
        order = self.client.call(
            "POST",
            f"/v1/merchants/{merchant['id']}/checkouts",
            token=merchant["ownerToken"],
            body={"allocations": [{"merchantId": merchant["id"], "kind": "sale", "amount": 10000}]},
            idempotency_key=unique_uuid(),
            expected_status=200,
        )
        ref = order.body["reference"]

        self.client.call(
            "POST",
            f"/v1/checkouts/{ref}/pay/wallet",
            token=customer["token"],
            idempotency_key=unique_uuid(),
            expected_status=200,
        )

        bal_before = self.client.call("GET", f"/v1/merchants/{merchant['id']}/balances", token=merchant["ownerToken"])
        self.assertEqual(bal_before.body.get("pending"), 9900)
        self.assertEqual(bal_before.body.get("available"), 0)

        # Execute ops settlement run with ignoreDelay=True
        settle_res = self.client.call(
            "POST",
            "/v1/ops/settlements/run",
            token=self.ops_token,
            body={"ignoreDelay": True, "merchantId": merchant["id"]},
            expected_status=200,
        )
        self.assertIsInstance(settle_res.body, list)

        # Verify pending funds moved to available
        bal_after = self.client.call("GET", f"/v1/merchants/{merchant['id']}/balances", token=merchant["ownerToken"])
        self.assertEqual(bal_after.body.get("pending"), 0)
        self.assertEqual(bal_after.body.get("available"), 9900)

    def test_tc_t1_e_04_merchant_settlement_history_query(self):
        """TC-T1-E-04: Merchant queries their own settled batches list successfully."""
        merchant = self.create_merchant("History Merchant", "merchant")
        settlements = self.client.call(
            "GET",
            f"/v1/merchants/{merchant['id']}/settlements",
            token=merchant["ownerToken"],
            expected_status=200,
        )
        self.assertIsInstance(settlements.body, list)

    def test_tc_t1_e_05_ops_queue_item_resolution(self):
        """TC-T1-E-05: Query ops queue and resolve pending operational exception items."""
        queue = self.client.call("GET", "/v1/ops/queue", token=self.ops_token, expected_status=200)
        self.assertIsInstance(queue.body, list)
        if len(queue.body) > 0:
            item_id = queue.body[0].get("id")
            resolve_res = self.client.call(
                "POST",
                f"/v1/ops/queue/{item_id}/resolve",
                token=self.ops_token,
                body={"resolution": "Reconciled and cleared during automated test"},
                expected_status=200,
            )
            self.assertEqual(resolve_res.body.get("status"), "resolved")
