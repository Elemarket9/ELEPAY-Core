package com.elepay.bills;

import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AirtimeDataPurchaseEngine {

    private final JdbcClient db;
    private final BillCatalogService catalogService;

    public AirtimeDataPurchaseEngine(JdbcClient db, BillCatalogService catalogService) {
        this.db = db;
        this.catalogService = catalogService;
    }

    public record AirtimePurchaseRequest(
            UUID customerId,
            String network, // MTN, TELECEL, AIRTELTIGO
            String phoneNumber,
            long amountPesewas
    ) {}

    public record BundlePurchaseRequest(
            UUID customerId,
            String productCode,
            String phoneNumber
    ) {}

    public record AirtimeDataReceipt(
            UUID transactionId,
            String reference,
            String partnerReference,
            String network,
            String productCode,
            String phoneNumber,
            long amountPesewas,
            long feePesewas,
            String tokenVoucher,
            String status
    ) {}

    public String detectNetwork(String phoneNumber) {
        String clean = phoneNumber.replaceAll("[^0-9]", "");
        if (clean.startsWith("233")) {
            clean = "0" + clean.substring(3);
        }

        if (clean.length() < 3) {
            throw new IllegalArgumentException("Invalid phone number length: " + phoneNumber);
        }

        String prefix = clean.substring(0, 3);
        return switch (prefix) {
            case "024", "054", "055", "059", "025" -> "MTN";
            case "020", "050" -> "TELECEL";
            case "027", "057", "026", "056" -> "AIRTELTIGO";
            default -> throw new IllegalArgumentException("Unknown telco network prefix for number: " + phoneNumber);
        };
    }

    @Transactional
    public AirtimeDataReceipt purchaseAirtime(AirtimePurchaseRequest req) {
        if (req.amountPesewas() < 50) { // Min 50 pesewas (GHS 0.50)
            throw new IllegalArgumentException("Minimum airtime purchase is 50 pesewas");
        }

        String detected = detectNetwork(req.phoneNumber());
        if (req.network() != null && !req.network().equalsIgnoreCase(detected)) {
            throw new IllegalArgumentException("Specified network " + req.network() + " does not match phone number prefix for " + req.phoneNumber() + " (detected: " + detected + ")");
        }

        String network = detected;
        String productCode = network + "_AIRTIME";
        UUID txnId = UUID.randomUUID();
        String ref = "AIR-" + System.currentTimeMillis() + "-" + txnId.toString().substring(0, 6).toUpperCase();
        String partnerRef = network.substring(0, 3) + "-GW-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        db.sql("""
            INSERT INTO bill_payment_transaction
            (id, customer_id, provider_code, product_code, biller_account, amount_pesewas, fee_pesewas, total_pesewas, reference, partner_reference, status)
            VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?, 'COMPLETED')
        """).params(txnId, req.customerId(), network, productCode, req.phoneNumber(), req.amountPesewas(), req.amountPesewas(), ref, partnerRef)
        .update();

        return new AirtimeDataReceipt(
                txnId, ref, partnerRef, network, productCode, req.phoneNumber(),
                req.amountPesewas(), 0L, null, "COMPLETED"
        );
    }

    @Transactional
    public AirtimeDataReceipt purchaseBundle(BundlePurchaseRequest req) {
        var product = catalogService.getProductByCode(req.productCode())
                .orElseThrow(() -> new IllegalArgumentException("Data bundle product not found: " + req.productCode()));

        String detected = detectNetwork(req.phoneNumber());
        if (!product.providerCode().equalsIgnoreCase(detected)) {
            throw new IllegalArgumentException("Product provider " + product.providerCode() + " does not match phone number network: " + detected);
        }

        UUID txnId = UUID.randomUUID();
        String ref = "BND-" + System.currentTimeMillis() + "-" + txnId.toString().substring(0, 6).toUpperCase();
        String partnerRef = detected.substring(0, 3) + "-BND-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String bundleVoucher = "VOL-" + product.dataMb() + "MB-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        db.sql("""
            INSERT INTO bill_payment_transaction
            (id, customer_id, provider_code, product_code, biller_account, amount_pesewas, fee_pesewas, total_pesewas, reference, partner_reference, status, token_voucher)
            VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?, 'COMPLETED', ?)
        """).params(txnId, req.customerId(), product.providerCode(), product.productCode(), req.phoneNumber(),
                product.amountPesewas(), product.amountPesewas(), ref, partnerRef, bundleVoucher)
        .update();

        return new AirtimeDataReceipt(
                txnId, ref, partnerRef, product.providerCode(), product.productCode(), req.phoneNumber(),
                product.amountPesewas(), 0L, bundleVoucher, "COMPLETED"
        );
    }
}
