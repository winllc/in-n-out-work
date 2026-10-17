package com.winllc.innoutwork.audit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One audit event as a call site describes it: what happened, to what, and how it went. The store adds the
 * rest (id, seq, time, product, host, the actor and source from the current request when not given, and
 * the hash chain); see {@link AuditJson} for the emitted {@code ens-audit/1} form.
 *
 * <pre>
 * audit.record(AuditEntry.of(AuditEntry.Category.ACCOUNT, "account.role-changed")
 *         .target("user", dn, cn)
 *         .change("role", oldRole, newRole));
 * </pre>
 */
public final class AuditEntry {

    public enum Category {
        AUTHENTICATION, AUTHORIZATION, ACCOUNT, CONFIGURATION, DATA, LICENSE, SYSTEM, AUDIT;

        public String id() {
            return name().toLowerCase();
        }
    }

    public enum Outcome {
        SUCCESS, FAILURE, DENIED;

        public String id() {
            return name().toLowerCase();
        }
    }

    public enum Severity {
        INFO, NOTICE, WARNING, ALERT;

        public String id() {
            return name().toLowerCase();
        }

        /** RFC 5424 severity: info 6, notice 5, warning 4, alert 1. */
        public int syslog() {
            return switch (this) {
                case INFO -> 6;
                case NOTICE -> 5;
                case WARNING -> 4;
                case ALERT -> 1;
            };
        }
    }

    /** {@code actor.type}: user, service (an API token or client cert), system or anonymous. */
    public record Actor(String type, String id, String name, List<String> roles) {
        public Actor {
            roles = roles == null ? List.of() : List.copyOf(roles);
        }

        public static Actor system() {
            return new Actor("system", null, null, List.of());
        }

        /** A failed sign-in: the attempted username, identity unknown. */
        public static Actor anonymous(String attemptedUsername) {
            return new Actor("anonymous", attemptedUsername, null, List.of());
        }

        public static Actor service(String id, String name) {
            return new Actor("service", id, name, List.of());
        }
    }

    public record Source(String ip, String userAgent) {
    }

    public record Target(String type, String id, String name) {
    }

    private final Category category;
    private final String action;
    private Outcome outcome = Outcome.SUCCESS;
    private Severity severity;
    private Actor actor;
    private Source source;
    private boolean sourceSet;
    private Target target;
    private String reason;
    private final Map<String, Object> details = new LinkedHashMap<>();
    private Instant time;

    private AuditEntry(Category category, String action) {
        this.category = Objects.requireNonNull(category);
        this.action = Objects.requireNonNull(action);
    }

    public static AuditEntry of(Category category, String action) {
        return new AuditEntry(category, action);
    }

    public AuditEntry outcome(Outcome outcome) {
        this.outcome = Objects.requireNonNull(outcome);
        return this;
    }

    public AuditEntry failure(String reason) {
        this.outcome = Outcome.FAILURE;
        this.reason = reason;
        return this;
    }

    public AuditEntry denied(String reason) {
        this.outcome = Outcome.DENIED;
        this.reason = reason;
        return this;
    }

    public AuditEntry severity(Severity severity) {
        this.severity = severity;
        return this;
    }

    public AuditEntry actor(Actor actor) {
        this.actor = actor;
        return this;
    }

    /** Overrides the source taken from the current request; null records none. */
    public AuditEntry source(Source source) {
        this.source = source;
        this.sourceSet = true;
        return this;
    }

    public AuditEntry target(String type, String id, String name) {
        this.target = new Target(type, id, name);
        return this;
    }

    public AuditEntry reason(String reason) {
        this.reason = reason;
        return this;
    }

    /** A detail value: a string, number, boolean or null. Anything else is recorded as its string form. */
    public AuditEntry detail(String key, Object value) {
        details.put(key, value);
        return this;
    }

    /** A change, as {@code <field>.old} and {@code <field>.new}. */
    public AuditEntry change(String field, Object oldValue, Object newValue) {
        details.put(field + ".old", oldValue);
        details.put(field + ".new", newValue);
        return this;
    }

    /** The event time; only for migrated history, everything else is stamped when stored. */
    public AuditEntry time(Instant time) {
        this.time = time;
        return this;
    }

    public Category category() {
        return category;
    }

    public String action() {
        return action;
    }

    public Outcome outcome() {
        return outcome;
    }

    /** The severity given, or the spec's default: warning for failures and denials, notice for changes to
     * configuration, accounts and the license, info otherwise. */
    public Severity severity() {
        if (severity != null) {
            return severity;
        }
        if (outcome != Outcome.SUCCESS) {
            return Severity.WARNING;
        }
        return switch (category) {
            case CONFIGURATION, ACCOUNT, LICENSE -> Severity.NOTICE;
            default -> Severity.INFO;
        };
    }

    public Actor actor() {
        return actor;
    }

    public Source source() {
        return source;
    }

    public boolean sourceSet() {
        return sourceSet;
    }

    public Target target() {
        return target;
    }

    public String reason() {
        return reason;
    }

    public Map<String, Object> details() {
        return details;
    }

    public Instant time() {
        return time;
    }

    /** A copy with {@code details.count} set, for a collapsed run of identical events. */
    AuditEntry withCount(long count) {
        AuditEntry copy = new AuditEntry(category, action);
        copy.outcome = outcome;
        copy.severity = severity;
        copy.actor = actor;
        copy.source = source;
        copy.sourceSet = sourceSet;
        copy.target = target;
        copy.reason = reason;
        copy.details.putAll(details);
        copy.details.put("count", count);
        return copy;
    }

    @Override
    public String toString() {
        List<String> parts = new ArrayList<>();
        parts.add(action);
        parts.add(outcome.id());
        if (reason != null) {
            parts.add(reason);
        }
        return String.join(" ", parts);
    }
}
