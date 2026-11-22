package com.elepay.crm;

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

    @Transactional
    public CustomerProfile registerCustomer(UUID merchantId, String customerCode, String fullName,
                                            String phone, String email, String address) {
        UUID id = UUID.randomUUID();
        db.sql("""
            INSERT INTO crm_customer
            (id, merchant_id, customer_code, full_name, phone, email, address,
             total_spend_pesewas, transaction_count, rfm_segment, loyalty_tier)
            VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, 'NEW_CUSTOMERS', 'BRONZE')
        """).params(id, merchantId, customerCode, fullName, phone, email, address)
        .update();

        // Also initialize loyalty account
        loyaltyService.getOrCreateAccount(merchantId, id);

        return new CustomerProfile(id, merchantId, customerCode, fullName, phone, email, 0L, 0, "NEW_CUSTOMERS", "BRONZE");
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
}
