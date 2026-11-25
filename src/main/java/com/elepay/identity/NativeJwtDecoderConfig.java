package com.elepay.identity;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Configures {@link JwtDecoder} so Bearer tokens issued by {@link NativeAuthService}
 * (HS256) and/or an external OIDC provider (RS256/ES256 via {@code issuer-uri})
 * both authenticate seamlessly in production and local runs.
 */
@Configuration
class NativeJwtDecoderConfig {

    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    JwtDecoder jwtDecoder(
            Environment env,
            @Value("${elepay.auth.jwt-signing-secret:}") String authSecret,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuerUri) {
        JwtDecoder hs256Decoder = buildHs256Decoder(env, authSecret);
        JwtDecoder oidcDecoder = (issuerUri != null && !issuerUri.isBlank())
                ? JwtDecoders.fromIssuerLocation(issuerUri.trim())
                : null;

        if (hs256Decoder != null && oidcDecoder != null) {
            return token -> {
                if (isHs256(token)) {
                    return hs256Decoder.decode(token);
                }
                return oidcDecoder.decode(token);
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

    private static JwtDecoder buildHs256Decoder(Environment env, String authSecret) {
        String effective = (authSecret != null && !authSecret.isBlank()) ? authSecret.trim() : "";
        boolean isProd = env != null && env.acceptsProfiles(Profiles.of("prod", "production"))
                && !env.acceptsProfiles(Profiles.of("test", "dev", "sandbox"));
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
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withSecretKey(new SecretKeySpec(keyBytes, "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefault());
        decoder.setJwtValidator(validator);
        return decoder;
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
