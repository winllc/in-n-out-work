package com.winllc.innoutwork.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Prometheus is exposed only when actuator has a port of its own (MANAGEMENT_SERVER_PORT). */
class ManagementPortExposureTest {

    private static String exposure(Map<String, Object> variables) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new HashMap<>(variables)));
        // application.yml's own value, below the environment.
        environment.getPropertySources().addLast(new MapPropertySource("application.yml",
                Map.of("management.endpoints.web.exposure.include", "health", "server.port", "8181")));
        new ManagementPortExposure().postProcessEnvironment(environment, null);
        return environment.getProperty("management.endpoints.web.exposure.include");
    }

    @Test
    void withoutAManagementPortOnlyHealthIsExposed() {
        assertEquals("health", exposure(Map.of()));
        assertEquals("health", exposure(Map.of("MANAGEMENT_SERVER_PORT", "")));
        assertEquals("health", exposure(Map.of("MANAGEMENT_SERVER_PORT", "-1")));
        assertEquals("health", exposure(Map.of("MANAGEMENT_SERVER_PORT", "8181")), "the application's own port");
    }

    @Test
    void aManagementPortOfItsOwnAlsoExposesPrometheus() {
        assertEquals("health,prometheus", exposure(Map.of("MANAGEMENT_SERVER_PORT", "9090")));
    }

    @Test
    void anExplicitExposureListStillWins() {
        assertEquals("health,info", exposure(Map.of("MANAGEMENT_SERVER_PORT", "9090",
                "MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE", "health,info")));
    }
}
