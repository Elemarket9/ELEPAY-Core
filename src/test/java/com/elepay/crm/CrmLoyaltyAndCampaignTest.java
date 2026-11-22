package com.elepay.crm;

import com.elepay.PostgresTestBase;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class CrmLoyaltyAndCampaignTest extends PostgresTestBase {

    @Autowired
    private RfmSegmentationService rfmService;

    @Autowired
    private LoyaltyProgramService loyaltyService;

    @Autowired
    private GiftCardService giftCardService;

    @Autowired
    private CouponEngineService couponService;

    @Autowired
    private StoreCreditService storeCreditService;

    @Autowired
    private MarketingCampaignService campaignService;

    @Test
    @DisplayName("Domain 7: RFM Customer Behavioral Segmentation Engine")
    void testRfmSegmentation() {
        OffsetDateTime now = OffsetDateTime.now();

        // 1. Champions: recent, frequent, high spend
        var champ = rfmService.calculateRfm(now.minusDays(2), 25, 600_000L);
        assertThat(champ.segment()).isEqualTo("CHAMPIONS");
        assertThat(champ.recencyScore()).isEqualTo(5);
        assertThat(champ.frequencyScore()).isEqualTo(5);
        assertThat(champ.monetaryScore()).isEqualTo(5);

        // 2. New Customers: recent, single purchase, low spend
        var newCust = rfmService.calculateRfm(now.minusDays(3), 1, 15_000L);
        assertThat(newCust.segment()).isEqualTo("NEW_CUSTOMERS");

        // 3. At Risk: past frequent buyer who hasn't visited in 90 days
        var atRisk = rfmService.calculateRfm(now.minusDays(90), 8, 350_000L);
        assertThat(atRisk.segment()).isEqualTo("AT_RISK");

        // 4. Hibernating: old visit, single purchase, low spend
        var hiber = rfmService.calculateRfm(now.minusDays(180), 1, 10_000L);
        assertThat(hiber.segment()).isEqualTo("HIBERNATING");
    }

    @Test
    @DisplayName("Domain 7: Tiered Loyalty Points, Multiplier Bonuses & Redemption Ledger")
    void testTieredLoyaltyProgram() {
        UUID merchantId = UUID.randomUUID();
        var cust = campaignService.registerCustomer(
                merchantId, "CUST-LOYAL-1", "Kweku Baah", "+233241112233", "kweku@test.com", "Tema");
        UUID customerId = cust.id();

        // 1. Initial Bronze account
        var acc = loyaltyService.getOrCreateAccount(merchantId, customerId);
        assertThat(acc.currentTier()).isEqualTo(LoyaltyProgramService.LoyaltyTier.BRONZE);
        assertThat(acc.pointsBalance()).isEqualTo(0L);

        // 2. Earn points on GHS 6,000 spend (600,000 pesewas)
        // Bronze multiplier: 1.0x (1 point per 1000 pesewas = 600 points)
        // Lifetime points 600 >= 500 triggers tier upgrade to SILVER!
        var earnRes = loyaltyService.earnPointsForPurchase(
                merchantId, customerId, 600_000L, "POS_ORDER", UUID.randomUUID());

        assertThat(earnRes.pointsDelta()).isEqualTo(600L);
        assertThat(earnRes.newPointsBalance()).isEqualTo(600L);
        assertThat(earnRes.currentTier()).isEqualTo(LoyaltyProgramService.LoyaltyTier.SILVER);

        // 3. Next purchase under Silver (1.25x multiplier bonus)
        // GHS 2,000 spend (200,000 pesewas) -> base 200 points * 1.25 = 250 points
        var earnSilver = loyaltyService.earnPointsForPurchase(
                merchantId, customerId, 200_000L, "INVOICE", UUID.randomUUID());

        assertThat(earnSilver.pointsDelta()).isEqualTo(250L);
        assertThat(earnSilver.newPointsBalance()).isEqualTo(850L);

        // 4. Redeem 200 points for order discount (200 points * 10 pesewas = GHS 20.00 / 2,000 pesewas)
        var redeemRes = loyaltyService.redeemPoints(
                merchantId, customerId, 200L, "CHECKOUT", UUID.randomUUID());

        assertThat(redeemRes.pointsDelta()).isEqualTo(-200L);
        assertThat(redeemRes.newPointsBalance()).isEqualTo(650L);
        assertThat(redeemRes.monetaryEquivalentPesewas()).isEqualTo(2_000L);

        // 5. Over-redemption rejection
        assertThatThrownBy(() -> loyaltyService.redeemPoints(merchantId, customerId, 10_000L, "CHECKOUT", UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Insufficient points");
    }

    @Test
    @DisplayName("Domain 7: Gift Card Issuance, Cryptographic PIN Hashing & Redemption")
    void testGiftCardLifecycle() {
        UUID merchantId = UUID.randomUUID();
        String cardCode = "GC-ELE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String pin = "4829";

        // 1. Issue gift card for GHS 500 (50,000 pesewas)
        var card = giftCardService.issueGiftCard(
                merchantId,
                cardCode,
                pin,
                50_000L,
                "GHS",
                "Akosua Addo",
                "+233245678901",
                OffsetDateTime.now().plusMonths(6)
        );

        assertThat(card.status()).isEqualTo("ACTIVE");
        assertThat(card.currentBalancePesewas()).isEqualTo(50_000L);

        // 2. Reject incorrect PIN
        assertThatThrownBy(() -> giftCardService.redeemGiftCard(merchantId, cardCode, "9999", 10_000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid gift card PIN");

        // 3. Partial redemption with correct PIN (GHS 200 / 20,000 pesewas)
        var partial = giftCardService.redeemGiftCard(merchantId, cardCode, pin, 20_000L);
        assertThat(partial.amountDeductedPesewas()).isEqualTo(20_000L);
        assertThat(partial.remainingBalancePesewas()).isEqualTo(30_000L);
        assertThat(partial.status()).isEqualTo("ACTIVE");

        // 4. Redeem remaining balance (GHS 300 / 30,000 pesewas)
        var full = giftCardService.redeemGiftCard(merchantId, cardCode, pin, 30_000L);
        assertThat(full.amountDeductedPesewas()).isEqualTo(30_000L);
        assertThat(full.remainingBalancePesewas()).isEqualTo(0L);
        assertThat(full.status()).isEqualTo("REDEEMED");
    }

    @Test
    @DisplayName("Domain 7: Dynamic Coupon Engine with Cap, Minimum Order & Usage Limits")
    void testCouponEngine() {
        UUID merchantId = UUID.randomUUID();

        // 1. 20% discount coupon (2000 bps) with max cap GHS 100 (10,000 pesewas) and min order GHS 200 (20,000 pesewas)
        var coupon = couponService.createCoupon(
                merchantId,
                "AKWAABA20",
                CouponEngineService.DiscountType.PERCENTAGE,
                2000L,     // 20%
                20_000L,   // min order GHS 200
                10_000L,   // max cap GHS 100
                5,         // usage limit 5
                OffsetDateTime.now().minusHours(1),
                OffsetDateTime.now().plusDays(7)
        );

        assertThat(coupon.code()).isEqualTo("AKWAABA20");

        // Order below min order (GHS 150 = 15,000 pesewas) -> rejection
        var rejected = couponService.applyCoupon(merchantId, "AKWAABA20", 15_000L);
        assertThat(rejected.isValid()).isFalse();

        // Order of GHS 1,000 (100,000 pesewas) -> 20% would be GHS 200, but capped at GHS 100 (10,000 pesewas)
        var applied = couponService.applyCoupon(merchantId, "AKWAABA20", 100_000L);
        assertThat(applied.isValid()).isTrue();
        assertThat(applied.discountPesewas()).isEqualTo(10_000L); // capped at 10,000
        assertThat(applied.finalOrderAmountPesewas()).isEqualTo(90_000L);
    }

    @Test
    @DisplayName("Domain 7: Customer Store Credit Account & Ledger Balance Tracking")
    void testCustomerStoreCredit() {
        UUID merchantId = UUID.randomUUID();
        var cust = campaignService.registerCustomer(
                merchantId, "CUST-SC-1", "Efua Sutherland", "+233249998877", "efua@test.com", "Cape Coast");
        UUID customerId = cust.id();

        // 1. Add GHS 250 store credit for return refund (25,000 pesewas)
        var credit = storeCreditService.addCredit(
                merchantId,
                customerId,
                25_000L,
                "REFUND_DEPOSIT",
                "RET-001",
                "Store credit for returned shirt"
        );

        assertThat(credit.balanceAfterPesewas()).isEqualTo(25_000L);

        // 2. Deduct GHS 100 towards purchase (10,000 pesewas)
        var deduct = storeCreditService.deductCredit(
                merchantId,
                customerId,
                10_000L,
                "ORD-999",
                "Partial checkout payment"
        );

        assertThat(deduct.balanceAfterPesewas()).isEqualTo(15_000L);

        // 3. Over-deduction rejection
        assertThatThrownBy(() -> storeCreditService.deductCredit(merchantId, customerId, 50_000L, "ORD-999", "Over spend"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Insufficient store credit");
    }

    @Test
    @DisplayName("Domain 7: Targeted Marketing Campaigns & Variable Template Dispatch Hooks")
    void testMarketingCampaigns() {
        UUID merchantId = UUID.randomUUID();

        // Register 2 customers
        var c1 = campaignService.registerCustomer(
                merchantId, "CUST-001", "Abena Osei", "+233240001111", "abena@example.com", "Accra");
        var c2 = campaignService.registerCustomer(
                merchantId, "CUST-002", "Yaw Mensah", "+233200002222", "yaw@example.com", "Kumasi");

        // Create campaign
        var campaign = campaignService.createCampaign(
                merchantId,
                "May Flash Sale",
                MarketingCampaignService.CampaignChannel.WHATSAPP,
                "ALL",
                "Hello {{customer_name}}, enjoy 20% off with code FLASH20 at ELEPAY merchant!"
        );

        assertThat(campaign.status()).isEqualTo("DRAFT");

        // Dispatch campaign
        var dispatch = campaignService.dispatchCampaign(merchantId, campaign.id());
        assertThat(dispatch.totalTargeted()).isEqualTo(2);
        assertThat(dispatch.totalSent()).isEqualTo(2);
        assertThat(dispatch.totalFailed()).isEqualTo(0);

        // Check personalized outbound messages
        var bodies = dispatch.dispatchedMessages().stream()
                .map(MarketingCampaignService.OutboundMessage::personalizedBody)
                .toList();
        assertThat(bodies).anyMatch(b -> b.contains("Hello Abena Osei"));
        assertThat(bodies).anyMatch(b -> b.contains("Hello Yaw Mensah"));
    }
}
