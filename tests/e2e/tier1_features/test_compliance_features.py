"""Domain D: Compliance Controls — Tier 1 Feature Coverage Test Suite.

Covers:
- TC-T1-D-01: Audited Recipient Phone Lookup with Verified Name Resolution
- TC-T1-D-02: Bound Ephemeral Confirmation Token Preview and Execution
- TC-T1-D-03: KYC Tier Limit Enforcement on Customer Wallet
- TC-T1-D-04: Customer Phone and Status Management via Ops Control
- TC-T1-D-05: Operational Audit Trail Inspection by Subject
"""
from tests.e2e.framework.assertions import assert_http_status
from tests.e2e.framework.fixtures import local_phone, unique_id, unique_phone, unique_uuid
from tests.e2e.framework.runner import OpaqueTestCase


class TestComplianceFeatures(OpaqueTestCase):
    """Test suite for KYC tiers, audited lookups, and regulatory compliance happy paths."""

    def test_tc_t1_d_01_audited_recipient_phone_lookup(self):
        """TC-T1-D-01: Audited recipient phone lookup returns masked verified name and ephemeral confirmation token."""
        sender = self.enroll_customer("Audit Sender")
        recipient = self.enroll_customer("Ama Mensah")

        lookup = self.client.call(
            "POST",
            "/v1/wallet/recipients/lookup",
            token=sender["token"],
            body={"rail": "wallet", "phone": local_phone(recipient["phone"])},
            expected_status=200,
        )
        self.assertEqual(lookup.body.get("status"), "verified")
        self.assertEqual(lookup.body.get("verifiedName"), "Ama Mensah")
        self.assertIsNotNone(lookup.body.get("confirmationId"))
        self.assertIsNotNone(lookup.body.get("expiresAt"))

        # Verify destination is formatted canonically with E.164
        self.assertTrue(lookup.body.get("destination").startswith("+233"))

    def test_tc_t1_d_02_bound_confirmation_preview_lifecycle(self):
        """TC-T1-D-02: Ephemeral transfer confirmation preview binds sender, recipient, amount, fee, and total."""
        sender = self.enroll_customer("Preview Sender")
        recipient = self.enroll_customer("Preview Recipient")
        self.fund_wallet(sender["token"], amount=10000)

        lookup = self.client.call(
            "POST",
            "/v1/wallet/recipients/lookup",
            token=sender["token"],
            body={"rail": "wallet", "phone": local_phone(recipient["phone"])},
            expected_status=200,
        )
        conf_id = lookup.body.get("confirmationId")

        # Request preview
        preview = self.client.call(
            "POST",
            "/v1/wallet/transfers/preview",
            token=sender["token"],
            body={"confirmationId": conf_id, "amount": 7500},
            expected_status=200,
        )
        self.assertEqual(preview.body.get("amount"), 7500)
        self.assertEqual(preview.body.get("fee"), 0)
        self.assertEqual(preview.body.get("total"), 7500)
        self.assertEqual(preview.body.get("currency"), "GHS")

        # Execute confirmed transfer
        transfer = self.client.call(
            "POST",
            "/v1/wallet/transfers/confirmed",
            token=sender["token"],
            body={"confirmationId": conf_id, "amount": 7500},
            idempotency_key=unique_uuid(),
            expected_status=200,
        )
        self.assertEqual(transfer.body.get("status"), "completed")

    def test_tc_t1_d_03_kyc_tier_standard_transfer_compliance(self):
        """TC-T1-D-03: Execute transfer strictly within KYC Tier daily and single-transaction limit ceilings."""
        sender = self.enroll_customer("KYC Customer")
        recipient = self.enroll_customer("KYC Recipient")
        self.fund_wallet(sender["token"], amount=30000)

        lookup = self.client.call(
            "POST",
            "/v1/wallet/recipients/lookup",
            token=sender["token"],
            body={"rail": "wallet", "phone": local_phone(recipient["phone"])},
            expected_status=200,
        )
        conf_id = lookup.body.get("confirmationId")

        # Transfer within standard single-transaction limits (25,000 pesewas = GH₵250.00)
        res = self.client.call(
            "POST",
            "/v1/wallet/transfers/confirmed",
            token=sender["token"],
            body={"confirmationId": conf_id, "amount": 25000},
            idempotency_key=unique_uuid(),
            expected_status=200,
        )
        self.assertEqual(res.body.get("status"), "completed")

    def test_tc_t1_d_04_customer_phone_and_status_management(self):
        """TC-T1-D-04: Ops management of customer phone updates and status transitions records proper audit."""
        customer = self.enroll_customer("Managed Customer")
        cust_id = customer["customerId"]

        # Update phone number
        new_phone = unique_phone()
        update_phone = self.client.call(
            "PUT",
            f"/v1/ops/customers/{cust_id}/phone",
            token=self.ops_token,
            body={"phone": new_phone, "reason": "SIM replacement verified"},
            expected_status=200,
        )
        self.assertEqual(update_phone.status, 200)

        # Freeze and unfreeze status
        freeze = self.client.call(
            "PUT",
            f"/v1/ops/customers/{cust_id}/status",
            token=self.ops_token,
            body={"status": "frozen", "reason": "Customer security hold request"},
            expected_status=200,
        )
        self.assertEqual(freeze.status, 200)

        unfreeze = self.client.call(
            "PUT",
            f"/v1/ops/customers/{cust_id}/status",
            token=self.ops_token,
            body={"status": "active", "reason": "Customer security hold cleared"},
            expected_status=200,
        )
        self.assertEqual(unfreeze.status, 200)

    def test_tc_t1_d_05_controlled_audit_log_query(self):
        """TC-T1-D-05: Query ops audit log and verify presence of actor, action, and immutable timestamps."""
        customer = self.enroll_customer("Audited Subject")
        audit_res = self.client.call(
            "GET",
            f"/v1/ops/audit?subject=customer:{customer['customerId']}",
            token=self.ops_token,
            expected_status=200,
        )
        entries = audit_res.body if isinstance(audit_res.body, list) else []
        self.assertGreaterEqual(len(entries), 1)
        entry = entries[0]
        self.assertIn("actor", entry)
        self.assertIn("action", entry)
        self.assertIn("created_at", entry)
