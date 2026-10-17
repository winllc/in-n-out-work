package com.winllc.innoutwork.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

/**
 * Exposes Prometheus metrics ({@code /actuator/prometheus}) only on a separate management port. With
 * MANAGEMENT_SERVER_PORT unset (the default) actuator shares the application's port and exposes health alone, as
 * application.yml says; with it set, the management port also exposes {@code prometheus}. That port is for the
 * cluster's monitoring, never published by an ingress or route; SecurityConfig lets health and prometheus be
 * read there without signing in.
 * <p>
 * An exposure list given explicitly in the environment (MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE) still wins.
 */
public class ManagementPortExposure implements EnvironmentPostProcessor, Ordered {

    static final String SOURCE = "ensManagementPortExposure";
    static final String EXPOSURE = "management.endpoints.web.exposure.include";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!separateManagementPort(environment)) {
            return;
        }
        MapPropertySource exposure = new MapPropertySource(SOURCE, Map.of(EXPOSURE, "health,prometheus"));
        if (environment.getPropertySources().contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
            environment.getPropertySources().addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, exposure);
        } else {
            environment.getPropertySources().addLast(exposure);
        }
    }

    /**
     * Whether actuator runs on a port of its own: management.server.port set, not -1 (actuator off) and not the
     * application's port. 0 is a random port of its own.
     */
    public static boolean separateManagementPort(org.springframework.core.env.Environment environment) {
        String port = environment.getProperty("management.server.port");
        if (port == null || port.isBlank() || "-1".equals(port.strip())) {
            return false;
        }
        return "0".equals(port.strip()) || !port.strip().equals(environment.getProperty("server.port", "8080"));
    }

    /** After the configuration files are loaded, so the port can come from any of them. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
