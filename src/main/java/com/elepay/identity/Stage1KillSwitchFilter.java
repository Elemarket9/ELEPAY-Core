package com.elepay.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Route-level Stage 1 Kill-Switch Filter.
 *
 * <p>Runs before authentication and database lookups (right after {@link EdgeProtectionFilter})
 * and returns HTTP {@code 404 Not Found} with {@code FEATURE_NOT_ENABLED} for all disabled
 * non-Stage-1 modules.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 15)
public class Stage1KillSwitchFilter extends OncePerRequestFilter {
    private final KillSwitchService killSwitchService;

    public Stage1KillSwitchFilter(KillSwitchService killSwitchService) {
        this.killSwitchService = killSwitchService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = RequestPaths.normalized(request);
        if (path == null) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"code\":\"invalid_path\",\"detail\":\"The request path is not allowed\",\"message\":\"The request path is not allowed\",\"status\":400}");
            return;
        }
        String disabledModule = killSwitchService.resolveDisabledModuleForPath(path);
        if (disabledModule != null
                && !("DELETE".equalsIgnoreCase(request.getMethod()) && path.startsWith("/v1/rails/card/tokens/"))) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(
                    "{\"code\":\"FEATURE_NOT_ENABLED\",\"error\":\"FEATURE_NOT_ENABLED\",\"module\":\""
                            + disabledModule
                            + "\",\"detail\":\"This module is disabled in Stage 1\",\"message\":\"This module is disabled in Stage 1\",\"status\":404}"
            );
            return;
        }
        chain.doFilter(request, response);
    }
}
