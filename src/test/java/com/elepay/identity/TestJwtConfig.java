package com.elepay.identity;

import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration
@Profile({"test", "sandbox", "dev"})
class TestJwtConfig {
    /** Local development and tests: HS256 tokens signed with a shared secret. */
    @Bean
    @ConditionalOnProperty("elepay.security.dev-jwt-secret")
    JwtDecoder devJwtDecoder(org.springframework.core.env.Environment env) {
        byte[] secret = env.getRequiredProperty("elepay.security.dev-jwt-secret").getBytes(StandardCharsets.UTF_8);
        return NimbusJwtDecoder.withSecretKey(new SecretKeySpec(secret, "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256).build();
    }

}
