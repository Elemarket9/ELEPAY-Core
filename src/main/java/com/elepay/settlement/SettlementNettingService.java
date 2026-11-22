package com.elepay.settlement;

import com.elepay.common.ApiException;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Algorithm 055: Settlement Netting.
 *
 * <p>Computes bilateral and multilateral netting calculations across merchants,
 * payment rails, and platform accounts within strict legal entity and currency boundaries.
 * Preserves a fully traceable gross transaction audit trail while minimizing
 * the number and volume of external settlement transfers.
 *
 * <p>Financial Invariants:
 * <ul>
 *   <li>All monetary values use integer minor units with checked arithmetic ({@link Math#addExact}).</li>
 *   <li>Netting never crosses legal entity or currency boundaries.</li>
 *   <li>Multilateral zero-sum invariant: &sum; netPosition = 0 across all participants.</li>
 *   <li>Every net settlement transfer links back to its constituent gross audit records.</li>
 * </ul>
 */
@Service
public class SettlementNettingService {

    public enum NettingType {
        BILATERAL,
        MULTILATERAL
    }

    /**
     * Individual gross transaction constituting an obligation between two parties.
     */
    public record GrossTransaction(
            UUID id,
            String reference,
            String legalEntity,
            String currency,
            String debtorId,
            String creditorId,
            long amount,
            String transactionType,
            OffsetDateTime timestamp
    ) {
        public GrossTransaction {
            if (id == null) id = UUID.randomUUID();
            if (reference == null || reference.isBlank()) {
                throw ApiException.badRequest("invalid_reference", "Transaction reference is required");
            }
            if (legalEntity == null || legalEntity.isBlank()) {
                throw ApiException.badRequest("invalid_legal_entity", "Legal entity boundary is required");
            }
            if (currency == null || currency.isBlank()) {
                throw ApiException.badRequest("invalid_currency", "Currency boundary is required");
            }
            if (debtorId == null || debtorId.isBlank() || creditorId == null || creditorId.isBlank()) {
                throw ApiException.badRequest("invalid_parties", "Debtor and creditor IDs are required");
            }
            if (debtorId.equals(creditorId)) {
                throw ApiException.badRequest("self_obligation", "Debtor and creditor cannot be the same entity: " + debtorId);
            }
            if (amount <= 0) {
                throw ApiException.badRequest("invalid_amount", "Gross transaction amount must be positive: " + amount);
            }
            if (timestamp == null) timestamp = OffsetDateTime.now();
        }
    }

    /**
     * Net position of an individual participant within a netting cycle.
     */
    public record ParticipantPosition(
            String participantId,
            String legalEntity,
            String currency,
            long grossPayable,
            long grossReceivable,
            long netAmount,
            int transactionCount
    ) {
        public boolean isNetDebtor() {
            return netAmount < 0;
        }

        public boolean isNetCreditor() {
            return netAmount > 0;
        }

        public boolean isBalanced() {
            return netAmount == 0;
        }
    }

    /**
     * Bilateral netting outcome between exactly two counterparties.
     */
    public record BilateralNettingResult(
            UUID nettingId,
            String legalEntity,
            String currency,
            String partyA,
            String partyB,
            long grossAtoB,
            long grossBtoA,
            long totalGrossVolume,
            long netAmount,
            String netDebtor,
            String netCreditor,
            long netSettlementAmount,
            long nettingSavings,
            double nettingEfficiencyPct,
            List<GrossTransaction> grossRecords
    ) {}

    /**
     * Optimized transfer instruction generated to clear multilateral obligations.
     */
    public record NetSettlementTransfer(
            UUID transferId,
            String legalEntity,
            String currency,
            String debtorId,
            String creditorId,
            long amount
    ) {}

    /**
     * Complete multilateral netting cycle batch.
     */
    public record MultilateralNettingBatch(
            UUID batchId,
            String legalEntity,
            String currency,
            OffsetDateTime calculationTime,
            long totalGrossVolume,
            long totalNetSettlementVolume,
            long nettingSavings,
            double nettingEfficiencyPct,
            int grossTransactionCount,
            int settlementTransferCount,
            Map<String, ParticipantPosition> participantPositions,
            List<NetSettlementTransfer> settlementTransfers,
            List<GrossTransaction> auditRecords
    ) {}

    /**
     * Calculates bilateral netting between two counterparties within a single currency and legal entity.
     */
    public BilateralNettingResult calculateBilateralNetting(
            String legalEntity,
            String currency,
            String partyA,
            String partyB,
            List<GrossTransaction> transactions
    ) {
        if (partyA == null || partyB == null || partyA.equals(partyB)) {
            throw ApiException.badRequest("invalid_counterparties", "Two distinct counterparties are required");
        }
        if (transactions == null || transactions.isEmpty()) {
            return new BilateralNettingResult(
                    UUID.randomUUID(), legalEntity, currency, partyA, partyB,
                    0L, 0L, 0L, 0L, null, null, 0L, 0L, 0.0, List.of()
            );
        }

        long grossAtoB = 0L;
        long grossBtoA = 0L;
        List<GrossTransaction> relevant = new ArrayList<>();

        for (GrossTransaction tx : transactions) {
            validateBoundaries(tx, legalEntity, currency);

            if (tx.debtorId().equals(partyA) && tx.creditorId().equals(partyB)) {
                grossAtoB = Math.addExact(grossAtoB, tx.amount());
                relevant.add(tx);
            } else if (tx.debtorId().equals(partyB) && tx.creditorId().equals(partyA)) {
                grossBtoA = Math.addExact(grossBtoA, tx.amount());
                relevant.add(tx);
            } else {
                throw ApiException.badRequest("unrelated_transaction",
                        "Transaction " + tx.reference() + " involves parties outside " + partyA + " and " + partyB);
            }
        }

        long totalGross = Math.addExact(grossAtoB, grossBtoA);
        long netAmount = grossAtoB - grossBtoA;
        String netDebtor = null;
        String netCreditor = null;
        long netSettlement = Math.abs(netAmount);

        if (netAmount > 0) {
            netDebtor = partyA;
            netCreditor = partyB;
        } else if (netAmount < 0) {
            netDebtor = partyB;
            netCreditor = partyA;
        }

        long savings = totalGross - netSettlement;
        double efficiencyPct = totalGross > 0 ? ((double) savings / totalGross) * 100.0 : 0.0;

        return new BilateralNettingResult(
                UUID.randomUUID(),
                legalEntity,
                currency,
                partyA,
                partyB,
                grossAtoB,
                grossBtoA,
                totalGross,
                netAmount,
                netDebtor,
                netCreditor,
                netSettlement,
                savings,
                efficiencyPct,
                Collections.unmodifiableList(relevant)
        );
    }

    /**
     * Calculates multilateral netting across multiple counterparties within a single currency and legal entity.
     * Enforces the zero-sum balance invariant and computes the minimal set of settlement transfers.
     */
    public MultilateralNettingBatch calculateMultilateralNetting(
            String legalEntity,
            String currency,
            List<GrossTransaction> transactions
    ) {
        if (transactions == null || transactions.isEmpty()) {
            return new MultilateralNettingBatch(
                    UUID.randomUUID(), legalEntity, currency, OffsetDateTime.now(),
                    0L, 0L, 0L, 0.0, 0, 0,
                    Map.of(), List.of(), List.of()
            );
        }

        // Validate boundaries and aggregate gross volumes
        long totalGross = 0L;
        Map<String, Long> grossPayables = new HashMap<>();
        Map<String, Long> grossReceivables = new HashMap<>();
        Map<String, Integer> txCounts = new HashMap<>();

        for (GrossTransaction tx : transactions) {
            validateBoundaries(tx, legalEntity, currency);
            totalGross = Math.addExact(totalGross, tx.amount());

            grossPayables.merge(tx.debtorId(), tx.amount(), Math::addExact);
            grossReceivables.merge(tx.creditorId(), tx.amount(), Math::addExact);

            txCounts.merge(tx.debtorId(), 1, Integer::sum);
            txCounts.merge(tx.creditorId(), 1, Integer::sum);
        }

        // Collect all distinct participants
        Set<String> allParticipants = new HashSet<>();
        allParticipants.addAll(grossPayables.keySet());
        allParticipants.addAll(grossReceivables.keySet());

        // Calculate net position per participant
        Map<String, ParticipantPosition> positions = new HashMap<>();
        long netSum = 0L;

        for (String p : allParticipants) {
            long payable = grossPayables.getOrDefault(p, 0L);
            long receivable = grossReceivables.getOrDefault(p, 0L);
            long net = receivable - payable;
            netSum = Math.addExact(netSum, net);

            positions.put(p, new ParticipantPosition(
                    p, legalEntity, currency, payable, receivable, net, txCounts.getOrDefault(p, 0)
            ));
        }

        // Verify zero-sum multilateral invariant
        if (netSum != 0L) {
            throw new IllegalStateException("Multilateral netting invariant violated: sum of net positions is " + netSum + " (expected 0)");
        }

        // Optimize settlement transfers: greedy matching of largest net debtors and creditors
        List<NetSettlementTransfer> transfers = optimizeTransfers(legalEntity, currency, positions);

        long totalNetSettlement = transfers.stream().mapToLong(NetSettlementTransfer::amount).sum();
        long savings = totalGross - totalNetSettlement;
        double efficiencyPct = totalGross > 0 ? ((double) savings / totalGross) * 100.0 : 0.0;

        return new MultilateralNettingBatch(
                UUID.randomUUID(),
                legalEntity,
                currency,
                OffsetDateTime.now(),
                totalGross,
                totalNetSettlement,
                savings,
                efficiencyPct,
                transactions.size(),
                transfers.size(),
                Collections.unmodifiableMap(positions),
                Collections.unmodifiableList(transfers),
                Collections.unmodifiableList(new ArrayList<>(transactions))
        );
    }

    /**
     * Minimizes settlement transfers by greedily clearing largest net debtor with largest net creditor.
     */
    private List<NetSettlementTransfer> optimizeTransfers(
            String legalEntity,
            String currency,
            Map<String, ParticipantPosition> positions
    ) {
        // Debtors have netAmount < 0, remaining debt is positive
        PriorityQueue<DebtorEntry> debtorQueue = new PriorityQueue<>(
                Comparator.comparingLong((DebtorEntry d) -> d.remainingDebt).reversed()
        );
        // Creditors have netAmount > 0, remaining credit is positive
        PriorityQueue<CreditorEntry> creditorQueue = new PriorityQueue<>(
                Comparator.comparingLong((CreditorEntry c) -> c.remainingCredit).reversed()
        );

        for (ParticipantPosition pos : positions.values()) {
            if (pos.isNetDebtor()) {
                debtorQueue.add(new DebtorEntry(pos.participantId(), -pos.netAmount()));
            } else if (pos.isNetCreditor()) {
                creditorQueue.add(new CreditorEntry(pos.participantId(), pos.netAmount()));
            }
        }

        List<NetSettlementTransfer> transfers = new ArrayList<>();

        while (!debtorQueue.isEmpty() && !creditorQueue.isEmpty()) {
            DebtorEntry debtor = debtorQueue.poll();
            CreditorEntry creditor = creditorQueue.poll();

            long settleAmount = Math.min(debtor.remainingDebt, creditor.remainingCredit);
            transfers.add(new NetSettlementTransfer(
                    UUID.randomUUID(),
                    legalEntity,
                    currency,
                    debtor.id,
                    creditor.id,
                    settleAmount
            ));

            long debtorRemaining = debtor.remainingDebt - settleAmount;
            long creditorRemaining = creditor.remainingCredit - settleAmount;

            if (debtorRemaining > 0) {
                debtorQueue.add(new DebtorEntry(debtor.id, debtorRemaining));
            }
            if (creditorRemaining > 0) {
                creditorQueue.add(new CreditorEntry(creditor.id, creditorRemaining));
            }
        }

        return transfers;
    }

    private void validateBoundaries(GrossTransaction tx, String expectedEntity, String expectedCurrency) {
        if (!tx.legalEntity().equalsIgnoreCase(expectedEntity)) {
            throw ApiException.badRequest("legal_entity_mismatch",
                    "Transaction " + tx.reference() + " belongs to legal entity " + tx.legalEntity()
                            + ", expected " + expectedEntity);
        }
        if (!tx.currency().equalsIgnoreCase(expectedCurrency)) {
            throw ApiException.badRequest("currency_mismatch",
                    "Transaction " + tx.reference() + " has currency " + tx.currency()
                            + ", expected " + expectedCurrency);
        }
    }

    private static class DebtorEntry {
        final String id;
        long remainingDebt;

        DebtorEntry(String id, long remainingDebt) {
            this.id = id;
            this.remainingDebt = remainingDebt;
        }
    }

    private static class CreditorEntry {
        final String id;
        long remainingCredit;

        CreditorEntry(String id, long remainingCredit) {
            this.id = id;
            this.remainingCredit = remainingCredit;
        }
    }
}
