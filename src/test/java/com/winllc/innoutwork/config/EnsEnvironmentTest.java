package com.winllc.innoutwork.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
