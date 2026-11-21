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

    Merchants(JdbcClient db, MerchantAccess access, Ledger ledger, Audit audit, ApiKeys apiKeys, Webhooks webhooks) {
        this.db = db;
        this.access = access;
        this.ledger = ledger;
        this.audit = audit;
        this.apiKeys = apiKeys;
        this.webhooks = webhooks;
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

    record Destination(UUID id, @NotBlank @Pattern(regexp = "momo|bank") String type, @NotBlank @Size(max = 20) String network,
                       @NotBlank @Size(max = 34) String accountNumber, @NotBlank @Size(max = 150) String accountName,
                       String status) {}

    record SellerInvite(@NotBlank @Pattern(regexp = "[0-9]{6}") String merchantCode) {}

    record Seller(UUID merchantId, String tradingName, String kind, String status) {}

    record Balances(UUID merchantId, String currency, long pending, long available, long reserved) {}

    record Label(@Size(max = 100) String label) {}

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
    public List<MerchantView> queue(@RequestParam(defaultValue = "pending_review") String status) {
        Actor.current().requireOps();
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
    public Destination addDestination(@PathVariable UUID id, @Valid @RequestBody Destination request) {
        var a = access.require(id, MANAGE);
        String account = request.accountNumber();
        if (request.type().equals("momo")) {
            if (!MOMO.contains(request.network())) throw ApiException.badRequest("invalid_network", "Choose mtn, telecel or airteltigo");
            account = References.msisdn(account);
        } else if (!account.matches("[0-9]{6,34}")) {
            throw ApiException.badRequest("invalid_account", "Enter the full bank account number");
        }
        UUID dest = UUID.randomUUID();
        db.sql("""
                INSERT INTO settlement_destination (id, owner_type, owner_id, type, network, account_number, account_name, created_by)
                VALUES (:id, 'merchant', :m, :type, :network, :account, :name, :by)""")
                .param("id", dest).param("m", id).param("type", request.type()).param("network", request.network())
                .param("account", account).param("name", request.accountName()).param("by", a.actor().subject()).update();
        audit.record(a.actor().subject(), "merchant.destination_added", "merchant:" + id,
                Map.of("destination", dest.toString(), "type", request.type(), "network", request.network(),
                        "accountLast4", account.substring(account.length() - 4)));
        return new Destination(dest, request.type(), request.network(), account, request.accountName(), "active");
    }

    @DeleteMapping("/merchants/{id}/settlement-destinations/{destination}")
    @Transactional
    public Map<String, String> removeDestination(@PathVariable UUID id, @PathVariable UUID destination) {
        var a = access.require(id, MANAGE);
        int updated = db.sql("""
                UPDATE settlement_destination SET status = 'removed'
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
                SELECT id, type, network, account_number, account_name, status FROM settlement_destination
                WHERE owner_type = 'merchant' AND owner_id = :m AND status = 'active' ORDER BY created_at""")
                .param("m", id).query((rs, i) -> new Destination(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6))).list();
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
    public ApiKeys.Created createKey(@PathVariable UUID id, @Valid @RequestBody Label request) {
        var a = access.require(id, MANAGE);
        a.actor().requireUser();
        var created = apiKeys.create(id, request.label(), a.actor().subject());
        audit.record(a.actor().subject(), "merchant.api_key_created", "merchant:" + id, Map.of("prefix", created.prefix()));
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
        var endpoint = webhooks.register(id, request.url());
        audit.record(a.actor().subject(), "merchant.webhook_added", "merchant:" + id, Map.of("url", request.url()));
        return endpoint;
    }

    @GetMapping("/merchants/{id}/webhooks")
    public List<Webhooks.Endpoint> listWebhooks(@PathVariable UUID id) {
        access.require(id, MANAGE);
        return webhooks.list(id);
    }
}
