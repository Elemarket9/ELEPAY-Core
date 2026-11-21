package com.elepay.identity;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Issues HS256 tokens for local development and automated journeys only.
 * Never active in production: requires the dev or test profile and a dev secret.
 */
@RestController
@Profile({"dev", "test"})
@ConditionalOnProperty("elepay.security.dev-jwt-secret")
class DevTokens {
    private final byte[] secret;

    DevTokens(Environment env) {
        this.secret = env.getRequiredProperty("elepay.security.dev-jwt-secret").getBytes(StandardCharsets.UTF_8);
    }

    record TokenRequest(String subject, List<String> roles) {}

    @PostMapping("/dev/token")
    Map<String, String> issue(@RequestBody TokenRequest request) throws Exception {
        return Map.of("accessToken", token(secret, request.subject(), request.roles() == null ? List.of() : request.roles()));
    }

    public static String token(byte[] secret, String subject, List<String> roles) throws Exception {
        var claims = new JWTClaimsSet.Builder().subject(subject).claim("roles", roles)
                .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(3600))).build();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(secret));
        return jwt.serialize();
    }
}
