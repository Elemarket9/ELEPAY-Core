package com.elepay.enterprise.refund;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refund liabilities are authoritative database records. Never return an
 * in-memory substitute when PostgreSQL is unavailable or a write fails.
 */
@Service
public class EnterpriseRefundLiabilityTracker {
    private final JdbcClient db;

    public EnterpriseRefundLiabilityTracker(JdbcClient db) {
        this.db = db;
    }

    private JdbcClient requireDb() {
        if (db == null) {
            throw new IllegalStateException("DATABASE_NOT_CONFIGURED: refund liability ledger unavailable");
        }
        return db;
    }

    @Transactional
    public RefundLiabilityRecord recordLiability(UUID enterpriseId, UUID merchantId, UUID refundId,
                                                 long amountPesewas) {
        if (amountPesewas <= 0) {
            throw new IllegalArgumentException("Liability amount must be positive: " + amountPesewas);
        }
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        int inserted = requireDb().sql("""
                INSERT INTO enterprise_refund_liability
                    (id, enterprise_id, merchant_id, refund_id, amount_pesewas, settled, created_at)
                VALUES (:id, :eid, :mid, :rid, :amt, false, :now)
                """)
                .param("id", id)
                .param("eid", enterpriseId)
                .param("mid", merchantId)
                .param("rid", refundId)
                .param("amt", amountPesewas)
                .param("now", now).update();
        if (inserted != 1) {
            throw new IllegalStateException("REFUND_LIABILITY_NOT_RECORDED");
        }
        return new RefundLiabilityRecord(id, enterpriseId, merchantId, refundId,
                amountPesewas, false, now, null);
    }

    public long getOutstandingLiability(UUID enterpriseId, UUID merchantId) {
        Long amount = requireDb().sql("""
                SELECT coalesce(sum(amount_pesewas), 0)
                FROM enterprise_refund_liability
                WHERE enterprise_id = :eid AND merchant_id = :mid AND settled = false
                """)
                .param("eid", enterpriseId).param("mid", merchantId)
                .query(Long.class).single();
        if (amount == null) {
            throw new IllegalStateException("REFUND_LIABILITY_BALANCE_UNAVAILABLE");
        }
        return amount;
    }

    /**
     * Lock unsettled liabilities and mark whole records settled within the
     * maximum offset, all in one transaction. Do not acknowledge uncommitted
     * or unpersisted offsets.
     */
    @Transactional
    public long settleLiabilities(UUID enterpriseId, UUID merchantId, long maxAmountToSettle) {
        if (maxAmountToSettle <= 0) return 0;
        record Liability(UUID id, long amount) {}
        List<Liability> eligible = requireDb().sql("""
                SELECT id, amount_pesewas
                FROM enterprise_refund_liability
                WHERE enterprise_id = :eid AND merchant_id = :mid AND settled = false
                ORDER BY created_at, id
                FOR UPDATE
                """)
                .param("eid", enterpriseId).param("mid", merchantId)
                .query((rs, row) -> new Liability(
                        rs.getObject("id", UUID.class), rs.getLong("amount_pesewas")))
                .list();
        long remaining = maxAmountToSettle;
        long settled = 0;
        for (Liability liability : eligible) {
            if (liability.amount() > remaining) continue;
            int updated = requireDb().sql("""
                    UPDATE enterprise_refund_liability
                    SET settled = true, settled_at = now()
                    WHERE id = :id AND settled = false
                    """).param("id", liability.id()).update();
            if (updated != 1) {
                throw new IllegalStateException("REFUND_LIABILITY_SETTLEMENT_CONFLICT");
            }
            settled = Math.addExact(settled, liability.amount());
            remaining -= liability.amount();
            if (remaining == 0) break;
        }
        return settled;
    }
}
