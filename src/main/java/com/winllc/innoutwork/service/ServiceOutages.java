package com.winllc.innoutwork.service;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.model.ServiceHeartbeat;
import com.winllc.innoutwork.model.ServiceOutage;
import com.winllc.innoutwork.repository.ServiceHeartbeatRepository;
import com.winllc.innoutwork.repository.ServiceOutageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Notices when the whole service was down, and what that means for the jobs that act on missing events.
 * <p>
 * The Windows client sends each logon, lock and unlock once; while no instance of ReadyRoom is running they are
 * lost. Every instance updates one heartbeat row every {@value #BEAT_MILLIS} ms, timed by the database's clock; when
 * an instance finds it older
 * than {@link #GAP}, nobody was running in between, and that span is recorded as an outage. With two or more
 * replicas and rolling updates there is always someone beating, so an upgrade is no outage. With one replica
 * (or the Recreate strategy) the restart is one, and so is a crash.
 * <ul>
 *   <li>Absence alerts ({@code SendAbsentNotificationsCron}): someone whose check-in time plus grace period fell
 *       after an outage began is given until the outage's end plus {@code application.ha.outage-grace} (30 minutes)
 *       to be seen, since their check-in may be among the lost events. Their next logon or unlock checks them
 *       in.</li>
 *   <li>Automatic check-out ({@code MarkInactiveCron}): a session last seen locked before an outage is counted from
 *       the outage's end, since its unlock may be among the lost events.</li>
 * </ul>
 */
@Service
public class ServiceOutages {

    private static final Logger log = LoggerFactory.getLogger(ServiceOutages.class);

    static final long BEAT_MILLIS = 15_000;
    /** Three missed beats: no instance was running. */
    static final Duration GAP = Duration.ofMillis(3 * BEAT_MILLIS);
    /** Outages are kept this long; only the last day's matter to the jobs. */
    static final Duration KEEP = Duration.ofDays(35);

    private final ServiceHeartbeatRepository heartbeats;
    private final ServiceOutageRepository outages;
    private final TransactionTemplate transaction;
    private final ApplicationProperties properties;
    private final Clock clock;
    private final String instance;

    @Autowired
    public ServiceOutages(ServiceHeartbeatRepository heartbeats, ServiceOutageRepository outages,
                          PlatformTransactionManager transactions, ApplicationProperties properties) {
        this(heartbeats, outages, transactions, properties, Clock.systemUTC(), hostName());
    }

    ServiceOutages(ServiceHeartbeatRepository heartbeats, ServiceOutageRepository outages,
                   PlatformTransactionManager transactions, ApplicationProperties properties, Clock clock,
                   String instance) {
        this.heartbeats = heartbeats;
        this.outages = outages;
        this.transaction = new TransactionTemplate(transactions);
        this.properties = properties;
        this.clock = clock;
        this.instance = instance;
    }

    /**
     * Every instance, not one at a time: the heartbeat says that the service is up, and any instance can say it.
     * The jobs that read outages also beat first, so an outage is on record before a job just started acts.
     */
    @Scheduled(fixedDelay = BEAT_MILLIS, initialDelay = 0)
    public void beat() {
        try {
            recordBeat();
        } catch (RuntimeException e) {
            // The database is down: nothing else works either, and the next beat after it returns records the gap.
            log.warn("Could not record the service heartbeat: {}", e.toString());
        }
    }

    /** Updates the heartbeat, recording the gap before it as an outage when nobody beat for too long. */
    public void recordBeat() {
        transaction.executeWithoutResult(status -> {
            Instant now = databaseNow();
            ServiceHeartbeat heartbeat = heartbeats.lockHeartbeat().orElse(null);
            if (heartbeat == null) {
                heartbeat = new ServiceHeartbeat();
                heartbeat.setId(ServiceHeartbeat.ID);
            } else if (heartbeat.getBeatAt() != null && Duration.between(heartbeat.getBeatAt(), now).compareTo(GAP) > 0) {
                outages.save(new ServiceOutage(heartbeat.getBeatAt(), now));
                log.warn("ReadyRoom was not running from {} to {} (last instance up: {}): events the Windows client"
                                + " sent then were lost. Absence alerts wait until {} for people expected in by then.",
                        heartbeat.getBeatAt(), now, heartbeat.getInstance(), now.plus(grace()));
                outages.deleteEndedBefore(now.minus(KEEP));
            }
            if (heartbeat.getBeatAt() == null || now.isAfter(heartbeat.getBeatAt())) {
                heartbeat.setBeatAt(now);
                heartbeat.setInstance(instance);
            }
            heartbeats.save(heartbeat);
        });
    }

    /** The database's time, so instances whose clocks differ agree on gaps; this instance's when it cannot say. */
    Instant databaseNow() {
        Object now = heartbeats.databaseNow();
        if (now instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (now instanceof java.time.OffsetDateTime offset) {
            return offset.toInstant();
        }
        if (now instanceof java.time.ZonedDateTime zoned) {
            return zoned.toInstant();
        }
        if (now instanceof Instant instant) {
            return instant;
        }
        return clock.instant();
    }

    /** Outages that ended after {@code after}. */
    public List<ServiceOutage> endedAfter(Instant after) {
        return outages.findByDownUntilAfterOrderByDownFromAsc(after);
    }

    public Duration grace() {
        return properties.getHa().getOutageGrace();
    }

    /**
     * When someone whose check-in was due by {@code deadline} counts as absent: the deadline, or, when the service
     * was down at some point before it, the end of that outage plus the grace period if that is later.
     */
    public static Instant absentAfter(Instant deadline, List<ServiceOutage> outages, Duration grace) {
        Instant result = deadline;
        for (ServiceOutage outage : outages) {
            if (outage.getDownFrom().isBefore(deadline)) {
                Instant until = outage.getDownUntil().plus(grace);
                if (until.isAfter(result)) {
                    result = until;
                }
            }
        }
        return result;
    }

    /**
     * When a session last seen locked at {@code lockedAt} started being idle, as far as is known: the lock, or the
     * end of the latest outage after it, during which an unlock may have been lost.
     */
    public static Instant idleSince(Instant lockedAt, List<ServiceOutage> outages) {
        Instant result = lockedAt;
        for (ServiceOutage outage : outages) {
            if (outage.getDownUntil().isAfter(result)) {
                result = outage.getDownUntil();
            }
        }
        return result;
    }

    private static String hostName() {
        String pod = System.getenv("HOSTNAME");
        if (pod != null && !pod.isBlank()) {
            return pod;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
