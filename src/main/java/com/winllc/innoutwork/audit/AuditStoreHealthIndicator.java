package com.winllc.innoutwork.audit;

import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

/**
 * {@code auditStore}: whether the audit trail can be written, part of readiness. ReadyRoom refuses sign-ins and
 * security changes it cannot audit, so without the store it cannot serve. The check takes the chain head's row
 * lock briefly, as every audit write does, which needs the same rights.
 */
@Component("auditStore")
public class AuditStoreHealthIndicator extends AbstractHealthIndicator {

    private final AuditService audit;

    public AuditStoreHealthIndicator(AuditService audit) {
        super("The audit store cannot be written");
        this.audit = audit;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        audit.requireAvailable();
        builder.up().withDetail("lastSeq", audit.headSeq());
    }
}
