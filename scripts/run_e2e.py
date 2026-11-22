#!/usr/bin/env python3
"""ELEPAY Core — End-to-End (E2E) Test Runner.

Executes the 4-tier E2E test suite over HTTP against a running ELEPAY instance.
Built exclusively on the Python 3 standard library with zero external dependencies.

Usage:
    python3 scripts/run_e2e.py [OPTIONS]

Example:
    python3 scripts/run_e2e.py --tier 1,2,3,4 --verify-ledger --junit-xml target/junit-e2e.xml
"""
import argparse
import os
import sys
import time
import unittest

# Ensure project root is in sys.path
PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if PROJECT_ROOT not in sys.path:
    sys.path.insert(0, PROJECT_ROOT)

from tests.e2e.framework.client import OpaqueApiClient
from tests.e2e.framework.config import TestConfig, set_config
from tests.e2e.framework.runner import (
    TestResultCollector,
    discover_e2e_suites,
    export_json_report,
    export_junit_xml,
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="ELEPAY Core 4-Tier E2E Test Suite Runner",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument(
        "--base-url",
        type=str,
        default=os.environ.get("ELEPAY_URL", "http://127.0.0.1:8080"),
        help="Target ELEPAY server base URL",
    )
    parser.add_argument(
        "--tier",
        type=str,
        default="all",
        help="Comma-separated list of tiers to run (e.g. '1', '1,2', '3', '4', 'all')",
    )
    parser.add_argument(
        "-k",
        "--filter",
        type=str,
        default=None,
        help="Regex or substring filter pattern matching test IDs",
    )
    parser.add_argument(
        "--fast",
        action="store_true",
        default=False,
        help="Fast mode: run scaled-down financial stress sequences",
    )
    parser.add_argument(
        "--seed",
        type=int,
        default=42,
        help="Random seed for reproducible test generation",
    )
    parser.add_argument(
        "--verify-ledger",
        dest="verify_ledger",
        action="store_true",
        default=True,
        help="Enforce GET /v1/ops/ledger/integrity check after suites",
    )
    parser.add_argument(
        "--no-verify-ledger",
        dest="verify_ledger",
        action="store_false",
        help="Disable automatic ledger integrity verification",
    )
    parser.add_argument(
        "--report",
        type=str,
        default=None,
        help="Path to write JSON execution report (e.g. target/e2e-report.json)",
    )
    parser.add_argument(
        "--junit-xml",
        type=str,
        default=None,
        help="Path to write JUnit XML test report (e.g. target/junit-e2e.xml)",
    )
    parser.add_argument(
        "-x",
        "--fail-fast",
        action="store_true",
        default=False,
        help="Stop suite execution immediately on first failure",
    )
    parser.add_argument(
        "--concurrency",
        type=int,
        default=1,
        help="Number of worker threads (default: 1)",
    )
    parser.add_argument(
        "-v",
        "--verbose",
        action="store_true",
        default=False,
        help="Verbose console test execution logging",
    )
    return parser.parse_args()


def parse_tiers(tier_str: str) -> list[int]:
    if tier_str.strip().lower() in ("all", "*"):
        return [1, 2, 3, 4]
    tiers = []
    for part in tier_str.split(","):
        part = part.strip()
        if part.isdigit():
            val = int(part)
            if val in (1, 2, 3, 4):
                tiers.append(val)
    return tiers or [1, 2, 3, 4]


def main() -> int:
    args = parse_args()
    selected_tiers = parse_tiers(args.tier)

    config = TestConfig(
        base_url=args.base_url.rstrip("/"),
        verify_ledger=args.verify_ledger,
        fast=args.fast,
        seed=args.seed,
        fail_fast=args.fail_fast,
        verbose=args.verbose,
        concurrency=args.concurrency,
        tiers=selected_tiers,
        filter_pattern=args.filter,
        report_path=args.report,
        junit_xml_path=args.junit_xml,
    )
    set_config(config)

    print("=" * 70)
    print(" ELEPAY Core End-to-End (E2E) Test Harness")
    print(f" Target Server : {config.base_url}")
    print(f" Active Tiers  : {config.tiers}")
    print(f" Filter Pattern: {config.filter_pattern or 'None (all tests)'}")
    print(f" Verify Ledger : {config.verify_ledger}")
    print(f" Fast Mode     : {config.fast}")
    print("=" * 70)

    # Check server availability
    client = OpaqueApiClient(config.base_url)
    server_online = client.check_health()
    if not server_online:
        print(f"[!] Server is not online at {config.base_url}.")
        print("[!] Tests will be discovered, loaded, and safely skipped or dry-run.")
    else:
        print(f"[+] Connected successfully to ELEPAY Core at {config.base_url}")

    # Discover and build test suite
    start_time = time.time()
    suite = discover_e2e_suites(
        tiers=config.tiers,
        filter_pattern=config.filter_pattern,
        base_dir=os.path.join(PROJECT_ROOT, "tests", "e2e"),
    )
    total_discovered = suite.countTestCases()
    print(f"[*] Discovered {total_discovered} test cases matching criteria.\n")

    verbosity = 2 if config.verbose else 1
    result = TestResultCollector(verbosity=verbosity)
    result.failfast = config.fail_fast

    suite.run(result)
    elapsed = time.time() - start_time

    # Print Summary
    passed_count = len([r for r in result.test_records if r["status"] == "PASSED"])
    failed_count = len(result.failures)
    error_count = len(result.errors)
    skipped_count = len(result.skipped)

    print("\n" + "=" * 70)
    print(" E2E EXECUTION SUMMARY")
    print("=" * 70)
    print(f" Total Tests Run : {result.testsRun}")
    print(f" Passed          : {passed_count}")
    print(f" Failed          : {failed_count}")
    print(f" Errors          : {error_count}")
    print(f" Skipped         : {skipped_count}")
    print(f" Elapsed Time    : {elapsed:.2f}s")
    print("=" * 70)

    # Export reports if requested
    if config.report_path:
        export_json_report(result, elapsed, config.report_path, config)
        print(f"[+] JSON report exported to: {config.report_path}")

    if config.junit_xml_path:
        export_junit_xml(result, elapsed, config.junit_xml_path)
        print(f"[+] JUnit XML report exported to: {config.junit_xml_path}")

    success = result.wasSuccessful()
    return 0 if success else 1


if __name__ == "__main__":
    sys.exit(main())
