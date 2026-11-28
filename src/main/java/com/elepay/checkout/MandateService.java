package com.elepay.checkout;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.elepay.common.ApiException;
import com.elepay.common.Audit;
import com.elepay.common.Idempotency;
import com.elepay.ledger.Ledger;
import com.elepay.ledger.Ledger.Leg;
import com.elepay.merchant.MerchantAccess;
import com.elepay.outbox.Outbox;
import com.elepay.wallet.CustomerDirectory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Algorithms 020 & 021: Recurring Billing Mandates & Dunning Schedules.
 * Handles customer mandate consents, recurring charges against customer wallet,
 * idempotency, and automated dunning with exponential backoff and mandate auto-suspension.
 */
@Service
@RestController
public class MandateService {
    private static final Set<String> ALLOWED_FREQUENCIES = Set.of(
            "daily", "weekly", "biweekly", "monthly", "quarterly", "yearly", "ad_hoc");

    private final JdbcClient db;
    private final Ledger ledger;
    private final MerchantAccess merchants;
    private final CustomerDirectory customers;
    private final Idempotency idempotency;
    private final Outbox outbox;
    private final Audit audit;

    private final TransactionTemplate tx;
    private final TransactionTemplate txRequiresNew;
    private final com.elepay.common.FinancialTxRetry txRetry;
    private com.elepay.wallet.TransferLimits transferLimits;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTransferLimits(com.elepay.wallet.TransferLimits transferLimits) {
        this.transferLimits = transferLimits;
    }

    public MandateService(JdbcClient db, Ledger ledger, MerchantAccess merchants, CustomerDirectory customers,
                          Idempotency idempotency, Outbox outbox, Audit audit, PlatformTransactionManager tm) {
        this.db = db;
        this.ledger = ledger;
        this.merchants = merchants;
        this.customers = customers;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.audit = audit;
        this.tx = new TransactionTemplate(tm);
        this.txRequiresNew = new TransactionTemplate(tm);
        this.txRequiresNew.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.txRetry = new com.elepay.common.FinancialTxRetry(tm);
    }

    public record CreateMandateRequest(
            @NotNull UUID merchantId,
            @JsonAlias({"maxAmount", "amount"}) @Min(1) long maxAmountPesewas,
            @NotBlank String frequency,
            @JsonAlias({"reference", "consent"}) @NotBlank String consentReference,
            String currency
    ) {}

    public record Mandate(
            UUID id,
            UUID customerId,
            UUID merchantId,
            String currency,
            long maxAmountPesewas,
            String frequency,
            String status,
            String consentReference,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        @JsonProperty("maxAmount")
        public long maxAmount() {
            return maxAmountPesewas;
        }
    }

    public record ChargeRequest(
            @JsonAlias({"amount", "amountPesewas"}) @Min(1) long amount,
            String description
    ) {}

    public record MandateCharge(
            UUID id,
            UUID mandateId,
            long amountPesewas,
            String idempotencyKey,
            String status,
            String failureReason,
            int attemptCount,
            OffsetDateTime nextRetryAt,
            OffsetDateTime createdAt
    ) {
        @JsonProperty("amount")
        public long amount() {
            return amountPesewas;
        }
    }

    public record DunningSchedule(
            UUID id,
            UUID chargeId,
            int scheduleStep,
            int maxSteps,
            int backoffHours,
            String status,
            OffsetDateTime lastAttemptAt,
            OffsetDateTime nextAttemptAt
    ) {}

    // ---- Customer Endpoints -------------------------------------------------------------

    @PostMapping("/v1/customers/mandates")
    @Transactional
    public Mandate createMandate(
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody CreateMandateRequest request) {
        if (key == null || key.isBlank()) {
            throw ApiException.badRequest("missing_idempotency_key", "Idempotency-Key header is required");
        }
        UUID customerId = customers.currentCustomer();
        return idempotency.execute("mandate-create:" + customerId, key, request, Mandate.class,
                () -> doCreateMandate(customerId, request, key));
    }

    private Mandate doCreateMandate(UUID customerId, CreateMandateRequest request, String key) {
        var merchant = merchants.find(request.merchantId())
                .orElseThrow(() -> ApiException.badRequest("unknown_merchant", "Merchant not found"));
        if (!"active".equals(merchant.status())) {
            throw ApiException.conflict("merchant_not_active", "Merchant is not active");
        }
        String freq = request.frequency().toLowerCase();
        if (!ALLOWED_FREQUENCIES.contains(freq)) {
            throw ApiException.badRequest("invalid_frequency", "Frequency must be one of: " + ALLOWED_FREQUENCIES);
        }
        String cur = request.currency() != null ? request.currency().toUpperCase() : Ledger.GHS;
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();

        db.sql("""
                INSERT INTO mandate (id, customer_id, merchant_id, currency, max_amount_pesewas, frequency, status,
                                     consent_reference, idempotency_key, created_at, updated_at)
                VALUES (:id, :cust, :m, :cur, :max, :freq, 'active', :consent, :key, :now, :now)""")
                .param("id", id).param("cust", customerId).param("m", request.merchantId())
                .param("cur", cur).param("max", request.maxAmountPesewas).param("freq", freq)
                .param("consent", request.consentReference()).param("key", key).param("now", now).update();

        audit.record("customer:" + customerId, "mandate.created", "mandate:" + id,
                Map.of("merchant", request.merchantId(), "maxAmount", request.maxAmountPesewas));
        outbox.publish("mandate", id, "mandate.created",
                Map.of("mandate", id.toString(), "customerId", customerId.toString(), "merchantId", request.merchantId().toString()),
                Set.of(request.merchantId()));

        return new Mandate(id, customerId, request.merchantId(), cur, request.maxAmountPesewas, freq, "active",
                request.consentReference(), now, now);
    }

    @GetMapping("/v1/customers/mandates")
    public List<Mandate> listCustomerMandates() {
        UUID customerId = customers.currentCustomer();
        return db.sql("""
                SELECT id, customer_id, merchant_id, currency, max_amount_pesewas, frequency, status,
                       consent_reference, created_at, updated_at
                FROM mandate WHERE customer_id = :cust ORDER BY created_at DESC""")
                .param("cust", customerId)
                .query(MandateService::mapMandate).list();
    }

    @PostMapping("/v1/customers/mandates/{id}/cancel")
    @Transactional
    public Mandate cancelMandate(@PathVariable UUID id) {
        UUID customerId = customers.currentCustomer();
        var mandateOpt = db.sql("""
                SELECT id, customer_id, merchant_id, currency, max_amount_pesewas, frequency, status,
                       consent_reference, created_at, updated_at
                FROM mandate WHERE id = :id AND customer_id = :cust FOR UPDATE""")
                .param("id", id).param("cust", customerId)
                .query(MandateService::mapMandate).optional();

        if (mandateOpt.isEmpty()) {
            throw ApiException.notFound("Mandate not found");
        }
        Mandate mandate = mandateOpt.get();
        if ("cancelled".equals(mandate.status())) {
            throw ApiException.conflict("already_cancelled", "Mandate is already cancelled");
        }

        OffsetDateTime now = OffsetDateTime.now();
        db.sql("UPDATE mandate SET status = 'cancelled', updated_at = :now WHERE id = :id")
                .param("now", now).param("id", id).update();

        audit.record("customer:" + customerId, "mandate.cancelled", "mandate:" + id, Map.of());
        outbox.publish("mandate", id, "mandate.cancelled",
                Map.of("mandate", id.toString(), "merchantId", mandate.merchantId().toString()),
                Set.of(mandate.merchantId()));

        return new Mandate(mandate.id(), mandate.customerId(), mandate.merchantId(), mandate.currency(),
                mandate.maxAmountPesewas(), mandate.frequency(), "cancelled", mandate.consentReference(),
                mandate.createdAt(), now);
    }

    // ---- Merchant Endpoints -------------------------------------------------------------

    @GetMapping("/v1/merchants/{merchantId}/mandates")
    public List<Mandate> listMerchantMandates(@PathVariable UUID merchantId) {
        merchants.require(merchantId, MerchantAccess.Permission.VIEW);
        return db.sql("""
                SELECT id, customer_id, merchant_id, currency, max_amount_pesewas, frequency, status,
                       consent_reference, created_at, updated_at
                FROM mandate WHERE merchant_id = :m ORDER BY created_at DESC""")
                .param("m", merchantId)
                .query(MandateService::mapMandate).list();
    }

    @PostMapping("/v1/merchants/{merchantId}/mandates/{mandateId}/charge")
    public MandateCharge chargeMandate(
            @PathVariable UUID merchantId,
            @PathVariable UUID mandateId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody ChargeRequest request) {
        if (key == null || key.isBlank()) {
            throw ApiException.badRequest("missing_idempotency_key", "Idempotency-Key header is required");
        }
        merchants.require(merchantId, MerchantAccess.Permission.COLLECT);

        return txRetry.execute(() -> idempotency.execute("mandate-charge:" + merchantId + ":" + mandateId, key, request, MandateCharge.class,
                () -> doChargeMandate(merchantId, mandateId, key, request.amount(), request.description())));
    }

    private record LockedMandate(Mandate mandate, OffsetDateTime lastChargedAt, OffsetDateTime nextBillingAt) {}

    private static OffsetDateTime computeNextBillingAt(String frequency, OffsetDateTime chargedAt) {
        if (frequency == null) return null;
        return switch (frequency.toLowerCase(Locale.ROOT)) {
            case "daily" -> chargedAt.plusDays(1);
            case "weekly" -> chargedAt.plusWeeks(1);
            case "biweekly" -> chargedAt.plusWeeks(2);
            case "monthly" -> chargedAt.plusMonths(1);
            case "quarterly" -> chargedAt.plusMonths(3);
            case "yearly" -> chargedAt.plusYears(1);
            case "ad_hoc" -> null;
            default -> null;
        };
    }

    private MandateCharge doChargeMandate(UUID merchantId, UUID mandateId, String key, long amount, String description) {
        var lockedOpt = db.sql("""
                SELECT id, customer_id, merchant_id, currency, max_amount_pesewas, frequency, status,
                       consent_reference, created_at, updated_at, last_charged_at, next_billing_at
                FROM mandate WHERE id = :id AND merchant_id = :m FOR NO KEY UPDATE""")
                .param("id", mandateId).param("m", merchantId)
                .query((rs, i) -> new LockedMandate(
                        mapMandate(rs, i),
                        rs.getObject(11, OffsetDateTime.class),
                        rs.getObject(12, OffsetDateTime.class)))
                .optional();

        if (lockedOpt.isEmpty()) {
            throw ApiException.notFound("Mandate not found");
        }
        LockedMandate locked = lockedOpt.get();
        Mandate mandate = locked.mandate();

        if (!"active".equals(mandate.status())) {
            throw ApiException.conflict("mandate_not_active", "Mandate status is " + mandate.status());
        }
        if (amount > mandate.maxAmountPesewas()) {
            throw ApiException.badRequest("amount_exceeds_mandate_max",
                    "Charge amount " + amount + " exceeds mandate ceiling " + mandate.maxAmountPesewas());
        }
        if (!customers.isActive(mandate.customerId())) {
            throw ApiException.forbidden("Customer account is not active and cannot be debited");
        }

        // Check if charge with this idempotency key already exists for THIS mandate (scoped)
        var existingCharge = db.sql("""
                SELECT id, mandate_id, amount_pesewas, idempotency_key, status, failure_reason,
                       attempt_count, next_retry_at, created_at
                FROM mandate_charge WHERE mandate_id = :m AND idempotency_key = :k""")
                .param("m", mandateId)
                .param("k", key)
                .query(MandateService::mapCharge).optional();
        if (existingCharge.isPresent()) {
            return existingCharge.get();
        }

        OffsetDateTime now = OffsetDateTime.now();

        // Enforce mandate billing frequency (non-ad_hoc mandates cannot be charged again before next_billing_at)
        if (!"ad_hoc".equalsIgnoreCase(mandate.frequency())) {
            OffsetDateTime effectiveNext = locked.nextBillingAt() != null
                    ? locked.nextBillingAt()
                    : (locked.lastChargedAt() != null ? computeNextBillingAt(mandate.frequency(), locked.lastChargedAt()) : null);
            if (effectiveNext != null && now.isBefore(effectiveNext)) {
                throw ApiException.conflict("mandate_frequency_exceeded",
                        "Mandate (" + mandate.frequency() + ") cannot be charged again until " + effectiveNext);
            }
        }

        // Enforce BoG KYC tier daily/monthly limits before debiting customer wallet
        if (transferLimits != null) {
            transferLimits.check(mandate.customerId(), amount);
        }

        UUID chargeId = UUID.randomUUID();
        String customerAccount = Ledger.code("customer", mandate.customerId(), "available", mandate.currency());
        String merchantAccount = Ledger.code("merchant", merchantId, "pending", mandate.currency());

        Ledger.Posted posted = null;
        try {
            posted = ledger.post(Ledger.Posting.of("mandate-charge", chargeId.toString(), "mandate_charge", List.of(
                    Leg.debit(customerAccount, amount),
                    Leg.credit(merchantAccount, amount)
            )).describedAs(description != null ? description : "Recurring mandate charge " + mandateId));
        } catch (ApiException e) {
            // Customer wallet balance insufficient
            posted = null;
        }

        if (posted != null) {
            db.sql("""
                    INSERT INTO mandate_charge (id, mandate_id, amount_pesewas, idempotency_key, status,
                                                failure_reason, attempt_count, next_retry_at, created_at)
                    VALUES (:id, :m, :amt, :k, 'succeeded', NULL, 1, NULL, :now)""")
                    .param("id", chargeId).param("m", mandateId).param("amt", amount).param("k", key)
                    .param("now", now).update();

            OffsetDateTime nextBilling = computeNextBillingAt(mandate.frequency(), now);
            db.sql("""
                    UPDATE mandate
                    SET last_charged_at = :now, next_billing_at = :nextBilling, updated_at = :now
                    WHERE id = :id""")
                    .param("now", now).param("nextBilling", nextBilling).param("id", mandateId).update();

            String opRef = "MND-" + chargeId.toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
            customers.recordOperation(opRef, mandate.customerId(), "payment", amount, "completed",
                    description != null ? description : "Recurring mandate charge " + mandateId,
                    posted.transactionId(), chargeId);

            audit.record("merchant:" + merchantId, "mandate.charge_succeeded", "charge:" + chargeId,
                    Map.of("mandate", mandateId, "amount", amount));
            outbox.publish("mandate", chargeId, "mandate.charge_succeeded",
                    Map.of("chargeId", chargeId.toString(), "mandateId", mandateId.toString(), "amount", amount),
                    Set.of(merchantId));

            return new MandateCharge(chargeId, mandateId, amount, key, "succeeded", null, 1, null, now);
        } else {
            // Algorithm 021: Dunning logic with exponential backoff (1d, 3d, 7d)
            int attemptCount = 1;
            int maxSteps = 3;
            int backoffHours = 24; // step 1 = 24h
            OffsetDateTime nextRetry = now.plusHours(backoffHours);

            txRequiresNew.executeWithoutResult(status -> {
                db.sql("""
                        INSERT INTO mandate_charge (id, mandate_id, amount_pesewas, idempotency_key, status,
                                                    failure_reason, attempt_count, next_retry_at, created_at)
                        VALUES (:id, :m, :amt, :k, 'failed', 'insufficient_funds', :att, :retry, :now)""")
                        .param("id", chargeId).param("m", mandateId).param("amt", amount).param("k", key)
                        .param("att", attemptCount).param("retry", nextRetry).param("now", now).update();

                UUID scheduleId = UUID.randomUUID();
                db.sql("""
                        INSERT INTO dunning_schedule (id, charge_id, schedule_step, max_steps, backoff_hours,
                                                      status, last_attempt_at, next_attempt_at)
                        VALUES (:id, :cid, 1, :maxSteps, :backoff, 'active', :now, :next)""")
                        .param("id", scheduleId).param("cid", chargeId).param("maxSteps", maxSteps)
                        .param("backoff", backoffHours).param("now", now).param("next", nextRetry).update();

                audit.record("merchant:" + merchantId, "mandate.charge_failed", "charge:" + chargeId,
                        Map.of("mandate", mandateId, "reason", "insufficient_funds"));
                outbox.publish("dunning", chargeId, "mandate.charge_failed",
                        Map.of("chargeId", chargeId.toString(), "mandateId", mandateId.toString(), "step", 1,
                                "nextRetryAt", nextRetry.toString()),
                        Set.of(merchantId));
            });

            throw ApiException.conflict("insufficient_funds", "Customer wallet has insufficient funds for recurring charge");
        }
    }

    /**
     * Executes dunning retry progression: step 1 (24h) -> step 2 (72h) -> step 3 (168h) -> suspension.
     */
    @Transactional
    public void progressDunning(UUID scheduleId) {
        var scheduleOpt = db.sql("""
                SELECT id, charge_id, schedule_step, max_steps, backoff_hours, status, last_attempt_at, next_attempt_at
                FROM dunning_schedule WHERE id = :id FOR UPDATE""")
                .param("id", scheduleId)
                .query(MandateService::mapSchedule).optional();
        if (scheduleOpt.isEmpty() || !"active".equals(scheduleOpt.get().status())) return;

        DunningSchedule schedule = scheduleOpt.get();
        var charge = db.sql("""
                SELECT id, mandate_id, amount_pesewas, idempotency_key, status, failure_reason,
                       attempt_count, next_retry_at, created_at
                FROM mandate_charge WHERE id = :id FOR UPDATE""")
                .param("id", schedule.chargeId()).query(MandateService::mapCharge).single();
        var mandate = db.sql("""
                SELECT id, customer_id, merchant_id, currency, max_amount_pesewas, frequency, status,
                       consent_reference, created_at, updated_at
                FROM mandate WHERE id = :id FOR UPDATE""")
                .param("id", charge.mandateId()).query(MandateService::mapMandate).single();

        OffsetDateTime now = OffsetDateTime.now();

        if (!"active".equals(mandate.status())) {
            db.sql("UPDATE dunning_schedule SET status = 'abandoned', last_attempt_at = :now, next_attempt_at = NULL WHERE id = :id")
                    .param("now", now).param("id", schedule.id()).update();
            return;
        }

        if (!customers.isActive(mandate.customerId())) {
            db.sql("UPDATE dunning_schedule SET status = 'abandoned', last_attempt_at = :now, next_attempt_at = NULL WHERE id = :id")
                    .param("now", now).param("id", schedule.id()).update();
            db.sql("UPDATE mandate SET status = 'suspended', updated_at = :now WHERE id = :id")
                    .param("now", now).param("id", mandate.id()).update();
            audit.record("system", "dunning.abandoned", "mandate:" + mandate.id(),
                    Map.of("scheduleId", schedule.id(), "reason", "customer_not_active"));
            outbox.publish("mandate", mandate.id(), "mandate.suspended",
                    Map.of("mandateId", mandate.id().toString(), "reason", "customer_not_active"),
                    Set.of(mandate.merchantId()));
            return;
        }

        int nextStep = schedule.scheduleStep() + 1;
        if (nextStep > schedule.maxSteps()) {
            // Auto-suspend exhausted mandate
            db.sql("UPDATE dunning_schedule SET status = 'exhausted', last_attempt_at = :now WHERE id = :id")
                    .param("now", now).param("id", schedule.id()).update();
            db.sql("UPDATE mandate SET status = 'suspended', updated_at = :now WHERE id = :id")
                    .param("now", now).param("id", mandate.id()).update();
            outbox.publish("mandate", mandate.id(), "mandate.suspended",
                    Map.of("mandateId", mandate.id().toString(), "reason", "dunning_exhausted"),
                    Set.of(mandate.merchantId()));
        } else {
            // Backoff hours: step 2 = 72h (3d), step 3 = 168h (7d)
            int nextBackoff = nextStep == 2 ? 72 : 168;
            OffsetDateTime nextRetry = now.plusHours(nextBackoff);
            db.sql("""
                    UPDATE dunning_schedule SET schedule_step = :s, backoff_hours = :b,
                           last_attempt_at = :now, next_attempt_at = :next WHERE id = :id""")
                    .param("s", nextStep).param("b", nextBackoff).param("now", now).param("next", nextRetry)
                    .param("id", schedule.id()).update();
            db.sql("UPDATE mandate_charge SET attempt_count = :att, next_retry_at = :retry WHERE id = :id")
                    .param("att", nextStep).param("retry", nextRetry).param("id", charge.id()).update();
            outbox.publish("dunning", charge.id(), "mandate.charge_failed",
                    Map.of("chargeId", charge.id().toString(), "step", nextStep, "nextRetryAt", nextRetry.toString()),
                    Set.of(mandate.merchantId()));
        }
    }

    private static Mandate mapMandate(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Mandate(
                rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class),
                rs.getString(4),
                rs.getLong(5),
                rs.getString(6),
                rs.getString(7),
                rs.getString(8),
                rs.getObject(9, OffsetDateTime.class),
                rs.getObject(10, OffsetDateTime.class)
        );
    }

    private static MandateCharge mapCharge(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new MandateCharge(
                rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class),
                rs.getLong(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6),
                rs.getInt(7),
                rs.getObject(8, OffsetDateTime.class),
                rs.getObject(9, OffsetDateTime.class)
        );
    }

    private static DunningSchedule mapSchedule(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new DunningSchedule(
                rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class),
                rs.getInt(3),
                rs.getInt(4),
                rs.getInt(5),
                rs.getString(6),
                rs.getObject(7, OffsetDateTime.class),
                rs.getObject(8, OffsetDateTime.class)
        );
    }
}
