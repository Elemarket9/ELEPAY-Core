package com.elepay;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Generates the API contract from the running application and checks it
 * against the committed docs/openapi.json. Set ELEPAY_WRITE_OPENAPI=1 to
 * regenerate after an intended API change.
 */
class OpenApiContractIT extends PostgresTestBase {
    @LocalServerPort int port;

    @Test
    void committedContractMatchesTheApplication() throws Exception {
        String raw = RestClient.create("http://127.0.0.1:" + port).get().uri("/v3/api-docs").retrieve().body(String.class);
        ObjectMapper mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
        JsonNode generated = mapper.readTree(raw);
        ((tools.jackson.databind.node.ObjectNode) generated).remove("servers");
        String pretty = mapper.writeValueAsString(mapper.treeToValue(generated, java.util.TreeMap.class)) + "\n";
        Path committed = Path.of("docs/openapi.json");
        if ("1".equals(System.getenv("ELEPAY_WRITE_OPENAPI")) || !Files.exists(committed)) {
            Files.writeString(committed, pretty);
        }
        assertThat(generated.path("paths").has("/v1/wallet/recipients/lookup")).isTrue();
        assertThat(generated.path("paths").has("/v1/merchants/{merchantId}/checkouts")).isTrue();
        assertThat(mapper.readTree(Files.readString(committed))).isEqualTo(mapper.readTree(pretty));
    }
}
