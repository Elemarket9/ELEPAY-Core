package com.elepay.rails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elepay.PostgresTestBase;
import com.elepay.common.AlertDispatcher;
import com.elepay.ledger.Ledger;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

class CustodianBankHostToHostIT extends PostgresTestBase {

    @Autowired CustodianBankBackstopService backstop;
    @Autowired Ledger ledger;
    @Autowired ObjectMapper json;
    @Autowired AlertDispatcher alerts;

    private HttpServer server;

    @BeforeEach
    void setUp() {
        backstop.resetForTesting();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        backstop.resetForTesting();
    }

    @Test
    void productionBaseEnforcesHttpsAndAllowlistedCustodianHosts() {
        assertThat(CustodianBankHostToHostClient.productionBase("https://custodian.gcb.com.gh"))
                .isEqualTo(URI.create("https://custodian.gcb.com.gh"));
        assertThat(CustodianBankHostToHostClient.productionBase("https://custodian.ecobank.com.gh"))
                .isEqualTo(URI.create("https://custodian.ecobank.com.gh"));
        assertThat(CustodianBankHostToHostClient.productionBase("https://custodian.stanbic.com.gh"))
                .isEqualTo(URI.create("https://custodian.stanbic.com.gh"));

        assertThatThrownBy(() -> CustodianBankHostToHostClient.productionBase("http://custodian.gcb.com.gh"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CustodianBankHostToHostClient.productionBase("https://evil.example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CustodianBankHostToHostClient.productionBase("https://user:pass@custodian.gcb.com.gh"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CustodianBankHostToHostClient.productionBase("https://custodian.gcb.com.gh?x=1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void h2hClientSignsRequestsHandlesFailClosed5xxAndSyncsTrustBalance() throws IOException {
        String secret = "test-h2h-signing-secret";
        var creds = new CustodianBankHostToHostClient.Credentials(
                "ELEPAY-GH", "TRUST-ACC-001", "api-key-test", secret);

        long baselineLiabilities = backstop.verifyTrustSafeguarding(Ledger.GHS).totalSafeguardedLiabilitiesPesewas();
        long reportedLiveBalance = baselineLiabilities + 500_000L;

        AtomicReference<String> lastDisbursementKey = new AtomicReference<>();
        AtomicReference<Integer> disbursementHttpStatus = new AtomicReference<>(200);
        AtomicReference<String> disbursementStatusBody = new AtomicReference<>("SETTLED");

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/trust-accounts/TRUST-ACC-001/balance", exchange -> {
            String ts = exchange.getRequestHeaders().getFirst("X-Custodian-Timestamp");
            String sig = exchange.getRequestHeaders().getFirst("X-Custodian-Signature");
            String pathWithQuery = exchange.getRequestURI().toString();
            String expected = CustodianBankHostToHostClient.sign(ts, "GET", pathWithQuery, "", secret);
            if (!expected.equals(sig)) {
                byte[] err = "{\"error\":\"bad_signature\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(401, err.length);
                exchange.getResponseBody().write(err);
                exchange.close();
                return;
            }
            String resp = """
                    {"availableBalancePesewas":%d,"ledgerBalancePesewas":%d,"asOf":"%s","statementReference":"STMT-2026-001"}"""
                    .formatted(reportedLiveBalance, reportedLiveBalance, Instant.now());
            byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        server.createContext("/v1/disbursements", exchange -> {
            String method = exchange.getRequestMethod();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String ts = exchange.getRequestHeaders().getFirst("X-Custodian-Timestamp");
            String sig = exchange.getRequestHeaders().getFirst("X-Custodian-Signature");
            String path = exchange.getRequestURI().toString();
            String expected = CustodianBankHostToHostClient.sign(ts, method, path, body, secret);
            if (!expected.equals(sig)) {
                byte[] err = "{\"error\":\"bad_signature\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(401, err.length);
                exchange.getResponseBody().write(err);
                exchange.close();
                return;
            }
            if ("POST".equals(method)) {
                lastDisbursementKey.set(exchange.getRequestHeaders().getFirst("X-Idempotency-Key"));
                int code = disbursementHttpStatus.get();
                String resp = code == 503
                        ? "{\"message\":\"gateway_timeout\"}"
                        : "{\"status\":\"%s\",\"bankReference\":\"GCB-REF-991\",\"message\":\"ok\"}"
                                .formatted(disbursementStatusBody.get());
                byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(code, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            } else {
                String resp = "{\"status\":\"SETTLED\",\"bankReference\":\"GCB-REF-991\",\"message\":\"settled\"}";
                byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            }
        });
        server.start();

        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        var client = new CustodianBankHostToHostClient(json, base, creds, HttpClient.newHttpClient());

        // 1. Live trust balance sync updates CustodianBankBackstopService and satisfies safeguarding
        var syncService = new CustodianTrustBalanceSyncService(backstop, client, alerts, null, false);
        var syncResult = syncService.syncPrimaryTrustBalance("ops:sync-test").orElseThrow();
        assertThat(syncResult.snapshot().availableBalancePesewas()).isEqualTo(reportedLiveBalance);
        assertThat(syncResult.snapshot().bankStatementReference()).isEqualTo("STMT-2026-001");
        assertThat(syncResult.safeguarding().safeguardingSatisfied()).isTrue();
        assertThat(backstop.getPrimaryConfig().lastReconciledBankBalancePesewas()).isEqualTo(reportedLiveBalance);

        // 2. Signed idempotent disbursement succeeds
        var instruction = new CustodianBankGateway.DisbursementInstruction(
                "idem-" + UUID.randomUUID(),
                CustodianBankBackstopService.PRIMARY_TRUST_CODE,
                15_000L,
                Ledger.GHS,
                "bank",
                "GCB",
                "102030405060",
                "ELEPAY Operating Ltd",
                "revenue_sweep",
                "Earned revenue sweep"
        );
        var settled = client.initiateDisbursement(instruction);
        assertThat(settled.status()).isEqualTo(CustodianBankGateway.DisbursementStatus.SETTLED);
        assertThat(settled.bankReference()).isEqualTo("GCB-REF-991");
        assertThat(lastDisbursementKey.get()).isEqualTo(instruction.idempotencyKey());

        // 3. HTTP 503 fails closed to UNKNOWN (never REJECTED) and queryDisbursement resolves it
        disbursementHttpStatus.set(503);
        var ambiguous = client.initiateDisbursement(instruction);
        assertThat(ambiguous.status()).isEqualTo(CustodianBankGateway.DisbursementStatus.UNKNOWN);

        var queried = client.queryDisbursement(instruction.idempotencyKey(), "GCB-REF-991");
        assertThat(queried.status()).isEqualTo(CustodianBankGateway.DisbursementStatus.SETTLED);

        // 4. HTTP 422 returns REJECTED
        disbursementHttpStatus.set(422);
        disbursementStatusBody.set("REJECTED");
        var rejected = client.initiateDisbursement(instruction);
        assertThat(rejected.status()).isEqualTo(CustodianBankGateway.DisbursementStatus.REJECTED);

        // 5. Verify ledger stays balanced
        assertThat(ledger.integrity().ok()).isTrue();
    }
}
