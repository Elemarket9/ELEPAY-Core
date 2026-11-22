"""Domain J: Customer and Growth — Tier 2 Boundary & Negative Test Suite.

Covers:
- TC-T2-J-01: Malformed and Short Phone Number Rejection (HTTP 400)
- TC-T2-J-02: Non-Ghana Country Code Phone Lookup on Ghana Rail (HTTP 400)
- TC-T2-J-03: Invalid Phone Length Boundary Tests
- TC-T2-J-04: Non-Existent Customer Wallet Lookup (HTTP 404)
- TC-T2-J-05: Wallet Transfer with Same Sender and Recipient Handling
"""
from tests.e2e.framework.assertions import assert_http_status
from tests.e2e.framework.fixtures import local_phone, unique_id, unique_phone, unique_uuid
from tests.e2e.framework.runner import OpaqueTestCase


class TestGrowthBoundaries(OpaqueTestCase):
    """Test suite for phone normalization edges, customer boundaries, and validation rules."""

    def test_tc_t2_j_01_malformed_phone_number_rejected(self):
        """TC-T2-J-01: Submitting short, alphabetic, or malformed phone string returns HTTP 400 Bad Request."""
        customer = self.enroll_customer("Phone Tester")
        for bad_phone in ("12345", "not-a-phone", "024abc1234", "0000000000"):
            res = self.client.call(
                "POST",
                "/v1/wallet/recipients/lookup",
                token=customer["token"],
                body={"rail": "wallet", "phone": bad_phone},
                expected_status=[400, 422],
            )
            self.assertIn(res.status, (400, 422))

    def test_tc_t2_j_02_foreign_country_code_on_ghana_wallet_rail(self):
        """TC-T2-J-02: Looking up a foreign phone number on a Ghana-only rail returns HTTP 400."""
        customer = self.enroll_customer("Foreign Tester")
        foreign_phone = "+12025550199"  # US number
        res = self.client.call(
            "POST",
            "/v1/wallet/recipients/lookup",
            token=customer["token"],
            body={"rail": "wallet", "phone": foreign_phone},
            expected_status=[400, 422],
        )
        self.assertIn(res.status, (400, 422))

    def test_tc_t2_j_03_phone_length_overflow_boundary(self):
        """TC-T2-J-03: Submitting excessively long phone strings (overflow test) is rejected cleanly."""
        customer = self.enroll_customer("Length Tester")
        long_phone = "233" + ("9" * 50)
        res = self.client.call(
            "POST",
            "/v1/wallet/recipients/lookup",
            token=customer["token"],
            body={"rail": "wallet", "phone": long_phone},
            expected_status=[400, 422],
        )
        self.assertIn(res.status, (400, 422))

    def test_tc_t2_j_04_non_existent_customer_wallet_lookup(self):
        """TC-T2-J-04: Non-existent customer token returns HTTP 404 when querying wallet."""
        fake_token = self.mint_token(f"non-existent-{unique_id()}")
        res = self.client.call("GET", "/v1/wallet", token=fake_token, expected_status=404)
        self.assertEqual(res.status, 404)

    def test_tc_t2_j_05_self_transfer_safeguards(self):
        """TC-T2-J-05: Looking up self phone number produces proper status without system panic."""
        customer = self.enroll_customer("Self Tester")
        res = self.client.call(
            "POST",
            "/v1/wallet/recipients/lookup",
            token=customer["token"],
            body={"rail": "wallet", "phone": local_phone(customer["phone"])},
            expected_status=[200, 400, 409],
        )
        self.assertIn(res.status, (200, 400, 409))
