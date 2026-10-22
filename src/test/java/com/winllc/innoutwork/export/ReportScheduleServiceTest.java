package com.winllc.innoutwork.export;

import com.winllc.innoutwork.audit.AuditEventRepository;
import com.winllc.innoutwork.audit.AuditLog;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.cron.ScheduledReportsJob;
import com.winllc.innoutwork.model.ReportSchedule;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.ReportScheduleRepository;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.security.AccessPolicy;
import com.winllc.innoutwork.security.DirectoryAccounts;
import com.winllc.innoutwork.service.InvalidRequestException;
import com.winllc.innoutwork.service.LdapService;
import com.winllc.innoutwork.service.Mailer;
import com.winllc.innoutwork.service.PersonalDataService;
import com.winllc.innoutwork.service.TimeZones;
import com.winllc.innoutwork.support.FullApplicationTest;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Scheduled, e-mailed reports: only for people who may run the group's report, re-checked at every run, sent with
 * the report attached, audited, and gone with an erased person.
 */
class ReportScheduleServiceTest extends FullApplicationTest {

    private static final String ENGINEERING = "cn=Engineering,ou=Groups,dc=winllc,dc=com";

    @Autowired ReportScheduleRepository repository;
    @Autowired ReportExports exports;
    @Autowired AccessPolicy accessPolicy;
    @Autowired DirectoryAccounts accounts;
    @Autowired LdapService ldapService;
    @Autowired UserRecordRepository people;
    @Autowired TimeZones timeZones;
    @Autowired ApplicationProperties properties;
    @Autowired AuditLog auditLog;
    @Autowired AuditEventRepository audit;
    @Autowired SpringTemplateEngine templates;
    @Autowired PersonalDataService personalData;

    private final List<MimeMessage> sent = new ArrayList<>();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-21T12:00:00Z"));
    private ReportScheduleService service;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        sent.clear();
        role(ALICE, UserRoleEnum.MANAGER);
        role(BOB, UserRoleEnum.USER);
        JavaMailSender mail = mock(JavaMailSender.class);
        when(mail.createMimeMessage()).thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
        doAnswer(i -> {
            sent.add(i.getArgument(0));
            return null;
        }).when(mail).send(any(MimeMessage.class));
        Mailer mailer = new Mailer(mail, templates, properties, Runnable::run);
        Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        service = new ReportScheduleService(repository, exports, accessPolicy, accounts, ldapService, people, timeZones,
                mailer, properties, auditLog, clock);
    }

    private void role(String dn, UserRoleEnum role) {
        UserRecord record = people.findByDnIgnoreCase(dn).orElseGet(UserRecord::new);
        record.setDn(dn);
        record.setUserRole(role);
        people.save(record);
    }

    private static UsernamePasswordAuthenticationToken signedIn(String dn, String... roles) {
        return UsernamePasswordAuthenticationToken.authenticated(dn, null,
                Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList());
    }

    @Test
    void periodsAndSendingTimes() {
        LocalDate wednesday = LocalDate.of(2026, 10, 21);
        assertArrayEquals(new LocalDate[]{wednesday.minusDays(1), wednesday.minusDays(1)},
                ReportScheduleService.period(ReportSchedule.Frequency.DAILY, wednesday));
        assertArrayEquals(new LocalDate[]{LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 18)},
                ReportScheduleService.period(ReportSchedule.Frequency.WEEKLY, wednesday));
        assertArrayEquals(new LocalDate[]{LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 18)},
                ReportScheduleService.period(ReportSchedule.Frequency.WEEKLY, LocalDate.of(2026, 10, 19)));
        assertArrayEquals(new LocalDate[]{LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)},
                ReportScheduleService.period(ReportSchedule.Frequency.MONTHLY, wednesday));

        ZoneId ny = ZoneId.of("America/New_York");
        Instant at = Instant.parse("2026-10-21T12:00:00Z"); // 08:00 in New York
        assertEquals(LocalDate.of(2026, 10, 22).atTime(7, 0).atZone(ny).toInstant(),
                ReportScheduleService.nextRun(ReportSchedule.Frequency.DAILY, 7, ny, at));
        assertEquals(DayOfWeek.MONDAY, ReportScheduleService.nextRun(ReportSchedule.Frequency.WEEKLY, 7, ny, at)
                .atZone(ny).getDayOfWeek());
        assertEquals(LocalDate.of(2026, 11, 1).atTime(7, 0).atZone(ny).toInstant(),
                ReportScheduleService.nextRun(ReportSchedule.Frequency.MONTHLY, 7, ny, at));
    }

    @Test
    void onlyPeopleWhoMayRunTheReportScheduleOrReceiveIt() {
        assertThrows(AccessDeniedException.class, () -> service.create("x", ENGINEERING,
                ReportSchedule.Frequency.DAILY, "csv", "alice@winllc.com", 7, signedIn(BOB, "USER")));
        InvalidRequestException refused = assertThrows(InvalidRequestException.class, () -> service.create("x",
                ENGINEERING, ReportSchedule.Frequency.DAILY, "csv", "alice@winllc.com\nbob@winllc.com\nnobody@x.example",
                7, signedIn(ALICE, "USER", "MANAGER")));
        assertTrue(refused.getMessage().contains("Bob Barker (may not see"), refused.getMessage());
        assertTrue(refused.getMessage().contains("nobody@x.example (not in the directory)"), refused.getMessage());
        assertThrows(InvalidRequestException.class, () -> service.create("x", ENGINEERING,
                ReportSchedule.Frequency.DAILY, "csv", "", 7, signedIn(ALICE, "USER", "MANAGER")));

        ReportSchedule schedule = service.create("Weekly", ENGINEERING, ReportSchedule.Frequency.WEEKLY, "xlsx",
                "alice@winllc.com; " + ALICE, 7, signedIn(ALICE, "USER", "MANAGER"));
        assertEquals(1, schedule.getRecipientList().size());
        assertTrue(schedule.getRecipientList().getFirst().equalsIgnoreCase(ALICE), schedule.getRecipients());
        assertNotNull(schedule.getNextRunAt());
        assertTrue(audit.findAll().stream().anyMatch(a -> a.getAction().equals("report.schedule-created")));
    }

    @Test
    void aDueReportIsSentWithItsAttachmentAuditedAndRescheduled() throws Exception {
        ReportSchedule schedule = service.create("Daily", ENGINEERING, ReportSchedule.Frequency.DAILY, "csv",
                "alice@winllc.com", 7, signedIn(ALICE, "USER", "MANAGER"));
        assertEquals(0, service.runDue(), "not due yet");
        now.set(schedule.getNextRunAt().plusSeconds(1));
        assertEquals(1, service.runDue(), () -> "now " + now.get() + ", stored " + repository.findAll());

        assertEquals(1, sent.size(), () -> repository.findAll().toString());
        MimeMessage message = sent.getFirst();
        assertEquals("alice@winllc.com", message.getAllRecipients()[0].toString());
        MimeMultipart parts = (MimeMultipart) message.getContent();
        boolean attached = false;
        for (int i = 0; i < parts.getCount(); i++) {
            String name = parts.getBodyPart(i).getFileName();
            attached |= name != null && name.endsWith(".csv");
        }
        assertTrue(attached, "the CSV is attached");
        ReportSchedule after = repository.findById(schedule.getId()).orElseThrow();
        assertTrue(after.getLastResult().startsWith("Sent to 1 of 1"), after.getLastResult());
        assertTrue(after.getNextRunAt().isAfter(now.get()));
        assertTrue(audit.findAll().stream().anyMatch(a -> a.getAction().equals("data.exported")
                && a.getLine().contains("\"via\":\"scheduled\"")));
    }

    @Test
    void aScheduleWhoseOwnerMayNoLongerRunItIsTurnedOff() {
        ReportSchedule schedule = service.create("Daily", ENGINEERING, ReportSchedule.Frequency.DAILY, "csv",
                "alice@winllc.com", 7, signedIn(ALICE, "USER", "MANAGER"));
        role(ALICE, UserRoleEnum.USER);
        now.set(schedule.getNextRunAt().plusSeconds(1));
        service.runDue();
        ReportSchedule after = repository.findById(schedule.getId()).orElseThrow();
        assertFalse(after.isEnabled());
        assertTrue(sent.isEmpty(), "nothing sent");
    }

    @Test
    void aDisabledOwnerTurnsTheScheduleOffAndADisabledRecipientIsSkipped() {
        ReportSchedule schedule = service.create("Daily", ENGINEERING, ReportSchedule.Frequency.DAILY, "csv",
                "alice@winllc.com", 7, signedIn(ADMIN, "USER", "ADMIN"));
        now.set(schedule.getNextRunAt().plusSeconds(1));
        try {
            setAttribute(ALICE, "nsAccountLock", "true");
            service.runDue();
            ReportSchedule after = repository.findById(schedule.getId()).orElseThrow();
            assertTrue(after.isEnabled());
            assertTrue(after.getLastResult().startsWith("Not sent: none of its recipients"), after.getLastResult());
            assertTrue(sent.isEmpty());

            setAttribute(ADMIN, "userAccountControl", "514");
            now.set(after.getNextRunAt().plusSeconds(1));
            service.runDue();
            assertFalse(repository.findById(schedule.getId()).orElseThrow().isEnabled(), "turned off");
        } finally {
            clearAccountStatus(ALICE, ADMIN);
        }
    }

    @Test
    void anErasedPersonsSchedulesGoWithThem() {
        service.create("Daily", ENGINEERING, ReportSchedule.Frequency.DAILY, "csv", "alice@winllc.com", 7,
                signedIn(ALICE, "USER", "MANAGER"));
        // What erasure calls (PersonalDataService.erase; its other tables are PostgreSQL-only, see PersonalDataPostgresTest).
        assertEquals(1, service.forget(ALICE));
        assertEquals(0, repository.count());
    }

    @Test
    void theJobRunsOnOneReplicaAtATime() throws Exception {
        assertNotNull(ScheduledReportsJob.class.getMethod("run").getAnnotation(SchedulerLock.class));
    }
}
