package com.winllc.innoutwork.audit;

/**
 * An authentication that is a system acting for someone (a public API token), recorded in the audit trail as
 * itself rather than as the person it acts for.
 */
public interface ServiceAuthentication {

    /** The actor its audit events name. */
    AuditEntry.Actor auditActor();
}
