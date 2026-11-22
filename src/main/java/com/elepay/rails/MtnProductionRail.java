package com.elepay.rails;

import com.elepay.common.References;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Direct MTN Ghana production collection and disbursement adapter. No aggregator, sandbox
 * endpoints, synthetic results, or local approval shortcuts. Partner status is polled over TLS.
 * Enable only after the Ghana account manager confirms the production endpoint and credentials.
 * Reference: MTN production API portal, https://momoapi.mtn.com/API-collections.
 */
@Component
@ConditionalOnProperty(name = "elepay.rails.mtn.enabled", havingValue = "true")
public class MtnProductionRail implements PaymentRail {
    private record Credentials(String user, String key, String subscription) {
        Credentials {
            if (user == null || user.isBlank() || key == null || key.isBlank()
                    || subscription == null || subscription.isBlank())
                throw new IllegalStateException("MTN production credentials must be configured");
        }
    }
    private record Response(int status, String body) {}
    private final URI base;
    private final Credentials collection;
    private final Credentials disbursement;
    private final ObjectMapper json;
    private final NetworkHealthDetector health;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public MtnProductionRail(ObjectMapper json, NetworkHealthDetector health,
            @Value("${elepay.rails.mtn.base-url:}") String baseUrl,
            @Value("${elepay.rails.mtn.collection-user:}") String collectionUser,
            @Value("${elepay.rails.mtn.collection-key:}") String collectionKey,
            @Value("${elepay.rails.mtn.collection-subscription:}") String collectionSubscription,
            @Value("${elepay.rails.mtn.disbursement-user:}") String disbursementUser,
            @Value("${elepay.rails.mtn.disbursement-key:}") String disbursementKey,
            @Value("${elepay.rails.mtn.disbursement-subscription:}") String disbursementSubscription) {
        this.base = productionBase(baseUrl);
        this.collection = new Credentials(collectionUser, collectionKey, collectionSubscription);
        this.disbursement = new Credentials(disbursementUser, disbursementKey, disbursementSubscription);
        this.json = json;
        this.health = health;
    }

    static URI productionBase(String url) {
        URI u = URI.create(url);
        if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null
                || !Set.of("proxy.momoapi.mtn.com","momoapi.momo.africa").contains(u.getHost().toLowerCase(java.util.Locale.ROOT))
                || u.getUserInfo() != null || u.getQuery() != null || u.getFragment() != null
                || (u.getPort() != -1 && u.getPort() != 443)
                || !(u.getPath().isEmpty() || u.getPath().equals("/")))
            throw new IllegalStateException("Use the MTN-confirmed production HTTPS API origin");
        return u;
    }

    @Override public String name() { return "mtn"; }

    @Override public boolean supports(String direction, String channel, String network) {
        return Set.of("charge","payout").contains(direction) && "momo".equalsIgnoreCase(channel)
                && "mtn".equalsIgnoreCase(network);
    }

    // Stable provider UUID for every ELEPAY reference: remains identical across crashes and retries.
    static UUID providerId(String direction, String reference) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(
                    ("elepay:mtn:ghana:v1:" + direction + ":" + reference).getBytes(StandardCharsets.UTF_8));
            bytes[6] = (byte)((bytes[6] & 0x0f) | 0x40);
            bytes[8] = (byte)((bytes[8] & 0x3f) | 0x80);
            ByteBuffer b = ByteBuffer.wrap(bytes);
            return new UUID(b.getLong(), b.getLong());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    @Override public Result charge(Charge request) {
        if (!supports("charge", request.channel(), request.network()))
            return new Result(Status.FAILED, null, request.amount(), request.currency(), null, null, "Unsupported destination");
        return submit("charge", request.reference(), request.amount(), request.currency(), request.msisdn());
    }

    @Override public Result payout(Payout request) {
        if (!supports("payout", request.type(), request.network()))
            return new Result(Status.FAILED, null, request.amount(), request.currency(), null, null, "Unsupported destination");
        return submit("payout", request.reference(), request.amount(), request.currency(), request.accountNumber());
    }

    @Override public Result refund(Refund request) {
        // Refund product contract must be confirmed separately; never substitute an unrelated transfer.
        return Result.unknown("MTN refund operation not configured");
    }

    private Result submit(String direction, String ref, long amount, String currency, String phone) {
        if (amount <= 0 || !"GHS".equals(currency))
            return new Result(Status.FAILED, null, amount, currency, null, null, "Invalid Ghana currency or amount");
        long start = System.currentTimeMillis();
        try {
            String msisdn = References.msisdn(phone);
            String product = product(direction);
            Credentials c = credentials(direction);
            String token = token(product, c);
            String id = providerId(direction, ref).toString();
            Map<String, Object> body = Map.of(
                    "amount", BigDecimal.valueOf(amount, 2).toPlainString(), "currency", currency,
                    "externalId", ref, direction.equals("charge") ? "payer" : "payee",
                    Map.of("partyIdType","MSISDN","partyId",msisdn),
                    "payerMessage", "ELEPAY " + ref, "payeeNote", "ELEPAY " + ref);
            Response response = send(request(path(direction))
                    .header("Authorization","Bearer " + token).header("Ocp-Apim-Subscription-Key",c.subscription())
                    .header("X-Reference-Id", id).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build());
            health.record(name(), response.status() == 202, false, System.currentTimeMillis()-start);
            if (response.status() == 202)
                return new Result(Status.PENDING,id,amount,currency,null,null,"MTN accepted the request; awaiting verified status");
            // Duplicate, unexpected, auth, or server response: preserve uncertainty and query the same reference.
            return Result.unknown("MTN submission outcome requires status verification");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.unknown("MTN request interrupted");
        } catch (Exception e) {
            health.record(name(), false, e instanceof java.net.http.HttpTimeoutException, System.currentTimeMillis()-start);
            return Result.unknown("MTN network or authentication unavailable");
        }
    }

    @Override public Result query(String direction, String reference, String railReference) {
        if (!Set.of("charge","payout").contains(direction)) return Result.unknown("Unsupported MTN query");
        long start = System.currentTimeMillis();
        try {
            String product = product(direction);
            Credentials c = credentials(direction);
            String token = token(product,c);
            Response response = send(request(path(direction) + "/" + providerId(direction, reference))
                    .header("Authorization","Bearer "+token)
                    .header("Ocp-Apim-Subscription-Key",c.subscription()).GET().build());
            health.record(name(), response.status()==200 || response.status()==404, false, System.currentTimeMillis()-start);
            if (response.status()==404) return new Result(Status.NOT_FOUND,null,null,null,null,null,"MTN reference not found");
            if (response.status()!=200) return Result.unknown("MTN status unavailable");
            return parseStatus(json.readTree(response.body()),reference);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.unknown("MTN status request interrupted");
        } catch (Exception e) {
            health.record(name(),false,e instanceof java.net.http.HttpTimeoutException,System.currentTimeMillis()-start);
            return Result.unknown("MTN status unavailable");
        }
    }

    static Result parseStatus(JsonNode node, String reference) {
        if (!reference.equals(node.path("externalId").asString("")))
            return Result.unknown("MTN external reference mismatch");
        String status = node.path("status").asString("");
        if ("PENDING".equals(status)) return new Result(Status.PENDING,null,null,null,null,null,"MTN payment pending");
        if ("FAILED".equals(status)) return new Result(Status.FAILED,null,null,null,null,null,"MTN payment failed");
        if (!"SUCCESSFUL".equals(status)) return Result.unknown("Unknown MTN status");
        try {
            long amount = new BigDecimal(node.path("amount").asString("")).movePointRight(2).longValueExact();
            String currency = node.path("currency").asString("");
            String financialId = node.path("financialTransactionId").asString("");
            if (amount <= 0 || !"GHS".equals(currency) || financialId.isBlank())
                return Result.unknown("MTN success lacks financial evidence");
            return new Result(Status.SUCCEEDED,financialId,amount,currency,null,null,"MTN authenticated status confirmed");
        } catch (RuntimeException e) { return Result.unknown("MTN amount evidence malformed"); }
    }

    private String product(String direction) { return direction.equals("charge") ? "collection" : "disbursement"; }
    private Credentials credentials(String direction) { return direction.equals("charge") ? collection : disbursement; }
    private String path(String direction) { return direction.equals("charge") ? "/collection/v1_0/requesttopay" : "/disbursement/v1_0/transfer"; }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(15))
                .header("Accept","application/json").header("X-Target-Environment","mtnghana");
    }

    private String token(String product, Credentials c) throws Exception {
        String basic=Base64.getEncoder().encodeToString((c.user()+":"+c.key()).getBytes(StandardCharsets.UTF_8));
        Response response=send(request("/"+product+"/token/")
                .header("Authorization","Basic "+basic).header("Ocp-Apim-Subscription-Key",c.subscription())
                .POST(HttpRequest.BodyPublishers.noBody()).build());
        if (response.status()!=200) throw new IllegalStateException("MTN authentication rejected");
        String token=json.readTree(response.body()).path("access_token").asString("");
        if (token.isBlank()) throw new IllegalStateException("MTN token missing");
        return token;
    }

    private Response send(HttpRequest request) throws Exception {
        HttpResponse<InputStream> response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in=response.body()) {
            byte[] body=in.readNBytes(65537);
            if (body.length>65536) throw new IllegalStateException("MTN response too large");
            return new Response(response.statusCode(),new String(body,StandardCharsets.UTF_8));
        }
    }

    @Override public boolean callbacksAreAuthoritative() { return false; }
    @Override public Callback verifyCallback(Map<String,String> headers,String body) {
        // No callback authentication contract has been onboarded. Polling alone determines state.
        throw new InvalidCallbackException("MTN callback authentication not configured; use status polling");
    }
}
