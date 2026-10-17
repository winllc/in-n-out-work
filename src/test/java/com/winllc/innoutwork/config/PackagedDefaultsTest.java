package com.winllc.innoutwork.config;

import com.winllc.innoutwork.audit.AuditProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What the packaged application.yml leaves to each site, and the settings that stop startup when impossible: no
 * development mail server or administrators, the mail server's certificate checked, bounded mail timeouts.
 */
class PackagedDefaultsTest {

    private static StandardEnvironment packaged() throws IOException {
        return EnsEnvironmentTest.environment(Map.of());
    }

    @Test
    void noMailServerAdministratorOrSenderIsPackaged() throws IOException {
        StandardEnvironment env = packaged();
        assertNull(env.getProperty("spring.mail.host"));
        assertNull(env.getProperty("spring.mail.username"));
        assertNull(env.getProperty("spring.mail.password"));
        assertNull(env.getProperty("application.super-user-dns[0]"));
        assertEquals("", env.getProperty("application.notification-sender-email"));
        assertEquals("240", env.getProperty("application.checkOutAfterMinutes"));
    }

    @Test
    void theMailServersCertificateIsCheckedAndEveryWaitIsBounded() throws IOException {
        StandardEnvironment env = packaged();
        String smtp = "spring.mail.properties.mail.smtp.";
        assertNull(env.getProperty(smtp + "ssl.trust"), "no trust-all by default");
        assertEquals("true", env.getProperty(smtp + "starttls.required"));
        assertEquals("30000", env.getProperty(smtp + "connectiontimeout"));
        assertEquals("30000", env.getProperty(smtp + "timeout"));
        assertEquals("30000", env.getProperty(smtp + "writetimeout"));
        // The explicit opt-in still works.
        assertEquals("*", EnsEnvironmentTest.environment(Map.of("SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_TRUST", "*"))
                .getProperty(smtp + "ssl.trust"));
    }

    @Test
    void aMailServerNeedsASender() {
        assertThrows(IllegalStateException.class, () -> MailConfig.check("smtp.example.com", ""));
        assertDoesNotThrow(() -> MailConfig.check("smtp.example.com", "readyroom@example.com"));
        assertDoesNotThrow(() -> MailConfig.check(null, ""));
    }

    @EnableConfigurationProperties({ApplicationProperties.class, AuditProperties.class})
    static class Bound {
    }

    private static final ApplicationContextRunner RUNNER = new ApplicationContextRunner()
            .withUserConfiguration(Bound.class)
            .withPropertyValues("application.user-base-dn=dc=example,dc=com");

    @Test
    void impossibleValuesStopStartupNamingTheSetting() {
        RUNNER.run(context -> assertThat(context).hasNotFailed());
        RUNNER.withPropertyValues("application.check-out-after-minutes=0")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("checkOutAfterMinutes"));
        RUNNER.withPropertyValues("application.application-base-url=readyroom.example.com")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("APPLICATION_BASE_URL"));
        RUNNER.withPropertyValues("application.notification-sender-email=not an address")
                .run(context -> assertThat(context).hasFailed());
        RUNNER.withPropertyValues("application.logoff.refused-per-minute=-1")
                .run(context -> assertThat(context).hasFailed());
        RUNNER.withPropertyValues("application.ldap.max-pages=0")
                .run(context -> assertThat(context).hasFailed());
        RUNNER.withPropertyValues("application.user-base-dn=")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("APPLICATION_USERBASEDN"));
        RUNNER.withPropertyValues("ens.audit.syslog.protocol=smoke-signal")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("ENS_AUDIT_SYSLOG_PROTOCOL"));
        RUNNER.withPropertyValues("ens.audit.retention-days=-5")
                .run(context -> assertThat(context).hasFailed());
    }
}
