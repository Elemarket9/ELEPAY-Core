#!/usr/bin/env python3
"""Guard against an OWASP exception that accidentally hides other CVEs or releases."""
import pathlib
import re
import unittest
import xml.etree.ElementTree as ET

FILE = pathlib.Path(__file__).resolve().parents[1] / "security" / "owasp-suppressions.xml"
NS = {"dc": "https://jeremylong.github.io/DependencyCheck/dependency-suppression.1.4.xsd"}


class OpenTelemetryFalsePositiveScopeTest(unittest.TestCase):
    def test_suppression_remains_versioned_js_cve_only(self):
        doc = ET.parse(FILE)
        rules = doc.findall("dc:suppress", NS)
        self.assertEqual(len(rules), 1)
        rule = rules[0]
        self.assertEqual(rule.findtext("dc:cve", namespaces=NS), "CVE-2026-54285")
        expression = rule.find("dc:packageUrl", NS)
        self.assertIsNotNone(expression)
        self.assertEqual(expression.attrib.get("regex"), "true")
        regex = re.compile(expression.text)
        for artifact in ["opentelemetry-api", "opentelemetry-sdk",
                         "opentelemetry-common", "opentelemetry-sdk-trace"]:
            self.assertIsNotNone(regex.fullmatch(
                "pkg:maven/io.opentelemetry/" + artifact + "@1.68.0"))
        for other in ["pkg:npm/%40opentelemetry/core@2.7.0",
                      "pkg:maven/io.opentelemetry/opentelemetry-api@1.69.0",
                      "pkg:maven/com.other/opentelemetry-api@1.68.0",
                      "pkg:maven/io.opentelemetry/opentelemetry-api@1.67.0"]:
            self.assertIsNone(regex.fullmatch(other))


if __name__ == "__main__":
    unittest.main()
