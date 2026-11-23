package com.elepay.emtech;

import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Builds a {@code cryptoPSPTransactionV2} object. Amounts are JSON numbers, ids and places are strings. */
public final class EmtechTransactionMapper {
    private final ObjectMapper json;
    private final String platformEmoneyType;

    public EmtechTransactionMapper(ObjectMapper json, String platformEmoneyType) {
        this.json = json;
        this.platformEmoneyType = platformEmoneyType == null ? "" : platformEmoneyType;
    }

    public ObjectNode toJson(RegulatoryFacts.Transaction fact) {
        var origin = EmtechAccountTypeMapper.map(fact.originInstrument(), fact.originProvider(),
                fact.originRegulatoryAccountType(), platformEmoneyType);
        var destination = EmtechAccountTypeMapper.map(fact.destinationInstrument(), fact.destinationProvider(),
                fact.destinationRegulatoryAccountType(), platformEmoneyType);
        var kyc = EmtechKycMapper.map(new EmtechKycMapper.Input(
                fact.originKycTier(), fact.ghanaCardStatus(), fact.faceApproved(),
                fact.originKycService(), fact.originKycType(), fact.originKycStatus(),
                fact.originKycTypeDetails(), fact.originKycReason()));
        ObjectNode node = json.createObjectNode();
        putText(node, "transactionId", fact.transactionId());
        putText(node, "transactionType", type(fact.elepayKind()));
        putText(node, "transactionStatus", status(fact.elepayStatus()));
        putText(node, "transactionChannel", channel(fact.channel()));
        putText(node, "transactionDatetime", fact.occurredAt());
        putText(node, "transactionDeviceId", fact.deviceId());
        node.put("transactionFeeAmount", EmtechMoney.ghs(fact.feePesewas()));
        putText(node, "transactionFeeCurrency", currency(fact.feeCurrency()));
        putText(node, "transactionFeeCurrencyType", "FIAT");
        putText(node, "originUserId", fact.originUserId());
        putText(node, "originAccountID", fact.originAccountId());
        putText(node, "originAccountType", origin.emtechType());
        putText(node, "originAccountProvider", origin.provider());
        putText(node, "originAccountCity", fact.originCity());
        putText(node, "originAccountRegion", fact.originRegion());
        putText(node, "originAccountCountry", fact.originCountry());
        node.put("originAmount", EmtechMoney.ghs(fact.originAmountPesewas()));
        putText(node, "originCurrency", currency(fact.originCurrency()));
        putText(node, "originCurrencyType", currencyType(fact.originInstrument(), fact.originCurrency()));
        putText(node, "originUserKYCLevel", kyc.level());
        putText(node, "originUserKYCService", kyc.service());
        putText(node, "originUserKYCStatus", kyc.status());
        putText(node, "originUserKYCType", kyc.type());
        putText(node, "originUserKYCTypeDetails", kyc.details());
        putText(node, "originUserKYCReason", kyc.reason());
        putText(node, "destinationAccountId", fact.destinationAccountId());
        putText(node, "destinationAccountType", destination.emtechType());
        putText(node, "destinationAccountProvider", destination.provider());
        putText(node, "destinationAccountCity", fact.destinationCity());
        putText(node, "destinationAccountRegion", fact.destinationRegion());
        putText(node, "destinationAccountCountry", fact.destinationCountry());
        node.put("destinationAmount", EmtechMoney.ghs(fact.destinationAmountPesewas()));
        putText(node, "destinationCurrency", currency(fact.destinationCurrency()));
        putText(node, "destinationCurrencyType", currencyType(fact.destinationInstrument(), fact.destinationCurrency()));
        putId(node, "gatewayTransactionId", fact.gatewayTransactionId());
        putText(node, "gatewayTransactionProvider", fact.gatewayProvider());
        putText(node, "gatewayTransactionStatus", fact.gatewayStatus());
        putText(node, "gatewayTransactionReason", fact.gatewayReason());
        putText(node, "transactionReason", reason(fact, kyc));
        node.set("meta", json.valueToTree(meta(fact, origin, destination)));
        return node;
    }

    public static String type(String elepayKind) {
        String kind = elepayKind == null ? "" : elepayKind.toLowerCase();
        return switch (kind) {
            case "payment", "checkout", "purchase" -> "PAYMENT";
            case "topup", "deposit" -> "DEPOSIT";
            case "withdrawal", "payout" -> "WITHDRAWAL";
            case "exchange", "fx" -> "EXCHANGE";
            case "refund", "transfer", "wallet_transfer" -> "OTHER";
            default -> "OTHER";
        };
    }

    public static String status(String elepayStatus) {
        String status = elepayStatus == null ? "" : elepayStatus.toLowerCase();
        if (status.contains("suspect") || status.contains("fraud") || status.contains("sanction")) return "SUSPECT";
        if (status.contains("reject") || status.contains("insufficient")) return "REJECT";
        return switch (status) {
            case "succeeded", "completed", "paid", "success" -> "SUCCESS";
            case "failed", "failure" -> "FAILED";
            case "rejected", "reject" -> "REJECT";
            case "suspect" -> "SUSPECT";
            default -> "OTHER";
        };
    }

    public static String channel(String channel) {
        String value = channel == null ? "" : channel.trim().toUpperCase();
        return switch (value) {
            case "WEB", "MOBILE_APP", "MOBILE_USSD", "ATM", "KIOSK", "POS", "OTHER" -> value;
            case "USSD" -> "MOBILE_USSD";
            case "APP", "MOBILE" -> "MOBILE_APP";
            default -> "OTHER";
        };
    }

    private static String currency(String currency) {
        return currency == null || currency.isBlank() ? "GHS" : currency.trim().toUpperCase();
    }

    private static String currencyType(String instrument, String currency) {
        String code = currency(currency);
        if ("CRYPTO".equals(code) || "BTC".equals(code) || "CRYPTOCURRENCY".equalsIgnoreCase(instrument)) return "VIRTUAL";
        if ("airtime".equalsIgnoreCase(instrument) && !"GHS".equals(code)) return "VIRTUAL";
        return "FIAT";
    }

    private static String reason(RegulatoryFacts.Transaction fact, EmtechKycMapper.Output kyc) {
        if (fact.reason() != null && !fact.reason().isBlank()) return fact.reason();
        if ("OTHER".equals(type(fact.elepayKind()))) return "ELEPAY " + fact.elepayKind();
        if ("UNVERIFIED".equals(kyc.status()) || "NOTCHECKED".equals(kyc.status())) return null;
        return null;
    }

    private static Map<String, Object> meta(RegulatoryFacts.Transaction fact,
                                            EmtechAccountTypeMapper.Resolved origin,
                                            EmtechAccountTypeMapper.Resolved destination) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("source", "ELEPAY");
        meta.put("elepayKind", fact.elepayKind());
        meta.put("originLocalitySource", fact.originLocalitySource());
        meta.put("destinationLocalitySource", fact.destinationLocalitySource());
        if (origin.emoneyWallet()) meta.put("originAccountInstrument", "emoney_wallet");
        else meta.put("originAccountInstrument", fact.originInstrument());
        if (destination.emoneyWallet()) meta.put("destinationAccountInstrument", "emoney_wallet");
        else meta.put("destinationAccountInstrument", fact.destinationInstrument());
        return meta;
    }

    private static void putText(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) node.put(field, value);
    }

    /** Gateway ids must match {@code [\w-]+}. Anything else is left off the wire payload. */
    private static void putId(ObjectNode node, String field, String value) {
        if (value != null && value.matches("[\\w-]+")) node.put(field, value);
    }

    public JsonNode read(String factJson) {
        return json.readTree(factJson);
    }
}
