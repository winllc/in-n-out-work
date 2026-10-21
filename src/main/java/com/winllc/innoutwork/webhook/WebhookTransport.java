package com.winllc.innoutwork.webhook;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sends one delivery: an HTTPS POST to an address {@link WebhookAddressPolicy} has just checked, and to no other.
 * The host is looked up and checked here, at send time, and the TCP connection is made to the checked address
 * itself, while TLS still verifies the certificate against the host name (SNI and HTTPS endpoint identification).
 * So the name cannot be made to resolve somewhere else between the check and the connection. No proxy, no
 * redirects (a 3xx is a failure), a connect and a read timeout, an overall deadline for the whole try
 * (application.webhooks.attempt-timeout) enforced by closing the connection, and only the status line is read back.
 */
public class WebhookTransport {

    /** The most bytes of the response's status line and headers read. */
    static final int MAX_HEAD = 16 * 1024;

    /** Closes the sockets of tries that run past their deadline. */
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "webhook-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    private final WebhookAddressPolicy policy;
    private final SSLSocketFactory tls;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final Duration attemptTimeout;

    public WebhookTransport(WebhookAddressPolicy policy, SSLSocketFactory tls, Duration connectTimeout,
                            Duration readTimeout, Duration attemptTimeout) {
        this.policy = policy;
        this.tls = tls;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.attemptTimeout = attemptTimeout;
    }

    /** No HTTP answer within the try's overall deadline: the connection was closed. */
    public static class AttemptTimedOut extends IOException {
        AttemptTimedOut(Duration after) {
            super("no answer within " + after.toSeconds() + " seconds; the connection was closed");
        }
    }

    /** @return the HTTP status the endpoint answered with */
    public int post(String url, Map<String, String> headers, byte[] body) throws IOException, WebhookRefusedException {
        URI uri = policy.checkUrl(url);
        String host = uri.getHost();
        // One deadline for the whole try, the host lookup and every address included: the lookup is abandoned at it
        // (the system resolver has no timeout Java can set), and a watchdog closes the connection when it passes,
        // whatever the receiver is doing (a handshake or status line sent a byte at a time would otherwise keep each
        // read within its own timeout for hours).
        long deadline = System.nanoTime() + attemptTimeout.toNanos();
        List<InetAddress> addresses = policy.resolve(host, attemptTimeout);
        int port = uri.getPort() == -1 ? 443 : uri.getPort();
        String bareHost = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
        IOException last = null;
        for (InetAddress address : addresses) {
            long remaining = (deadline - System.nanoTime()) / 1_000_000;
            if (remaining <= 0) {
                throw new AttemptTimedOut(attemptTimeout);
            }
            Socket raw = new Socket();
            AtomicBoolean expired = new AtomicBoolean();
            ScheduledFuture<?> watchdog = WATCHDOG.schedule(() -> {
                expired.set(true);
                closeQuietly(raw);
            }, remaining, TimeUnit.MILLISECONDS);
            try {
                try {
                    raw.connect(new InetSocketAddress(address, port),
                            (int) Math.max(1, Math.min(connectTimeout.toMillis(), remaining)));
                } catch (IOException e) {
                    closeQuietly(raw);
                    if (expired.get()) {
                        throw new AttemptTimedOut(attemptTimeout);
                    }
                    last = e;
                    continue;
                }
                try (SSLSocket socket = (SSLSocket) tls.createSocket(raw, bareHost, port, true)) {
                    socket.setSoTimeout((int) readTimeout.toMillis());
                    SSLParameters parameters = socket.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    if (!isLiteral(bareHost)) {
                        parameters.setServerNames(List.of(new SNIHostName(bareHost)));
                    }
                    socket.setSSLParameters(parameters);
                    socket.startHandshake();
                    return exchange(socket, uri, port, headers, body);
                } catch (IOException e) {
                    if (expired.get()) {
                        throw new AttemptTimedOut(attemptTimeout);
                    }
                    throw e;
                }
            } finally {
                watchdog.cancel(false);
                closeQuietly(raw);
            }
        }
        throw last != null ? last : new IOException("no address to connect to");
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // closing
        }
    }

    private static int exchange(SSLSocket socket, URI uri, int port, Map<String, String> headers, byte[] body)
            throws IOException {
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) {
            path += "?" + uri.getRawQuery();
        }
        StringBuilder head = new StringBuilder();
        head.append("POST ").append(path).append(" HTTP/1.1\r\n");
        head.append("Host: ").append(uri.getHost()).append(port == 443 ? "" : ":" + port).append("\r\n");
        head.append("Content-Type: application/json\r\n");
        head.append("Content-Length: ").append(body.length).append("\r\n");
        head.append("User-Agent: ReadyRoom-Webhooks/1\r\n");
        head.append("Connection: close\r\n");
        for (Map.Entry<String, String> h : headers.entrySet()) {
            if (h.getKey().indexOf('\r') >= 0 || h.getKey().indexOf('\n') >= 0
                    || h.getValue().indexOf('\r') >= 0 || h.getValue().indexOf('\n') >= 0) {
                throw new IOException("a header holds a line break");
            }
            head.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
        }
        head.append("\r\n");
        OutputStream out = socket.getOutputStream();
        out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
        return status(socket.getInputStream());
    }

    /** The status code from {@code HTTP/1.1 200 OK}; the rest of the response is not read. */
    static int status(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1 && b != '\n') {
            if (line.size() >= MAX_HEAD) {
                throw new IOException("the response's status line is too long");
            }
            line.write(b);
        }
        String status = line.toString(StandardCharsets.US_ASCII).trim();
        String[] parts = status.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/")) {
            throw new IOException("not an HTTP response");
        }
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("not an HTTP status");
        }
    }

    private static boolean isLiteral(String host) {
        return host.indexOf(':') >= 0 || host.matches("[0-9.]+");
    }
}
