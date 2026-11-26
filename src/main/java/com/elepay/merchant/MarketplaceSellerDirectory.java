package com.elepay.merchant;

import com.elepay.common.ApiException;
import com.elepay.common.Audit;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/merchants/{marketplace}/seller-directory")
class MarketplaceSellerDirectory {
    record Entry(@NotBlank @Pattern(regexp="[A-Za-z0-9._:-]{1,128}") String externalSellerId,
                 String merchantCode, Merchants.Application profile, Boolean marketplaceApproved, @Size(max=200) String evidenceReference) {}
    record Result(String externalSellerId, UUID merchantId, String status, boolean payoutDestinationReady, String detail) {}

    private final JdbcClient db;
    private final MerchantAccess access;
    private final Merchants merchants;
    private final Validator validation;
    private final Audit audit;
    private final TransactionTemplate tx;

    MarketplaceSellerDirectory(JdbcClient db, MerchantAccess access, Merchants merchants, Validator validation,
            Audit audit, PlatformTransactionManager tm) {
        this.db = db;
        this.access = access;
        this.merchants = merchants;
        this.validation = validation;
        this.audit = audit;
        this.tx = new TransactionTemplate(tm);
    }

    @PostMapping("/sync")
    public List<Result> sync(@PathVariable UUID marketplace, @RequestBody List<Entry> entries) {
        var actor = access.require(marketplace, MerchantAccess.Permission.SELLER_SYNC).actor();
        if (!access.find(marketplace).orElseThrow().kind().equals("marketplace"))
            throw ApiException.forbidden("Seller sync requires a marketplace");
        if (entries.isEmpty() || entries.size() > 500)
            throw ApiException.badRequest("invalid_batch", "Send between 1 and 500 sellers");
        List<Result> results = new ArrayList<>();
        for (Entry entry : entries) {
            try {
                results.add(tx.execute(s -> syncOne(marketplace, entry, actor.subject())));
            } catch (ApiException error) {
                results.add(new Result(entry == null ? null : entry.externalSellerId(), null,
                        error.code(), false, error.getMessage()));
            }
        }
        return results;
    }

    private Result syncOne(UUID marketplace, Entry entry, String actor) {
        if (entry == null || !validation.validate(entry).isEmpty())
            throw ApiException.badRequest("invalid_entry", "Check the seller ID and evidence reference");
        // Serialize concurrent creation and retries for this external identity.
        db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", marketplace + ":" + entry.externalSellerId()).query((rs,i) -> true).single();
        var existing = db.sql("SELECT seller_id FROM marketplace_seller_directory WHERE marketplace_id=:m AND external_id=:e")
                .param("m", marketplace).param("e", entry.externalSellerId()).query(UUID.class).optional();
        if (existing.isPresent()) {
            if (entry.merchantCode() != null && !access.find(existing.get()).orElseThrow().merchantCode().equals(entry.merchantCode()))
                throw ApiException.conflict("seller_mapping_conflict", "Seller ID already belongs to another merchant");
            if (entry.profile() != null) {
                if (!validation.validate(entry.profile()).isEmpty() || !"merchant".equals(entry.profile().kind()))
                    throw ApiException.badRequest("invalid_profile", "Supply a valid merchant application");
                int updated = db.sql("""
                        UPDATE merchant SET trading_name=:name WHERE id=:s AND EXISTS (
                        SELECT 1 FROM marketplace_seller_directory WHERE marketplace_id=:m AND seller_id=:s AND managed)
                        AND legal_name=:legal AND contact_phone=:phone
                        AND contact_email IS NOT DISTINCT FROM :email AND registration_number IS NOT DISTINCT FROM :reg""")
                        .param("s", existing.get()).param("m", marketplace).param("name", entry.profile().tradingName())
                        .param("legal", entry.profile().legalName()).param("phone", com.elepay.common.References.msisdn(entry.profile().contactPhone()))
                        .param("email", entry.profile().contactEmail()).param("reg", entry.profile().registrationNumber()).update();
                if (updated == 0) throw ApiException.conflict("identity_review_required", "Identity and contact changes require review");
            }
            if (Boolean.TRUE.equals(entry.marketplaceApproved())) {
                attestManagedSeller(marketplace, existing.get(), entry.evidenceReference(), actor);
            }
            return status(marketplace, entry.externalSellerId());
        }
        boolean managed = entry.merchantCode() == null;
        UUID seller;
        if (managed) {
            if (entry.profile() == null || !validation.validate(entry.profile()).isEmpty() || !"merchant".equals(entry.profile().kind()))
                throw ApiException.badRequest("profile_required", "Supply a valid merchant application");
            seller = merchants.provision(entry.profile(), actor);
        } else {
            if (entry.profile() != null)
                throw ApiException.badRequest("ambiguous_seller", "Supply a profile or an existing merchant code");
            seller = access.findByCode(entry.merchantCode()).orElseThrow(() -> ApiException.notFound("Unknown merchant code")).id();
            if (seller.equals(marketplace)) throw ApiException.badRequest("self", "Cannot link marketplace to itself");
        }
        db.sql("INSERT INTO marketplace_seller(marketplace_id,seller_id,status) VALUES(:m,:s,:state) ON CONFLICT DO NOTHING")
                .param("m", marketplace).param("s", seller).param("state", managed ? "active" : "invited").update();
        int inserted = db.sql("""
                INSERT INTO marketplace_seller_directory(marketplace_id,external_id,seller_id,managed,evidence_reference)
                VALUES(:m,:e,:s,:managed,:evidence) ON CONFLICT DO NOTHING""")
                .param("m", marketplace).param("e", entry.externalSellerId()).param("s", seller)
                .param("managed", managed).param("evidence", entry.evidenceReference()).update();
        if (inserted == 0) throw ApiException.conflict("seller_mapping_conflict", "Merchant already mapped");
        if (managed && Boolean.TRUE.equals(entry.marketplaceApproved())) {
            attestManagedSeller(marketplace, seller, entry.evidenceReference(), actor);
        }
        audit.record(actor, "marketplace.seller_synced", "merchant:" + marketplace, Map.of("seller", seller.toString()));
        return status(marketplace, entry.externalSellerId());
    }

    private void attestManagedSeller(UUID marketplace, UUID seller, String evidence, String actor) {
        int activated = db.sql("""
                UPDATE merchant SET status='active', reviewed_by=:by, reviewed_at=now()
                WHERE id=:s AND status='pending_review' AND reviewed_at IS NULL
                AND EXISTS (SELECT 1 FROM marketplace_seller_directory d
                    JOIN marketplace_seller r USING (marketplace_id,seller_id)
                    JOIN merchant parent ON parent.id=d.marketplace_id
                    WHERE d.marketplace_id=:m AND d.seller_id=:s AND d.managed AND r.status='active'
                    AND parent.kind='marketplace' AND parent.status='active')""")
                .param("by", "partner-attestation:" + marketplace).param("s", seller).param("m", marketplace).update();
        if (activated == 1) {
            db.sql("""
                    UPDATE marketplace_seller_directory SET evidence_reference=COALESCE(:e,evidence_reference)
                    WHERE marketplace_id=:m AND seller_id=:s""")
                    .param("e", evidence).param("m", marketplace).param("s", seller).update();
            audit.record(actor, "marketplace.seller_approval_attested", "merchant:" + seller,
                    Map.of("marketplaceId", marketplace.toString()));
        }
    }

    private UUID managedSeller(UUID marketplace, String externalId) {
        return db.sql("""
                SELECT d.seller_id FROM marketplace_seller_directory d
                JOIN marketplace_seller r USING (marketplace_id,seller_id)
                JOIN merchant m ON m.id=d.seller_id
                WHERE marketplace_id=:m AND external_id=:e AND managed AND r.status='active' AND m.status='active'""")
                .param("m", marketplace).param("e", externalId).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("Active managed seller not found"));
    }

    @PostMapping("/{externalId}/destinations")
    @org.springframework.transaction.annotation.Transactional
    public Merchants.Destination destination(@PathVariable UUID marketplace, @PathVariable String externalId,
            @jakarta.validation.Valid @RequestBody Merchants.DestinationRequest request) {
        var actor = access.require(marketplace, MerchantAccess.Permission.SELLER_SYNC).actor();
        return merchants.registerDestination(managedSeller(marketplace, externalId), request, actor.subject());
    }

    @PostMapping("/{externalId}/destinations/{destination}/confirm")
    @org.springframework.transaction.annotation.Transactional(noRollbackFor=ApiException.class)
    public Map<String,Object> confirm(@PathVariable UUID marketplace, @PathVariable String externalId,
            @PathVariable UUID destination, @RequestBody Merchants.OobConfirmRequest request) {
        var actor = access.require(marketplace, MerchantAccess.Permission.SELLER_SYNC).actor();
        return merchants.confirmDestination(managedSeller(marketplace, externalId), destination, request, actor.subject());
    }

    @PostMapping("/{externalId}/destinations/{destination}/resend")
    @org.springframework.transaction.annotation.Transactional
    public Map<String,Object> resend(@PathVariable UUID marketplace, @PathVariable String externalId,
            @PathVariable UUID destination) {
        var actor = access.require(marketplace, MerchantAccess.Permission.SELLER_SYNC).actor();
        return merchants.resendDestination(managedSeller(marketplace, externalId), destination, actor.subject());
    }

    @GetMapping("/{externalId}")
    public Result lookup(@PathVariable UUID marketplace, @PathVariable String externalId) {
        access.require(marketplace, MerchantAccess.Permission.VIEW);
        return status(marketplace, externalId);
    }

    private Result status(UUID marketplace, String externalId) {
        return db.sql("""
                SELECT d.seller_id, CASE WHEN r.status <> 'active' THEN r.status ELSE m.status END,
                  EXISTS (SELECT 1 FROM settlement_destination t WHERE t.owner_type='merchant' AND t.owner_id=m.id
                    AND t.status='active' AND t.verification='verified' AND COALESCE(t.oob_verification_status,'confirmed')='confirmed'
                    AND (t.cooling_off_until IS NULL OR t.cooling_off_until <= now()))
                FROM marketplace_seller_directory d JOIN merchant m ON m.id=d.seller_id
                JOIN marketplace_seller r USING (marketplace_id,seller_id)
                WHERE d.marketplace_id=:m AND d.external_id=:e""")
                .param("m", marketplace).param("e", externalId)
                .query((rs,i) -> new Result(externalId, rs.getObject(1, UUID.class), rs.getString(2), rs.getBoolean(3), null))
                .optional().orElseThrow(() -> ApiException.notFound("Unknown external seller"));
    }
}
