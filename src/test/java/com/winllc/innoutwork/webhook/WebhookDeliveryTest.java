package com.winllc.innoutwork.webhook;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import com.winllc.innoutwork.audit.AuditEventRepository;
import com.winllc.innoutwork.audit.AuditLog;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.WebhookDelivery;
import com.winllc.innoutwork.model.WebhookEndpoint;
import com.winllc.innoutwork.repository.WebhookDeliveryRepository;
import com.winllc.innoutwork.repository.WebhookEndpointRepository;
import com.winllc.innoutwork.security.AccessPolicy;
import com.winllc.innoutwork.security.AppUserDetailsService;
import com.winllc.innoutwork.service.CheckInOutService;
import com.winllc.innoutwork.service.InvalidRequestException;
import com.winllc.innoutwork.support.FullApplicationTest;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Signed webhooks end to end, against a real HTTPS receiver on this machine: queued only once the change commits,
 * signed with HMAC-SHA256 over the timestamp and body, retried with backoff, logged, and never sent to an address
 * the SSRF guard refuses at send time (DNS rebinding included). The signing secret is shown once and kept out of
 * the database in clear, the session, the log and the audit trail.
 */
@ExtendWith(OutputCaptureExtension.class)
class WebhookDeliveryTest extends FullApplicationTest {

    @Autowired WebhookService webhooks;
    @Autowired WebhookEndpointRepository endpoints;
    @Autowired WebhookDeliveryRepository deliveries;
    @Autowired ApplicationProperties properties;
    @Autowired AccessPolicy accessPolicy;
    @Autowired AppUserDetailsService users;
    @Autowired AuditLog auditLog;
    @Autowired AuditEventRepository audit;
    @Autowired PlatformTransactionManager transactions;
    @Autowired CheckInOutService checkIns;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mockMvc;

    private HttpsServer server;
    private SSLContext clientTls;
    private final ConcurrentLinkedQueue<Integer> answers = new ConcurrentLinkedQueue<>();
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
    /** Where the test's resolver sends rebind.test: public when the webhook is saved, loopback afterwards. */
    private final AtomicInteger rebindLookups = new AtomicInteger();

    record Received(Map<String, List<String>> headers, byte[] body) {
    }

    private static UsernamePasswordAuthenticationToken admin() {
        return UsernamePasswordAuthenticationToken.authenticated(ADMIN, null,
                List.of(new SimpleGrantedAuthority("ADMIN"), new SimpleGrantedAuthority("USER")));
    }

    @BeforeEach
    void receiver() throws Exception {
        // The sender's clock runs a little ahead, so what the context's service queues during the test is due.
        now.set(Instant.now().plusSeconds(10));
        deliveries.deleteAll();
        endpoints.deleteAll();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(256);
        KeyPair keys = generator.generateKeyPair();
        Instant t = Instant.now();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(new X500Name("CN=localhost"),
                BigInteger.valueOf(t.toEpochMilli()), Date.from(t.minus(Duration.ofDays(1))),
                Date.from(t.plus(Duration.ofDays(1))), new X500Name("CN=localhost"), keys.getPublic());
        builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName[]{
                new GeneralName(GeneralName.dNSName, "localhost"), new GeneralName(GeneralName.dNSName, "rebind.test"),
                new GeneralName(GeneralName.iPAddress, "127.0.0.1")}));
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(keys.getPrivate())));

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("server", keys.getPrivate(), "pw".toCharArray(), new X509Certificate[]{cert});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, "pw".toCharArray());
        SSLContext serverTls = SSLContext.getInstance("TLS");
        serverTls.init(kmf.getKeyManagers(), null, null);

        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("server", cert);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        clientTls = SSLContext.getInstance("TLS");
        clientTls.init(null, tmf.getTrustManagers(), null);

        server = HttpsServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverTls));
        server.createContext("/hook", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            received.add(new Received(Map.copyOf(exchange.getRequestHeaders()), body));
            Integer answer = answers.poll();
            int code = answer == null ? 204 : answer;
            exchange.sendResponseHeaders(code, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String url(String host) {
        return "https://" + host + ":" + server.getAddress().getPort() + "/hook";
    }

    /** A sender over the context's database, trusting the test receiver, with its own clock and resolver. */
    private WebhookService sender(List<String> allowed) {
        WebhookAddressPolicy policy = new WebhookAddressPolicy(allowed.stream().map(Cidr::parse).toList(), host -> {
            if (host.equals("rebind.test")) {
                return new InetAddress[]{InetAddress.getByName(rebindLookups.getAndIncrement() == 0
                        ? "93.184.216.34" : "127.0.0.1")};
            }
            if (host.equals("localhost")) {
                return new InetAddress[]{InetAddress.getByName("127.0.0.1")};
            }
            throw new UnknownHostException(host);
        });
        WebhookTransport transport = new WebhookTransport(policy, clientTls.getSocketFactory(),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        return new WebhookService(endpoints, deliveries, policy, transport, properties, accessPolicy, users,
                auditLog, transactions, clock);
    }

    private CheckInOutRecord checkIn() {
        CheckInOutRecord record = new CheckInOutRecord();
        record.setDn(BOB);
        record.setAction(CheckInOutEnum.CHECK_IN);
        record.setTimestamp(ZonedDateTime.now());
        return record;
    }

    @Test
    void aStatusChangeIsQueuedAfterCommitSignedDeliveredAndRetried(CapturedOutput output) {
        WebhookService sender = sender(List.of("127.0.0.0/8"));
        WebhookService.Created created = sender.create("HR", url("localhost"),
                List.of(WebhookEventType.STATUS_CHANGED), admin());
        String secret = created.secret();
        assertTrue(secret.startsWith(WebhookService.SECRET_PREFIX));

        // Rolled back: nothing is queued. Committed: one delivery.
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            checkIns.saveCheckInOutRecord(checkIn());
            s.setRollbackOnly();
        });
        // The context's own service queues (its cache of endpoints is refreshed for the test).
        webhooks.activeEndpointsForTest();
        assertEquals(0, deliveries.count(), "a rolled-back change sends nothing");
        checkIns.saveCheckInOutRecord(checkIn());
        assertEquals(1, deliveries.count());

        // First try: the receiver fails; retried 30 seconds later, then delivered.
        answers.add(503);
        assertEquals(1, sender.deliverDue());
        WebhookDelivery d = deliveries.findAll().getFirst();
        assertEquals(WebhookDelivery.State.PENDING, d.getState());
        assertEquals(1, d.getAttempts());
        assertEquals(503, d.getLastStatus());
        assertEquals(0, sender.deliverDue(), "not due yet");
        now.set(now.get().plusSeconds(31));
        assertEquals(1, sender.deliverDue());
        d = deliveries.findAll().getFirst();
        assertEquals(WebhookDelivery.State.DELIVERED, d.getState());
        assertEquals(2, d.getAttempts());

        Received last = received.getLast();
        String signature = last.headers().get("X-readyroom-signature").getFirst();
        long timestamp = Long.parseLong(last.headers().get("X-readyroom-timestamp").getFirst());
        assertTrue(WebhookSignature.verify(secret, signature, last.body(), timestamp, 300), signature);
        assertFalse(WebhookSignature.verify("whsec_wrong", signature, last.body(), timestamp, 300));
        String body = new String(last.body(), StandardCharsets.UTF_8);
        assertTrue(body.contains("\"type\":\"status.changed\"") && body.contains("\"status\":\"IN\"")
                && body.contains("Bob Barker"), body);
        assertEquals("status.changed", last.headers().get("X-readyroom-event").getFirst());

        // The secret: encrypted at rest, never logged or audited.
        String stored = jdbc.queryForObject("select secret_ciphertext from webhook_endpoints where id = ?",
                String.class, created.endpoint().getId());
        assertFalse(stored.contains(secret.substring(WebhookService.SECRET_PREFIX.length())));
        assertFalse(output.getAll().contains(secret), "the secret is in the log");
        assertTrue(audit.findAll().stream().noneMatch(a -> a.getLine().contains(secret)));
        assertTrue(audit.findAll().stream().anyMatch(a -> a.getAction().equals("webhook.created")));
    }

    @Test
    void afterTheLastTryADeliveryFails() {
        WebhookService sender = sender(List.of("127.0.0.0/8"));
        sender.create("HR", url("localhost"), List.of(WebhookEventType.ABSENCE_ALERT), admin());
        webhooks.activeEndpointsForTest();
        webhooks.onEvent(WebhookEvent.of(WebhookEventType.ABSENCE_ALERT, Map.of("person", Map.of("dn", BOB))));
        for (int i = 0; i < properties.getWebhooks().getMaxAttempts(); i++) {
            answers.add(500);
            assertEquals(1, sender.deliverDue(), "try " + (i + 1));
            now.set(now.get().plus(Duration.ofHours(9)));
        }
        WebhookDelivery d = deliveries.findAll().getFirst();
        assertEquals(WebhookDelivery.State.FAILED, d.getState());
        assertEquals(properties.getWebhooks().getMaxAttempts(), d.getAttempts());
        assertEquals(0, sender.deliverDue());
        WebhookEndpoint endpoint = endpoints.findAll().getFirst();
        assertNotNull(endpoint.getLastFailureAt());
    }

    @Test
    void aNameThatRebindsToAnInternalAddressIsNotSentTo() {
        // Allow-lists nothing: rebind.test is public when saved, then resolves to 127.0.0.1.
        WebhookService sender = sender(List.of());
        sender.create("Rebinding", url("rebind.test"), List.of(WebhookEventType.STATUS_CHANGED), admin());
        webhooks.activeEndpointsForTest();
        webhooks.onEvent(WebhookEvent.of(WebhookEventType.STATUS_CHANGED, Map.of("x", 1)));
        assertEquals(1, sender.deliverDue());
        WebhookDelivery d = deliveries.findAll().getFirst();
        assertEquals(WebhookDelivery.State.PENDING, d.getState());
        assertTrue(d.getLastError().contains("127.0.0.1"), d.getLastError());
        assertTrue(received.isEmpty(), "nothing reached the internal address");
    }

    @Test
    void internalAndMetadataAddressesAreRefusedWhenSaved() {
        for (String url : List.of("http://hooks.example.com/x", "https://127.0.0.1/x", "https://[::1]/x",
                "https://169.254.169.254/latest/meta-data", "https://10.1.2.3/x", "https://[::ffff:127.0.0.1]/x",
                "https://[fd00:ec2::254]/x", "https://user:pw@hooks.example.com/x")) {
            assertThrows(InvalidRequestException.class,
                    () -> webhooks.create("bad", url, List.of(WebhookEventType.STATUS_CHANGED), admin()), url);
        }
        assertEquals(0, endpoints.count());
        UsernamePasswordAuthenticationToken notAdmin = UsernamePasswordAuthenticationToken.authenticated(ALICE, null,
                List.of(new SimpleGrantedAuthority("USER")));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> webhooks.create("x", "https://hooks.example.com/x", List.of(WebhookEventType.PING), notAdmin));
    }

    @Test
    void nothingIsQueuedForAnEndpointWhoseCreatorIsNoLongerAnAdministrator() {
        WebhookService sender = sender(List.of("127.0.0.0/8"));
        WebhookEndpoint endpoint = sender.create("HR", url("localhost"), List.of(WebhookEventType.STATUS_CHANGED),
                admin()).endpoint();
        endpoint.setOwnerDn(ALICE);
        endpoints.save(endpoint);
        webhooks.activeEndpointsForTest();
        webhooks.onEvent(WebhookEvent.of(WebhookEventType.STATUS_CHANGED, Map.of("x", 1)));
        assertEquals(0, deliveries.count());
    }

    @Test
    void theSecretIsShownOnceOnThePageAndNeverPutInTheSession() throws Exception {
        // A public address literal: checked without DNS.
        MvcResult page = mockMvc.perform(post("/app/admin/integrations/webhooks")
                        .with(user(ADMIN).authorities(new SimpleGrantedAuthority("ADMIN"), new SimpleGrantedAuthority("USER")))
                        .with(csrf()).param("name", "Example").param("url", "https://93.184.216.34/readyroom")
                        .param("event", "status.changed"))
                .andExpect(status().isOk()).andReturn();
        Object secret = page.getModelAndView().getModel().get("newSecret");
        assertNotNull(secret, String.valueOf(page.getModelAndView().getModel().get("error")));
        assertTrue(page.getResponse().getContentAsString().contains((String) secret));
        assertTrue(page.getFlashMap().isEmpty());
        for (byte[] stored : jdbc.queryForList("select attribute_bytes from spring_session_attributes", byte[].class)) {
            assertFalse(new String(stored, StandardCharsets.ISO_8859_1).contains((String) secret));
        }
    }
}
