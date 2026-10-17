package com.winllc.innoutwork.audit;

import java.util.List;

/** A forwarding destination (Splunk HEC, syslog): sends a batch of stored events, or throws. */
public interface AuditOutput {

    /** The output's name in the status panel and its cursor row: {@code splunk} or {@code syslog}. */
    String name();

    /** Where it sends, for the status panel and the startup log; never includes a secret. */
    String describe();

    /** The most events one {@link #send} takes. */
    default int batchSize() {
        return 100;
    }

    /** Delivers the events, in order, or throws; nothing is considered delivered unless this returns. */
    void send(List<AuditEventRecord> events) throws Exception;
}
