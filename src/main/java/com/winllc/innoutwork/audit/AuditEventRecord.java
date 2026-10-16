package com.winllc.innoutwork.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One stored audit event: the full {@code ens-audit/1} line, verbatim, plus the columns the admin filters
 * search. Rows are only ever inserted, and deleted by retention pruning; nothing updates them.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "audit_events", indexes = {
        @Index(name = "ux_audit_events_event_id", columnList = "event_id", unique = true),
        @Index(name = "ix_audit_events_time", columnList = "event_time"),
        @Index(name = "ix_audit_events_category", columnList = "category"),
        @Index(name = "ix_audit_events_action", columnList = "action"),
        @Index(name = "ix_audit_events_outcome", columnList = "outcome"),
        @Index(name = "ix_audit_events_severity", columnList = "severity"),
        @Index(name = "ix_audit_events_actor_id", columnList = "actor_id"),
        @Index(name = "ix_audit_events_target", columnList = "target_type, target_id"),
        @Index(name = "ix_audit_events_source_ip", columnList = "source_ip")
})
public class AuditEventRecord {

    @Id
    private Long seq;

    @Column(name = "event_id", nullable = false, length = 36)
    private String eventId;

    @Column(name = "event_time", nullable = false)
    private Instant time;

    @Column(nullable = false, length = 32)
    private String category;

    @Column(nullable = false, length = 128)
    private String action;

    @Column(nullable = false, length = 16)
    private String outcome;

    @Column(nullable = false, length = 16)
    private String severity;

    @Column(name = "actor_type", length = 16)
    private String actorType;

    @Column(name = "actor_id", length = 1000)
    private String actorId;

    @Column(name = "actor_name", length = 1000)
    private String actorName;

    @Column(name = "target_type", length = 64)
    private String targetType;

    @Column(name = "target_id", length = 1000)
    private String targetId;

    @Column(name = "target_name", length = 1000)
    private String targetName;

    @Column(name = "source_ip", length = 64)
    private String sourceIp;

    @Column(length = 1000)
    private String reason;

    /** The full line as emitted, hash included; verification works from this alone. */
    @Column(nullable = false, length = 1_000_000)
    private String line;

    @Column(nullable = false, length = 64)
    private String hash;
}
