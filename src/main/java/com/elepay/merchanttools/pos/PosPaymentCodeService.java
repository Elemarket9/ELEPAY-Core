package com.elepay.merchanttools.pos;

import com.elepay.common.ApiException;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Read-only resolver for barcode payment references issued by the authoritative checkout. */
@Service
public class PosPaymentCodeService {
    private final JdbcClient db;

    public PosPaymentCodeService(JdbcClient db) { this.db = db; }

    public UUID orderForReference(String reference) {
        if (reference == null || reference.isBlank() || reference.length() > 128)
            throw ApiException.badRequest("payment_barcode_invalid", "Invalid payment barcode reference");
        return db.sql("SELECT order_id FROM pos_checkout_link WHERE checkout_ref = :reference")
                .param("reference", reference).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("Payment barcode not found"));
    }
}
