package com.elepay.ledger;

import com.elepay.common.ApiException;
import com.elepay.common.Idempotency;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Append-only, balanced double-entry ledger in integer minor units.
 *
 * <p>Postings are atomic and idempotent per (scope, key): an identical retry
 * returns the original transaction, a different request under the same key is
 * rejected. Accounts touched by a posting are locked in id order, so
 * concurrent spenders of the same balance serialize and the loser sees the
 * committed balance.
 */
@Service
public class Ledger {
    public static final String GHS = "GHS";

    private final JdbcClient db;
    private final ObjectMapper json;

    public Ledger(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    /** One side of a posting. Positive amounts increase the account balance. */
    public record Leg(String account, long amount) {
        public static Leg debit(String account, long amount) { return new Leg(account, -amount); }
        public static Leg credit(String account, long amount) { return new Leg(account, amount); }
    }

    public record Posting(String scope, String key, String kind, String description,
                          List<Leg> legs, UUID relatedTo, Map<String, String> metadata) {
        public Posting {
            legs = List.copyOf(legs);
            metadata = metadata == null ? Map.of() : new TreeMap<>(metadata);
        }

        public static Posting of(String scope, String key, String kind, List<Leg> legs) {
            return new Posting(scope, key, kind, null, legs, null, Map.of());
        }

        public Posting relatedTo(UUID id) {
            return new Posting(scope, key, kind, description, legs, id, metadata);
        }

        public Posting withMetadata(Map<String, String> values) {
            return new Posting(scope, key, kind, description, legs, relatedTo, values);
        }

        public Posting describedAs(String text) {
            return new Posting(scope, key, kind, text, legs, relatedTo, metadata);
        }
    }

    public record Posted(UUID transactionId, boolean replay) {}

    public record Entry(UUID transactionId, int line, String account, String currency, long amount,
                        String kind, OffsetDateTime postedAt, long runningBalance, String description) {}

    public record Account(long id, String code, String ownerType, UUID ownerId, String bucket,
                          String currency, boolean allowNegative, long balance) {}

    @Transactional
    public Posted post(Posting posting) {
        return write(posting, null);
    }

    /**
     * Posts the exact inverse of {@code original}. A transaction can be fully
     * reversed only once; reversals of reversals are rejected.
     */
    @Transactional
    public Posted reverse(UUID original, String scope, String key, String reason) {
        var tx = db.sql("SELECT kind, reverses FROM journal_transaction WHERE id = :id")
                .param("id", original)
                .query((rs, i) -> new String[]{rs.getString(1), rs.getString(2)})
                .optional().orElseThrow(() -> ApiException.notFound("Unknown journal transaction"));
        if (tx[1] != null) throw ApiException.conflict("reversal_of_reversal", "A reversal cannot be reversed");
        List<Leg> inverse = db.sql("""
                SELECT a.code, e.amount FROM journal_entry e JOIN ledger_account a ON a.id = e.account_id
                WHERE e.transaction_id = :id ORDER BY e.line""")
                .param("id", original)
                .query((rs, i) -> new Leg(rs.getString(1), -rs.getLong(2))).list();
        Posting posting = new Posting(scope, key, "reversal:" + tx[0], reason, inverse, null,
                Map.of("reverses", original.toString()));
        return write(posting, original);
    }

    private Posted write(Posting posting, UUID reverses) {
        if (posting.legs().size() < 2) throw ApiException.badRequest("unbalanced", "A posting needs at least two legs");
        String hash = Idempotency.hash(Map.of(
                "kind", posting.kind(),
                "legs", posting.legs(),
                "related", String.valueOf(posting.relatedTo()),
                "reverses", String.valueOf(reverses),
                "metadata", posting.metadata()));
        UUID id = UUID.randomUUID();
        int inserted;
        try {
            inserted = db.sql("""
                INSERT INTO journal_transaction
                    (id, idempotency_scope, idempotency_key, request_hash, kind, description, reverses, related_to, metadata)
                VALUES (:id, :scope, :key, :hash, :kind, :description, :reverses, :related, CAST(:metadata AS jsonb))
                ON CONFLICT (idempotency_scope, idempotency_key) DO NOTHING""")
                .param("id", id).param("scope", posting.scope()).param("key", posting.key())
                .param("hash", hash).param("kind", posting.kind()).param("description", posting.description())
                .param("reverses", reverses).param("related", posting.relatedTo())
                .param("metadata", json.writeValueAsString(posting.metadata()))
                .update();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // Only the single-reversal index can raise here; the idempotency key is the arbiter.
            throw ApiException.conflict("already_reversed", "This transaction was already reversed");
        }
        if (inserted == 0) {
            record Existing(UUID id, String hash) {}
            Existing existing = db.sql("""
                    SELECT id, request_hash FROM journal_transaction
                    WHERE idempotency_scope = :scope AND idempotency_key = :key""")
                    .param("scope", posting.scope()).param("key", posting.key())
                    .query((rs, i) -> new Existing(rs.getObject(1, UUID.class), rs.getString(2))).single();
            if (!existing.hash().equals(hash)) throw ApiException.idempotencyConflict();
            return new Posted(existing.id(), true);
        }

        Map<String, Account> accounts = lock(posting.legs().stream().map(Leg::account).toList());
        Map<String, Long> perCurrency = new TreeMap<>();
        Map<String, Long> delta = new HashMap<>();
        for (Leg leg : posting.legs()) {
            if (leg.amount() == 0) throw ApiException.badRequest("zero_amount", "Posting legs must be non-zero");
            Account account = accounts.get(leg.account());
            perCurrency.merge(account.currency(), leg.amount(), Math::addExact);
            delta.merge(account.code(), leg.amount(), Math::addExact);
        }
        if (perCurrency.values().stream().anyMatch(sum -> sum != 0)) {
            throw ApiException.badRequest("unbalanced", "Posting legs must sum to zero in each currency");
        }
        for (var change : delta.entrySet()) {
            Account account = accounts.get(change.getKey());
            if (!account.allowNegative() && Math.addExact(account.balance(), change.getValue()) < 0) {
                throw ApiException.insufficientFunds();
            }
        }
        int line = 0;
        for (Leg leg : posting.legs()) {
            Account account = accounts.get(leg.account());
            db.sql("""
                    INSERT INTO journal_entry (transaction_id, line, account_id, currency, amount)
                    VALUES (:tx, :line, :account, :currency, :amount)""")
                    .param("tx", id).param("line", line++).param("account", account.id())
                    .param("currency", account.currency()).param("amount", leg.amount()).update();
        }
        return new Posted(id, false);
    }

    private Map<String, Account> lock(Collection<String> codes) {
        List<String> distinct = codes.stream().distinct().sorted().toList();
        List<Account> found = db.sql("""
                SELECT id, code, owner_type, owner_id, bucket, currency, allow_negative, balance
                FROM ledger_account WHERE code IN (:codes) ORDER BY id FOR UPDATE""")
                .param("codes", distinct).query(Ledger::account).list();
        if (found.size() != distinct.size()) {
            throw ApiException.badRequest("unknown_account", "Posting references an unknown ledger account");
        }
        Map<String, Account> byCode = new HashMap<>();
        found.forEach(a -> byCode.put(a.code(), a));
        return byCode;
    }

    private static Account account(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Account(rs.getLong("id"), rs.getString("code"), rs.getString("owner_type"),
                rs.getObject("owner_id", UUID.class), rs.getString("bucket"), rs.getString("currency"),
                rs.getBoolean("allow_negative"), rs.getLong("balance"));
    }

    // ---- accounts -------------------------------------------------------------------------

    public static String code(String ownerType, UUID ownerId, String bucket, String currency) {
        return ownerType + ":" + ownerId + ":" + bucket + ":" + currency;
    }

    public static String clearing(String provider) {
        return "system:clearing:" + provider + ":" + GHS;
    }

    public static final String REVENUE = "system:revenue:GHS";
    public static final String DEFERRED_REVENUE = "system:deferred_revenue:GHS";
    public static final String REVENUE_EARNED = "system:revenue:earned:GHS";
    public static final String SUSPENSE = "system:suspense:GHS";
    public static final String REFUNDS_IN_FLIGHT = "system:refunds_in_flight:GHS";

    /** Creates the account if needed and returns its code. */
    @Transactional
    public String open(String ownerType, UUID ownerId, String bucket, String currency) {
        String code = code(ownerType, ownerId, bucket, currency);
        db.sql("""
                INSERT INTO ledger_account (code, owner_type, owner_id, bucket, currency)
                VALUES (:code, :type, :owner, :bucket, :currency) ON CONFLICT DO NOTHING""")
                .param("code", code).param("type", ownerType).param("owner", ownerId)
                .param("bucket", bucket).param("currency", currency).update();
        return code;
    }

    public long balance(String code) {
        return db.sql("SELECT balance FROM ledger_account WHERE code = :code")
                .param("code", code).query(Long.class).optional().orElse(0L);
    }

    public Optional<Account> account(String code) {
        return db.sql("""
                SELECT id, code, owner_type, owner_id, bucket, currency, allow_negative, balance
                FROM ledger_account WHERE code = :code""")
                .param("code", code).query(Ledger::account).optional();
    }

    public List<Account> accountsOf(String ownerType, UUID ownerId) {
        return db.sql("""
                SELECT id, code, owner_type, owner_id, bucket, currency, allow_negative, balance
                FROM ledger_account WHERE owner_type = :type AND owner_id = :owner ORDER BY bucket""")
                .param("type", ownerType).param("owner", ownerId).query(Ledger::account).list();
    }

    /** Entries for one account with a running balance, newest last. */
    public List<Entry> statement(String code, Instant from, Instant to) {
        return db.sql("""
                SELECT * FROM (
                    SELECT e.transaction_id, e.line, a.code, e.currency, e.amount, t.kind, t.posted_at,
                           sum(e.amount) OVER (ORDER BY t.posted_at, e.transaction_id, e.line) AS running,
                           t.description
                    FROM journal_entry e
                    JOIN journal_transaction t ON t.id = e.transaction_id
                    JOIN ledger_account a ON a.id = e.account_id
                    WHERE a.code = :code
                ) s
                WHERE s.posted_at >= :from AND s.posted_at < :to
                ORDER BY s.posted_at, s.transaction_id, s.line""")
                .param("code", code)
                .param("from", OffsetDateTime.ofInstant(from, java.time.ZoneOffset.UTC))
                .param("to", OffsetDateTime.ofInstant(to, java.time.ZoneOffset.UTC))
                .query((rs, i) -> new Entry(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3),
                        rs.getString(4), rs.getLong(5), rs.getString(6), rs.getObject(7, OffsetDateTime.class),
                        rs.getLong(8), rs.getString(9)))
                .list();
    }

    public List<Entry> entries(UUID transactionId) {
        return db.sql("""
                SELECT e.transaction_id, e.line, a.code, e.currency, e.amount, t.kind, t.posted_at, 0, t.description
                FROM journal_entry e JOIN journal_transaction t ON t.id = e.transaction_id
                JOIN ledger_account a ON a.id = e.account_id
                WHERE e.transaction_id = :tx ORDER BY e.line""")
                .param("tx", transactionId)
                .query((rs, i) -> new Entry(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3),
                        rs.getString(4), rs.getLong(5), rs.getString(6), rs.getObject(7, OffsetDateTime.class),
                        rs.getLong(8), rs.getString(9)))
                .list();
    }

    /** Integrity summary: projection drift and global balance per currency. */
    public record Integrity(long accountsWithDrift, Map<String, Long> netByCurrency, long unbalancedTransactions) {
        public boolean ok() {
            return accountsWithDrift == 0 && unbalancedTransactions == 0
                    && netByCurrency.values().stream().allMatch(v -> v == 0);
        }
    }

    public Integrity integrity() {
        long drift = db.sql("SELECT count(*) FROM ledger_account_reconstruction WHERE projected_balance <> journal_balance")
                .query(Long.class).single();
        Map<String, Long> net = new TreeMap<>();
        db.sql("SELECT currency, sum(balance)::bigint FROM ledger_account GROUP BY currency")
                .query((rs, i) -> net.put(rs.getString(1), rs.getLong(2))).list();
        long unbalanced = db.sql("""
                SELECT count(*) FROM (SELECT transaction_id FROM journal_entry
                GROUP BY transaction_id, currency HAVING sum(amount) <> 0) u""").query(Long.class).single();
        return new Integrity(drift, net, unbalanced);
    }
}
