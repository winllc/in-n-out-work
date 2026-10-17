package com.winllc.innoutwork.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code ens.*}: the settings every ENS product shares, with the same names and defaults in each (the ENS
 * platform baseline). application.yml binds each one to its {@code ENS_*} environment variable, so the names
 * in the README work exactly as written. The audit trail's {@code ens.audit.*} has its own class,
 * AuditProperties.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "ens")
public class EnsProperties {

    private Csp csp = new Csp();

    /** The Content-Security-Policy header (see web.SecurityHeadersFilter). */
    @Data
    public static class Csp {
        /**
         * ENS_CSP_REPORT_ONLY: send the policy as Content-Security-Policy-Report-Only, which browsers report on
         * but do not enforce. An escape hatch for a page the policy breaks; not for normal running.
         */
        private boolean reportOnly = false;
    }
}
