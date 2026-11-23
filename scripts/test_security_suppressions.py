#!/usr/bin/env python3
"""Security policy regression checks: no hidden dependency-scan exceptions."""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
POM = ROOT / "pom.xml"
OWASP_SUPPRESSIONS = ROOT / "security" / "owasp-suppressions.xml"
OBSERVABILITY = ROOT / "src/main/java/com/elepay/reliability/ObservabilityService.java"


class UnsuppressedDependencyAuditTest(unittest.TestCase):
    def test_no_local_owasp_suppressions(self):
        self.assertFalse(OWASP_SUPPRESSIONS.exists(),
                         "Delete local CVE exception lists; remediate or verify findings")
        content = POM.read_text()
        self.assertNotIn("<suppressionFiles>", content)
        self.assertNotIn("<suppressionFile>", content)
        self.assertIn("<failBuildOnCVSS>5</failBuildOnCVSS>", content)

    def test_remove_unused_opentelemetry_and_noop_spans(self):
        pom = POM.read_text()
        self.assertNotIn("<groupId>io.opentelemetry</groupId>", pom)
        service = OBSERVABILITY.read_text()
        self.assertNotIn("OpenTelemetry.noop().getTracer(", service)
        self.assertIn("Observation.start(spanName, observationRegistry)", service)


if __name__ == "__main__":
    unittest.main()
