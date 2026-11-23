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
import org.springframework.beans.factory.annotation.Value;
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
    org.springframework.security.web.firewall.HttpFirewall httpFirewall() {
        var firewall = new org.springframework.security.web.firewall.StrictHttpFirewall();
        firewall.setAllowUrlEncodedPercent(false);
        firewall.setAllowUrlEncodedSlash(false);
        firewall.setAllowBackSlash(false);
        firewall.setAllowSemicolon(false);
        firewall.setAllowUrlEncodedPeriod(false);
        return firewall;
    }

    @Bean
    SecurityFilterChain api(HttpSecurity http, ApiKeys apiKeys, RequestSigningFilter requestSigningFilter,
                            RateLimitFilter rateLimitFilter, Stage1KillSwitchFilter stage1KillSwitchFilter,
                            @org.springframework.beans.factory.annotation.Value("${management.server.port:0}") int managementPort,
                            @Value("${elepay.security.trusted-proxies:}") String trustedProxies,
                            @Value("${elepay.security.pos-lan-origin:}") String posLanOrigin)
            throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(cors -> {})
                // OWASP secure headers: strict CSP for the web app, HSTS, no framing, no referrer leakage.
                .headers(h -> h
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                                + "img-src 'self' data:; connect-src 'self'" + checkedLanOrigin(posLanOrigin)
                                + "; font-src 'self'; object-src 'none'; "
                                + "base-uri 'self'; form-action 'self'; frame-ancestors 'none'"))
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                        .frameOptions(f -> f.deny())
                        .referrerPolicy(r -> r.policy(org.springframework.security.web.header.writers
                                .ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .addHeaderWriter(new org.springframework.security.web.header.writers.StaticHeadersWriter(
                                "Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(self)")))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Terminal pairing and simulated 3DS remain quarantined. Offline
                        // cash-only POS replay is authenticated and merchant-scoped in PosController.
                        .requestMatchers("/v1/pos/terminals/pair", "/v1/rails/card/3ds/**").denyAll()
                        // The legacy sandbox only runs under the sandbox profile and binds to loopback.
                        .requestMatchers("/sandbox/**").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/health", "/actuator/info").permitAll()
                        // Metrics are served only on the internal management port (prod: 9090), never publicly.
                        .requestMatchers(request -> {
                            String path = RequestPaths.normalized(request);
                            return path != null && path.startsWith("/actuator/")
                                    && managementPort > 0 && request.getLocalPort() == managementPort;
                        }).permitAll()
                        .requestMatchers("/actuator/**").hasAnyRole("ops", "ops_admin")
                        .requestMatchers("/v1/public/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/v3/api-docs", "/v3/api-docs/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/rails/*/callbacks/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/fx/partners/*/callbacks", "/v1/fx/partners/*/inbound").permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/ussd/**").permitAll()
                        .requestMatchers("/dev/token").permitAll()
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/assets/**", "/pay/**", "/favicon.svg",
                                "/merchant", "/merchant/**", "/ops", "/ops/**", "/app", "/app/**", "/developer", "/developer/**",
                                "/kitchen", "/kitchen/**", "/kitchen-sw.js", "/kitchen.webmanifest",
                                "/pos-sw.js", "/customer-display").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(new RolesConverter())))
                .addFilterBefore(stage1KillSwitchFilter, BearerTokenAuthenticationFilter.class)
                .addFilterBefore(rateLimitFilter, BearerTokenAuthenticationFilter.class)
                .addFilterBefore(requestSigningFilter, BearerTokenAuthenticationFilter.class)
                .addFilterBefore(new ApiKeyFilter(apiKeys, trustedProxies), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Whitelist one exact trusted HTTPS edge origin from deployment configuration.
     * No wildcard, no arbitrary host from a request and no silent relaxation of CSP.
     */
    static String checkedLanOrigin(String configured) {
        if (configured == null || configured.isBlank()) return "";
        try {
            java.net.URI uri = new java.net.URI(configured.strip());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getHost().length() > 253 || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getPath() != null && !uri.getPath().isBlank() && !"/".equals(uri.getPath()))
                    || configured.indexOf(';') >= 0 || configured.indexOf(' ') >= 0)
                throw new IllegalArgumentException("Invalid trusted POS LAN origin");
            return " " + uri.getScheme().toLowerCase(java.util.Locale.ROOT) + "://"
                    + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("Invalid trusted POS LAN origin", e);
        }
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
        private final String trustedProxies;

        ApiKeyFilter(ApiKeys keys, String trustedProxies) {
            this.keys = keys;
            this.trustedProxies = trustedProxies;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String header = request.getHeader("X-Elepay-Api-Key");
            if (header != null) {
                var authOpt = keys.authenticate(header);
                if (authOpt.isEmpty()) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"code\":\"invalid_api_key\",\"error\":\"invalid_api_key\",\"message\":\"Invalid or revoked API key\"}");
                    return;
                }
                ApiKeyAuthentication auth = authOpt.get();

                // Gate 1: Enforce IP Allowlist for payout_live_* keys (and any key with configured allowed_ips)
                String pathForGates = RequestPaths.normalized(request);
                if (pathForGates == null) {
                    response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"code\":\"invalid_path\",\"message\":\"The request path is not allowed\"}");
                    return;
                }
                String callerIp = ClientIpResolver.resolve(request, trustedProxies);
                if (!keys.isIpAllowed(auth, callerIp)) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"code\":\"ip_not_allowlisted\",\"error\":\"ip_not_allowlisted\",\"message\":\"API key is restricted to the merchant's registered server IP allowlist\"}");
                    return;
                }

                // Gate 1: pk_live_* (publishable key) can ONLY create payment intents / checkouts
                if ("publishable".equalsIgnoreCase(auth.keyType())) {
                    String method = request.getMethod();
                    String path = pathForGates;
                    boolean allowedForPublishable = "POST".equalsIgnoreCase(method)
                            && (path.matches("^/v1/merchants/[^/]+/checkouts$")
                                || path.equals("/v1/checkouts")
                                || path.startsWith("/v1/public/"));
                    if (!allowedForPublishable) {
                        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                        response.setContentType("application/json");
                        response.getWriter().write("{\"code\":\"publishable_key_restricted\",\"error\":\"publishable_key_restricted\",\"message\":\"Publishable keys (pk_live) can only create payment intents / checkouts\"}");
                        return;
                    }
                }

                // Kitchen device keys (kd_live_*) reach only the kitchen display endpoints, nothing else.
                if ("kitchen".equalsIgnoreCase(auth.keyType()) && !KitchenDevicePaths.allowed(request.getMethod(), pathForGates)) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"code\":\"kitchen_key_restricted\",\"error\":\"kitchen_key_restricted\",\"message\":\"Kitchen device keys can only use the kitchen display\"}");
                    return;
                }

                // Gate 1: sk_live_* and legacy ek_* keys cannot initiate payouts or withdrawals.
                if ("secret".equalsIgnoreCase(auth.keyType()) || "legacy".equalsIgnoreCase(auth.keyType())) {
                    String method = request.getMethod();
                    String path = pathForGates;
                    boolean isPayoutMutation = ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method))
                            && (path.matches("^/v1/merchants/[^/]+/withdrawals(?:/.*)?$")
                                || path.matches("^/v1/merchants/[^/]+/payout-batches(?:/.*)?$")
                                || path.matches("^/v1/merchants/[^/]+/payouts/.+/retry$"));
                    if (isPayoutMutation) {
                        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                        response.setContentType("application/json");
                        response.getWriter().write("{\"code\":\"payout_key_required\",\"error\":\"payout_key_required\",\"message\":\"Secret keys (sk_live) cannot initiate payouts or withdrawals alone; use a dedicated payout_live key\"}");
                        return;
                    }
                }

                SecurityContextHolder.getContext().setAuthentication(auth);
            }
            chain.doFilter(request, response);
        }

    }
}
