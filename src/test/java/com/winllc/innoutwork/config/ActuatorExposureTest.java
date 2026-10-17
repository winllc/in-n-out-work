package com.winllc.innoutwork.config;

import com.winllc.innoutwork.security.AppUserDetailsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.ldap.core.support.BaseLdapPathContextSource;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Actuator: the packaged configuration exposes health only, and the security chain lets anyone read health and
 * nothing else. env and metrics, which used to be both exposed and open, would show the configuration (database,
 * directory and mail credentials among it) to anyone who could reach the port.
 */
@WebMvcTest
@Import({SecurityConfig.class, ActuatorExposureTest.Beans.class})
class ActuatorExposureTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean AppUserDetailsService appUsers;
    @MockitoBean BaseLdapPathContextSource contextSource;

    @Configuration
    @EnableWebSecurity
    static class Beans {
        @Bean
        ApplicationProperties applicationProperties() {
            ApplicationProperties properties = new ApplicationProperties();
            properties.setUserBaseDn("dc=winllc,dc=com");
            return properties;
        }
    }

    @Test
    void thePackagedConfigurationExposesHealthOnly() {
        ConfigurableEnvironment env = new StandardEnvironment();
        ConfigDataEnvironmentPostProcessor.applyTo(env, new DefaultResourceLoader(), null, List.of());

        assertEquals("health", env.getProperty("management.endpoints.web.exposure.include"));
    }

    /** What the container healthcheck and the Kubernetes probes call: past the security chain without a sign-in. */
    @Test
    void healthAndItsProbeGroupsNeedNoSignIn() throws Exception {
        for (String path : List.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness")) {
            mockMvc.perform(get(path))
                    .andExpect(result -> assertNotEquals(302, result.getResponse().getStatus(), path))
                    .andExpect(result -> assertNotEquals(403, result.getResponse().getStatus(), path));
        }
    }

    @Test
    void envAndMetricsAreNotOpenToAnonymousCallers() throws Exception {
        mockMvc.perform(get("/actuator/env")).andExpect(redirectedUrl("/login"));
        mockMvc.perform(get("/actuator/metrics")).andExpect(redirectedUrl("/login"));
        mockMvc.perform(get("/actuator/info")).andExpect(redirectedUrl("/login"));
    }

    /** Should an operator expose more, it is for administrators, not every signed-in user. */
    @Test
    void anythingButHealthNeedsAnAdministrator() throws Exception {
        mockMvc.perform(get("/actuator/env").with(user("CN=bob,OU=Users,DC=winllc,DC=com").authorities(() -> "USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/env").with(user("CN=ada,OU=Users,DC=winllc,DC=com").authorities(() -> "ADMIN")))
                .andExpect(result -> assertNotEquals(403, result.getResponse().getStatus()));
    }
}
