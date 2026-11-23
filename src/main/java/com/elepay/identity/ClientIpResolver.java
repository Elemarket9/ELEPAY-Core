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
                // Proxies APPEND to X-Forwarded-For (e.g. nginx $proxy_add_x_forwarded_for), so the
                // chain reads left-to-right as [client, edge, ..., nearest proxy]. The LEFTMOST token
                // is fully attacker-controlled (the client can send any XFF it likes) and must NEVER be
                // trusted. We instead walk the chain from RIGHT to LEFT starting at the hop our own edge
                // appended, skipping entries that are themselves trusted proxies, and return the first
                // address that is NOT a trusted proxy — that is the real client as seen by our edge.
                String[] hops = xff.split(",");
                for (int i = hops.length - 1; i >= 0; i--) {
                    String hop = hops[i].trim();
                    if (hop.isEmpty()) continue;
                    if (!isTrustedProxy(hop, trustedProxies)) {
                        return hop;
                    }
                }
                // Every hop is a trusted proxy (or the header held only blanks): fall through to the peer.
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
