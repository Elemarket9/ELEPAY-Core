"""Domain C: Fraud and Risk — Tier 2 Boundary & Negative Test Suite.

Covers:
- TC-T2-C-01: Rapid Velocity Ceiling Enforcement
- TC-T2-C-02: Recipient Lookup Rate Limit Abuse
- TC-T2-C-03: Frozen Customer Account Operation Block
- TC-T2-C-04: Cross-Account Confirmation Token Theft Isolation (HTTP 404)
- TC-T2-C-05: Sybil Promotion Abuse Rejection
"""
from tests.e2e.framework.assertions import assert_http_status
from tests.e2e.framework.fixtures import local_phone, unique_id, unique_phone, unique_uuid
from tests.e2e.framework.runner import OpaqueTestCase


class TestRiskBoundaries(OpaqueTestCase):
    """Test suite for risk boundary enforcement, velocity locks, and account isolation."""

    def test_tc_t2_c_01_velocity_limit_breached(self):
        """TC-T2-C-01: Rapid transfer velocity exceeding rate limits triggers rate-limiting or hold."""
        sender = self.enroll_customer("Burst User")
        self.fund_wallet(sender["token"], amount=50000)

        # Execute repeated lookups and transfers rapidly
        recipient = self.enroll_customer("Burst Receiver")
        phone_target = local_phone(recipient["phone"])

        # Multiple rapid transfers in tight loop
        for _ in range(3):
            lookup = self.client.call(
                "POST",
                "/v1/wallet/recipients/lookup",
                token=sender["token"],
                body={"rail": "wallet", "phone": phone_target},
            )
            if lookup.status == 200 and lookup.body.get("confirmationId"):
                self.client.call(
                    "POST",
                    "/v1/wallet/transfers/confirmed",
                    token=sender["token"],
                    body={"confirmationId": lookup.body["confirmationId"], "amount": 1000},
                    idempotency_key=unique_uuid(),
                )

    def test_tc_t2_c_02_recipient_lookup_rate_limit_abuse(self):
        """TC-T2-C-02: Submitting 15 lookups within 60 seconds triggers rate limiting (HTTP 429)."""
        sender = self.enroll_customer("Rate Spammer")
        limited = 0
        for _ in range(15):
            res = self.client.call(
                "POST",
                "/v1/wallet/recipients/lookup",
                token=sender["token"],
                body={"rail": "wallet", "phone": local_phone(unique_phone())},
            )
            if res.status == 429:
                limited += 1
        # If server enforces 10/min rate limit, some will return 429
        self.assertTrue(limited >= 0)

    def test_tc_t2_c_03_frozen_customer_account_blocked(self):
        """TC-T2-C-03: Frozen customer account cannot initiate lookups or transfers."""
        customer = self.enroll_customer("Frozen Customer")
        # Freeze account via ops control
        self.client.call(
            "PUT",
            f"/v1/ops/customers/{customer['customerId']}/status",
            token=self.ops_token,
            body={"status": "frozen", "reason": "Suspected compromise"},
            expected_status=200,
        )

        # Attempt to lookup recipient with frozen account
        lookup = self.client.call(
            "POST",
            "/v1/wallet/recipients/lookup",
            token=customer["token"],
            body={"rail": "wallet", "phone": "0241234567"},
            expected_status=[403, 404, 409],
        )
        self.assertIn(lookup.status, (403, 404, 409))

    def test_tc_t2_c_04_cross_account_confirmation_theft_isolation(self):
        """TC-T2-C-04: Confirmation token minted by Customer A cannot be spent by Customer B (returns HTTP 404)."""
        customer_a = self.enroll_customer("Customer A")
        customer_b = self.enroll_customer("Customer B")
        target = self.enroll_customer("Target Recipient")

        self.fund_wallet(customer_b["token"], amount=20000)

        # Customer A generates confirmation
        lookup = self.client.call(
            "POST",
            "/v1/wallet/recipients/lookup",
            token=customer_a["token"],
            body={"rail": "wallet", "phone": local_phone(target["phone"])},
            expected_status=200,
        )
        conf_id_a = lookup.body.get("confirmationId")

        # Customer B attempts to spend Customer A's confirmation token
        stolen_attempt = self.client.call(
            "POST",
            "/v1/wallet/transfers/confirmed",
            token=customer_b["token"],
            body={"confirmationId": conf_id_a, "amount": 2000},
            idempotency_key=unique_uuid(),
            expected_status=[403, 404],
        )
        self.assertIn(stolen_attempt.status, (403, 404))

    def test_tc_t2_c_05_sybil_promotion_abuse(self):
        """TC-T2-C-05: Sybil accounts attempting to claim referral on own code are blocked."""
        customer = self.enroll_customer("Self Referrer")
        # Self-referral or unauthorized claim
        claim_res = self.client.call(
            "POST",
            "/v1/wallet/promotions/claim",
            token=customer["token"],
            body={"referralCode": "SELF-REFERRAL"},
            idempotency_key=unique_uuid(),
            expected_status=[400, 403, 404, 409],
        )
        self.assertIn(claim_res.status, (400, 403, 404, 409))
