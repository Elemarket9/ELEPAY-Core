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
        String path = request.getRequestURI();
        String disabledModule = killSwitchService.resolveDisabledModuleForPath(path);
        if (disabledModule != null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(
                    "{\"code\":\"FEATURE_NOT_ENABLED\",\"error\":\"FEATURE_NOT_ENABLED\",\"module\":\""
                            + disabledModule
                            + "\",\"message\":\"This module is disabled in Stage 1\"}"
            );
            return;
        }
        chain.doFilter(request, response);
    }
}
