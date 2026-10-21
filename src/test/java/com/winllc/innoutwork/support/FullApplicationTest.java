package com.winllc.innoutwork.support;

import com.enssolutions.license.LicenseManager;
import com.winllc.innoutwork.audit.AuditApplicationContextTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;

/**
 * The whole application, wired as it runs (every security chain, the LDAP provider against an in-memory
 * directory, the audit store, scheduling) over H2, with a full test license. Carol Clark is the administrator
 * (application.super-user-dns); Alice Adams owns the Engineering group (Alice and Bob). Tests extending this share
 * one context.
 */
@SpringBootTest(classes = AuditApplicationContextTest.Application.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:fullapp;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.ldap.username=",
        "spring.ldap.password=",
        "logging.level.com.winllc=DEBUG",
        "application.super-user-dns[0]=" + FullApplicationTest.ADMIN,
        "application.application-base-url=https://readyroom.example.com/",
        "application.webhooks.secret-key=test-webhook-key-0123456789abcdef0123456789",
        "application.webhooks.poll-millis=3600000",
        "application.refresh-user-records-cron.initial-delay=3600000",
        "application.send-absent-notification-cron.initial-delay=3600000",
        "application.inactive-cron.initial-delay=3600000"
})
@AutoConfigureMockMvc
public abstract class FullApplicationTest {

    public static final String ADMIN = "cn=Carol Clark,ou=Users,dc=winllc,dc=com";
    public static final String ALICE = "cn=Alice Adams,ou=Users,dc=winllc,dc=com";
    public static final String BOB = "cn=Bob Barker,ou=Users,dc=winllc,dc=com";
    public static final String JANE = "cn=Doe\\, Jane,ou=Users,dc=winllc,dc=com";

    protected static final InMemoryDirectory DIRECTORY = InMemoryDirectory.start();

    @DynamicPropertySource
    static void ldap(DynamicPropertyRegistry registry) {
        registry.add("spring.ldap.urls", DIRECTORY::url);
    }

    @TestBean(name = "licenseManager")
    LicenseManager licenseManager;

    static LicenseManager licenseManager() {
        return AuditApplicationContextTest.licenseManager();
    }
}
