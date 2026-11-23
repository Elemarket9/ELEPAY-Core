package com.elepay.enterprise.accounts;

import com.elepay.common.ApiException;
import com.elepay.common.Idempotency;
import com.elepay.ledger.Ledger;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Enterprise Managed Accounts Engine.
 *
 * <p>Handles enterprise registration, deterministic external identity mapping,
 * duplicate detection, KYC tier synchronization, and double-entry subaccount
 * ledger management (operating, holding, reserved, rewards) wired via {@link Ledger}.
 */
@Service
public class EnterpriseManagedAccountService {
    public static final String OWNER_TYPE_ENTERPRISE_MANAGED = "enterprise_managed";
    public static final String GHS = "GHS";

    private final JdbcClient db;
    private final Ledger ledger;
    private final ObjectMapper json;

    public EnterpriseManagedAccountService(JdbcClient db, Ledger ledger, ObjectMapper json) {
        this.db = db;
        this.ledger = ledger;
        this.json = json;
    }

    // =========================================================================
    // Enterprise Management & KYC Tier Limits
    // =========================================================================

    @Transactional
    public Enterprise registerEnterprise(String code, String name, String contactEmail,
                                         String contactPhone, String webhookUrl) {
        if (code == null || code.isBlank()) {
            throw ApiException.badRequest("invalid_code", "Enterprise code is required");
        }
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("invalid_name", "Enterprise name is required");
        }
        UUID id = UUID.randomUUID();
        String normalizedCode = code.trim().toUpperCase(Locale.ROOT);

        db.sql("""
                INSERT INTO enterprise (id, code, name, status, contact_email, contact_phone, webhook_url, kyc_status)
                VALUES (:id, :code, :name, 'active', :email, :phone, :webhook, 'verified')
                """)
                .param("id", id)
                .param("code", normalizedCode)
                .param("name", name.trim())
                .param("email", contactEmail != null ? contactEmail.trim().toLowerCase(Locale.ROOT) : null)
                .param("phone", contactPhone != null ? contactPhone.trim() : null)
                .param("webhook", webhookUrl != null ? webhookUrl.trim() : null)
                .update();

        // Initialize default tier limits for the enterprise
        for (int tier = 1; tier <= 3; tier++) {
            EnterpriseTierLimit def = EnterpriseTierLimit.defaultForTier(id, tier);
            configureTierLimit(id, tier, def.maxBalance(), def.dailyLimit(), def.monthlyLimit(), def.perTransactionLimit());
        }

        // Open default enterprise clearing account in Ledger
        ledger.openSystem(enterpriseClearingCode(id, GHS), "clearing", GHS, true);

        return getEnterprise(id);
    }

    public Optional<Enterprise> findEnterprise(UUID enterpriseId) {
        return db.sql("""
                SELECT id, code, name, status, contact_email, contact_phone, webhook_url, kyc_status, created_at, updated_at
                FROM enterprise WHERE id = :id
                """)
                .param("id", enterpriseId)
                .query(this::mapEnterprise)
                .optional();
    }

    public Optional<Enterprise> findEnterpriseByCode(String code) {
        if (code == null) return Optional.empty();
        return db.sql("""
                SELECT id, code, name, status, contact_email, contact_phone, webhook_url, kyc_status, created_at, updated_at
                FROM enterprise WHERE code = :code
                """)
                .param("code", code.trim().toUpperCase(Locale.ROOT))
                .query(this::mapEnterprise)
                .optional();
    }

    public Enterprise getEnterprise(UUID enterpriseId) {
        return findEnterprise(enterpriseId)
                .orElseThrow(() -> ApiException.notFound("enterprise_not_found", "Enterprise not found: " + enterpriseId));
    }

    @Transactional
    public EnterpriseTierLimit configureTierLimit(UUID enterpriseId, int tier, long maxBalance,
                                                  long dailyLimit, long monthlyLimit, long perTransactionLimit) {
        if (tier < 1 || tier > 3) {
            throw ApiException.badRequest("invalid_tier", "KYC tier must be between 1 and 3");
        }
        if (maxBalance < 0 || dailyLimit < 0 || monthlyLimit < 0 || perTransactionLimit < 0) {
            throw ApiException.badRequest("invalid_limit", "Limits must be non-negative");
        }
        UUID limitId = UUID.randomUUID();

        db.sql("""
                INSERT INTO enterprise_tier_limit (id, enterprise_id, kyc_tier, max_balance, daily_limit, monthly_limit, per_transaction_limit, updated_at)
                VALUES (:id, :eid, :tier, :mb, :dl, :ml, :ptl, now())
                ON CONFLICT (enterprise_id, kyc_tier) DO UPDATE SET
                    max_balance = EXCLUDED.max_balance,
                    daily_limit = EXCLUDED.daily_limit,
                    monthly_limit = EXCLUDED.monthly_limit,
                    per_transaction_limit = EXCLUDED.per_transaction_limit,
                    updated_at = now()
                """)
                .param("id", limitId)
                .param("eid", enterpriseId)
                .param("tier", tier)
                .param("mb", maxBalance)
                .param("dl", dailyLimit)
                .param("ml", monthlyLimit)
                .param("ptl", perTransactionLimit)
                .update();

        return getTierLimit(enterpriseId, tier);
    }

    public EnterpriseTierLimit getTierLimit(UUID enterpriseId, int tier) {
        if (tier < 1 || tier > 3) {
            throw ApiException.badRequest("invalid_tier", "KYC tier must be between 1 and 3");
        }
        return db.sql("""
                SELECT id, enterprise_id, kyc_tier, max_balance, daily_limit, monthly_limit, per_transaction_limit, created_at, updated_at
                FROM enterprise_tier_limit
                WHERE enterprise_id = :eid AND kyc_tier = :tier
                """)
                .param("eid", enterpriseId)
                .param("tier", tier)
                .query((rs, i) -> new EnterpriseTierLimit(
                        rs.getObject("id", UUID.class),
                        rs.getObject("enterprise_id", UUID.class),
                        rs.getInt("kyc_tier"),
                        rs.getLong("max_balance"),
                        rs.getLong("daily_limit"),
                        rs.getLong("monthly_limit"),
                        rs.getLong("per_transaction_limit"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class)
                ))
                .optional()
                .orElseGet(() -> EnterpriseTierLimit.defaultForTier(enterpriseId, tier));
    }

    // =========================================================================
    // Deterministic Identity Mapping & Account Provisioning
    // =========================================================================

    public String computeIdentityHash(UUID enterpriseId, String externalCustomerId) {
        if (enterpriseId == null || externalCustomerId == null || externalCustomerId.isBlank()) {
            throw ApiException.badRequest("invalid_identity", "enterpriseId and externalCustomerId are required");
        }
        return Idempotency.sha256(enterpriseId + ":" + externalCustomerId.trim());
    }

    public UUID deriveDeterministicAccountId(UUID enterpriseId, String externalCustomerId) {
        if (enterpriseId == null || externalCustomerId == null || externalCustomerId.isBlank()) {
            throw ApiException.badRequest("invalid_identity", "enterpriseId and externalCustomerId are required");
        }
        String key = enterpriseId + ":" + externalCustomerId.trim();
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    public static String enterpriseClearingCode(UUID enterpriseId, String currency) {
        return "system:clearing:enterprise:" + enterpriseId + ":" + currency.toUpperCase(Locale.ROOT);
    }

    public static String subaccountCode(UUID accountId, SubaccountBucket bucket, String currency) {
        return Ledger.code(OWNER_TYPE_ENTERPRISE_MANAGED, accountId, bucket.code(), currency.toUpperCase(Locale.ROOT));
    }

    @Transactional
    public EnterpriseManagedAccount createAccount(CreateManagedAccountRequest request) {
        if (request.enterpriseId() == null) {
            throw ApiException.badRequest("missing_enterprise", "enterpriseId is required");
        }
        if (request.externalCustomerId() == null || request.externalCustomerId().isBlank()) {
            throw ApiException.badRequest("missing_external_id", "externalCustomerId is required");
        }
        if (request.displayName() == null || request.displayName().isBlank()) {
            throw ApiException.badRequest("missing_display_name", "displayName is required");
        }
        if (request.initialTier() != null && (request.initialTier() < 1 || request.initialTier() > 3)) {
            throw ApiException.badRequest("invalid_tier", "initialTier must be between 1 and 3");
        }

        Enterprise enterprise = getEnterprise(request.enterpriseId());
        if (!enterprise.isActive()) {
            throw ApiException.forbidden("Enterprise is inactive: " + enterprise.code());
        }

        String externalId = request.externalCustomerId().trim();
        String identityHash = computeIdentityHash(enterprise.id(), externalId);

        // Check if account already exists under this deterministic identity
        Optional<EnterpriseManagedAccount> existing = findAccount(enterprise.id(), externalId);
        if (existing.isPresent()) {
            return existing.get();
        }

        UUID accountId = deriveDeterministicAccountId(enterprise.id(), externalId);
        String currency = (request.currency() != null && !request.currency().isBlank())
                ? request.currency().trim().toUpperCase(Locale.ROOT) : GHS;

        String phone = normalizePhone(request.phone());
        String email = normalizeEmail(request.email());
        String nationalId = normalizeNationalId(request.nationalId());

        // Duplication Detection across phone, email, and national ID
        List<DuplicateMatch> duplicates = detectDuplicateMatches(enterprise.id(), phone, email, nationalId, null);

        if (!duplicates.isEmpty() && request.duplicateAction() == DuplicateAction.REJECT) {
            DuplicateMatch first = duplicates.get(0);
            throw ApiException.conflict("duplicate_account",
                    "Duplicate account detected matching " + first.field() + " (" + first.value() + ") with existing account " + first.matchedAccountId());
        }

        String status = duplicates.isEmpty() ? "active" : "flagged_duplicate";
        int tier = request.initialTier() != null ? request.initialTier() : 1;
        String accountType = request.accountType() != null ? request.accountType() : "customer";
        String metadataJson = serializeMetadata(request.metadata());

        // 1. Insert into enterprise_managed_account
        db.sql("""
                INSERT INTO enterprise_managed_account
                    (id, enterprise_id, external_customer_id, account_type, display_name, phone, email, national_id, kyc_tier, status, currency, metadata)
                VALUES
                    (:id, :eid, :extId, :type, :name, :phone, :email, :nid, :tier, :status, :cur, CAST(:meta AS jsonb))
                """)
                .param("id", accountId)
                .param("eid", enterprise.id())
                .param("extId", externalId)
                .param("type", accountType)
                .param("name", request.displayName().trim())
                .param("phone", phone)
                .param("email", email)
                .param("nid", nationalId)
                .param("tier", tier)
                .param("status", status)
                .param("cur", currency)
                .param("meta", metadataJson)
                .update();

        // 2. Insert into enterprise_identity_mapping
        db.sql("""
                INSERT INTO enterprise_identity_mapping (id, enterprise_id, external_customer_id, elepay_account_id, id_hash)
                VALUES (:id, :eid, :extId, :aid, :hash)
                """)
                .param("id", UUID.randomUUID())
                .param("eid", enterprise.id())
                .param("extId", externalId)
                .param("aid", accountId)
                .param("hash", identityHash)
                .update();

        // 3. Record duplicate tracking if duplicates detected
        for (DuplicateMatch dup : duplicates) {
            db.sql("""
                    INSERT INTO enterprise_duplicate_tracking (id, enterprise_id, account_id, matched_account_id, match_field, match_value, resolution)
                    VALUES (:id, :eid, :aid, :maid, :mf, :mv, 'flagged')
                    """)
                    .param("id", UUID.randomUUID())
                    .param("eid", enterprise.id())
                    .param("aid", accountId)
                    .param("maid", dup.matchedAccountId())
                    .param("mf", dup.field())
                    .param("mv", dup.value())
                    .update();
        }

        // 4. Provision 4 subaccount balances in authoritative Ledger
        for (SubaccountBucket bucket : SubaccountBucket.values()) {
            ledger.open(OWNER_TYPE_ENTERPRISE_MANAGED, accountId, bucket.code(), currency);
        }

        // 5. Ensure enterprise clearing account exists
        ledger.openSystem(enterpriseClearingCode(enterprise.id(), currency), "clearing", currency, true);

        return getAccountById(accountId);
    }

    @Transactional
    public BulkCreateResult createAccountsBulk(BulkCreateAccountsRequest request) {
        if (request.enterpriseId() == null) {
            throw ApiException.badRequest("missing_enterprise", "enterpriseId is required");
        }
        Enterprise enterprise = getEnterprise(request.enterpriseId());
        if (!enterprise.isActive()) {
            throw ApiException.forbidden("Enterprise is inactive: " + enterprise.code());
        }

        List<ManagedAccountResult> results = new ArrayList<>();
        List<BulkItemError> errors = new ArrayList<>();
        int createdCount = 0;
        int existingCount = 0;
        int duplicatesFlagged = 0;

        for (CreateManagedAccountRequest item : request.accounts()) {
            try {
                // Default duplicate action from bulk request if not specified per-item
                CreateManagedAccountRequest effective = new CreateManagedAccountRequest(
                        item.enterpriseId() != null ? item.enterpriseId() : request.enterpriseId(),
                        item.externalCustomerId(),
                        item.displayName(),
                        item.phone(),
                        item.email(),
                        item.nationalId(),
                        item.initialTier(),
                        item.accountType(),
                        item.currency(),
                        item.metadata(),
                        item.duplicateAction() != null ? item.duplicateAction() : request.defaultDuplicateAction()
                );

                Optional<EnterpriseManagedAccount> existing = findAccount(enterprise.id(), effective.externalCustomerId());
                boolean isNew = existing.isEmpty();

                EnterpriseManagedAccount account = createAccount(effective);
                SubaccountBalances balances = getSubaccountBalances(account.id(), account.currency());

                if (isNew) {
                    createdCount++;
                    if (account.isFlaggedDuplicate()) {
                        duplicatesFlagged++;
                    }
                } else {
                    existingCount++;
                }

                results.add(new ManagedAccountResult(
                        account.externalCustomerId(),
                        account.id(),
                        account.status(),
                        isNew,
                        account.isFlaggedDuplicate(),
                        balances
                ));
            } catch (ApiException e) {
                errors.add(new BulkItemError(item.externalCustomerId(), e.code(), e.getMessage()));
            } catch (Exception e) {
                errors.add(new BulkItemError(item.externalCustomerId(), "internal_error", e.getMessage()));
            }
        }

        return new BulkCreateResult(
                request.accounts().size(),
                createdCount,
                existingCount,
                duplicatesFlagged,
                errors.size(),
                results,
                errors
        );
    }

    public BulkCreateResult bulkCreateAccounts(BulkCreateAccountsRequest request) {
        return createAccountsBulk(request);
    }

    public EnterpriseManagedAccount getAccountByExternalId(UUID enterpriseId, String externalCustomerId) {
        return getAccount(enterpriseId, externalCustomerId);
    }

    // =========================================================================
    // Query & Identity Resolution
    // =========================================================================

    public Optional<EnterpriseManagedAccount> findAccount(UUID enterpriseId, String externalCustomerId) {
        if (enterpriseId == null || externalCustomerId == null) return Optional.empty();
        return db.sql("""
                SELECT id, enterprise_id, external_customer_id, account_type, display_name, phone, email, national_id,
                       kyc_tier, status, currency, metadata, created_at, updated_at
                FROM enterprise_managed_account
                WHERE enterprise_id = :eid AND external_customer_id = :extId
                """)
                .param("eid", enterpriseId)
                .param("extId", externalCustomerId.trim())
                .query(this::mapAccount)
                .optional();
    }

    public EnterpriseManagedAccount getAccount(UUID enterpriseId, String externalCustomerId) {
        return findAccount(enterpriseId, externalCustomerId)
                .orElseThrow(() -> ApiException.notFound("account_not_found",
                        "Managed account not found for enterprise " + enterpriseId + " and external ID " + externalCustomerId));
    }

    public Optional<EnterpriseManagedAccount> findAccountById(UUID accountId) {
        if (accountId == null) return Optional.empty();
        return db.sql("""
                SELECT id, enterprise_id, external_customer_id, account_type, display_name, phone, email, national_id,
                       kyc_tier, status, currency, metadata, created_at, updated_at
                FROM enterprise_managed_account
                WHERE id = :id
                """)
                .param("id", accountId)
                .query(this::mapAccount)
                .optional();
    }

    public EnterpriseManagedAccount getAccountById(UUID accountId) {
        return findAccountById(accountId)
                .orElseThrow(() -> ApiException.notFound("account_not_found", "Managed account not found: " + accountId));
    }

    public Optional<EnterpriseManagedAccount> findAccountByIdentityHash(String idHash) {
        if (idHash == null || idHash.isBlank()) return Optional.empty();
        return db.sql("""
                SELECT a.id, a.enterprise_id, a.external_customer_id, a.account_type, a.display_name, a.phone, a.email,
                       a.national_id, a.kyc_tier, a.status, a.currency, a.metadata, a.created_at, a.updated_at
                FROM enterprise_managed_account a
                JOIN enterprise_identity_mapping m ON m.elepay_account_id = a.id
                WHERE m.id_hash = :hash
                """)
                .param("hash", idHash.trim())
                .query(this::mapAccount)
                .optional();
    }

    public Optional<EnterpriseIdentityMapping> getIdentityMapping(UUID enterpriseId, String externalCustomerId) {
        if (enterpriseId == null || externalCustomerId == null) return Optional.empty();
        return db.sql("""
                SELECT id, enterprise_id, external_customer_id, elepay_account_id, id_hash, mapped_at
                FROM enterprise_identity_mapping
                WHERE enterprise_id = :eid AND external_customer_id = :extId
                """)
                .param("eid", enterpriseId)
                .param("extId", externalCustomerId.trim())
                .query((rs, i) -> new EnterpriseIdentityMapping(
                        rs.getObject("id", UUID.class),
                        rs.getObject("enterprise_id", UUID.class),
                        rs.getString("external_customer_id"),
                        rs.getObject("elepay_account_id", UUID.class),
                        rs.getString("id_hash"),
                        rs.getObject("mapped_at", OffsetDateTime.class)
                ))
                .optional();
    }

    public String getLedgerAccountCode(UUID enterpriseId, String externalCustomerId, SubaccountBucket bucket, String currency) {
        EnterpriseManagedAccount account = getAccount(enterpriseId, externalCustomerId);
        String curr = (currency != null && !currency.isBlank()) ? currency : account.currency();
        return subaccountCode(account.id(), bucket, curr);
    }

    // =========================================================================
    // Duplicate Tracking & Resolution
    // =========================================================================

    public record DuplicateMatch(String field, String value, UUID matchedAccountId) {}

    public List<DuplicateMatch> detectDuplicateMatches(UUID enterpriseId, String phone, String email,
                                                       String nationalId, UUID excludeAccountId) {
        List<DuplicateMatch> matches = new ArrayList<>();

        if (phone != null && !phone.isBlank()) {
            String sql = excludeAccountId != null
                    ? "SELECT id FROM enterprise_managed_account WHERE enterprise_id = :eid AND phone = :phone AND id <> :exclude"
                    : "SELECT id FROM enterprise_managed_account WHERE enterprise_id = :eid AND phone = :phone";
            var spec = db.sql(sql).param("eid", enterpriseId).param("phone", phone);
            if (excludeAccountId != null) spec = spec.param("exclude", excludeAccountId);
            List<UUID> matched = spec.query(UUID.class).list();
            for (UUID mid : matched) {
                matches.add(new DuplicateMatch("phone", phone, mid));
            }
        }

        if (email != null && !email.isBlank()) {
            String sql = excludeAccountId != null
                    ? "SELECT id FROM enterprise_managed_account WHERE enterprise_id = :eid AND lower(email) = :email AND id <> :exclude"
                    : "SELECT id FROM enterprise_managed_account WHERE enterprise_id = :eid AND lower(email) = :email";
            var spec = db.sql(sql).param("eid", enterpriseId).param("email", email);
            if (excludeAccountId != null) spec = spec.param("exclude", excludeAccountId);
            List<UUID> matched = spec.query(UUID.class).list();
            for (UUID mid : matched) {
                matches.add(new DuplicateMatch("email", email, mid));
            }
        }

        if (nationalId != null && !nationalId.isBlank()) {
            String sql = excludeAccountId != null
                    ? "SELECT id FROM enterprise_managed_account WHERE enterprise_id = :eid AND national_id = :nid AND id <> :exclude"
                    : "SELECT id FROM enterprise_managed_account WHERE enterprise_id = :eid AND national_id = :nid";
            var spec = db.sql(sql).param("eid", enterpriseId).param("nid", nationalId);
            if (excludeAccountId != null) spec = spec.param("exclude", excludeAccountId);
            List<UUID> matched = spec.query(UUID.class).list();
            for (UUID mid : matched) {
                matches.add(new DuplicateMatch("national_id", nationalId, mid));
            }
        }

        return matches;
    }

    public List<EnterpriseDuplicateRecord> getDuplicatesForAccount(UUID accountId) {
        return db.sql("""
                SELECT id, enterprise_id, account_id, matched_account_id, match_field, match_value, resolution, resolution_notes, detected_at, resolved_at
                FROM enterprise_duplicate_tracking
                WHERE account_id = :aid OR matched_account_id = :aid
                ORDER BY detected_at DESC
                """)
                .param("aid", accountId)
                .query((rs, i) -> new EnterpriseDuplicateRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("enterprise_id", UUID.class),
                        rs.getObject("account_id", UUID.class),
                        rs.getObject("matched_account_id", UUID.class),
                        rs.getString("match_field"),
                        rs.getString("match_value"),
                        rs.getString("resolution"),
                        rs.getString("resolution_notes"),
                        rs.getObject("detected_at", OffsetDateTime.class),
                        rs.getObject("resolved_at", OffsetDateTime.class)
                ))
                .list();
    }

    @Transactional
    public void resolveDuplicate(UUID duplicateId, DuplicateResolution resolution, String notes) {
        int updated = db.sql("""
                UPDATE enterprise_duplicate_tracking
                SET resolution = :res, resolution_notes = :notes, resolved_at = now()
                WHERE id = :id
                """)
                .param("id", duplicateId)
                .param("res", resolution.name().toLowerCase(Locale.ROOT))
                .param("notes", notes)
                .update();
        if (updated == 0) {
            throw ApiException.notFound("duplicate_not_found", "Duplicate tracking record not found: " + duplicateId);
        }
    }

    // =========================================================================
    // KYC Tier Synchronization & Limits
    // =========================================================================

    @Transactional
    public EnterpriseManagedAccount syncKycTier(KycSyncRequest request) {
        if (request.accountId() == null) {
            throw ApiException.badRequest("missing_account", "accountId is required");
        }
        if (request.newTier() < 1 || request.newTier() > 3) {
            throw ApiException.badRequest("invalid_tier", "newTier must be between 1 and 3");
        }

        EnterpriseManagedAccount account = getAccountById(request.accountId());
        EnterpriseTierLimit tierLimit = getTierLimit(account.enterpriseId(), request.newTier());

        // Validate that current balance does not exceed the target tier ceiling
        SubaccountBalances balances = getSubaccountBalances(account.id(), account.currency());
        if (balances.total() > tierLimit.maxBalance()) {
            throw ApiException.badRequest("balance_exceeds_tier_limit",
                    "Total balance (" + balances.total() + ") exceeds Tier " + request.newTier() + " limit (" + tierLimit.maxBalance() + ")");
        }

        // Merge KYC evidence into metadata
        Map<String, Object> updatedMeta = new HashMap<>(account.metadata());
        updatedMeta.put("kyc_synced_at", OffsetDateTime.now().toString());
        if (request.reason() != null) updatedMeta.put("kyc_reason", request.reason());
        if (!request.kycEvidence().isEmpty()) updatedMeta.put("kyc_evidence", request.kycEvidence());

        // If tier upgraded and account was previously flagged duplicate, check if evidence clears it
        String newStatus = account.status();
        if (request.newTier() >= 2 && "flagged_duplicate".equalsIgnoreCase(account.status())) {
            newStatus = "active";
        }

        db.sql("""
                UPDATE enterprise_managed_account
                SET kyc_tier = :tier, status = :status, metadata = CAST(:meta AS jsonb), updated_at = now()
                WHERE id = :id
                """)
                .param("tier", request.newTier())
                .param("status", newStatus)
                .param("meta", serializeMetadata(updatedMeta))
                .param("id", account.id())
                .update();

        return getAccountById(account.id());
    }

    public void validateTransactionLimit(UUID accountId, long amountPesewas, String currency) {
        if (amountPesewas <= 0) {
            throw ApiException.badRequest("invalid_amount", "Transaction amount must be positive");
        }
        EnterpriseManagedAccount account = getAccountById(accountId);
        EnterpriseTierLimit limit = getTierLimit(account.enterpriseId(), account.kycTier());

        if (amountPesewas > limit.perTransactionLimit()) {
            throw ApiException.badRequest("limit_exceeded",
                    "Amount (" + amountPesewas + ") exceeds per-transaction limit (" + limit.perTransactionLimit() + ") for KYC Tier " + account.kycTier());
        }

        // Validate max balance limit post-transaction
        SubaccountBalances balances = getSubaccountBalances(accountId, currency);
        long projectedTotal = Math.addExact(balances.total(), amountPesewas);
        if (projectedTotal > limit.maxBalance()) {
            throw ApiException.badRequest("max_balance_exceeded",
                    "Transaction would cause total balance (" + projectedTotal + ") to exceed max balance limit (" + limit.maxBalance() + ")");
        }
    }

    // =========================================================================
    // Subaccount Balances & Double-Entry Ledger Wiring
    // =========================================================================

    public SubaccountBalances getSubaccountBalances(UUID accountId, String currency) {
        String curr = (currency != null && !currency.isBlank()) ? currency.toUpperCase(Locale.ROOT) : GHS;

        long operating = ledger.balance(subaccountCode(accountId, SubaccountBucket.OPERATING, curr));
        long holding = ledger.balance(subaccountCode(accountId, SubaccountBucket.HOLDING, curr));
        long reserved = ledger.balance(subaccountCode(accountId, SubaccountBucket.RESERVED, curr));
        long rewards = ledger.balance(subaccountCode(accountId, SubaccountBucket.REWARDS, curr));

        return SubaccountBalances.of(accountId, operating, holding, reserved, rewards, curr);
    }

    @Transactional
    public Ledger.Posted fundOperatingBalance(UUID accountId, long amountPesewas, String currency,
                                              String idempotencyKey, String description) {
        if (amountPesewas <= 0) {
            throw ApiException.badRequest("invalid_amount", "Amount must be strictly positive");
        }
        EnterpriseManagedAccount account = getAccountById(accountId);
        String curr = (currency != null && !currency.isBlank()) ? currency.toUpperCase(Locale.ROOT) : account.currency();
        validateTransactionLimit(accountId, amountPesewas, curr);

        String operatingAcc = subaccountCode(accountId, SubaccountBucket.OPERATING, curr);
        String enterpriseClearingAcc = enterpriseClearingCode(account.enterpriseId(), curr);

        Ledger.Posting posting = Ledger.Posting.of(
                "enterprise:fund:" + accountId,
                idempotencyKey,
                "enterprise:funding",
                List.of(
                        Ledger.Leg.debit(enterpriseClearingAcc, amountPesewas),
                        Ledger.Leg.credit(operatingAcc, amountPesewas)
                )
        ).describedAs(description != null ? description : "Enterprise funding to operating subaccount");

        return ledger.post(posting);
    }

    @Transactional
    public Ledger.Posted reserveFunds(UUID accountId, long amountPesewas, String currency,
                                      String idempotencyKey, String description) {
        if (amountPesewas <= 0) {
            throw ApiException.badRequest("invalid_amount", "Amount must be strictly positive");
        }
        EnterpriseManagedAccount account = getAccountById(accountId);
        String curr = (currency != null && !currency.isBlank()) ? currency.toUpperCase(Locale.ROOT) : account.currency();

        String operatingAcc = subaccountCode(accountId, SubaccountBucket.OPERATING, curr);
        String reservedAcc = subaccountCode(accountId, SubaccountBucket.RESERVED, curr);

        Ledger.Posting posting = Ledger.Posting.of(
                "enterprise:reserve:" + accountId,
                idempotencyKey,
                "enterprise:reserve",
                List.of(
                        Ledger.Leg.debit(operatingAcc, amountPesewas),
                        Ledger.Leg.credit(reservedAcc, amountPesewas)
                )
        ).describedAs(description != null ? description : "Reserve operating funds");

        return ledger.post(posting);
    }

    @Transactional
    public Ledger.Posted releaseReservedFunds(UUID accountId, long amountPesewas, String currency,
                                             String idempotencyKey, String description) {
        if (amountPesewas <= 0) {
            throw ApiException.badRequest("invalid_amount", "Amount must be strictly positive");
        }
        EnterpriseManagedAccount account = getAccountById(accountId);
        String curr = (currency != null && !currency.isBlank()) ? currency.toUpperCase(Locale.ROOT) : account.currency();

        String reservedAcc = subaccountCode(accountId, SubaccountBucket.RESERVED, curr);
        String operatingAcc = subaccountCode(accountId, SubaccountBucket.OPERATING, curr);

        Ledger.Posting posting = Ledger.Posting.of(
                "enterprise:release-reserve:" + accountId,
                idempotencyKey,
                "enterprise:release_reserve",
                List.of(
                        Ledger.Leg.debit(reservedAcc, amountPesewas),
                        Ledger.Leg.credit(operatingAcc, amountPesewas)
                )
        ).describedAs(description != null ? description : "Release reserved funds back to operating");

        return ledger.post(posting);
    }

    @Transactional
    public Ledger.Posted creditHolding(UUID accountId, long amountPesewas, String currency,
                                       String idempotencyKey, String description) {
        if (amountPesewas <= 0) {
            throw ApiException.badRequest("invalid_amount", "Amount must be strictly positive");
        }
        EnterpriseManagedAccount account = getAccountById(accountId);
        String curr = (currency != null && !currency.isBlank()) ? currency.toUpperCase(Locale.ROOT) : account.currency();

        String enterpriseClearingAcc = enterpriseClearingCode(account.enterpriseId(), curr);
        String holdingAcc = subaccountCode(accountId, SubaccountBucket.HOLDING, curr);

        Ledger.Posting posting = Ledger.Posting.of(
                "enterprise:holding:" + accountId,
                idempotencyKey,
                "enterprise:credit_holding",
                List.of(
                        Ledger.Leg.debit(enterpriseClearingAcc, amountPesewas),
                        Ledger.Leg.credit(holdingAcc, amountPesewas)
                )
        ).describedAs(description != null ? description : "Credit holding subaccount for pending fulfillment");

        return ledger.post(posting);
    }

    @Transactional
    public Ledger.Posted releaseHoldingToOperating(UUID accountId, long amountPesewas, String currency,
                                                  String idempotencyKey, String description) {
        if (amountPesewas <= 0) {
            throw ApiException.badRequest("invalid_amount", "Amount must be strictly positive");
        }
        EnterpriseManagedAccount account = getAccountById(accountId);
        String curr = (currency != null && !currency.isBlank()) ? currency.toUpperCase(Locale.ROOT) : account.currency();

        String holdingAcc = subaccountCode(accountId, SubaccountBucket.HOLDING, curr);
        String operatingAcc = subaccountCode(accountId, SubaccountBucket.OPERATING, curr);

        Ledger.Posting posting = Ledger.Posting.of(
                "enterprise:release-holding:" + accountId,
                idempotencyKey,
                "enterprise:release_holding",
                List.of(
                        Ledger.Leg.debit(holdingAcc, amountPesewas),
                        Ledger.Leg.credit(operatingAcc, amountPesewas)
                )
        ).describedAs(description != null ? description : "Release holding funds to operating");

        return ledger.post(posting);
    }

    @Transactional
    public Ledger.Posted creditRewards(UUID accountId, long amountPesewas, String currency,
                                       String idempotencyKey, String description) {
        if (amountPesewas <= 0) {
            throw ApiException.badRequest("invalid_amount", "Amount must be strictly positive");
        }
        EnterpriseManagedAccount account = getAccountById(accountId);
        String curr = (currency != null && !currency.isBlank()) ? currency.toUpperCase(Locale.ROOT) : account.currency();

        String enterpriseClearingAcc = enterpriseClearingCode(account.enterpriseId(), curr);
        String rewardsAcc = subaccountCode(accountId, SubaccountBucket.REWARDS, curr);

        Ledger.Posting posting = Ledger.Posting.of(
                "enterprise:rewards:" + accountId,
                idempotencyKey,
                "enterprise:credit_rewards",
                List.of(
                        Ledger.Leg.debit(enterpriseClearingAcc, amountPesewas),
                        Ledger.Leg.credit(rewardsAcc, amountPesewas)
                )
        ).describedAs(description != null ? description : "Award enterprise rewards");

        return ledger.post(posting);
    }

    @Transactional
    public Ledger.Posted transferBetweenSubaccounts(UUID accountId, SubaccountBucket fromBucket,
                                                   SubaccountBucket toBucket, long amountPesewas,
                                                   String currency, String idempotencyKey,
                                                   String description) {
        if (amountPesewas <= 0) {
            throw ApiException.badRequest("invalid_amount", "Transfer amount must be strictly positive");
        }
        if (fromBucket == toBucket) {
            throw ApiException.badRequest("invalid_transfer", "Source and destination subaccounts must be distinct");
        }
        EnterpriseManagedAccount account = getAccountById(accountId);
        String curr = (currency != null && !currency.isBlank()) ? currency.toUpperCase(Locale.ROOT) : account.currency();

        String fromAcc = subaccountCode(accountId, fromBucket, curr);
        String toAcc = subaccountCode(accountId, toBucket, curr);

        Ledger.Posting posting = Ledger.Posting.of(
                "enterprise:subaccount-transfer:" + accountId,
                idempotencyKey,
                "enterprise:internal_transfer",
                List.of(
                        Ledger.Leg.debit(fromAcc, amountPesewas),
                        Ledger.Leg.credit(toAcc, amountPesewas)
                )
        ).describedAs(description != null ? description : "Subaccount transfer from " + fromBucket.code() + " to " + toBucket.code());

        return ledger.post(posting);
    }

    // =========================================================================
    // Helpers & Mappers
    // =========================================================================

    private Enterprise mapEnterprise(ResultSet rs, int i) throws SQLException {
        return new Enterprise(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("status"),
                rs.getString("contact_email"),
                rs.getString("contact_phone"),
                rs.getString("webhook_url"),
                rs.getString("kyc_status"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private EnterpriseManagedAccount mapAccount(ResultSet rs, int i) throws SQLException {
        return new EnterpriseManagedAccount(
                rs.getObject("id", UUID.class),
                rs.getObject("enterprise_id", UUID.class),
                rs.getString("external_customer_id"),
                rs.getString("account_type"),
                rs.getString("display_name"),
                rs.getString("phone"),
                rs.getString("email"),
                rs.getString("national_id"),
                rs.getInt("kyc_tier"),
                rs.getString("status"),
                rs.getString("currency"),
                deserializeMetadata(rs.getString("metadata")),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private String normalizePhone(String phone) {
        if (phone == null || phone.isBlank()) return null;
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.startsWith("0") && digits.length() == 10) {
            digits = "233" + digits.substring(1);
        }
        return digits;
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) return null;
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeNationalId(String nationalId) {
        if (nationalId == null || nationalId.isBlank()) return null;
        return nationalId.trim().toUpperCase(Locale.ROOT);
    }

    private String serializeMetadata(Map<String, Object> meta) {
        if (meta == null || meta.isEmpty()) return "{}";
        try {
            return json.writeValueAsString(meta);
        } catch (Exception e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deserializeMetadata(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            return json.readValue(raw, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
