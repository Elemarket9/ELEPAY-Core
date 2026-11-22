package com.elepay.checkout;

import com.elepay.common.ApiException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Public checkout module contract for POS. No direct reads of checkout tables by merchant tools. */
@Service
public class PosCheckoutGateway {
    private final CheckoutService checkouts;
    private final String publicBaseUrl;

    PosCheckoutGateway(CheckoutService checkouts, @Value("${elepay.public-base-url}") String publicBaseUrl) {
        this.checkouts = checkouts;
        this.publicBaseUrl = publicBaseUrl;
    }

    public record PaidEvidence(String method, UUID journalTx) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public String create(UUID merchant, UUID branch, UUID linkId, long amount, String actor) {
        return checkouts.create(merchant, branch, null,
                List.of(new CheckoutService.AllocationRequest(merchant,"sale",amount,"POS payment")),
                "POS payment", 30, actor, "pos:" + linkId).reference();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PaidEvidence requirePaid(String reference, UUID merchant, UUID branch, long amount) {
        var c = checkouts.load(reference,true);
        if (!merchant.equals(c.collectorId()) || !Objects.equals(branch,c.branchId()) || c.subtotal()!=amount
                || c.allocations().size()!=1 || !merchant.equals(c.allocations().get(0).merchantId())
                || c.allocations().get(0).amount()!=amount)
            throw ApiException.notFound("Matching POS checkout not found");
        var allocation=c.allocations().get(0);
        if (!"paid".equals(c.status()) || c.journalTx()==null || c.totalPaid()==null
                || c.totalPaid()!=Math.addExact(amount,c.customerFee()) || allocation.refunded()!=0 || allocation.disputed()!=0)
            throw ApiException.conflict("payment_not_confirmed","Checkout is not an undisputed, fully paid ledger transaction");
        return new PaidEvidence(c.paymentMethod(),c.journalTx());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void cancel(UUID merchant,UUID branch,String ref,String actor) {
        checkouts.cancel(merchant,branch,ref,actor);
    }

    public String payUrl(String reference) { return publicBaseUrl + "/pay/" + reference; }
}
