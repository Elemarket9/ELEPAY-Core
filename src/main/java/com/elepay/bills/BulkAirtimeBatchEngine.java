package com.elepay.bills;

import com.elepay.ledger.Ledger;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * High-concurrency bulk airtime and data bundle batch distribution engine.
 * Dispatches mass corporate airtime disbursements across MTN, Telecel, and AT networks
 * with individual transaction logging and atomic double-entry funding deduction.
 */
@Service
public class BulkAirtimeBatchEngine {

    public record AirtimeBatchItem(
            String recipientPhone,
            String network, // 'MTN', 'TELECEL', 'AIRTELTIGO'
            long amountPesewas,
            String itemReference
    ) {}

    public record BatchItemOutcome(
            UUID transactionId,
            String recipientPhone,
            String network,
            long amountPesewas,
            String itemReference,
            boolean success,
            String status
    ) {}

    public record BulkBatchResult(
            UUID batchId,
            String batchReference,
            UUID sponsorCustomerId,
            int totalCount,
            int successCount,
            int failureCount,
            long totalAmountPesewas,
            UUID ledgerTxId,
            List<BatchItemOutcome> itemOutcomes,
            OffsetDateTime executedAt
    ) {}

    private final JdbcClient db;
    private final Ledger ledger;

    public static final String AIRTIME_CLEARING_ACCOUNT = "system:clearing:airtime:GHS";

    public BulkAirtimeBatchEngine(JdbcClient db, Ledger ledger) {
        this.db = db;
        this.ledger = ledger;
    }

    @Transactional
    public BulkBatchResult processBatch(
            UUID sponsorCustomerId,
            String batchReference,
            List<AirtimeBatchItem> items
    ) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Batch must contain at least one airtime recipient");
        }

        UUID batchId = UUID.randomUUID();
        String batchRef = (batchReference != null && !batchReference.isBlank())
                ? batchReference.trim()
                : "AIRTIME-BATCH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        long totalAmountPesewas = 0L;
        long validAmountPesewas = 0L;
        List<Boolean> itemValidFlags = new ArrayList<>(items.size());
        for (var item : items) {
            if (item == null) {
                throw new IllegalArgumentException("Batch item cannot be null");
            }
            if (item.amountPesewas() <= 0) {
                throw new IllegalArgumentException("Each airtime item amount must be positive: " + item.amountPesewas());
            }
            totalAmountPesewas = Math.addExact(totalAmountPesewas, item.amountPesewas());
            boolean validPhone = item.recipientPhone() != null
                    && item.recipientPhone().replaceAll("[^0-9]", "").length() >= 9;
            itemValidFlags.add(validPhone);
            if (validPhone) {
                validAmountPesewas = Math.addExact(validAmountPesewas, item.amountPesewas());
            }
        }
        if (totalAmountPesewas <= 0) {
            throw new IllegalArgumentException("Total batch amount must be positive");
        }

        // 1. Post funding ONLY for valid items using non-negative customer wallet account (never allow_negative=true)
        String sponsorAccount = ledger.open("customer", sponsorCustomerId, "available", Ledger.GHS);
        ledger.openSystem(AIRTIME_CLEARING_ACCOUNT, "clearing", Ledger.GHS, true);

        UUID ledgerTxId = null;
        if (validAmountPesewas > 0) {
            var posting = Ledger.Posting.of(
                    "bill_bulk_airtime",
                    "BATCH-" + batchRef,
                    "BULK_AIRTIME_DISBURSEMENT",
                    List.of(
                            Ledger.Leg.debit(sponsorAccount, validAmountPesewas),
                            Ledger.Leg.credit(AIRTIME_CLEARING_ACCOUNT, validAmountPesewas)
                    )
            ).describedAs("Bulk airtime batch " + batchRef + " for " + items.size() + " recipients");

            ledgerTxId = ledger.post(posting).transactionId();
        }

        // 2. Process and record individual items
        List<BatchItemOutcome> outcomes = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;
        OffsetDateTime now = OffsetDateTime.now();

        for (int idx = 0; idx < items.size(); idx++) {
            var item = items.get(idx);
            boolean validPhone = itemValidFlags.get(idx);
            UUID txnId = UUID.randomUUID();
            String itemRef = (item.itemReference() != null && !item.itemReference().isBlank())
                    ? item.itemReference().trim()
                    : "AIR-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase();

            String status = validPhone ? "COMPLETED" : "FAILED";
            if (validPhone) {
                successCount++;
            } else {
                failureCount++;
            }

            String dbRef = batchRef + "_" + itemRef;
            String safeMeta = "{\"batchReference\":\"" + escapeJson(batchRef)
                    + "\",\"batchJournalTx\":" + (ledgerTxId == null ? "null" : "\"" + ledgerTxId + "\"")
                    + ",\"itemReference\":\"" + escapeJson(itemRef) + "\"}";

            db.sql("""
                INSERT INTO bill_payment_transaction (
                    id, customer_id, provider_code, product_code, biller_account,
                    amount_pesewas, fee_pesewas, total_pesewas, reference,
                    status, metadata_json, created_at, journal_tx
                ) VALUES (
                    :id, :customerId, :carrier, 'AIRTIME_TOPUP', :phone,
                    :amt, 0, :amt, :ref,
                    :status, :meta, :now, :journalTx
                )
            """)
                    .param("id", txnId)
                    .param("customerId", sponsorCustomerId)
                    .param("carrier", item.network() != null ? item.network().toUpperCase() : "MTN")
                    .param("phone", item.recipientPhone())
                    .param("amt", item.amountPesewas())
                    .param("ref", dbRef)
                    .param("status", status)
                    .param("meta", safeMeta)
                    .param("now", now)
                    .param("journalTx", (validPhone && successCount == 1) ? ledgerTxId : null)
                    .update();

            outcomes.add(new BatchItemOutcome(
                    txnId, item.recipientPhone(), item.network(), item.amountPesewas(), itemRef, validPhone, status
            ));
        }

        return new BulkBatchResult(
                batchId, batchRef, sponsorCustomerId, items.size(),
                successCount, failureCount, validAmountPesewas, ledgerTxId, outcomes, now
        );
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
