package com.elepay.enterprise.accounts;

import java.util.Map;
import java.util.UUID;

public record CreateManagedAccountRequest(
        UUID enterpriseId,
        String externalCustomerId,
        String displayName,
        String phone,
        String email,
        String nationalId,
        Integer initialTier,
        String accountType,
        String currency,
        Map<String, Object> metadata,
        DuplicateAction duplicateAction
) {
    public CreateManagedAccountRequest {
        if (initialTier == null) {
            initialTier = 1;
        }
        if (accountType == null || accountType.isBlank()) {
            accountType = "customer";
        }
        if (currency == null || currency.isBlank()) {
            currency = "GHS";
        }
        if (metadata == null) {
            metadata = Map.of();
        }
        if (duplicateAction == null) {
            duplicateAction = DuplicateAction.FLAG;
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private UUID enterpriseId;
        private String externalCustomerId;
        private String displayName;
        private String phone;
        private String email;
        private String nationalId;
        private Integer initialTier = 1;
        private String accountType = "customer";
        private String currency = "GHS";
        private Map<String, Object> metadata = Map.of();
        private DuplicateAction duplicateAction = DuplicateAction.FLAG;

        public Builder enterpriseId(UUID enterpriseId) {
            this.enterpriseId = enterpriseId;
            return this;
        }

        public Builder externalCustomerId(String externalCustomerId) {
            this.externalCustomerId = externalCustomerId;
            return this;
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder phone(String phone) {
            this.phone = phone;
            return this;
        }

        public Builder email(String email) {
            this.email = email;
            return this;
        }

        public Builder nationalId(String nationalId) {
            this.nationalId = nationalId;
            return this;
        }

        public Builder initialTier(Integer initialTier) {
            this.initialTier = initialTier;
            return this;
        }

        public Builder accountType(String accountType) {
            this.accountType = accountType;
            return this;
        }

        public Builder currency(String currency) {
            this.currency = currency;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder duplicateAction(DuplicateAction duplicateAction) {
            this.duplicateAction = duplicateAction;
            return this;
        }

        public CreateManagedAccountRequest build() {
            return new CreateManagedAccountRequest(
                    enterpriseId, externalCustomerId, displayName, phone, email, nationalId,
                    initialTier, accountType, currency, metadata, duplicateAction
            );
        }
    }
}
