package com.elepay.identity;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CompositeFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
class SecurityConfig {

    @Bean
    FilterRegistrationBean<EdgeProtectionFilter> edgeProtectionFilterRegistration(EdgeProtectionFilter filter) {
        var reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    FilterRegistrationBean<Stage1KillSwitchFilter> stage1KillSwitchFilterRegistration(Stage1KillSwitchFilter filter) {
        var reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        var reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    FilterRegistrationBean<RequestSigningFilter> requestSigningFilterRegistration(RequestSigningFilter filter) {
        var reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    @ConditionalOnBean(name = "cellRoutingFilter")
    FilterRegistrationBean<OncePerRequestFilter> cellRoutingFilterRegistration(
            @Qualifier("cellRoutingFilter") OncePerRequestFilter filter) {
        var reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }

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
    CorsConfigurationSource corsConfigurationSource(
            @Value("${elepay.cors.allowed-origins:}") String allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = allowedOrigins == null || allowedOrigins.isBlank()
                ? List.of()
                : Arrays.stream(allowedOrigins.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "Accept", "Idempotency-Key",
                "X-Elepay-Api-Key", "X-Elepay-Signature", "X-Elepay-Signature-Version",
                "X-Elepay-Timestamp", "X-Elepay-Nonce", "X-Elepay-Channel", "X-Trace-Id"
        ));
        config.setExposedHeaders(List.of("X-Trace-Id", "X-Span-Id", "Retry-After"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    static String extractOrigin(String uriString) {
        if (uriString == null || uriString.isBlank()) return null;
        try {
            URI uri = URI.create(uriString.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) return null;
            int port = uri.getPort();
            boolean defaultPort = (port == -1)
                    || ("http".equalsIgnoreCase(scheme) && port == 80)
                    || ("https".equalsIgnoreCase(scheme) && port == 443);
            return defaultPort ? scheme + "://" + host : scheme + "://" + host + ":" + port;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Bean
    SecurityFilterChain api(HttpSecurity http, ApiKeys apiKeys,
                            EdgeProtectionFilter edgeProtectionFilter,
                            Stage1KillSwitchFilter stage1KillSwitchFilter,
                            RateLimitFilter rateLimitFilter,
                            RequestSigningFilter requestSigningFilter,
                            @Qualifier("cellRoutingFilter") @Autowired(required = false) OncePerRequestFilter cellRoutingFilter,
                            Environment env,
                            @Value("${management.server.port:0}") int managementPort,
                            @Value("${elepay.security.trusted-proxies:}") String trustedProxies,
                            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuerUri)
            throws Exception {
        boolean isProd = env != null && env.acceptsProfiles(Profiles.of("prod", "production"));
        String issuerOrigin = extractOrigin(issuerUri);
        String connectSrc = issuerOrigin != null ? "connect-src 'self' " + issuerOrigin : "connect-src 'self'";

        List<Filter> preAuthList = new ArrayList<>();
        preAuthList.add(edgeProtectionFilter);
        preAuthList.add(stage1KillSwitchFilter);
        preAuthList.add(rateLimitFilter);
        if (cellRoutingFilter != null) {
            preAuthList.add(cellRoutingFilter);
        }
        preAuthList.add(new ApiKeyFilter(apiKeys, trustedProxies));
        CompositeFilter preAuthFilters = new CompositeFilter();
        preAuthFilters.setFilters(preAuthList);

        http.csrf(csrf -> csrf.disable())
                .cors(cors -> {})
                // OWASP secure headers: strict CSP for the web app, HSTS, no framing, no referrer leakage.
                .headers(h -> h
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                                + "img-src 'self' data:; " + connectSrc + "; font-src 'self'; object-src 'none'; "
                                + "base-uri 'self'; form-action 'self'; frame-ancestors 'none'"))
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                        .frameOptions(f -> f.deny())
                        .referrerPolicy(r -> r.policy(org.springframework.security.web.header.writers
                                .ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .addHeaderWriter(new org.springframework.security.web.header.writers.StaticHeadersWriter(
                                "Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(self)")))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    if (isProd) {
                        auth.requestMatchers("/sandbox/**", "/dev/token").denyAll();
                    } else {
                        auth.requestMatchers("/sandbox/**", "/dev/token").permitAll();
                    }
                    auth
                            .requestMatchers("/actuator/health/**", "/actuator/health", "/actuator/info").permitAll()
                            // Metrics are served only on the internal management port (prod: 9090), never publicly.
                            .requestMatchers(request -> {
                                String path = RequestPaths.normalized(request);
                                return path != null && path.startsWith("/actuator/")
                                        && managementPort > 0 && request.getLocalPort() == managementPort;
                            }).permitAll()
                            .requestMatchers("/actuator/**").hasAnyRole("ops", "ops_admin")
                            .requestMatchers("/v1/public/**").permitAll()
                            .requestMatchers(HttpMethod.POST,
                                    "/v1/auth/register", "/v1/auth/login", "/v1/auth/verify",
                                    "/v1/auth/resend", "/v1/auth/forgot-password", "/v1/auth/reset-password").permitAll()
                            .requestMatchers(HttpMethod.GET, "/v3/api-docs", "/v3/api-docs/**").permitAll()
                            .requestMatchers(HttpMethod.POST, "/v1/rails/*/callbacks/**").permitAll()
                            .requestMatchers(HttpMethod.POST, "/v1/fx/partners/*/callbacks", "/v1/fx/partners/*/inbound").permitAll()
                            .requestMatchers(HttpMethod.POST, "/v1/ussd/**").permitAll()
                            .requestMatchers(HttpMethod.GET, "/", "/index.html", "/assets/**", "/pay/**", "/favicon.svg",
                                    "/merchant", "/merchant/**", "/ops", "/ops/**", "/app", "/app/**", "/developer", "/developer/**",
                                    "/kitchen", "/kitchen/**", "/kitchen-sw.js", "/kitchen.webmanifest").permitAll()
                            .anyRequest().authenticated();
                })
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(new RolesConverter())))
                .addFilterBefore(preAuthFilters, BearerTokenAuthenticationFilter.class)
                .addFilterAfter(requestSigningFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
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
                    writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "invalid_api_key", "Invalid or revoked API key");
                    return;
                }
                ApiKeyAuthentication auth = authOpt.get();

                // Gate 1: Enforce IP Allowlist for payout_live_* keys (and any key with configured allowed_ips)
                String pathForGates = RequestPaths.normalized(request);
                if (pathForGates == null) {
                    writeError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_path", "The request path is not allowed");
                    return;
                }
                String callerIp = ClientIpResolver.resolve(request, trustedProxies);
                if (!keys.isIpAllowed(auth, callerIp)) {
                    writeError(response, HttpServletResponse.SC_FORBIDDEN, "ip_not_allowlisted", "API key is restricted to the merchant's registered server IP allowlist");
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
                        writeError(response, HttpServletResponse.SC_FORBIDDEN, "publishable_key_restricted", "Publishable keys (pk_live) can only create payment intents / checkouts");
                        return;
                    }
                }

                // Kitchen device keys (kd_live_*) reach only the kitchen display endpoints, nothing else.
                if ("kitchen".equalsIgnoreCase(auth.keyType()) && !KitchenDevicePaths.allowed(request.getMethod(), pathForGates)) {
                    writeError(response, HttpServletResponse.SC_FORBIDDEN, "kitchen_key_restricted", "Kitchen device keys can only use the kitchen display");
                    return;
                }

                // POS terminal keys (pos_live_*) reach only POS terminal/order/cart/shift/NFC/sync endpoints.
                if ("pos_terminal".equalsIgnoreCase(auth.keyType()) && !PosDevicePaths.allowed(request.getMethod(), pathForGates)) {
                    writeError(response, HttpServletResponse.SC_FORBIDDEN, "pos_terminal_key_restricted", "POS terminal keys can only access POS terminal endpoints");
                    return;
                }

                String method = request.getMethod();
                boolean isRead = "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method);

                // Gate 1: Non-payout keys cannot perform any non-GET mutation on payout, withdrawal, or payroll-payout routes.
                if (!"payout".equalsIgnoreCase(auth.keyType()) && !isRead) {
                    boolean isPayoutRoute = pathForGates.matches("^/v1/merchants/[^/]+/withdrawals(?:/.*)?$")
                            || pathForGates.matches("^/v1/merchants/[^/]+/payout-batches(?:/.*)?$")
                            || pathForGates.matches("^/v1/merchants/[^/]+/payouts(?:/.*)?$")
                            || pathForGates.matches("^/v1/merchants/[^/]+/payroll(?:/.*)?/payouts(?:/.*)?$")
                            || pathForGates.matches("^/v1/payouts(?:/.*)?$");
                    if (isPayoutRoute) {
                        writeError(response, HttpServletResponse.SC_FORBIDDEN, "payout_key_required", "Non-payout API keys cannot initiate or modify payouts or withdrawals; use a dedicated payout_live key");
                        return;
                    }
                }

                // Enforce fine-grained scopes (view, collect, refund, withdraw, payouts) on API keys.
                if (!hasRequiredScope(auth, method, pathForGates)) {
                    writeError(response, HttpServletResponse.SC_FORBIDDEN, "insufficient_scope", "API key lacks required scope for this operation");
                    return;
                }

                SecurityContextHolder.getContext().setAuthentication(auth);
            }
            chain.doFilter(request, response);
        }

        private static void writeError(HttpServletResponse response, int status, String code, String message) throws IOException {
            response.setStatus(status);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"" + code + "\",\"error\":\"" + code
                    + "\",\"detail\":\"" + message + "\",\"message\":\"" + message + "\",\"status\":" + status + "}");
        }

        private static boolean hasRequiredScope(ApiKeyAuthentication auth, String method, String path) {
            var scopes = auth.scopes();
            if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method)) {
                return scopes.contains("view");
            }
            if (path.matches("^/v1/merchants/[^/]+/seller-directory(?:/.*)?$")) {
                return scopes.contains("seller_sync");
            }
            if (path.contains("/refunds")) {
                return scopes.contains("refund");
            }
            if (path.matches("^/v1/merchants/[^/]+/withdrawals(?:/.*)?$")) {
                return scopes.contains("withdraw") || scopes.contains("payouts");
            }
            if (path.matches("^/v1/merchants/[^/]+/payout-batches(?:/.*)?$")
                    || path.matches("^/v1/merchants/[^/]+/payouts(?:/.*)?$")
                    || path.matches("^/v1/merchants/[^/]+/payroll(?:/.*)?/payouts(?:/.*)?$")
                    || path.matches("^/v1/payouts(?:/.*)?$")) {
                return scopes.contains("payouts") || scopes.contains("withdraw");
            }
            return scopes.contains("collect");
        }

    }
}
