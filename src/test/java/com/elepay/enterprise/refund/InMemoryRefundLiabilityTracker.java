package com.elepay.enterprise.refund;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** In-memory refund-liability fixture used only by isolated unit tests. Never packaged. */
final class InMemoryRefundLiabilityTracker extends EnterpriseRefundLiabilityTracker {
    private final List<RefundLiabilityRecord> records = new ArrayList<>();

    InMemoryRefundLiabilityTracker() {
        super(null);
    }

    @Override
    public synchronized RefundLiabilityRecord recordLiability(
            UUID enterpriseId, UUID merchantId, UUID refundId, long amountPesewas) {
        if (amountPesewas <= 0) {
            throw new IllegalArgumentException("Liability amount must be positive");
        }
        var record = new RefundLiabilityRecord(UUID.randomUUID(), enterpriseId, merchantId,
                refundId, amountPesewas, false, OffsetDateTime.now(), null);
        records.add(record);
        return record;
    }

    @Override
    public synchronized long getOutstandingLiability(UUID enterpriseId, UUID merchantId) {
        return records.stream().filter(r -> r.enterpriseId().equals(enterpriseId)
                && r.merchantId().equals(merchantId) && !r.settled())
                .mapToLong(RefundLiabilityRecord::amountPesewas).sum();
    }

    @Override
    public synchronized long settleLiabilities(UUID enterpriseId, UUID merchantId, long maxAmountToSettle) {
        if (maxAmountToSettle <= 0) return 0;
        long remaining = maxAmountToSettle;
        long total = 0;
        for (int i = 0; i < records.size(); i++) {
            RefundLiabilityRecord r = records.get(i);
            if (!r.enterpriseId().equals(enterpriseId) || !r.merchantId().equals(merchantId)
                    || r.settled() || r.amountPesewas() > remaining) continue;
            records.set(i, new RefundLiabilityRecord(
                    r.liabilityId(), r.enterpriseId(), r.merchantId(), r.refundId(),
                    r.amountPesewas(), true, r.createdAt(), OffsetDateTime.now()));
            total += r.amountPesewas();
            remaining -= r.amountPesewas();
            if (remaining == 0) break;
        }
        return total;
    }
}
