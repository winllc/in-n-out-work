package com.winllc.innoutwork.audit;

import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Retention ({@code ENS_AUDIT_RETENTION_DAYS}, 0 = keep forever): once a day, deletes events older than that
 * which every enabled forwarding output has already delivered, never the newest event (the chain head), and
 * records {@code audit.pruned} with the count and the seq range. The only path that deletes events.
 */
@Component
public class AuditRetention {

    private static final Logger log = LoggerFactory.getLogger(AuditRetention.class);

    private final AuditProperties properties;
    private final AuditService audit;
    private final AuditEventRepository events;
    private final AuditOutputs outputs;
    private final TransactionTemplate transaction;
    private final Clock clock;

    @Autowired
    public AuditRetention(AuditProperties properties, AuditService audit, AuditEventRepository events,
                          AuditOutputs outputs, PlatformTransactionManager transactions) {
        this(properties, audit, events, outputs, transactions, Clock.systemUTC());
    }

    AuditRetention(AuditProperties properties, AuditService audit, AuditEventRepository events,
                   AuditOutputs outputs, PlatformTransactionManager transactions, Clock clock) {
        this.properties = properties;
        this.audit = audit;
        this.events = events;
        this.outputs = outputs;
        this.transaction = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    @Scheduled(cron = "0 23 3 * * *")
    public void run() {
        try {
            prune();
        } catch (RuntimeException e) {
            log.error("Audit retention failed; the next run will retry", e);
            audit.recordQuietly(AuditEntry.of(AuditEntry.Category.SYSTEM, "job.failed")
                    .failure(e.toString())
                    .actor(AuditEntry.Actor.system())
                    .target("job", "AuditRetention", "AuditRetention")
                    .detail("job", "AuditRetention"));
        }
    }

    /** Prunes once; returns how many events were deleted. */
    public int prune() {
        int days = properties.getRetentionDays();
        if (days <= 0) {
            return 0;
        }
        long head = audit.headSeq();
        if (head <= 1) {
            return 0;
        }
        Instant cutoff = clock.instant().minus(Duration.ofDays(days));
        long limit = Math.min(head - 1, outputs.deliveredByAll(head));
        Long last = events.findLastSeqBefore(cutoff, limit);
        Long first = events.findFirstByOrderBySeqAsc().map(AuditEventRecord::getSeq).orElse(null);
        if (last == null || first == null || last < first) {
            return 0;
        }
        Integer deleted = transaction.execute(status -> events.deleteUpTo(last));
        int count = deleted == null ? 0 : deleted;
        log.info("Audit retention: pruned {} event(s), seq {} to {}, older than {} days", count, first, last, days);
        audit.recordQuietly(AuditEntry.of(AuditEntry.Category.AUDIT, "audit.pruned")
                .actor(AuditEntry.Actor.system())
                .detail("count", count)
                .detail("fromSeq", first)
                .detail("toSeq", last)
                .detail("olderThan", AuditJson.time(cutoff))
                .detail("retentionDays", days));
        return count;
    }
}
