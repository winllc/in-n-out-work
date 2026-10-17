package com.winllc.innoutwork.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * {@code ens.*}: the settings every ENS product shares, with the same names and defaults in each (the ENS
 * platform baseline). application.yml binds each one to its {@code ENS_*} environment variable, so the names
 * in the README work exactly as written. The audit trail's {@code ens.audit.*} has its own class,
 * AuditProperties. An impossible value stops startup, naming the setting.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "ens")
public class EnsProperties {

    @Valid
    private Csp csp = new Csp();

    @Valid
    private ConsentBanner consentBanner = new ConsentBanner();

    @Valid
    private Session session = new Session();

    @Valid
    private X509 x509 = new X509();

    /** The Content-Security-Policy header (see web.SecurityHeadersFilter). */
    @Data
    public static class Csp {
        /**
         * ENS_CSP_REPORT_ONLY: send the policy as Content-Security-Policy-Report-Only, which browsers report on
         * but do not enforce. An escape hatch for a page the policy breaks; not for normal running.
         */
        private boolean reportOnly = false;
    }

    /**
     * The DoD notice-and-consent banner (security.ConsentFilter): while enabled, nothing signs in until the
     * browser session has acknowledged it.
     */
    @Data
    public static class ConsentBanner {

        public static final String DEFAULT_TITLE =
                "U.S. Government (USG) Information System (IS) Notice and Consent";

        /** The standard DoD notice-and-consent text; blank lines separate paragraphs. */
        public static final String DEFAULT_TEXT = """
                You are accessing a U.S. Government (USG) Information System (IS) that is provided for \
                USG-authorized use only. By using this IS (which includes any device attached to this IS), you \
                consent to the following conditions:

                - The USG routinely intercepts and monitors communications on this IS for purposes including, but \
                not limited to, penetration testing, COMSEC monitoring, network operations and defense, personnel \
                misconduct (PM), law enforcement (LE), and counterintelligence (CI) investigations.

                - At any time, the USG may inspect and seize data stored on this IS.

                - Communications using, or data stored on, this IS are not private, are subject to routine \
                monitoring, interception, and search, and may be disclosed or used for any USG-authorized purpose.

                - This IS includes security measures (e.g., authentication and access controls) to protect USG \
                interests--not for your personal benefit or privacy.

                - Notwithstanding the above, using this IS does not constitute consent to PM, LE or CI \
                investigative searching or monitoring of the content of privileged communications, or work \
                product, related to personal representation or services by attorneys, psychotherapists, or \
                clergy, and their assistants. Such communications and work product are private and \
                confidential. See User Agreement for details.""";

        /** ENS_CONSENT_BANNER_ENABLED. */
        private boolean enabled = false;
        /** ENS_CONSENT_BANNER_TITLE; blank: the default. */
        private String title = DEFAULT_TITLE;
        /** ENS_CONSENT_BANNER_TEXT, plain text; blank: the DoD text above. */
        private String text = DEFAULT_TEXT;
        /** ENS_CONSENT_BANNER_FILE: read the text from this file instead; wins over the text. */
        private String file = "";

        public void setTitle(String title) {
            this.title = title == null || title.isBlank() ? DEFAULT_TITLE : title;
        }

        public void setText(String text) {
            this.text = text == null || text.isBlank() ? DEFAULT_TEXT : text;
        }
    }

    /** Session lifetime (security.SessionControls). Machine endpoints (the Windows client's API) are exempt. */
    @Data
    public static class Session {
        /** ENS_SESSION_IDLE_TIMEOUT: a session unused this long ends. */
        @NotNull
        private Duration idleTimeout = Duration.ofMinutes(30);
        /** ENS_SESSION_ADMIN_IDLE_TIMEOUT: the idle timeout for administrators; unset: the same as everyone's. */
        private Duration adminIdleTimeout;
        /** ENS_SESSION_ABSOLUTE_TIMEOUT: a session ends this long after sign-in, however busy. */
        @NotNull
        private Duration absoluteTimeout = Duration.ofHours(12);
        /** ENS_SESSION_MAX_PER_USER: concurrent sessions per user; past it the oldest ends. 0: no limit. */
        @Min(0)
        private int maxPerUser = 0;

        /** The administrators' idle timeout, which defaults to everyone's. */
        public Duration effectiveAdminIdleTimeout() {
            return adminIdleTimeout != null ? adminIdleTimeout : idleTimeout;
        }

        @AssertTrue(message = "ENS_SESSION_IDLE_TIMEOUT, ENS_SESSION_ADMIN_IDLE_TIMEOUT and ENS_SESSION_ABSOLUTE_TIMEOUT "
                + "must be at least one minute")
        public boolean isEachTimeoutAtLeastAMinute() {
            Duration minute = Duration.ofMinutes(1);
            return (idleTimeout == null || idleTimeout.compareTo(minute) >= 0)
                    && (adminIdleTimeout == null || adminIdleTimeout.compareTo(minute) >= 0)
                    && (absoluteTimeout == null || absoluteTimeout.compareTo(minute) >= 0);
        }
    }

    /**
     * Client-certificate (X.509, CAC/PIV) sign-in: where the certificate comes from, how it maps to a directory
     * user, which issuers and revocation checks apply (security.x509).
     */
    @Data
    public static class X509 {

        /** The header a TLS-terminating proxy must also send, carrying the shared secret. */
        public static final String PROXY_SECRET_HEADER = "X-ENS-Proxy-Secret";

        public enum Source { SERVLET, HEADER }

        public enum Revocation { OFF, OCSP, CRL, OCSP_THEN_CRL }

        public enum RevocationFail { CLOSED, OPEN }

        /** ENS_X509_SOURCE: servlet (ReadyRoom terminates TLS) or header (a trusted proxy forwards the certificate). */
        @NotNull
        private Source source = Source.SERVLET;
        /** ENS_X509_HEADER: the header the proxy forwards it in, URL-encoded PEM ($ssl_client_escaped_cert). */
        private String header = "X-SSL-Client-Cert";
        /** ENS_X509_TRUSTED_PROXIES: addresses (CIDRs) allowed to send it, separated by ; or ,. */
        private String trustedProxies = "127.0.0.1/32;::1/128";
        /** ENS_X509_PROXY_SECRET: what the proxy sends in X-ENS-Proxy-Secret; at least 32 characters. */
        private String proxySecret = "";
        /** ENS_X509_PROXY_SECRET_FILE: the same, from a file (wins). */
        private String proxySecretFile = "";
        /** ENS_X509_TRUSTED_CA_FILE: PEM CA certificates that issue user certificates (header mode chain check). */
        private String trustedCaFile = "";
        /**
         * ENS_X509_USER_FILTER: an LDAP filter template finding the user, with {upn}, {edipi}, {email}, {uuid},
         * {subject} and {cn}; blank: the certificate's subject DN is the user's DN (the original behaviour).
         */
        private String userFilter = "";
        /** ENS_X509_ALLOWED_ISSUERS: issuer DNs whose certificates may sign in, separated by ; (DNs hold commas). */
        private String allowedIssuers = "";
        /** ENS_X509_REVOCATION: off, ocsp, crl or ocsp-then-crl. */
        @NotNull
        private Revocation revocation = Revocation.OFF;
        /** ENS_X509_OCSP_RESPONDER_URL: ask this responder instead of the certificate's own (AIA). */
        private String ocspResponderUrl = "";
        /** ENS_X509_CRL_DIR: CRL files (DER or PEM), re-read when they change; CRLs are then never fetched. */
        private String crlDir = "";
        /** ENS_X509_REVOCATION_FAIL: closed refuses a certificate whose status is unknown; open allows it. */
        @NotNull
        private RevocationFail revocationFail = RevocationFail.CLOSED;
        /** ENS_X509_REVOCATION_CACHE: how long a good or revoked answer is kept, per issuer and serial. */
        @NotNull
        private Duration revocationCache = Duration.ofMinutes(10);
    }
}
