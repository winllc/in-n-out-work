package com.winllc.innoutwork.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * One event on its way to one webhook endpoint, and the delivery log: queued when the event happens, sent (and
 * retried with backoff) by webhook.WebhookDispatcher, kept for application.webhooks.delivery-log-days.
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(exclude = "payload")
@Entity
@Table(name = "webhook_deliveries", indexes = {
        @Index(name = "ix_webhook_deliveries_due", columnList = "state, next_attempt_at"),
        @Index(name = "ix_webhook_deliveries_endpoint", columnList = "endpoint_id, created_at")
})
public class WebhookDelivery {

    public enum State {
        /** Waiting for its first or next try. */
        PENDING,
        /** The endpoint answered 2xx. */
        DELIVERED,
        /** Given up: every try failed, or the endpoint can no longer be sent to. */
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "endpoint_id", nullable = false)
    private Long endpointId;

    /** The event's id, the same in every endpoint's delivery of it; receivers de-duplicate on it. */
    @Column(name = "event_id", nullable = false, length = 36)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    /** The exact JSON body sent. */
    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private State state;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "last_status")
    private Integer lastStatus;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;
}
