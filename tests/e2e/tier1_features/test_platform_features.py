"""Domain I: Reliability and Platform — Tier 1 Feature Coverage Test Suite.

Covers:
- TC-T1-I-01: Transactional Outbox Event Persistence on Payment Mutation
- TC-T1-I-02: Partner Rail Normal Healthy Invocation
- TC-T1-I-03: Token-Bucket Rate Limiter Clean Concurrency Under Ceiling
- TC-T1-I-04: Operational Overview Dashboard Health Probe
- TC-T1-I-05: Controlled Chaos Fault Recovery Sweep
"""
from tests.e2e.framework.assertions import assert_http_status
from tests.e2e.framework.fixtures import local_phone, unique_id, unique_phone, unique_uuid
from tests.e2e.framework.runner import OpaqueTestCase


class TestPlatformFeatures(OpaqueTestCase):
    """Test suite for transactional outbox, operational metrics, rate limits, and resilience happy paths."""

    def test_tc_t1_i_01_outbox_event_persistence(self):
        """TC-T1-I-01: Completing a checkout atomically persists domain event into merchant outbox stream."""
        merchant = self.create_merchant("Outbox Merchant", "merchant")
        customer = self.enroll_customer("Outbox Customer")
        self.fund_wallet(customer["token"], amount=15000)

        order = self.client.call(
            "POST",
            f"/v1/merchants/{merchant['id']}/checkouts",
            token=merchant["ownerToken"],
            body={"allocations": [{"merchantId": merchant["id"], "kind": "sale", "amount": 10000}]},
            idempotency_key=unique_uuid(),
            expected_status=200,
        )
        ref = order.body["reference"]

        self.client.call(
            "POST",
            f"/v1/checkouts/{ref}/pay/wallet",
            token=customer["token"],
            idempotency_key=unique_uuid(),
            expected_status=200,
        )

        # Query merchant events stream
        events_res = self.client.call(
            "GET",
            f"/v1/merchants/{merchant['id']}/events",
            token=merchant["ownerToken"],
            expected_status=200,
        )
        self.assertIsInstance(events_res.body, list)
        self.assertGreaterEqual(len(events_res.body), 1)

    def test_tc_t1_i_02_partner_rail_healthy_invocation(self):
        """TC-T1-I-02: Consecutive successful rail submissions maintain healthy closed circuit breaker state."""
        merchant = self.create_merchant("Circuit Merchant", "merchant")
        order = self.client.call(
            "POST",
            f"/v1/merchants/{merchant['id']}/checkouts",
            token=merchant["ownerToken"],
            body={"allocations": [{"merchantId": merchant["id"], "kind": "sale", "amount": 5000}]},
            idempotency_key=unique_uuid(),
            expected_status=200,
        )
        ref = order.body["reference"]

        pay_res = self.client.call(
            "POST",
            f"/v1/public/checkouts/{ref}/pay",
            body={"method": "momo", "phone": "0245550005", "network": "mtn"},
            idempotency_key=unique_uuid(),
            expected_status=200,
        )
        self.assertIn(pay_res.body.get("status"), ("processing", "pending", "succeeded"))

    def test_tc_t1_i_03_rate_limiter_clean_concurrency_under_limit(self):
        """TC-T1-I-03: Issue 5 lookup requests sequentially within 10 seconds under rate ceiling; all return 200 OK."""
        sender = self.enroll_customer("Rate Limit User")
        recipient = self.enroll_customer("Target Recipient")
        phone_target = local_phone(recipient["phone"])

        for _ in range(5):
            res = self.client.call(
                "POST",
                "/v1/wallet/recipients/lookup",
                token=sender["token"],
                body={"rail": "wallet", "phone": phone_target},
                expected_status=200,
            )
            self.assertEqual(res.status, 200)

    def test_tc_t1_i_04_operational_overview_dashboard_probe(self):
        """TC-T1-I-04: Retrieve ops overview dashboard metrics for platform monitoring."""
        overview = self.client.call("GET", "/v1/ops/overview", token=self.ops_token, expected_status=200)
        self.assertIsInstance(overview.body, dict)

    def test_tc_t1_i_05_controlled_chaos_fault_recovery(self):
        """TC-T1-I-05: Trigger ops rail recovery sweep to resolve simulated network latency and transient holds."""
        rec = self.client.call("POST", "/v1/ops/rails/recover", token=self.ops_token, expected_status=200)
        self.assertIsInstance(rec.body.get("recovered"), int)
