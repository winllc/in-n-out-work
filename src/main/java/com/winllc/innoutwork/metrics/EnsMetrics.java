package com.winllc.innoutwork.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;

import java.util.Locale;

/**
 * The counters of the ENS platform baseline and ReadyRoom's own, named as the baseline names them. Prometheus
 * renders {@code ens.auth.attempts} as {@code ens_auth_attempts_total}, and so on.
 * <p>
 * They go to Micrometer's global registry, which Spring Boot joins to the Prometheus one
 * ({@code management.metrics.use-global-registry}, on by default), so the places that count need no wiring;
 * where nothing is registered (unit tests) counting does nothing.
 */
public final class EnsMetrics {

    private EnsMetrics() {
    }

    static MeterRegistry registry() {
        return Metrics.globalRegistry;
    }

    /** {@code ens_auth_attempts_total{method,outcome}}: every sign-in, by how and whether it succeeded. */
    public static void authAttempt(String method, boolean success) {
        registry().counter("ens.auth.attempts", "method", label(method), "outcome", success ? "success" : "failure")
                .increment();
    }

    /** {@code ens_audit_events_total{category,outcome}}: every stored audit event. */
    public static void auditEvent(String category, String outcome) {
        registry().counter("ens.audit.events", "category", label(category), "outcome", label(outcome)).increment();
    }

    /** {@code ens_audit_forward_failures_total{output}}: a failed delivery attempt to an audit output. */
    public static void auditForwardFailure(String output) {
        registry().counter("ens.audit.forward.failures", "output", label(output)).increment();
    }

    /** {@code ens_readyroom_checkins_total{action}}: the Windows client's events recorded (in, lock, unlock, out). */
    public static void checkIn(String action) {
        registry().counter("ens.readyroom.checkins", "action", label(action)).increment();
    }

    /** {@code ens_readyroom_alerts_sent_total}: absence alert e-mails sent. */
    public static void alertSent() {
        registry().counter("ens.readyroom.alerts.sent").increment();
    }

    /** {@code ens_readyroom_logoffs_refused_total{reason}}: logoffs refused (unknown_session, rate_limited). */
    public static void logoffRefused(String reason) {
        registry().counter("ens.readyroom.logoffs.refused", "reason", label(reason)).increment();
    }

    private static String label(String value) {
        return value == null || value.isBlank() ? "unknown" : value.toLowerCase(Locale.ROOT);
    }
}
