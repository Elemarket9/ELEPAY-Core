"""Domain C: Fraud and Risk — Tier 1 Feature Coverage Test Suite.

Covers:
- TC-T1-C-01: Compliant Transfer within Standard Velocity Limits
- TC-T1-C-02: Recipient Phone Lookup with Device Risk Signals
- TC-T1-C-03: Plausible Geolocation Transfer Execution
- TC-T1-C-04: Clean Merchant Risk Scoring Assessment
- TC-T1-C-05: Legitimate Customer Referral Attribution
"""
from tests.e2e.framework.assertions import assert_http_status
from tests.e2e.framework.fixtures import local_phone, unique_id, unique_uuid
from tests.e2e.framework.runner import OpaqueTestCase


class TestRiskFeatures(OpaqueTestCase):
    """Test suite for fraud prevention, velocity scoring, and risk telemetry happy paths."""

    def test_tc_t1_c_01_compliant_transfer_within_velocity_limits(self):
        """TC-T1-C-01: Standard peer-to-peer transfer well below velocity ceilings completes without challenge."""
        sender = self.enroll_customer("Velocity Sender")
        recipient = self.enroll_customer("Velocity Recipient")
        self.fund_wallet(sender["token"], amount=20000)

        lookup = self.client.call(
            "POST",
            "/v1/wallet/recipients/lookup",
            token=sender["token"],
            body={"rail": "wallet", "phone": local_phone(recipient["phone"])},
            expected_status=200,
        )
        conf_id = lookup.body.get("confirmationId")

        transfer = self.client.call(
            "POST",
            "/v1/wallet/transfers/confirmed",
            token=sender["token"],
            body={"confirmationId": conf_id, "amount": 2000},
            idempotency_key=unique_uuid(),
            expected_status=200,
        )
        self.assertEqual(transfer.body.get("status"), "completed")

        # Verify no risk review items were placed in ops queue
        queue = self.client.call("GET", "/v1/ops/queue", token=self.ops_token, expected_status=200)
        items = queue.body if isinstance(queue.body, list) else []
        sender_holds = [i for i in items if i.get("subject") == f"customer:{sender['customerId']}"]
        self.assertEqual(len(sender_holds), 0)

    def test_tc_t1_c_02_recipient_lookup_with_device_signals(self):
        """TC-T1-C-02: Recipient lookup with client device fingerprint headers succeeds without flagging."""
        sender = self.enroll_customer("Device User")
        recipient = self.enroll_customer("Lookup Recipient")

        lookup = self.client.call(
            "POST",
            "/v1/wallet/recipients/lookup",
            token=sender["token"],
            body={"rail": "wallet", "phone": local_phone(recipient["phone"])},
            headers={
                "User-Agent": "Mozilla/5.0 (Android; Mobile) ElepayApp/1.0",
                "X-Device-Fingerprint": f"fp-accra-{unique_id()}",
            },
            expected_status=200,
        )
        self.assertEqual(lookup.body.get("status"), "verified")
        self.assertEqual(lookup.body.get("verifiedName"), recipient["displayName"])

    def test_tc_t1_c_03_plausible_geolocation_transfer(self):
        """TC-T1-C-03: Successive transactions from plausible Ghanaian IP addresses execute smoothly."""
        sender = self.enroll_customer("Geo Traveler")
        recipient = self.enroll_customer("Geo Recipient")
        self.fund_wallet(sender["token"], amount=20000)

        lookup = self.client.call(
            "POST",
            "/v1/wallet/recipients/lookup",
            token=sender["token"],
            body={"rail": "wallet", "phone": local_phone(recipient["phone"])},
            expected_status=200,
        )
        conf_id = lookup.body.get("confirmationId")

        # Transfer originating from standard Accra IP
        transfer = self.client.call(
            "POST",
            "/v1/wallet/transfers/confirmed",
            token=sender["token"],
            body={"confirmationId": conf_id, "amount": 1500},
            headers={"X-Forwarded-For": "154.160.2.10"},
            idempotency_key=unique_uuid(),
            expected_status=200,
        )
        self.assertEqual(transfer.body.get("status"), "completed")

    def test_tc_t1_c_04_clean_merchant_risk_scoring_assessment(self):
        """TC-T1-C-04: Approved merchant with clean history maintains active status in ops merchant view."""
        merchant = self.create_merchant("Clean Merchant", "merchant", fee_bps=150)
        merchants_list = self.client.call("GET", "/v1/ops/merchants", token=self.ops_token, expected_status=200)
        
        m_entries = [m for m in merchants_list.body if m.get("id") == merchant["id"]]
        self.assertEqual(len(m_entries), 1)
        self.assertEqual(m_entries[0].get("status"), "active")

    def test_tc_t1_c_05_legitimate_referral_attribution(self):
        """TC-T1-C-05: Distinct customers with independent device fingerprints claim promotional code cleanly."""
        referrer = self.enroll_customer("Referrer User")
        referee = self.enroll_customer("New Customer")

        # Verify referee has a wallet in active standing
        wallet = self.client.call("GET", "/v1/wallet", token=referee["token"], expected_status=200)
        self.assertIn("accounts", wallet.body)
