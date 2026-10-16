package com.winllc.innoutwork.audit;

import org.springframework.beans.factory.annotation.Autowired;
import com.enssolutions.license.LicenseManager;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * The audit store: appends events to {@code audit_events}, synchronously, in the request that caused them.
 *
 * <p><b>Writers are serialized</b> by a row lock on the chain head ({@code audit_chain_head}, one row): each
 * append locks it ({@code SELECT ... FOR UPDATE}), takes the next seq and the previous hash, inserts the event
 * and moves the head on, all in one transaction. That holds across threads and across application instances
 * sharing the database, and a rolled-back transaction rolls the head back with it, so seq never has gaps.
 *
 * <p>After the commit each line goes to the {@code ens.audit} logger (stdout and the optional file, see
 * {@link AuditOutputs}) and the forwarders (Splunk HEC, syslog) are woken to deliver from the store.
 */
@Service
public class AuditService implements AuditLog {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    /** The raw JSON lines, one per event, with no prefix: stdout and the audit file. */
    public static final Logger AUDIT_LINES = LoggerFactory.getLogger("ens.audit");

    private final EntityManager em;
    private final TransactionTemplate required;
    private final TransactionTemplate requiresNew;
    private final AuditEventRepository events;
    private final AuditProperties properties;
    private final ObjectProvider<LicenseManager> licenses;
    private final String version;
    private final String host;
    private final Clock clock;
    private final List<Runnable> appendListeners = new CopyOnWriteArrayList<>();
    private volatile String installationId;

    @Autowired
    public AuditService(EntityManager em, PlatformTransactionManager transactions, AuditEventRepository events,
                        AuditProperties properties, ObjectProvider<LicenseManager> licenses,
                        ObjectProvider<BuildProperties> build) {
        this(em, transactions, events, properties, licenses,
                build.getIfAvailable() != null && build.getIfAvailable().getVersion() != null
                        ? build.getIfAvailable().getVersion() : "dev",
                hostName(), Clock.systemUTC());
    }

    AuditService(EntityManager em, PlatformTransactionManager transactions, AuditEventRepository events,
                 AuditProperties properties, ObjectProvider<LicenseManager> licenses, String version, String host,
                 Clock clock) {
        this.em = em;
        this.required = new TransactionTemplate(transactions);
        this.requiresNew = new TransactionTemplate(transactions);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.events = events;
        this.properties = properties;
        this.licenses = licenses;
        this.version = version;
        this.host = host;
        this.clock = clock;
    }

    /** Creates the chain head if this is the first start against this database. */
    @PostConstruct
    void createHead() {
        try {
            requiresNew.executeWithoutResult(status -> head());
        } catch (RuntimeException e) {
            // Another instance created it at the same moment, or the database is not up yet: the first append
            // creates it otherwise.
            log.debug("Audit chain head not created at startup: {}", e.getMessage());
        }
    }

    public String product() {
        return properties.getProduct();
    }

    public String version() {
        return version;
    }

    public String host() {
        return host;
    }

    /** Called after every committed append; the forwarders use it to deliver promptly. */
    public void onAppend(Runnable listener) {
        appendListeners.add(listener);
    }

    @Override
    public void record(AuditEntry entry) {
        Captured captured = capture(entry);
        try {
            required.executeWithoutResult(status -> append(captured));
        } catch (AuditUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("Could not store audit event {}: {}", entry, e.toString());
            throw new AuditUnavailableException("The audit log could not be written; the action was not completed.", e);
        }
    }

    @Override
    public void recordQuietly(AuditEntry entry) {
        Captured captured = capture(entry);
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            // Not in the caller's transaction: a failure here must not roll the caller back, and the caller's
            // own outcome (it may be rolling back right now) must not take this event with it.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    storeQuietly(captured, requiresNew);
                }
            });
            return;
        }
        storeQuietly(captured, required);
    }

    private void storeQuietly(Captured captured, TransactionTemplate template) {
        try {
            template.executeWithoutResult(status -> append(captured));
        } catch (RuntimeException e) {
            log.error("Could not store audit event {}; continuing without it: {}", captured.entry(), e.toString());
        }
    }

    @Override
    public <T> T inTransaction(Supplier<T> change) {
        return required.execute(status -> change.get());
    }

    @Override
    public void requireAvailable() {
        try {
            required.executeWithoutResult(status -> head());
        } catch (RuntimeException e) {
            throw new AuditUnavailableException("The audit log could not be written; the action was not completed.", e);
        }
    }

    /** The last recorded license mode, for noticing a change across restarts. */
    public String lastLicenseMode() {
        return required.execute(status -> head().getLicenseMode());
    }

    /** Remembers the license mode with the event that records the change, in one transaction. */
    public void recordLicenseMode(String mode, AuditEntry entry) {
        Captured captured = capture(entry);
        try {
            required.executeWithoutResult(status -> {
                append(captured);
                AuditChainHead head = em.find(AuditChainHead.class, AuditChainHead.ID);
                head.setLicenseMode(mode);
            });
        } catch (RuntimeException e) {
            log.error("Could not record the license mode change to {}: {}", mode, e.toString());
        }
    }

    /** The newest seq stored, 0 when there is none. */
    public long headSeq() {
        return required.execute(status -> {
            AuditChainHead head = em.find(AuditChainHead.class, AuditChainHead.ID);
            return head == null ? 0L : head.getLastSeq();
        });
    }

    // --- appending --------------------------------------------------------------------------------------------

    /** The event with everything taken from the calling thread (request, signed-in user) resolved now. */
    record Captured(AuditEntry entry, AuditEntry.Actor actor, AuditEntry.Source source, String requestId,
                    String session, Instant time) {
    }

    private Captured capture(AuditEntry entry) {
        AuditRequestInfo.Info info = AuditRequestInfo.current();
        AuditEntry.Actor actor = entry.actor() != null ? entry.actor() : info.actor();
        AuditEntry.Source source = entry.sourceSet() ? entry.source() : info.source();
        Instant time = entry.time() != null ? entry.time() : clock.instant();
        return new Captured(entry, actor, source, info.requestId(), info.session(), time.truncatedTo(ChronoUnit.MILLIS));
    }

    /** The chain head, locked until the current transaction ends. */
    private AuditChainHead head() {
        AuditChainHead head = em.find(AuditChainHead.class, AuditChainHead.ID, LockModeType.PESSIMISTIC_WRITE);
        if (head == null) {
            // First event against this database (normally created at startup). Created in its own transaction, so a
            // concurrent creator's duplicate key cannot abort the caller's transaction.
            try {
                requiresNew.executeWithoutResult(status -> {
                    if (em.find(AuditChainHead.class, AuditChainHead.ID) == null) {
                        AuditChainHead created = new AuditChainHead();
                        created.setId(AuditChainHead.ID);
                        AuditEventRecord last = events.findFirstByOrderBySeqDesc().orElse(null);
                        created.setLastSeq(last == null ? 0 : last.getSeq());
                        created.setLastHash(last == null ? AuditJson.ZERO_HASH : last.getHash());
                        em.persist(created);
                        em.flush();
                    }
                });
            } catch (DataIntegrityViolationException | jakarta.persistence.PersistenceException e) {
                log.debug("Audit chain head created concurrently: {}", e.toString());
            }
            head = em.find(AuditChainHead.class, AuditChainHead.ID, LockModeType.PESSIMISTIC_WRITE);
            if (head == null) {
                throw new IllegalStateException("The audit chain head could not be created");
            }
        }
        return head;
    }

    private void append(Captured c) {
        AuditEntry entry = c.entry();
        AuditChainHead head = head();
        long seq = head.getLastSeq() + 1;
        String prevHash = head.getLastHash();

        Map<String, Object> details = AuditRedaction.details(entry.details());
        String reason = AuditRedaction.text(entry.reason());
        AuditJson.Event event = new AuditJson.Event(UUID.randomUUID().toString(), seq, c.time(),
                properties.getProduct(), version, host, installationId(), entry.category().id(), entry.action(),
                entry.outcome().id(), entry.severity().id(), c.actor(), c.source(), entry.target(), reason,
                details, c.requestId(), c.session(), prevHash);
        String body = AuditJson.body(event);
        String hash = AuditJson.hash(prevHash, body);
        String line = AuditJson.line(body, hash);

        AuditEventRecord row = new AuditEventRecord();
        row.setSeq(seq);
        row.setEventId(event.id());
        row.setTime(c.time());
        row.setCategory(event.category());
        row.setAction(event.action());
        row.setOutcome(event.outcome());
        row.setSeverity(event.severity());
        if (c.actor() != null) {
            row.setActorType(c.actor().type());
            row.setActorId(AuditJson.cut(c.actor().id()));
            row.setActorName(AuditJson.cut(c.actor().name()));
        }
        if (entry.target() != null) {
            row.setTargetType(entry.target().type());
            row.setTargetId(AuditJson.cut(entry.target().id()));
            row.setTargetName(AuditJson.cut(entry.target().name()));
        }
        row.setSourceIp(c.source() != null ? c.source().ip() : null);
        row.setReason(AuditJson.cut(reason));
        row.setLine(line);
        row.setHash(hash);
        em.persist(row);

        head.setLastSeq(seq);
        head.setLastHash(hash);
        em.flush();

        afterCommit(line);
    }

    private void afterCommit(String line) {
        Runnable emit = () -> {
            try {
                AUDIT_LINES.info(line);
            } catch (RuntimeException e) {
                log.error("Could not write the audit line to stdout or the audit file: {}", e.toString());
            }
            for (Runnable listener : appendListeners) {
                try {
                    listener.run();
                } catch (RuntimeException e) {
                    log.debug("Audit append listener failed: {}", e.toString());
                }
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    emit.run();
                }
            });
        } else {
            emit.run();
        }
    }

    private String installationId() {
        String id = installationId;
        if (id == null) {
            LicenseManager manager = licenses.getIfAvailable();
            if (manager == null) {
                return null;
            }
            try {
                id = manager.installationId();
                installationId = id;
            } catch (RuntimeException e) {
                log.debug("No installation id for the audit trail: {}", e.toString());
                return null;
            }
        }
        return id;
    }

    private static String hostName() {
        String env = System.getenv("HOSTNAME");
        if (env != null && !env.isBlank()) {
            return env;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
