package com.winllc.innoutwork.audit;

import java.util.List;

/**
 * Every action id ReadyRoom records. Stable: never rename one once shipped. The admin page's action filter
 * lists these and any other id found in the store.
 */
public final class AuditActions {

    private AuditActions() {
    }

    public static final List<String> ALL = List.of(
            // authentication
            "auth.login", "auth.logout", "auth.consent-acknowledged", "auth.session-expired",
            // authorization
            "access.denied",
            // account
            "person.created", "account.role-changed", "account.permission-granted", "account.permission-revoked",
            // configuration
            "settings.changed", "settings.cache-evicted", "calendar.org-event-created", "calendar.org-event-deleted",
            "group.managers-changed",
            // data
            "person.updated", "calendar.status-set", "absence.alert-responded", "absence.alerts-dismissed",
            "data.exported",
            // license
            "license.installed", "license.rejected", "license.mode-changed", "license.limit-exceeded",
            // system
            "system.started", "system.stopped", "job.completed", "job.failed", "job.skipped",
            // audit
            "audit.exported", "audit.verified", "audit.token-created", "audit.token-revoked", "audit.pruned",
            "audit.delivery-failed", "audit.delivery-recovered");
}
