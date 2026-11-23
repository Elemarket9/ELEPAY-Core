package com.elepay.merchanttools.crm;

import com.elepay.common.ApiException;
import com.elepay.merchanttools.MerchantToolAccess;
import java.util.List;
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
        if (req.phone() == null || !req.phone().replaceAll("[\\s-]", "").matches("\\+?\\d{9,15}"))
            throw ApiException.badRequest("phone_invalid", "Enter a valid phone number");
        return customers.registerCustomer(req.merchantId(), customers.nextCustomerCode(req.merchantId()),
                req.fullName().strip(), req.phone().replaceAll("[\\s-]", ""), blank(req.email()), blank(req.address()));
    }

    public record PurchaseRequest(long amountPesewas) {}

    /** Records a purchase the customer made, earning loyalty points and updating their spend. */
    @PostMapping("/{customerId}/purchases")
    @Transactional
    public LoyaltyProgramService.PointTransactionResult recordPurchase(@PathVariable UUID customerId, @RequestBody PurchaseRequest req) {
        UUID merchantId = access.operate(CUSTOMER, customerId);
        if (req.amountPesewas() <= 0) throw ApiException.badRequest("amount_invalid", "Purchase amount must be positive");
        rfm.recordCustomerPurchase(customerId, req.amountPesewas());
        return loyalty.earnPointsForPurchase(merchantId, customerId, req.amountPesewas(), "MANUAL_PURCHASE", UUID.randomUUID());
    }

    public record RedeemRequest(long points) {}

    @PostMapping("/{customerId}/redeem")
    public LoyaltyProgramService.PointTransactionResult redeem(@PathVariable UUID customerId, @RequestBody RedeemRequest req) {
        UUID merchantId = access.operate(CUSTOMER, customerId);
        if (req.points() <= 0) throw ApiException.badRequest("points_invalid", "Points to redeem must be positive");
        try {
            return loyalty.redeemPoints(merchantId, customerId, req.points(), "MANUAL_REDEMPTION", UUID.randomUUID());
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw ApiException.unprocessable("redeem_refused", e.getMessage());
        }
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.strip(); }
}
