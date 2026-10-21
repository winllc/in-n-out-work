package com.winllc.innoutwork.webhook;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

/**
 * Where webhooks may be sent: the guard against server-side request forgery. A webhook URL must be
 * {@code https://}, name a host and carry no user name or password. Every address its host resolves to must be a
 * public one: loopback, unspecified, link-local, private (RFC 1918, unique local), shared (100.64/10), reserved,
 * benchmarking, broadcast and multicast addresses are refused, including IPv4 ones inside IPv6 (mapped, compatible,
 * NAT64, 6to4), unless an allow-listed range (application.webhooks.allowed-cidrs) holds them. Cloud metadata
 * services (169.254.169.254, fd00:ec2::254, 100.100.100.200, 169.254.170.2) are refused whatever the allow-list
 * says, and multicast too.
 *
 * <p>The check runs when a webhook is saved and again on every delivery, and the delivery connects to the address
 * that was checked ({@link WebhookTransport}), so a name that resolves to a public address when saved and to an
 * internal one later (DNS rebinding) is refused then.
 */
public class WebhookAddressPolicy {

    /** Looks a host up: {@link InetAddress#getAllByName} outside tests. */
    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    static final int MAX_URL = 2000;

    private static final List<Cidr> METADATA = List.of(
            Cidr.parse("169.254.169.254/32"), Cidr.parse("169.254.170.2/32"), Cidr.parse("100.100.100.200/32"),
            Cidr.parse("fd00:ec2::254/128"));

    private static final List<Cidr> SPECIAL_V4 = List.of(
            Cidr.parse("0.0.0.0/8"),          // "this network", unspecified
            Cidr.parse("10.0.0.0/8"),         // private
            Cidr.parse("100.64.0.0/10"),      // shared address space (carrier-grade NAT)
            Cidr.parse("127.0.0.0/8"),        // loopback
            Cidr.parse("169.254.0.0/16"),     // link-local
            Cidr.parse("172.16.0.0/12"),      // private
            Cidr.parse("192.0.0.0/24"),       // IETF protocol assignments
            Cidr.parse("192.0.2.0/24"),       // documentation
            Cidr.parse("192.168.0.0/16"),     // private
            Cidr.parse("198.18.0.0/15"),      // benchmarking
            Cidr.parse("198.51.100.0/24"),    // documentation
            Cidr.parse("203.0.113.0/24"),     // documentation
            Cidr.parse("224.0.0.0/4"),        // multicast
            Cidr.parse("240.0.0.0/4"));       // reserved, and broadcast

    private static final List<Cidr> SPECIAL_V6 = List.of(
            Cidr.parse("::/128"),             // unspecified
            Cidr.parse("::1/128"),            // loopback
            Cidr.parse("fc00::/7"),           // unique local
            Cidr.parse("fe80::/10"),          // link-local
            Cidr.parse("fec0::/10"),          // site-local (deprecated)
            Cidr.parse("ff00::/8"),           // multicast
            Cidr.parse("2001:db8::/32"),      // documentation
            Cidr.parse("100::/64"));          // discard

    private final List<Cidr> allowed;
    private final HostResolver resolver;

    public WebhookAddressPolicy(List<Cidr> allowed, HostResolver resolver) {
        this.allowed = List.copyOf(allowed);
        this.resolver = resolver;
    }

    public static WebhookAddressPolicy of(List<String> allowedCidrs) {
        return new WebhookAddressPolicy(allowedCidrs.stream().map(Cidr::parse).toList(), InetAddress::getAllByName);
    }

    /** The URL, if a webhook may be sent to it at all (its host is checked by {@link #resolve}). */
    public URI checkUrl(String url) throws WebhookRefusedException {
        if (url == null || url.isBlank()) {
            throw new WebhookRefusedException("Enter the endpoint's URL.");
        }
        if (url.length() > MAX_URL) {
            throw new WebhookRefusedException("The URL is longer than " + MAX_URL + " characters.");
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new WebhookRefusedException("That is not a URL.");
        }
        if (!"https".equals(uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT))) {
            throw new WebhookRefusedException("Webhooks are sent over HTTPS only: the URL must start with https://.");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new WebhookRefusedException("The URL names no host.");
        }
        if (uri.getRawUserInfo() != null) {
            throw new WebhookRefusedException("The URL may not carry a user name or password.");
        }
        if (uri.getRawFragment() != null) {
            throw new WebhookRefusedException("The URL may not have a #fragment.");
        }
        if (uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new WebhookRefusedException("The URL's port is out of range.");
        }
        return uri;
    }

    /**
     * Every address {@code host} resolves to, if all of them may be sent to.
     *
     * @throws WebhookRefusedException when the host does not resolve, or any of its addresses is refused
     */
    public List<InetAddress> resolve(String host) throws WebhookRefusedException {
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(bare);
        } catch (UnknownHostException e) {
            throw new WebhookRefusedException("The host " + bare + " does not resolve.");
        }
        if (addresses == null || addresses.length == 0) {
            throw new WebhookRefusedException("The host " + bare + " does not resolve.");
        }
        for (InetAddress address : addresses) {
            String refusal = refusal(address);
            if (refusal != null) {
                throw new WebhookRefusedException("The host " + bare + " resolves to " + address.getHostAddress()
                        + ", " + refusal + ".");
            }
        }
        return List.of(addresses);
    }

    /** Why {@code address} may not be sent to, or null when it may. */
    public String refusal(InetAddress address) {
        for (Cidr metadata : METADATA) {
            if (metadata.contains(address)) {
                return "a cloud metadata address, which webhooks are never sent to";
            }
        }
        Inet4Address embedded = embeddedIpv4(address);
        if (embedded != null) {
            String inner = refusal(embedded);
            if (inner != null) {
                return inner;
            }
        }
        if (address.isMulticastAddress()) {
            return "a multicast address";
        }
        if (!special(address)) {
            return null;
        }
        for (Cidr range : allowed) {
            if (range.contains(address)) {
                return null;
            }
        }
        return "a loopback, private, link-local or reserved address; to allow it, add its range to "
                + "application.webhooks.allowed-cidrs";
    }

    private static boolean special(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()) {
            return true;
        }
        List<Cidr> ranges = address instanceof Inet4Address ? SPECIAL_V4 : SPECIAL_V6;
        return ranges.stream().anyMatch(r -> r.contains(address));
    }

    /**
     * The IPv4 address inside an IPv6 one that carries it: IPv4-mapped (::ffff:0:0/96), IPv4-compatible (::/96),
     * NAT64 (64:ff9b::/96) and 6to4 (2002::/16). Null otherwise.
     */
    static Inet4Address embeddedIpv4(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return null;
        }
        byte[] a = address.getAddress();
        byte[] v4 = null;
        if (allZero(a, 0, 10) && ((a[10] == (byte) 0xFF && a[11] == (byte) 0xFF) || (a[10] == 0 && a[11] == 0))) {
            if (allZero(a, 0, 12) && allZero(a, 12, 15) && (a[15] == 0 || a[15] == 1)) {
                return null; // :: and ::1, handled as IPv6
            }
            v4 = new byte[]{a[12], a[13], a[14], a[15]};
        } else if (a[0] == 0x00 && a[1] == 0x64 && a[2] == (byte) 0xFF && a[3] == (byte) 0x9B && allZero(a, 4, 12)) {
            v4 = new byte[]{a[12], a[13], a[14], a[15]};
        } else if (a[0] == 0x20 && a[1] == 0x02) {
            v4 = new byte[]{a[2], a[3], a[4], a[5]};
        }
        if (v4 == null) {
            return null;
        }
        try {
            return (Inet4Address) InetAddress.getByAddress(v4);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static boolean allZero(byte[] a, int from, int to) {
        for (int i = from; i < to; i++) {
            if (a[i] != 0) {
                return false;
            }
        }
        return true;
    }
}
