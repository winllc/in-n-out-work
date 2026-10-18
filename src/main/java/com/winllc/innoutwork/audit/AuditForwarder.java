package com.winllc.innoutwork.audit;

import com.winllc.innoutwork.metrics.EnsMetrics;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Delivers the store to one output (Splunk HEC or syslog) from a background thread, never from a request. It
 * reads events after its persisted cursor (the last seq delivered, in {@code audit_output_state}), sends them
 * in batches and moves the cursor only after a batch is accepted, so delivery is at least once and survives
 * restarts. Failures are retried with exponential backoff, capped at five minutes. An output failing for more
 * than 15 minutes records one {@code audit.delivery-failed}; {@code audit.delivery-recovered} follows when it
 * delivers again.
 * <p>
 * With several replicas every one runs a forwarder, but each batch is delivered under a lock in the database
 * ({@link #setClusterLock}), so one replica at a time reads the cursor, sends and moves it: no batch goes out twice
 * because two replicas read the same cursor.
 */
public class AuditForwarder {

    private static final Logger log = LoggerFactory.getLogger(AuditForwarder.class);

    static final Duration MIN_BACKOFF = Duration.ofSeconds(2);
    static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    private final AuditOutput output;
    private final AuditService audit;
    private final AuditQueryService query;
    private final AuditOutputStateRepository states;
    private final Duration alertAfter;
    private final long pollMillis;
    private final Clock clock;
    private final Object signal = new Object();
    private volatile boolean woken;
    private volatile boolean running;
    private Thread thread;
    private Duration backoff = MIN_BACKOFF;
    /** Held while a batch is delivered; null delivers without one (one instance, tests). */
    private LockingTaskExecutor clusterLock;

    /** How long a delivery's lock lasts if its replica dies; extended while a slow delivery is still running. */
    static final Duration LOCK_AT_MOST = Duration.ofMinutes(2);

    public AuditForwarder(AuditOutput output, AuditService audit, AuditQueryService query,
                          AuditOutputStateRepository states, Duration alertAfter, long pollMillis, Clock clock) {
        this.output = output;
        this.audit = audit;
        this.query = query;
        this.states = states;
        this.alertAfter = alertAfter;
        this.pollMillis = pollMillis;
        this.clock = clock;
    }

    /** Delivers each batch holding this lock, named for the output, so replicas take turns. */
    public void setClusterLock(LockingTaskExecutor clusterLock) {
        this.clusterLock = clusterLock;
    }

    public AuditOutput output() {
        return output;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::loop, "audit-forwarder-" + output.name());
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        wake();
        if (thread != null) {
            thread.interrupt();
        }
    }

    /** New events are in the store: deliver now rather than at the next poll. */
    public void wake() {
        synchronized (signal) {
            woken = true;
            signal.notifyAll();
        }
    }

    private void loop() {
        while (running) {
            long waitMillis;
            boolean backingOff = false;
            try {
                int sent = deliverTakingTurns();
                backoff = MIN_BACKOFF;
                waitMillis = sent >= output.batchSize() ? 0 : pollMillis;
            } catch (Exception e) {
                waitMillis = backoff.toMillis();
                backingOff = true;
                backoff = backoff.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff.multipliedBy(2);
            }
            if (waitMillis > 0) {
                pause(waitMillis, backingOff);
            }
        }
    }

    /** Waits, or until woken by new events; a backoff is waited out in full whatever arrives meanwhile. */
    private void pause(long millis, boolean backingOff) {
        long deadline = System.currentTimeMillis() + millis;
        synchronized (signal) {
            try {
                while (running) {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0 || (woken && !backingOff)) {
                        break;
                    }
                    signal.wait(left);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;
            }
            woken = false;
        }
    }

    /** {@link #deliverOnce()} under the cluster lock; 0 when another replica holds it (it is delivering). */
    int deliverTakingTurns() throws Exception {
        if (clusterLock == null) {
            return deliverOnce();
        }
        LockingTaskExecutor.TaskResult<Integer> result;
        try {
            result = clusterLock.executeWithLock(this::deliverOnce, new LockConfiguration(clock.instant(),
                    "AuditForwarder-" + output.name(), LOCK_AT_MOST, Duration.ZERO));
        } catch (Exception | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
        return result.wasExecuted() && result.getResult() != null ? result.getResult() : 0;
    }

    /**
     * Sends the next batch after the cursor, if there is one. Returns how many were delivered; throws (after
     * recording the error) when the output refused them.
     */
    public int deliverOnce() throws Exception {
        AuditOutputState state = state();
        List<AuditEventRecord> batch = query.after(state.getLastSeq(), output.batchSize());
        if (batch.isEmpty()) {
            if (state.getFailingSince() != null) {
                recovered(state);
            }
            return 0;
        }
        try {
            output.send(batch);
        } catch (Exception e) {
            failed(state, e);
            throw e;
        }
        // Only ever forward: the cursor was read under this delivery's lock, and the batch is after it.
        state.setLastSeq(Math.max(state.getLastSeq(), batch.getLast().getSeq()));
        state.setLastDeliveredAt(clock.instant());
        if (state.getFailingSince() != null) {
            recovered(state);
        } else {
            states.save(state);
        }
        return batch.size();
    }

    private void failed(AuditOutputState state, Exception e) {
        EnsMetrics.auditForwardFailure(output.name());
        Instant now = clock.instant();
        String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (error.length() > 1000) {
            error = error.substring(0, 1000);
        }
        if (state.getFailingSince() == null) {
            state.setFailingSince(now);
            log.warn("Audit delivery to {} ({}) failed; retrying: {}", output.name(), output.describe(), error);
        } else {
            log.debug("Audit delivery to {} still failing: {}", output.name(), error);
        }
        state.setLastError(error);
        state.setLastErrorAt(now);
        boolean alert = !state.isFailureReported()
                && !state.getFailingSince().plus(alertAfter).isAfter(now);
        if (alert) {
            state.setFailureReported(true);
        }
        states.save(state);
        if (alert) {
            log.error("Audit delivery to {} has failed for over {} minutes: {}", output.name(),
                    alertAfter.toMinutes(), error);
            audit.recordQuietly(AuditEntry.of(AuditEntry.Category.AUDIT, "audit.delivery-failed")
                    .failure(error)
                    .severity(AuditEntry.Severity.ALERT)
                    .actor(AuditEntry.Actor.system())
                    .detail("output", output.name())
                    .detail("destination", output.describe())
                    .detail("error", error)
                    .detail("failingSince", AuditJson.time(state.getFailingSince()))
                    .detail("cursor", state.getLastSeq()));
        }
    }

    private void recovered(AuditOutputState state) {
        boolean reported = state.isFailureReported();
        Instant since = state.getFailingSince();
        state.setFailingSince(null);
        state.setFailureReported(false);
        states.save(state);
        log.info("Audit delivery to {} has recovered", output.name());
        if (reported) {
            audit.recordQuietly(AuditEntry.of(AuditEntry.Category.AUDIT, "audit.delivery-recovered")
                    .actor(AuditEntry.Actor.system())
                    .detail("output", output.name())
                    .detail("destination", output.describe())
                    .detail("failingSince", since == null ? null : AuditJson.time(since))
                    .detail("cursor", state.getLastSeq()));
        }
    }

    /** The persisted cursor; a new output starts from the beginning of the retained trail. */
    public AuditOutputState state() {
        return states.findById(output.name()).orElseGet(() -> {
            AuditOutputState s = new AuditOutputState();
            s.setName(output.name());
            s.setLastSeq(0);
            return states.save(s);
        });
    }
}
