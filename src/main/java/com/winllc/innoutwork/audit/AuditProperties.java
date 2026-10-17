package com.winllc.innoutwork.audit;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code ens.audit.*}: the audit trail's outputs and retention. application.yml binds each one to its
 * {@code ENS_AUDIT_*} environment variable (see the README's Audit logging section).
 */
@Data
@ConfigurationProperties(prefix = "ens.audit")
public class AuditProperties {

    /** The product id written into every event. */
    private String product = "readyroom";

    /** ENS_AUDIT_STDOUT: each event as one raw JSON line on stdout (logger {@code ens.audit}). */
    private boolean stdout = true;

    /** ENS_AUDIT_FILE: also append the lines to this file, rotated daily or at 100 MB; off when empty. */
    private String file = "";

    /** ENS_AUDIT_FILE_KEEP: rotated files kept. */
    private int fileKeep = 30;

    /** ENS_AUDIT_RETENTION_DAYS: 0 keeps events forever. */
    private int retentionDays = 0;

    private Splunk splunk = new Splunk();

    private Syslog syslog = new Syslog();

    /** How often the forwarders look for new events when nothing wakes them, in milliseconds. */
    private long pollMillis = 5000;

    /** An output failing for longer than this records audit.delivery-failed. */
    private long failureAlertMinutes = 15;

    @Data
    public static class Splunk {
        /** ENS_AUDIT_SPLUNK_URL, e.g. https://splunk.example:8088; off when empty. */
        private String url = "";
        /** ENS_AUDIT_SPLUNK_TOKEN. Never logged. */
        private String token = "";
        /** ENS_AUDIT_SPLUNK_TOKEN_FILE: read the token from this file instead. */
        private String tokenFile = "";
        private String index = "";
        private String sourcetype = "ens:audit";
        /** ENS_AUDIT_SPLUNK_INSECURE: skip TLS verification. Test setups only. */
        private boolean insecure = false;

        public boolean isEnabled() {
            return url != null && !url.isBlank();
        }
    }

    @Data
    public static class Syslog {
        /** ENS_AUDIT_SYSLOG_HOST; off when empty. */
        private String host = "";
        /** ENS_AUDIT_SYSLOG_PORT; 514 for udp and tcp, 6514 for tls when unset. */
        private Integer port;
        /** udp, tcp or tls. */
        private String protocol = "tcp";
        private String facility = "local0";

        public boolean isEnabled() {
            return host != null && !host.isBlank();
        }

        public int effectivePort() {
            if (port != null && port > 0) {
                return port;
            }
            return "tls".equalsIgnoreCase(protocol) ? 6514 : 514;
        }
    }
}
