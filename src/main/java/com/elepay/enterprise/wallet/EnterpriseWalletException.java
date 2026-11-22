package com.elepay.enterprise.wallet;

/**
 * Standard enterprise wallet domain exception.
 */
public class EnterpriseWalletException extends RuntimeException {

    public enum ErrorCode {
        INVALID_CREDENTIALS,
        INSUFFICIENT_SCOPE,
        TOKEN_EXPIRED,
        TOKEN_REVOKED,
        INVALID_SIGNATURE,
        REPLAY_ATTACK_DETECTED,
        IDEMPOTENCY_CONFLICT,
        INSUFFICIENT_FUNDS,
        LIMIT_EXCEEDED,
        TRANSACTION_NOT_FOUND,
        INVALID_TRANSACTION_STATE,
        UNSUPPORTED_OPERATION,
        CONNECTOR_UNAVAILABLE,
        PARTNER_COMMUNICATION_ERROR
    }

    private final ErrorCode errorCode;
    private final int httpStatus;

    public EnterpriseWalletException(ErrorCode errorCode, String message, int httpStatus) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    public EnterpriseWalletException(ErrorCode errorCode, String message, int httpStatus, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
