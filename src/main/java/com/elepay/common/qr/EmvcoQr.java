package com.elepay.common.qr;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.modulith.NamedInterface;

/**
 * Algorithm 022: EMVCo QR Code Encoding & Decoding.
 * Implements EMVCo Merchant-Presented Mode (MPM) specification:
 * - TLV parsing and building
 * - Standard CCITT-FALSE / 0x1021 CRC16 with init 0xFFFF (Tag 63)
 */
@NamedInterface
public final class EmvcoQr {
    public static final String TAG_PAYLOAD_FORMAT_INDICATOR = "00";
    public static final String TAG_POINT_OF_INITIATION_METHOD = "01";
    public static final String TAG_MERCHANT_ACCOUNT_INFO = "26";
    public static final String TAG_MERCHANT_CATEGORY_CODE = "52";
    public static final String TAG_TRANSACTION_CURRENCY = "53";
    public static final String TAG_TRANSACTION_AMOUNT = "54";
    public static final String TAG_COUNTRY_CODE = "58";
    public static final String TAG_MERCHANT_NAME = "59";
    public static final String TAG_MERCHANT_CITY = "60";
    public static final String TAG_ADDITIONAL_DATA = "62";
    public static final String TAG_CRC = "63";

    public static final String POINT_STATIC = "11";
    public static final String POINT_DYNAMIC = "12";
    public static final String DEFAULT_CURRENCY_GHS = "936";
    public static final String DEFAULT_MCC = "5411";
    public static final String DEFAULT_COUNTRY = "GH";
    public static final String DEFAULT_CITY = "Accra";
    public static final String REVERSE_DOMAIN = "com.elepay";

    private static final int[] CRC_TABLE = new int[256];
    static {
        for (int i = 0; i < 256; i++) {
            int curr = i << 8;
            for (int j = 0; j < 8; j++) {
                if ((curr & 0x8000) != 0) {
                    curr = ((curr << 1) ^ 0x1021) & 0xFFFF;
                } else {
                    curr = (curr << 1) & 0xFFFF;
                }
            }
            CRC_TABLE[i] = curr;
        }
    }

    private EmvcoQr() {}

    /**
     * Calculates CRC16-CCITT (polynomial 0x1021, initial value 0xFFFF, CCITT-FALSE).
     * RFC test vector: "123456789" -> "29B1".
     */
    public static String calculateCrc(String data) {
        int crc = 0xFFFF;
        byte[] bytes = data.getBytes(StandardCharsets.ISO_8859_1);
        for (byte b : bytes) {
            crc = ((crc << 8) & 0xFFFF) ^ CRC_TABLE[((crc >> 8) ^ (b & 0xFF)) & 0xFF];
        }
        return String.format("%04X", crc);
    }

    public static String tlv(String tag, String value) {
        if (value == null) return "";
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return String.format("%s%02d%s", tag, bytes.length, value);
    }

    public static Map<String, String> parseTlv(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        int index = 0;
        while (index + 4 <= raw.length()) {
            String tag = raw.substring(index, index + 2);
            int len;
            try {
                len = Integer.parseInt(raw.substring(index + 2, index + 4));
            } catch (NumberFormatException e) {
                break;
            }
            int valStart = index + 4;
            int valEnd = valStart + len;
            if (valEnd > raw.length()) {
                break;
            }
            String value = raw.substring(valStart, valEnd);
            result.put(tag, value);
            index = valEnd;
        }
        return result;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String pointOfInitiation = POINT_DYNAMIC;
        private String merchantId;
        private String merchantCode;
        private String merchantCategoryCode = DEFAULT_MCC;
        private String currency = DEFAULT_CURRENCY_GHS;
        private String amount;
        private String countryCode = DEFAULT_COUNTRY;
        private String merchantName = "ELEPAY Merchant";
        private String merchantCity = DEFAULT_CITY;
        private String reference;

        public Builder pointOfInitiation(String poi) {
            this.pointOfInitiation = poi;
            return this;
        }

        public Builder merchantId(String id) {
            this.merchantId = id;
            return this;
        }

        public Builder merchantCode(String code) {
            this.merchantCode = code;
            return this;
        }

        public Builder merchantCategoryCode(String mcc) {
            this.merchantCategoryCode = mcc;
            return this;
        }

        public Builder currency(String cur) {
            this.currency = cur;
            return this;
        }

        public Builder amount(String amt) {
            this.amount = amt;
            return this;
        }

        public Builder countryCode(String country) {
            this.countryCode = country;
            return this;
        }

        public Builder merchantName(String name) {
            this.merchantName = name;
            return this;
        }

        public Builder merchantCity(String city) {
            this.merchantCity = city;
            return this;
        }

        public Builder reference(String ref) {
            this.reference = ref;
            return this;
        }

        public String build() {
            StringBuilder sb = new StringBuilder();
            // 00: Payload Format Indicator
            sb.append(tlv(TAG_PAYLOAD_FORMAT_INDICATOR, "01"));
            // 01: Point of Initiation Method
            sb.append(tlv(TAG_POINT_OF_INITIATION_METHOD, pointOfInitiation != null ? pointOfInitiation : POINT_DYNAMIC));

            // 26: Merchant Account Information
            StringBuilder tag26 = new StringBuilder();
            tag26.append(tlv("00", REVERSE_DOMAIN));
            String identifier = merchantCode != null ? merchantCode : (merchantId != null ? merchantId : "UNKNOWN");
            tag26.append(tlv("01", identifier));
            sb.append(tlv(TAG_MERCHANT_ACCOUNT_INFO, tag26.toString()));

            // 52: MCC
            sb.append(tlv(TAG_MERCHANT_CATEGORY_CODE, merchantCategoryCode != null ? merchantCategoryCode : DEFAULT_MCC));
            // 53: Currency
            sb.append(tlv(TAG_TRANSACTION_CURRENCY, currency != null ? currency : DEFAULT_CURRENCY_GHS));

            // 54: Amount if present
            if (amount != null && !amount.isBlank()) {
                sb.append(tlv(TAG_TRANSACTION_AMOUNT, amount));
            }

            // 58: Country Code
            sb.append(tlv(TAG_COUNTRY_CODE, countryCode != null ? countryCode : DEFAULT_COUNTRY));

            // 59: Merchant Name (sanitized max 25 chars)
            String safeName = merchantName != null ? merchantName : "ELEPAY Merchant";
            if (safeName.length() > 25) safeName = safeName.substring(0, 25);
            sb.append(tlv(TAG_MERCHANT_NAME, safeName));

            // 60: Merchant City (max 15 chars)
            String safeCity = merchantCity != null ? merchantCity : DEFAULT_CITY;
            if (safeCity.length() > 15) safeCity = safeCity.substring(0, 15);
            sb.append(tlv(TAG_MERCHANT_CITY, safeCity));

            // 62: Additional Data Field (reference)
            if (reference != null && !reference.isBlank()) {
                String sub62 = tlv("05", reference);
                sb.append(tlv(TAG_ADDITIONAL_DATA, sub62));
            }

            // 63: CRC16
            String prefix = sb.toString() + TAG_CRC + "04";
            String crc = calculateCrc(prefix);
            sb.append(TAG_CRC).append("04").append(crc);

            return sb.toString();
        }
    }

    public record Decoded(
            boolean valid,
            String formatIndicator,
            String pointOfInitiation,
            String merchantAccountInfo,
            String merchantCode,
            String merchantCategoryCode,
            String currency,
            String amount,
            String countryCode,
            String merchantName,
            String merchantCity,
            Map<String, String> additionalData,
            String expectedCrc,
            String calculatedCrc,
            Map<String, String> tags
    ) {}

    public static Decoded decode(String qrPayload) {
        if (qrPayload == null || qrPayload.length() < 10) {
            return new Decoded(false, null, null, null, null, null, null, null, null, null, null, Map.of(), null, null, Map.of());
        }
        Map<String, String> tags = parseTlv(qrPayload);
        String formatIndicator = tags.get(TAG_PAYLOAD_FORMAT_INDICATOR);
        String pointOfInitiation = tags.get(TAG_POINT_OF_INITIATION_METHOD);
        String merchantAccountInfo = tags.get(TAG_MERCHANT_ACCOUNT_INFO);
        String merchantCategoryCode = tags.get(TAG_MERCHANT_CATEGORY_CODE);
        String currency = tags.get(TAG_TRANSACTION_CURRENCY);
        String amount = tags.get(TAG_TRANSACTION_AMOUNT);
        String countryCode = tags.get(TAG_COUNTRY_CODE);
        String merchantName = tags.get(TAG_MERCHANT_NAME);
        String merchantCity = tags.get(TAG_MERCHANT_CITY);
        String additionalDataRaw = tags.get(TAG_ADDITIONAL_DATA);
        String crc = tags.get(TAG_CRC);

        Map<String, String> sub26 = merchantAccountInfo != null ? parseTlv(merchantAccountInfo) : Map.of();
        String merchantCode = sub26.get("01");

        Map<String, String> additionalData = additionalDataRaw != null ? parseTlv(additionalDataRaw) : Map.of();

        boolean validCrc = false;
        String calculatedCrc = null;
        if (crc != null && crc.length() == 4 && qrPayload.contains("6304" + crc)) {
            int crcPos = qrPayload.lastIndexOf("6304");
            String prefix = qrPayload.substring(0, crcPos + 4);
            calculatedCrc = calculateCrc(prefix);
            validCrc = calculatedCrc.equalsIgnoreCase(crc);
        }

        boolean valid = validCrc && "01".equals(formatIndicator) && merchantAccountInfo != null;

        return new Decoded(
                valid,
                formatIndicator,
                pointOfInitiation,
                merchantAccountInfo,
                merchantCode,
                merchantCategoryCode,
                currency,
                amount,
                countryCode,
                merchantName,
                merchantCity,
                additionalData,
                crc,
                calculatedCrc,
                tags
        );
    }
}
