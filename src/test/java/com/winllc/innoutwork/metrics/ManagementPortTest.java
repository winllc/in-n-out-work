package com.winllc.innoutwork.metrics;

import com.enssolutions.license.LicenseManager;
import com.winllc.innoutwork.audit.AuditApplicationContextTest;
import com.winllc.innoutwork.audit.AuditEntry;
import com.winllc.innoutwork.audit.AuditLog;
import com.winllc.innoutwork.support.InMemoryDirectory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole application on a real server with a separate management port (MANAGEMENT_SERVER_PORT): Prometheus
 * and health are read there without signing in, the application's port exposes no metrics, and the baseline's
 * metric names are what Prometheus sees.
 */
@SpringBootTest(classes = AuditApplicationContextTest.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:managementport;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.ldap.username=",
        "spring.ldap.password=",
        "server.ssl.enabled=false",
        "management.server.port=0",
        "management.health.mail.enabled=false",
        "application.refresh-user-records-cron.initial-delay=3600000",
        "application.send-absent-notification-cron.initial-delay=3600000",
        "application.inactive-cron.initial-delay=3600000"
})
class ManagementPortTest {

    static final InMemoryDirectory DIRECTORY = InMemoryDirectory.start();

    @DynamicPropertySource
    static void ldap(DynamicPropertyRegistry registry) {
        registry.add("spring.ldap.urls", DIRECTORY::url);
    }

    @AfterAll
    static void stop() {
        DIRECTORY.close();
    }

    @TestBean(name = "licenseManager")
    LicenseManager licenseManager;

    static LicenseManager licenseManager() {
        return AuditApplicationContextTest.licenseManager();
    }

    @LocalServerPort
    int port;

    @LocalManagementPort
    int managementPort;

    @Autowired
    AuditLog audit;

    private static HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void prometheusIsOpenOnTheManagementPortWithTheBaselinesNames() throws Exception {
        assertNotEquals(port, managementPort);
        audit.record(AuditEntry.of(AuditEntry.Category.SYSTEM, "job.completed").actor(AuditEntry.Actor.system()));
        EnsMetrics.checkIn("in");
        EnsMetrics.logoffRefused("unknown_session");
        EnsMetrics.alertSent();

        HttpResponse<String> metrics = get(managementPort, "/actuator/prometheus");

        assertEquals(200, metrics.statusCode(), metrics.body());
        String body = metrics.body();
        assertTrue(body.contains("ens_license_mode{") && body.contains("mode=\"full\""), body);
        assertTrue(body.contains("ens_license_days_remaining"), "license days");
        assertTrue(body.contains("ens_readyroom_people_in"), "people in");
        assertTrue(body.contains("ens_audit_events_total{") && body.contains("category=\"system\""), "audit events");
        assertTrue(body.contains("ens_readyroom_checkins_total{") && body.contains("action=\"in\""), "check-ins");
        assertTrue(body.contains("ens_readyroom_logoffs_refused_total{"), "refused logoffs");
        assertTrue(body.contains("ens_readyroom_alerts_sent_total"), "alerts");
    }

    @Test
    void theProbesAreOpenOnTheManagementPortAndReadinessCoversWhatReadyRoomNeeds() throws Exception {
        assertEquals(200, get(managementPort, "/actuator/health/liveness").statusCode());
        HttpResponse<String> readiness = get(managementPort, "/actuator/health/readiness");
        assertEquals(200, readiness.statusCode(), readiness.body());
        assertTrue(readiness.body().contains("\"status\":\"UP\""), readiness.body());
    }

    @Test
    void theApplicationsPortExposesNoMetrics() throws Exception {
        HttpResponse<String> metrics = get(port, "/actuator/prometheus");
        // Behind the ADMIN rule like the rest of /actuator there, so an anonymous caller is sent to sign in.
        assertEquals(302, metrics.statusCode(), metrics.body());
        assertFalse(metrics.body().contains("ens_license_mode"), metrics.body());
    }
}
