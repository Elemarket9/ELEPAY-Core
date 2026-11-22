package com.elepay.bills;

import com.elepay.ledger.Ledger;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AirtimeDataPurchaseEngine {

    private final JdbcClient db;
    private final BillCatalogService catalogService;
    private final CarrierIntegrationClient carrierClient;
    private final Ledger ledger;

    public AirtimeDataPurchaseEngine(
            JdbcClient db,
            BillCatalogService catalogService,
            CarrierIntegrationClient carrierClient,
            Ledger ledger
    ) {
        this.db = db;
        this.catalogService = catalogService;
        this.carrierClient = carrierClient;
        this.ledger = ledger;
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

        if (!carrierClient.isConfigured(network)) {
            db.sql("""
                INSERT INTO bill_payment_transaction
                (id, customer_id, provider_code, product_code, biller_account, amount_pesewas, fee_pesewas, total_pesewas, reference, partner_reference, status)
                VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, NULL, 'UNAVAILABLE')
            """).params(txnId, req.customerId(), network, productCode, req.phoneNumber(), req.amountPesewas(), req.amountPesewas(), ref)
            .update();

            return new AirtimeDataReceipt(
                    txnId, ref, null, network, productCode, req.phoneNumber(),
                    req.amountPesewas(), 0L, null, "UNAVAILABLE"
            );
        }

        var delivery = carrierClient.deliverAirtime(network, req.phoneNumber(), req.amountPesewas(), ref);
        if (!"COMPLETED".equals(delivery.status())) {
            db.sql("""
                INSERT INTO bill_payment_transaction
                (id, customer_id, provider_code, product_code, biller_account, amount_pesewas, fee_pesewas, total_pesewas, reference, partner_reference, status)
                VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, NULL, ?)
            """).params(txnId, req.customerId(), network, productCode, req.phoneNumber(), req.amountPesewas(), req.amountPesewas(), ref, delivery.status())
            .update();

            return new AirtimeDataReceipt(
                    txnId, ref, null, network, productCode, req.phoneNumber(),
                    req.amountPesewas(), 0L, null, delivery.status()
            );
        }

        // Authoritative ledger posting when carrier delivers airtime
        String customerAccount = ledger.open("customer", req.customerId(), "available", Ledger.GHS);
        String carrierClearing = ledger.openSystem("system:clearing:" + network.toLowerCase() + ":GHS", "clearing", Ledger.GHS, true);
        var posted = ledger.post(Ledger.Posting.of("airtime-purchase", ref, "airtime_purchase", List.of(
                Ledger.Leg.debit(customerAccount, req.amountPesewas()),
                Ledger.Leg.credit(carrierClearing, req.amountPesewas())
        )).describedAs(network + " Airtime delivery to " + req.phoneNumber()));

        db.sql("""
            INSERT INTO bill_payment_transaction
            (id, customer_id, provider_code, product_code, biller_account, amount_pesewas, fee_pesewas, total_pesewas, reference, partner_reference, status, journal_tx)
            VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?, 'COMPLETED', ?)
        """).params(txnId, req.customerId(), network, productCode, req.phoneNumber(), req.amountPesewas(), req.amountPesewas(), ref, delivery.carrierReference(), posted.transactionId())
            .update();

        return new AirtimeDataReceipt(
                txnId, ref, delivery.carrierReference(), network, productCode, req.phoneNumber(),
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

        if (!carrierClient.isConfigured(product.providerCode())) {
            db.sql("""
                INSERT INTO bill_payment_transaction
                (id, customer_id, provider_code, product_code, biller_account, amount_pesewas, fee_pesewas, total_pesewas, reference, partner_reference, status)
                VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, NULL, 'UNAVAILABLE')
            """).params(txnId, req.customerId(), product.providerCode(), product.productCode(), req.phoneNumber(),
                    product.amountPesewas(), product.amountPesewas(), ref)
            .update();

            return new AirtimeDataReceipt(
                    txnId, ref, null, product.providerCode(), product.productCode(), req.phoneNumber(),
                    product.amountPesewas(), 0L, null, "UNAVAILABLE"
            );
        }

        var delivery = carrierClient.deliverBundle(product.providerCode(), product.productCode(), req.phoneNumber(), product.amountPesewas(), ref);
        if (!"COMPLETED".equals(delivery.status())) {
            db.sql("""
                INSERT INTO bill_payment_transaction
                (id, customer_id, provider_code, product_code, biller_account, amount_pesewas, fee_pesewas, total_pesewas, reference, partner_reference, status)
                VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, NULL, ?)
            """).params(txnId, req.customerId(), product.providerCode(), product.productCode(), req.phoneNumber(),
                    product.amountPesewas(), product.amountPesewas(), ref, delivery.status())
            .update();

            return new AirtimeDataReceipt(
                    txnId, ref, null, product.providerCode(), product.productCode(), req.phoneNumber(),
                    product.amountPesewas(), 0L, null, delivery.status()
            );
        }

        // Authoritative ledger posting when carrier delivers bundle
        String customerAccount = ledger.open("customer", req.customerId(), "available", Ledger.GHS);
        String carrierClearing = ledger.openSystem("system:clearing:" + product.providerCode().toLowerCase() + ":GHS", "clearing", Ledger.GHS, true);
        var posted = ledger.post(Ledger.Posting.of("bundle-purchase", ref, "bundle_purchase", List.of(
                Ledger.Leg.debit(customerAccount, product.amountPesewas()),
                Ledger.Leg.credit(carrierClearing, product.amountPesewas())
        )).describedAs(product.providerCode() + " bundle delivery to " + req.phoneNumber()));

        db.sql("""
            INSERT INTO bill_payment_transaction
            (id, customer_id, provider_code, product_code, biller_account, amount_pesewas, fee_pesewas, total_pesewas, reference, partner_reference, status, token_voucher, journal_tx)
            VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?, 'COMPLETED', ?, ?)
        """).params(txnId, req.customerId(), product.providerCode(), product.productCode(), req.phoneNumber(),
                product.amountPesewas(), product.amountPesewas(), ref, delivery.carrierReference(), delivery.voucherCode(), posted.transactionId())
        .update();

        return new AirtimeDataReceipt(
                txnId, ref, delivery.carrierReference(), product.providerCode(), product.productCode(), req.phoneNumber(),
                product.amountPesewas(), 0L, delivery.voucherCode(), "COMPLETED"
        );
    }
}
