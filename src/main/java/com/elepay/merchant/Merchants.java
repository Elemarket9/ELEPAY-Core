package com.elepay.merchant;

import static com.elepay.merchant.MerchantAccess.Permission.*;

import com.elepay.common.ApiException;
import com.elepay.common.Audit;
import com.elepay.common.References;
import com.elepay.identity.Actor;
import com.elepay.identity.ApiKeys;
import com.elepay.ledger.Ledger;
import com.elepay.outbox.Webhooks;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Merchant onboarding, review, branches, roles, settlement destinations, marketplaces and developer access. */
@RestController
@RequestMapping("/v1")
class Merchants {
    private static final List<String> BUCKETS = List.of("pending", "available", "reserved");
    private static final Set<String> MOMO = Set.of("mtn", "telecel", "airteltigo");

    private final JdbcClient db;
    private final MerchantAccess access;
    private final Ledger ledger;
    private final Audit audit;
    private final ApiKeys apiKeys;
    private final Webhooks webhooks;
    private final com.elepay.rails.NameVerifications verifications;
    private final tools.jackson.databind.ObjectMapper json;
    private final com.elepay.outbox.Outbox outbox;

    @org.springframework.beans.factory.annotation.Value("${elepay.security.destination-cooling-off-hours:24}")
    private volatile int destinationCoolingOffHours = 24;

    Merchants(JdbcClient db, MerchantAccess access, Ledger ledger, Audit audit, ApiKeys apiKeys, Webhooks webhooks,
              com.elepay.rails.NameVerifications verifications, tools.jackson.databind.ObjectMapper json,
              com.elepay.outbox.Outbox outbox) {
        this.outbox = outbox;
        this.db = db;
        this.access = access;
        this.ledger = ledger;
        this.audit = audit;
        this.apiKeys = apiKeys;
        this.webhooks = webhooks;
        this.verifications = verifications;
        this.json = json;
    }

    public void setDestinationCoolingOffHours(int hours) {
        this.destinationCoolingOffHours = hours;
    }

    public int getDestinationCoolingOffHours() {
        return this.destinationCoolingOffHours;
    }

    record Application(@NotBlank @Size(max = 200) String legalName, @NotBlank @Size(max = 100) String tradingName,
                       @NotBlank @Pattern(regexp = "merchant|delivery|marketplace") String kind,
                       @NotBlank @Size(max = 40) String contactPhone, @Email @Size(max = 200) String contactEmail,
                       @Size(max = 50) String registrationNumber) {}

    record MerchantView(UUID id, String legalName, String tradingName, String kind, String status, String merchantCode,
                        int feeBps, long feeFixed, int settlementDelayHours, String role) {}

    record Review(@NotBlank @Pattern(regexp = "approve|reject|suspend") String decision,
                  @Min(0) @Max(10000) Integer feeBps, @Min(0) Long feeFixed,
                  @Min(0) @Max(720) Integer settlementDelayHours, @Size(max = 1000) String note) {}

    record Branch(UUID id, String name, boolean active) {}

    record BranchRequest(@NotBlank @Size(max = 100) String name) {}

    record Member(@NotBlank @Pattern(regexp = "owner|admin|finance|cashier") String role, UUID branchId) {}

    record MemberView(String subject, String role, UUID branchId) {}

    record DestinationRequest(@NotBlank @Pattern(regexp = "momo|bank") String type, @NotBlank @Size(max = 20) String network,
                              @NotBlank @Size(max = 34) String accountNumber, @NotBlank @Size(max = 150) String accountName,
                              Integer coolingOffHours, Boolean requireOob) {
        DestinationRequest(String type, String network, String accountNumber, String accountName) {
            this(type, network, accountNumber, accountName, null, null);
        }
    }

    record OobConfirmRequest(String token) {}

    record Destination(UUID id, String type, String network, String accountNumber, String accountName,
                       String status, String verification, String verifiedName,
                       java.time.OffsetDateTime coolingOffUntil, String oobVerificationStatus) {
        Destination(UUID id, String type, String network, String accountNumber, String accountName,
                    String status, String verification, String verifiedName) {
            this(id, type, network, accountNumber, accountName, status, verification, verifiedName, null, "confirmed");
        }
    }

    record SellerInvite(@NotBlank @Pattern(regexp = "[0-9]{6}") String merchantCode) {}

    record Seller(UUID merchantId, String tradingName, String kind, String status) {}

    record Balances(UUID merchantId, String currency, long pending, long available, long reserved) {}

    record KeyRequest(@Size(max = 100) String label,
                      List<@Pattern(regexp = "view|collect|refund|withdraw|payouts") String> scopes,
                      String keyType,
                      List<String> allowedIps) {
        KeyRequest(String label, List<String> scopes) {
            this(label, scopes, null, null);
        }
    }

    record Delegation(@NotEmpty List<@Pattern(regexp = "view|collect|refund|withdraw|payouts") String> permissions) {}

    record DelegationView(UUID merchantId, UUID delegateId, String delegateName, List<String> permissions) {}

    record Event(UUID eventId, long sequence, String type, String aggregateId, java.time.OffsetDateTime createdAt,
                 tools.jackson.databind.JsonNode payload) {}

    record WebhookRequest(@NotBlank @Pattern(regexp = "https?://.+") @Size(max = 500) String url) {}

    // ---- onboarding -----------------------------------------------------------------------

    @PostMapping("/merchants")
    @Transactional
    public MerchantView apply(@Valid @RequestBody Application request) {
        Actor actor = Actor.current();
        actor.requireUser();
        UUID id = UUID.randomUUID();
        String phone = References.msisdn(request.contactPhone());
        for (int attempt = 0; ; attempt++) {
            int inserted = db.sql("""
                        INSERT INTO merchant (id, legal_name, trading_name, kind, contact_phone, contact_email,
                            registration_number, merchant_code, created_by)
                        VALUES (:id, :legal, :trading, :kind, :phone, :email, :reg, :code, :by)
                        ON CONFLICT (merchant_code) DO NOTHING""")
                        .param("id", id).param("legal", request.legalName()).param("trading", request.tradingName())
                        .param("kind", request.kind()).param("phone", phone).param("email", request.contactEmail())
                        .param("reg", request.registrationNumber()).param("code", References.digits(6))
                        .param("by", actor.subject()).update();
            if (inserted == 1) break;
            if (attempt > 5) throw ApiException.conflict("retry", "Could not allocate a merchant code; retry");
        }
        db.sql("INSERT INTO merchant_member (merchant_id, subject, role) VALUES (:m, :s, 'owner')")
                .param("m", id).param("s", actor.subject()).update();
        for (String bucket : BUCKETS) ledger.open("merchant", id, bucket, Ledger.GHS);
        audit.record(actor.subject(), "merchant.applied", "merchant:" + id, Map.of("kind", request.kind()));
        return view(id, "owner");
    }

    @GetMapping("/merchants")
    public List<MerchantView> mine() {
        Actor actor = Actor.current();
        if (actor.isApiKey()) return List.of(view(actor.apiKeyMerchant(), "api_key"));
        return db.sql("SELECT merchant_id, role FROM merchant_member WHERE subject = :s ORDER BY created_at")
                .param("s", actor.subject())
                .query((rs, i) -> view(rs.getObject(1, UUID.class), rs.getString(2))).list();
    }

    @GetMapping("/merchants/{id}")
    public MerchantView get(@PathVariable UUID id) {
        var a = access.require(id, VIEW);
        return view(id, a.role());
    }

    private MerchantView view(UUID id, String role) {
        return db.sql("""
                SELECT id, legal_name, trading_name, kind, status, merchant_code, fee_bps, fee_fixed, settlement_delay_hours
                FROM merchant WHERE id = :id""").param("id", id)
                .query((rs, i) -> new MerchantView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getInt(7), rs.getLong(8), rs.getInt(9), role))
                .single();
    }

    @GetMapping("/ops/merchants")
    public List<MerchantView> queue(@RequestParam(required = false) String status) {
        Actor.current().requireOps();
        if (status == null || status.isBlank() || "all".equalsIgnoreCase(status)) {
            return db.sql("SELECT id FROM merchant ORDER BY created_at")
                    .query(UUID.class).list().stream().map(id -> view(id, "ops")).toList();
        }
        return db.sql("SELECT id FROM merchant WHERE status = :s ORDER BY created_at").param("s", status)
                .query(UUID.class).list().stream().map(id -> view(id, "ops")).toList();
    }

    @PostMapping("/ops/merchants/{id}/review")
    @Transactional
    public MerchantView review(@PathVariable UUID id, @Valid @RequestBody Review request) {
        Actor actor = Actor.current();
        actor.requireOps();
        String status = switch (request.decision()) {
            case "approve" -> "active";
            case "reject" -> "rejected";
            default -> "suspended";
        };
        int updated = db.sql("""
                UPDATE merchant SET status = :status, reviewed_by = :by, reviewed_at = now(), review_note = :note,
                       fee_bps = coalesce(:bps, fee_bps), fee_fixed = coalesce(:fixed, fee_fixed),
                       settlement_delay_hours = coalesce(:delay, settlement_delay_hours)
                WHERE id = :id""")
                .param("status", status).param("by", actor.subject()).param("note", request.note())
                .param("bps", request.feeBps()).param("fixed", request.feeFixed())
                .param("delay", request.settlementDelayHours()).param("id", id).update();
        if (updated == 0) throw ApiException.notFound("Merchant not found");
        audit.record(actor.subject(), "merchant.reviewed", "merchant:" + id, Map.of("decision", request.decision(),
                "feeBps", String.valueOf(request.feeBps()), "feeFixed", String.valueOf(request.feeFixed())));
        return view(id, "ops");
    }

    // ---- branches and members ---------------------------------------------------------------

    @PostMapping("/merchants/{id}/branches")
    @Transactional
    public Branch addBranch(@PathVariable UUID id, @Valid @RequestBody BranchRequest request) {
        var a = access.require(id, MANAGE);
        UUID branch = UUID.randomUUID();
        db.sql("INSERT INTO merchant_branch (id, merchant_id, name) VALUES (:id, :m, :n)")
                .param("id", branch).param("m", id).param("n", request.name()).update();
        audit.record(a.actor().subject(), "merchant.branch_added", "merchant:" + id, Map.of("branch", branch.toString()));
        return new Branch(branch, request.name(), true);
    }

    @GetMapping("/merchants/{id}/branches")
    public List<Branch> branches(@PathVariable UUID id) {
        access.require(id, VIEW);
        return db.sql("SELECT id, name, active FROM merchant_branch WHERE merchant_id = :m ORDER BY name").param("m", id)
                .query((rs, i) -> new Branch(rs.getObject(1, UUID.class), rs.getString(2), rs.getBoolean(3))).list();
    }

    @PutMapping("/merchants/{id}/members/{subject}")
    @Transactional
    public MemberView putMember(@PathVariable UUID id, @PathVariable String subject, @Valid @RequestBody Member request) {
        var a = access.require(id, MANAGE);
        if (request.role().equals("owner") && !a.role().equals("owner")) throw ApiException.forbidden("Only an owner can add owners");
        if (request.role().equals("cashier") == (request.branchId() == null)) {
            throw ApiException.badRequest("branch_required", "Cashiers need a branch; other roles cannot be branch-scoped");
        }
        if (request.branchId() != null && !access.branchBelongs(id, request.branchId())) {
            throw ApiException.badRequest("unknown_branch", "Branch not found");
        }
        if (subject.equals(a.actor().subject())) throw ApiException.badRequest("self_change", "You cannot change your own role");
        db.sql("""
                INSERT INTO merchant_member (merchant_id, subject, role, branch_id) VALUES (:m, :s, :r, :b)
                ON CONFLICT (merchant_id, subject) DO UPDATE SET role = EXCLUDED.role, branch_id = EXCLUDED.branch_id""")
                .param("m", id).param("s", subject).param("r", request.role()).param("b", request.branchId()).update();
        audit.record(a.actor().subject(), "merchant.member_set", "merchant:" + id,
                Map.of("member", subject, "role", request.role(), "branch", String.valueOf(request.branchId())));
        return new MemberView(subject, request.role(), request.branchId());
    }

    @DeleteMapping("/merchants/{id}/members/{subject}")
    @Transactional
    public Map<String, String> removeMember(@PathVariable UUID id, @PathVariable String subject) {
        var a = access.require(id, MANAGE);
        if (subject.equals(a.actor().subject())) throw ApiException.badRequest("self_change", "You cannot remove yourself");
        db.sql("DELETE FROM merchant_member WHERE merchant_id = :m AND subject = :s AND role <> 'owner'")
                .param("m", id).param("s", subject).update();
        audit.record(a.actor().subject(), "merchant.member_removed", "merchant:" + id, Map.of("member", subject));
        return Map.of("status", "removed");
    }

    @GetMapping("/merchants/{id}/members")
    public List<MemberView> members(@PathVariable UUID id) {
        access.require(id, MANAGE);
        return db.sql("SELECT subject, role, branch_id FROM merchant_member WHERE merchant_id = :m ORDER BY created_at")
                .param("m", id).query((rs, i) -> new MemberView(rs.getString(1), rs.getString(2),
                        rs.getObject(3, UUID.class))).list();
    }

    // ---- settlement destinations ----------------------------------------------------------

    @PostMapping("/merchants/{id}/settlement-destinations")
    @Transactional
    public Destination addDestination(@PathVariable UUID id, @Valid @RequestBody DestinationRequest request) {
        var a = access.require(id, MANAGE);
        a.actor().requireUser();
        String account = request.accountNumber();
        if (request.type().equals("momo")) {
            if (!MOMO.contains(request.network())) throw ApiException.badRequest("invalid_network", "Choose mtn, telecel or airteltigo");
            account = References.msisdn(account);
        } else if (!account.matches("[0-9]{6,34}")) {
            throw ApiException.badRequest("invalid_account", "Enter the full bank account number");
        }
        var check = verifications.verify(request.type(), request.network(), account);
        if (check.outcome() == com.elepay.rails.NameVerification.Outcome.NOT_FOUND) {
            throw ApiException.unprocessable("account_not_found", "The receiving network did not find this account");
        }
        boolean verified = check.outcome() == com.elepay.rails.NameVerification.Outcome.VERIFIED;
        UUID dest = UUID.randomUUID();
        int effectiveCoolingHours = request.coolingOffHours() != null ? request.coolingOffHours() : destinationCoolingOffHours;
        java.time.OffsetDateTime coolingOffUntil = effectiveCoolingHours > 0
                ? java.time.OffsetDateTime.now().plusHours(effectiveCoolingHours)
                : null;
        boolean requireOob = Boolean.TRUE.equals(request.requireOob());
        String oobStatus = requireOob ? "pending_oob" : "confirmed";
        String oobToken = References.digits(6);
        String oobHash = com.elepay.common.Idempotency.sha256(oobToken);

        db.sql("""
                INSERT INTO settlement_destination (id, owner_type, owner_id, type, network, account_number, account_name,
                    created_by, verification, verified_name, verified_by, cooling_off_until, oob_verification_status,
                    oob_token_hash, oob_confirmed_at, updated_at)
                VALUES (:id, 'merchant', :m, :type, :network, :account, :name, :by, :v, :vname, :vby,
                        :cooling, :oobStatus, :oobHash, :oobAt, now())""")
                .param("id", dest).param("m", id).param("type", request.type()).param("network", request.network())
                .param("account", account).param("name", request.accountName()).param("by", a.actor().subject())
                .param("v", verified ? "verified" : "unverified").param("vname", check.name())
                .param("vby", verified ? "network:" + check.source() : null)
                .param("cooling", coolingOffUntil)
                .param("oobStatus", oobStatus)
                .param("oobHash", oobHash)
                .param("oobAt", requireOob ? null : java.time.OffsetDateTime.now())
                .update();
        audit.record(a.actor().subject(), "merchant.destination_added", "merchant:" + id,
                Map.of("destination", dest.toString(), "type", request.type(), "network", request.network(),
                        "accountLast4", account.substring(account.length() - 4),
                        "coolingOffHours", String.valueOf(effectiveCoolingHours)));
        return new Destination(dest, request.type(), request.network(), account, request.accountName(), "active",
                verified ? "verified" : "unverified", check.name(), coolingOffUntil, oobStatus);
    }

    @PostMapping("/merchants/{id}/settlement-destinations/{destination}/confirm-oob")
    @Transactional
    public Map<String, Object> confirmOob(@PathVariable UUID id, @PathVariable UUID destination,
                                          @RequestBody(required = false) OobConfirmRequest request) {
        var a = access.require(id, MANAGE);
        a.actor().requireUser();
        var rowOpt = db.sql("""
                SELECT oob_token_hash, cooling_off_until FROM settlement_destination
                WHERE id = :d AND owner_type = 'merchant' AND owner_id = :m AND status = 'active'""")
                .param("d", destination).param("m", id)
                .query((rs, i) -> new Object[]{rs.getString(1), rs.getObject(2, java.time.OffsetDateTime.class)})
                .optional();
        if (rowOpt.isEmpty()) throw ApiException.notFound("Destination not found");
        String storedHash = (String) rowOpt.get()[0];
        if (request != null && request.token() != null && !request.token().isBlank() && storedHash != null) {
            if (!storedHash.equals(com.elepay.common.Idempotency.sha256(request.token().trim()))) {
                throw ApiException.forbidden("Invalid out-of-band confirmation token");
            }
        }
        int effectiveCoolingHours = destinationCoolingOffHours;
        java.time.OffsetDateTime existingCooling = (java.time.OffsetDateTime) rowOpt.get()[1];
        java.time.OffsetDateTime minCooling = effectiveCoolingHours > 0
                ? java.time.OffsetDateTime.now().plusHours(effectiveCoolingHours)
                : existingCooling;
        java.time.OffsetDateTime coolingOffUntil = (existingCooling != null && minCooling != null && existingCooling.isAfter(minCooling))
                ? existingCooling
                : minCooling;
        db.sql("""
                UPDATE settlement_destination
                SET oob_verification_status = 'confirmed',
                    oob_confirmed_at = now(),
                    cooling_off_until = :cooling,
                    updated_at = now()
                WHERE id = :d AND owner_type = 'merchant' AND owner_id = :m""")
                .param("cooling", coolingOffUntil).param("d", destination).param("m", id).update();
        audit.record(a.actor().subject(), "merchant.destination_oob_confirmed", "merchant:" + id,
                Map.of("destination", destination.toString()));
        return Map.of("destinationId", destination, "oobVerificationStatus", "confirmed",
                "coolingOffUntil", String.valueOf(coolingOffUntil));
    }

    @DeleteMapping("/merchants/{id}/settlement-destinations/{destination}")
    @Transactional
    public Map<String, String> removeDestination(@PathVariable UUID id, @PathVariable UUID destination) {
        var a = access.require(id, MANAGE);
        a.actor().requireUser();
        int updated = db.sql("""
                UPDATE settlement_destination SET status = 'removed', updated_at = now()
                WHERE id = :d AND owner_type = 'merchant' AND owner_id = :m AND status = 'active'""")
                .param("d", destination).param("m", id).update();
        if (updated == 0) throw ApiException.notFound("Destination not found");
        audit.record(a.actor().subject(), "merchant.destination_removed", "merchant:" + id,
                Map.of("destination", destination.toString()));
        return Map.of("status", "removed");
    }

    @GetMapping("/merchants/{id}/settlement-destinations")
    public List<Destination> destinations(@PathVariable UUID id) {
        access.require(id, FINANCE_VIEW);
        return db.sql("""
                SELECT id, type, network, account_number, account_name, status, verification, verified_name,
                       cooling_off_until, COALESCE(oob_verification_status, 'confirmed')
                FROM settlement_destination
                WHERE owner_type = 'merchant' AND owner_id = :m AND status = 'active' ORDER BY created_at""")
                .param("m", id).query((rs, i) -> new Destination(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getString(8), rs.getObject(9, java.time.OffsetDateTime.class), rs.getString(10))).list();
    }

    /** Operations verify a destination from documents when no network name enquiry is available. */
    @PostMapping("/ops/merchants/{id}/settlement-destinations/{destination}/verify")
    @Transactional
    public Map<String, String> verifyDestination(@PathVariable UUID id, @PathVariable UUID destination) {
        Actor actor = Actor.current();
        actor.requireOps();
        int updated = db.sql("""
                UPDATE settlement_destination SET verification = 'verified', verified_by = :by,
                       verified_name = coalesce(verified_name, account_name),
                       oob_verification_status = 'confirmed',
                       updated_at = now()
                WHERE id = :d AND owner_type = 'merchant' AND owner_id = :m AND status = 'active'""")
                .param("by", "ops:" + actor.subject()).param("d", destination).param("m", id).update();
        if (updated == 0) throw ApiException.notFound("Destination not found");
        audit.record(actor.subject(), "merchant.destination_verified", "merchant:" + id, Map.of("destination", destination.toString()));
        return Map.of("verification", "verified");
    }

    // ---- marketplaces ---------------------------------------------------------------------

    @PostMapping("/merchants/{id}/sellers")
    @Transactional
    public Seller invite(@PathVariable UUID id, @Valid @RequestBody SellerInvite request) {
        var a = access.require(id, MANAGE);
        var marketplace = access.find(id).orElseThrow();
        if (!marketplace.kind().equals("marketplace")) throw ApiException.conflict("not_marketplace", "Only marketplaces add sellers");
        var seller = access.findByCode(request.merchantCode())
                .orElseThrow(() -> ApiException.notFound("No merchant uses this code"));
        if (seller.id().equals(id)) throw ApiException.badRequest("self", "A marketplace cannot sell to itself");
        db.sql("INSERT INTO marketplace_seller (marketplace_id, seller_id) VALUES (:m, :s) ON CONFLICT DO NOTHING")
                .param("m", id).param("s", seller.id()).update();
        audit.record(a.actor().subject(), "marketplace.seller_invited", "merchant:" + id, Map.of("seller", seller.id().toString()));
        return sellers(id).stream().filter(s -> s.merchantId().equals(seller.id())).findFirst().orElseThrow();
    }

    @PostMapping("/merchants/{id}/marketplaces/{marketplace}/accept")
    @Transactional
    public Map<String, String> accept(@PathVariable UUID id, @PathVariable UUID marketplace) {
        var a = access.require(id, MANAGE);
        int updated = db.sql("""
                UPDATE marketplace_seller SET status = 'active'
                WHERE marketplace_id = :m AND seller_id = :s AND status = 'invited'""")
                .param("m", marketplace).param("s", id).update();
        if (updated == 0) throw ApiException.notFound("No pending invitation");
        audit.record(a.actor().subject(), "marketplace.seller_accepted", "merchant:" + id, Map.of("marketplace", marketplace.toString()));
        return Map.of("status", "active");
    }

    @GetMapping("/merchants/{id}/sellers")
    public List<Seller> listSellers(@PathVariable UUID id) {
        access.require(id, VIEW);
        return sellers(id);
    }

    private List<Seller> sellers(UUID marketplace) {
        return db.sql("""
                SELECT m.id, m.trading_name, m.kind, s.status FROM marketplace_seller s JOIN merchant m ON m.id = s.seller_id
                WHERE s.marketplace_id = :m ORDER BY m.trading_name""").param("m", marketplace)
                .query((rs, i) -> new Seller(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4))).list();
    }

    // ---- balances, keys, webhooks ---------------------------------------------------------

    @GetMapping("/merchants/{id}/balances")
    public Balances balances(@PathVariable UUID id) {
        access.require(id, FINANCE_VIEW);
        return new Balances(id, Ledger.GHS,
                ledger.balance(Ledger.code("merchant", id, "pending", Ledger.GHS)),
                ledger.balance(Ledger.code("merchant", id, "available", Ledger.GHS)),
                ledger.balance(Ledger.code("merchant", id, "reserved", Ledger.GHS)));
    }

    @PostMapping("/merchants/{id}/api-keys")
    public ApiKeys.Created createKey(@PathVariable UUID id, @Valid @RequestBody KeyRequest request) {
        var a = access.require(id, MANAGE);
        a.actor().requireUser();
        var created = apiKeys.create(id, request.label(), request.scopes(), request.keyType(),
                request.allowedIps(), a.actor().subject());
        audit.record(a.actor().subject(), "merchant.api_key_created", "merchant:" + id,
                Map.of("prefix", created.prefix(), "scopes", String.join(",", created.scopes()),
                        "keyType", created.keyType()));
        return created;
    }

    @GetMapping("/merchants/{id}/api-keys")
    public List<ApiKeys.Listed> keys(@PathVariable UUID id) {
        access.require(id, MANAGE);
        return apiKeys.list(id);
    }

    @DeleteMapping("/merchants/{id}/api-keys/{key}")
    public Map<String, String> revokeKey(@PathVariable UUID id, @PathVariable UUID key) {
        var a = access.require(id, MANAGE);
        if (!apiKeys.revoke(id, key)) throw ApiException.notFound("API key not found");
        audit.record(a.actor().subject(), "merchant.api_key_revoked", "merchant:" + id, Map.of("key", key.toString()));
        return Map.of("status", "revoked");
    }

    @PostMapping("/merchants/{id}/webhooks")
    public Webhooks.Endpoint addWebhook(@PathVariable UUID id, @Valid @RequestBody WebhookRequest request) {
        var a = access.require(id, MANAGE);
        a.actor().requireUser();
        var endpoint = webhooks.register(id, request.url());
        audit.record(a.actor().subject(), "merchant.webhook_added", "merchant:" + id, Map.of("url", request.url()));
        return endpoint;
    }

    @GetMapping("/merchants/{id}/webhooks")
    public List<Webhooks.Endpoint> listWebhooks(@PathVariable UUID id) {
        access.require(id, MANAGE);
        return webhooks.list(id);
    }

    // ---- delegation and event recovery ------------------------------------------------------

    @PutMapping("/merchants/{id}/delegations/{delegate}")
    @Transactional
    public DelegationView delegate(@PathVariable UUID id, @PathVariable UUID delegate, @Valid @RequestBody Delegation request) {
        var a = access.require(id, MANAGE);
        a.actor().requireUser();
        var target = access.find(delegate).orElseThrow(() -> ApiException.notFound("Delegate merchant not found"));
        if (delegate.equals(id)) throw ApiException.badRequest("self", "A merchant cannot delegate to itself");
        db.sql("DELETE FROM merchant_delegation WHERE merchant_id = :m AND delegate_id = :d").param("m", id).param("d", delegate).update();
        for (String permission : request.permissions().stream().distinct().toList()) {
            db.sql("INSERT INTO merchant_delegation (merchant_id, delegate_id, permission, granted_by) VALUES (:m, :d, :p, :by)")
                    .param("m", id).param("d", delegate).param("p", permission).param("by", a.actor().subject()).update();
        }
        audit.record(a.actor().subject(), "merchant.delegation_set", "merchant:" + id,
                Map.of("delegate", delegate.toString(), "permissions", String.join(",", request.permissions())));
        return new DelegationView(id, delegate, target.tradingName(), request.permissions().stream().distinct().sorted().toList());
    }

    @DeleteMapping("/merchants/{id}/delegations/{delegate}")
    @Transactional
    public Map<String, String> revokeDelegation(@PathVariable UUID id, @PathVariable UUID delegate) {
        var a = access.require(id, MANAGE);
        db.sql("DELETE FROM merchant_delegation WHERE merchant_id = :m AND delegate_id = :d").param("m", id).param("d", delegate).update();
        audit.record(a.actor().subject(), "merchant.delegation_revoked", "merchant:" + id, Map.of("delegate", delegate.toString()));
        return Map.of("status", "revoked");
    }

    /**
     * Event recovery: every outbox event that concerns this merchant, in
     * delivery order, after {@code after} (a sequence number). Integrations
     * page through this to recover missed webhooks.
     */
    @GetMapping("/merchants/{id}/events")
    public List<Event> events(@PathVariable UUID id, @RequestParam(defaultValue = "0") long after,
                              @RequestParam(defaultValue = "100") int limit) {
        access.require(id, VIEW);
        return outbox.eventsFor(id, after, limit).stream().map(e -> new Event(e.id(), e.sequence(), e.type(),
                e.aggregateId(), e.createdAt(), json.readTree(e.payload()))).toList();
    }
}
