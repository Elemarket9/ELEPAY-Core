package com.elepay.security;

import com.elepay.common.ApiException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * JWKS caching and rollover engine (Algorithm 078).
 * Manages OIDC public keys, bounded TTL caching, anti-stampede refresh limits,
 * asynchronous key rollover, and JWT signature verification.
 */
public class JwksService {

    public record JwkKey(
            String kid,
            String kty,
            String alg,
            String use,
            PublicKey publicKey,
            Instant cachedAt,
            Instant expiresAt,
            boolean activeForSigning
    ) {}

    private final ObjectMapper json;
    private final Map<String, JwkKey> keyCache = new ConcurrentHashMap<>();
    private final int maxCacheSize;
    private final Duration cacheTtl;
    private final Duration minRefreshInterval;
    private volatile Instant lastRefreshAttempt = Instant.EPOCH;

    public JwksService(ObjectMapper json) {
        this(json, 100, Duration.ofHours(1), Duration.ofSeconds(10));
    }

    public JwksService(ObjectMapper json, int maxCacheSize, Duration cacheTtl, Duration minRefreshInterval) {
        this.json = json != null ? json : new ObjectMapper();
        this.maxCacheSize = maxCacheSize;
        this.cacheTtl = cacheTtl;
        this.minRefreshInterval = minRefreshInterval;
    }

    public void registerKey(String kid, String alg, String use, PublicKey publicKey, boolean activeForSigning) {
        evictExpiredKeysIfFull();
        Instant now = Instant.now();
        JwkKey key = new JwkKey(kid, "RSA", alg != null ? alg : "RS256", use != null ? use : "sig", publicKey, now, now.plus(cacheTtl), activeForSigning);
        keyCache.put(kid, key);
    }

    public void parseAndCacheJwks(String jwksJson) {
        try {
            JsonNode root = json.readTree(jwksJson);
            JsonNode keysNode = root.get("keys");
            if (keysNode == null || !keysNode.isArray()) {
                throw ApiException.badRequest("invalid_jwks", "JWKS must contain a 'keys' array");
            }
            Instant now = Instant.now();
            lastRefreshAttempt = now;

            for (JsonNode keyNode : keysNode) {
                String kty = keyNode.path("kty").asText("RSA");
                String kid = keyNode.path("kid").asText();
                String alg = keyNode.path("alg").asText("RS256");
                String use = keyNode.path("use").asText("sig");
                String n = keyNode.path("n").asText();
                String e = keyNode.path("e").asText();

                if (!kid.isEmpty() && "RSA".equalsIgnoreCase(kty) && !n.isEmpty() && !e.isEmpty()) {
                    PublicKey pubKey = constructRsaPublicKey(n, e);
                    evictExpiredKeysIfFull();
                    keyCache.put(kid, new JwkKey(kid, kty, alg, use, pubKey, now, now.plus(cacheTtl), true));
                }
            }
        } catch (ApiException ae) {
            throw ae;
        } catch (Exception ex) {
            throw ApiException.badRequest("invalid_jwks", "Failed to parse JWKS: " + ex.getMessage());
        }
    }

    public Optional<JwkKey> getKey(String kid) {
        JwkKey key = keyCache.get(kid);
        if (key != null) {
            if (Instant.now().isAfter(key.expiresAt())) {
                keyCache.remove(kid);
                return Optional.empty();
            }
            return Optional.of(key);
        }
        return Optional.empty();
    }

    public boolean canRefreshKeys() {
        return Duration.between(lastRefreshAttempt, Instant.now()).compareTo(minRefreshInterval) >= 0;
    }

    public synchronized void rolloverKey(String retiredKid, String newKid, PublicKey newPublicKey) {
        Instant now = Instant.now();
        // 1. Add new key and make it primary
        keyCache.put(newKid, new JwkKey(newKid, "RSA", "RS256", "sig", newPublicKey, now, now.plus(cacheTtl), true));

        // 2. Mark retired key as no longer active for new signing, but keep it for verification during rollover grace period
        JwkKey oldKey = keyCache.get(retiredKid);
        if (oldKey != null) {
            keyCache.put(retiredKid, new JwkKey(oldKey.kid(), oldKey.kty(), oldKey.alg(), oldKey.use(), oldKey.publicKey(), oldKey.cachedAt(), now.plus(Duration.ofMinutes(30)), false));
        }
    }

    public boolean verifyJwtSignature(String token, String expectedIssuer, String expectedAudience) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return false;
            }
            String headerJson = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            byte[] signatureBytes = Base64.getUrlDecoder().decode(parts[2]);

            JsonNode header = json.readTree(headerJson);
            String kid = header.path("kid").asText();
            String alg = header.path("alg").asText("RS256");

            if (!"RS256".equalsIgnoreCase(alg)) {
                return false;
            }

            Optional<JwkKey> keyOpt = getKey(kid);
            if (keyOpt.isEmpty()) {
                return false;
            }

            // Verify claims
            JsonNode payload = json.readTree(payloadJson);
            if (expectedIssuer != null && !expectedIssuer.equals(payload.path("iss").asText())) {
                return false;
            }
            if (expectedAudience != null && !expectedAudience.equals(payload.path("aud").asText())) {
                return false;
            }
            long exp = payload.path("exp").asLong(0);
            if (exp > 0 && Instant.ofEpochSecond(exp).isBefore(Instant.now())) {
                return false; // Expired
            }

            // Verify RSA signature
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(keyOpt.get().publicKey());
            byte[] signedContent = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
            verifier.update(signedContent);
            return verifier.verify(signatureBytes);
        } catch (Exception e) {
            return false;
        }
    }

    private PublicKey constructRsaPublicKey(String base64UrlModulus, String base64UrlExponent) throws Exception {
        byte[] modBytes = Base64.getUrlDecoder().decode(base64UrlModulus);
        byte[] expBytes = Base64.getUrlDecoder().decode(base64UrlExponent);
        BigInteger modulus = new BigInteger(1, modBytes);
        BigInteger exponent = new BigInteger(1, expBytes);
        RSAPublicKeySpec spec = new RSAPublicKeySpec(modulus, exponent);
        return KeyFactory.getInstance("RSA").generatePublic(spec);
    }

    private void evictExpiredKeysIfFull() {
        if (keyCache.size() >= maxCacheSize) {
            Instant now = Instant.now();
            keyCache.entrySet().removeIf(e -> now.isAfter(e.getValue().expiresAt()));
            if (keyCache.size() >= maxCacheSize) {
                // If still full, remove oldest entry
                keyCache.keySet().stream().findFirst().ifPresent(keyCache::remove);
            }
        }
    }
}
