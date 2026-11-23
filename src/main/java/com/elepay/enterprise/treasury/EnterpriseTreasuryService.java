package com.elepay.enterprise.treasury;

import com.elepay.common.ApiException;
import com.elepay.ledger.Ledger;
import com.elepay.ledger.Ledger.Leg;
import com.elepay.ledger.Ledger.Posting;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enterprise Treasury & Funding Engine.
 *
 * <p>Enforces strict segregation between enterprise operational funds and safeguarded
 * customer/merchant balances. Manages enterprise prefunding buffers, tracks external
 * receivables ledgers, performs funding shortfall detection with automated holds/alerts,
 * and produces forward-looking liquidity forecasts.
 */
@Service
public class EnterpriseTreasuryService {

    public static final String GHS = "GHS";

    private final JdbcClient db;
    private final Ledger ledger;

    public EnterpriseTreasuryService(JdbcClient db, Ledger ledger) {
        this.db = Objects.requireNonNull(db, "db cannot be null");
        this.ledger = Objects.requireNonNull(ledger, "ledger cannot be null");
    }

    /**
     * Initializes the designated segregated treasury accounts for an enterprise.
     */
    @Transactional
    public void setupEnterpriseTreasury(UUID enterpriseId, String currency) {
        Objects.requireNonNull(enterpriseId, "enterpriseId cannot be null");
        String cur = currency != null ? currency.toUpperCase(Locale.ROOT) : GHS;

        for (TreasuryAccountType type : TreasuryAccountType.values()) {
            String code = buildAccountCode(enterpriseId, type, cur);

            // Open in double-entry ledger based on type
            switch (type) {
                case PREFUNDING -> ledger.open("enterprise", enterpriseId, "holding", cur);
                case SAFEGUARDED_SETTLEMENT -> ledger.open("enterprise", enterpriseId, "available", cur);
                case EXTERNAL_RECEIVABLES -> ledger.openSystem("system:receivables:" + enterpriseId + ":" + cur, "clearing", cur, true);
                case OPERATIONAL_REVENUE -> ledger.open("enterprise", enterpriseId, "revenue", cur);
            }

            db.sql("""
                    INSERT INTO enterprise_treasury_account (
                        id, enterprise_id, account_type, account_code, currency,
                        balance_pesewas, reserved_pesewas, status, strict_segregation
                    ) VALUES (
                        :id, :enterpriseId, :type, :code, :currency,
                        0, 0, 'ACTIVE', true
                    ) ON CONFLICT (enterprise_id, account_type, currency) DO NOTHING
                    """)
                    .param("id", UUID.randomUUID())
                    .param("enterpriseId", enterpriseId)
                    .param("type", type.name())
                    .param("code", code)
                    .param("currency", cur)
                    .update();
        }
    }

    /**
     * Deposits enterprise prefunding into the designated safeguarded prefunding account,
     * posting double-entry legs to ensure conservation of money.
     */
    @Transactional
    public PrefundingLedgerEntry depositPrefunding(UUID enterpriseId, long amountPesewas,
                                                   String currency, String reference, String description) {
        if (amountPesewas <= 0) {
            throw ApiException.badRequest("invalid_amount", "Prefunding amount must be positive");
        }
        String cur = currency != null ? currency.toUpperCase(Locale.ROOT) : GHS;
        String ref = reference != null ? reference : "DEP-" + UUID.randomUUID();

        setupEnterpriseTreasury(enterpriseId, cur);
        EnterpriseTreasuryAccount prefundingAcc = getTreasuryAccount(enterpriseId, TreasuryAccountType.PREFUNDING, cur);

        // Double-entry posting: Debit clearing / source of funds, Credit enterprise prefunding holding
        String sourceClearing = ledger.openSystem("system:clearing:prefunding:" + cur, "clearing", cur, true);
        String prefundingLedgerAcc = Ledger.code("enterprise", enterpriseId, "holding", cur);

        var posted = ledger.post(Posting.of(
                "treasury:prefunding",
                ref,
                "prefunding:deposit",
                List.of(
                        Leg.debit(sourceClearing, amountPesewas),
                        Leg.credit(prefundingLedgerAcc, amountPesewas)
                )
        ).describedAs("Enterprise prefunding deposit: " + description));

        // Update database balance atomically
        db.sql("""
                UPDATE enterprise_treasury_account
                SET balance_pesewas = balance_pesewas + :amt, updated_at = now()
                WHERE id = :id
                """)
                .param("amt", amountPesewas)
                .param("id", prefundingAcc.id())
                .update();

        long balanceAfter = prefundingAcc.balancePesewas() + amountPesewas;

        // Record in append-only prefunding ledger
        UUID entryId = UUID.randomUUID();
        db.sql("""
                INSERT INTO enterprise_prefunding_ledger (
                    id, enterprise_id, treasury_account_id, transaction_reference,
                    entry_type, amount_pesewas, balance_after_pesewas, currency,
                    ledger_tx_id, description
                ) VALUES (
                    :id, :enterpriseId, :accId, :ref,
                    :entryType, :amt, :balanceAfter, :currency,
                    :txId, :desc
                )
                """)
                .param("id", entryId)
                .param("enterpriseId", enterpriseId)
                .param("accId", prefundingAcc.id())
                .param("ref", ref)
                .param("entryType", PrefundingEntryType.DEPOSIT.name())
                .param("amt", amountPesewas)
                .param("balanceAfter", balanceAfter)
                .param("currency", cur)
                .param("txId", posted.transactionId())
                .param("desc", description)
                .update();

        return new PrefundingLedgerEntry(
                entryId, enterpriseId, prefundingAcc.id(), ref,
                PrefundingEntryType.DEPOSIT, amountPesewas, balanceAfter, cur,
                posted.transactionId(), description, OffsetDateTime.now()
        );
    }

    /**
     * Records an external receivable incurred when an order is collected externally by the enterprise's own payment platform.
     */
    @Transactional
    public PrefundingLedgerEntry recordReceivable(UUID enterpriseId, String orderReference,
                                                  long amountPesewas, String currency) {
        if (amountPesewas <= 0) {
            throw ApiException.badRequest("invalid_amount", "Receivable amount must be positive");
        }
        String cur = currency != null ? currency.toUpperCase(Locale.ROOT) : GHS;
        setupEnterpriseTreasury(enterpriseId, cur);

        EnterpriseTreasuryAccount recvAcc = getTreasuryAccount(enterpriseId, TreasuryAccountType.EXTERNAL_RECEIVABLES, cur);

        db.sql("""
                UPDATE enterprise_treasury_account
                SET balance_pesewas = balance_pesewas + :amt, updated_at = now()
                WHERE id = :id
                """)
                .param("amt", amountPesewas)
                .param("id", recvAcc.id())
                .update();

        long balanceAfter = recvAcc.balancePesewas() + amountPesewas;
        String ref = "RCV-" + orderReference;
        UUID entryId = UUID.randomUUID();

        db.sql("""
                INSERT INTO enterprise_prefunding_ledger (
                    id, enterprise_id, treasury_account_id, transaction_reference,
                    entry_type, amount_pesewas, balance_after_pesewas, currency, description
                ) VALUES (
                    :id, :enterpriseId, :accId, :ref,
                    :entryType, :amt, :balanceAfter, :currency, :desc
                )
                """)
                .param("id", entryId)
                .param("enterpriseId", enterpriseId)
                .param("accId", recvAcc.id())
                .param("ref", ref)
                .param("entryType", PrefundingEntryType.RECEIVABLE_INCURRED.name())
                .param("amt", amountPesewas)
                .param("balanceAfter", balanceAfter)
                .param("currency", cur)
                .param("desc", "External receivable for order " + orderReference)
                .update();

        return new PrefundingLedgerEntry(
                entryId, enterpriseId, recvAcc.id(), ref,
                PrefundingEntryType.RECEIVABLE_INCURRED, amountPesewas, balanceAfter, cur,
                null, "External receivable for order " + orderReference, OffsetDateTime.now()
        );
    }

    /**
     * Marks an external receivable as settled when the partner reconciles and transfers funds.
     */
    @Transactional
    public PrefundingLedgerEntry settleReceivable(UUID enterpriseId, String orderReference,
                                                  long amountPesewas, String currency) {
        String cur = currency != null ? currency.toUpperCase(Locale.ROOT) : GHS;
        EnterpriseTreasuryAccount recvAcc = getTreasuryAccount(enterpriseId, TreasuryAccountType.EXTERNAL_RECEIVABLES, cur);
        EnterpriseTreasuryAccount settleAcc = getTreasuryAccount(enterpriseId, TreasuryAccountType.SAFEGUARDED_SETTLEMENT, cur);

        db.sql("""
                UPDATE enterprise_treasury_account
                SET balance_pesewas = balance_pesewas - :amt, updated_at = now()
                WHERE id = :id
                """)
                .param("amt", amountPesewas)
                .param("id", recvAcc.id())
                .update();

        db.sql("""
                UPDATE enterprise_treasury_account
                SET balance_pesewas = balance_pesewas + :amt, updated_at = now()
                WHERE id = :id
                """)
                .param("amt", amountPesewas)
                .param("id", settleAcc.id())
                .update();

        long balanceAfter = recvAcc.balancePesewas() - amountPesewas;
        String ref = "STL-" + orderReference;
        UUID entryId = UUID.randomUUID();

        db.sql("""
                INSERT INTO enterprise_prefunding_ledger (
                    id, enterprise_id, treasury_account_id, transaction_reference,
                    entry_type, amount_pesewas, balance_after_pesewas, currency, description
                ) VALUES (
                    :id, :enterpriseId, :accId, :ref,
                    :entryType, :amt, :balanceAfter, :currency, :desc
                )
                """)
                .param("id", entryId)
                .param("enterpriseId", enterpriseId)
                .param("accId", recvAcc.id())
                .param("ref", ref)
                .param("entryType", PrefundingEntryType.RECEIVABLE_SETTLED.name())
                .param("amt", amountPesewas)
                .param("balanceAfter", balanceAfter)
                .param("currency", cur)
                .param("desc", "Receivable settled for order " + orderReference)
                .update();

        return new PrefundingLedgerEntry(
                entryId, enterpriseId, recvAcc.id(), ref,
                PrefundingEntryType.RECEIVABLE_SETTLED, amountPesewas, balanceAfter, cur,
                null, "Receivable settled for order " + orderReference, OffsetDateTime.now()
        );
    }

    /**
     * Evaluates funding sufficiency before a settlement release, customer withdrawal, or payout batch.
     * If available prefunding + settlement accounts do not cover the requirement, detects shortfall,
     * logs alert and applies hold.
     */
    @Transactional
    public ShortfallEvaluation checkFundingSufficiency(UUID enterpriseId, long requiredPesewas,
                                                      String currency, String obligationType) {
        String cur = currency != null ? currency.toUpperCase(Locale.ROOT) : GHS;
        setupEnterpriseTreasury(enterpriseId, cur);

        EnterpriseTreasuryAccount prefunding = getTreasuryAccount(enterpriseId, TreasuryAccountType.PREFUNDING, cur);
        EnterpriseTreasuryAccount safeguarded = getTreasuryAccount(enterpriseId, TreasuryAccountType.SAFEGUARDED_SETTLEMENT, cur);

        long availableFunding = prefunding.availablePesewas() + safeguarded.availablePesewas();

        if (availableFunding < requiredPesewas) {
            long shortfall = requiredPesewas - availableFunding;

            // Record shortfall incident with automated HOLD
            UUID shortfallId = UUID.randomUUID();
            db.sql("""
                    INSERT INTO enterprise_treasury_shortfall (
                        id, enterprise_id, obligation_type, required_funding_pesewas,
                        available_funding_pesewas, shortfall_pesewas, status, action_taken
                    ) VALUES (
                        :id, :enterpriseId, :obType, :required,
                        :available, :shortfall, 'ACTIVE', 'HOLD_PLACED'
                    )
                    """)
                    .param("id", shortfallId)
                    .param("enterpriseId", enterpriseId)
                    .param("obType", obligationType)
                    .param("required", requiredPesewas)
                    .param("available", availableFunding)
                    .param("shortfall", shortfall)
                    .update();

            return new ShortfallEvaluation(
                    ShortfallStatus.SHORTFALL,
                    ShortfallAction.HOLD_PLACED,
                    requiredPesewas,
                    availableFunding,
                    shortfall,
                    shortfallId
            );
        } else if (availableFunding < (requiredPesewas * 12) / 10) { // < 120% buffer
            return new ShortfallEvaluation(
                    ShortfallStatus.WARNING,
                    ShortfallAction.ALERT_DISPATCHED,
                    requiredPesewas,
                    availableFunding,
                    0L,
                    null
            );
        } else {
            return new ShortfallEvaluation(
                    ShortfallStatus.SUFFICIENT,
                    ShortfallAction.NONE,
                    requiredPesewas,
                    availableFunding,
                    0L,
                    null
            );
        }
    }

    /**
     * Reserves funding for an upcoming settlement or withdrawal payout batch.
     */
    @Transactional
    public void reserveFunding(UUID enterpriseId, long amountPesewas, String currency) {
        String cur = currency != null ? currency.toUpperCase(Locale.ROOT) : GHS;
        EnterpriseTreasuryAccount prefunding = getTreasuryAccount(enterpriseId, TreasuryAccountType.PREFUNDING, cur);

        if (prefunding.availablePesewas() < amountPesewas) {
            throw ApiException.badRequest("insufficient_prefunding",
                    "Insufficient available prefunding balance (" + prefunding.availablePesewas()
                            + " pesewas) for reservation of " + amountPesewas + " pesewas");
        }

        db.sql("""
                UPDATE enterprise_treasury_account
                SET reserved_pesewas = reserved_pesewas + :amt, updated_at = now()
                WHERE id = :id
                """)
                .param("amt", amountPesewas)
                .param("id", prefunding.id())
                .update();
    }

    /**
     * Releases reserved funding upon settlement execution (consumed=true) or cancellation (consumed=false).
     */
    @Transactional
    public void releaseReservedFunding(UUID enterpriseId, long amountPesewas, String currency, boolean consumed) {
        String cur = currency != null ? currency.toUpperCase(Locale.ROOT) : GHS;
        EnterpriseTreasuryAccount prefunding = getTreasuryAccount(enterpriseId, TreasuryAccountType.PREFUNDING, cur);

        long balanceDeduction = consumed ? amountPesewas : 0L;

        db.sql("""
                UPDATE enterprise_treasury_account
                SET reserved_pesewas = GREATEST(0, reserved_pesewas - :resAmt),
                    balance_pesewas = GREATEST(0, balance_pesewas - :balAmt),
                    updated_at = now()
                WHERE id = :id
                """)
                .param("resAmt", amountPesewas)
                .param("balAmt", balanceDeduction)
                .param("id", prefunding.id())
                .update();
    }

    /**
     * Retrieves an enterprise treasury account by type.
     */
    public EnterpriseTreasuryAccount getTreasuryAccount(UUID enterpriseId, TreasuryAccountType type, String currency) {
        String cur = currency != null ? currency.toUpperCase(Locale.ROOT) : GHS;
        return db.sql("""
                SELECT id, enterprise_id, account_type, account_code, currency,
                       balance_pesewas, reserved_pesewas, status, strict_segregation,
                       created_at, updated_at
                FROM enterprise_treasury_account
                WHERE enterprise_id = :enterpriseId AND account_type = :type AND currency = :cur
                """)
                .param("enterpriseId", enterpriseId)
                .param("type", type.name())
                .param("cur", cur)
                .query(this::mapTreasuryAccount)
                .optional()
                .orElseGet(() -> {
                    setupEnterpriseTreasury(enterpriseId, cur);
                    return db.sql("""
                            SELECT id, enterprise_id, account_type, account_code, currency,
                                   balance_pesewas, reserved_pesewas, status, strict_segregation,
                                   created_at, updated_at
                            FROM enterprise_treasury_account
                            WHERE enterprise_id = :enterpriseId AND account_type = :type AND currency = :cur
                            """)
                            .param("enterpriseId", enterpriseId)
                            .param("type", type.name())
                            .param("cur", cur)
                            .query(this::mapTreasuryAccount)
                            .single();
                });
    }

    /**
     * Generates a forward-looking liquidity forecast for the enterprise.
     */
    public LiquidityForecast generateLiquidityForecast(UUID enterpriseId, String currency) {
        String cur = currency != null ? currency.toUpperCase(Locale.ROOT) : GHS;
        setupEnterpriseTreasury(enterpriseId, cur);

        EnterpriseTreasuryAccount prefunding = getTreasuryAccount(enterpriseId, TreasuryAccountType.PREFUNDING, cur);
        EnterpriseTreasuryAccount safeguarded = getTreasuryAccount(enterpriseId, TreasuryAccountType.SAFEGUARDED_SETTLEMENT, cur);
        EnterpriseTreasuryAccount receivables = getTreasuryAccount(enterpriseId, TreasuryAccountType.EXTERNAL_RECEIVABLES, cur);

        // Sum pending delivery escrows
        Long pendingEscrow = db.sql("""
                SELECT COALESCE(SUM(net_payable_pesewas), 0)
                FROM enterprise_delivery_escrow
                WHERE enterprise_id = :enterpriseId AND escrow_status = 'HELD'
                """)
                .param("enterpriseId", enterpriseId)
                .query(Long.class)
                .single();

        // Projected obligations next 24 hours: delivered but unreleased items + pending withdrawals
        Long obligations24h = db.sql("""
                SELECT COALESCE(SUM(net_payable_pesewas), 0)
                FROM enterprise_delivery_escrow
                WHERE enterprise_id = :enterpriseId AND delivery_status = 'DELIVERED' AND escrow_status = 'HELD'
                """)
                .param("enterpriseId", enterpriseId)
                .query(Long.class)
                .single();

        long obligations7d = pendingEscrow != null ? pendingEscrow : 0L;
        long totalCurrentFunding = prefunding.availablePesewas() + safeguarded.availablePesewas();
        long netLiquidityPosition = totalCurrentFunding + receivables.balancePesewas() - obligations7d;

        ShortfallStatus status;
        if (totalCurrentFunding < (obligations24h != null ? obligations24h : 0L)) {
            status = ShortfallStatus.SHORTFALL;
        } else if (totalCurrentFunding < ((obligations24h != null ? obligations24h : 0L) * 12) / 10) {
            status = ShortfallStatus.WARNING;
        } else {
            status = ShortfallStatus.SUFFICIENT;
        }

        return new LiquidityForecast(
                enterpriseId,
                cur,
                prefunding.balancePesewas(),
                safeguarded.balancePesewas(),
                receivables.balancePesewas(),
                pendingEscrow != null ? pendingEscrow : 0L,
                obligations24h != null ? obligations24h : 0L,
                obligations7d,
                netLiquidityPosition,
                status,
                Instant.now()
        );
    }

    public record ShortfallEvaluation(
            ShortfallStatus status,
            ShortfallAction actionTaken,
            long requiredFundingPesewas,
            long availableFundingPesewas,
            long shortfallPesewas,
            UUID shortfallId
    ) {
        public boolean hasShortfall() {
            return status == ShortfallStatus.SHORTFALL;
        }
    }

    private String buildAccountCode(UUID enterpriseId, TreasuryAccountType type, String currency) {
        return "treasury:" + enterpriseId + ":" + type.name().toLowerCase(Locale.ROOT) + ":" + currency;
    }

    private EnterpriseTreasuryAccount mapTreasuryAccount(ResultSet rs, int rowNum) throws SQLException {
        return new EnterpriseTreasuryAccount(
                rs.getObject("id", UUID.class),
                rs.getObject("enterprise_id", UUID.class),
                TreasuryAccountType.valueOf(rs.getString("account_type")),
                rs.getString("account_code"),
                rs.getString("currency"),
                rs.getLong("balance_pesewas"),
                rs.getLong("reserved_pesewas"),
                rs.getString("status"),
                rs.getBoolean("strict_segregation"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)
        );
    }
}
