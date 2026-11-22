"""Domain D: Compliance Controls — Tier 2 Boundary & Negative Test Suite.

Covers:
- TC-T2-D-01: Re-Use of Already Consumed Confirmation Token (HTTP 409)
- TC-T2-D-02: Stale Recipient Phone Reassignment Invalidation (HTTP 409)
- TC-T2-D-03: Unknown Recipient Phone Lookup Handling (not_found)
- TC-T2-D-04: Non-Ops User Forbidden from Ops Compliance Endpoints (HTTP 403)
- TC-T2-D-05: Missing Authorization Header on Protected Resource (HTTP 401)
"""
from tests.e2e.framework.assertions import assert_http_status
from tests.e2e.framework.fixtures import local_phone, unique_id, unique_phone, unique_uuid
from tests.e2e.framework.runner import OpaqueTestCase


class TestComplianceBoundaries(OpaqueTestCase):
    """Test suite for compliance boundaries, single-use token constraints, and RBAC security boundaries."""

    def test_tc_t2_d_01_confirmation_token_single_use_enforcement(self):
        """TC-T2-D-01: A confirmation token can only be consumed once; subsequent executions return HTTP 409."""
        sender = self.enroll_customer("Single Use Sender")
        recipient = self.enroll_customer("Recipient")
        self.fund_wallet(sender["token"], amount=20000)

        lookup = self.client.call(
            "POST",
            "/v1/wallet/recipients/lookup",
            token=sender["token"],
            body={"rail": "wallet", "phone": local_phone(recipient["phone"])},
            expected_status=200,
        )
        conf_id = lookup.body.get("confirmationId")

        # First confirmed transfer succeeds
        first_transfer = self.client.call(
            "POST",
            "/v1/wallet/transfers/confirmed",
            token=sender["token"],
            body={"confirmationId": conf_id, "amount": 4000},
            idempotency_key=unique_uuid(),
            expected_status=200,
        )
        self.assertEqual(first_transfer.body.get("status"), "completed")

        # Second transfer attempting to use same confirmation token with fresh idempotency key returns 409
        second_attempt = self.client.call(
            "POST",
            "/v1/wallet/transfers/confirmed",
            token=sender["token"],
            body={"confirmationId": conf_id, "amount": 4000},
            idempotency_key=unique_uuid(),
            expected_status=409,
        )
        self.assertEqual(second_attempt.status, 409)

    def test_tc_t2_d_02_stale_recipient_reassignment_invalidation(self):
        """TC-T2-D-02: Updating recipient phone number after lookup invalidates confirmation token (HTTP 409)."""
        sender = self.enroll_customer("Sender")
        recipient = self.enroll_customer("Recipient To Change")
        self.fund_wallet(sender["token"], amount=10000)

        lookup = self.client.call(
            "POST",
            "/v1/wallet/recipients/lookup",
            token=sender["token"],
            body={"rail": "wallet", "phone": local_phone(recipient["phone"])},
            expected_status=200,
        )
        conf_id = lookup.body.get("confirmationId")

        # Recipient changes phone number via ops
        new_phone = unique_phone()
        self.client.call(
            "PUT",
            f"/v1/ops/customers/{recipient['customerId']}/phone",
            token=self.ops_token,
            body={"phone": new_phone, "reason": "Reassigned"},
            expected_status=200,
        )

        # Attempt to execute transfer using old confirmation token
        stale_transfer = self.client.call(
            "POST",
            "/v1/wallet/transfers/confirmed",
            token=sender["token"],
            body={"confirmationId": conf_id, "amount": 1000},
            idempotency_key=unique_uuid(),
            expected_status=409,
        )
        self.assertEqual(stale_transfer.status, 409)

    def test_tc_t2_d_03_unknown_recipient_lookup(self):
        """TC-T2-D-03: Looking up unregistered phone number returns status: not_found and null confirmation."""
        sender = self.enroll_customer("Searcher")
        unregistered = unique_phone()
        lookup = self.client.call(
            "POST",
            "/v1/wallet/recipients/lookup",
            token=sender["token"],
            body={"rail": "wallet", "phone": local_phone(unregistered)},
            expected_status=200,
        )
        self.assertEqual(lookup.body.get("status"), "not_found")
        self.assertIsNone(lookup.body.get("verifiedName"))
        self.assertIsNone(lookup.body.get("confirmationId"))

    def test_tc_t2_d_04_non_ops_forbidden_from_compliance_endpoints(self):
        """TC-T2-D-04: Standard customer or merchant tokens cannot invoke ops compliance endpoints (HTTP 403)."""
        customer = self.enroll_customer("Unauthorized Actor")
        # Attempt to access ops customer admin endpoint
        res = self.client.call(
            "POST",
            "/v1/ops/customers",
            token=customer["token"],
            body={"subject": "fake", "phone": unique_phone(), "displayName": "Fake"},
            expected_status=403,
        )
        self.assertEqual(res.status, 403)

    def test_tc_t2_d_05_missing_authorization_header(self):
        """TC-T2-D-05: Protected endpoint returns HTTP 401 when Authorization header is absent."""
        res = self.client.call("GET", "/v1/wallet", expected_status=401)
        self.assertEqual(res.status, 401)
