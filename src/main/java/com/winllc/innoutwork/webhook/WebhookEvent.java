package com.winllc.innoutwork.webhook;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Something that happened, for the webhook endpoints subscribed to it: published as a Spring application event and
 * queued for delivery once the transaction it happened in commits (WebhookService). {@code data} becomes the
 * payload's {@code data} object.
 */
public record WebhookEvent(String id, WebhookEventType type, Instant createdAt, Map<String, Object> data) {

    public static WebhookEvent of(WebhookEventType type, Map<String, Object> data) {
        return new WebhookEvent(UUID.randomUUID().toString(), type, Instant.now(), data);
    }
}
