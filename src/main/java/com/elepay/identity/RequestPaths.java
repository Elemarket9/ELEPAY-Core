package com.elepay.identity;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Path used for security decisions. Spring MVC matches the decoded path, so controls that
 * look at the raw request URI miss percent-encoded segments such as {@code %77allet}.
 */
public final class RequestPaths {
    private RequestPaths() {}

    /**
     * @return the decoded application path, or {@code null} when the raw path is not safe to interpret
     *         (percent-encoding, backslash, or dot-segments)
     */
    public static String normalized(HttpServletRequest request) {
        String raw = request.getRequestURI();
        if (raw == null) return null;
        if (raw.indexOf('%') >= 0 || raw.indexOf('\\') >= 0 || raw.indexOf(';') >= 0) return null;
        if (raw.contains("/../") || raw.contains("/./") || raw.endsWith("/..") || raw.endsWith("/.")) return null;
        String servlet = request.getServletPath() == null ? "" : request.getServletPath();
        String info = request.getPathInfo() == null ? "" : request.getPathInfo();
        String path = servlet + info;
        if (path.isEmpty()) path = raw;
        if (path.indexOf('%') >= 0 || path.contains("..")) return null;
        return path;
    }
}
