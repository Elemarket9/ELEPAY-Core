package com.elepay.enterprise.wallet;

import com.elepay.common.HmacVerification;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enterprise partner token lifecycle management, OAuth2 client credentials authentication,
 * and OIDC delegated authorization service.
 */
@Service
public class EnterpriseTokenService {

    // Registered partner credentials: partnerId -> credentials
    private final Map<String, PartnerAuthCredentials> partnerRegistry = new ConcurrentHashMap<>();
    // Lookup by clientId: clientId -> partnerId
    private final Map<String, String> clientIdToPartnerId = new ConcurrentHashMap<>();

    // Active partner access tokens: accessToken -> PartnerAuthResult
    private final Map<String, IssuedAccessToken> accessTokens = new ConcurrentHashMap<>();

    // Active delegated tokens: tokenId -> DelegatedToken
    private final Map<String, DelegatedToken> delegatedTokens = new ConcurrentHashMap<>();

    // Revoked tokens blacklist
    private final Set<String> revokedTokenIds = ConcurrentHashMap.newKeySet();

    private record IssuedAccessToken(
            String token,
            String partnerId,
            Set<PartnerScope> scopes,
            Instant issuedAt,
            Instant expiresAt
    ) {
        public boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    /**
     * Registers an enterprise partner credentials profile.
     */
    public void registerPartner(PartnerAuthCredentials credentials) {
        Objects.requireNonNull(credentials, "credentials cannot be null");
        partnerRegistry.put(credentials.partnerId(), credentials);
        clientIdToPartnerId.put(credentials.clientId(), credentials.partnerId());
    }

    public Optional<PartnerAuthCredentials> getPartnerCredentials(String partnerId) {
        return Optional.ofNullable(partnerRegistry.get(partnerId));
    }

    /**
     * Performs zero-downtime key rotation for a partner.
     */
    public PartnerAuthCredentials rotatePartnerKey(String partnerId, String newSigningKey) {
        PartnerAuthCredentials current = partnerRegistry.get(partnerId);
        if (current == null) {
            throw new EnterpriseWalletException(
                    EnterpriseWalletException.ErrorCode.INVALID_CREDENTIALS,
                    "Partner not found: " + partnerId,
                    404
            );
        }
        PartnerAuthCredentials updated = current.withRotatedKey(newSigningKey);
        partnerRegistry.put(partnerId, updated);
        return updated;
    }

    /**
     * Authenticates partner using OAuth2 Client Credentials grant.
     */
    public PartnerAuthResult authenticateClientCredentials(PartnerAuthRequest request) {
        if (request == null || request.clientId() == null || request.clientSecret() == null) {
            return PartnerAuthResult.failure("Missing client credentials");
        }

        String partnerId = clientIdToPartnerId.get(request.clientId());
        if (partnerId == null) {
            return PartnerAuthResult.failure("Invalid client ID");
        }

        PartnerAuthCredentials creds = partnerRegistry.get(partnerId);
        if (creds == null || !creds.active()) {
            return PartnerAuthResult.failure("Partner account inactive or not found");
        }

        // Constant-time client secret verification
        if (!MessageDigest.isEqual(
                creds.clientSecret().getBytes(StandardCharsets.UTF_8),
                request.clientSecret().getBytes(StandardCharsets.UTF_8))) {
            return PartnerAuthResult.failure("Invalid client secret");
        }

        // Validate requested scopes against partner allowed scopes
        Set<PartnerScope> grantedScopes = new HashSet<>();
        if (request.requestedScopes() == null || request.requestedScopes().isEmpty()) {
            grantedScopes.addAll(creds.allowedScopes());
        } else {
            for (PartnerScope scope : request.requestedScopes()) {
                if (!creds.allowedScopes().contains(scope)) {
                    return PartnerAuthResult.failure("Scope not authorized: " + scope);
                }
                grantedScopes.add(scope);
            }
        }

        String token = "el_tok_" + UUID.randomUUID().toString().replace("-", "");
        long ttlSeconds = 3600L; // 1 hour
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(ttlSeconds);

        IssuedAccessToken issued = new IssuedAccessToken(token, partnerId, Collections.unmodifiableSet(grantedScopes), now, expiresAt);
        accessTokens.put(token, issued);

        return PartnerAuthResult.success(partnerId, token, ttlSeconds, grantedScopes);
    }

    /**
     * Issues an OAuth2/OIDC delegated authorization token binding customer and authorized amount.
     */
    public DelegatedToken issueDelegatedToken(DelegatedTokenRequest request) {
        PartnerAuthCredentials creds = partnerRegistry.get(request.partnerId());
        if (creds == null || !creds.active()) {
            throw new EnterpriseWalletException(
                    EnterpriseWalletException.ErrorCode.INVALID_CREDENTIALS,
                    "Invalid or inactive partner: " + request.partnerId(),
                    401
            );
        }

        // Verify scopes are subset of partner allowed scopes
        for (PartnerScope scope : request.scopes()) {
            if (!creds.allowedScopes().contains(scope)) {
                throw new EnterpriseWalletException(
                        EnterpriseWalletException.ErrorCode.INSUFFICIENT_SCOPE,
                        "Scope not permitted for partner: " + scope,
                        403
                );
            }
        }

        String tokenId = "del_" + UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(request.ttlSeconds());

        // Canonical string for signature: tokenId|partnerId|customerId|externalCustomerId|amount|currency|expiryEpoch
        String canonical = buildCanonicalDelegationPayload(tokenId, request.partnerId(), request.customerId(),
                request.externalCustomerId(), request.maxAuthorizedMinorUnits(), request.currency(), expiresAt.getEpochSecond());

        String signature = HmacVerification.computeHex(creds.activeSigningKey(), canonical);

        DelegatedToken token = new DelegatedToken(
                tokenId,
                request.partnerId(),
                request.customerId(),
                request.externalCustomerId(),
                request.maxAuthorizedMinorUnits(),
                request.currency(),
                request.scopes(),
                now,
                expiresAt,
                signature
        );

        delegatedTokens.put(tokenId, token);
        return token;
    }

    /**
     * Validates a delegated token against caller requirements.
     */
    public DelegatedToken validateDelegatedToken(
            String tokenId,
            PartnerScope requiredScope,
            long requestedAmountMinorUnits,
            String currency
    ) {
        if (tokenId == null || tokenId.isBlank()) {
            throw new EnterpriseWalletException(
                    EnterpriseWalletException.ErrorCode.INVALID_CREDENTIALS,
                    "Delegated token ID is required",
                    401
            );
        }

        if (revokedTokenIds.contains(tokenId)) {
            throw new EnterpriseWalletException(
                    EnterpriseWalletException.ErrorCode.TOKEN_REVOKED,
                    "Delegated token has been revoked: " + tokenId,
                    401
            );
        }

        DelegatedToken token = delegatedTokens.get(tokenId);
        if (token == null) {
            throw new EnterpriseWalletException(
                    EnterpriseWalletException.ErrorCode.INVALID_CREDENTIALS,
                    "Delegated token not found: " + tokenId,
                    401
            );
        }

        if (token.isExpired()) {
            throw new EnterpriseWalletException(
                    EnterpriseWalletException.ErrorCode.TOKEN_EXPIRED,
                    "Delegated token has expired: " + tokenId,
                    401
            );
        }

        PartnerAuthCredentials creds = partnerRegistry.get(token.partnerId());
        if (creds == null || !creds.active()) {
            throw new EnterpriseWalletException(
                    EnterpriseWalletException.ErrorCode.INVALID_CREDENTIALS,
                    "Partner for token is invalid or inactive",
                    401
            );
        }

        // Verify cryptographic signature
        String canonical = buildCanonicalDelegationPayload(token.tokenId(), token.partnerId(), token.customerId(),
                token.externalCustomerId(), token.maxAuthorizedMinorUnits(), token.currency(), token.expiresAt().getEpochSecond());

        boolean validSignature = verifyHmacSignature(creds.activeSigningKey(), canonical, token.cryptographicSignature());
        if (!validSignature && creds.previousSigningKey() != null) {
            validSignature = verifyHmacSignature(creds.previousSigningKey(), canonical, token.cryptographicSignature());
        }

        if (!validSignature) {
            throw new EnterpriseWalletException(
                    EnterpriseWalletException.ErrorCode.INVALID_SIGNATURE,
                    "Cryptographic signature check failed on delegated token: " + tokenId,
                    401
            );
        }

        // Verify required scope
        if (requiredScope != null && !token.hasScope(requiredScope)) {
            throw new EnterpriseWalletException(
                    EnterpriseWalletException.ErrorCode.INSUFFICIENT_SCOPE,
                    "Delegated token lacks required scope: " + requiredScope,
                    403
            );
        }

        // Verify currency match
        if (currency != null && !currency.equalsIgnoreCase(token.currency())) {
            throw new EnterpriseWalletException(
                    EnterpriseWalletException.ErrorCode.UNSUPPORTED_OPERATION,
                    "Delegated token currency (" + token.currency() + ") does not match requested currency (" + currency + ")",
                    400
            );
        }

        // Verify spending authorization amount
        if (requestedAmountMinorUnits > token.maxAuthorizedMinorUnits()) {
            throw new EnterpriseWalletException(
                    EnterpriseWalletException.ErrorCode.LIMIT_EXCEEDED,
                    "Requested amount (" + requestedAmountMinorUnits + ") exceeds delegated limit (" + token.maxAuthorizedMinorUnits() + ")",
                    403
            );
        }

        return token;
    }

    /**
     * Revokes a token immediately.
     */
    public void revokeToken(String tokenId) {
        if (tokenId != null) {
            revokedTokenIds.add(tokenId);
            delegatedTokens.remove(tokenId);
            accessTokens.remove(tokenId);
        }
    }

    /**
     * Downscopes / exchanges a delegated token for a more restricted delegation token.
     */
    public DelegatedToken exchangeDelegatedToken(String originalTokenId, Set<PartnerScope> downscoped, long maxAmount) {
        DelegatedToken original = validateDelegatedToken(originalTokenId, null, 0, null);
        for (PartnerScope s : downscoped) {
            if (!original.hasScope(s)) {
                throw new EnterpriseWalletException(
                        EnterpriseWalletException.ErrorCode.INSUFFICIENT_SCOPE,
                        "Cannot grant scope not present in parent token: " + s,
                        403
                );
            }
        }
        long restrictedAmount = Math.min(maxAmount, original.maxAuthorizedMinorUnits());
        DelegatedTokenRequest exchangeRequest = new DelegatedTokenRequest(
                original.partnerId(),
                original.customerId(),
                original.externalCustomerId(),
                restrictedAmount,
                original.currency(),
                downscoped,
                original.expiresAt().getEpochSecond() - Instant.now().getEpochSecond()
        );
        return issueDelegatedToken(exchangeRequest);
    }

    private String buildCanonicalDelegationPayload(
            String tokenId,
            String partnerId,
            String customerId,
            String externalCustomerId,
            long amount,
            String currency,
            long expiryEpoch
    ) {
        return tokenId + "|" + partnerId + "|" + (customerId != null ? customerId : "") + "|"
                + (externalCustomerId != null ? externalCustomerId : "") + "|"
                + amount + "|" + currency + "|" + expiryEpoch;
    }

    private boolean verifyHmacSignature(String secret, String canonical, String providedHex) {
        if (secret == null || canonical == null || providedHex == null) return false;
        String expected = HmacVerification.computeHex(secret, canonical);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                providedHex.trim().toLowerCase().getBytes(StandardCharsets.UTF_8)
        );
    }
}
