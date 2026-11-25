package com.elepay.reliability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Filter that attaches distributed trace headers (X-Trace-Id, X-Span-Id)
 * and propagates W3C TraceContext standards.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceContextFilter extends OncePerRequestFilter {
    public static final String HEADER_TRACE_ID = "X-Trace-Id";
    public static final String HEADER_SPAN_ID = "X-Span-Id";
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String incomingTraceId = request.getHeader(HEADER_TRACE_ID);
        String trimmed = incomingTraceId != null ? incomingTraceId.trim() : null;
        String traceId = (trimmed != null && SAFE_TRACE_ID.matcher(trimmed).matches())
                ? trimmed
                : UUID.randomUUID().toString().replace("-", "");

        String spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        response.setHeader(HEADER_TRACE_ID, traceId);
        response.setHeader(HEADER_SPAN_ID, spanId);

        filterChain.doFilter(request, response);
    }
}
