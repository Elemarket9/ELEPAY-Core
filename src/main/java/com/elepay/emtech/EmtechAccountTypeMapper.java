package com.elepay.emtech;

import java.util.Locale;
import java.util.Set;

/**
 * ELEPAY payment instrument to Emtech's closed account-type enum.
 *
 * <p>One-to-one instruments:
 * <ul>
 *   <li>{@code momo} → {@code MOBILE_MONEY}</li>
 *   <li>{@code bank} → {@code BANK}</li>
 *   <li>{@code card} (and debit) → {@code CREDIT}. The enum has no card value.
 *       {@code meta.elepayInstrument} still says {@code card}.</li>
 *   <li>{@code cash} → {@code CASH}</li>
 *   <li>{@code airtime} → {@code AIRTIME}</li>
 *   <li>{@code crypto} → {@code CRYPTOCURRENCY}</li>
 * </ul>
 *
 * <p>ELEPAY e-money ({@code wallet}) is not an MNO wallet and not a bank account.
 * The enum has no e-money member, so this mapper will not guess {@code BANK} or
 * {@code MOBILE_MONEY}. The filing value is the customer's
 * {@code regulatory_account_type} when set, otherwise
 * {@code ELEPAY_EMTECH_EMONEY_ACCOUNT_TYPE}. Until compliance sets one of those,
 * a wallet leg cannot be submitted. The wire payload still says
 * {@code meta.elepayAccountInstrument=emoney_wallet} and provider {@code ELEPAY}.
 */
public final class EmtechAccountTypeMapper {
    public static final Set<String> EMTECH = Set.of(
            "CASH", "CREDIT", "BANK", "MOBILE_MONEY", "AIRTIME", "CRYPTOCURRENCY");

    private EmtechAccountTypeMapper() {}

    public record Resolved(String emtechType, boolean emoneyWallet, String provider) {}

    public static Resolved map(String instrument, String provider, String regulatoryAccountType, String platformEmoneyType) {
        String key = instrument == null ? "" : instrument.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "momo", "mobile_money", "mobile-money" ->
                    new Resolved("MOBILE_MONEY", false, blankTo(provider, "MOBILE_MONEY"));
            case "bank", "ghipss", "gip" -> new Resolved("BANK", false, blankTo(provider, "BANK"));
            case "card", "credit", "debit" -> new Resolved("CREDIT", false, blankTo(provider, "CARD"));
            case "cash" -> new Resolved("CASH", false, blankTo(provider, "ELEPAY"));
            case "airtime" -> new Resolved("AIRTIME", false, blankTo(provider, "AIRTIME"));
            case "crypto", "cryptocurrency", "virtual" ->
                    new Resolved("CRYPTOCURRENCY", false, blankTo(provider, "ELEPAY"));
            case "wallet", "emoney", "e-money", "elepay" -> resolveWallet(provider, regulatoryAccountType, platformEmoneyType);
            default -> throw new EmtechValidationException("originAccountType",
                    "No Emtech account type for ELEPAY instrument '" + instrument + "'");
        };
    }

    private static Resolved resolveWallet(String provider, String regulatoryAccountType, String platformEmoneyType) {
        String chosen = firstConfigured(regulatoryAccountType, platformEmoneyType);
        if (chosen == null) {
            throw new EmtechValidationException("originAccountType",
                    "ELEPAY e-money wallet has no Emtech account type. Set customer.regulatory_account_type "
                            + "or ELEPAY_EMTECH_EMONEY_ACCOUNT_TYPE to one of " + EMTECH
                            + ". ELEPAY will not report a wallet as BANK or MOBILE_MONEY by default.");
        }
        if (!EMTECH.contains(chosen)) {
            throw new EmtechValidationException("originAccountType",
                    "E-money classification '" + chosen + "' is not an Emtech account type");
        }
        return new Resolved(chosen, true, blankTo(provider, "ELEPAY"));
    }

    private static String firstConfigured(String regulatory, String platform) {
        if (regulatory != null && !regulatory.isBlank()) return regulatory.trim();
        if (platform != null && !platform.isBlank()) return platform.trim();
        return null;
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
