package com.elepay.enterprise.payout;

import com.elepay.common.ApiException;

import java.util.Set;

/**
 * Validates and normalizes Ghana mobile subscriber numbers to canonical E.164 format (+233...).
 * Identifies mobile network operators (MTN, Telecel, AirtelTigo).
 */
public final class GhanaPhoneValidator {

    private static final Set<String> MTN_PREFIXES = Set.of("24", "54", "55", "59", "25");
    private static final Set<String> TELECEL_PREFIXES = Set.of("20", "50");
    private static final Set<String> AIRTELTIGO_PREFIXES = Set.of("27", "57", "26", "56");

    public record NormalizedPhone(
            String canonicalE164,
            String network,
            String nationalDigits
    ) {}

    private GhanaPhoneValidator() {}

    /**
     * Normalizes and validates a Ghana phone number into canonical E.164 (+233XXXXXXXXX).
     *
     * @param rawPhone raw input phone string
     * @return NormalizedPhone containing canonical format and identified network
     */
    public static NormalizedPhone validateAndNormalize(String rawPhone) {
        if (rawPhone == null || rawPhone.isBlank()) {
            throw ApiException.badRequest("invalid_phone", "Phone number is required");
        }

        String digits = rawPhone.replaceAll("[^0-9]", "");

        // Format 1: 0XXXXXXXXX (10 digits)
        if (digits.startsWith("0") && digits.length() == 10) {
            digits = "233" + digits.substring(1);
        }

        // Format 2: 233XXXXXXXXX (12 digits)
        if (!digits.matches("233[1-9][0-9]{8}")) {
            throw ApiException.badRequest("invalid_phone",
                    "Invalid Ghana phone number. Expected formats: +233XXXXXXXXX, 024XXXXXXX, or 233XXXXXXXXX");
        }

        String nationalDigits = digits.substring(3); // 9 digits
        String prefix = nationalDigits.substring(0, 2);

        String network;
        if (MTN_PREFIXES.contains(prefix)) {
            network = "mtn";
        } else if (TELECEL_PREFIXES.contains(prefix)) {
            network = "telecel";
        } else if (AIRTELTIGO_PREFIXES.contains(prefix)) {
            network = "airteltigo";
        } else {
            throw ApiException.badRequest("invalid_phone",
                    "Unrecognized Ghana mobile network operator prefix: 0" + prefix);
        }

        String canonicalE164 = "+" + digits;
        return new NormalizedPhone(canonicalE164, network, nationalDigits);
    }
}
