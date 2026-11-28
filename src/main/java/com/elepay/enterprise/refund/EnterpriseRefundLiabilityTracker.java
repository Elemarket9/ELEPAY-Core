package com.elepay.enterprise.refund;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks merchant refund liabilities and manages settlement balance adjustments.
 */
@Service
public class EnterpriseRefundLiabilityTracker {

    private final JdbcClient db;

    // Concurrent in-memory index for fast lookup: (enterpriseId:merchantId) -> list of liabilities
    private final Map<String, List<RefundLiabilityRecord>> inMemoryLiabilities = new ConcurrentHashMap<>();

    public EnterpriseRefundLiabilityTracker(JdbcClient db) {
        this.db = db;
    }

    /**
     * Records a new refund liability for a sub-merchant.
     */
    @Transactional
    public RefundLiabilityRecord recordLiability(UUID enterpriseId, UUID merchantId, UUID refundId, long amountPesewas) {
        if (amountPesewas <= 0) {
            throw new IllegalArgumentException("Liability amount must be positive: " + amountPesewas);
        }

        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();

        if (db != null) {
            db.sql("""
                INSERT INTO enterprise_refund_liability (id, enterprise_id, merchant_id, refund_id, amount_pesewas, settled, created_at)
                VALUES (:id, :eid, :mid, :rid, :amt, false, :now)
                """)
                .param("id", id)
                .param("eid", enterpriseId)
                .param("mid", merchantId)
                .param("rid", refundId)
                .param("amt", amountPesewas)
                .param("now", now)
                .update();

            return new RefundLiabilityRecord(
                    id, enterpriseId, merchantId, refundId, amountPesewas, false, now, null
            );
        }

        RefundLiabilityRecord record = new RefundLiabilityRecord(
                id,
                enterpriseId,
                merchantId,
                refundId,
                amountPesewas,
                false,
                now,
                null
        );

        String key = cacheKey(enterpriseId, merchantId);
        inMemoryLiabilities.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>())).add(record);

        return record;
    }

    /**
     * Calculates the total unsettled refund liabilities for a sub-merchant in minor units.
     */
    public long getOutstandingLiability(UUID enterpriseId, UUID merchantId) {
        if (db != null) {
            Long total = db.sql("""
                SELECT coalesce(sum(amount_pesewas), 0)
                FROM enterprise_refund_liability
                WHERE enterprise_id = :eid AND merchant_id = :mid AND settled = false
                """)
                .param("eid", enterpriseId)
                .param("mid", merchantId)
                .query(Long.class)
                .single();
            return total != null ? total : 0L;
        }

        String key = cacheKey(enterpriseId, merchantId);
        List<RefundLiabilityRecord> records = inMemoryLiabilities.get(key);
        if (records == null || records.isEmpty()) return 0L;

        synchronized (records) {
            return records.stream()
                    .filter(r -> !r.settled())
                    .mapToLong(RefundLiabilityRecord::amountPesewas)
                    .reduce(0L, Math::addExact);
        }
    }

    /**
     * Offsets and marks unsettled liabilities as settled up to a given maximum cumulative amount.
     * Returns the exact total amount settled.
     */
    @Transactional
    public long settleLiabilities(UUID enterpriseId, UUID merchantId, long maxAmountToSettle) {
        if (maxAmountToSettle <= 0) return 0L;

        if (db != null) {
            record PendingRow(UUID id, long amountPesewas) {}
            List<PendingRow> rows = db.sql("""
                SELECT id, amount_pesewas
                FROM enterprise_refund_liability
                WHERE enterprise_id = :eid AND merchant_id = :mid AND settled = false
                ORDER BY created_at ASC, id ASC
                FOR UPDATE
                """)
                .param("eid", enterpriseId)
                .param("mid", merchantId)
                .query((rs, i) -> new PendingRow(rs.getObject("id", UUID.class), rs.getLong("amount_pesewas")))
                .list();

            long remaining = maxAmountToSettle;
            long totalSettled = 0L;
            List<UUID> selectedIds = new ArrayList<>();
            for (PendingRow row : rows) {
                if (row.amountPesewas() > 0 && row.amountPesewas() <= remaining) {
                    selectedIds.add(row.id());
                    remaining -= row.amountPesewas();
                    totalSettled = Math.addExact(totalSettled, row.amountPesewas());
                    if (remaining == 0) break;
                }
            }
            for (UUID id : selectedIds) {
                db.sql("""
                    UPDATE enterprise_refund_liability
                    SET settled = true, settled_at = now()
                    WHERE id = :id AND settled = false
                    """)
                    .param("id", id)
                    .update();
            }
            return totalSettled;
        }

        long remaining = maxAmountToSettle;
        long totalSettled = 0L;
        String key = cacheKey(enterpriseId, merchantId);
        List<RefundLiabilityRecord> records = inMemoryLiabilities.get(key);

        if (records != null) {
            synchronized (records) {
                for (int i = 0; i < records.size() && remaining > 0; i++) {
                    RefundLiabilityRecord rec = records.get(i);
                    if (!rec.settled() && rec.amountPesewas() <= remaining) {
                        records.set(i, new RefundLiabilityRecord(
                                rec.liabilityId(),
                                rec.enterpriseId(),
                                rec.merchantId(),
                                rec.refundId(),
                                rec.amountPesewas(),
                                true,
                                rec.createdAt(),
                                OffsetDateTime.now()
                        ));
                        remaining -= rec.amountPesewas();
                        totalSettled = Math.addExact(totalSettled, rec.amountPesewas());
                    }
                }
            }
        }

        return totalSettled;
    }

    private static String cacheKey(UUID enterpriseId, UUID merchantId) {
        return enterpriseId + ":" + merchantId;
    }
}
