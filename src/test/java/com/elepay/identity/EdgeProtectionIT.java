package com.elepay.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.elepay.PostgresTestBase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Edge defences wired into the real server: Tomcat, filter order, Spring Security and Jackson. */
class EdgeProtectionIT extends PostgresTestBase {

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void oversizedBodyIsRejectedBeforeAuthentication() throws Exception {
        // A protected route: 413 (not 401) proves the edge filter runs ahead of Spring Security.
        var res = post("/v1/wallet/transfers", "{\"pad\":\"" + "x".repeat(2 * 1024 * 1024) + "\"}");
        assertThat(res.statusCode()).isEqualTo(413);
        assertThat(res.body()).contains("payload_too_large");
    }

    @Test
    void hostileJsonIsRejectedAsBadRequestNotServerError() throws Exception {
        var deep = post("/v1/public/qr/decode", "{\"payload\":" + "[".repeat(5_000) + "]".repeat(5_000) + "}");
        assertThat(deep.statusCode()).isBetween(400, 499);
        var bigNumber = post("/v1/public/qr/decode", "{\"payload\":" + "9".repeat(5_000) + "}");
        assertThat(bigNumber.statusCode()).isBetween(400, 499);
    }

    @Test
    void overlongUriIsRejected() throws Exception {
        var res = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/public/" + "a".repeat(3_000)))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(414);
    }
}
