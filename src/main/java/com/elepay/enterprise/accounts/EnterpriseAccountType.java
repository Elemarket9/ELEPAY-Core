package com.elepay.enterprise.accounts;

public enum EnterpriseAccountType {
    CUSTOMER("customer"),
    MERCHANT("merchant"),
    SUBACCOUNT("subaccount");

    private final String code;

    EnterpriseAccountType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static EnterpriseAccountType fromCode(String code) {
        for (EnterpriseAccountType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        return CUSTOMER;
    }
}
