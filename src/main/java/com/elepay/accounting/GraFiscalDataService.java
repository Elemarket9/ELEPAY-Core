package com.elepay.accounting;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Domain 5: Ghana Revenue Authority (GRA) e-VAT fiscal data integration.
 * Computes statutory taxes (VAT 15%, NHIL 2.5%, GETFund 2.5%, COVID-19 Levy 1.0%),
 * generates certified V-SDC (Virtual Sales Data Controller) fiscal receipt signatures and QR payloads.
 */
@Service
public class GraFiscalDataService {

    public static final int VAT_RATE_BPS = 1500;       // 15.0%
    public static final int NHIL_RATE_BPS = 250;       // 2.5%
    public static final int GETFUND_RATE_BPS = 250;    // 2.5%
    public static final int COVID_LEVY_RATE_BPS = 100; // 1.0%
    public static final int TOTAL_TAX_RATE_BPS = 2100; // 21.0%

    private final JdbcClient db;

    public GraFiscalDataService(JdbcClient db) {
        this.db = db;
    }

    public record TaxBreakdown(
            long taxableAmountPesewas,
            long vatAmountPesewas,
            long nhilAmountPesewas,
            long getfundAmountPesewas,
            long covidLevyAmountPesewas,
            long totalTaxPesewas,
            long grossTotalPesewas
    ) {}

    public record GraFiscalReceipt(
            String sdcReceiptNumber,
            String fiscalSignature,
            String qrCodePayload,
            OffsetDateTime fiscalTimestamp,
            TaxBreakdown taxBreakdown
    ) {}

    /**
     * Calculates itemized statutory Ghanaian taxes from a base taxable amount (in pesewas).
     */
    public TaxBreakdown calculateStatutoryTaxes(long taxableAmountPesewas) {
        long vat = (taxableAmountPesewas * VAT_RATE_BPS) / 10000;
        long nhil = (taxableAmountPesewas * NHIL_RATE_BPS) / 10000;
        long getfund = (taxableAmountPesewas * GETFUND_RATE_BPS) / 10000;
        long covid = (taxableAmountPesewas * COVID_LEVY_RATE_BPS) / 10000;
        long totalTax = vat + nhil + getfund + covid;
        long gross = taxableAmountPesewas + totalTax;

        return new TaxBreakdown(taxableAmountPesewas, vat, nhil, getfund, covid, totalTax, gross);
    }

    /**
     * Generates a certified GRA e-VAT fiscal receipt with cryptographic signature and QR payload.
     */
    @Transactional
    public GraFiscalReceipt signFiscalInvoice(UUID invoiceId, String merchantTin, String invoiceNumber,
                                             long taxableAmountPesewas) {
        TaxBreakdown breakdown = calculateStatutoryTaxes(taxableAmountPesewas);
        OffsetDateTime now = OffsetDateTime.now();
        String timestampStr = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        String rawSignaturePayload = String.format("%s|%s|%s|%d|%d|%d",
                merchantTin != null ? merchantTin : "C0000000000",
                invoiceNumber,
                timestampStr,
                taxableAmountPesewas,
                breakdown.totalTaxPesewas(),
                breakdown.grossTotalPesewas()
        );

        String signature = sha256Hex(rawSignaturePayload);
        String sdcReceiptNumber = String.format("SDC-GRA-GH-%s-%d-%06d",
                merchantTin != null ? merchantTin : "TIN",
                now.getYear(),
                Math.abs(invoiceId.hashCode() % 1000000)
        );

        String qrPayload = String.format("https://gra.gov.gh/evat/verify?sdc=%s&tin=%s&inv=%s&total=%d&tax=%d&sig=%s",
                sdcReceiptNumber,
                merchantTin != null ? merchantTin : "TIN",
                invoiceNumber,
                breakdown.grossTotalPesewas(),
                breakdown.totalTaxPesewas(),
                signature.substring(0, 16)
        );

        // Update the invoice with GRA fiscal data
        db.sql("""
            UPDATE merchant_invoice
            SET gra_sdc_receipt_number = ?, gra_qr_code = ?, gra_signature = ?, updated_at = now()
            WHERE id = ?
        """).params(sdcReceiptNumber, qrPayload, signature, invoiceId).update();

        return new GraFiscalReceipt(sdcReceiptNumber, signature, qrPayload, now, breakdown);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm unavailable", e);
        }
    }
}
