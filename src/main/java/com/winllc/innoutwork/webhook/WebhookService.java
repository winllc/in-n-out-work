package com.winllc.innoutwork.webhook;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.winllc.innoutwork.audit.AuditEntry;
import com.winllc.innoutwork.audit.AuditLog;
import com.winllc.innoutwork.audit.TokenSecrets;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.model.WebhookDelivery;
import com.winllc.innoutwork.model.WebhookEndpoint;
import com.winllc.innoutwork.repository.WebhookDeliveryRepository;
import com.winllc.innoutwork.repository.WebhookEndpointRepository;
import com.winllc.innoutwork.security.AccessPolicy;
import com.winllc.innoutwork.service.InvalidRequestException;
import com.winllc.innoutwork.util.LogSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import com.winllc.innoutwork.security.AppUserDetailsService;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Signed outbound webhooks (RR-14): the endpoints administrators register, the queue of deliveries, and sending.
 *
 * <ul>
 *   <li><b>Endpoints</b> are HTTPS URLs that {@link WebhookAddressPolicy} allows, checked when saved and again on
 *       every delivery. Each has its own signing secret ({@code whsec_...}): shown once when it is created, then
 *       kept encrypted ({@link SecretBox}), never in the session, the log or the audit trail. Only an
 *       administrator creates, disables or deletes one, and each change is audited. An endpoint acts for the
 *       administrator who created it: while they are no longer an administrator, nothing new is queued for it and
 *       what is queued fails.</li>
 *   <li><b>Deliveries</b> are queued in the database once the change they describe has committed, one per
 *       subscribed endpoint, and sent by {@link WebhookDispatcher} on one replica at a time. A 2xx answer delivers
 *       one; anything else (another status, a refused address, a TLS or network error) is retried with backoff,
 *       up to application.webhooks.max-attempts tries. Delivery is at least once: receivers de-duplicate on the
 *       payload's {@code id}.</li>
 * </ul>
 * Nothing here is ever allowed to fail the change that caused an event: queueing errors are logged.
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    public static final String SECRET_PREFIX = "whsec_";
    static final int MAX_NAME = 200;
    static final int BATCH = 50;
    /** Waits before the 2nd, 3rd, ... try; the last one repeats. */
    static final List<Duration> BACKOFF = List.of(Duration.ofSeconds(30), Duration.ofMinutes(2),
            Duration.ofMinutes(10), Duration.ofMinutes(30), Duration.ofHours(1), Duration.ofHours(2),
            Duration.ofHours(4), Duration.ofHours(8));

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final WebhookEndpointRepository endpoints;
    private final WebhookDeliveryRepository deliveries;
    private final WebhookAddressPolicy policy;
    private final WebhookTransport transport;
    private final ApplicationProperties.Webhooks settings;
    private final AccessPolicy accessPolicy;
    private final UserDetailsService users;
    private final AuditLog audit;
    private final TransactionTemplate newTransaction;
    private final Clock clock;
    private final SecretBox box;

    /** The enabled endpoints whose owner is still an administrator: read again at most every 30 seconds. */
    private final Cache<String, List<WebhookEndpoint>> activeEndpoints = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30)).maximumSize(1).build();

    @org.springframework.beans.factory.annotation.Autowired
    public WebhookService(WebhookEndpointRepository endpoints, WebhookDeliveryRepository deliveries,
                          WebhookAddressPolicy policy, WebhookTransport transport, ApplicationProperties properties,
                          AccessPolicy accessPolicy, AppUserDetailsService users, ObjectProvider<AuditLog> audit,
                          PlatformTransactionManager transactions) {
        this(endpoints, deliveries, policy, transport, properties, accessPolicy, users,
                audit.getIfAvailable(() -> AuditLog.NOOP), transactions, Clock.systemUTC());
    }

    WebhookService(WebhookEndpointRepository endpoints, WebhookDeliveryRepository deliveries,
                   WebhookAddressPolicy policy, WebhookTransport transport, ApplicationProperties properties,
                   AccessPolicy accessPolicy, UserDetailsService users, AuditLog audit,
                   PlatformTransactionManager transactions, Clock clock) {
        this.endpoints = endpoints;
        this.deliveries = deliveries;
        this.policy = policy;
        this.transport = transport;
        this.settings = properties.getWebhooks();
        this.accessPolicy = accessPolicy;
        this.users = users;
        this.audit = audit;
        this.newTransaction = new TransactionTemplate(transactions);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        this.box = settings.isConfigured() ? new SecretBox(settings.getSecretKey()) : null;
    }

    /** Whether webhooks can be used: application.webhooks.secret-key is set. */
    public boolean isConfigured() {
        return box != null;
    }

    // --- endpoints -------------------------------------------------------------------------------------------

    /** An endpoint just created: the signing secret is in {@code secret}, here and nowhere else. */
    public record Created(WebhookEndpoint endpoint, String secret) {
        @Override
        public String toString() {
            return "Created[" + endpoint + "]";
        }
    }

    /**
     * Registers an endpoint, after checking its URL and every address its host resolves to.
     *
     * @throws AccessDeniedException   unless {@code creator} is an administrator
     * @throws InvalidRequestException when webhooks are not configured, no event is chosen, or the URL is refused
     */
    public Created create(String name, String url, Collection<WebhookEventType> events, Authentication creator) {
        requireAdmin(creator);
        requireConfigured();
        if (events == null || events.isEmpty()) {
            throw InvalidRequestException.badRequest("invalid_events", "Choose at least one event.");
        }
        URI uri;
        try {
            uri = policy.checkUrl(url);
            policy.resolve(uri.getHost());
        } catch (WebhookRefusedException e) {
            throw InvalidRequestException.badRequest("invalid_url", e.getMessage());
        }
        String cleanName = name == null || name.isBlank() ? uri.getHost() : name.trim();
        if (cleanName.length() > MAX_NAME) {
            cleanName = cleanName.substring(0, MAX_NAME);
        }
        String secret = TokenSecrets.generate(SECRET_PREFIX);
        WebhookEndpoint endpoint = new WebhookEndpoint();
        endpoint.setName(cleanName);
        endpoint.setUrl(uri.toString());
        endpoint.setEvents(WebhookEventType.join(EnumSet.copyOf(events)));
        endpoint.setSecretCiphertext(box.seal(secret));
        endpoint.setSecretPrefix(TokenSecrets.prefix(secret, SECRET_PREFIX));
        endpoint.setOwnerDn(creator.getName());
        endpoint.setCreatedAt(clock.instant());
        WebhookEndpoint saved = audit.inTransaction(() -> {
            WebhookEndpoint s = endpoints.save(endpoint);
            audit.record(entry("webhook.created", s).detail("host", uri.getHost()).detail("events", s.getEvents()));
            return s;
        });
        activeEndpoints.invalidateAll();
        return new Created(saved, secret);
    }

    /** Stops (or starts again) sending to an endpoint; queued deliveries fail while it is disabled. */
    public Optional<WebhookEndpoint> setEnabled(long id, boolean enabled, Authentication by) {
        requireAdmin(by);
        Optional<WebhookEndpoint> changed = audit.inTransaction(() -> endpoints.findById(id)
                .filter(e -> e.isEnabled() != enabled)
                .map(e -> {
                    e.setDisabledAt(enabled ? null : clock.instant());
                    e.setDisabledBy(enabled ? null : by.getName());
                    WebhookEndpoint s = endpoints.save(e);
                    audit.record(entry(enabled ? "webhook.enabled" : "webhook.disabled", s));
                    return s;
                }));
        activeEndpoints.invalidateAll();
        return changed;
    }

    /** Deletes an endpoint and its delivery log. */
    public boolean delete(long id, Authentication by) {
        requireAdmin(by);
        boolean deleted = audit.inTransaction(() -> endpoints.findById(id).map(e -> {
            deliveries.deleteByEndpoint(e.getId());
            endpoints.delete(e);
            audit.record(entry("webhook.deleted", e));
            return true;
        }).orElse(false));
        activeEndpoints.invalidateAll();
        return deleted;
    }

    /** Queues a {@code ping} for one endpoint, to be sent within seconds. */
    public boolean ping(long id, Authentication by) {
        requireAdmin(by);
        requireConfigured();
        return endpoints.findById(id).filter(WebhookEndpoint::isEnabled).map(e -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("message", "A test delivery from ReadyRoom, asked for from the Integrations page.");
            WebhookEvent event = WebhookEvent.of(WebhookEventType.PING, data);
            newTransaction.executeWithoutResult(s -> deliveries.save(delivery(e, event, payload(event))));
            return true;
        }).orElse(false);
    }

    public List<WebhookEndpoint> list() {
        return endpoints.findAllByOrderByCreatedAtDesc();
    }

    /** The most recent deliveries to an endpoint, newest first: the delivery log. */
    public List<WebhookDelivery> recentDeliveries(long endpointId, int limit) {
        return deliveries.findByEndpointIdOrderByCreatedAtDescIdDesc(endpointId, PageRequest.of(0, limit));
    }

    // --- queueing ---------------------------------------------------------------------------------------------

    /** An event, once the transaction it happened in has committed (at once without one): queued for delivery. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onEvent(WebhookEvent event) {
        if (!isConfigured()) {
            return;
        }
        try {
            List<WebhookEndpoint> subscribed = active().stream()
                    .filter(e -> e.getEventSet().contains(event.type()))
                    .toList();
            if (subscribed.isEmpty()) {
                return;
            }
            String payload = payload(event);
            newTransaction.executeWithoutResult(s -> subscribed.forEach(e -> deliveries.save(delivery(e, event, payload))));
        } catch (RuntimeException e) {
            log.error("Could not queue webhook event {} ({})", event.id(), event.type().id(), e);
        }
    }

    private List<WebhookEndpoint> active() {
        return activeEndpoints.get("all", k -> endpoints.findByDisabledAtIsNull().stream()
                .filter(e -> ownerIsAdmin(e.getOwnerDn()))
                .toList());
    }

    private WebhookDelivery delivery(WebhookEndpoint endpoint, WebhookEvent event, String payload) {
        WebhookDelivery d = new WebhookDelivery();
        d.setEndpointId(endpoint.getId());
        d.setEventId(event.id());
        d.setEventType(event.type().id());
        d.setPayload(payload);
        d.setState(WebhookDelivery.State.PENDING);
        d.setAttempts(0);
        d.setCreatedAt(clock.instant());
        d.setNextAttemptAt(clock.instant());
        return d;
    }

    /** The body sent: {@code {"id", "type", "createdAt", "data"}}. */
    static String payload(WebhookEvent event) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", event.id());
        body.put("type", event.type().id());
        body.put("createdAt", event.createdAt().toString());
        body.put("data", event.data());
        return JSON.writeValueAsString(body);
    }

    // --- sending ----------------------------------------------------------------------------------------------

    /** Sends what is due, oldest first, a batch at a time. @return how many were tried */
    public int deliverDue() {
        if (!isConfigured()) {
            return 0;
        }
        List<WebhookDelivery> due = deliveries.findDue(WebhookDelivery.State.PENDING, clock.instant(),
                PageRequest.of(0, BATCH));
        for (WebhookDelivery delivery : due) {
            attempt(delivery);
        }
        return due.size();
    }

    void attempt(WebhookDelivery delivery) {
        Instant now = clock.instant();
        Optional<WebhookEndpoint> found = endpoints.findById(delivery.getEndpointId());
        String problem = null;
        Integer status = null;
        if (found.isEmpty() || !found.get().isEnabled()) {
            finish(delivery, null, now, WebhookDelivery.State.FAILED, null, "the endpoint is disabled or deleted");
            return;
        }
        WebhookEndpoint endpoint = found.get();
        if (!ownerIsAdmin(endpoint.getOwnerDn())) {
            finish(delivery, endpoint, now, WebhookDelivery.State.FAILED, null,
                    "the administrator who created this endpoint no longer is one");
            return;
        }
        try {
            byte[] body = delivery.getPayload().getBytes(StandardCharsets.UTF_8);
            long timestamp = now.getEpochSecond();
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("X-ReadyRoom-Event", delivery.getEventType());
            headers.put("X-ReadyRoom-Delivery", delivery.getEventId());
            headers.put(WebhookSignature.TIMESTAMP_HEADER, String.valueOf(timestamp));
            headers.put(WebhookSignature.HEADER, WebhookSignature.header(box.open(endpoint.getSecretCiphertext()),
                    timestamp, body));
            status = transport.post(endpoint.getUrl(), headers, body);
            if (status >= 200 && status < 300) {
                finish(delivery, endpoint, now, WebhookDelivery.State.DELIVERED, status, null);
                return;
            }
            problem = "answered HTTP " + status;
        } catch (WebhookRefusedException e) {
            problem = "refused: " + e.getMessage();
        } catch (IOException e) {
            problem = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        } catch (RuntimeException e) {
            log.error("Webhook delivery {} to endpoint {} failed", delivery.getId(), endpoint.getId(), e);
            problem = e.getClass().getSimpleName();
        }
        boolean last = delivery.getAttempts() + 1 >= settings.getMaxAttempts();
        log.warn("Webhook delivery {} ({}) to endpoint {} failed, try {} of {}: {}", delivery.getId(),
                delivery.getEventType(), endpoint.getId(), delivery.getAttempts() + 1, settings.getMaxAttempts(),
                LogSafe.of(problem));
        finish(delivery, endpoint, now, last ? WebhookDelivery.State.FAILED : WebhookDelivery.State.PENDING, status,
                problem);
    }

    private void finish(WebhookDelivery delivery, WebhookEndpoint endpoint, Instant now, WebhookDelivery.State state,
                        Integer status, String problem) {
        delivery.setAttempts(delivery.getAttempts() + 1);
        delivery.setLastAttemptAt(now);
        delivery.setLastStatus(status);
        delivery.setLastError(cut(problem));
        delivery.setState(state);
        if (state == WebhookDelivery.State.DELIVERED) {
            delivery.setDeliveredAt(now);
            delivery.setNextAttemptAt(null);
        } else if (state == WebhookDelivery.State.PENDING) {
            delivery.setNextAttemptAt(now.plus(BACKOFF.get(Math.min(delivery.getAttempts() - 1, BACKOFF.size() - 1))));
        } else {
            delivery.setNextAttemptAt(null);
        }
        newTransaction.executeWithoutResult(s -> {
            deliveries.save(delivery);
            if (endpoint != null) {
                endpoints.findById(endpoint.getId()).ifPresent(e -> {
                    if (state == WebhookDelivery.State.DELIVERED) {
                        e.setLastSuccessAt(now);
                    } else {
                        e.setLastFailureAt(now);
                        e.setLastError(cut(problem));
                    }
                    endpoints.save(e);
                });
            }
        });
    }

    /** Deletes delivered and failed deliveries older than application.webhooks.delivery-log-days. */
    public int pruneLog() {
        Instant before = clock.instant().minus(Duration.ofDays(settings.getDeliveryLogDays()));
        Integer n = newTransaction.execute(s -> deliveries.deleteFinishedBefore(WebhookDelivery.State.PENDING, before));
        return n == null ? 0 : n;
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    private boolean ownerIsAdmin(String ownerDn) {
        try {
            return users.loadUserByUsername(ownerDn).getAuthorities().stream()
                    .anyMatch(a -> UserRoleEnum.ADMIN.name().equals(a.getAuthority()));
        } catch (UsernameNotFoundException e) {
            return false;
        }
    }

    private void requireAdmin(Authentication who) {
        if (!accessPolicy.isAdmin(who)) {
            throw new AccessDeniedException("Only administrators manage webhooks");
        }
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw InvalidRequestException.conflict("webhooks_not_configured",
                    "Webhooks need application.webhooks.secret-key (APPLICATION_WEBHOOKS_SECRETKEY) to be set.");
        }
    }

    private static AuditEntry entry(String action, WebhookEndpoint e) {
        return AuditEntry.of(AuditEntry.Category.CONFIGURATION, action)
                .severity(AuditEntry.Severity.NOTICE)
                .target("webhook", String.valueOf(e.getId()), e.getName())
                .detail("secretPrefix", e.getSecretPrefix());
    }

    private static String cut(String s) {
        return s == null || s.length() <= 500 ? s : s.substring(0, 500);
    }

    /** For tests and the page: the endpoints events go to now. */
    List<WebhookEndpoint> activeEndpointsForTest() {
        activeEndpoints.invalidateAll();
        return new ArrayList<>(active());
    }
}
