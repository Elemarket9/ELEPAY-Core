package com.elepay.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, ApiKeys apiKeys, RequestSigningFilter requestSigningFilter) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(cors -> {})
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // The legacy sandbox only runs under the sandbox profile and binds to loopback.
                        .requestMatchers("/sandbox/**").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/health", "/actuator/info").permitAll()
                        // In production the management endpoints listen on a separate internal port.
                        .requestMatchers("/actuator/prometheus").permitAll()
                        .requestMatchers("/v1/public/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/v3/api-docs", "/v3/api-docs/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/rails/*/callbacks/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/fx/partners/*/callbacks", "/v1/fx/partners/*/inbound").permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/ussd/**").permitAll()
                        .requestMatchers("/dev/token").permitAll()
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/assets/**", "/pay/**", "/favicon.svg",
                                "/merchant", "/merchant/**", "/ops", "/ops/**", "/app", "/app/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(new RolesConverter())))
                .addFilterBefore(requestSigningFilter, BearerTokenAuthenticationFilter.class)
                .addFilterBefore(new ApiKeyFilter(apiKeys), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /** Local development and tests: HS256 tokens signed with a shared secret. */
    @Bean
    @ConditionalOnProperty("elepay.security.dev-jwt-secret")
    JwtDecoder devJwtDecoder(org.springframework.core.env.Environment env) {
        byte[] secret = env.getRequiredProperty("elepay.security.dev-jwt-secret").getBytes(StandardCharsets.UTF_8);
        return NimbusJwtDecoder.withSecretKey(new SecretKeySpec(secret, "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256).build();
    }

    /** Maps the OIDC {@code roles} claim (and realm roles from Keycloak) to authorities. */
    static final class RolesConverter implements Converter<Jwt, AbstractAuthenticationToken> {
        @Override
        public AbstractAuthenticationToken convert(Jwt jwt) {
            Collection<GrantedAuthority> authorities = new ArrayList<>();
            List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles == null && jwt.getClaim("realm_access") instanceof java.util.Map<?, ?> realm
                    && realm.get("roles") instanceof List<?> list) {
                roles = list.stream().map(String::valueOf).toList();
            }
            if (roles != null) roles.forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
            return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
        }
    }

    static final class ApiKeyFilter extends OncePerRequestFilter {
        private final ApiKeys keys;

        ApiKeyFilter(ApiKeys keys) {
            this.keys = keys;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String header = request.getHeader("X-Elepay-Api-Key");
            if (header != null) {
                var auth = keys.authenticate(header);
                if (auth.isEmpty()) {
                    response.sendError(401, "Invalid API key");
                    return;
                }
                SecurityContextHolder.getContext().setAuthentication(auth.get());
            }
            chain.doFilter(request, response);
        }
    }
}
