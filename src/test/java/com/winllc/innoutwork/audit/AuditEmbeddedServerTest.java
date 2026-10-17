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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        "spring.flyway.enabled=false",
        "spring.ldap.username=",
        "spring.ldap.password=",
        "server.ssl.enabled=false",
        // The packaged mail server is not reachable from here; health would report DOWN.
        "management.health.mail.enabled=false",
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

    /**
     * The sign-in form's CSRF token starts a session. Its cookie is never sent with another site's posts
     * (SameSite=Lax) and is out of scripts' reach (HttpOnly), as the packaged configuration sets it.
     */
    @Test
    void theSessionCookieIsSameSiteLaxAndHttpOnly() throws Exception {
        HttpResponse<String> login = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/login")).build(),
                HttpResponse.BodyHandlers.ofString());

        String cookie = login.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith("JSESSIONID=")).findFirst()
                .orElseThrow(() -> new AssertionError("no session cookie: " + login.headers().map()));
        assertTrue(cookie.contains("SameSite=Lax"), cookie);
        assertTrue(cookie.contains("HttpOnly"), cookie);
    }

    /** The image's healthcheck reads health without signing in; env, as packaged, is neither exposed nor open. */
    @Test
    void healthIsOpenAndEnvIsNot() throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> health = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, health.statusCode(), health.body());
        assertTrue(health.body().contains("\"status\":\"UP\""), health.body());
        assertFalse(health.body().contains("components"), "no details for anonymous callers: " + health.body());

        HttpResponse<String> env = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/env")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(302, env.statusCode(), "sent to sign in, not answered");
    }
}
