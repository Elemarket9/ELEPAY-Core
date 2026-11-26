package com.elepay.merchanttools.crm;

import com.elepay.common.ApiException;
import com.elepay.common.Idempotency;
import com.elepay.merchanttools.MerchantToolAccess;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import static com.elepay.merchanttools.MerchantToolAccess.Resource.*;

/**
 * Customers and loyalty points. Store credit and gift cards hold customer money (stored value), which needs
 * Bank of Ghana confirmation first, so they are deliberately not offered here.
 */
@RestController
@RequestMapping("/v1/customers")
public class CustomerController {
    private final MerchantToolAccess access;
    private final MarketingCampaignService customers;
    private final LoyaltyProgramService loyalty;
    private final RfmSegmentationService rfm;

    public CustomerController(MerchantToolAccess access, MarketingCampaignService customers,
                              LoyaltyProgramService loyalty, RfmSegmentationService rfm) {
        this.access = access;
        this.customers = customers;
        this.loyalty = loyalty;
        this.rfm = rfm;
    }

    @GetMapping("/merchant/{merchantId}")
    public List<MarketingCampaignService.CustomerRow> list(@PathVariable UUID merchantId) {
        access.view(merchantId);
        return customers.listCustomers(merchantId);
    }

    public record CreateCustomerRequest(UUID merchantId, String fullName, String phone, String email, String address) {}

    @PostMapping
    @Transactional
    public MarketingCampaignService.CustomerProfile create(@RequestBody CreateCustomerRequest req) {
        access.operate(req.merchantId());
        if (req.fullName() == null || req.fullName().isBlank())
            throw ApiException.badRequest("name_required", "Customer name is required");
        String trimmedName = req.fullName().strip();
        if (trimmedName.length() > 128)
            throw ApiException.badRequest("invalid_argument", "Customer full name must not exceed 128 characters");
        String normalizedPhone = MarketingCampaignService.normalizePhone(req.phone());
        if (normalizedPhone.isBlank() || normalizedPhone.length() > 32 || !normalizedPhone.matches("\\+?\\d{9,15}"))
            throw ApiException.badRequest("phone_invalid", "Enter a valid phone number");
        String email = blank(req.email());
        if (email != null && email.length() > 128)
            throw ApiException.badRequest("invalid_argument", "Email must not exceed 128 characters");
        String address = blank(req.address());
        if (address != null && address.length() > 255)
            throw ApiException.badRequest("invalid_argument", "Address must not exceed 255 characters");
        return customers.registerCustomer(req.merchantId(), null, trimmedName, normalizedPhone, email, address);
    }

    public record PurchaseRequest(long amountPesewas) {}

    /** Records a purchase the customer made, earning loyalty points and updating their spend. */
    @PostMapping("/{customerId}/purchases")
    @Transactional
    public LoyaltyProgramService.PointTransactionResult recordPurchase(
            @PathVariable UUID customerId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody PurchaseRequest req) {
        UUID merchantId = access.operate(CUSTOMER, customerId);
        String key = requireIdempotencyKey(idempotencyKey);
        if (req.amountPesewas() <= 0) throw ApiException.badRequest("amount_invalid", "Purchase amount must be positive");
        String payloadHash = Idempotency.sha256("EARN:" + req.amountPesewas());
        Optional<LoyaltyProgramService.PointTransactionResult> replay =
                loyalty.lockAccountAndFindIdempotentResult(merchantId, customerId, key, payloadHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        rfm.recordCustomerPurchase(customerId, req.amountPesewas());
        return loyalty.earnPointsForPurchase(merchantId, customerId, req.amountPesewas(),
                "MANUAL_PURCHASE", UUID.randomUUID(), key, payloadHash);
    }

    public record RedeemRequest(long points) {}

    @PostMapping("/{customerId}/redeem")
    @Transactional
    public LoyaltyProgramService.PointTransactionResult redeem(
            @PathVariable UUID customerId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody RedeemRequest req) {
        UUID merchantId = access.operate(CUSTOMER, customerId);
        String key = requireIdempotencyKey(idempotencyKey);
        if (req.points() <= 0) throw ApiException.badRequest("points_invalid", "Points to redeem must be positive");
        String payloadHash = Idempotency.sha256("REDEEM:" + req.points());
        try {
            return loyalty.redeemPoints(merchantId, customerId, req.points(),
                    "MANUAL_REDEMPTION", UUID.randomUUID(), key, payloadHash);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw ApiException.unprocessable("redeem_refused", e.getMessage());
        }
    }

    private static String requireIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.strip().length() > 200) {
            throw ApiException.badRequest("idempotency_key_required",
                    "An Idempotency-Key of 1 to 200 characters is required");
        }
        return key.strip();
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.strip(); }
}
