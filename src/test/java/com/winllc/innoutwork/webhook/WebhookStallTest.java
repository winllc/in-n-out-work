package com.winllc.innoutwork.webhook;

import com.winllc.innoutwork.audit.AuditLog;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.model.WebhookDelivery;
import com.winllc.innoutwork.model.WebhookEndpoint;
import com.winllc.innoutwork.repository.WebhookDeliveryRepository;
import com.winllc.innoutwork.repository.WebhookEndpointRepository;
import com.winllc.innoutwork.security.AccessPolicy;
import com.winllc.innoutwork.security.DirectoryAccounts;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.PlatformTransactionManager;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A receiver that answers slowly, a byte at a time, or not at all cannot hold the sender: each try ends at its
 * deadline, an endpoint that gave no answer gets nothing more in that run, others are still served, and a claimed
 * delivery is never sent twice, even by two senders at once.
 */
class WebhookStallTest extends FullApplicationTest {

    private static final Duration ATTEMPT = Duration.ofSeconds(2);

    @Autowired WebhookEndpointRepository endpoints;
    @Autowired WebhookDeliveryRepository deliveries;
    @Autowired AccessPolicy accessPolicy;
    @Autowired DirectoryAccounts accounts;
    @Autowired AuditLog auditLog;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ApplicationProperties context;

    private SSLContext serverTls;
    private SSLContext clientTls;
    private final List<ServerSocket> servers = new CopyOnWriteArrayList<>();
    private final ExecutorService threads = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "stall-test");
        t.setDaemon(true);
        return t;
    });
    /** Delivery ids the live receiver got, with how often. */
    private final Map<String, AtomicInteger> received = new ConcurrentHashMap<>();

    @BeforeEach
    void tls() throws Exception {
        deliveries.deleteAll();
        endpoints.deleteAll();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(256);
        KeyPair keys = generator.generateKeyPair();
        Instant t = Instant.now();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(new X500Name("CN=localhost"),
                BigInteger.valueOf(t.toEpochMilli()), Date.from(t.minus(Duration.ofDays(1))),
                Date.from(t.plus(Duration.ofDays(1))), new X500Name("CN=localhost"), keys.getPublic());
        builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(
                new GeneralName(GeneralName.dNSName, "localhost")));
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(keys.getPrivate())));
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("s", keys.getPrivate(), "pw".toCharArray(), new X509Certificate[]{cert});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "pw".toCharArray());
        serverTls = SSLContext.getInstance("TLS");
        serverTls.init(kmf.getKeyManagers(), null, null);
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("s", cert);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        clientTls = SSLContext.getInstance("TLS");
        clientTls.init(null, tmf.getTrustManagers(), null);
    }

    @AfterEach
    void stop() {
        servers.forEach(s -> {
            try {
                s.close();
            } catch (IOException ignored) {
                // closing
            }
        });
        threads.shutdownNow();
    }

    // --- receivers ---------------------------------------------------------------------------------------------

    /** Accepts connections and hands each to {@code handler} on its own thread. */
    private int serve(ServerSocket server, SocketHandler handler) {
        servers.add(server);
        threads.submit(() -> {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    threads.submit(() -> {
                        try (socket) {
                            handler.handle(socket);
                        } catch (Exception ignored) {
                            // the client closed it
                        }
                    });
                } catch (IOException e) {
                    return;
                }
            }
        });
        return server.getLocalPort();
    }

    interface SocketHandler {
        void handle(Socket socket) throws Exception;
    }

    /** Plain TCP: the start of a TLS record, then one byte every 300 ms, forever. */
    private int drippingHandshake() throws IOException {
        return serve(new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")), socket -> {
            OutputStream out = socket.getOutputStream();
            out.write(new byte[]{0x16, 0x03, 0x03, 0x40, 0x00});
            out.flush();
            while (true) {
                Thread.sleep(300);
                out.write(0);
                out.flush();
            }
        });
    }

    private SSLServerSocket tlsServer() throws IOException {
        return (SSLServerSocket) serverTls.getServerSocketFactory().createServerSocket(0, 50,
                InetAddress.getByName("127.0.0.1"));
    }

    private static String readRequest(InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            head.append((char) b);
            if (head.toString().endsWith("\r\n\r\n")) {
                break;
            }
        }
        Matcher length = Pattern.compile("(?i)Content-Length: (\\d+)").matcher(head);
        if (length.find()) {
            in.readNBytes(Integer.parseInt(length.group(1)));
        }
        return head.toString();
    }

    /** TLS, reads the request, then sends its status line a character every 300 ms, never finishing it. */
    private int drippingStatusLine() throws IOException {
        return serve(tlsServer(), socket -> {
            readRequest(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            for (char c : "HTTP/1.1 200".toCharArray()) {
                out.write(c);
                out.flush();
                Thread.sleep(300);
            }
            while (true) {
                out.write(' ');
                out.flush();
                Thread.sleep(300);
            }
        });
    }

    /** TLS, answers 204 after {@code delayMillis}, and counts each delivery id it gets. */
    private int live(long delayMillis) throws IOException {
        return serve(tlsServer(), socket -> {
            String request = readRequest(socket.getInputStream());
            Matcher id = Pattern.compile("(?i)X-ReadyRoom-Delivery: (\\S+)").matcher(request);
            if (id.find()) {
                received.computeIfAbsent(id.group(1), k -> new AtomicInteger()).incrementAndGet();
            }
            Thread.sleep(delayMillis);
            socket.getOutputStream().write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
        });
    }

    // --- the sender ------------------------------------------------------------------------------------------

    /** Released at the end of a test: lookups of hang*.test wait for it, deaf to interruption. */
    private final java.util.concurrent.CountDownLatch hang = new java.util.concurrent.CountDownLatch(1);

    @AfterEach
    void releaseLookups() {
        hang.countDown();
    }

    private WebhookService sender(Duration runBudget) {
        return sender(runBudget, Duration.ofSeconds(20));
    }

    private WebhookService sender(Duration runBudget, Duration perEndpointBudget) {
        ApplicationProperties settings = new ApplicationProperties();
        settings.getWebhooks().setSecretKey(context.getWebhooks().getSecretKey());
        settings.getWebhooks().setAllowedCidrs(List.of("127.0.0.0/8"));
        settings.getWebhooks().setReadTimeout(Duration.ofSeconds(1));
        settings.getWebhooks().setAttemptTimeout(ATTEMPT);
        settings.getWebhooks().setRunBudget(runBudget);
        settings.getWebhooks().setPerEndpointBudget(perEndpointBudget);
        WebhookAddressPolicy policy = new WebhookAddressPolicy(List.of(Cidr.parse("127.0.0.0/8")), host -> {
            if (host.startsWith("hang")) {
                while (hang.getCount() > 0) {
                    try {
                        hang.await();
                    } catch (InterruptedException ignored) {
                        // a hung resolver does not notice
                    }
                }
                throw new UnknownHostException(host);
            }
            if (host.equals("localhost")) {
                return new InetAddress[]{InetAddress.getByName("127.0.0.1")};
            }
            throw new UnknownHostException(host);
        });
        WebhookTransport transport = new WebhookTransport(policy, clientTls.getSocketFactory(), Duration.ofSeconds(2),
                Duration.ofSeconds(1), ATTEMPT);
        return new WebhookService(endpoints, deliveries, policy, transport, settings, accessPolicy, accounts, auditLog,
                transactions, Clock.systemUTC());
    }

    private WebhookEndpoint endpoint(WebhookService sender, int port) {
        return sender.create("receiver " + port, "https://localhost:" + port + "/hook",
                List.of(WebhookEventType.STATUS_CHANGED), UsernamePasswordAuthenticationToken.authenticated(ADMIN, null,
                        List.of(new SimpleGrantedAuthority("ADMIN")))).endpoint();
    }

    private WebhookDelivery queue(WebhookEndpoint endpoint, Instant due) {
        WebhookDelivery d = new WebhookDelivery();
        d.setEndpointId(endpoint.getId());
        d.setEventId(UUID.randomUUID().toString());
        d.setEventType("status.changed");
        d.setPayload("{\"id\":\"x\"}");
        d.setState(WebhookDelivery.State.PENDING);
        d.setAttempts(0);
        d.setCreatedAt(due);
        d.setNextAttemptAt(due);
        return deliveries.save(d);
    }

    private static long timed(Runnable run) {
        long start = System.nanoTime();
        run.run();
        return Duration.ofNanos(System.nanoTime() - start).toMillis();
    }

    // --- the tests -------------------------------------------------------------------------------------------

    @Test
    void aHandshakeSentAByteAtATimeEndsAtTheDeadline() throws Exception {
        WebhookService sender = sender(Duration.ofSeconds(40));
        WebhookDelivery d = queue(endpoint(sender, drippingHandshake()), Instant.now().minusSeconds(1));
        long took = timed(sender::deliverDue);
        assertTrue(took < ATTEMPT.toMillis() + 2000, "took " + took + " ms");
        WebhookDelivery after = deliveries.findById(d.getId()).orElseThrow();
        assertEquals(1, after.getAttempts());
        assertTrue(after.getLastError().contains("no answer within"), after.getLastError());
        assertEquals(null, after.getClaimedUntil(), "the claim ends with the try");
    }

    @Test
    void aStatusLineSentAByteAtATimeEndsAtTheDeadline() throws Exception {
        WebhookService sender = sender(Duration.ofSeconds(40));
        WebhookDelivery d = queue(endpoint(sender, drippingStatusLine()), Instant.now().minusSeconds(1));
        long took = timed(sender::deliverDue);
        assertTrue(took < ATTEMPT.toMillis() + 2000, "took " + took + " ms");
        assertTrue(deliveries.findById(d.getId()).orElseThrow().getLastError().contains("no answer within"));
    }

    @Test
    void aDeadEndpointDoesNotHoldUpALiveOne() throws Exception {
        WebhookService sender = sender(Duration.ofSeconds(40));
        WebhookEndpoint dead = endpoint(sender, drippingHandshake());
        WebhookEndpoint alive = endpoint(sender, live(0));
        Instant older = Instant.now().minusSeconds(60);
        List<WebhookDelivery> toDead = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            toDead.add(queue(dead, older.plusSeconds(i)));
        }
        WebhookDelivery toLive = queue(alive, Instant.now().minusSeconds(1));

        long took = timed(sender::deliverDue);
        assertTrue(took < 2 * ATTEMPT.toMillis() + 2000, "one try to the dead endpoint, not three: " + took + " ms");
        assertEquals(WebhookDelivery.State.DELIVERED, deliveries.findById(toLive.getId()).orElseThrow().getState());
        assertEquals(1, toDead.stream().mapToInt(d -> deliveries.findById(d.getId()).orElseThrow().getAttempts()).sum(),
                "after no answer, nothing more to that endpoint in this run");
    }

    @Test
    void noTryStartsAfterTheRunBudget() throws Exception {
        WebhookService sender = sender(Duration.ofMillis(500));
        WebhookEndpoint slow = endpoint(sender, live(800));
        for (int i = 0; i < 4; i++) {
            queue(slow, Instant.now().minusSeconds(10 - i));
        }
        assertEquals(1, sender.deliverDue(), "the first try outlasts the budget");
    }

    @Test
    void aDeadEndpointsBacklogDoesNotDelayALiveEndpoint() throws Exception {
        WebhookService sender = sender(Duration.ofSeconds(10));
        WebhookEndpoint dead = endpoint(sender, drippingHandshake());
        WebhookEndpoint alive = endpoint(sender, live(0));
        Instant older = Instant.now().minusSeconds(3600);
        List<WebhookDelivery> backlog = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            WebhookDelivery d = new WebhookDelivery();
            d.setEndpointId(dead.getId());
            d.setEventId(UUID.randomUUID().toString());
            d.setEventType("status.changed");
            d.setPayload("{}");
            d.setState(WebhookDelivery.State.PENDING);
            d.setCreatedAt(older.plusSeconds(i));
            d.setNextAttemptAt(older.plusSeconds(i));
            backlog.add(d);
        }
        deliveries.saveAll(backlog);
        List<WebhookDelivery> toLive = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            toLive.add(queue(alive, Instant.now().minusSeconds(5 - i)));
        }

        long took = timed(sender::deliverDue);
        assertTrue(took < 10_000, "within the run budget: " + took + " ms");
        assertTrue(toLive.stream().allMatch(d -> deliveries.findById(d.getId()).orElseThrow().getState()
                == WebhookDelivery.State.DELIVERED), "every live delivery in one run");
        assertTrue(endpoints.findById(dead.getId()).orElseThrow().getPausedUntil() != null, "the dead one is paused");

        // The next runs spend no try on it while it is paused.
        long next = timed(() -> assertEquals(0, sender.deliverDue()));
        assertTrue(next < 1_000, "took " + next + " ms");
        assertEquals(1, deliveries.findAll().stream().filter(d -> d.getEndpointId().equals(dead.getId()))
                .mapToInt(WebhookDelivery::getAttempts).sum());
    }

    @Test
    void aStaleCopyCanNeitherClaimNorOverwriteADelivery() throws Exception {
        WebhookService sender = sender(Duration.ofSeconds(40));
        WebhookEndpoint alive = endpoint(sender, live(0));
        WebhookDelivery d = queue(alive, Instant.now().minusSeconds(1));
        WebhookDelivery stale = deliveries.findById(d.getId()).orElseThrow();

        // Another node tried it and rescheduled it meanwhile.
        WebhookDelivery fresh = deliveries.findById(d.getId()).orElseThrow();
        fresh.setAttempts(3);
        fresh.setNextAttemptAt(Instant.now().plusSeconds(600));
        deliveries.save(fresh);

        org.springframework.transaction.support.TransactionTemplate tx =
                new org.springframework.transaction.support.TransactionTemplate(transactions);
        assertEquals(Integer.valueOf(0), tx.execute(s -> deliveries.claim(d.getId(), WebhookDelivery.State.PENDING, stale.getAttempts(),
                Instant.now(), Instant.now().plusSeconds(60))), "a stale count of tries");
        assertEquals(Integer.valueOf(0), tx.execute(s -> deliveries.claim(d.getId(), WebhookDelivery.State.PENDING, 3,
                Instant.now(), Instant.now().plusSeconds(60))), "rescheduled: not due");
        assertEquals(Integer.valueOf(0), tx.execute(s -> deliveries.recordResult(d.getId(), stale.getAttempts(),
                WebhookDelivery.State.FAILED, Instant.now(), 500, "stale", null, null)), "a stale result");
        WebhookDelivery after = deliveries.findById(d.getId()).orElseThrow();
        assertEquals(3, after.getAttempts(), "never lowered");
        assertEquals(WebhookDelivery.State.PENDING, after.getState());
        assertEquals(0, sender.deliverDue(), "not due yet");
        assertTrue(received.isEmpty());
    }

    @Test
    void lookupsWaitingForOurOwnPoolDoNotPauseALiveEndpoint() throws Exception {
        WebhookService sender = sender(Duration.ofSeconds(30));
        int port = live(0);
        Instant older = Instant.now().minusSeconds(3600);
        // As many hung lookups as the pool has threads, each from an endpoint of its own.
        for (int i = 0; i < WebhookAddressPolicy.LOOKUP_THREADS; i++) {
            WebhookEndpoint dead = endpoint(sender, port);
            dead.setUrl("https://hang" + i + ".test:" + port + "/hook");
            endpoints.save(dead);
            queue(dead, older.plusSeconds(i));
        }
        WebhookEndpoint alive = endpoint(sender, port);
        WebhookDelivery toLive = queue(alive, Instant.now().minusSeconds(1));

        sender.deliverDue();
        WebhookDelivery after = deliveries.findById(toLive.getId()).orElseThrow();
        assertEquals(WebhookDelivery.State.PENDING, after.getState());
        assertEquals(0, after.getAttempts(), "its lookup never started: not a try");
        assertEquals(null, after.getClaimedUntil(), "given back");
        WebhookEndpoint live = endpoints.findById(alive.getId()).orElseThrow();
        assertEquals(null, live.getPausedUntil(), "not paused");
        assertEquals(0, live.getSilentFailures());

        hang.countDown();
        Thread.sleep(200);
        sender.deliverDue();
        assertEquals(WebhookDelivery.State.DELIVERED, deliveries.findById(toLive.getId()).orElseThrow().getState());
    }

    @Test
    void endpointsTakeTurnsAcrossRuns() throws Exception {
        WebhookService sender = sender(Duration.ofMillis(600));
        WebhookEndpoint slowA = endpoint(sender, live(400));
        WebhookEndpoint slowB = endpoint(sender, live(400));
        WebhookEndpoint late = endpoint(sender, live(0));
        Instant old = Instant.now().minusSeconds(3600);
        for (int i = 0; i < 10; i++) {
            queue(slowA, old.plusSeconds(i));
            queue(slowB, old.plusSeconds(100 + i));
        }
        WebhookDelivery waiting = queue(late, Instant.now().minusSeconds(1));
        for (int run = 0; run < 3; run++) {
            sender.deliverDue();
        }
        assertEquals(WebhookDelivery.State.DELIVERED, deliveries.findById(waiting.getId()).orElseThrow().getState(),
                "the endpoint no run had reached went first in the third");
    }

    @Test
    void aClaimedDeliveryIsNotSentTwice() throws Exception {
        WebhookService one = sender(Duration.ofSeconds(40));
        WebhookService two = sender(Duration.ofSeconds(40));
        WebhookEndpoint alive = endpoint(one, live(150));

        // Held by another replica: not sent.
        WebhookDelivery held = queue(alive, Instant.now().minusSeconds(1));
        held.setClaimedUntil(Instant.now().plusSeconds(120));
        deliveries.save(held);
        assertEquals(0, one.deliverDue());
        assertTrue(received.isEmpty());
        deliveries.deleteAll();

        // Two senders at once: each delivery reaches the receiver exactly once.
        List<WebhookDelivery> queued = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            queued.add(queue(alive, Instant.now().minusSeconds(10 - i)));
        }
        Future<Integer> a = threads.submit(one::deliverDue);
        Future<Integer> b = threads.submit(two::deliverDue);
        assertEquals(6, a.get() + b.get());
        assertEquals(6, received.size());
        assertTrue(received.values().stream().allMatch(n -> n.get() == 1), received.toString());
        assertTrue(queued.stream().allMatch(d -> deliveries.findById(d.getId()).orElseThrow().getState()
                == WebhookDelivery.State.DELIVERED));
    }
}
