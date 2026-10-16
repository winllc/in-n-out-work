package com.winllc.innoutwork.audit;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Syslog, RFC 5424: {@code <PRI>1 TIMESTAMP HOST APP-NAME - MSGID - MSG}, where APP-NAME is the product id, MSGID
 * the action, PRI the facility and the event's severity (info 6, notice 5, warning 4, alert 1), no structured
 * data, and MSG the event's JSON line. Over tcp and tls each message is octet-counted ({@code LEN SP MSG},
 * RFC 6587 / RFC 5425); over udp it is one datagram. TLS uses the JVM's trust store.
 */
public class SyslogOutput implements AuditOutput {

    static final Map<String, Integer> FACILITIES = Map.ofEntries(
            Map.entry("kern", 0), Map.entry("user", 1), Map.entry("mail", 2), Map.entry("daemon", 3),
            Map.entry("auth", 4), Map.entry("syslog", 5), Map.entry("lpr", 6), Map.entry("news", 7),
            Map.entry("uucp", 8), Map.entry("cron", 9), Map.entry("authpriv", 10), Map.entry("ftp", 11),
            Map.entry("local0", 16), Map.entry("local1", 17), Map.entry("local2", 18), Map.entry("local3", 19),
            Map.entry("local4", 20), Map.entry("local5", 21), Map.entry("local6", 22), Map.entry("local7", 23));

    private final String host;
    private final int port;
    private final String protocol;
    private final int facility;
    private final String appName;
    private final String hostname;
    private Socket socket;
    private OutputStream out;

    public SyslogOutput(AuditProperties.Syslog settings, String product, String hostname) {
        this.host = settings.getHost().trim();
        this.port = settings.effectivePort();
        this.protocol = settings.getProtocol() == null ? "tcp" : settings.getProtocol().trim().toLowerCase();
        if (!List.of("udp", "tcp", "tls").contains(protocol)) {
            throw new IllegalArgumentException("ENS_AUDIT_SYSLOG_PROTOCOL must be udp, tcp or tls, not " + protocol);
        }
        Integer f = FACILITIES.get(settings.getFacility() == null ? "local0" : settings.getFacility().trim().toLowerCase());
        if (f == null) {
            throw new IllegalArgumentException("Unknown ENS_AUDIT_SYSLOG_FACILITY " + settings.getFacility());
        }
        this.facility = f;
        this.appName = product;
        this.hostname = hostname == null || hostname.isBlank() ? "-" : hostname.replace(' ', '_');
    }

    @Override
    public String name() {
        return "syslog";
    }

    @Override
    public String describe() {
        return protocol + "://" + host + ":" + port;
    }

    @Override
    public synchronized void send(List<AuditEventRecord> events) throws Exception {
        if (events.isEmpty()) {
            return;
        }
        if ("udp".equals(protocol)) {
            try (DatagramSocket udp = new DatagramSocket()) {
                InetAddress address = InetAddress.getByName(host);
                for (AuditEventRecord e : events) {
                    byte[] bytes = message(e).getBytes(StandardCharsets.UTF_8);
                    udp.send(new DatagramPacket(bytes, bytes.length, address, port));
                }
            }
            return;
        }
        try {
            OutputStream stream = stream();
            for (AuditEventRecord e : events) {
                stream.write(frame(message(e)));
            }
            stream.flush();
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    /** The RFC 5424 message for one event. */
    String message(AuditEventRecord e) {
        int severity = AuditEntry.Severity.valueOf(e.getSeverity().toUpperCase()).syslog();
        int pri = facility * 8 + severity;
        return "<" + pri + ">1 " + AuditJson.time(e.getTime()) + " " + hostname + " " + appName + " - "
                + msgId(e.getAction()) + " - " + e.getLine();
    }

    /** Octet counting: the message's length in bytes, a space, the message. */
    static byte[] frame(String message) {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        byte[] prefix = (bytes.length + " ").getBytes(StandardCharsets.US_ASCII);
        byte[] framed = new byte[prefix.length + bytes.length];
        System.arraycopy(prefix, 0, framed, 0, prefix.length);
        System.arraycopy(bytes, 0, framed, prefix.length, bytes.length);
        return framed;
    }

    /** MSGID is at most 32 printable ASCII characters. */
    private static String msgId(String action) {
        String id = action.replaceAll("[^\\x21-\\x7e]", "_");
        return id.length() > 32 ? id.substring(0, 32) : id;
    }

    private OutputStream stream() throws IOException {
        if (socket == null || socket.isClosed() || out == null) {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host, port), 10_000);
            s.setSoTimeout(30_000);
            if ("tls".equals(protocol)) {
                SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                        .createSocket(s, host, port, true);
                SSLParameters parameters = tls.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS"); // check the certificate names the host
                tls.setSSLParameters(parameters);
                tls.startHandshake();
                s = tls;
            }
            socket = s;
            out = s.getOutputStream();
        }
        return out;
    }

    private void close() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // already broken
        }
        socket = null;
        out = null;
    }
}
