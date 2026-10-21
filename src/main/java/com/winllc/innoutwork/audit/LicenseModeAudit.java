package com.winllc.innoutwork.audit;

import com.enssolutions.license.LicenseCheck;
import com.enssolutions.license.LicenseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * {@code system.started} and {@code system.stopped}, and {@code license.mode-changed}: whenever the license
 * check's mode (full, read-only, unlicensed) differs from the last one recorded, at startup, after an install,
 * on requests and every 30 seconds (a license expiring needs no request to notice). The last recorded mode is
 * kept in the audit chain head, so a change while the application was down is recorded at the next start.
 */
@Component
public class LicenseModeAudit implements Consumer<LicenseCheck> {

    private static final Logger log = LoggerFactory.getLogger(LicenseModeAudit.class);

    private final AuditService audit;
    private final LicenseManager licenses;
    private volatile String lastMode;
    private volatile boolean started;

    public LicenseModeAudit(AuditService audit, LicenseManager licenses) {
        this.audit = audit;
        this.licenses = licenses;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(0)
    public void started() {
        audit.recordQuietly(AuditEntry.of(AuditEntry.Category.SYSTEM, "system.started")
                .actor(AuditEntry.Actor.system())
                .detail("version", audit.version())
                .detail("host", audit.host()));
        try {
            lastMode = audit.lastLicenseMode();
        } catch (RuntimeException e) {
            log.error("Could not read the last recorded license mode: {}", e.toString());
        }
        started = true;
        check();
    }

    @EventListener(ContextClosedEvent.class)
    public void stopped() {
        if (started) {
            started = false;
            audit.recordQuietly(AuditEntry.of(AuditEntry.Category.SYSTEM, "system.stopped")
                    .actor(AuditEntry.Actor.system())
                    .detail("version", audit.version()));
        }
    }

    // Every instance, not one at a time (no ShedLock): each checks its own license state; a change is recorded once,
    // under the audit chain's row lock (AuditService.recordLicenseMode).
    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void check() {
        if (started) {
            accept(licenses.current());
        }
    }

    /** Called with every license check the application makes; records a change of mode once. */
    @Override
    public void accept(LicenseCheck check) {
        String mode = check.mode().name();
        if (!started || mode.equals(lastMode)) {
            return;
        }
        synchronized (this) {
            if (mode.equals(lastMode)) {
                return;
            }
            String old = lastMode;
            lastMode = mode;
            audit.recordLicenseMode(mode, AuditEntry.of(AuditEntry.Category.LICENSE, "license.mode-changed")
                    .actor(AuditEntry.Actor.system())
                    .source(null)
                    .severity(AuditEntry.Severity.NOTICE)
                    .change("mode", old, mode)
                    .detail("state", check.state().name())
                    .detail("message", check.message()));
        }
    }
}
