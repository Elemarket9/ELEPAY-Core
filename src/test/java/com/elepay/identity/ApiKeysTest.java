package com.elepay.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ApiKeysTest {

    @Test
    @DisplayName("ApiKeys.create generates cryptographically secure dynamic signing secret")
    void createGeneratesDynamicSigningSecret() {
        JdbcClient db = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        when(db.sql(anyString()).param(anyString(), any()).update()).thenReturn(1);

        ApiKeys apiKeys = new ApiKeys(db);
        UUID merchantId = UUID.randomUUID();

        ApiKeys.Created created = apiKeys.create(merchantId, "prod-keys", List.of("view", "collect"), "admin");

        assertThat(created).isNotNull();
        assertThat(created.id()).isNotNull();
        assertThat(created.prefix()).startsWith("ek_");
        assertThat(created.secret()).startsWith(created.prefix() + "_");
        assertThat(created.apiKey()).isEqualTo(created.secret());

        // Verify dynamic signing secret
        assertThat(created.signingSecret()).isNotNull();
        assertThat(created.signingSecret()).startsWith("sec_");
        // sec_ + 64 hex chars = 68 chars
        assertThat(created.signingSecret().length()).isGreaterThanOrEqualTo(68);
    }

    @Test
    @DisplayName("ApiKeys.create rejects invalid scopes")
    void createRejectsInvalidScopes() {
        JdbcClient db = mock(JdbcClient.class);
        ApiKeys apiKeys = new ApiKeys(db);
        UUID merchantId = UUID.randomUUID();

        assertThrows(Exception.class, () ->
                apiKeys.create(merchantId, "bad-keys", List.of("invalid_scope_name"), "admin")
        );
    }
}
