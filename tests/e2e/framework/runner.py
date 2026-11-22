"""Base test case class, test discovery, and execution runner for ELEPAY E2E suites."""
import json
import os
import re
import sys
import time
import unittest
import uuid
import xml.etree.ElementTree as ET
from typing import Any, Dict, List, Optional

from tests.e2e.framework.assertions import (
    assert_http_status,
    assert_ledger_integrity,
)
from tests.e2e.framework.client import OpaqueApiClient
from tests.e2e.framework.config import TestConfig, get_config
from tests.e2e.framework.fixtures import unique_id, unique_phone, unique_uuid


class OpaqueTestCase(unittest.TestCase):
    """Base test case for all ELEPAY opaque-box E2E test suites."""

    config: TestConfig
    client: OpaqueApiClient
    run_id: str
    ops_token: Optional[str] = None
    _server_online: Optional[bool] = None

    @classmethod
    def setUpClass(cls) -> None:
        cls.config = get_config()
        cls.client = OpaqueApiClient(cls.config.base_url, default_timeout=cls.config.default_timeout)
        cls.run_id = unique_id()
        cls._server_online = cls.client.check_health()
        if cls._server_online:
            cls.ops_token = cls.client.mint_token(f"ops-{cls.run_id}", roles=["ops"])

    def setUp(self) -> None:
        self.config = get_config()
        self.client = OpaqueApiClient(self.config.base_url, default_timeout=self.config.default_timeout)
        self.run_id = unique_id()
        if self._server_online is None:
            self._server_online = self.client.check_health()
        if not self._server_online:
            self.skipTest(f"ELEPAY Core server is offline at {self.config.base_url}")
        if self.ops_token is None:
            self.ops_token = self.client.mint_token(f"ops-{self.run_id}", roles=["ops"])

    def tearDown(self) -> None:
        if self._server_online and self.config.verify_ledger and self.ops_token:
            try:
                assert_ledger_integrity(self.client, self.ops_token)
            except Exception as e:
                # Re-raise ledger drift exception to fail test
                raise e

    # --- Convenience Helper Methods ---

    def mint_token(self, subject: str, *roles: str) -> str:
        """Mint a dev bearer JWT for the given subject and roles."""
        return self.client.mint_token(subject, list(roles))

    def unique_phone(self) -> str:
        """Generate unique valid Ghana phone."""
        return unique_phone()

    def enroll_customer(
        self,
        name: str = "Test Customer",
        phone_number: Optional[str] = None,
        subject: Optional[str] = None,
    ) -> Dict[str, Any]:
        """Enroll a new customer via ops endpoint and return customer details and token."""
        sub = subject or f"cust-{self.run_id}-{unique_id()}"
        ph = phone_number or self.unique_phone()
        token = self.mint_token(sub)
        res = self.client.call(
            "POST",
            "/v1/ops/customers",
            token=self.ops_token,
            body={"subject": sub, "phone": ph, "displayName": name},
            expected_status=200,
        )
        data = res.body if isinstance(res.body, dict) else {}
        return {
            "customerId": data.get("customerId"),
            "subject": sub,
            "phone": ph,
            "displayName": name,
            "token": token,
        }

    def create_merchant(
        self,
        name: str = "Test Merchant",
        kind: str = "merchant",
        fee_bps: int = 150,
        fee_fixed: int = 0,
        settlement_delay_hours: int = 24,
        owner_subject: Optional[str] = None,
    ) -> Dict[str, Any]:
        """Create and approve a merchant, returning its details and owner token."""
        sub = owner_subject or f"mowner-{self.run_id}-{unique_id()}"
        owner_token = self.mint_token(sub)
        trading_name = f"{name} {unique_id()}"
        created = self.client.call(
            "POST",
            "/v1/merchants",
            token=owner_token,
            body={
                "legalName": f"{trading_name} Ltd",
                "tradingName": trading_name,
                "kind": kind,
                "contactPhone": "0241234567",
            },
            expected_status=200,
        )
        m_data = created.body
        merchant_id = m_data.get("id")

        # Ops review and approve
        self.client.call(
            "POST",
            f"/v1/ops/merchants/{merchant_id}/review",
            token=self.ops_token,
            body={
                "decision": "approve",
                "feeBps": fee_bps,
                "feeFixed": fee_fixed,
                "settlementDelayHours": settlement_delay_hours,
            },
            expected_status=200,
        )

        # Retrieve current merchant record to get merchantCode
        details = self.client.call("GET", f"/v1/merchants/{merchant_id}", token=owner_token)
        merchant_code = details.body.get("merchantCode") if isinstance(details.body, dict) else None

        return {
            "merchantId": merchant_id,
            "id": merchant_id,
            "merchantCode": merchant_code,
            "tradingName": trading_name,
            "ownerToken": owner_token,
            "ownerSubject": sub,
        }

    def fund_wallet(
        self,
        customer_token: str,
        amount: int = 15000,
        phone_number: str = "0245550005",
        network: str = "mtn",
    ) -> None:
        """Fund customer wallet via simulated MoMo topup and rail recovery."""
        topup_key = f"topup-{unique_uuid()}"
        res = self.client.call(
            "POST",
            "/v1/wallet/topups",
            token=customer_token,
            body={
                "amount": amount,
                "method": "momo",
                "phone": phone_number,
                "network": network,
            },
            idempotency_key=topup_key,
            expected_status=200,
        )
        assert res.body.get("status") in ("pending", "succeeded")
        # Trigger ops rail recovery to finalize simulated pending transactions
        if res.body.get("status") == "pending":
            self.client.call("POST", "/v1/ops/rails/recover", token=self.ops_token, expected_status=200)

    def verify_ledger_integrity(self) -> Dict[str, Any]:
        """Explicitly run and assert zero ledger drift."""
        return assert_ledger_integrity(self.client, self.ops_token)


class TestResultCollector(unittest.TestResult):
    """Detailed collector tracking durations and outcomes for JSON/JUnit reports."""

    def __init__(self, verbosity: int = 1):
        super().__init__()
        self.verbosity = verbosity
        self.test_records: List[Dict[str, Any]] = []
        self._start_times: Dict[str, float] = {}

    def startTest(self, test: unittest.TestCase) -> None:
        super().startTest(test)
        self._start_times[test.id()] = time.time()
        if self.verbosity > 1:
            sys.stdout.write(f"  RUN  {test.id()} ... ")
            sys.stdout.flush()

    def addSuccess(self, test: unittest.TestCase) -> None:
        super().addSuccess(test)
        duration = time.time() - self._start_times.get(test.id(), time.time())
        self.test_records.append(
            {"id": test.id(), "status": "PASSED", "duration": round(duration, 4), "message": None}
        )
        if self.verbosity > 1:
            sys.stdout.write(f"OK ({duration:.3f}s)\n")

    def addError(self, test: unittest.TestCase, err: Any) -> None:
        super().addError(test, err)
        duration = time.time() - self._start_times.get(test.id(), time.time())
        msg = self._exc_info_to_string(err, test)
        self.test_records.append(
            {"id": test.id(), "status": "ERROR", "duration": round(duration, 4), "message": msg}
        )
        if self.verbosity > 1:
            sys.stdout.write(f"ERROR ({duration:.3f}s)\n")

    def addFailure(self, test: unittest.TestCase, err: Any) -> None:
        super().addFailure(test, err)
        duration = time.time() - self._start_times.get(test.id(), time.time())
        msg = self._exc_info_to_string(err, test)
        self.test_records.append(
            {"id": test.id(), "status": "FAILED", "duration": round(duration, 4), "message": msg}
        )
        if self.verbosity > 1:
            sys.stdout.write(f"FAIL ({duration:.3f}s)\n")

    def addSkip(self, test: unittest.TestCase, reason: str) -> None:
        super().addSkip(test, reason)
        duration = time.time() - self._start_times.get(test.id(), time.time())
        self.test_records.append(
            {"id": test.id(), "status": "SKIPPED", "duration": round(duration, 4), "message": reason}
        )
        if self.verbosity > 1:
            sys.stdout.write(f"SKIPPED ({reason})\n")


def discover_e2e_suites(
    tiers: Optional[List[int]] = None,
    filter_pattern: Optional[str] = None,
    base_dir: Optional[str] = None,
) -> unittest.TestSuite:
    """Discover and filter test cases matching requested tiers and regex/substring pattern."""
    loader = unittest.defaultTestLoader
    if base_dir is None:
        # tests/e2e
        base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

    active_tiers = tiers if tiers is not None else [1, 2, 3, 4]
    tier_dirs = {
        1: "tier1_features",
        2: "tier2_boundaries",
        3: "tier3_combinations",
        4: "tier4_scenarios",
    }

    combined_suite = unittest.TestSuite()
    pattern_re = re.compile(filter_pattern, re.IGNORECASE) if filter_pattern else None

    for tier_num in active_tiers:
        subdir = tier_dirs.get(tier_num)
        if not subdir:
            continue
        tier_path = os.path.join(base_dir, subdir)
        if not os.path.isdir(tier_path):
            continue

        suite = loader.discover(start_dir=tier_path, pattern="test_*.py", top_level_dir=os.path.dirname(base_dir))
        # Filter tests within suite if pattern is provided
        _filter_suite(suite, pattern_re, combined_suite)

    return combined_suite


def _filter_suite(
    suite: unittest.TestSuite,
    pattern: Optional[re.Pattern],
    collector: unittest.TestSuite,
) -> None:
    for item in suite:
        if isinstance(item, unittest.TestSuite):
            _filter_suite(item, pattern, collector)
        elif isinstance(item, unittest.TestCase):
            test_id = item.id()
            if pattern is None or pattern.search(test_id):
                collector.addTest(item)


def export_json_report(
    result: TestResultCollector,
    elapsed_time: float,
    report_path: str,
    config: TestConfig,
) -> None:
    """Export machine-readable JSON execution summary."""
    os.makedirs(os.path.dirname(os.path.abspath(report_path)), exist_ok=True)
    summary = {
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "total": result.testsRun,
        "passed": len([r for r in result.test_records if r["status"] == "PASSED"]),
        "failed": len(result.failures),
        "errors": len(result.errors),
        "skipped": len(result.skipped),
        "elapsed_seconds": round(elapsed_time, 4),
        "config": {
            "base_url": config.base_url,
            "tiers": config.tiers,
            "fast": config.fast,
            "verify_ledger": config.verify_ledger,
        },
        "tests": result.test_records,
    }
    with open(report_path, "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2)


def export_junit_xml(
    result: TestResultCollector,
    elapsed_time: float,
    xml_path: str,
) -> None:
    """Export standard JUnit XML test report for CI pipeline reporting."""
    os.makedirs(os.path.dirname(os.path.abspath(xml_path)), exist_ok=True)
    testsuites = ET.Element(
        "testsuites",
        time=f"{elapsed_time:.3f}",
        tests=str(result.testsRun),
        failures=str(len(result.failures)),
        errors=str(len(result.errors)),
        skipped=str(len(result.skipped)),
    )
    testsuite = ET.SubElement(
        testsuites,
        "testsuite",
        name="ELEPAY-E2E-Suite",
        tests=str(result.testsRun),
        failures=str(len(result.failures)),
        errors=str(len(result.errors)),
        skipped=str(len(result.skipped)),
        time=f"{elapsed_time:.3f}",
    )

    for record in result.test_records:
        test_id = record["id"]
        class_name, test_name = test_id.rsplit(".", 1) if "." in test_id else ("", test_id)
        testcase = ET.SubElement(
            testsuite,
            "testcase",
            classname=class_name,
            name=test_name,
            time=f"{record['duration']:.3f}",
        )
        if record["status"] == "FAILED":
            elem = ET.SubElement(testcase, "failure", message="Assertion failure")
            elem.text = record["message"] or ""
        elif record["status"] == "ERROR":
            elem = ET.SubElement(testcase, "error", message="Execution error")
            elem.text = record["message"] or ""
        elif record["status"] == "SKIPPED":
            elem = ET.SubElement(testcase, "skipped", message=record["message"] or "Skipped")

    tree = ET.ElementTree(testsuites)
    tree.write(xml_path, encoding="utf-8", xml_declaration=True)
