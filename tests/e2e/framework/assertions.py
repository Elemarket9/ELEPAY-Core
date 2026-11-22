"""Assertion helpers and cryptographic verification routines for ELEPAY."""
import hashlib
import hmac
from typing import Any, Dict, List, Union


class LedgerIntegrityViolationError(AssertionError):
    """Raised when the authoritative ledger drift check fails."""
    pass


def assert_http_status(response: Any, expected: Union[int, List[int]], msg: str = "") -> None:
    """Verify HTTP status code matches expected status."""
    status = getattr(response, "status", None)
    if status is None and isinstance(response, dict):
        status = response.get("status")
    
    expected_list = [expected] if isinstance(expected, int) else expected
    if status not in expected_list:
        body = getattr(response, "body", response)
        raise AssertionError(
            f"Expected HTTP status in {expected_list}, got {status}. Response body: {body}. {msg}".strip()
        )


def assert_minor_unit_integer(amount: Any, field_name: str = "amount") -> None:
    """Verify monetary amount is strictly an integer minor unit (pesewas/cents), forbidding floats."""
    if not isinstance(amount, int) or isinstance(amount, bool):
        raise AssertionError(
            f"Financial invariant violated: '{field_name}' must be an integer minor unit, got {type(amount).__name__}: {amount}"
        )


def assert_zero_sum_entries(legs: List[Dict[str, Any]], currency: str = "GHS") -> None:
    """Verify that ledger journal entries for the specified currency sum to exactly zero."""
    total = 0
    currency_legs = 0
    for leg in legs:
        leg_curr = leg.get("currency", "GHS")
        if leg_curr == currency:
            currency_legs += 1
            amt = leg.get("amount", 0)
            assert_minor_unit_integer(amt, "leg.amount")
            total += amt
    if total != 0:
        raise AssertionError(
            f"Zero-sum per-currency ledger invariant violated for {currency}: sum of {currency_legs} legs is {total} != 0"
        )


def assert_ledger_integrity(client: Any, ops_token: str) -> Dict[str, Any]:
    """Execute GET /v1/ops/ledger/integrity and assert zero drift across all accounts and currencies."""
    response = client.call("GET", "/v1/ops/ledger/integrity", token=ops_token)
    assert_http_status(response, 200, "Ledger integrity probe failed")
    
    data = response.body
    if not isinstance(data, dict):
        raise LedgerIntegrityViolationError(f"Unexpected ledger integrity response structure: {data}")
    
    drift = data.get("accountsWithDrift", 0)
    unbalanced = data.get("unbalancedTransactions", 0)
    net_by_currency = data.get("netByCurrency", {})
    
    violations = []
    if drift != 0:
        violations.append(f"accountsWithDrift={drift}")
    if unbalanced != 0:
        violations.append(f"unbalancedTransactions={unbalanced}")
    for curr, net in net_by_currency.items():
        if net != 0:
            violations.append(f"netByCurrency[{curr}]={net} != 0")
    
    if violations:
        raise LedgerIntegrityViolationError(
            f"Ledger integrity invariant check failed: {', '.join(violations)}. Full report: {data}"
        )
    return data


def sign_ussd_body(secret: str, body: str) -> str:
    """Compute HMAC-SHA256 signature for generic USSD gateway payloads."""
    key = secret.encode("utf-8")
    msg = body.encode("utf-8")
    return hmac.new(key, msg, hashlib.sha256).hexdigest().lower()


def sign_rail_callback(secret: str, body: str) -> str:
    """Compute HMAC-SHA512 signature for sandbox payment rail callbacks."""
    key = secret.encode("utf-8")
    msg = body.encode("utf-8")
    return hmac.new(key, msg, hashlib.sha512).hexdigest().lower()


def sign_fx_partner(secret: str, body: str) -> str:
    """Compute HMAC-SHA512 signature for simulated FX partner webhooks."""
    key = secret.encode("utf-8")
    msg = body.encode("utf-8")
    return hmac.new(key, msg, hashlib.sha512).hexdigest().lower()


def sign_webhook(secret: str, timestamp: int, body: str) -> str:
    """Compute standard ELEPAY webhook signature: t=<timestamp>,v1=<hmac_sha256>."""
    key = secret.encode("utf-8")
    payload = f"{timestamp}.{body}".encode("utf-8")
    sig = hmac.new(key, payload, hashlib.sha256).hexdigest().lower()
    return f"t={timestamp},v1={sig}"


def crc16_ccitt(data: str) -> str:
    """Calculate CRC16-CCITT (polynomial 0x1021, init 0xFFFF) formatted as 4-char uppercase hex.

    Used by EMVCo QR code specification (Tag 63).
    """
    crc = 0xFFFF
    for ch in data:
        crc = ((crc << 8) & 0xFFFF) ^ _CRC16_TABLE[((crc >> 8) ^ ord(ch)) & 0xFF]
    return f"{crc:04X}"


# Precomputed lookup table for standard CCITT 0x1021
_CRC16_TABLE = []
for i in range(256):
    curr = i << 8
    for _ in range(8):
        if curr & 0x8000:
            curr = ((curr << 1) ^ 0x1021) & 0xFFFF
        else:
            curr = (curr << 1) & 0xFFFF
    _CRC16_TABLE.append(curr)
