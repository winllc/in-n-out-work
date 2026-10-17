package com.winllc.innoutwork.audit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;

/**
 * Splunk HTTP Event Collector: {@code POST <url>/services/collector/event} with {@code Authorization: Splunk
 * <token>}, up to 100 events per request, each one
 * {@code {"time":<epoch seconds.millis>,"host":...,"source":"<product>","sourcetype":...,"index"?,"event":<event>}}.
 * TLS is checked against the JVM's trust store unless {@code ENS_AUDIT_SPLUNK_INSECURE} (test setups only).
 */
public class SplunkHecOutput implements AuditOutput {

    private final URI endpoint;
    private final String token;
    private final String index;
    private final String sourcetype;
    private final String product;
    private final String host;
    private final HttpClient client;

    public SplunkHecOutput(AuditProperties.Splunk settings, String product, String host) {
        String base = settings.getUrl().trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.endpoint = URI.create(base.endsWith("/services/collector/event") ? base : base + "/services/collector/event");
        this.token = token(settings);
        this.index = settings.getIndex() == null || settings.getIndex().isBlank() ? null : settings.getIndex().trim();
        this.sourcetype = settings.getSourcetype() == null || settings.getSourcetype().isBlank()
                ? "ens:audit" : settings.getSourcetype().trim();
        this.product = product;
        this.host = host;
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
        if (settings.isInsecure()) {
            builder.sslContext(trustAll());
        }
        this.client = builder.build();
    }

    static String token(AuditProperties.Splunk settings) {
        if (settings.getTokenFile() != null && !settings.getTokenFile().isBlank()) {
            try {
                return Files.readString(Path.of(settings.getTokenFile().trim()), StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read ENS_AUDIT_SPLUNK_TOKEN_FILE " + settings.getTokenFile()
                        + ": " + e.getMessage());
            }
        }
        return settings.getToken() == null ? "" : settings.getToken().trim();
    }

    @Override
    public String name() {
        return "splunk";
    }

    @Override
    public String describe() {
        return endpoint.toString() + (index != null ? " (index " + index + ")" : "");
    }

    @Override
    public void send(List<AuditEventRecord> events) throws Exception {
        if (events.isEmpty()) {
            return;
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Splunk " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload(events), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            String body = response.body() == null ? "" : response.body();
            throw new IOException("Splunk HEC answered " + response.statusCode() + ": "
                    + (body.length() > 200 ? body.substring(0, 200) : body));
        }
    }

    /** The events as HEC's batch format: the envelopes concatenated, one per line. */
    String payload(List<AuditEventRecord> events) {
        StringBuilder b = new StringBuilder();
        for (AuditEventRecord e : events) {
            long millis = e.getTime().toEpochMilli();
            b.append("{\"time\":").append(Math.floorDiv(millis, 1000)).append('.')
                    .append(String.format("%03d", Math.floorMod(millis, 1000)))
                    .append(",\"host\":").append(AuditJson.quote(host))
                    .append(",\"source\":").append(AuditJson.quote(product))
                    .append(",\"sourcetype\":").append(AuditJson.quote(sourcetype));
            if (index != null) {
                b.append(",\"index\":").append(AuditJson.quote(index));
            }
            b.append(",\"event\":").append(e.getLine()).append("}\n");
        }
        return b.toString();
    }

    private static SSLContext trustAll() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, new SecureRandom());
            return context;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
