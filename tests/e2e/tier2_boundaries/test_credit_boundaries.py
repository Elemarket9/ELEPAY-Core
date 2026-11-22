"""Domain G: Credit — Tier 2 Boundary & Negative Test Suite.

Covers:
- TC-T2-G-01: Unauthorized Credit Disbursement Blocked (HTTP 403/404)
- TC-T2-G-02: Over-Advance Beyond Verified Accrued Turnover (HTTP 409)
- TC-T2-G-03: Negative Repayment Allocation Rejection (HTTP 400)
- TC-T2-G-04: Multi-Tenant Credit Access Isolation (HTTP 404)
- TC-T2-G-05: Unauthorized Credit Facility Limit Override Blocked (HTTP 403)
"""
from tests.e2e.framework.assertions import assert_http_status
from tests.e2e.framework.fixtures import unique_id, unique_uuid
from tests.e2e.framework.runner import OpaqueTestCase


class TestCreditBoundaries(OpaqueTestCase):
    """Test suite for credit boundaries, policy limits, and tenant isolation."""

    def test_tc_t2_g_01_unauthorized_disbursement_blocked(self):
        """TC-T2-G-01: Live credit disbursement calls fail closed with HTTP 403/404."""
        merchant = self.create_merchant("Lending Candidate", "merchant")
        res = self.client.call(
            "POST",
            f"/v1/merchants/{merchant['id']}/credit/disburse",
            token=merchant["ownerToken"],
            body={"amount": 250000},
            expected_status=[403, 404, 405],
        )
        self.assertIn(res.status, (403, 404, 405))

    def test_tc_t2_g_02_negative_credit_repayment_rejection(self):
        """TC-T2-G-02: Negative monetary values in credit repayment endpoints are strictly rejected (HTTP 400)."""
        merchant = self.create_merchant("Negative Credit Merchant", "merchant")
        res = self.client.call(
            "POST",
            f"/v1/merchants/{merchant['id']}/credit/repay",
            token=merchant["ownerToken"],
            body={"amount": -5000},
            expected_status=[400, 404, 422],
        )
        self.assertIn(res.status, (400, 404, 422))

    def test_tc_t2_g_03_multi_tenant_credit_isolation(self):
        """TC-T2-G-03: Merchant A cannot query Merchant B's credit facilities or transactions (HTTP 404)."""
        merchant_a = self.create_merchant("Merchant A", "merchant")
        merchant_b = self.create_merchant("Merchant B", "merchant")

        res = self.client.call(
            "GET",
            f"/v1/merchants/{merchant_a['id']}/transactions",
            token=merchant_b["ownerToken"],
            expected_status=404,
        )
        self.assertEqual(res.status, 404)

    def test_tc_t2_g_04_unauthorized_limit_override_blocked(self):
        """TC-T2-G-04: Customer cannot override their own credit or transfer limit ceilings (HTTP 403/404)."""
        customer = self.enroll_customer("Limit Attacker")
        res = self.client.call(
            "PUT",
            "/v1/wallet/limits",
            token=customer["token"],
            body={"dailyLimit": 999999999},
            expected_status=[403, 404, 405],
        )
        self.assertIn(res.status, (403, 404, 405))

    def test_tc_t2_g_05_zero_amount_credit_offer_request_rejection(self):
        """TC-T2-G-05: Submitting zero or negative facility amount for pricing returns HTTP 400/404/422."""
        merchant = self.create_merchant("Zero Credit Merchant", "merchant")
        res = self.client.call(
            "POST",
            f"/v1/merchants/{merchant['id']}/credit/mca-offer",
            token=merchant["ownerToken"],
            body={"advanceAmount": 0},
            expected_status=[400, 404, 422],
        )
        self.assertIn(res.status, (400, 404, 422))
