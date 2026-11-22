package com.elepay.common;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Server-side request forgery (SSRF) guard for URLs that merchants supply and ELEPAY calls (webhooks).
 *
 * <p>In production only {@code https} to public internet hosts on ports 443 or 8443 is allowed. Every
 * address the host resolves to is checked, at registration and again before each call (DNS can change):
 * loopback, private (RFC 1918), carrier-grade NAT, link-local (including cloud metadata at
 * 169.254.169.254), multicast, unique-local IPv6 and reserved ranges are refused. Development profiles may
 * allow http and private hosts for local receivers.
 */
@Component
public class OutboundUrlGuard {
    private static final Set<Integer> HTTPS_PORTS = Set.of(-1, 443, 8443);

    private final boolean allowHttp;
    private final boolean allowPrivateHosts;

    public OutboundUrlGuard(@Value("${elepay.outbound.allow-http:false}") boolean allowHttp,
                            @Value("${elepay.outbound.allow-private-hosts:false}") boolean allowPrivateHosts) {
        this.allowHttp = allowHttp;
        this.allowPrivateHosts = allowPrivateHosts;
    }

    /** Throws {@code 400 unsafe_url} when ELEPAY must not call this URL. */
    public void validate(String url) {
        String problem = problem(url);
        if (problem != null) throw ApiException.badRequest("unsafe_url", problem);
    }

    public boolean isSafe(String url) {
        return problem(url) == null;
    }

    /** Why the URL is refused, or null when it is allowed. */
    public String problem(String url) {
        if (url == null || url.isBlank() || url.length() > 2048) return "Enter an https URL";
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return "The URL is not valid";
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(allowHttp && scheme.equals("http"))) return "The URL must use https";
        if (uri.getRawUserInfo() != null) return "The URL must not contain credentials";
        String host = uri.getHost();
        if (host == null || host.isBlank()) return "The URL needs a host name";
        if (!allowPrivateHosts && !HTTPS_PORTS.contains(uri.getPort())) return "Use port 443 or 8443";
        if (allowPrivateHosts) return null;
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.equals("localhost") || lower.endsWith(".localhost") || lower.endsWith(".internal")
                || lower.endsWith(".local")) {
            return "The URL must point to a public host";
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return "The host name does not resolve";
        }
        for (InetAddress a : addresses) {
            if (!isPublic(a)) return "The URL must point to a public host";
        }
        return null;
    }

    static boolean isPublic(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress()) {
            return false;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int o0 = b[0] & 0xff, o1 = b[1] & 0xff, o2 = b[2] & 0xff;
            if (o0 == 0) return false;                                   // 0.0.0.0/8
            if (o0 == 100 && o1 >= 64 && o1 <= 127) return false;       // 100.64.0.0/10 carrier-grade NAT
            if (o0 == 192 && o1 == 0 && o2 == 0) return false;          // 192.0.0.0/24 IETF
            if (o0 == 192 && o1 == 0 && o2 == 2) return false;          // TEST-NET-1
            if (o0 == 198 && (o1 == 18 || o1 == 19)) return false;      // benchmarking
            if (o0 == 198 && o1 == 51 && o2 == 100) return false;       // TEST-NET-2
            if (o0 == 203 && o1 == 0 && o2 == 113) return false;        // TEST-NET-3
            if (o0 >= 240) return false;                                // reserved and broadcast
            return true;
        }
        if (a instanceof Inet6Address) {
            if ((b[0] & 0xfe) == 0xfc) return false;                    // fc00::/7 unique local
            if ((b[0] & 0xff) == 0x20 && (b[1] & 0xff) == 0x01 && (b[2] & 0xff) == 0x0d && (b[3] & 0xff) == 0xb8) {
                return false;                                           // 2001:db8::/32 documentation
            }
            // IPv4-mapped (::ffff:a.b.c.d): judge the embedded IPv4 address.
            boolean mapped = true;
            for (int i = 0; i < 10; i++) mapped &= b[i] == 0;
            if (mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
                try {
                    return isPublic(InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]}));
                } catch (UnknownHostException e) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }
}
