"""Domain H: Security — Tier 1 Feature Coverage Test Suite.

Covers:
- TC-T1-H-01: Merchant Webhook Registration and HMAC Secret Provisioning
- TC-T1-H-02: USSD Wallet PIN Setup with BCrypt Hashing
- TC-T1-H-03: Signed Generic USSD Protocol Session Navigation
- TC-T1-H-04: Maker-Checker Dual Operator Review Flow
- TC-T1-H-05: Merchant Scoped API Key Generation and Authenticated Invocation
"""
import json
from tests.e2e.framework.assertions import (
    assert_http_status,
    sign_ussd_body,
)
from tests.e2e.framework.fixtures import local_phone, unique_id, unique_phone, unique_uuid
from tests.e2e.framework.runner import OpaqueTestCase


class TestSecurityFeatures(OpaqueTestCase):
    """Test suite for authentication, USSD PIN hashing, HMAC signatures, and RBAC happy paths."""

    def test_tc_t1_h_01_merchant_webhook_registration(self):
        """TC-T1-H-01: Register merchant webhook endpoint and receive unique webhook secret."""
        merchant = self.create_merchant("Webhook Merchant", "merchant")
        res = self.client.call(
            "POST",
            f"/v1/merchants/{merchant['id']}/webhooks",
            token=merchant["ownerToken"],
            body={"url": "https://webhook.site/elepay-test-endpoint"},
            expected_status=200,
        )
        self.assertIsNotNone(res.body.get("id"))
        self.assertIsNotNone(res.body.get("secret"))
        self.assertEqual(res.body.get("url"), "https://webhook.site/elepay-test-endpoint")

    def test_tc_t1_h_02_ussd_wallet_pin_setup(self):
        """TC-T1-H-02: Configure 4-digit USSD wallet PIN meeting complexity rules."""
        customer = self.enroll_customer("USSD User")
        res = self.client.call(
            "PUT",
            "/v1/wallet/ussd-pin",
            token=customer["token"],
            body={"pin": "7392"},
            expected_status=200,
        )
        self.assertEqual(res.status, 200)

    def test_tc_t1_h_03_signed_ussd_session_navigation(self):
        """TC-T1-H-03: Submit HMAC-signed generic USSD session requests and check balance."""
        customer = self.enroll_customer("Basic Phone User")
        self.client.call(
            "PUT",
            "/v1/wallet/ussd-pin",
            token=customer["token"],
            body={"pin": "4826"},
            expected_status=200,
        )
        self.fund_wallet(customer["token"], amount=10000)

        session_id = f"USSD-SESS-{unique_id()}"
        msisdn = customer["phone"]

        # Step 1: Start session
        body1 = json.dumps({"sessionId": session_id, "msisdn": msisdn, "type": "start", "input": ""})
        sig1 = sign_ussd_body(self.config.ussd_secret, body1)
        res1 = self.client.call(
            "POST",
            "/v1/ussd/generic",
            body=body1,
            headers={"X-Ussd-Signature": sig1},
            expected_status=200,
        )
        self.assertFalse(res1.body.get("end"))
        self.assertIn("1", res1.body.get("message"))  # Menu option 1: Check balance

        # Step 2: Select option 1
        body2 = json.dumps({"sessionId": session_id, "msisdn": msisdn, "type": "continue", "input": "1"})
        sig2 = sign_ussd_body(self.config.ussd_secret, body2)
        res2 = self.client.call(
            "POST",
            "/v1/ussd/generic",
            body=body2,
            headers={"X-Ussd-Signature": sig2},
            expected_status=200,
        )
        self.assertIn("PIN", res2.body.get("message"))

        # Step 3: Enter PIN
        body3 = json.dumps({"sessionId": session_id, "msisdn": msisdn, "type": "continue", "input": "4826"})
        sig3 = sign_ussd_body(self.config.ussd_secret, body3)
        res3 = self.client.call(
            "POST",
            "/v1/ussd/generic",
            body=body3,
            headers={"X-Ussd-Signature": sig3},
            expected_status=200,
        )
        self.assertTrue(res3.body.get("end"))
        self.assertIn("GHS 100.00", res3.body.get("message"))

    def test_tc_t1_h_04_maker_checker_two_operator_flow(self):
        """TC-T1-H-04: Privileged merchant approval succeeds when approved by distinct authorized operator."""
        applicant_token = self.mint_token(f"applicant-{unique_id()}")
        created = self.client.call(
            "POST",
            "/v1/merchants",
            token=applicant_token,
            body={
                "legalName": f"Audited Corp {unique_id()} Ltd",
                "tradingName": f"Audited Corp {unique_id()}",
                "kind": "merchant",
                "contactPhone": "0241234567",
            },
            expected_status=200,
        )
        merchant_id = created.body["id"]

        # Distinct ops operator reviews and approves
        approver_token = self.mint_token(f"checker-ops-{unique_id()}", "ops")
        review_res = self.client.call(
            "POST",
            f"/v1/ops/merchants/{merchant_id}/review",
            token=approver_token,
            body={"decision": "approve", "feeBps": 150},
            expected_status=200,
        )
        self.assertEqual(review_res.body.get("status"), "active")

    def test_tc_t1_h_05_merchant_scoped_api_key_invocation(self):
        """TC-T1-H-05: Generate merchant API key and invoke balances endpoint using X-Elepay-Api-Key header."""
        merchant = self.create_merchant("API Merchant", "merchant")
        key_res = self.client.call(
            "POST",
            f"/v1/merchants/{merchant['id']}/api-keys",
            token=merchant["ownerToken"],
            body={"label": "Production Backend", "scopes": ["collect", "refund", "view"]},
            expected_status=200,
        )
        api_key = key_res.body.get("apiKey")
        self.assertIsNotNone(api_key)

        # Authenticate using X-Elepay-Api-Key header
        bal_res = self.client.call(
            "GET",
            f"/v1/merchants/{merchant['id']}/balances",
            headers={"X-Elepay-Api-Key": api_key},
            expected_status=200,
        )
        self.assertEqual(bal_res.body.get("merchantId"), merchant["id"])
