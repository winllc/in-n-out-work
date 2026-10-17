package com.winllc.innoutwork.audit;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The forwarding outputs against local stand-ins: a stub Splunk HTTP Event Collector, and a TCP syslog listener.
 * Payload shape and auth header, retry after a failure, the persisted cursor, the failure alert, RFC 5424 framing.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:auditforward;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "ens.audit.stdout=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AuditForwardingTest {

    private static final String HEC_TOKEN = "11111111-2222-3333-4444-555555555555";

    @Configuration
    @Import(AuditStoreSupport.Store.class)
    static class Config {
    }

    @Autowired AuditService audit;
    @Autowired AuditQueryService query;
    @Autowired AuditOutputStateRepository states;
    @Autowired AuditEventRepository events;
    @Autowired JdbcTemplate jdbc;

    private HttpServer hec;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();
    private final AtomicInteger failuresLeft = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        jdbc.update("DELETE FROM audit_events");
        jdbc.update("DELETE FROM audit_chain_head");
        jdbc.update("DELETE FROM audit_output_state");
        hec = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        hec.createContext("/services/collector/event", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            int status;
            String reply;
            if (failuresLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                status = 503;
                reply = "{\"text\":\"Server is busy\",\"code\":9}";
            } else {
                bodies.add(new String(body, StandardCharsets.UTF_8));
                status = 200;
                reply = "{\"text\":\"Success\",\"code\":0}";
            }
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        hec.start();
    }

    @AfterEach
    void tearDown() {
        hec.stop(0);
    }

    private AuditProperties.Splunk splunk() {
        AuditProperties.Splunk s = new AuditProperties.Splunk();
        s.setUrl("http://127.0.0.1:" + hec.getAddress().getPort() + "/");
        s.setToken(HEC_TOKEN);
        s.setIndex("security");
        return s;
    }

    private AuditForwarder forwarder(AuditOutput output, Clock clock) {
        return new AuditForwarder(output, audit, query, states, Duration.ofMinutes(15), 1000, clock);
    }

    private void record(int n) {
        for (int i = 0; i < n; i++) {
            audit.record(AuditEntry.of(AuditEntry.Category.SYSTEM, "job.completed")
                    .actor(AuditEntry.Actor.system()).detail("n", i));
        }
    }

    @Test
    void hecGetsBatchesOfEnvelopesWithTheSplunkAuthHeader() throws Exception {
        record(150);
        AuditForwarder forwarder = forwarder(new SplunkHecOutput(splunk(), "readyroom", "app-1"), Clock.systemUTC());

        assertEquals(100, forwarder.deliverOnce(), "batches of up to 100");
        assertEquals(50, forwarder.deliverOnce());
        assertEquals(0, forwarder.deliverOnce());

        assertEquals(List.of("Splunk " + HEC_TOKEN, "Splunk " + HEC_TOKEN), authHeaders);
        String[] envelopes = bodies.getFirst().split("\n");
        assertEquals(100, envelopes.length);
        JsonNode first = new ObjectMapper().readTree(envelopes[0]);
        List<String> keys = new java.util.ArrayList<>();
        first.properties().forEach(e -> keys.add(e.getKey()));
        assertEquals(List.of("time", "host", "source", "sourcetype", "index", "event"), keys);
        assertTrue(envelopes[0].matches("\\{\"time\":\\d+\\.\\d{3},.*"), envelopes[0]);
        assertEquals("app-1", first.get("host").asString());
        assertEquals("readyroom", first.get("source").asString());
        assertEquals("ens:audit", first.get("sourcetype").asString());
        assertEquals("security", first.get("index").asString());
        AuditEventRecord stored = events.findById(1L).orElseThrow();
        assertEquals(stored.getLine(), first.get("event").toString(), "the event is the stored line, verbatim");
        assertEquals(stored.getTime().toEpochMilli() / 1000.0, first.get("time").asDouble(), 0.0005);

        assertEquals(150, states.findById("splunk").orElseThrow().getLastSeq(), "the cursor is persisted");
        assertFalse(String.join("", bodies).contains(HEC_TOKEN), "the token is never in the payload");
    }

    @Test
    void aFailedBatchIsRetriedAndTheCursorOnlyMovesOnSuccess() throws Exception {
        record(3);
        failuresLeft.set(1);
        AuditForwarder forwarder = forwarder(new SplunkHecOutput(splunk(), "readyroom", "h"), Clock.systemUTC());

        Exception refused = assertThrows(Exception.class, forwarder::deliverOnce);
        assertTrue(refused.getMessage().contains("503"), refused.getMessage());
        AuditOutputState failed = states.findById("splunk").orElseThrow();
        assertEquals(0, failed.getLastSeq());
        assertNotNull(failed.getFailingSince());
        assertTrue(failed.getLastError().contains("503"));

        assertEquals(3, forwarder.deliverOnce());
        AuditOutputState ok = states.findById("splunk").orElseThrow();
        assertEquals(3, ok.getLastSeq());
        assertNull(ok.getFailingSince());
        assertEquals(1, bodies.size());

        // A restart picks up from the persisted cursor: nothing is sent twice.
        AuditForwarder restarted = forwarder(new SplunkHecOutput(splunk(), "readyroom", "h"), Clock.systemUTC());
        assertEquals(0, restarted.deliverOnce());
    }

    @Test
    void anOutputFailingForOverFifteenMinutesRecordsOneAlertAndThenItsRecovery() throws Exception {
        record(1);
        failuresLeft.set(3);
        Instant start = Instant.now();
        MutableClock clock = new MutableClock(start);
        AuditForwarder forwarder = forwarder(new SplunkHecOutput(splunk(), "readyroom", "h"), clock);

        assertThrows(Exception.class, forwarder::deliverOnce);
        clock.now = start.plus(Duration.ofMinutes(16));
        assertThrows(Exception.class, forwarder::deliverOnce);
        clock.now = start.plus(Duration.ofMinutes(20));
        assertThrows(Exception.class, forwarder::deliverOnce);

        List<AuditEventRecord> alerts = events.findAll().stream()
                .filter(e -> e.getAction().equals("audit.delivery-failed")).toList();
        assertEquals(1, alerts.size(), "one alert per run of failures");
        assertEquals("alert", alerts.getFirst().getSeverity());
        assertTrue(alerts.getFirst().getLine().contains("\"output\":\"splunk\""));

        forwarder.deliverOnce();
        assertTrue(events.findAll().stream().anyMatch(e -> e.getAction().equals("audit.delivery-recovered")));
    }

    @Test
    void syslogOverTcpIsRfc5424WithOctetCounting() throws Exception {
        audit.record(AuditEntry.of(AuditEntry.Category.AUTHENTICATION, "auth.login").failure("bad credentials")
                .actor(AuditEntry.Actor.anonymous("jdoe")));
        audit.record(AuditEntry.of(AuditEntry.Category.CONFIGURATION, "settings.changed"));

        try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            AuditProperties.Syslog settings = new AuditProperties.Syslog();
            settings.setHost("127.0.0.1");
            settings.setPort(server.getLocalPort());
            settings.setProtocol("tcp");
            AuditForwarder forwarder = forwarder(new SyslogOutput(settings, "readyroom", "app-1"), Clock.systemUTC());

            assertEquals(2, forwarder.deliverOnce());

            try (Socket client = server.accept()) {
                client.setSoTimeout(5000);
                DataInputStream in = new DataInputStream(client.getInputStream());
                String first = readFrame(in);
                String second = readFrame(in);

                Pattern rfc5424 = Pattern.compile(
                        "^<(\\d+)>1 (\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z) app-1 readyroom - (\\S+) - (\\{.*})$");
                Matcher m = rfc5424.matcher(first);
                assertTrue(m.matches(), first);
                assertEquals(String.valueOf(16 * 8 + 4), m.group(1), "local0, warning");
                assertEquals("auth.login", m.group(3));
                assertEquals(events.findById(1L).orElseThrow().getLine(), m.group(4));

                Matcher m2 = rfc5424.matcher(second);
                assertTrue(m2.matches(), second);
                assertEquals(String.valueOf(16 * 8 + 5), m2.group(1), "local0, notice");
                assertEquals("settings.changed", m2.group(3));
            }
            assertEquals(2, states.findById("syslog").orElseThrow().getLastSeq());
        }
    }

    @Test
    void aPasswordAndATokenNeverReachAnySink(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        String password = "Pa55word-never-logged";
        String token = "ensat_" + "Q".repeat(43);
        AuditProperties fileOnly = new AuditProperties();
        fileOnly.setStdout(true);
        fileOnly.setFile(dir.resolve("audit.jsonl").toString());
        AuditOutputs.configureLogger(fileOnly);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> stdout =
                AuditStoreSupport.captureAuditLines();
        try {
            audit.record(AuditEntry.of(AuditEntry.Category.ACCOUNT, "account.password-changed")
                    .change("password", password, password + "2")
                    .detail("token", token)
                    .detail("apiKey", token)
                    .detail("note", "pasted " + token + " by mistake")
                    .reason("token was " + token));

            List<String> sinks = new java.util.ArrayList<>();
            events.findAll().forEach(e -> sinks.add(e.getLine()));
            sinks.addAll(AuditStoreSupport.lines(stdout));
            sinks.add(java.nio.file.Files.readString(dir.resolve("audit.jsonl")));
            AuditForwarder splunk = forwarder(new SplunkHecOutput(splunk(), "readyroom", "h"), Clock.systemUTC());
            splunk.deliverOnce();
            sinks.addAll(bodies);
            AuditProperties.Syslog settings = new AuditProperties.Syslog();
            settings.setHost("127.0.0.1");
            settings.setProtocol("tcp");
            SyslogOutput syslog = new SyslogOutput(settings, "readyroom", "h");
            events.findAll().forEach(e -> sinks.add(syslog.message(e)));

            assertEquals(5, sinks.size());
            for (String sink : sinks) {
                assertFalse(sink.contains(password), sink);
                assertFalse(sink.contains(token), sink);
                assertTrue(sink.contains("[redacted]"), sink);
            }
        } finally {
            AuditStoreSupport.release(stdout);
            AuditOutputs.configureLogger(new AuditProperties());
        }
    }

    @Test
    void severitiesMapToSyslogLevels() {
        assertEquals(6, AuditEntry.Severity.INFO.syslog());
        assertEquals(5, AuditEntry.Severity.NOTICE.syslog());
        assertEquals(4, AuditEntry.Severity.WARNING.syslog());
        assertEquals(1, AuditEntry.Severity.ALERT.syslog());
    }

    /** One octet-counted frame: digits, a space, then exactly that many bytes. */
    private static String readFrame(InputStream in) throws IOException {
        ByteArrayOutputStream digits = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != ' ') {
            assertTrue(c >= '0' && c <= '9', "frame length digits, got " + c);
            digits.write(c);
        }
        int length = Integer.parseInt(digits.toString(StandardCharsets.US_ASCII));
        byte[] message = new DataInputStream(in).readNBytes(length);
        assertEquals(length, message.length);
        return new String(message, StandardCharsets.UTF_8);
    }

    static final class MutableClock extends Clock {
        volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
