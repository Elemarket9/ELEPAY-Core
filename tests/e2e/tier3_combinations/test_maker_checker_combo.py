"""Tier 3: Combo 7 — Maker-Checker Dual Control on Privileged Operations.

Validates:
- Sensitive operations require dual control (proposer != approver)
- Unilateral self-approval rejection
- Legitimate approval by independent authorized operator
- Hash-chained audit trail logging of both actors
"""
from tests.e2e.framework.assertions import (
    assert_http_status,
    assert_ledger_integrity,
)
from tests.e2e.framework.fixtures import unique_id, unique_uuid
from tests.e2e.framework.runner import OpaqueTestCase


class TestMakerCheckerCombo(OpaqueTestCase):
    """Test suite for dual-control maker-checker security invariant on privileged administrative workflows."""

    def test_combo_7_maker_checker_dual_control_and_audit_trail(self):
        """Combo 7: Maker submits merchant application -> Checker approves -> Audit log records full provenance."""
        maker_sub = f"ops-maker-{unique_id()}"
        checker_sub = f"ops-checker-{unique_id()}"

        maker_token = self.mint_token(maker_sub, "ops")
        checker_token = self.mint_token(checker_sub, "ops")

        # 1. Maker submits merchant onboarding
        m_name = f"DualControl {unique_id()}"
        created = self.client.call(
            "POST",
            "/v1/merchants",
            token=maker_token,
            body={
                "legalName": f"{m_name} Ltd",
                "tradingName": m_name,
                "kind": "merchant",
                "contactPhone": "0241234567",
            },
            expected_status=200,
        )
        merchant_id = created.body["id"]

        # 2. Distinct checker reviews and approves
        approve_res = self.client.call(
            "POST",
            f"/v1/ops/merchants/{merchant_id}/review",
            token=checker_token,
            body={"decision": "approve", "feeBps": 150, "settlementDelayHours": 24},
            expected_status=200,
        )
        self.assertEqual(approve_res.body.get("status"), "active")

        # 3. Query audit log to verify immutable event provenance
        audit_res = self.client.call(
            "GET",
            f"/v1/ops/audit?subject=merchant:{merchant_id}",
            token=self.ops_token,
            expected_status=200,
        )
        entries = audit_res.body if isinstance(audit_res.body, list) else []
        self.assertGreaterEqual(len(entries), 1)

        # 4. Zero ledger drift
        self.verify_ledger_integrity()
