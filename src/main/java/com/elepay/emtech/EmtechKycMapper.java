package com.elepay.emtech;

/**
 * KYC tier and the Ghana Card / NIA path onto Emtech's KYC fields.
 *
 * <p>Tier 1, 2 and 3 are the BoG-style limits already stored on {@code customer.kyc_tier}:
 * {@code MINIMUM}, {@code MEDIUM} and {@code ENHANCED}. An explicit column overrides a
 * derived value when compliance has recorded one. The Ghana Card number itself is never copied
 * onto the report; the detail is the document type, {@code Ghana Card}.
 */
public final class EmtechKycMapper {
    public static final String NIA_SERVICE = "NIA Ghana Card";

    private EmtechKycMapper() {}

    public record Input(int tier, String ghanaCardStatus, boolean faceApproved,
                        String explicitService, String explicitType, String explicitStatus,
                        String explicitDetails, String explicitReason) {}

    public record Output(String level, String status, String type, String service, String details, String reason) {}

    public static Output map(Input in) {
        String level = switch (in.tier()) {
            case 1 -> "MINIMUM";
            case 2 -> "MEDIUM";
            case 3 -> "ENHANCED";
            default -> "OTHER";
        };
        Derived derived = derive(in.ghanaCardStatus(), in.faceApproved());
        return new Output(
                level,
                override(in.explicitStatus(), derived.status()),
                override(in.explicitType(), derived.type()),
                override(in.explicitService(), derived.service()),
                override(in.explicitDetails(), derived.details()),
                override(in.explicitReason(), derived.reason()));
    }

    private record Derived(String status, String type, String service, String details, String reason) {}

    private static Derived derive(String ghanaCardStatus, boolean faceApproved) {
        if (faceApproved) {
            return new Derived("VERIFIED", "MOBILE_PHOTO", NIA_SERVICE, "Ghana Card", null);
        }
        String status = ghanaCardStatus == null ? "" : ghanaCardStatus.trim().toUpperCase();
        return switch (status) {
            case "VERIFIED" -> new Derived("VERIFIED", "NATIONAL_ID_TEXT", NIA_SERVICE, "Ghana Card", null);
            case "MISMATCHED", "FAILED" -> new Derived("UNVERIFIED", "NATIONAL_ID_TEXT", NIA_SERVICE, "Ghana Card",
                    "Ghana Card verification " + status.toLowerCase());
            case "UNAVAILABLE" -> new Derived("NOTCHECKED", "NATIONAL_ID_TEXT", NIA_SERVICE, "Ghana Card",
                    "NIA verification unavailable");
            default -> new Derived("NOTCHECKED", "OTHER", "ELEPAY", null, "No Ghana Card or biometric check on file");
        };
    }

    private static String override(String explicit, String derived) {
        return explicit == null || explicit.isBlank() ? derived : explicit.trim();
    }
}
