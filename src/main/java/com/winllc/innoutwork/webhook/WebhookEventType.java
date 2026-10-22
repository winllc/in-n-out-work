package com.winllc.innoutwork.webhook;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** The events a webhook endpoint can receive. */
public enum WebhookEventType {

    /** Someone has not checked in by their expected time plus the grace period; their managers were alerted. */
    ABSENCE_ALERT("absence.alert", "Absence alerts"),
    /** Someone's status changed: checked in, locked, unlocked, checked out, or a status put on their calendar. */
    STATUS_CHANGED("status.changed", "Status changes"),
    /** Sent on request from the Integrations page, to check an endpoint receives and verifies deliveries. */
    PING("ping", "Test deliveries");

    private final String id;
    private final String description;

    WebhookEventType(String id, String description) {
        this.id = id;
        this.description = description;
    }

    public String id() {
        return id;
    }

    public String getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public static Optional<WebhookEventType> of(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String wanted = id.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(t -> t.id.equals(wanted)).findFirst();
    }

    public static String join(Collection<WebhookEventType> types) {
        return EnumSet.copyOf(types).stream().map(WebhookEventType::id).collect(Collectors.joining(" "));
    }

    public static Set<WebhookEventType> parse(String stored) {
        Set<WebhookEventType> types = new LinkedHashSet<>();
        if (stored != null) {
            for (String part : stored.trim().split("\\s+")) {
                of(part).ifPresent(types::add);
            }
        }
        return types;
    }

    /** The events an administrator can subscribe to (a ping goes to every endpoint it is asked for). */
    public static Set<WebhookEventType> subscribable() {
        return EnumSet.of(ABSENCE_ALERT, STATUS_CHANGED);
    }
}
