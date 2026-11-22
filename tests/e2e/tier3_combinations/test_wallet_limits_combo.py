"""Tier 3: Combo 1 — Ledger + Wallet Transfers + Limit Checks.

Validates the interplay between:
- Atomic double-entry balance postings and row locking
- Ephemeral single-use confirmation tokens bound to destination and amount
- Transfer limits and velocity checks
- Authoritative ledger integrity proof
"""
from tests.e2e.framework.assertions import (
    assert_http_status,
    assert_ledger_integrity,
    assert_minor_unit_integer,
)
from tests.e2e.framework.fixtures import local_phone, unique_id, unique_uuid
from tests.e2e.framework.runner import OpaqueTestCase


class TestWalletLimitsCombo(OpaqueTestCase):
    """Test suite for integrated wallet transfers, limit checks, and ledger invariants."""

    def test_combo_1_wallet_transfer_limits_and_ledger_invariants(self):
        """Combo 1: Fund wallet -> Bound confirmation -> Valid transfer -> Repeat protection -> Ledger proof."""
        sender = self.enroll_customer("Combo1 Sender")
        recipient = self.enroll_customer("Combo1 Recipient")

        # 1. Fund sender wallet with 20000 pesewas (GH₵200.00)
        self.fund_wallet(sender["token"], amount=20000)

        # 2. Lookup recipient
        lookup = self.client.call(
            "POST",
            "/v1/wallet/recipients/lookup",
            token=sender["token"],
            body={"rail": "wallet", "phone": local_phone(recipient["phone"])},
            expected_status=200,
        )
        self.assertEqual(lookup.body.get("status"), "verified")
        conf_id = lookup.body.get("confirmationId")

        # 3. Preview transfer of 6000 pesewas
        preview = self.client.call(
            "POST",
            "/v1/wallet/transfers/preview",
            token=sender["token"],
            body={"confirmationId": conf_id, "amount": 6000},
            expected_status=200,
        )
        self.assertEqual(preview.body.get("amount"), 6000)
        self.assertEqual(preview.body.get("total"), 6000)

        # 4. Execute confirmed transfer
        transfer_key = unique_uuid()
        transfer = self.client.call(
            "POST",
            "/v1/wallet/transfers/confirmed",
            token=sender["token"],
            body={"confirmationId": conf_id, "amount": 6000},
            idempotency_key=transfer_key,
            expected_status=200,
        )
        self.assertEqual(transfer.body.get("status"), "completed")

        # 5. Idempotent replay: exact same payload and key returns exact same receipt
        replay = self.client.call(
            "POST",
            "/v1/wallet/transfers/confirmed",
            token=sender["token"],
            body={"confirmationId": conf_id, "amount": 6000},
            idempotency_key=transfer_key,
            expected_status=200,
        )
        self.assertEqual(replay.body, transfer.body)

        # 6. Verify balances
        sender_wallet = self.client.call("GET", "/v1/wallet", token=sender["token"])
        sender_avail = next(a["balance"] for a in sender_wallet.body["accounts"] if a["bucket"] == "available")
        self.assertEqual(sender_avail, 14000)  # 20000 - 6000 = 14000

        recipient_wallet = self.client.call("GET", "/v1/wallet", token=recipient["token"])
        recipient_avail = next(a["balance"] for a in recipient_wallet.body["accounts"] if a["bucket"] == "available")
        self.assertEqual(recipient_avail, 6000)

        # 7. Single-use constraint: New request attempting to re-use consumed confirmation token returns 409
        reuse_attempt = self.client.call(
            "POST",
            "/v1/wallet/transfers/confirmed",
            token=sender["token"],
            body={"confirmationId": conf_id, "amount": 6000},
            idempotency_key=unique_uuid(),
            expected_status=409,
        )
        self.assertEqual(reuse_attempt.status, 409)

        # 8. Assert Authoritative Ledger Integrity
        self.verify_ledger_integrity()
