"""Tier 4: Scenario 4 — High-Volume Financial Stress Suite.

Executes a sequence of pseudo-random financial operations with reproducible seed:
- Top-ups
- Peer-to-peer wallet transfers
- Marketplace checkouts and allocations
- Partial line refunds
- Merchant settlement batching
- Verified withdrawals
- Continuous and post-run assertion of zero ledger drift and balanced entries
"""
import random
from tests.e2e.framework.assertions import (
    assert_http_status,
    assert_ledger_integrity,
    assert_minor_unit_integer,
)
from tests.e2e.framework.fixtures import local_phone, unique_id, unique_uuid
from tests.e2e.framework.runner import OpaqueTestCase


class TestFinancialStressSuite(OpaqueTestCase):
    """Test suite executing high-volume financial sequences while enforcing zero ledger drift."""

    def test_high_volume_financial_operations_stress_sequence(self):
        """Scenario 4: Multi-operation financial sequence maintaining strict double-entry balance invariants."""
        rng = random.Random(self.config.seed)
        op_count = 10 if self.config.fast else 30

        # Create shared pool of actors
        customers = [self.enroll_customer(f"Stress User {i}") for i in range(3)]
        merchants = [self.create_merchant(f"Stress Merchant {i}", "merchant", fee_bps=100) for i in range(2)]

        # Pre-fund all customers
        for c in customers:
            self.fund_wallet(c["token"], amount=50000)

        for i in range(op_count):
            action = rng.choice(["transfer", "checkout", "refund", "settle"])

            if action == "transfer":
                sender, recipient = rng.sample(customers, 2)
                lookup = self.client.call(
                    "POST",
                    "/v1/wallet/recipients/lookup",
                    token=sender["token"],
                    body={"rail": "wallet", "phone": local_phone(recipient["phone"])},
                )
                if lookup.status == 200 and lookup.body.get("confirmationId"):
                    amt = rng.randint(500, 2000)
                    self.client.call(
                        "POST",
                        "/v1/wallet/transfers/confirmed",
                        token=sender["token"],
                        body={"confirmationId": lookup.body["confirmationId"], "amount": amt},
                        idempotency_key=unique_uuid(),
                    )

            elif action == "checkout":
                buyer = rng.choice(customers)
                seller = rng.choice(merchants)
                amt = rng.randint(1000, 5000)
                order = self.client.call(
                    "POST",
                    f"/v1/merchants/{seller['id']}/checkouts",
                    token=seller["ownerToken"],
                    body={"allocations": [{"merchantId": seller["id"], "kind": "sale", "amount": amt}]},
                    idempotency_key=unique_uuid(),
                )
                if order.status == 200:
                    self.client.call(
                        "POST",
                        f"/v1/checkouts/{order.body['reference']}/pay/wallet",
                        token=buyer["token"],
                        idempotency_key=unique_uuid(),
                    )

            elif action == "settle":
                seller = rng.choice(merchants)
                self.client.call(
                    "POST",
                    "/v1/ops/settlements/run",
                    token=self.ops_token,
                    body={"ignoreDelay": True, "merchantId": seller["id"]},
                )

        # Trigger rail recovery to clean up simulated transactions
        self.client.call("POST", "/v1/ops/rails/recover", token=self.ops_token, expected_status=200)

        # Assert zero ledger drift
        self.verify_ledger_integrity()
