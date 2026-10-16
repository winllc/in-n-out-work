package com.winllc.innoutwork.audit;

import java.util.function.Supplier;

/**
 * Where call sites record audit events. The application's implementation is {@link AuditService}; classes
 * that record events take it through an optional setter and default to {@link #NOOP}, so slices and unit
 * tests that construct them without the audit store keep working unchanged.
 */
public interface AuditLog {

    /**
     * Stores the event, synchronously, in the current transaction if there is one. Security actions (sign-in,
     * role, permission, configuration and license changes) use this: when the store cannot be written it
     * throws {@link AuditUnavailableException}, and the action must fail with it.
     */
    void record(AuditEntry entry);

    /** For system and job events, and denials: a failure to store is logged as an error, never thrown. */
    void recordQuietly(AuditEntry entry);

    /**
     * Runs {@code change} in a transaction that the events it records join: the change and its audit trail
     * are committed together or not at all.
     */
    <T> T inTransaction(Supplier<T> change);

    default void inTransaction(Runnable change) {
        inTransaction(() -> {
            change.run();
            return null;
        });
    }

    /** Throws {@link AuditUnavailableException} unless the store can be written now; for changes outside the
     * database (the license file) that cannot share its transaction. */
    void requireAvailable();

    /** Records nothing. */
    AuditLog NOOP = new AuditLog() {
        @Override
        public void record(AuditEntry entry) {
        }

        @Override
        public void recordQuietly(AuditEntry entry) {
        }

        @Override
        public <T> T inTransaction(Supplier<T> change) {
            return change.get();
        }

        @Override
        public void requireAvailable() {
        }
    };
}
