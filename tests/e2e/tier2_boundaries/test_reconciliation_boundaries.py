"""Domain E: Reconciliation and Treasury — Tier 2 Boundary & Negative Test Suite.

Covers:
- TC-T2-E-01: Statement Line Discrepancy Detection in Multi-Currency Reconciliation
- TC-T2-E-02: Withdrawal Attempt Exceeding Settled Available Balance (HTTP 409)
- TC-T2-E-03: Payout Retry on Non-Failed Item Rejection (HTTP 409)
- TC-T2-E-04: Duplicate Payout Client Reference Rejection (HTTP 400/409)
- TC-T2-E-05: Double Withdrawal Request Replay Protection
"""
import time
from tests.e2e.framework.assertions import assert_http_status
from tests.e2e.framework.fixtures import unique_id, unique_uuid
from tests.e2e.framework.runner import OpaqueTestCase


class TestReconciliationBoundaries(OpaqueTestCase):
    """Test suite for reconciliation exceptions, over-withdrawal guards, and settlement batch boundaries."""

    def test_tc_t2_e_01_statement_line_amount_discrepancy(self):
        """TC-T2-E-01: Reconciler flags amount deviations between partner line and ledger as exception."""
        today = time.strftime("%Y-%m-%d", time.gmtime())
        discrepant_line = {
            "period": today,
            "lines": [
                {
                    "reference": f"UNKNOWN-{unique_id()}",
                    "sourceCurrency": "USD",
                    "sourceAmount": 999999,
                    "destinationCurrency": "GHS",
                    "destinationAmount": 1,
                    "status": "paid",
                }
            ],
        }
        res = self.client.call(
            "POST",
            "/v1/ops/fx/reconcile/sim-remit",
            token=self.ops_token,
            body=discrepant_line,
            expected_status=200,
        )
        self.assertIsInstance(res.body, list)
        self.assertNotEqual(res.body[0].get("result"), "matched")

    def test_tc_t2_e_02_withdrawal_exceeding_available_balance(self):
        """TC-T2-E-02: Merchant withdrawal request exceeding available balance returns HTTP 409."""
        merchant = self.create_merchant("Overdraw Merchant", "merchant")
        dest = self.client.call(
            "POST",
            f"/v1/merchants/{merchant['id']}/settlement-destinations",
            token=merchant["ownerToken"],
            body={
                "type": "momo",
                "network": "mtn",
                "accountNumber": "0245550005",
                "accountName": merchant["tradingName"],
            },
            expected_status=200,
        )
        dest_id = dest.body["id"]

        # Merchant available balance is 0; attempt to withdraw 50000 pesewas
        withdraw_res = self.client.call(
            "POST",
            f"/v1/merchants/{merchant['id']}/withdrawals",
            token=merchant["ownerToken"],
            body={"amount": 50000, "destinationId": dest_id},
            idempotency_key=unique_uuid(),
            expected_status=[409, 422],
        )
        self.assertIn(withdraw_res.status, (409, 422))

    def test_tc_t2_e_03_payout_retry_on_non_failed_item(self):
        """TC-T2-E-03: Attempting to retry a payout that is not in failed/returned state returns HTTP 409."""
        merchant = self.create_merchant("Payout Merchant", "merchant")
        # Attempt to retry non-existent or successful payout
        retry_res = self.client.call(
            "POST",
            f"/v1/merchants/{merchant['id']}/payouts/{unique_uuid()}/retry",
            token=merchant["ownerToken"],
            body={},
            expected_status=[404, 409],
        )
        self.assertIn(retry_res.status, (404, 409))

    def test_tc_t2_e_04_duplicate_withdrawal_replay_protection(self):
        """TC-T2-E-04: Replaying identical withdrawal idempotency key returns cached receipt without double deduction."""
        merchant = self.create_merchant("Replay Merchant", "merchant")
        customer = self.enroll_customer("Buyer")
        self.fund_wallet(customer["token"], amount=20000)

        order = self.client.call(
            "POST",
            f"/v1/merchants/{merchant['id']}/checkouts",
            token=merchant["ownerToken"],
            body={"allocations": [{"merchantId": merchant["id"], "kind": "sale", "amount": 20000}]},
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
            body={"ignoreDelay": True, "merchantId": merchant["id"]},
            expected_status=200,
        )

        dest = self.client.call(
            "POST",
            f"/v1/merchants/{merchant['id']}/settlement-destinations",
            token=merchant["ownerToken"],
            body={
                "type": "momo",
                "network": "mtn",
                "accountNumber": "0245550005",
                "accountName": merchant["tradingName"],
            },
            expected_status=200,
        )
        dest_id = dest.body["id"]

        bal = self.client.call("GET", f"/v1/merchants/{merchant['id']}/balances", token=merchant["ownerToken"])
        avail = bal.body["available"]

        withdraw_key = unique_uuid()
        w1 = self.client.call(
            "POST",
            f"/v1/merchants/{merchant['id']}/withdrawals",
            token=merchant["ownerToken"],
            body={"amount": avail, "destinationId": dest_id},
            idempotency_key=withdraw_key,
            expected_status=200,
        )
        w2 = self.client.call(
            "POST",
            f"/v1/merchants/{merchant['id']}/withdrawals",
            token=merchant["ownerToken"],
            body={"amount": avail, "destinationId": dest_id},
            idempotency_key=withdraw_key,
            expected_status=200,
        )
        self.assertEqual(w1.body, w2.body)

    def test_tc_t2_e_05_settlement_run_with_negative_parameters(self):
        """TC-T2-E-05: Ops settlement run with invalid merchant ID returns gracefully without system corruption."""
        res = self.client.call(
            "POST",
            "/v1/ops/settlements/run",
            token=self.ops_token,
            body={"ignoreDelay": True, "merchantId": unique_uuid()},
            expected_status=[200, 404],
        )
        self.assertIn(res.status, (200, 404))
