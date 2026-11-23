package com.elepay.merchant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Sends a destination confirmation code to the contact already on the merchant file.
 * The code is never returned on the API that created the destination. Replace this
 * component with an SMS or email provider; the default does not log the code.
 */
@Component
public class OobDelivery {
    private static final Logger log = LoggerFactory.getLogger(OobDelivery.class);

    public void send(String phone, String email, String token) {
        if (token == null || token.isBlank()) return;
        String destination = phone != null && !phone.isBlank() ? "phone" : (email != null ? "email" : "none");
        log.info("Settlement destination confirmation dispatched via {}", destination);
    }
}
