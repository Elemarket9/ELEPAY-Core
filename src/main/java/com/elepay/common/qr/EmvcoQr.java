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
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            crc = ((crc << 8) & 0xFFFF) ^ CRC_TABLE[((crc >> 8) ^ (b & 0xFF)) & 0xFF];
        }
        return String.format("%04X", crc);
    }

    public static String tlv(String tag, String value) {
        if (value == null) return "";
        if (tag == null || tag.length() != 2 || !isTwoAsciiDigits(tag.getBytes(StandardCharsets.UTF_8), 0)) {
            throw new IllegalArgumentException("EMVCo TLV tag must be 2 ASCII digits: " + tag);
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 99) {
            throw new IllegalArgumentException(
                    "EMVCo TLV value for tag " + tag + " exceeds maximum length of 99 bytes: " + bytes.length);
        }
        return String.format("%s%02d%s", tag, bytes.length, value);
    }

    private static boolean isTwoAsciiDigits(byte[] bytes, int offset) {
        if (offset + 2 > bytes.length) return false;
        byte b0 = bytes[offset];
        byte b1 = bytes[offset + 1];
        return b0 >= '0' && b0 <= '9' && b1 >= '0' && b1 <= '9';
    }

    private static int parseTwoAsciiDigits(byte[] bytes, int offset) {
        return (bytes[offset] - '0') * 10 + (bytes[offset + 1] - '0');
    }

    private static String truncateUtf8(String value, int maxChars, int maxBytes) {
        if (value == null) return "";
        StringBuilder sb = new StringBuilder();
        int charCount = 0;
        int byteCount = 0;
        int i = 0;
        while (i < value.length() && charCount < maxChars) {
            int cp = value.codePointAt(i);
            int cpChars = Character.charCount(cp);
            if (charCount + cpChars > maxChars) break;
            int cpBytes = cp <= 0x7F ? 1 : (cp <= 0x7FF ? 2 : (cp <= 0xFFFF ? 3 : 4));
            if (byteCount + cpBytes > maxBytes) break;
            sb.appendCodePoint(cp);
            charCount += cpChars;
            byteCount += cpBytes;
            i += cpChars;
        }
        return sb.toString();
    }

    public static Map<String, String> parseTlv(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw == null) {
            return result;
        }
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        int index = 0;
        while (index + 4 <= bytes.length) {
            if (!isTwoAsciiDigits(bytes, index) || !isTwoAsciiDigits(bytes, index + 2)) {
                break;
            }
            String tag = new String(bytes, index, 2, StandardCharsets.UTF_8);
            int len = parseTwoAsciiDigits(bytes, index + 2);
            int valStart = index + 4;
            int valEnd = valStart + len;
            if (valEnd > bytes.length || valEnd < valStart) {
                break;
            }
            String value = new String(bytes, valStart, len, StandardCharsets.UTF_8);
            result.put(tag, value);
            index = valEnd;
        }
        return result;
    }

    private record StrictTlvResult(boolean valid, Map<String, String> tags) {}

    private static StrictTlvResult parseTlvStrict(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new StrictTlvResult(false, Map.of());
        }
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        Map<String, String> tags = new LinkedHashMap<>();
        int idx = 0;
        while (idx + 4 <= bytes.length) {
            if (!isTwoAsciiDigits(bytes, idx) || !isTwoAsciiDigits(bytes, idx + 2)) {
                return new StrictTlvResult(false, tags);
            }
            String tag = new String(bytes, idx, 2, StandardCharsets.UTF_8);
            int len = parseTwoAsciiDigits(bytes, idx + 2);
            int valStart = idx + 4;
            int valEnd = valStart + len;
            if (valEnd > bytes.length || valEnd < valStart) {
                return new StrictTlvResult(false, tags);
            }
            if (tags.containsKey(tag)) {
                return new StrictTlvResult(false, tags);
            }
            tags.put(tag, new String(bytes, valStart, len, StandardCharsets.UTF_8));
            idx = valEnd;
        }
        if (idx != bytes.length) {
            return new StrictTlvResult(false, tags);
        }
        return new StrictTlvResult(true, tags);
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

            // 59: Merchant Name (sanitized max 25 chars and <= 99 UTF-8 bytes)
            String safeName = truncateUtf8(merchantName != null ? merchantName : "ELEPAY Merchant", 25, 99);
            sb.append(tlv(TAG_MERCHANT_NAME, safeName));

            // 60: Merchant City (max 15 chars and <= 99 UTF-8 bytes)
            String safeCity = truncateUtf8(merchantCity != null ? merchantCity : DEFAULT_CITY, 15, 99);
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

    private static boolean isNestedTemplateTag(String tag) {
        if (tag == null || tag.length() != 2) return false;
        int num = (tag.charAt(0) - '0') * 10 + (tag.charAt(1) - '0');
        return (num >= 26 && num <= 51) || num == 62 || num == 64 || (num >= 80 && num <= 99);
    }

    public static Decoded decode(String qrPayload) {
        if (qrPayload == null || qrPayload.length() < 10) {
            return new Decoded(false, null, null, null, null, null, null, null, null, null, null, Map.of(), null, null, Map.of());
        }
        byte[] payloadBytes = qrPayload.getBytes(StandardCharsets.UTF_8);
        boolean hasDuplicateOrTrailing = false;
        Map<String, String> tags = new LinkedHashMap<>();
        int idx = 0;
        while (idx + 4 <= payloadBytes.length) {
            if (!isTwoAsciiDigits(payloadBytes, idx) || !isTwoAsciiDigits(payloadBytes, idx + 2)) {
                hasDuplicateOrTrailing = true;
                break;
            }
            String tag = new String(payloadBytes, idx, 2, StandardCharsets.UTF_8);
            int len = parseTwoAsciiDigits(payloadBytes, idx + 2);
            int valStart = idx + 4;
            int valEnd = valStart + len;
            if (valEnd > payloadBytes.length || valEnd < valStart) {
                hasDuplicateOrTrailing = true;
                break;
            }
            if (tags.containsKey(tag)) {
                hasDuplicateOrTrailing = true;
                break;
            }
            tags.put(tag, new String(payloadBytes, valStart, len, StandardCharsets.UTF_8));
            idx = valEnd;
            if (TAG_CRC.equals(tag)) {
                if (idx != payloadBytes.length) {
                    hasDuplicateOrTrailing = true;
                }
                break;
            }
        }
        if (idx != payloadBytes.length) {
            hasDuplicateOrTrailing = true;
        }

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

        boolean nestedValid = true;
        Map<String, String> sub26 = Map.of();
        if (merchantAccountInfo != null) {
            StrictTlvResult parsed26 = parseTlvStrict(merchantAccountInfo);
            if (!parsed26.valid()) {
                nestedValid = false;
            } else {
                sub26 = parsed26.tags();
            }
        }
        String merchantCode = sub26.get("01");

        Map<String, String> additionalData = Map.of();
        if (additionalDataRaw != null) {
            StrictTlvResult parsed62 = parseTlvStrict(additionalDataRaw);
            if (!parsed62.valid()) {
                nestedValid = false;
            } else {
                additionalData = parsed62.tags();
            }
        }

        // Validate any other nested EMVCo templates (27-51, 64, 80-99) strictly as well
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            String t = entry.getKey();
            if (!TAG_MERCHANT_ACCOUNT_INFO.equals(t) && !TAG_ADDITIONAL_DATA.equals(t) && isNestedTemplateTag(t)) {
                if (!parseTlvStrict(entry.getValue()).valid()) {
                    nestedValid = false;
                }
            }
        }

        boolean validCrc = false;
        String calculatedCrc = null;
        if (!hasDuplicateOrTrailing && crc != null && crc.length() == 4
                && payloadBytes.length >= 8
                && qrPayload.endsWith("6304" + crc)) {
            String prefix = qrPayload.substring(0, qrPayload.length() - 4);
            calculatedCrc = calculateCrc(prefix);
            validCrc = calculatedCrc.equalsIgnoreCase(crc);
        }

        boolean valid = !hasDuplicateOrTrailing && nestedValid && validCrc
                && "01".equals(formatIndicator) && merchantAccountInfo != null;

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
