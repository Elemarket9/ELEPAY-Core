package com.elepay.rails;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Ecobank Corporate API v2 request signing. Field order is a wire-protocol
 * invariant, not a configurable business rule. Only documented formulas are
 * implemented here; this class does not submit transactions or assert success.
 * See https://apimuat-developer.ecobank.com/documentation/endpoint-flows
 */
public final class EcobankRequestSigner {
    private EcobankRequestSigner() {}

    public record Header(String clientId, String affiliateCode, String sourceCode,
                         String requestId, String requestType, String ipAddress) {
        public Header {
            require(clientId); require(affiliateCode); require(sourceCode);
            require(requestId); require(requestType); require(ipAddress);
        }
        private String prefix() {
            return clientId + affiliateCode + sourceCode + requestId + requestType + ipAddress;
        }
        @Override public String toString() { return "EcobankHeader[redacted]"; }
    }

    public record Signature(String requestToken, String secureHash) {
        @Override public String toString() { return "EcobankSignature[redacted]"; }
    }

    public static Signature validateAccount(Header h, String secret, String accountNo, String bankCode) {
        // Account validation uniquely places the request token AFTER account and bank.
        String prefix = prefix(h, secret);
        String token = sha512(prefix + secret);
        return new Signature(token, sha512(prefix + require(accountNo) + require(bankCode) + token + secret));
    }

    /** Account information, account balance, or pool balance. */
    public static Signature accountBalanceOrInformation(Header h, String secret, String accountNo) {
        return tokenFirst(h, secret, require(accountNo));
    }

    /** Account or pool statement; dates must match the serialized request exactly. */
    public static Signature accountStatement(Header h, String secret, String accountNo,
                                             String startDate, String endDate) {
        return tokenFirst(h, secret, require(accountNo), require(startDate), require(endDate));
    }

    /** Get bank institutions, MoMo networks, airtime networks, or biller categories. */
    public static Signature lookup(Header h, String secret) {
        return tokenFirst(h, secret);
    }

    public static Signature domesticTransfer(Header h, String secret, String receiverAccountNo,
                                              String amountString, String currency, String description) {
        return tokenFirst(h, secret, require(receiverAccountNo), require(amountString),
                require(currency), Objects.requireNonNull(description));
    }

    public static Signature accountTransfer(Header h, String secret, String sourceAccountNo,
                                             String receiverAccountNo, String amountString,
                                             String currency, String description) {
        return tokenFirst(h, secret, require(sourceAccountNo), require(receiverAccountNo),
                require(amountString), require(currency), Objects.requireNonNull(description));
    }

    public static Signature initiateDirectDebit(Header h, String secret, String amountString,
                                                 String currency, String description) {
        return tokenFirst(h, secret, require(amountString), require(currency), Objects.requireNonNull(description));
    }

    public static Signature validateDirectDebit(Header h, String secret, String otp, String transactionReference) {
        return tokenFirst(h, secret, require(otp), require(transactionReference));
    }

    private static Signature tokenFirst(Header h, String secret, String... orderedFields) {
        String prefix = prefix(h, secret);
        String token = sha512(prefix + secret);
        return new Signature(token, sha512(prefix + token + String.join("", orderedFields) + secret));
    }

    private static String prefix(Header h, String secret) {
        require(secret);
        return Objects.requireNonNull(h).prefix();
    }

    private static String require(String field) {
        if (field == null || field.isBlank()) throw new IllegalArgumentException("Missing Ecobank signing field");
        return field;
    }

    private static String sha512(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Required SHA-512 algorithm unavailable", e);
        }
    }
}
