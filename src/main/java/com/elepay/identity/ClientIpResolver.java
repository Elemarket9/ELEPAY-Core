package com.elepay.identity;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;

/**
 * Client address for IP allowlists. {@code X-Forwarded-For} is honored only when the immediate
 * peer is on an explicit trusted-proxy list. Loopback is not trusted unless it is listed.
 */
public final class ClientIpResolver {
    private ClientIpResolver() {}

    public static String resolve(HttpServletRequest request, String trustedProxies) {
        String remote = request.getRemoteAddr();
        if (remote != null && isTrustedProxy(remote, trustedProxies)) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                return xff.split(",")[0].trim();
            }
        }
        return remote;
    }

    public static boolean isTrustedProxy(String remoteAddr, String trustedProxies) {
        if (remoteAddr == null || trustedProxies == null || trustedProxies.isBlank()) return false;
        List<String> trusted = Arrays.stream(trustedProxies.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        for (String spec : trusted) {
            if (spec.equals(remoteAddr)) return true;
            try {
                if (CidrBlock.parse(spec).contains(remoteAddr)) return true;
            } catch (IllegalArgumentException ignored) {
                // A bare address that did not match is not a CIDR.
            }
        }
        return false;
    }
}
