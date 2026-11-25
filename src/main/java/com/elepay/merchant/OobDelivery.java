package com.elepay.merchant;

import com.elepay.common.VerificationDelivery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Sends a settlement destination out-of-band confirmation code to BOTH the merchant's
 * contact email and contact phone (when present) via {@link VerificationDelivery}.
 * The raw code is never returned on the API response or logged in production.
 */
@Component
public class OobDelivery {
    private static final Logger log = LoggerFactory.getLogger(OobDelivery.class);
    private final VerificationDelivery verificationDelivery;

    public OobDelivery(VerificationDelivery verificationDelivery) {
        this.verificationDelivery = verificationDelivery;
    }

    public VerificationDelivery.DeliveryReceipt send(String phone, String email, String token) {
        if (token == null || token.isBlank()) {
            return new VerificationDelivery.DeliveryReceipt(false, false, "none", "none", null, null);
        }
        boolean hasPhone = phone != null && !phone.isBlank();
        boolean hasEmail = email != null && !email.isBlank();
        if (!hasPhone && !hasEmail) {
            log.warn("Merchant has neither contact phone nor contact email for OOB delivery");
            return new VerificationDelivery.DeliveryReceipt(false, false, "none", "none", null, null);
        }
        VerificationDelivery.DeliveryReceipt receipt = verificationDelivery.sendBoth(
                hasEmail ? email : null,
                hasEmail ? token : null,
                hasPhone ? phone : null,
                hasPhone ? token : null,
                "ELEPAY Settlement Destination Confirmation",
                "settlement destination confirmation"
        );
        log.info("Settlement destination OOB confirmation dispatched (email={}, sms={})",
                receipt.emailChannel(), receipt.smsChannel());
        return receipt;
    }
}
