package com.winllc.innoutwork.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** A forwarding output's persisted cursor (the last seq it delivered) and its health. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "audit_output_state")
public class AuditOutputState {

    @Id
    @Column(length = 32)
    private String name;

    @Column(name = "last_seq", nullable = false)
    private long lastSeq;

    @Column(name = "last_delivered_at")
    private Instant lastDeliveredAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "last_error_at")
    private Instant lastErrorAt;

    /** When the current run of failures began; null while delivering. */
    @Column(name = "failing_since")
    private Instant failingSince;

    /** Whether audit.delivery-failed has been recorded for the current run of failures. */
    @Column(name = "failure_reported", nullable = false)
    private boolean failureReported;
}
