package com.elepay.cell;

import com.elepay.common.datasource.DataSourceRouteContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Extracts the target merchant UUID from {@code /v1/merchants/{merchantId}/...} requests,
 * resolves its shard cell via {@link CellRouter}, and binds the cell ID into
 * {@link DataSourceRouteContext} for the duration of the HTTP request.
 *
 * <p>Always clears {@link DataSourceRouteContext} in a {@code finally} block so pooled or
 * virtual threads never leak cell routing or RYOW sticky-primary state across requests.
 */
@Component("cellRoutingFilter")
public class CellRoutingFilter extends OncePerRequestFilter {

    static final String CELL_HEADER = "X-Elepay-Cell";

    private static final Pattern MERCHANT_URI_PATTERN = Pattern.compile(
            "^/v1/merchants/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(?:/.*)?$");

    private final CellRouter cellRouter;
    private final boolean cellsEnabled;

    public CellRoutingFilter(
            CellRouter cellRouter,
            @Value("${elepay.cells.enabled:false}") boolean cellsEnabled) {
        this.cellRouter = cellRouter;
        this.cellsEnabled = cellsEnabled;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        try {
            UUID merchantId = extractMerchantId(request.getRequestURI());
            String cellId = cellRouter.resolveCell(merchantId);
            if (cellId == null || cellId.isBlank()) {
                cellId = DataSourceRouteContext.DEFAULT_CELL_ID;
            }
            DataSourceRouteContext.setCellId(cellId);
            if (cellsEnabled) {
                response.setHeader(CELL_HEADER, cellId);
            }
            filterChain.doFilter(request, response);
        } finally {
            DataSourceRouteContext.clear();
        }
    }

    static UUID extractMerchantId(String requestUri) {
        if (requestUri == null || requestUri.isEmpty()) {
            return null;
        }
        Matcher matcher = MERCHANT_URI_PATTERN.matcher(requestUri);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return UUID.fromString(matcher.group(1));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
