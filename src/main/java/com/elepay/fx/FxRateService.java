package com.elepay.fx;

import com.elepay.common.ApiException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Algorithm #059: Rate sourcing and blending.
 *
 * <p>Ingests FX rates from multiple external providers, validates rate freshness,
 * rejects statistical outliers based on deviation from the median, and synthesizes
 * an authoritative mid-market rate using source-quality weighted averaging.
 */
@Service
public class FxRateService {
    private static final MathContext MC = new MathContext(18, RoundingMode.HALF_UP);
    private static final BigDecimal OUTLIER_THRESHOLD = new BigDecimal("0.0200"); // 2.0% deviation from median
    private static final Duration DEFAULT_MAX_AGE = Duration.ofMinutes(15);
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    private final JdbcClient db;

    public FxRateService(JdbcClient db) {
        this.db = db;
    }

    public record RateSource(UUID id, String source, String sourceCurrency, String destinationCurrency,
                             BigDecimal bidRate, BigDecimal askRate, BigDecimal midRate,
                             BigDecimal weight, OffsetDateTime capturedAt, String status) {}

    public record BlendedRate(UUID id, String sourceCurrency, String destinationCurrency,
                              BigDecimal midRate, int spreadBps, int sourcesCount,
                              int outliersCount, OffsetDateTime calculatedAt, OffsetDateTime validUntil) {}

    /**
     * Ingests a raw rate quote from an external provider/market source.
     */
    @Transactional
    public RateSource ingestRate(String source, String sourceCurrency, String destinationCurrency,
                                 BigDecimal bidRate, BigDecimal askRate, double weight) {
        if (source == null || source.isBlank()) throw ApiException.badRequest("invalid_source", "Source provider name is required");
        if (sourceCurrency.equals(destinationCurrency)) throw ApiException.badRequest("invalid_currency_pair", "Source and destination currencies must differ");
        if (bidRate.compareTo(BigDecimal.ZERO) <= 0 || askRate.compareTo(bidRate) < 0) {
            throw ApiException.badRequest("invalid_rates", "Bid rate must be > 0 and ask rate >= bid rate");
        }
        BigDecimal midRate = bidRate.add(askRate).divide(BigDecimal.valueOf(2), MC);
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        BigDecimal w = BigDecimal.valueOf(Math.max(0.1, weight)).setScale(2, RoundingMode.HALF_UP);

        db.sql("""
                INSERT INTO fx_rate_source (id, source, source_currency, destination_currency, bid_rate, ask_rate,
                    mid_rate, weight, captured_at, status)
                VALUES (:id, :s, :sc, :dc, :bid, :ask, :mid, :w, :now, 'active')""")
                .param("id", id).param("s", source).param("sc", sourceCurrency).param("dc", destinationCurrency)
                .param("bid", bidRate).param("ask", askRate).param("mid", midRate).param("w", w).param("now", now)
                .update();

        return new RateSource(id, source, sourceCurrency, destinationCurrency, bidRate, askRate, midRate, w, now, "active");
    }

    /**
     * Synthesizes mid-market rate by fetching recent rates within {@code maxAge},
     * identifying and marking outliers (deviation > 2% from median), and computing
     * a weighted average of valid rates.
     */
    @Transactional
    public BlendedRate synthesizeRate(String sourceCurrency, String destinationCurrency, Duration maxAge) {
        Duration age = (maxAge != null && !maxAge.isNegative() && !maxAge.isZero()) ? maxAge : DEFAULT_MAX_AGE;
        OffsetDateTime cutoff = OffsetDateTime.now().minus(age);

        List<RateSource> raw = db.sql("""
                SELECT id, source, source_currency, destination_currency, bid_rate, ask_rate, mid_rate, weight,
                       captured_at, status
                FROM fx_rate_source
                WHERE source_currency = :sc AND destination_currency = :dc AND captured_at >= :cutoff
                ORDER BY captured_at DESC""")
                .param("sc", sourceCurrency).param("dc", destinationCurrency).param("cutoff", cutoff)
                .query((rs, i) -> new RateSource(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7),
                        rs.getBigDecimal(8), rs.getObject(9, OffsetDateTime.class), rs.getString(10)))
                .list();

        if (raw.isEmpty()) {
            throw ApiException.notFound("No active rates found for " + sourceCurrency + "/" + destinationCurrency + " within " + age.toMinutes() + " minutes");
        }

        // Calculate median mid rate
        List<BigDecimal> sortedMids = raw.stream().map(RateSource::midRate).sorted().toList();
        BigDecimal median;
        int size = sortedMids.size();
        if (size % 2 == 1) {
            median = sortedMids.get(size / 2);
        } else {
            median = sortedMids.get((size / 2) - 1).add(sortedMids.get(size / 2)).divide(BigDecimal.valueOf(2), MC);
        }

        // Identify outliers
        List<RateSource> validSources = new ArrayList<>();
        int outlierCount = 0;
        for (RateSource r : raw) {
            BigDecimal diff = r.midRate().subtract(median).abs();
            BigDecimal relativeDiff = diff.divide(median, MC);
            if (size >= 3 && relativeDiff.compareTo(OUTLIER_THRESHOLD) > 0) {
                // Outlier detected
                db.sql("UPDATE fx_rate_source SET status = 'outlier' WHERE id = :id").param("id", r.id()).update();
                outlierCount++;
            } else {
                validSources.add(r);
            }
        }

        // If all filtered as outliers (rare edge case), fallback to median
        if (validSources.isEmpty()) {
            validSources.addAll(raw);
            outlierCount = 0;
        }

        // Weighted mid rate synthesis
        BigDecimal totalWeight = BigDecimal.ZERO;
        BigDecimal weightedSum = BigDecimal.ZERO;
        BigDecimal totalBid = BigDecimal.ZERO;
        BigDecimal totalAsk = BigDecimal.ZERO;

        for (RateSource r : validSources) {
            totalWeight = totalWeight.add(r.weight());
            weightedSum = weightedSum.add(r.midRate().multiply(r.weight(), MC));
            totalBid = totalBid.add(r.bidRate());
            totalAsk = totalAsk.add(r.askRate());
        }

        BigDecimal blendedMid = weightedSum.divide(totalWeight, MC);
        BigDecimal avgBid = totalBid.divide(BigDecimal.valueOf(validSources.size()), MC);
        BigDecimal avgAsk = totalAsk.divide(BigDecimal.valueOf(validSources.size()), MC);

        int spreadBps = 0;
        if (avgBid.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal spread = avgAsk.subtract(avgBid).divide(blendedMid, MC).multiply(BigDecimal.valueOf(10_000));
            spreadBps = spread.setScale(0, RoundingMode.HALF_UP).intValue();
        }

        UUID blendedId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime validUntil = now.plus(DEFAULT_TTL);

        db.sql("""
                INSERT INTO fx_blended_rate (id, source_currency, destination_currency, mid_rate, spread_bps,
                    sources_count, outliers_count, calculated_at, valid_until)
                VALUES (:id, :sc, :dc, :mid, :bps, :scount, :ocount, :now, :exp)""")
                .param("id", blendedId).param("sc", sourceCurrency).param("dc", destinationCurrency)
                .param("mid", blendedMid).param("bps", spreadBps).param("scount", validSources.size())
                .param("ocount", outlierCount).param("now", now).param("exp", validUntil).update();

        return new BlendedRate(blendedId, sourceCurrency, destinationCurrency, blendedMid, spreadBps,
                validSources.size(), outlierCount, now, validUntil);
    }

    /**
     * Returns the latest valid blended mid-market rate. If expired or missing, synthesizes a fresh one.
     */
    public BlendedRate latestBlendedRate(String sourceCurrency, String destinationCurrency) {
        var existing = db.sql("""
                SELECT id, source_currency, destination_currency, mid_rate, spread_bps, sources_count,
                       outliers_count, calculated_at, valid_until
                FROM fx_blended_rate
                WHERE source_currency = :sc AND destination_currency = :dc AND valid_until > now()
                ORDER BY calculated_at DESC LIMIT 1""")
                .param("sc", sourceCurrency).param("dc", destinationCurrency)
                .query((rs, i) -> new BlendedRate(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getBigDecimal(4), rs.getInt(5), rs.getInt(6), rs.getInt(7),
                        rs.getObject(8, OffsetDateTime.class), rs.getObject(9, OffsetDateTime.class)))
                .optional();

        return existing.orElseGet(() -> synthesizeRate(sourceCurrency, destinationCurrency, DEFAULT_MAX_AGE));
    }
}
