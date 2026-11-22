package com.elepay.security;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CI security scanner engine (Algorithm 084).
 * Performs static secret scanning, dependency vulnerability evaluation,
 * and financial integrity rule checks, blocking CI pipelines on HIGH/CRITICAL findings.
 */
public class CiSecurityScanner {

    public enum FindingSeverity {
        CRITICAL,
        HIGH,
        MEDIUM,
        LOW
    }

    public record ScanFinding(
            String ruleId,
            String description,
            FindingSeverity severity,
            String location,
            int lineNumber
    ) {}

    public record DependencyVulnerability(
            String groupId,
            String artifactId,
            String affectedVersionPattern,
            String cveId,
            double cvssScore,
            String description
    ) {}

    public record DependencyArtifact(
            String groupId,
            String artifactId,
            String version
    ) {}

    public record ScanReport(
            boolean gatePassed,
            int criticalCount,
            int highCount,
            int mediumCount,
            int lowCount,
            List<ScanFinding> findings
    ) {}

    private static final List<PatternRule> SECRET_PATTERNS = List.of(
            new PatternRule("SEC-001", "AWS Access Key ID detected", FindingSeverity.CRITICAL,
                    Pattern.compile("AKIA[0-9A-Z]{16}")),
            new PatternRule("SEC-002", "Private RSA/EC/Generic key block detected", FindingSeverity.CRITICAL,
                    Pattern.compile("-----BEGIN (RSA|EC|OPENSSH|PGP|PRIVATE) KEY-----")),
            new PatternRule("SEC-003", "GitHub Personal Access Token detected", FindingSeverity.CRITICAL,
                    Pattern.compile("ghp_[A-Za-z0-9_]{36}")),
            new PatternRule("SEC-004", "Hardcoded API password or token assignment", FindingSeverity.HIGH,
                    Pattern.compile("(?i)(password|secret|api_key|apikey)\\s*=\\s*[\"'][A-Za-z0-9!@#$%^&*()_+]{8,}[\"']"))
    );

    private static final Pattern FINANCIAL_FLOAT_PATTERN = Pattern.compile("(?i)(float|double)\\s+(amount|balance|pesewas|cedis|fee|rate)");

    private final List<DependencyVulnerability> knownVulnerabilities = new ArrayList<>();

    public CiSecurityScanner() {
        // Register standard critical CVE database baseline
        registerKnownVulnerability(new DependencyVulnerability("org.apache.logging.log4j", "log4j-core", "2\\.(0|1|2|3|4|5|6|7|8|9|10|11|12|13|14|15|16|17\\.0).*", "CVE-2021-44228", 10.0, "Log4Shell remote code execution"));
        registerKnownVulnerability(new DependencyVulnerability("org.apache.commons", "commons-text", "1\\.(0|1|2|3|4|5|6|7|8|9).*", "CVE-2022-42889", 9.8, "Text4Shell arbitrary code execution"));
        registerKnownVulnerability(new DependencyVulnerability("org.springframework", "spring-core", "5\\.3\\.(0|1|2|3|4|5|6|7|8|9|10|11|12|13|14|15|16|17).*", "CVE-2022-22965", 9.8, "Spring4Shell RCE"));
    }

    public void registerKnownVulnerability(DependencyVulnerability vuln) {
        knownVulnerabilities.add(vuln);
    }

    public List<ScanFinding> scanSourceCode(String filename, String content) {
        List<ScanFinding> findings = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return findings;
        }

        String[] lines = content.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineNum = i + 1;

            // Check secrets
            for (PatternRule rule : SECRET_PATTERNS) {
                Matcher m = rule.pattern().matcher(line);
                if (m.find()) {
                    findings.add(new ScanFinding(rule.ruleId(), rule.description(), rule.severity(), filename, lineNum));
                }
            }

            // Check non-floating point financial rule
            Matcher floatMatcher = FINANCIAL_FLOAT_PATTERN.matcher(line);
            if (floatMatcher.find()) {
                findings.add(new ScanFinding(
                        "FIN-001",
                        "Floating-point type used for monetary amount; financial fields must use integer minor units (pesewas)",
                        FindingSeverity.HIGH,
                        filename,
                        lineNum
                ));
            }
        }
        return findings;
    }

    public List<ScanFinding> auditDependencies(List<DependencyArtifact> dependencies) {
        List<ScanFinding> findings = new ArrayList<>();
        if (dependencies == null) {
            return findings;
        }

        for (DependencyArtifact dep : dependencies) {
            for (DependencyVulnerability vuln : knownVulnerabilities) {
                if (vuln.groupId().equals(dep.groupId()) && vuln.artifactId().equals(dep.artifactId())) {
                    if (dep.version() != null && dep.version().matches(vuln.affectedVersionPattern())) {
                        FindingSeverity severity = vuln.cvssScore() >= 9.0 ? FindingSeverity.CRITICAL : FindingSeverity.HIGH;
                        findings.add(new ScanFinding(
                                vuln.cveId(),
                                "Vulnerable dependency " + dep.groupId() + ":" + dep.artifactId() + ":" + dep.version() + " - " + vuln.description() + " (CVSS " + vuln.cvssScore() + ")",
                                severity,
                                "pom.xml",
                                1
                        ));
                    }
                }
            }
        }
        return findings;
    }

    public ScanReport evaluateGate(List<ScanFinding> findings) {
        int critical = 0;
        int high = 0;
        int medium = 0;
        int low = 0;

        for (ScanFinding f : findings) {
            switch (f.severity()) {
                case CRITICAL -> critical++;
                case HIGH -> high++;
                case MEDIUM -> medium++;
                case LOW -> low++;
            }
        }

        // CI gate blocks if any CRITICAL or HIGH findings exist
        boolean passed = (critical == 0 && high == 0);
        return new ScanReport(passed, critical, high, medium, low, findings);
    }

    private record PatternRule(
            String ruleId,
            String description,
            FindingSeverity severity,
            Pattern pattern
    ) {}
}
