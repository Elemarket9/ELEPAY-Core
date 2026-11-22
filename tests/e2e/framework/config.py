"""Configuration management for ELEPAY E2E test harness."""
from dataclasses import dataclass, field
import os
from typing import List, Optional


@dataclass
class TestConfig:
    base_url: str = field(
        default_factory=lambda: os.environ.get("ELEPAY_URL", "http://127.0.0.1:8080").rstrip("/")
    )
    dev_secret: str = "dev-secret"
    ussd_secret: str = field(
        default_factory=lambda: os.environ.get("ELEPAY_USSD_SECRET", "sandbox-ussd-secret")
    )
    rail_secret: str = field(
        default_factory=lambda: os.environ.get("ELEPAY_RAIL_SECRET", "sandbox-webhook-secret")
    )
    fx_secret: str = field(
        default_factory=lambda: os.environ.get("ELEPAY_FX_SECRET", "sim-remit-secret")
    )
    default_timeout: float = 20.0
    verify_ledger: bool = True
    fast: bool = False
    seed: int = 42
    fail_fast: bool = False
    verbose: bool = False
    concurrency: int = 1
    tiers: List[int] = field(default_factory=lambda: [1, 2, 3, 4])
    filter_pattern: Optional[str] = None
    report_path: Optional[str] = None
    junit_xml_path: Optional[str] = None


_CURRENT_CONFIG: TestConfig = TestConfig()


def get_config() -> TestConfig:
    """Return the global active TestConfig instance."""
    global _CURRENT_CONFIG
    return _CURRENT_CONFIG


def set_config(config: TestConfig) -> None:
    """Set the global active TestConfig instance."""
    global _CURRENT_CONFIG
    _CURRENT_CONFIG = config
