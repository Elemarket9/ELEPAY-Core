package com.elepay.checkout;

import com.elepay.common.ApiException;
import com.elepay.common.Idempotency;
import com.elepay.merchant.MerchantAccess;
import com.elepay.operations.EmtechCapture;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Merchant-code payments from a wallet for channels such as USSD. */
@Service
public class CheckoutChannel {
    private final CheckoutService checkouts;
    private final MerchantAccess merchants;
    private final Idempotency idempotency;
    private final TransactionTemplate tx;
    private final EmtechCapture regulatory;

    CheckoutChannel(CheckoutService checkouts, MerchantAccess merchants, Idempotency idempotency,
                    PlatformTransactionManager tm, EmtechCapture regulatory) {
        this.checkouts = checkouts;
        this.merchants = merchants;
        this.idempotency = idempotency;
        this.tx = new TransactionTemplate(tm);
        this.regulatory = regulatory;
    }

    public record Payment(String reference, String merchant, long subtotal, long fee, long total, String status) {}

    public Optional<String> activeMerchantName(String code) {
        return merchants.findByCode(code).filter(m -> m.status().equals("active")).map(MerchantAccess.Merchant::tradingName);
    }

    public long walletFee(long amount) {
        return checkouts.customerFee("wallet", amount);
    }

    /** Creates and pays a merchant-code checkout exactly once per idempotency key. */
    public Payment payMerchantCode(UUID customer, String channelSubject, String code, long amount, String key) {
        var merchant = merchants.findByCode(code).filter(m -> m.status().equals("active"))
                .orElseThrow(() -> ApiException.notFound("No active merchant uses this code"));
        return tx.execute(s -> idempotency.execute("channel-merchant-pay:" + customer, key,
                Map.of("code", code, "amount", amount), Payment.class, () -> {
                    var c = checkouts.create(merchant.id(), null, null,
                            List.of(new CheckoutService.AllocationRequest(merchant.id(), "sale", amount, "USSD payment")),
                            "USSD payment", 15, channelSubject);
                    checkouts.noteChannel(c.reference(), "MOBILE_USSD");
                    // The USSD gateway address is not the customer's location.
                    regulatory.rememberCheckout(c.id(), null);
                    var paid = checkouts.payWithWallet(c.reference(), customer);
                    return new Payment(c.reference(), merchant.tradingName(), paid.subtotal(), paid.fee(), paid.total(), paid.status());
                }));
    }
}
