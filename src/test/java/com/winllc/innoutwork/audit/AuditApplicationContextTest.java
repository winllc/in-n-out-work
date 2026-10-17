package com.winllc.innoutwork.audit;

import com.enssolutions.license.LicenseManager;
import com.winllc.innoutwork.config.InOutWorkApplication;
import com.winllc.innoutwork.license.TestLicenses;
import com.winllc.innoutwork.support.InMemoryDirectory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole application, wired as it runs (production security chains, the LDAP sign-in provider against an
 * in-memory directory, scheduling), over H2: the audit trail's wiring holds together, the store records startup,
 * a failed directory sign-in goes through the real chain into the trail, and the pull API has its own chain.
 */
@SpringBootTest(classes = AuditApplicationContextTest.Application.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:auditapp;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.ldap.username=",
        "spring.ldap.password=",
        "application.refresh-user-records-cron.initial-delay=3600000",
        "application.send-absent-notification-cron.initial-delay=3600000",
        "application.inactive-cron.initial-delay=3600000"
})
@AutoConfigureMockMvc
class AuditApplicationContextTest {

    /**
     * {@link InOutWorkApplication}'s configuration, scanning the main classes only: the test classes share its
     * packages, and their slices' configurations would otherwise be picked up as well.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @ComponentScan(basePackages = "com.winllc.innoutwork", excludeFilters = @ComponentScan.Filter(
            type = FilterType.CUSTOM, classes = MainClassesOnly.class))
    @EntityScan(basePackages = {"com.winllc.innoutwork.model", "com.winllc.innoutwork.audit"})
    @EnableJpaRepositories(basePackages = {"com.winllc.innoutwork.repository", "com.winllc.innoutwork.audit"})
    @EnableScheduling
    @ConfigurationPropertiesScan("com.winllc.innoutwork.config")
    static class Application {
    }

    /** Excludes test classes and the application class itself (whose own scan is unfiltered). */
    static class MainClassesOnly implements TypeFilter {
        @Override
        public boolean match(MetadataReader reader, MetadataReaderFactory factory) throws IOException {
            String location = reader.getResource().getURL().toString();
            return location.contains("/test/") || location.contains("test-classes")
                    || reader.getClassMetadata().getClassName().equals(InOutWorkApplication.class.getName());
        }
    }

    static final InMemoryDirectory DIRECTORY = InMemoryDirectory.start();

    @DynamicPropertySource
    static void ldap(DynamicPropertyRegistry registry) {
        registry.add("spring.ldap.urls", DIRECTORY::url);
    }

    @AfterAll
    static void stop() {
        DIRECTORY.close();
    }

    /** The test key's license manager over a temporary directory, in place of the production one. */
    @TestBean(name = "licenseManager")
    LicenseManager licenseManager;

    static LicenseManager licenseManager() {
        try {
            Path dir = Files.createTempDirectory("readyroom-audit-app");
            dir.toFile().deleteOnExit();
            LicenseManager licenses = TestLicenses.manager(dir);
            TestLicenses.installFile(licenses, TestLicenses.full());
            return licenses;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired AuditEventRepository events;
    @Autowired AuditQueryService query;
    @Autowired AuditLog auditLog;

    private List<AuditEventRecord> all() {
        return events.findAll(Sort.by("seq"));
    }

    @Test
    void theApplicationStartsWithTheAuditTrailWiredIn() {
        assertTrue(auditLog instanceof AuditService);
        List<AuditEventRecord> stored = all();
        assertFalse(stored.isEmpty());
        assertEquals("system.started", stored.getFirst().getAction());
        assertTrue(stored.stream().anyMatch(e -> e.getAction().equals("license.mode-changed")),
                "the first start records the license mode");
        assertTrue(query.verify().intact());
    }

    @Test
    void aFailedDirectorySignInIsRecordedThroughTheRealChain() throws Exception {
        mockMvc.perform(formLogin("/login").user("Alice Adams").password("not-her-password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/login?error*"));

        AuditEventRecord failed = all().stream().filter(e -> e.getAction().equals("auth.login")).toList().getLast();
        assertEquals("failure", failed.getOutcome());
        assertEquals("Alice Adams", failed.getActorId());
        assertTrue(failed.getLine().contains("\"method\":\"ldap\""), failed.getLine());
        assertFalse(failed.getLine().contains("not-her-password"));
    }

    @Test
    void thePullApiAndTheAuditPageAreGuarded() throws Exception {
        mockMvc.perform(get("/api/audit/events")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/app/admin/audit")).andExpect(status().is3xxRedirection());
    }
}
