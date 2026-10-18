package com.winllc.innoutwork.security;

import jakarta.servlet.http.HttpServletRequest;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The client address a request came from, in one canonical form, for comparing two requests' addresses: an IPv4
 * address mapped into IPv6 is the IPv4 address, and IPv6 is written out in full ({@code 0:0:0:0:0:0:0:1}), so the
 * same host always compares equal however its address was spelt.
 * <p>
 * It is {@link HttpServletRequest#getRemoteAddr()}: the TCP peer, or behind a reverse proxy, with
 * {@code server.forward-headers-strategy} set, the address the proxy reports. Never a name: nothing here looks a
 * name up.
 */
public final class ClientAddress {

    /**
     * An IPv4 dotted quad, or something with a colon made of what IPv6 literals are (zone included), which
     * InetAddress parses without a lookup. Anything else is compared as sent, lower-cased.
     */
    private static final Pattern LITERAL = Pattern.compile(
            "\\d{1,3}(\\.\\d{1,3}){3}|[0-9A-Fa-f.]*:[0-9A-Fa-f:.]*(%[0-9A-Za-z_.-]+)?");

    private ClientAddress() {
    }

    public static String of(HttpServletRequest request) {
        return normalize(request.getRemoteAddr());
    }

    /** {@code address} in canonical form; null or blank gives null. */
    public static String normalize(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        String trimmed = address.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        if (LITERAL.matcher(trimmed).matches()) {
            try {
                // A literal: parsed, never resolved.
                return InetAddress.getByName(trimmed).getHostAddress().toLowerCase(Locale.ROOT);
            } catch (UnknownHostException e) {
                // Not a valid literal after all; compared as sent.
            }
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }
}
