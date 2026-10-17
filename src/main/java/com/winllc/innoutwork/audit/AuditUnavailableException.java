package com.winllc.innoutwork.audit;

/** The audit store could not be written, so the audited action must not happen. */
public class AuditUnavailableException extends RuntimeException {
    public AuditUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
