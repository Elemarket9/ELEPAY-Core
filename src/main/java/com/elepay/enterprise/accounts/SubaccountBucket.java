package com.elepay.enterprise.accounts;

public enum SubaccountBucket {
    OPERATING("operating"),
    HOLDING("holding"),
    RESERVED("reserved"),
    REWARDS("rewards");

    private final String code;

    SubaccountBucket(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static SubaccountBucket fromCode(String code) {
        for (SubaccountBucket bucket : values()) {
            if (bucket.code.equalsIgnoreCase(code)) {
                return bucket;
            }
        }
        throw new IllegalArgumentException("Unknown subaccount bucket: " + code);
    }
}
