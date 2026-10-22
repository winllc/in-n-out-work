package com.winllc.innoutwork.a11y;

import com.enssolutions.license.LicenseManager;
import com.winllc.innoutwork.api.ApiScope;
import com.winllc.innoutwork.api.ApiTokenService;
import com.winllc.innoutwork.audit.AuditApplicationContextTest;
import com.winllc.innoutwork.config.LdapConnectionConfig;
import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.constant.NotificationTypeEnum;
import com.winllc.innoutwork.constant.RequestState;
import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.export.ReportScheduleService;
import com.winllc.innoutwork.license.LicenseManagerConfig;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.NotificationRecord;
import com.winllc.innoutwork.model.ReportSchedule;
import com.winllc.innoutwork.model.StatusRequest;
import com.winllc.innoutwork.model.UserEventRecord;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.NotificationRepository;
import com.winllc.innoutwork.repository.StatusRequestRepository;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.support.InMemoryDirectory;
import com.winllc.innoutwork.webhook.WebhookEventType;
import com.winllc.innoutwork.webhook.WebhookService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import javax.naming.directory.BasicAttribute;
import javax.naming.directory.DirContext;
import javax.naming.directory.ModificationItem;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ReadyRoom with seeded data, for the automated accessibility checks (a11y/, axe-core through Playwright, in CI):
 * the whole application over H2 and an in-memory directory, a full test license, the notice-and-consent banner on,
 * on http://localhost:8199 ({@code ./gradlew a11yServer}, or {@code -Pa11y.port=...}). Test code only: the
 * accounts are carol (administrator), alice (manager) and bob (user), password {@code password}.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackages = "com.winllc.innoutwork", excludeFilters = {
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AuditApplicationContextTest.MainClassesOnly.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = LicenseManagerConfig.class)})
@EntityScan(basePackages = {"com.winllc.innoutwork.model", "com.winllc.innoutwork.audit"})
@EnableJpaRepositories(basePackages = {"com.winllc.innoutwork.repository", "com.winllc.innoutwork.audit"})
@EnableScheduling
@ConfigurationPropertiesScan("com.winllc.innoutwork.config")
public class A11yServer {

    static final String ADMIN = "cn=Carol Clark,ou=Users,dc=winllc,dc=com";
    static final String ALICE = "cn=Alice Adams,ou=Users,dc=winllc,dc=com";
    static final String BOB = "cn=Bob Barker,ou=Users,dc=winllc,dc=com";
    static final String JANE = "cn=Doe\\, Jane,ou=Users,dc=winllc,dc=com";
    static final String ENGINEERING = "cn=Engineering,ou=Groups,dc=winllc,dc=com";

    /** The test key's license manager, in place of the production one. */
    @Bean
    LicenseManager licenseManager() {
        return AuditApplicationContextTest.licenseManager();
    }

    public static void main(String[] args) {
        LdapConnectionConfig.applyPoolDefaults();
        InMemoryDirectory directory = InMemoryDirectory.start();
        LdapTemplate ldap = directory.ldapTemplate();
        for (String dn : List.of(ADMIN, ALICE, BOB, JANE)) {
            ldap.modifyAttributes(dn, new ModificationItem[]{new ModificationItem(DirContext.ADD_ATTRIBUTE,
                    new BasicAttribute("userPassword", "password"))});
        }
        String port = System.getProperty("a11y.port", "8199");

        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        properties.put("server.port", port);
        properties.put("server.ssl.enabled", "false");
        properties.put("spring.datasource.url",
                "jdbc:h2:mem:a11y;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=MONTH,VALUE");
        properties.put("spring.datasource.driver-class-name", "org.h2.Driver");
        properties.put("spring.datasource.username", "sa");
        properties.put("spring.datasource.password", "");
        properties.put("spring.jpa.properties.hibernate.dialect", "org.hibernate.dialect.H2Dialect");
        properties.put("spring.jpa.hibernate.ddl-auto", "create-drop");
        properties.put("spring.flyway.enabled", "false");
        properties.put("spring.ldap.urls", directory.url());
        properties.put("spring.ldap.username", "");
        properties.put("spring.ldap.password", "");
        properties.put("management.health.mail.enabled", "false");
        properties.put("application.super-user-dns[0]", ADMIN);
        properties.put("application.application-base-url", "http://localhost:" + port);
        properties.put("application.webhooks.secret-key", "a11y-only-webhook-key-0123456789abcdef");
        properties.put("application.reports.site-time-zones[0]", "Chicago=America/Chicago");
        properties.put("ens.consent-banner.enabled", "true");
        properties.put("application.refresh-user-records-cron.initial-delay", "3600000");
        properties.put("application.send-absent-notification-cron.initial-delay", "3600000");
        properties.put("application.inactive-cron.initial-delay", "3600000");
        properties.put("application.reports.check-initial-delay-millis", "3600000");

        // As command-line arguments, which win over application.yml.
        List<String> arguments = new java.util.ArrayList<>(List.of(args));
        properties.forEach((k, v) -> arguments.add("--" + k + "=" + v));
        ConfigurableApplicationContext context = new SpringApplication(A11yServer.class)
                .run(arguments.toArray(String[]::new));
        seed(context);
        Runtime.getRuntime().addShutdownHook(new Thread(directory::close));
    }

    /** Enough data that every page shows its tables, lists and charts, not only their empty states. */
    static void seed(ConfigurableApplicationContext context) {
        // The job-lock table Flyway makes (V6), for the jobs that run while the checks do.
        context.getBean(org.springframework.jdbc.core.JdbcTemplate.class).execute("CREATE TABLE IF NOT EXISTS shedlock"
                + " (name varchar(64) NOT NULL PRIMARY KEY, lock_until timestamp(3) NOT NULL,"
                + " locked_at timestamp(3) NOT NULL, locked_by varchar(255) NOT NULL)");
        UserRecordRepository people = context.getBean(UserRecordRepository.class);
        CheckInOutRecordRepository records = context.getBean(CheckInOutRecordRepository.class);
        UserEventRecordRepository events = context.getBean(UserEventRecordRepository.class);
        StatusRequestRepository requests = context.getBean(StatusRequestRepository.class);
        NotificationRepository notifications = context.getBean(NotificationRepository.class);

        Map<String, String> locations = Map.of(ADMIN, "Chicago", ALICE, "New York", BOB, "New York", JANE, "Los Angeles");
        for (String dn : List.of(ADMIN, ALICE, BOB, JANE)) {
            UserRecord record = people.findByDnIgnoreCase(dn).orElseGet(UserRecord::new);
            record.setDn(dn);
            record.setUserRole(dn.equals(ALICE) ? UserRoleEnum.MANAGER : UserRoleEnum.USER);
            record.setLocation(locations.get(dn));
            record.setOrganization("WinLLC");
            people.save(record);
            ZonedDateTime morning = ZonedDateTime.now().truncatedTo(ChronoUnit.DAYS).plusHours(8);
            for (int day = 0; day < 10; day++) {
                if (dn.equals(JANE) && day == 0) {
                    continue;
                }
                record(records, dn, CheckInOutEnum.CHECK_IN, morning.minusDays(day).plusMinutes(day * 3L));
                if (day > 0) {
                    record(records, dn, CheckInOutEnum.LOCK, morning.minusDays(day).plusHours(4));
                    record(records, dn, CheckInOutEnum.UNLOCK, morning.minusDays(day).plusHours(5));
                    record(records, dn, CheckInOutEnum.CHECK_OUT, morning.minusDays(day).plusHours(9));
                }
            }
        }
        events.save(UserEventRecord.builder().dn(BOB).date(LocalDate.now().plusDays(3))
                .status(UserStatusEnum.WORK_FROM_HOME).build());
        requests.save(StatusRequest.builder().requesterDn(BOB).status(UserStatusEnum.SCHEDULED_LEAVE)
                .fromDate(LocalDate.now().plusDays(7)).toDate(LocalDate.now().plusDays(9))
                .state(RequestState.REQUESTED).note("Family visit").createdAt(ZonedDateTime.now()).build());
        NotificationRecord alert = new NotificationRecord();
        alert.setNotificationUuid(java.util.UUID.randomUUID().toString());
        alert.setType(NotificationTypeEnum.ABSENT);
        alert.setAboutUserDn(JANE);
        alert.setForUserDn(ALICE);
        alert.setNotificationDate(ZonedDateTime.now());
        alert.setExpectedCheckInTime(LocalTime.of(9, 0));
        notifications.save(alert);

        UsernamePasswordAuthenticationToken admin = UsernamePasswordAuthenticationToken.authenticated(ADMIN, null,
                List.of(new SimpleGrantedAuthority("ADMIN"), new SimpleGrantedAuthority("USER")));
        context.getBean(ApiTokenService.class).create("Payroll", EnumSet.of(ApiScope.PEOPLE_READ,
                ApiScope.ATTENDANCE_READ), 90, 120, admin);
        context.getBean(WebhookService.class).create("HR system", "https://93.184.216.34/readyroom",
                List.of(WebhookEventType.STATUS_CHANGED), admin);
        context.getBean(ReportScheduleService.class).create("Weekly attendance", ENGINEERING,
                ReportSchedule.Frequency.WEEKLY, "xlsx", "alice@winllc.com", 7, admin);
        System.out.println("A11Y SERVER READY");
    }

    private static void record(CheckInOutRecordRepository records, String dn, CheckInOutEnum action, ZonedDateTime at) {
        CheckInOutRecord r = new CheckInOutRecord();
        r.setDn(dn);
        r.setAction(action);
        r.setTimestamp(at);
        r.setOrganization("WinLLC");
        records.save(r);
    }
}
