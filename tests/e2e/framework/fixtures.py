"""Deterministic test fixtures and generators for ELEPAY E2E tests."""
import random
import uuid
from typing import Optional


def unique_phone(seed: Optional[int] = None) -> str:
    """Generate a valid Ghana E.164 phone string (e.g. '233241234567').

    Ghana mobile numbers begin with country code 233, followed by 9 digits
    starting with 2 or 5 (MTN: 024, 054, 055; Telecel: 020, 050; AT: 027, 057).
    """
    if seed is not None:
        rng = random.Random(seed)
        subscriber = rng.randint(200000000, 899999999)
    else:
        # UUID least significant bits mod range
        subscriber = 200000000 + (uuid.uuid4().int % 700000000)
    return f"233{subscriber}"


def local_phone(phone_str: str) -> str:
    """Convert international 233... or +233... to local 0... format."""
    cleaned = phone_str.lstrip("+")
    if cleaned.startswith("233"):
        return "0" + cleaned[3:]
    if cleaned.startswith("0"):
        return cleaned
    return "0" + cleaned


def intl_phone(phone_str: str) -> str:
    """Convert local 0... or raw 233... to +233... format."""
    cleaned = phone_str.strip().lstrip("+")
    if cleaned.startswith("0"):
        return "+233" + cleaned[1:]
    if cleaned.startswith("233"):
        return "+" + cleaned
    return "+233" + cleaned


def unique_id(prefix: str = "") -> str:
    """Generate a unique random alphanumeric identifier with an optional prefix."""
    token = uuid.uuid4().hex[:8]
    return f"{prefix}{token}" if prefix else token


def unique_uuid() -> str:
    """Generate a standard UUID string."""
    return str(uuid.uuid4())
