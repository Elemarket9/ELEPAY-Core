package com.elepay.compliance;

import com.elepay.common.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;

import org.springframework.stereotype.Service;

/**
 * Fuzzy sanctions screening engine (Algorithm 042).
 * Combines exact normalized matching, Jaro-Winkler string distance, and Soundex phonetic encoding
 * against international (UN, OFAC) and domestic (FIC Ghana) sanctions lists with versioning and analyst review.
 */
@Service
public class SanctionsScreeningService implements com.elepay.common.SanctionsScreener {

    @Override
    public boolean isSanctionedName(String candidateName) {
        return screenName(candidateName).stream().anyMatch(m ->
                m.matchType() == MatchType.EXACT || m.similarityScore() >= 0.95);
    }

    public enum MatchType {
        EXACT,
        JARO_WINKLER,
        SOUNDEX
    }

    public enum AlertDisposition {
        PENDING_REVIEW,
        CONFIRMED_MATCH,
        FALSE_POSITIVE
    }

    public record SanctionEntry(
            UUID id,
            String listCode,
            String version,
            String entityName,
            String entityType,
            String soundexCode,
            String nationality,
            String remarks,
            boolean active
    ) {}

    public record ScreeningMatch(
            SanctionEntry matchedEntry,
            MatchType matchType,
            double similarityScore
    ) {}

    public record ScreeningAlert(
            UUID alertId,
            UUID customerId,
            String queryName,
            UUID matchedListId,
            MatchType matchType,
            double similarityScore,
            AlertDisposition disposition,
            UUID reviewerId,
            String notes,
            Instant createdAt,
            Instant resolvedAt
    ) {}

    private final JdbcClient db;
    private final Map<UUID, SanctionEntry> sanctionsRegistry = new ConcurrentHashMap<>();
    private final Map<UUID, ScreeningAlert> alertRegistry = new ConcurrentHashMap<>();
    private static final double JARO_WINKLER_THRESHOLD = 0.85;

    public SanctionsScreeningService(JdbcClient db) {
        this.db = db;
    }

    public void addSanctionEntry(SanctionEntry entry) {
        sanctionsRegistry.put(entry.id(), entry);
        if (db != null) {
            db.sql("""
                INSERT INTO sanctions_list
                (id, list_code, version, entity_name, entity_type, soundex_code, nationality, remarks, active, published_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (id) DO UPDATE SET
                    list_code = EXCLUDED.list_code,
                    version = EXCLUDED.version,
                    entity_name = EXCLUDED.entity_name,
                    entity_type = EXCLUDED.entity_type,
                    soundex_code = EXCLUDED.soundex_code,
                    nationality = EXCLUDED.nationality,
                    remarks = EXCLUDED.remarks,
                    active = EXCLUDED.active
                """)
                .params(entry.id(), entry.listCode(), entry.version(), entry.entityName(), entry.entityType(), entry.soundexCode(), entry.nationality(), entry.remarks(), entry.active())
                .update();
        }
    }

    public List<ScreeningMatch> screenName(String candidateName) {
        if (candidateName == null || candidateName.trim().isEmpty()) {
            return List.of();
        }

        String normCandidate = normalize(candidateName);
        String candidateSoundex = computeSoundex(normCandidate);
        List<ScreeningMatch> matches = new ArrayList<>();

        Map<UUID, SanctionEntry> activeEntries = new java.util.LinkedHashMap<>();
        if (db != null) {
            db.sql("""
                    SELECT id, list_code, version, entity_name, entity_type, soundex_code, nationality, remarks, active
                    FROM sanctions_list
                    WHERE active = true""")
                    .query((rs, i) -> new SanctionEntry(
                            rs.getObject("id", UUID.class),
                            rs.getString("list_code"),
                            rs.getString("version"),
                            rs.getString("entity_name"),
                            rs.getString("entity_type"),
                            rs.getString("soundex_code"),
                            rs.getString("nationality"),
                            rs.getString("remarks"),
                            rs.getBoolean("active")
                    ))
                    .list()
                    .forEach(e -> activeEntries.put(e.id(), e));
        }
        sanctionsRegistry.values().stream()
                .filter(SanctionEntry::active)
                .forEach(e -> activeEntries.putIfAbsent(e.id(), e));

        for (SanctionEntry entry : activeEntries.values()) {
            if (!entry.active()) continue;

            String normEntry = normalize(entry.entityName());

            // 1. Exact match
            if (normCandidate.equals(normEntry)) {
                matches.add(new ScreeningMatch(entry, MatchType.EXACT, 1.0));
                continue;
            }

            // 2. Jaro-Winkler similarity
            double jwScore = computeJaroWinkler(normCandidate, normEntry);
            if (jwScore >= JARO_WINKLER_THRESHOLD) {
                matches.add(new ScreeningMatch(entry, MatchType.JARO_WINKLER, jwScore));
                continue;
            }

            // 3. Soundex phonetic match
            if (candidateSoundex.equals(entry.soundexCode())) {
                double secondaryJw = computeJaroWinkler(normCandidate, normEntry);
                if (secondaryJw >= 0.70) {
                    matches.add(new ScreeningMatch(entry, MatchType.SOUNDEX, secondaryJw));
                }
            }
        }

        return matches;
    }

    public ScreeningAlert screenAndAlert(UUID customerId, String candidateName) {
        List<ScreeningMatch> matches = screenName(candidateName);
        if (matches.isEmpty()) {
            return null;
        }

        // Pick highest match
        ScreeningMatch bestMatch = matches.stream()
                .max((m1, m2) -> Double.compare(m1.similarityScore(), m2.similarityScore()))
                .orElse(matches.get(0));

        UUID alertId = UUID.randomUUID();
        Instant now = Instant.now();
        ScreeningAlert alert = new ScreeningAlert(
                alertId,
                customerId,
                candidateName,
                bestMatch.matchedEntry().id(),
                bestMatch.matchType(),
                bestMatch.similarityScore(),
                AlertDisposition.PENDING_REVIEW,
                null,
                null,
                now,
                null
        );

        alertRegistry.put(alertId, alert);

        if (db != null) {
            db.sql("""
                INSERT INTO sanctions_screening_alert
                (id, customer_id, query_name, matched_list_id, match_type, similarity_score, disposition, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params(alert.alertId(), alert.customerId(), alert.queryName(), alert.matchedListId(), alert.matchType().name(), alert.similarityScore(), alert.disposition().name(), java.sql.Timestamp.from(alert.createdAt()))
                .update();
        }

        return alert;
    }

    public ScreeningAlert resolveAlert(UUID alertId, UUID reviewerId, AlertDisposition disposition, String notes) {
        ScreeningAlert existing = alertRegistry.get(alertId);
        if (existing == null && db != null) {
            existing = db.sql("""
                    SELECT id, customer_id, query_name, matched_list_id, match_type, similarity_score,
                           disposition, reviewer_id, disposition_notes, created_at, resolved_at
                    FROM sanctions_screening_alert
                    WHERE id = :id""")
                    .param("id", alertId)
                    .query((rs, i) -> new ScreeningAlert(
                            rs.getObject("id", UUID.class),
                            rs.getObject("customer_id", UUID.class),
                            rs.getString("query_name"),
                            rs.getObject("matched_list_id", UUID.class),
                            MatchType.valueOf(rs.getString("match_type")),
                            rs.getDouble("similarity_score"),
                            AlertDisposition.valueOf(rs.getString("disposition")),
                            rs.getObject("reviewer_id", UUID.class),
                            rs.getString("disposition_notes"),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getTimestamp("resolved_at") != null ? rs.getTimestamp("resolved_at").toInstant() : null
                    ))
                    .optional()
                    .orElse(null);
        }
        if (existing == null) {
            throw ApiException.notFound("Sanctions alert not found: " + alertId);
        }
        if (disposition == AlertDisposition.PENDING_REVIEW) {
            throw ApiException.badRequest("invalid_disposition", "Disposition must be CONFIRMED_MATCH or FALSE_POSITIVE");
        }

        Instant now = Instant.now();
        ScreeningAlert resolved = new ScreeningAlert(
                existing.alertId(),
                existing.customerId(),
                existing.queryName(),
                existing.matchedListId(),
                existing.matchType(),
                existing.similarityScore(),
                disposition,
                reviewerId,
                notes,
                existing.createdAt(),
                now
        );

        if (db != null) {
            db.sql("""
                UPDATE sanctions_screening_alert
                SET disposition = ?, reviewer_id = ?, disposition_notes = ?, resolved_at = ?
                WHERE id = ?
                """)
                .params(disposition.name(), reviewerId, notes, java.sql.Timestamp.from(now), alertId)
                .update();
        }

        alertRegistry.put(alertId, resolved);
        return resolved;
    }

    public static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public static String computeSoundex(String s) {
        if (s == null || s.isEmpty()) return "0000";
        String clean = s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
        if (clean.isEmpty()) return "0000";

        char firstChar = clean.charAt(0);
        StringBuilder out = new StringBuilder();
        out.append(firstChar);

        char prevDigit = getSoundexDigit(firstChar);

        for (int i = 1; i < clean.length() && out.length() < 4; i++) {
            char c = clean.charAt(i);
            char digit = getSoundexDigit(c);
            if (digit != '0' && digit != prevDigit) {
                out.append(digit);
            }
            if (digit != '0') {
                prevDigit = digit;
            }
        }

        while (out.length() < 4) {
            out.append('0');
        }
        return out.toString();
    }

    private static char getSoundexDigit(char c) {
        return switch (c) {
            case 'B', 'F', 'P', 'V' -> '1';
            case 'C', 'G', 'J', 'K', 'Q', 'S', 'X', 'Z' -> '2';
            case 'D', 'T' -> '3';
            case 'L' -> '4';
            case 'M', 'N' -> '5';
            case 'R' -> '6';
            default -> '0';
        };
    }

    public static double computeJaroWinkler(String s1, String s2) {
        if (s1.equals(s2)) return 1.0;
        int len1 = s1.length();
        int len2 = s2.length();
        if (len1 == 0 || len2 == 0) return 0.0;

        int matchDistance = Math.max(len1, len2) / 2 - 1;
        boolean[] s1Matches = new boolean[len1];
        boolean[] s2Matches = new boolean[len2];
        int matches = 0;

        for (int i = 0; i < len1; i++) {
            int start = Math.max(0, i - matchDistance);
            int end = Math.min(i + matchDistance + 1, len2);
            for (int j = start; j < end; j++) {
                if (s2Matches[j]) continue;
                if (s1.charAt(i) != s2.charAt(j)) continue;
                s1Matches[i] = true;
                s2Matches[j] = true;
                matches++;
                break;
            }
        }

        if (matches == 0) return 0.0;

        double transpositions = 0;
        int k = 0;
        for (int i = 0; i < len1; i++) {
            if (!s1Matches[i]) continue;
            while (!s2Matches[k]) k++;
            if (s1.charAt(i) != s2.charAt(k)) {
                transpositions++;
            }
            k++;
        }

        double jaro = ((matches / (double) len1)
                + (matches / (double) len2)
                + ((matches - transpositions / 2.0) / matches)) / 3.0;

        // Winkler prefix bonus (up to 4 matching initial chars * 0.1)
        int prefix = 0;
        for (int i = 0; i < Math.min(4, Math.min(len1, len2)); i++) {
            if (s1.charAt(i) == s2.charAt(i)) prefix++;
            else break;
        }

        return jaro + (prefix * 0.1 * (1.0 - jaro));
    }
}
