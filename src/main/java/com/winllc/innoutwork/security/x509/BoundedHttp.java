package com.winllc.innoutwork.security.x509;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * The only way revocation checking reaches the network: http and https only, no redirects, a 5 second connect and
 * 10 second response timeout, a size cap, and never to a link-local or cloud metadata address (169.254.169.254
 * and the like), whatever a certificate's AIA or CRL distribution point names. Uses no proxy.
 */
class BoundedHttp {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    /** Metadata services that are not link-local addresses. */
    private static final Set<String> METADATA = Set.of("100.100.100.200", "fd00:ec2:0:0:0:0:0:254");

    private final HttpClient client;

    BoundedHttp() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(HttpClient.Builder.NO_PROXY)
                .build();
    }

    byte[] get(URI uri, long maxBytes) throws IOException {
        return send(HttpRequest.newBuilder(checked(uri)).timeout(READ_TIMEOUT).GET().build(), maxBytes);
    }

    byte[] post(URI uri, String contentType, String accept, byte[] body, long maxBytes) throws IOException {
        return send(HttpRequest.newBuilder(checked(uri)).timeout(READ_TIMEOUT)
                .header("Content-Type", contentType).header("Accept", accept)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), maxBytes);
    }

    private byte[] send(HttpRequest request, long maxBytes) throws IOException {
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException(request.uri() + " answered HTTP " + response.statusCode());
            }
            byte[] bytes = body.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
            if (bytes.length > maxBytes) {
                throw new IOException(request.uri() + " sent more than " + maxBytes + " bytes");
            }
            return bytes;
        }
    }

    /** The URI, if it may be fetched at all. */
    static URI checked(URI uri) throws IOException {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IOException("not an http(s) address: " + uri);
        }
        if (uri.getHost() == null) {
            throw new IOException("no host in " + uri);
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(uri.getHost());
        } catch (UnknownHostException e) {
            throw new IOException("unknown host " + uri.getHost(), e);
        }
        for (InetAddress address : addresses) {
            if (blocked(address)) {
                throw new IOException(uri.getHost() + " is a link-local or metadata address (" + address.getHostAddress()
                        + "); not fetched");
            }
        }
        return uri;
    }

    static boolean blocked(InetAddress address) {
        return address.isLinkLocalAddress() || address.isAnyLocalAddress() || address.isMulticastAddress()
                || METADATA.contains(address.getHostAddress());
    }
}
