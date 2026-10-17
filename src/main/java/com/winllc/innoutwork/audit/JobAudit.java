package com.winllc.innoutwork.audit;

import java.util.Map;
import java.util.TreeMap;

/** {@code job.completed} and {@code job.failed}: one event per run of a scheduled job, with counts and duration. */
public final class JobAudit {

    private JobAudit() {
    }

    public static void completed(AuditLog audit, String job, long startMillis, Map<String, ?> counts) {
        AuditEntry entry = base("job.completed", job, startMillis);
        new TreeMap<>(counts).forEach(entry::detail);
        audit.recordQuietly(entry);
    }

    public static void failed(AuditLog audit, String job, long startMillis, Throwable error) {
        audit.recordQuietly(base("job.failed", job, startMillis)
                .failure(error.getClass().getSimpleName() + ": " + error.getMessage())
                .detail("error", error.toString()));
    }

    private static AuditEntry base(String action, String job, long startMillis) {
        return AuditEntry.of(AuditEntry.Category.SYSTEM, action)
                .actor(AuditEntry.Actor.system())
                .source(null)
                .target("job", job, job)
                .detail("job", job)
                .detail("durationMs", System.currentTimeMillis() - startMillis);
    }
}
