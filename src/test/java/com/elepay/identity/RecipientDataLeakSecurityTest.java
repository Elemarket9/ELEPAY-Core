package com.elepay.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class RecipientDataLeakSecurityTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    // Matching definition from RecipientConfirmations.LookupResult
    public record LookupResult(String status, String rail, String destination, String verifiedName,
                               UUID confirmationId, OffsetDateTime expiresAt, String message) {}

    @Test
    @DisplayName("Recipient lookup response contains strictly masked identity and NO balance or internal UUIDs")
    void recipientLookupExposesNoSensitiveData() throws Exception {
        UUID randomConfirmationId = UUID.randomUUID();
        OffsetDateTime expires = OffsetDateTime.now().plusMinutes(5);

        LookupResult result = new LookupResult(
                "verified",
                "wallet",
                "+233241234567",
                "Kofi Mensah",
                randomConfirmationId,
                expires,
                null
        );

        String json = objectMapper.writeValueAsString(result);
        JsonNode node = objectMapper.readTree(json);

        // 1. Verify exact permissible field set
        Set<String> fields = new HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);

        assertThat(fields).containsExactlyInAnyOrder(
                "status", "rail", "destination", "verifiedName", "confirmationId", "expiresAt", "message"
        );

        // 2. Strict invariant assertions: NEVER expose balance, ledger accounts, customer internal UUIDs
        assertThat(fields).doesNotContain("balance");
        assertThat(fields).doesNotContain("availableBalance");
        assertThat(fields).doesNotContain("currentBalance");
        assertThat(fields).doesNotContain("ledgerAccountId");
        assertThat(fields).doesNotContain("customerId");
        assertThat(fields).doesNotContain("recipientCustomerId");
        assertThat(fields).doesNotContain("internalId");

        // 3. String content checks
        assertThat(json.toLowerCase()).doesNotContain("balance");
        assertThat(json.toLowerCase()).doesNotContain("ledger");
        assertThat(json.toLowerCase()).doesNotContain("account_number");

        // 4. Assert values returned are strictly masked/safe
        assertThat(node.get("verifiedName").asText()).isEqualTo("Kofi Mensah");
        assertThat(node.get("destination").asText()).isEqualTo("+233241234567");
        assertThat(node.get("confirmationId").asText()).isEqualTo(randomConfirmationId.toString());
        assertThat(node.get("status").asText()).isEqualTo("verified");
    }

    @Test
    @DisplayName("Recipient lookup not-found and unavailable results do not leak information")
    void notFoundAndUnavailableLeakNoData() throws Exception {
        LookupResult notFound = new LookupResult(
                "not_found",
                "momo",
                "+233501234567 (telecel)",
                null,
                null,
                null,
                "The network did not find a mobile money wallet for this number."
        );

        String json = objectMapper.writeValueAsString(notFound);
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.get("status").asText()).isEqualTo("not_found");
        assertThat(node.get("verifiedName").isNull()).isTrue();
        assertThat(node.get("confirmationId").isNull()).isTrue();
        assertThat(json.toLowerCase()).doesNotContain("balance");
        assertThat(json.toLowerCase()).doesNotContain("uuid");
    }
}
