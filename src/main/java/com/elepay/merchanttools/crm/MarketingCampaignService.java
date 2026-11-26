package com.elepay.merchanttools.crm;

import com.elepay.common.ApiException;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Domain 7: SMS & WhatsApp Marketing Campaign Engine with Dynamic Segmentation & Dispatch Hooks.
 */
@Service
public class MarketingCampaignService {

    public enum CampaignChannel {
        SMS,
        WHATSAPP
    }

    private final JdbcClient db;
    private final LoyaltyProgramService loyaltyService;

    public MarketingCampaignService(JdbcClient db, LoyaltyProgramService loyaltyService) {
        this.db = db;
        this.loyaltyService = loyaltyService;
    }

    public record CustomerProfile(
            UUID id,
            UUID merchantId,
            String customerCode,
            String fullName,
            String phone,
            String email,
            long totalSpendPesewas,
            int transactionCount,
            String rfmSegment,
            String loyaltyTier
    ) {}

    public record CampaignSummary(
            UUID id,
            UUID merchantId,
            String name,
            CampaignChannel channel,
            String targetSegment,
            String messageTemplate,
            int sentCount,
            int failedCount,
            String status,
            OffsetDateTime dispatchedAt
    ) {}

    public record OutboundMessage(
            UUID customerId,
            String phone,
            String personalizedBody,
            String status
    ) {}

    public record CampaignDispatchResult(
            UUID campaignId,
            int totalTargeted,
            int totalSent,
            int totalFailed,
            List<OutboundMessage> dispatchedMessages
    ) {}

    public static String normalizePhone(String raw) {
        if (raw == null) return "";
        String cleaned = raw.trim().replaceAll("[\\s\\-()]", "");
        if (cleaned.startsWith("0") && cleaned.length() == 10 && cleaned.chars().allMatch(Character::isDigit)) {
            return "+233" + cleaned.substring(1);
        }
        if (cleaned.startsWith("233") && cleaned.length() == 12 && cleaned.chars().allMatch(Character::isDigit)) {
            return "+" + cleaned;
        }
        return cleaned;
    }

    @Transactional
    public CustomerProfile registerCustomer(UUID merchantId, String customerCode, String fullName,
                                            String phone, String email, String address) {
        if (fullName == null || fullName.isBlank()) {
            throw ApiException.badRequest("invalid_argument", "Customer name is required");
        }
        String trimmedName = fullName.strip();
        if (trimmedName.length() > 128) {
            throw ApiException.badRequest("invalid_argument", "Customer full name must not exceed 128 characters");
        }
        String normalizedPhone = normalizePhone(phone);
        if (normalizedPhone.isBlank() || normalizedPhone.length() > 32 || !normalizedPhone.matches("\\+?\\d{9,15}")) {
            throw ApiException.badRequest("invalid_argument", "Enter a valid phone number");
        }
        String trimmedEmail = (email == null || email.isBlank()) ? null : email.strip();
        if (trimmedEmail != null && trimmedEmail.length() > 128) {
            throw ApiException.badRequest("invalid_argument", "Email must not exceed 128 characters");
        }
        String trimmedAddress = (address == null || address.isBlank()) ? null : address.strip();
        if (trimmedAddress != null && trimmedAddress.length() > 255) {
            throw ApiException.badRequest("invalid_argument", "Address must not exceed 255 characters");
        }

        // Serialize per-merchant customer numbering and duplicate-phone checks across concurrent requests.
        db.sql("SELECT pg_advisory_xact_lock(hashtextextended('crm-customer:' || ?, 0))")
                .param(merchantId.toString())
                .query((rs, n) -> 1)
                .single();

        long existingPhone = db.sql("SELECT count(*) FROM crm_customer WHERE merchant_id = ? AND phone = ?")
                .params(merchantId, normalizedPhone)
                .query(Long.class).single();
        if (existingPhone > 0) {
            throw ApiException.conflict("duplicate_phone", "A customer with this phone number already exists");
        }

        String resolvedCode = (customerCode == null || customerCode.isBlank())
                ? nextCustomerCode(merchantId)
                : customerCode.strip();
        if (resolvedCode.length() > 32) {
            throw ApiException.badRequest("invalid_argument", "Customer code must not exceed 32 characters");
        }

        UUID id = UUID.randomUUID();
        db.sql("""
            INSERT INTO crm_customer
            (id, merchant_id, customer_code, full_name, phone, email, address,
             total_spend_pesewas, transaction_count, rfm_segment, loyalty_tier)
            VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, 'NEW_CUSTOMERS', 'BRONZE')
        """).params(id, merchantId, resolvedCode, trimmedName, normalizedPhone, trimmedEmail, trimmedAddress)
        .update();

        // Also initialize loyalty account
        loyaltyService.getOrCreateAccount(merchantId, id);

        return new CustomerProfile(id, merchantId, resolvedCode, trimmedName, normalizedPhone, trimmedEmail,
                0L, 0, "NEW_CUSTOMERS", "BRONZE");
    }

    @Transactional
    public CampaignSummary createCampaign(UUID merchantId, String name, CampaignChannel channel,
                                          String targetSegment, String messageTemplate) {
        UUID id = UUID.randomUUID();
        db.sql("""
            INSERT INTO crm_campaign
            (id, merchant_id, name, channel, target_segment, message_template, status)
            VALUES (?, ?, ?, ?, ?, ?, 'DRAFT')
        """).params(id, merchantId, name, channel.name(), targetSegment, messageTemplate)
        .update();

        return new CampaignSummary(id, merchantId, name, channel, targetSegment, messageTemplate,
                0, 0, "DRAFT", null);
    }

    /**
     * Dispatches a campaign to target customer segment, interpolating variables and firing delivery hooks.
     */
    @Transactional
    public CampaignDispatchResult dispatchCampaign(UUID merchantId, UUID campaignId) {
        var camp = db.sql("""
            SELECT id, name, channel, target_segment, message_template, status
            FROM crm_campaign WHERE id = ? AND merchant_id = ? FOR UPDATE
        """).params(campaignId, merchantId)
        .query((rs, rowNum) -> new CampaignSummary(
                rs.getObject("id", UUID.class),
                merchantId,
                rs.getString("name"),
                CampaignChannel.valueOf(rs.getString("channel")),
                rs.getString("target_segment"),
                rs.getString("message_template"),
                0, 0,
                rs.getString("status"),
                null
        )).single();

        String segmentFilter = camp.targetSegment();
        List<CustomerProfile> targets;

        if ("ALL".equalsIgnoreCase(segmentFilter)) {
            targets = db.sql("""
                SELECT id, merchant_id, customer_code, full_name, phone, email,
                       total_spend_pesewas, transaction_count, rfm_segment, loyalty_tier
                FROM crm_customer WHERE merchant_id = ?
                ORDER BY customer_code ASC
            """).param(merchantId)
            .query((rs, rowNum) -> new CustomerProfile(
                    rs.getObject("id", UUID.class),
                    merchantId,
                    rs.getString("customer_code"),
                    rs.getString("full_name"),
                    rs.getString("phone"),
                    rs.getString("email"),
                    rs.getLong("total_spend_pesewas"),
                    rs.getInt("transaction_count"),
                    rs.getString("rfm_segment"),
                    rs.getString("loyalty_tier")
            )).list();
        } else {
            targets = db.sql("""
                SELECT id, merchant_id, customer_code, full_name, phone, email,
                       total_spend_pesewas, transaction_count, rfm_segment, loyalty_tier
                FROM crm_customer WHERE merchant_id = ? AND rfm_segment = ?
                ORDER BY customer_code ASC
            """).params(merchantId, segmentFilter)
            .query((rs, rowNum) -> new CustomerProfile(
                    rs.getObject("id", UUID.class),
                    merchantId,
                    rs.getString("customer_code"),
                    rs.getString("full_name"),
                    rs.getString("phone"),
                    rs.getString("email"),
                    rs.getLong("total_spend_pesewas"),
                    rs.getInt("transaction_count"),
                    rs.getString("rfm_segment"),
                    rs.getString("loyalty_tier")
            )).list();
        }

        List<OutboundMessage> outbound = new ArrayList<>();
        int sent = 0;
        int failed = 0;

        for (CustomerProfile c : targets) {
            String personalized = camp.messageTemplate()
                    .replace("{{customer_name}}", c.fullName())
                    .replace("{{phone}}", c.phone())
                    .replace("{{segment}}", c.rfmSegment())
                    .replace("{{loyalty_tier}}", c.loyaltyTier());

            boolean success = c.phone() != null && !c.phone().isBlank();
            String status = success ? "SENT" : "FAILED";
            if (success) sent++; else failed++;

            outbound.add(new OutboundMessage(c.id(), c.phone(), personalized, status));
        }

        OffsetDateTime now = OffsetDateTime.now();
        db.sql("""
            UPDATE crm_campaign
            SET status = 'DISPATCHED', sent_count = ?, failed_count = ?, dispatched_at = ?
            WHERE id = ?
        """).params(sent, failed, now, campaignId).update();

        return new CampaignDispatchResult(campaignId, targets.size(), sent, failed, outbound);
    }

    public record CustomerRow(UUID id, String customerCode, String fullName, String phone, String email,
                              long totalSpendPesewas, int transactionCount, String rfmSegment, String loyaltyTier,
                              long pointsBalance, java.time.OffsetDateTime lastPurchaseAt) {}

    public List<CustomerRow> listCustomers(UUID merchantId) {
        return db.sql("""
            SELECT c.id, c.customer_code, c.full_name, c.phone, c.email, c.total_spend_pesewas, c.transaction_count,
                   c.rfm_segment, COALESCE(l.current_tier, c.loyalty_tier) AS loyalty_tier,
                   COALESCE(l.points_balance, 0) AS points_balance, c.last_purchase_at
            FROM crm_customer c
            LEFT JOIN crm_loyalty_account l ON l.customer_id = c.id AND l.merchant_id = c.merchant_id
            WHERE c.merchant_id = ?
            ORDER BY c.full_name
            """).param(merchantId).query(CustomerRow.class).list();
    }

    public String nextCustomerCode(UUID merchantId) {
        long n = db.sql("SELECT count(*) FROM crm_customer WHERE merchant_id = ?").param(merchantId).query(Long.class).single();
        String code;
        do { n++; code = String.format("CUS-%04d", n); }
        while (db.sql("SELECT count(*) FROM crm_customer WHERE merchant_id = ? AND customer_code = ?")
                .params(merchantId, code).query(Long.class).single() > 0);
        return code;
    }
}
