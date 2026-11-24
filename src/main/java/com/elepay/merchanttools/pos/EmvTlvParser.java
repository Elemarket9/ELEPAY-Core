package com.elepay.merchanttools.pos;

import com.elepay.common.ApiException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Strict EMV BER-TLV parser with a fail-closed PCI redaction gate.
 * Rejects any payload containing raw PAN (5A), Track 1/2 (56, 57, 9F1F, 9F20),
 * Cardholder Name (5F20), or PIN data (99), and retains only non-sensitive EMV cryptogram
 * and transaction metadata tags.
 */
@Component
public class EmvTlvParser {

    private static final HexFormat HEX_UPPER = HexFormat.of().withUpperCase();

    public static final Set<String> FORBIDDEN_SENSITIVE_TAGS = Set.of(
            "56", "57", "5A", "5F20", "99", "9F1F", "9F20"
    );

    public static final Set<String> ALLOWED_EMV_TAGS = Set.of(
            "9F26", // Application Cryptogram (ARQC)
            "9F27", // Cryptogram Information Data (CID)
            "9F36", // Application Transaction Counter (ATC)
            "9F37", // Unpredictable Number (UN)
            "9F02", // Amount, Authorised (Numeric BCD)
            "5F2A", // Transaction Currency Code (Numeric BCD, 0936 = GHS)
            "9A",   // Transaction Date
            "9C",   // Transaction Type
            "82",   // Application Interchange Profile (AIP)
            "95",   // Terminal Verification Results (TVR)
            "9F34", // Cardholder Verification Method (CVM) Results
            "84"    // Dedicated File (AID)
    );

    public record ParsedEmvPayload(
            Map<String, String> redactedTags,
            long amountMinorUnits,
            String currencyNumeric,
            String cvmMethod,
            String cardScheme,
            String arqc,
            String cid,
            String atc,
            String unpredictableNumber,
            String redactedJson
    ) {}

    public ParsedEmvPayload parseStrictlyRedacted(String emvTlvHex) {
        if (emvTlvHex == null || emvTlvHex.isBlank()) {
            throw ApiException.badRequest("emv_tlv_required", "EMV TLV payload is required");
        }
        String cleanHex = emvTlvHex.trim().toUpperCase(Locale.ROOT);
        if ((cleanHex.length() & 1) != 0 || !cleanHex.matches("^[0-9A-F]+$")) {
            throw ApiException.badRequest("invalid_emv_tlv", "EMV TLV must be even-length hexadecimal");
        }

        byte[] data = HEX_UPPER.parseHex(cleanHex);
        Map<String, String> extracted = new LinkedHashMap<>();
        parseTlvRange(data, 0, data.length, extracted, 0);

        String arqc = requireTag(extracted, "9F26");
        String cid = requireTag(extracted, "9F27");
        String atc = requireTag(extracted, "9F36");
        String un = extracted.getOrDefault("9F37", "");
        String amountHex = requireTag(extracted, "9F02");
        String currencyHex = requireTag(extracted, "5F2A");

        long amountMinorUnits = parseBcdAmount(amountHex);
        String currencyNumeric = parseBcdNumeric(currencyHex, 4, "5F2A");
        String cvmMethod = resolveCvmMethod(extracted.get("9F34"));
        String cardScheme = detectCardScheme(extracted.get("84"));
        Map<String, String> unmodifiable = Collections.unmodifiableMap(extracted);

        return new ParsedEmvPayload(
                unmodifiable,
                amountMinorUnits,
                currencyNumeric,
                cvmMethod,
                cardScheme,
                arqc,
                cid,
                atc,
                un,
                toRedactedJson(unmodifiable)
        );
    }

    private void parseTlvRange(byte[] data, int start, int end, Map<String, String> extracted, int depth) {
        if (depth > 16) {
            throw ApiException.badRequest("invalid_emv_tlv", "EMV TLV nesting depth exceeds maximum");
        }
        int pos = start;
        while (pos < end) {
            // 1. Parse Tag
            int tagStart = pos;
            int firstTagByte = data[pos++] & 0xFF;
            boolean constructed = (firstTagByte & 0x20) != 0;
            if ((firstTagByte & 0x1F) == 0x1F) {
                boolean more = true;
                while (more) {
                    if (pos >= end) {
                        throw ApiException.badRequest("invalid_emv_tlv", "Truncated multi-byte EMV tag");
                    }
                    int nextByte = data[pos++] & 0xFF;
                    more = (nextByte & 0x80) != 0;
                    if (pos - tagStart > 4) {
                        throw ApiException.badRequest("invalid_emv_tlv", "EMV tag exceeds maximum length");
                    }
                }
            }
            String tag = HEX_UPPER.formatHex(data, tagStart, pos);

            // 2. Parse Length (definite BER-TLV only)
            if (pos >= end) {
                throw ApiException.badRequest("invalid_emv_tlv", "Truncated EMV length for tag " + tag);
            }
            int lenByte = data[pos++] & 0xFF;
            int length;
            if (lenByte == 0x80) {
                throw ApiException.badRequest("invalid_emv_tlv", "Indefinite BER-TLV length is not allowed");
            } else if ((lenByte & 0x80) == 0) {
                length = lenByte;
            } else {
                int numLenBytes = lenByte & 0x7F;
                if (numLenBytes == 0 || numLenBytes > 2 || pos + numLenBytes > end) {
                    throw ApiException.badRequest("invalid_emv_tlv", "Malformed BER-TLV length for tag " + tag);
                }
                length = 0;
                for (int i = 0; i < numLenBytes; i++) {
                    length = (length << 8) | (data[pos++] & 0xFF);
                }
            }

            if (pos + length > end) {
                throw ApiException.badRequest("invalid_emv_tlv", "EMV value truncated for tag " + tag);
            }

            // 3. PCI Redaction Gate: immediately reject raw cardholder/PAN/Track/PIN tags at any depth
            if (FORBIDDEN_SENSITIVE_TAGS.contains(tag)) {
                throw ApiException.badRequest("pci_raw_card_data_forbidden",
                        "Sensitive raw card tag " + tag + " is forbidden in POS NFC payloads");
            }

            if (constructed) {
                parseTlvRange(data, pos, pos + length, extracted, depth + 1);
            } else if (ALLOWED_EMV_TAGS.contains(tag)) {
                extracted.put(tag, HEX_UPPER.formatHex(data, pos, pos + length));
            }
            pos += length;
        }
    }

    private static String requireTag(Map<String, String> tags, String tag) {
        String value = tags.get(tag);
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("missing_emv_tag", "Required EMV tag " + tag + " is missing");
        }
        return value;
    }

    static long parseBcdAmount(String hex9F02) {
        if (hex9F02 == null || hex9F02.length() != 12 || !hex9F02.matches("^[0-9]{12}$")) {
            throw ApiException.badRequest("invalid_emv_amount", "EMV tag 9F02 must be 12 BCD digits");
        }
        return Long.parseLong(hex9F02);
    }

    static String parseBcdNumeric(String hex, int expectedDigits, String tag) {
        if (hex == null || hex.length() != expectedDigits || !hex.matches("^[0-9]+$")) {
            throw ApiException.badRequest("invalid_emv_tag", "EMV tag " + tag + " must be " + expectedDigits + " BCD digits");
        }
        return hex;
    }

    static String resolveCvmMethod(String hex9F34) {
        if (hex9F34 == null || hex9F34.length() < 2) {
            return "NO_CVM";
        }
        int cvmCode = Integer.parseInt(hex9F34.substring(0, 2), 16) & 0x3F;
        return switch (cvmCode) {
            case 0x02 -> "ONLINE_PIN";
            case 0x1E -> "SIGNATURE";
            case 0x1F, 0x3F -> "NO_CVM";
            case 0x24, 0x2F -> "CDCVM";
            default -> "CDCVM";
        };
    }

    static String detectCardScheme(String aidHex) {
        if (aidHex == null || aidHex.isBlank()) {
            return "CARD";
        }
        if (aidHex.startsWith("A000000003")) return "VISA";
        if (aidHex.startsWith("A000000004")) return "MASTERCARD";
        if (aidHex.startsWith("A000000025")) return "AMEX";
        if (aidHex.startsWith("A000000152") || aidHex.startsWith("A000000324")) return "DISCOVER";
        if (aidHex.startsWith("A000000333")) return "UNIONPAY";
        return "CARD";
    }

    static String toRedactedJson(Map<String, String> tags) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(entry.getKey()).append("\":\"")
              .append(entry.getValue()).append('"');
        }
        sb.append('}');
        return sb.toString();
    }
}
