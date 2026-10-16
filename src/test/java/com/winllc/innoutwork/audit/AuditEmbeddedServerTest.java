package com.winllc.innoutwork.audit;

import com.enssolutions.license.LicenseManager;
import com.winllc.innoutwork.support.InMemoryDirectory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The application on a real embedded server, as the image runs it. Boot creates servlet filters (the
 * client API's sign-in auditing among them) while the server starts, earlier than under MockMvc, so
 * the audit store must be constructible at that point.
 */
@SpringBootTest(classes = AuditApplicationContextTest.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:auditserver;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.ldap.username=",
        "spring.ldap.password=",
        "server.ssl.enabled=false",
        "application.refresh-user-records-cron.initial-delay=3600000",
        "application.send-absent-notification-cron.initial-delay=3600000",
        "application.inactive-cron.initial-delay=3600000"
})
class AuditEmbeddedServerTest {

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

    @Test
    void theApplicationStartsOnAnEmbeddedServerAndServesTheSignInPage() throws Exception {
        HttpResponse<String> login = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/login")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, login.statusCode());
    }
}
