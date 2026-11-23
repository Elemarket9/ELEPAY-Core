#!/usr/bin/env python3
"""Fail-closed fintech-only regression gate, supplementary to compiler, CI and integration tests.

Historic Flyway migrations are excluded because applied migrations must remain immutable.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "src/main/java"
TEST_JAVA = ROOT / "src/test/java"
WEB = ROOT / "web/src"

REMOVED_TYPES = re.compile(
    r"\b(?:ElevyCalculationEngine|ElevyStatutoryReportingService|GraFiscalDataService|"
    r"PromotionAbuseService|PromoAbuseService|ChurnPredictionService|"
    r"LEGACY_DEFAULT_KEY|JumiaPay)\b"
)
# Merchant tools (POS cart, inventory, CRM, payroll, restaurant/KDS) were restored on
# Nov 23 2026 by founder decision; only international payout references stay banned.
NON_FINTECH_SOURCE = re.compile(
    r"\b(?:sendMoneyAbroad|crossBorderPayout|internationalPayout)\b"
)
WEB_FALSE_SUCCESS = re.compile(
    r"(?:Authenticity Hash:|All Systems Green|Payroll successfully executed|Store Credit issued|Recharge successful!|Bill payment completed!|"
    r"Virtual Visa Card issued successfully!|Restructuring file dispatched to credit risk committee\.)"
)
# Payment references must come from real payouts, never be invented in code.
INVENTED_PAYOUT_REF = re.compile(
    r"(?:payout_reference\s*=\s*'[^']*'\s*\|\||\"PAYOUT-[A-Z]+-\"\s*\+|'PAYOUT-[A-Z]+-'\s*\|\|)"
)
UNSAFE_SECRET_DEFAULT = re.compile(
    r"\$\{[^}\n]*(?:password|secret|api[-_.]?key|token)[^}:\n]*:[^}\n]+\}",
    re.IGNORECASE,
)
BANNED_ROUTES = (
    '"/v1/fx/transfers"', '"/v1/fx/transfers/{reference}"',
    '"/v1/fx/partners/{partner}/inbound"',
)


def source_files(directory: Path, extensions: tuple[str, ...]):
    if directory.exists():
        yield from sorted(p for p in directory.rglob("*") if p.is_file() and p.suffix in extensions)


def audit() -> list[str]:
    failures: list[str] = []
    if not all(directory.is_dir() for directory in (JAVA, TEST_JAVA, WEB)):
        return ["Expected production, test or frontend source directory missing"]

    for path in source_files(JAVA, (".java",)):
        rel = path.relative_to(ROOT).as_posix()
        for line_num, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if REMOVED_TYPES.search(line) or NON_FINTECH_SOURCE.search(line):
                failures.append(f"{rel}:{line_num}: deleted module or international payout reference")
            if INVENTED_PAYOUT_REF.search(line):
                failures.append(f"{rel}:{line_num}: invented payout reference (use a real payout)")
            if UNSAFE_SECRET_DEFAULT.search(line):
                failures.append(f"{rel}:{line_num}: production credential has a non-empty default")
            if re.search(r"\b(?:class|record)\s+Simulated\w+", line):
                failures.append(f"{rel}:{line_num}: simulated production implementation")
            if rel.endswith("/fx/FxApi.java") and any(route in line for route in BANNED_ROUTES):
                failures.append(f"{rel}:{line_num}: international recipient payout API returned")

    for path in source_files(TEST_JAVA, (".java",)):
        rel = path.relative_to(ROOT).as_posix()
        for line_num, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if REMOVED_TYPES.search(line):
                failures.append(f"{rel}:{line_num}: stale deleted-class test reference")

    for path in source_files(WEB, (".ts", ".tsx")):
        rel = path.relative_to(ROOT).as_posix()
        for line_num, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if WEB_FALSE_SUCCESS.search(line):
                failures.append(f"{rel}:{line_num}: fabricated financial or regulatory success")

    return failures


def main() -> int:
    failures = audit()
    if failures:
        print("ELEPAY fintech scope/security gate FAILED:", file=sys.stderr)
        for failure in failures:
            print(f"  - {failure}", file=sys.stderr)
        return 1
    print("ELEPAY fintech-only reference/false-success source gate passed.")
    print("Historic Flyway migrations remain immutable.")
    print("Maven, frontend, database, secrets and partner verification are still required.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
