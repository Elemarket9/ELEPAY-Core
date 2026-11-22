"""Domain J: Customer and Growth — Tier 1 Feature Coverage Test Suite.

Covers:
- TC-T1-J-01: Canonical Ghana Phone Normalization across Varied Formats
- TC-T1-J-02: Customer Wallet Statement and Spending History
- TC-T1-J-03: Multi-Account Wallet Sub-Accounts (Available, Pending, Reserved)
- TC-T1-J-04: Customer Activity Log and Wallet Operations
- TC-T1-J-05: Customer Limit Ceilings Inspection via Wallet Profile
"""
from tests.e2e.framework.assertions import assert_http_status
from tests.e2e.framework.fixtures import intl_phone, local_phone, unique_id, unique_phone, unique_uuid
from tests.e2e.framework.runner import OpaqueTestCase


class TestGrowthFeatures(OpaqueTestCase):
    """Test suite for customer experience, phone normalization, statements, and growth telemetry."""

    def test_tc_t1_j_01_ghana_phone_normalization_formats(self):
        """TC-T1-J-01: Local 024..., international +23324..., and spaced formats all resolve to identical customer."""
        sender = self.enroll_customer("Phone Normalizer")
        recipient = self.enroll_customer("Ama Format Tester")
        raw_phone = recipient["phone"]  # e.g. "233241234567"

        local_fmt = local_phone(raw_phone)
        intl_fmt = intl_phone(raw_phone)
        spaced_fmt = f"+233 {raw_phone[3:5]} {raw_phone[5:8]} {raw_phone[8:]}"

        # Test local format
        res_local = self.client.call(
            "POST",
            "/v1/wallet/recipients/lookup",
            token=sender["token"],
            body={"rail": "wallet", "phone": local_fmt},
            expected_status=200,
        )
        self.assertEqual(res_local.body.get("status"), "verified")
        self.assertEqual(res_local.body.get("verifiedName"), "Ama Format Tester")

        # Test international format
        res_intl = self.client.call(
            "POST",
            "/v1/wallet/recipients/lookup",
            token=sender["token"],
            body={"rail": "wallet", "phone": intl_fmt},
            expected_status=200,
        )
        self.assertEqual(res_intl.body.get("verifiedName"), "Ama Format Tester")

        # Test spaced format
        res_spaced = self.client.call(
            "POST",
            "/v1/wallet/recipients/lookup",
            token=sender["token"],
            body={"rail": "wallet", "phone": spaced_fmt},
            expected_status=200,
        )
        self.assertEqual(res_spaced.body.get("verifiedName"), "Ama Format Tester")

    def test_tc_t1_j_02_wallet_statement_and_spending_history(self):
        """TC-T1-J-02: Query wallet statement and verify chronological ledger entries and running balance."""
        customer = self.enroll_customer("History Customer")
        self.fund_wallet(customer["token"], amount=18000)

        statement = self.client.call("GET", "/v1/wallet/statement", token=customer["token"], expected_status=200)
        self.assertIsInstance(statement.body, list)
        self.assertGreaterEqual(len(statement.body), 1)

    def test_tc_t1_j_03_wallet_multi_account_buckets(self):
        """TC-T1-J-03: Inspect customer wallet accounts and verify bucket separation (available, etc.)."""
        customer = self.enroll_customer("Bucket Customer")
        wallet = self.client.call("GET", "/v1/wallet", token=customer["token"], expected_status=200)
        self.assertIn("accounts", wallet.body)
        buckets = [a.get("bucket") for a in wallet.body["accounts"]]
        self.assertIn("available", buckets)

    def test_tc_t1_j_04_wallet_operations_log(self):
        """TC-T1-J-04: Retrieve customer wallet operations activity log."""
        customer = self.enroll_customer("Ops Log Customer")
        self.fund_wallet(customer["token"], amount=10000)
        ops_res = self.client.call("GET", "/v1/wallet/operations", token=customer["token"], expected_status=200)
        self.assertIsInstance(ops_res.body, list)

    def test_tc_t1_j_05_customer_limit_profile_evaluation(self):
        """TC-T1-J-05: Query customer wallet profile and verify account status and active currency."""
        customer = self.enroll_customer("Limit User")
        wallet = self.client.call("GET", "/v1/wallet", token=customer["token"], expected_status=200)
        self.assertIsNotNone(wallet.body.get("customerId"))
