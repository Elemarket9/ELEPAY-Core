package com.elepay.ledger;

import com.elepay.common.ApiException;
import com.elepay.common.Idempotency;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
    private final int systemStripes;
    private final Set<String> knownStripes = ConcurrentHashMap.newKeySet();

    @Autowired
    public Ledger(JdbcClient db, ObjectMapper json,
                  @Value("${elepay.ledger.system-stripes:64}") int systemStripes) {
        if (systemStripes < 1 || systemStripes > 256) {
            throw new IllegalArgumentException(
                    "elepay.ledger.system-stripes must be between 1 and 256, got " + systemStripes);
        }
        this.db = db;
        this.json = json;
        this.systemStripes = systemStripes;
    }

    public Ledger(JdbcClient db, ObjectMapper json) {
        this(db, json, 64);
    }

    /** One side of a posting. Positive amounts increase the account balance. */
    public record Leg(String account, long amount) {
        public static Leg debit(String account, long amount) {
            if (amount <= 0) throw ApiException.badRequest("invalid_amount", "Leg amount must be positive");
            return new Leg(account, -amount);
        }
        public static Leg credit(String account, long amount) {
            if (amount <= 0) throw ApiException.badRequest("invalid_amount", "Leg amount must be positive");
            return new Leg(account, amount);
        }
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

    /** Looks up a previously posted journal transaction by its idempotency scope and key. */
    public Optional<UUID> findTransactionByIdempotency(String scope, String key) {
        if (scope == null || scope.isBlank() || key == null || key.isBlank()) {
            return Optional.empty();
        }
        return db.sql("""
                SELECT id FROM journal_transaction
                WHERE idempotency_scope = :scope AND idempotency_key = :key""")
                .param("scope", scope)
                .param("key", key)
                .query(UUID.class)
                .optional();
    }

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
        if (posting == null) {
            throw ApiException.badRequest("invalid_posting", "Posting must not be null");
        }
        if (posting.scope() == null || posting.scope().isBlank()
                || posting.key() == null || posting.key().isBlank()
                || posting.kind() == null || posting.kind().isBlank()) {
            throw ApiException.badRequest("invalid_posting", "Posting scope, key, and kind are required");
        }
        if (posting.legs() == null || posting.legs().size() < 2) {
            throw ApiException.badRequest("unbalanced", "A posting needs at least two legs");
        }
        for (Leg leg : posting.legs()) {
            if (leg == null || leg.account() == null || leg.account().isBlank()) {
                throw ApiException.badRequest("invalid_posting", "Posting leg account is required");
            }
        }
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
        insertEntriesBulk(id, posting.legs(), accounts);
        return new Posted(id, false);
    }

    /**
     * Bulk-inserts all {@code journal_entry} legs for a single {@code journal_transaction} in a
     * single SQL round trip (eliminating N-1 round trips per multi-merchant split or settlement).
     */
    private void insertEntriesBulk(UUID txId, List<Leg> legs, Map<String, Account> accounts) {
        if (legs == null || legs.isEmpty()) {
            throw ApiException.badRequest("unbalanced", "A posting needs at least two legs");
        }
        StringBuilder sql = new StringBuilder(
                "INSERT INTO journal_entry (transaction_id, line, account_id, currency, amount) VALUES ");
        Map<String, Object> params = new HashMap<>(legs.size() * 4 + 2);
        params.put("tx", txId);
        for (int line = 0; line < legs.size(); line++) {
            if (line > 0) sql.append(", ");
            Leg leg = legs.get(line);
            Account account = accounts.get(leg.account());
            sql.append("(:tx, :l").append(line)
               .append(", :a").append(line)
               .append(", :c").append(line)
               .append(", :m").append(line).append(")");
            params.put("l" + line, line);
            params.put("a" + line, account.id());
            params.put("c" + line, account.currency());
            params.put("m" + line, leg.amount());
        }
        var spec = db.sql(sql.toString());
        for (var entry : params.entrySet()) {
            spec = spec.param(entry.getKey(), entry.getValue());
        }
        int inserted = spec.update();
        if (inserted != legs.size()) {
            throw new IllegalStateException("Expected to insert " + legs.size() + " journal entries, but inserted " + inserted);
        }
    }

    public record BatchPostingOutcome(int index, String scope, String key, Posted posted, ApiException error) {
        public boolean succeeded() {
            return posted != null && error == null;
        }
    }

    /**
     * Financially safe batch ledger posting (Requirement 6).
     *
     * <p>Locks all distinct accounts across the batch in deterministic {@code id} order in a single
     * {@code SELECT ... FOR UPDATE}, validates each posting's double-entry zero-sum and non-negative
     * balance invariants against a running in-memory balance projection, and isolates individual
     * failed postings via JDBC savepoints so valid postings in the batch commit atomically without
     * committing partial or invalid entries.
     */
    @Transactional
    public List<BatchPostingOutcome> postBatch(List<Posting> postings) {
        if (postings == null || postings.isEmpty()) {
            return List.of();
        }
        // Pre-lock all valid known accounts across the batch in deterministic id order
        List<String> allCandidateCodes = postings.stream()
                .filter(Objects::nonNull)
                .flatMap(p -> p.legs() == null ? java.util.stream.Stream.empty() : p.legs().stream())
                .filter(Objects::nonNull)
                .map(Leg::account)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        if (!allCandidateCodes.isEmpty()) {
            db.sql("""
                    SELECT id FROM ledger_account
                    WHERE code IN (:codes)
                    ORDER BY id FOR UPDATE""")
                    .param("codes", allCandidateCodes)
                    .query(Long.class)
                    .list();
        }

        List<BatchPostingOutcome> outcomes = new ArrayList<>(postings.size());
        for (int i = 0; i < postings.size(); i++) {
            Posting p = postings.get(i);
            String scope = p != null ? p.scope() : null;
            String key = p != null ? p.key() : null;
            Object savepoint = null;
            try {
                if (TransactionSynchronizationManager.isActualTransactionActive()) {
                    var status = org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus();
                    savepoint = status.createSavepoint();
                }
                Posted posted = write(p, null);
                if (savepoint != null) {
                    org.springframework.transaction.interceptor.TransactionAspectSupport
                            .currentTransactionStatus().releaseSavepoint(savepoint);
                }
                outcomes.add(new BatchPostingOutcome(i, scope, key, posted, null));
            } catch (RuntimeException ex) {
                if (savepoint != null) {
                    var status = org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus();
                    status.rollbackToSavepoint(savepoint);
                    status.releaseSavepoint(savepoint);
                }
                if (com.elepay.common.FinancialTxRetry.isTransientConcurrencyFailure(ex)) {
                    throw ex;
                }
                ApiException apiEx = (ex instanceof ApiException ae)
                        ? ae
                        : ApiException.badRequest("invalid_posting",
                                ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
                outcomes.add(new BatchPostingOutcome(i, scope, key, null, apiEx));
            }
        }
        return outcomes;
    }

    private Map<String, Account> lock(Collection<String> codes) {
        List<String> distinct = codes.stream().distinct().sorted().toList();
        // Do NOT retry 40P01/40001 inside an active PostgreSQL transaction: PostgreSQL immediately
        // marks the transaction aborted (subsequent statements fail with SQLSTATE 25P02).
        // Let ConcurrencyFailureException / PessimisticLockingFailureException propagate so Spring
        // rolls back cleanly and outer transaction-boundary retry (FinancialTxRetry) starts a new tx.
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

    public static final String CLEARING_MOMO = "system:clearing:sandbox:GHS";
    public static final String REVENUE = "system:revenue:GHS";
    public static final String DEFERRED_REVENUE = "system:deferred_revenue:GHS";
    public static final String REVENUE_EARNED = "system:revenue:earned:GHS";
    public static final String REVENUE_SWEEP_RESERVED = "system:revenue_sweep_reserved:GHS";
    public static final String SUSPENSE = "system:suspense:GHS";
    public static final String REFUNDS_IN_FLIGHT = "system:refunds_in_flight:GHS";

    /** Configured number of stripes per hot system account (1..256). */
    public int stripeCount() {
        return systemStripes;
    }

    /** Deterministic stripe index in {@code [0, stripeCount() - 1]} for a routing key. */
    public int stripeIndex(Object routingKey) {
        if (systemStripes <= 1 || routingKey == null) {
            return 0;
        }
        byte[] bytes = routingKey.toString().getBytes(StandardCharsets.UTF_8);
        int h = 0x811c9dc5;
        for (byte b : bytes) {
            h ^= (b & 0xff);
            h *= 0x01000193;
        }
        // Murmur3 32-bit finalizer for uniform avalanche across stripe buckets.
        h ^= (h >>> 16);
        h *= 0x85ebca6b;
        h ^= (h >>> 13);
        h *= 0xc2b2ae35;
        h ^= (h >>> 16);
        return Math.floorMod(h, systemStripes);
    }

    /** Returns the striped account code for {@code baseCode} and {@code routingKey}. */
    public String stripedCode(String baseCode, Object routingKey) {
        if (systemStripes <= 1) {
            return baseCode;
        }
        return baseCode + ":s" + String.format(Locale.ROOT, "%02d", stripeIndex(routingKey));
    }

    /** Returns true when {@code code} ends with a stripe suffix ({@code :s00}..{@code :s255}). */
    public static boolean isStripeCode(String code) {
        if (code == null) return false;
        int idx = code.lastIndexOf(":s");
        if (idx <= 0) return false;
        int digitLen = code.length() - (idx + 2);
        if (digitLen < 2 || digitLen > 3) return false;
        for (int i = idx + 2; i < code.length(); i++) {
            char ch = code.charAt(i);
            if (ch < '0' || ch > '9') return false;
        }
        return true;
    }

    /** Creates a system account with an explicit code (clearing, partner accounts) if needed. */
    @Transactional
    public String openSystem(String code, String bucket, String currency, boolean allowNegative) {
        db.sql("""
                INSERT INTO ledger_account (code, owner_type, bucket, currency, allow_negative)
                VALUES (:c, 'system', :b, :cur, :neg) ON CONFLICT (code) DO NOTHING""")
                .param("c", code).param("b", bucket).param("cur", currency).param("neg", allowNegative).update();
        return code;
    }

    /**
     * Opens (if needed) the deterministic stripe for a hot system account and caches its
     * existence only after transaction commit so rolled-back transactions cannot poison the cache.
     */
    @Transactional
    public String openStripedSystem(String baseCode, String bucket, String currency,
                                    boolean allowNegative, Object routingKey) {
        String code = stripedCode(baseCode, routingKey);
        if (knownStripes.contains(code)) {
            return code;
        }
        db.sql("""
                INSERT INTO ledger_account (code, owner_type, bucket, currency, allow_negative)
                VALUES (:c, 'system', :b, :cur, :neg) ON CONFLICT (code) DO NOTHING""")
                .param("c", code).param("b", bucket).param("cur", currency).param("neg", allowNegative).update();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    knownStripes.add(code);
                }
            });
        } else {
            knownStripes.add(code);
        }
        return code;
    }

    @Transactional
    public String clearingStripe(String provider, String currency, Object routingKey) {
        return openStripedSystem("system:clearing:" + provider + ":" + currency, "clearing", currency, true, routingKey);
    }

    @Transactional
    public String clearingStripe(String provider, Object routingKey) {
        return clearingStripe(provider, GHS, routingKey);
    }

    @Transactional
    public String deferredRevenueStripe(String currency, Object routingKey) {
        return openStripedSystem("system:deferred_revenue:" + currency, "deferred_revenue", currency, true, routingKey);
    }

    @Transactional
    public String revenueStripe(String currency, Object routingKey) {
        return openStripedSystem("system:revenue:" + currency, "revenue", currency, false, routingKey);
    }

    /** Codes of unstriped system accounts in a bucket whose code starts with {@code prefix}. */
    public List<String> systemCodes(String prefix, String bucket, String currency) {
        return db.sql("""
                SELECT code FROM ledger_account
                WHERE owner_type = 'system' AND bucket = :b AND currency = :c AND starts_with(code, :p) ORDER BY code""")
                .param("b", bucket).param("c", currency).param("p", prefix).query(String.class).list()
                .stream().filter(c -> !isStripeCode(c)).toList();
    }

    /** Owners whose account in this bucket holds a positive balance. */
    public List<UUID> ownersWithBalance(String ownerType, String bucket, String currency) {
        return db.sql("""
                SELECT owner_id FROM ledger_account
                WHERE owner_type = :t AND bucket = :b AND currency = :c AND balance > 0 ORDER BY owner_id""")
                .param("t", ownerType).param("b", bucket).param("c", currency).query(UUID.class).list();
    }

    /** Sum of positive balances for all accounts of {@code ownerType} in {@code bucket} and {@code currency}. */
    public long sumPositiveOwnerBucket(String ownerType, String bucket, String currency) {
        return db.sql("""
                SELECT COALESCE(SUM(balance), 0)::bigint
                FROM ledger_account
                WHERE owner_type = :t AND bucket = :b AND currency = :c AND balance > 0""")
                .param("t", ownerType).param("b", bucket).param("c", currency)
                .query(Long.class).single();
    }

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

    /** Exact single-row balance for {@code exactCode} without stripe aggregation. */
    public long rawBalance(String exactCode) {
        return db.sql("SELECT balance FROM ledger_account WHERE code = :code")
                .param("code", exactCode).query(Long.class).optional().orElse(0L);
    }

    /** Locks {@code exactCode} row {@code FOR UPDATE} and returns its current balance (or 0 if absent). */
    public long lockBalance(String exactCode) {
        return db.sql("SELECT balance FROM ledger_account WHERE code = :code FOR UPDATE")
                .param("code", exactCode).query(Long.class).optional().orElse(0L);
    }

    /**
     * Locks a system base account and all its {@code :sNN} stripes in deterministic {@code id} order
     * ({@code FOR UPDATE}) and returns the checked sum of their balances.
     */
    public long lockStripedBalance(String baseCode) {
        List<Long> balances = db.sql("""
                SELECT balance FROM ledger_account
                WHERE code = :base OR starts_with(code, :prefix)
                ORDER BY id FOR UPDATE""")
                .param("base", baseCode)
                .param("prefix", baseCode + ":s")
                .query(Long.class)
                .list();
        long sum = 0L;
        for (Long b : balances) {
            sum = Math.addExact(sum, b == null ? 0L : b);
        }
        return sum;
    }

    /**
     * Balance of an account. For unstriped {@code system:} base codes, transparently aggregates
     * the base account plus all {@code :sNN} stripes.
     */
    public long balance(String code) {
        if (code != null && code.startsWith("system:") && !isStripeCode(code)) {
            return db.sql("""
                    SELECT COALESCE(SUM(balance), 0)::bigint
                    FROM ledger_account
                    WHERE code = :code OR starts_with(code, :stripePrefix)""")
                    .param("code", code)
                    .param("stripePrefix", code + ":s")
                    .query(Long.class).single();
        }
        return rawBalance(code);
    }

    /** Per-stripe balances (including base code if present) ordered by account code. */
    public Map<String, Long> stripeBalances(String baseCode) {
        Map<String, Long> balances = new LinkedHashMap<>();
        db.sql("""
                SELECT code, balance
                FROM ledger_account
                WHERE code = :code OR starts_with(code, :stripePrefix)
                ORDER BY code""")
                .param("code", baseCode)
                .param("stripePrefix", baseCode + ":s")
                .query((rs, i) -> balances.put(rs.getString(1), rs.getLong(2))).list();
        return balances;
    }

    /**
     * Allocates debit legs across the base account and positive-balance stripes for {@code baseCode}.
     * Candidate rows are locked in {@code id} order (matching {@link #lock(Collection)}) to prevent
     * AB-BA deadlocks, then drained deterministically by account code.
     */
    @Transactional
    public List<Leg> debitAcrossStripes(String baseCode, long amount) {
        return debitStripeOrAcrossStripes(baseCode, null, amount);
    }

    /**
     * TOCTOU-free and deadlock-safe debit allocation across stripes for {@code baseCode}, preferring
     * the stripe for {@code routingKey} first when it has a positive locked balance, and draining
     * remaining positive-balance stripes in deterministic account code order. All candidate rows are
     * locked in a single {@code ORDER BY id FOR UPDATE} query.
     */
    @Transactional
    public List<Leg> debitStripeOrAcrossStripes(String baseCode, Object routingKey, long amount) {
        if (amount <= 0) {
            throw ApiException.badRequest("invalid_amount", "Leg amount must be positive");
        }
        String preferredCode = routingKey != null ? stripedCode(baseCode, routingKey) : null;
        List<Account> candidates = new ArrayList<>(db.sql("""
                SELECT id, code, owner_type, owner_id, bucket, currency, allow_negative, balance
                FROM ledger_account
                WHERE (code = :base OR starts_with(code, :stripePrefix))
                  AND balance > 0
                ORDER BY id FOR UPDATE""")
                .param("base", baseCode)
                .param("stripePrefix", baseCode + ":s")
                .query(Ledger::account).list());
        candidates.sort((a, b) -> {
            if (preferredCode != null) {
                boolean aPref = preferredCode.equals(a.code());
                boolean bPref = preferredCode.equals(b.code());
                if (aPref != bPref) {
                    return aPref ? -1 : 1;
                }
            }
            return a.code().compareTo(b.code());
        });
        List<Leg> legs = new ArrayList<>();
        long remaining = amount;
        for (Account account : candidates) {
            if (remaining == 0) break;
            long take = Math.min(account.balance(), remaining);
            legs.add(Leg.debit(account.code(), take));
            remaining -= take;
        }
        if (remaining > 0) {
            throw ApiException.insufficientFunds();
        }
        return legs;
    }

    /**
     * When the oldest money still sitting in this account arrived, matching first-in, first-out: the current
     * balance is made of the most recent credits, so the oldest credit still needed to cover it dates it.
     * Empty when the balance is zero or negative. Used to spot merchant funds held past the agreed
     * settlement period (BoG Notice BG/GOV/SEC/2026/43).
     */
    public Optional<OffsetDateTime> oldestUnspentCreditAt(String code) {
        long balance = balance(code);
        if (balance <= 0) return Optional.empty();
        return db.sql("""
                SELECT min(posted_at) FROM (
                    SELECT t.posted_at, e.amount,
                           sum(e.amount) OVER (ORDER BY t.posted_at DESC, e.transaction_id DESC, e.line DESC) AS cum
                    FROM journal_entry e
                    JOIN journal_transaction t ON t.id = e.transaction_id
                    JOIN ledger_account a ON a.id = e.account_id
                    WHERE a.code = :code AND e.amount > 0) credits
                WHERE credits.cum - credits.amount < :balance""")
                .param("code", code).param("balance", balance)
                .query(OffsetDateTime.class).optional();
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
