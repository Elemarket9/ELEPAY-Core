package com.elepay.identity;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Configures {@link JwtDecoder} so Bearer tokens issued by {@link NativeAuthService}
 * (HS256) and/or an external OIDC provider (RS256/ES256 via {@code issuer-uri})
 * both authenticate seamlessly in production and local runs, while strictly enforcing
 * {@code aud}, {@code iss}, {@code jti}, and session revocation.
 */
@Configuration
class NativeJwtDecoderConfig {
    static final String DEFAULT_NATIVE_ISSUER = "elepay-native";
    static final String DEFAULT_NATIVE_AUDIENCE = "elepay-api";

    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    JwtDecoder jwtDecoder(
            Environment env,
            @Value("${elepay.auth.jwt-signing-secret:}") String authSecret,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuerUri,
            @Value("${spring.security.oauth2.resourceserver.jwt.audiences:}") List<String> oidcAudiences,
            @Value("${elepay.auth.jwt-issuer:" + DEFAULT_NATIVE_ISSUER + "}") String nativeIssuer,
            @Value("${elepay.auth.jwt-audience:" + DEFAULT_NATIVE_AUDIENCE + "}") String nativeAudience,
            @Autowired(required = false) DistributedSessionService sessionService) {
        boolean isProd = isProdProfile(env);
        List<String> cleanAudiences = cleanList(oidcAudiences);

        JwtDecoder hs256Decoder = buildHs256Decoder(env, authSecret, nativeIssuer, nativeAudience, sessionService);
        JwtDecoder oidcDecoder = null;
        if (issuerUri != null && !issuerUri.isBlank()) {
            String trimmedIssuer = issuerUri.trim();
            JwtDecoder raw = JwtDecoders.fromIssuerLocation(trimmedIssuer);
            if (raw instanceof NimbusJwtDecoder nimbus) {
                attachOidcValidators(nimbus, trimmedIssuer, cleanAudiences, isProd, sessionService);
            } else if (isProd && cleanAudiences.isEmpty()) {
                throw new IllegalStateException(
                        "spring.security.oauth2.resourceserver.jwt.audiences must be configured in production");
            }
            oidcDecoder = raw;
        }

        if (hs256Decoder != null && oidcDecoder != null) {
            JwtDecoder finalOidc = oidcDecoder;
            return token -> {
                if (isHs256(token)) {
                    return hs256Decoder.decode(token);
                }
                return finalOidc.decode(token);
            };
        }
        if (hs256Decoder != null) {
            return hs256Decoder;
        }
        if (oidcDecoder != null) {
            return oidcDecoder;
        }
        return token -> {
            throw new JwtException("No JWT decoder configured");
        };
    }

    static void attachOidcValidators(
            NimbusJwtDecoder decoder,
            String issuerUri,
            List<String> audiences,
            boolean isProd,
            DistributedSessionService sessionService) {
        List<String> cleanAudiences = cleanList(audiences);
        if (isProd && cleanAudiences.isEmpty()) {
            throw new IllegalStateException(
                    "spring.security.oauth2.resourceserver.jwt.audiences must be configured in production");
        }
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(JwtValidators.createDefaultWithIssuer(issuerUri));
        if (!cleanAudiences.isEmpty()) {
            validators.add(audienceValidator(cleanAudiences));
        }
        if (sessionService != null) {
            validators.add(revocationValidator(sessionService));
        }
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
    }

    static JwtDecoder buildHs256Decoder(
            Environment env,
            String authSecret,
            String nativeIssuer,
            String nativeAudience,
            DistributedSessionService sessionService) {
        String effective = (authSecret != null && !authSecret.isBlank()) ? authSecret.trim() : "";
        boolean isProd = isProdProfile(env);
        if (effective.isEmpty() && !isProd && env != null) {
            effective = env.getProperty("elepay.security.dev-jwt-secret", "").trim();
        }
        if (effective.isEmpty()) {
            return null;
        }
        byte[] keyBytes = effective.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException("elepay.auth.jwt-signing-secret must be at least 32 bytes");
        }
        String expectedIssuer = (nativeIssuer == null || nativeIssuer.isBlank()) ? DEFAULT_NATIVE_ISSUER : nativeIssuer.trim();
        String expectedAudience = (nativeAudience == null || nativeAudience.isBlank()) ? DEFAULT_NATIVE_AUDIENCE : nativeAudience.trim();

        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withSecretKey(new SecretKeySpec(keyBytes, "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(JwtValidators.createDefault());
        validators.add(new JwtClaimValidator<String>(JwtClaimNames.ISS, expectedIssuer::equals));
        validators.add(audienceValidator(List.of(expectedAudience)));
        validators.add(new JwtClaimValidator<String>(JwtClaimNames.JTI, jti -> jti != null && !jti.isBlank()));
        if (sessionService != null) {
            validators.add(revocationValidator(sessionService));
        }
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> audienceValidator(List<String> allowedAudiences) {
        return new JwtClaimValidator<List<String>>(
                JwtClaimNames.AUD,
                aud -> aud != null && aud.stream().anyMatch(allowedAudiences::contains));
    }

    static OAuth2TokenValidator<Jwt> revocationValidator(DistributedSessionService sessionService) {
        OAuth2Error revokedError = new OAuth2Error("invalid_token", "The token has been revoked", null);
        return jwt -> {
            if (sessionService.isTokenRevoked(jwt.getId(), jwt.getClaimAsString("uid"), jwt.getSubject(), jwt.getIssuedAt())) {
                return OAuth2TokenValidatorResult.failure(revokedError);
            }
            return OAuth2TokenValidatorResult.success();
        };
    }

    private static boolean isProdProfile(Environment env) {
        return env != null && env.acceptsProfiles(Profiles.of("prod", "production"))
                && !env.acceptsProfiles(Profiles.of("test", "dev", "sandbox"));
    }

    private static List<String> cleanList(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        return raw.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .toList();
    }

    private static boolean isHs256(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            return JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm());
        } catch (Exception e) {
            return false;
        }
    }
}
