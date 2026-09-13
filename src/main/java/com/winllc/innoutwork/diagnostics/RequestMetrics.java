package com.winllc.innoutwork.diagnostics;

/**
 * Per-thread counts of the SQL statements and LDAP operations one piece of work caused, for the request
 * metrics log. Counting only happens between {@link #start()} and {@link #stop()}; anything outside (a
 * scheduled job, a background cache refresh) is ignored.
 */
public final class RequestMetrics {

    /** What one request did. */
    public record Counts(int sqlStatements, int ldapOperations) {
    }

    private static final class Counter {
        private int sql;
        private int ldap;
    }

    private static final ThreadLocal<Counter> CURRENT = new ThreadLocal<>();

    private RequestMetrics() {
    }

    public static void start() {
        CURRENT.set(new Counter());
    }

    /** Ends counting on this thread and returns what was counted. */
    public static Counts stop() {
        Counter counter = CURRENT.get();
        CURRENT.remove();
        return counter == null ? new Counts(0, 0) : new Counts(counter.sql, counter.ldap);
    }

    static void sqlStatement() {
        Counter counter = CURRENT.get();
        if (counter != null) {
            counter.sql++;
        }
    }

    static void ldapOperation() {
        Counter counter = CURRENT.get();
        if (counter != null) {
            counter.ldap++;
        }
    }
}
