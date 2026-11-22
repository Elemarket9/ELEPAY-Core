package com.elepay.rails;

import com.elepay.common.ApiException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Algorithm 017: Card BIN lookup.
 * Resolves card metadata (scheme, issuer bank, card type, country) from a
 * versioned, offline data store.
 *
 * <p><strong>Mandatory Invariant:</strong> BIN metadata is strictly informational.
 * Resolving a valid BIN does NOT constitute proof of funds, account validity, or
 * cardholder authorization. Authorization must always occur through the card rail.
 */
@Service
public class CardBinLookupService {
    private static final Logger log = LoggerFactory.getLogger(CardBinLookupService.class);
    private static final String DEFAULT_VERSION = "2026.1";

    public record CardBin(
            String bin,
            String scheme,
            String cardType,
            String issuerBank,
            String countryCode,
            String currency,
            String version
    ) {}

    private final JdbcClient db;
    private final Map<String, CardBin> cache = new ConcurrentHashMap<>();

    public CardBinLookupService(JdbcClient db) {
        this.db = db;
        loadBins();
    }

    private void loadBins() {
        try {
            List<CardBin> records = db.sql("SELECT bin, scheme, card_type, issuer_bank, country_code, currency, version FROM card_bin")
                    .query((rs, i) -> new CardBin(
                            rs.getString("bin"),
                            rs.getString("scheme"),
                            rs.getString("card_type"),
                            rs.getString("issuer_bank"),
                            rs.getString("country_code"),
                            rs.getString("currency"),
                            rs.getString("version")
                    )).list();
            for (CardBin bin : records) {
                cache.put(bin.bin(), bin);
            }
            log.info("Loaded {} offline card BIN records into memory (version {})", cache.size(), DEFAULT_VERSION);
        } catch (Exception e) {
            log.warn("Could not pre-load card_bin table (will retry on lookup): {}", e.getMessage());
        }
    }

    /**
     * Resolves card metadata using the leading 6-8 digits of a PAN.
     * Prefers 8-digit match, then 6-digit match.
     */
    public Optional<CardBin> lookup(String panOrBin) {
        if (panOrBin == null) return Optional.empty();
        String cleaned = panOrBin.replaceAll("[^0-9]", "");
        if (cleaned.length() < 6) return Optional.empty();

        // Try 8-digit BIN first if available
        if (cleaned.length() >= 8) {
            String bin8 = cleaned.substring(0, 8);
            CardBin found = cache.get(bin8);
            if (found != null) return Optional.of(found);
        }

        // Try 6-digit standard BIN
        String bin6 = cleaned.substring(0, 6);
        CardBin found = cache.get(bin6);
        if (found != null) return Optional.of(found);

        // Fallback check database in case of dynamically inserted BIN
        try {
            return db.sql("""
                    SELECT bin, scheme, card_type, issuer_bank, country_code, currency, version
                    FROM card_bin WHERE bin IN (:bin6, :bin8)
                    ORDER BY length(bin) DESC LIMIT 1""")
                    .param("bin6", bin6)
                    .param("bin8", cleaned.length() >= 8 ? cleaned.substring(0, 8) : bin6)
                    .query((rs, i) -> new CardBin(
                            rs.getString("bin"),
                            rs.getString("scheme"),
                            rs.getString("card_type"),
                            rs.getString("issuer_bank"),
                            rs.getString("country_code"),
                            rs.getString("currency"),
                            rs.getString("version")
                    )).optional().map(b -> {
                        cache.put(b.bin(), b);
                        return b;
                    });
        } catch (Exception e) {
            log.warn("DB query for card BIN failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Updates or registers a new BIN entry in the offline database.
     */
    public void registerBin(CardBin bin) {
        db.sql("""
                INSERT INTO card_bin (bin, scheme, card_type, issuer_bank, country_code, currency, version, updated_at)
                VALUES (:bin, :scheme, :cardType, :bank, :country, :currency, :ver, now())
                ON CONFLICT (bin) DO UPDATE SET
                    scheme = EXCLUDED.scheme,
                    card_type = EXCLUDED.card_type,
                    issuer_bank = EXCLUDED.issuer_bank,
                    country_code = EXCLUDED.country_code,
                    currency = EXCLUDED.currency,
                    version = EXCLUDED.version,
                    updated_at = now()""")
                .param("bin", bin.bin())
                .param("scheme", bin.scheme())
                .param("cardType", bin.cardType())
                .param("bank", bin.issuerBank())
                .param("country", bin.countryCode())
                .param("currency", bin.currency())
                .param("ver", bin.version() == null ? DEFAULT_VERSION : bin.version())
                .update();
        cache.put(bin.bin(), bin);
    }

    public String activeVersion() {
        return DEFAULT_VERSION;
    }
}
