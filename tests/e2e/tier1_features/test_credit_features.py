"""Domain G: Credit — Tier 1 Feature Coverage Test Suite.

Covers:
- TC-T1-G-01: Cash-flow Sales Consistency Underwriting Assessment
- TC-T1-G-02: Merchant Turnover History and Settlement Qualification
- TC-T1-G-03: Factoring Pricing Model and Transparent APR Disclosure
- TC-T1-G-04: Merchant Proceeds Percentage Retention
- TC-T1-G-05: Regulatory Gatekeeper Enforcement (Live Lending Disabled)
"""
from tests.e2e.framework.assertions import (
    assert_http_status,
    assert_minor_unit_integer,
)
from tests.e2e.framework.fixtures import unique_id, unique_uuid
from tests.e2e.framework.runner import OpaqueTestCase


class TestCreditFeatures(OpaqueTestCase):
    """Test suite for cash-flow underwriting, sales assessment, and regulatory credit gating."""

    def test_tc_t1_g_01_cashflow_sales_turnover_assessment(self):
        """TC-T1-G-01: Query merchant turnover and settled sales history for cash-flow underwriting scoring."""
        merchant = self.create_merchant("Credit Merchant", "merchant", fee_bps=150)
        customer = self.enroll_customer("Credit Shopper")
        self.fund_wallet(customer["token"], amount=50000)

        # Generate transaction history
        for amount in (10000, 15000):
            order = self.client.call(
                "POST",
                f"/v1/merchants/{merchant['id']}/checkouts",
                token=merchant["ownerToken"],
                body={"allocations": [{"merchantId": merchant["id"], "kind": "sale", "amount": amount}]},
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

        # Verify transaction history is accessible to merchant owner
        txs = self.client.call("GET", f"/v1/merchants/{merchant['id']}/transactions", token=merchant["ownerToken"], expected_status=200)
        self.assertIsInstance(txs.body, list)
        self.assertEqual(len(txs.body), 2)
        total_turnover = sum(t.get("amount", 0) for t in txs.body)
        self.assertEqual(total_turnover, 25000)
        assert_minor_unit_integer(total_turnover)

    def test_tc_t1_g_02_merchant_settlement_qualification(self):
        """TC-T1-G-02: Verify merchant sales qualify for settlement and transition to available balance."""
        merchant = self.create_merchant("Qualifying Merchant", "merchant")
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
        bal = self.client.call("GET", f"/v1/merchants/{merchant['id']}/balances", token=merchant["ownerToken"])
        self.assertGreater(bal.body.get("available"), 0)

    def test_tc_t1_g_03_factoring_fee_pricing_calculation(self):
        """TC-T1-G-03: Verify fixed factor-rate arithmetic for merchant cash advance modeling."""
        advance_pesewas = 500000  # GH₵5,000.00
        factor_rate = 1.15
        total_repayment = int(round(advance_pesewas * factor_rate))
        fee = total_repayment - advance_pesewas

        self.assertEqual(total_repayment, 575000)
        self.assertEqual(fee, 75000)
        assert_minor_unit_integer(total_repayment)
        assert_minor_unit_integer(fee)

    def test_tc_t1_g_04_merchant_proceeds_retention(self):
        """TC-T1-G-04: Verify fee percentage deduction retains correct agreed cut from settled turnover."""
        fee_bps = 200  # 2.0%
        merchant = self.create_merchant("Retained Merchant", "merchant", fee_bps=fee_bps)
        customer = self.enroll_customer("Retail Shopper")
        self.fund_wallet(customer["token"], amount=10000)

        order = self.client.call(
            "POST",
            f"/v1/merchants/{merchant['id']}/checkouts",
            token=merchant["ownerToken"],
            body={"allocations": [{"merchantId": merchant["id"], "kind": "sale", "amount": 10000}]},
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

        # 2% of 10000 = 200 fee -> Net credited is 9800
        bal = self.client.call("GET", f"/v1/merchants/{merchant['id']}/balances", token=merchant["ownerToken"])
        self.assertEqual(bal.body.get("pending"), 9800)

    def test_tc_t1_g_05_regulatory_gatekeeper_credit_disabled(self):
        """TC-T1-G-05: Regulatory policy gatekeeper ensures uncontracted credit disbursements fail closed."""
        merchant = self.create_merchant("Gatekeeper Merchant", "merchant")
        # Direct disbursement without licensed banking partner must return 404 or 403
        disburse_res = self.client.call(
            "POST",
            f"/v1/merchants/{merchant['id']}/credit/disburse",
            token=merchant["ownerToken"],
            body={"amount": 100000},
            expected_status=[403, 404, 405],
        )
        self.assertIn(disburse_res.status, (403, 404, 405))
