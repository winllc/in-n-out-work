package com.winllc.innoutwork.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ENS_* environment variables of the platform baseline reach the settings they name, exactly as written in
 * the README, through the packaged application.yml, and the defaults hold when they are absent.
 */
class EnsEnvironmentTest {

    /** application.yml as the application reads it, under the given environment variables. */
    static StandardEnvironment environment(Map<String, Object> variables) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, new HashMap<>(variables)));
        List<PropertySource<?>> yaml = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        yaml.forEach(source -> environment.getPropertySources().addLast(source));
        ConfigurationPropertySources.attach(environment);
        return environment;
    }

    static EnsProperties ens(Map<String, Object> variables) throws IOException {
        return Binder.get(environment(variables)).bind("ens", EnsProperties.class).orElseGet(EnsProperties::new);
    }

    @Test
    void theContentSecurityPolicyIsEnforcedUnlessReportOnlyIsAskedFor() throws IOException {
        assertFalse(ens(Map.of()).getCsp().isReportOnly());
        assertTrue(ens(Map.of("ENS_CSP_REPORT_ONLY", "true")).getCsp().isReportOnly());
    }

    @Test
    void theSessionCookieIsSecureWithTlsOrWhenAskedFor() throws IOException {
        String secure = "server.servlet.session.cookie.secure";
        assertEquals("false", environment(Map.of()).getProperty(secure));
        assertEquals("true", environment(Map.of("SSL_ENABLED", "true")).getProperty(secure));
        assertEquals("true", environment(Map.of("ENS_COOKIE_SECURE", "true")).getProperty(secure));
        // The older name keeps working.
        assertEquals("true", environment(Map.of("SESSION_COOKIE_SECURE", "true")).getProperty(secure));
        assertEquals("lax", environment(Map.of()).getProperty("server.servlet.session.cookie.same-site"));
        assertEquals("true", environment(Map.of()).getProperty("server.servlet.session.cookie.http-only"));
    }

    @Test
    void theConsentBannerIsOffWithTheDodTextUntilConfigured() throws IOException {
        EnsProperties.ConsentBanner defaults = ens(Map.of()).getConsentBanner();
        assertFalse(defaults.isEnabled());
        assertEquals(EnsProperties.ConsentBanner.DEFAULT_TITLE, defaults.getTitle());
        assertEquals(EnsProperties.ConsentBanner.DEFAULT_TEXT, defaults.getText());
        assertEquals("", defaults.getFile());

        EnsProperties.ConsentBanner set = ens(Map.of("ENS_CONSENT_BANNER_ENABLED", "true",
                "ENS_CONSENT_BANNER_TITLE", "Notice", "ENS_CONSENT_BANNER_TEXT", "Be good.",
                "ENS_CONSENT_BANNER_FILE", "/etc/banner.txt")).getConsentBanner();
        assertTrue(set.isEnabled());
        assertEquals("Notice", set.getTitle());
        assertEquals("Be good.", set.getText());
        assertEquals("/etc/banner.txt", set.getFile());
    }

    @Test
    void sessionLimitsDefaultAsTheBaselineSays() throws IOException {
        EnsProperties.Session defaults = ens(Map.of()).getSession();
        assertEquals(Duration.ofMinutes(30), defaults.getIdleTimeout());
        assertNull(defaults.getAdminIdleTimeout());
        assertEquals(Duration.ofMinutes(30), defaults.effectiveAdminIdleTimeout());
        assertEquals(Duration.ofHours(12), defaults.getAbsoluteTimeout());
        assertEquals(0, defaults.getMaxPerUser());
        assertEquals("30m", environment(Map.of()).getProperty("server.servlet.session.timeout"));

        EnsProperties.Session set = ens(Map.of("ENS_SESSION_IDLE_TIMEOUT", "20m",
                "ENS_SESSION_ADMIN_IDLE_TIMEOUT", "15m", "ENS_SESSION_ABSOLUTE_TIMEOUT", "8h",
                "ENS_SESSION_MAX_PER_USER", "3")).getSession();
        assertEquals(Duration.ofMinutes(20), set.getIdleTimeout());
        assertEquals(Duration.ofMinutes(15), set.effectiveAdminIdleTimeout());
        assertEquals(Duration.ofHours(8), set.getAbsoluteTimeout());
        assertEquals(3, set.getMaxPerUser());
    }

    @EnableConfigurationProperties(EnsProperties.class)
    static class Bound {
    }

    /** An impossible value stops startup, naming the setting, instead of taking effect. */
    @Test
    void impossibleValuesStopStartup() {
        ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Bound.class);
        runner.withPropertyValues("ens.session.max-per-user=-1")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("maxPerUser"));
        runner.withPropertyValues("ens.session.idle-timeout=10s")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("ENS_SESSION_IDLE_TIMEOUT"));
        runner.withPropertyValues("ens.session.idle-timeout=15m", "ens.session.max-per-user=3")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
