package com.elepay.checkout;

import java.util.UUID;

/** Published inside the checkout completion transaction when a checkout is paid. */
public record CheckoutPaidEvent(
        UUID checkoutId,
        String reference,
        UUID journalTx,
        long amountPesewas,
        String currency,
        String channel
) {}
