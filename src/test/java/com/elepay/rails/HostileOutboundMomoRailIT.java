package com.elepay.rails;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Hostile-rail OUTBOUND resilience. Models the MTN MoMo contract (OAuth token, requesttopay -> 202,
 * status poll) with WireMock, then makes the rail MISBEHAVE: 500s, connection failures, slow and
 * empty / malformed bodies, and 202-then-never-settles. The invariant proven here is the one that
 * protects the ledger upstream in {@code RailTransactions}: a charge is credited ONLY on a
 * {@code SUCCEEDED} result carrying matching amount, currency and a non-blank provider reference.
 * Under every misbehaviour the real {@link MtnProductionRail} returns {@code PENDING} / {@code UNKNOWN}
 * / {@code FAILED} and NEVER a bare {@code SUCCEEDED} — so ELEPAY cannot double-spend, cannot mark a
 * charge paid without verified evidence, and leaves funds reserved for recovery-by-query.
 *
 * <p><strong>Seam used:</strong> the production rail's host-lock ({@code productionBase}) only guards
 * the Spring {@code @Value} constructor. The public direct-URI constructor
 * {@code MtnProductionRail(json, health, URI, Credentials, Credentials, HttpClient)} — the same one
 * the existing {@code MtnProductionRailTest} uses — lets us point the REAL production adapter at
 * WireMock without weakening production and without any change to main source. So item 3's gated
 * allowance is NOT needed here, and this test exercises the genuine outbound adapter end to end.
 */
@DisplayName("Hostile outbound MoMo rail resilience (no false success, no double-spend)")
class HostileOutboundMomoRailIT {

    private static final String TOKEN_OK = "{\"access_token\":\"wiremock-token\",\"expires_in\":3600}";
    private static final MtnProductionRail.Credentials COLL =
            new MtnProductionRail.Credentials("col-user", "col-key", "col-sub");
    private static final MtnProductionRail.Credentials DISB =
            new MtnProductionRail.Credentials("disb-user", "disb-key", "disb-sub");

    private final ObjectMapper json = new ObjectMapper();
    private NetworkHealthDetector health;
    private WireMockServer momo;

    @BeforeEach
    void setUp() {
        health = mock(NetworkHealthDetector.class);
        momo = new WireMockServer(wireMockConfig().dynamicPort());
        momo.start();
        momo.stubFor(post(urlPathEqualTo("/collection/token/")).willReturn(okJson(TOKEN_OK)));
        momo.stubFor(post(urlPathEqualTo("/disbursement/token/")).willReturn(okJson(TOKEN_OK)));
    }

    @AfterEach
    void tearDown() {
        if (momo != null) momo.stop();
    }

    private MtnProductionRail railAt(URI base) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        return new MtnProductionRail(json, health, base, COLL, DISB, client);
    }

    private MtnProductionRail rail() {
        return railAt(URI.create("http://127.0.0.1:" + momo.port()));
    }

    private static PaymentRail.Charge charge(String ref, long amount) {
        return new PaymentRail.Charge(ref, amount, "GHS", "momo", "0241234567", "mtn",
                "c@test.com", "Customer", "Resilience", null);
    }

    @Test
    @DisplayName("requesttopay 500 -> UNKNOWN (uncertainty preserved; funds stay reserved, never credited)")
    void serverErrorOnSubmitYieldsUnknown() {
        momo.stubFor(post(urlPathEqualTo("/collection/v1_0/requesttopay"))
                .willReturn(aResponse().withStatus(500).withBody("upstream exploded")));
        var result = rail().charge(charge("EPC-500", 5_000));
        assertThat(result.status()).isEqualTo(PaymentRail.Status.UNKNOWN);
    }

    @Test
    @DisplayName("requesttopay 400 -> FAILED (clean rejection, no reservation ambiguity)")
    void badRequestOnSubmitYieldsFailed() {
        momo.stubFor(post(urlPathEqualTo("/collection/v1_0/requesttopay"))
                .willReturn(aResponse().withStatus(400).withBody("{\"message\":\"bad payer\"}")));
        var result = rail().charge(charge("EPC-400", 5_000));
        assertThat(result.status()).isEqualTo(PaymentRail.Status.FAILED);
    }

    @Test
    @DisplayName("202 accepted then status stays PENDING forever (202-then-never-callback) -> never SUCCEEDED")
    void acceptedButNeverSettlesStaysPending() {
        momo.stubFor(post(urlPathEqualTo("/collection/v1_0/requesttopay"))
                .willReturn(aResponse().withStatus(202)));
        momo.stubFor(get(urlPathMatching("/collection/v1_0/requesttopay/.*"))
                .willReturn(okJson("{\"externalId\":\"EPC-202\",\"status\":\"PENDING\"}")));

        var submit = rail().charge(charge("EPC-202", 5_000));
        assertThat(submit.status()).as("202 is accepted-as-pending, not success").isEqualTo(PaymentRail.Status.PENDING);
        assertThat(submit.amount()).isEqualTo(5_000L);
        assertThat(submit.currency()).isEqualTo("GHS");

        var poll = rail().query("charge", "EPC-202", submit.railReference());
        assertThat(poll.status()).isEqualTo(PaymentRail.Status.PENDING);
    }

    @Test
    @DisplayName("Hostile rail claims SUCCESSFUL but omits financial evidence -> UNKNOWN (not trusted, not credited)")
    void successWithoutFinancialEvidenceYieldsUnknown() {
        momo.stubFor(post(urlPathEqualTo("/collection/v1_0/requesttopay"))
                .willReturn(aResponse().withStatus(202)));
        // Missing financialTransactionId -> the success is not authoritative.
        momo.stubFor(get(urlPathMatching("/collection/v1_0/requesttopay/.*"))
                .willReturn(okJson("{\"externalId\":\"EPC-NOEV\",\"status\":\"SUCCESSFUL\",\"amount\":\"50.00\",\"currency\":\"GHS\"}")));

        assertThat(rail().charge(charge("EPC-NOEV", 5_000)).status()).isEqualTo(PaymentRail.Status.PENDING);
        assertThat(rail().query("charge", "EPC-NOEV", null).status()).isEqualTo(PaymentRail.Status.UNKNOWN);
    }

    @Test
    @DisplayName("Status poll returns an empty / malformed body -> UNKNOWN, never a false success")
    void malformedStatusBodyYieldsUnknown() {
        momo.stubFor(post(urlPathEqualTo("/collection/v1_0/requesttopay"))
                .willReturn(aResponse().withStatus(202)));
        momo.stubFor(get(urlPathMatching("/collection/v1_0/requesttopay/.*"))
                .willReturn(aResponse().withStatus(200).withBody("")));

        assertThat(rail().charge(charge("EPC-EMPTY", 5_000)).status()).isEqualTo(PaymentRail.Status.PENDING);
        assertThat(rail().query("charge", "EPC-EMPTY", null).status()).isEqualTo(PaymentRail.Status.UNKNOWN);
    }

    @Test
    @DisplayName("A slow (but in-window) 202 is handled as PENDING, not a timeout error and not a success")
    void slowResponseIsPending() {
        momo.stubFor(post(urlPathEqualTo("/collection/v1_0/requesttopay"))
                .willReturn(aResponse().withStatus(202).withFixedDelay(750)));
        var result = rail().charge(charge("EPC-SLOW", 5_000));
        assertThat(result.status()).isEqualTo(PaymentRail.Status.PENDING);
    }

    @Test
    @DisplayName("A connection failure (dead endpoint) on submit -> UNKNOWN, never SUCCEEDED (no double-spend)")
    void connectionFailureYieldsUnknown() throws Exception {
        int deadPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            deadPort = socket.getLocalPort();
        } // closed: nothing is listening now
        var result = railAt(URI.create("http://127.0.0.1:" + deadPort)).charge(charge("EPC-DEAD", 5_000));
        assertThat(result.status()).isEqualTo(PaymentRail.Status.UNKNOWN);
    }

    @Test
    @DisplayName("The OAuth token endpoint failing (500) does not manufacture a success -> UNKNOWN")
    void tokenEndpointFailureYieldsUnknown() {
        momo.stubFor(post(urlPathEqualTo("/collection/token/"))
                .willReturn(aResponse().withStatus(500).withBody("auth down")));
        assertThat(rail().charge(charge("EPC-AUTH", 5_000)).status()).isEqualTo(PaymentRail.Status.UNKNOWN);
    }

    @Test
    @DisplayName("A genuine fully-evidenced SUCCESSFUL status is the ONLY path to SUCCEEDED (control)")
    void genuineSuccessIsTheOnlyCreditablePath() {
        momo.stubFor(post(urlPathEqualTo("/collection/v1_0/requesttopay"))
                .willReturn(aResponse().withStatus(202)));
        momo.stubFor(get(urlPathMatching("/collection/v1_0/requesttopay/.*"))
                .willReturn(okJson("{\"externalId\":\"EPC-GOOD\",\"status\":\"SUCCESSFUL\",\"amount\":\"50.00\","
                        + "\"currency\":\"GHS\",\"financialTransactionId\":\"FIN-OK-1\"}")));

        assertThat(rail().charge(charge("EPC-GOOD", 5_000)).status()).isEqualTo(PaymentRail.Status.PENDING);
        var ok = rail().query("charge", "EPC-GOOD", null);
        assertThat(ok.status()).isEqualTo(PaymentRail.Status.SUCCEEDED);
        assertThat(ok.amount()).isEqualTo(5_000L);
        assertThat(ok.currency()).isEqualTo("GHS");
        assertThat(ok.railReference()).isEqualTo("FIN-OK-1");
    }
}
