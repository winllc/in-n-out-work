package com.winllc.innoutwork.migration;

import com.winllc.innoutwork.audit.AuditEntry;
import com.winllc.innoutwork.audit.AuditLog;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.model.AttendanceSummary;
import com.winllc.innoutwork.repository.AttendanceSummaryRepository;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.NotificationRepository;
import com.winllc.innoutwork.repository.RetentionStateRepository;
import com.winllc.innoutwork.repository.StatusRequestRepository;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
import com.winllc.innoutwork.service.RetentionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.lang.reflect.Constructor;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The retention purge on PostgreSQL: what it summarises, what it deletes and what it leaves, that a later run adds to
 * a month already summarised without counting anything twice, that it works in batches, and that each run is
 * audited with its counts.
 */
class RetentionPostgresTest extends PostgresTestSupport {

    private static final ZoneId ZONE = ZoneId.of("America/New_York");
    private static final String BOB = "cn=Bob Barker,ou=Users,dc=winllc,dc=com";
    private static final String CAROL = "cn=Carol Clark,ou=Users,dc=winllc,dc=com";
    /** "Today" for the purge: Monday 4 May 2026, with 40 days kept the cutoff is Wednesday 25 March. */
    private static final LocalDate TODAY = LocalDate.of(2026, 5, 4);
    private static final LocalDate CUTOFF = TODAY.minusDays(40);

    @Autowired private CheckInOutRecordRepository checkIns;
    @Autowired private UserEventRecordRepository events;
    @Autowired private NotificationRepository notifications;
    @Autowired private StatusRequestRepository requests;
    @Autowired private AttendanceSummaryRepository summaries;
    @Autowired private RetentionStateRepository state;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;

    private final List<AuditEntry> audited = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM user_event_records");
        jdbc.update("DELETE FROM status_requests");
        jdbc.update("DELETE FROM check_in_out_records");
        jdbc.update("DELETE FROM notification_records");
        jdbc.update("DELETE FROM attendance_summaries");
        jdbc.update("UPDATE retention_state SET purged_before = NULL, last_run_at = NULL");
    }

    private RetentionService purgeOn(LocalDate today, int days) throws Exception {
        ApplicationProperties properties = new ApplicationProperties();
        properties.getRetention().setEventsDays(days);
        Constructor<RetentionService> constructor = RetentionService.class.getDeclaredConstructor(
                ApplicationProperties.class, CheckInOutRecordRepository.class, UserEventRecordRepository.class,
                NotificationRepository.class, StatusRequestRepository.class, AttendanceSummaryRepository.class,
                RetentionStateRepository.class, PlatformTransactionManager.class, Clock.class);
        constructor.setAccessible(true);
        RetentionService service = constructor.newInstance(properties, checkIns, events, notifications, requests,
                summaries, state, transactions, Clock.fixed(today.atTime(3, 0).atZone(ZONE).toInstant(), ZONE));
        service.setAuditLog(new AuditLog() {
            @Override public void record(AuditEntry entry) { audited.add(entry); }
            @Override public void recordQuietly(AuditEntry entry) { audited.add(entry); }
            @Override public <T> T inTransaction(Supplier<T> change) { return change.get(); }
            @Override public void requireAvailable() { }
        });
        return service;
    }

    private void checkIn(String dn, String action, LocalDate day, int hour, int minute) {
        jdbc.update("INSERT INTO check_in_out_records (dn, \"timestamp\", action, forced) VALUES (?, ?, ?, false)",
                dn, java.sql.Timestamp.from(day.atTime(hour, minute).atZone(ZONE).toInstant()), action);
    }

    private void status(String dn, LocalDate day, String status) {
        jdbc.update("INSERT INTO user_event_records (dn, date, status) VALUES (?, ?, ?)", dn, day, status);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private AttendanceSummary summary(String dn, LocalDate month) {
        return summaries.findByDn(dn).stream().filter(s -> s.getMonth().equals(month)).findFirst().orElseThrow();
    }

    @Test
    void oldRecordsAreSummarisedByMonthAndDeletedAndNewerOnesKept() throws Exception {
        LocalDate march2 = LocalDate.of(2026, 3, 2);
        // Bob: two working days in early March, 8 h 30 and 7 h 45; a day of leave; one day in February.
        checkIn(BOB, "CHECK_IN", march2, 8, 0);
        checkIn(BOB, "LOCK", march2, 12, 0);
        checkIn(BOB, "UNLOCK", march2, 12, 30);
        checkIn(BOB, "CHECK_OUT", march2, 16, 30);
        checkIn(BOB.toUpperCase(), "CHECK_IN", march2.plusDays(1), 9, 0);
        checkIn(BOB, "CHECK_OUT", march2.plusDays(1), 16, 45);
        status(BOB, march2.plusDays(2), "SCHEDULED_LEAVE");
        status(BOB, march2.plusDays(3), "STANDARD");
        checkIn(BOB, "CHECK_IN", LocalDate.of(2026, 2, 27), 8, 0);
        checkIn(BOB, "CHECK_OUT", LocalDate.of(2026, 2, 27), 9, 0);
        // Carol: telework on a day she also checked in.
        checkIn(CAROL, "CHECK_IN", march2, 10, 0);
        status(CAROL, march2, "WORK_FROM_HOME");
        // A record nobody owns, and records past the cutoff that stay.
        jdbc.update("INSERT INTO check_in_out_records (dn, \"timestamp\", action) VALUES (NULL, ?, 'CHECK_IN')",
                java.sql.Timestamp.from(march2.atTime(7, 0).atZone(ZONE).toInstant()));
        checkIn(BOB, "CHECK_IN", CUTOFF, 8, 0);
        status(BOB, CUTOFF, "TDY");
        // An alert and a request from before the cutoff, and a request that runs past it.
        jdbc.update("INSERT INTO notification_records (about_user_dn, for_user_dn, notification_date, type, ignore)"
                + " VALUES (?, ?, ?, 'ABSENT', false)", BOB, CAROL,
                java.sql.Timestamp.from(march2.atTime(10, 0).atZone(ZONE).toInstant()));
        String request = "INSERT INTO status_requests (requester_dn, status, from_date, to_date, state, created_at)"
                + " VALUES (?, 'SCHEDULED_LEAVE', ?, ?, 'APPROVED', now())";
        jdbc.update(request, BOB, march2.plusDays(2), march2.plusDays(2));
        jdbc.update(request, BOB, CUTOFF.minusDays(1), CUTOFF.plusDays(1));

        RetentionService.Result result = purgeOn(TODAY, 40).purge();

        assertEquals(CUTOFF, result.purgedBefore());
        assertEquals(9, result.checkIns(), "Bob's eight and Carol's one before the cutoff");
        assertEquals(1, result.unattributedCheckIns());
        assertEquals(3, result.statuses());
        assertEquals(1, result.notifications());
        assertEquals(1, result.requests(), "the request running past the cutoff stays");

        AttendanceSummary bobMarch = summary(BOB, LocalDate.of(2026, 3, 1));
        assertEquals(2, bobMarch.getDaysCheckedIn());
        assertEquals(8 * 60 + 30 + 7 * 60 + 45, bobMarch.getMinutesIn());
        assertEquals(Map.of(UserStatusEnum.SCHEDULED_LEAVE, 1), bobMarch.getStatusDays(), "normal is no status");
        AttendanceSummary bobFebruary = summary(BOB, LocalDate.of(2026, 2, 1));
        assertEquals(1, bobFebruary.getDaysCheckedIn());
        assertEquals(60, bobFebruary.getMinutesIn());
        AttendanceSummary carolMarch = summary(CAROL, LocalDate.of(2026, 3, 1));
        assertEquals(1, carolMarch.getDaysCheckedIn());
        assertEquals(0, carolMarch.getMinutesIn(), "no check-out, no time, as the reports count it");
        assertEquals(Map.of(UserStatusEnum.WORK_FROM_HOME, 1), carolMarch.getStatusDays());
        assertEquals(1, summaries.findByDn(BOB.toUpperCase()).stream()
                .filter(s -> s.getMonth().equals(LocalDate.of(2026, 3, 1))).count(), "one summary whatever the DN case");

        assertEquals(1, count("check_in_out_records"), "only the record on the cutoff day is left");
        assertEquals(1, count("user_event_records"));
        assertEquals(0, count("notification_records"));
        assertEquals(1, count("status_requests"));
        assertEquals(CUTOFF, state.findById(1).orElseThrow().getPurgedBefore());

        AuditEntry entry = audited.getLast();
        assertEquals("data.purged", entry.action());
        assertEquals(9L, entry.details().get("checkIns"));
        assertEquals(3L, entry.details().get("statuses"));
        assertEquals(40, entry.details().get("retentionDays"));
        assertEquals(CUTOFF.toString(), entry.details().get("purgedBefore"));
    }

    @Test
    void aLaterRunAddsToTheMonthWithoutCountingAnythingTwice() throws Exception {
        LocalDate first = CUTOFF.minusDays(1);           // 24 March: purged by the first run
        LocalDate second = CUTOFF;                       // 25 March: kept by the first run, purged by the next
        checkIn(BOB, "CHECK_IN", first, 8, 0);
        checkIn(BOB, "CHECK_OUT", first, 9, 0);
        checkIn(BOB, "CHECK_IN", second, 8, 0);
        checkIn(BOB, "CHECK_OUT", second, 10, 0);

        purgeOn(TODAY, 40).purge();
        assertEquals(1, summary(BOB, LocalDate.of(2026, 3, 1)).getDaysCheckedIn());
        RetentionService.Result again = purgeOn(TODAY, 40).purge();
        assertEquals(0, again.checkIns(), "nothing left to purge the same day");
        purgeOn(TODAY.plusDays(1), 40).purge();

        AttendanceSummary march = summary(BOB, LocalDate.of(2026, 3, 1));
        assertEquals(2, march.getDaysCheckedIn());
        assertEquals(180, march.getMinutesIn());
        assertEquals(0, count("check_in_out_records"));
        assertEquals(CUTOFF.plusDays(1), state.findById(1).orElseThrow().getPurgedBefore());
    }

    @Test
    void aBusyDayIsPurgedInBatches() throws Exception {
        LocalDate day = CUTOFF.minusDays(10);
        // 1,200 people on one day: more than two batches.
        jdbc.update("INSERT INTO check_in_out_records (dn, \"timestamp\", action)"
                + " SELECT 'cn=user' || g || ',ou=Users,dc=winllc,dc=com', ?, 'CHECK_IN' FROM generate_series(1, 1200) g",
                java.sql.Timestamp.from(day.atTime(9, 0).atZone(ZONE).toInstant()));

        RetentionService.Result result = purgeOn(TODAY, 40).purge();

        assertEquals(1200, result.checkIns());
        assertEquals(1200, result.summariesWritten());
        assertEquals(0, count("check_in_out_records"));
        assertEquals(1200, count("attendance_summaries"));
    }

    @Test
    void retentionOffPurgesNothing() throws Exception {
        checkIn(BOB, "CHECK_IN", LocalDate.of(2020, 1, 6), 8, 0);

        assertNull(purgeOn(TODAY, 0).purge());

        assertEquals(1, count("check_in_out_records"));
        assertTrue(audited.isEmpty());
    }

    @Test
    void aSummaryLeavesWithItsStatusesAndTheSchemaHoldsOnePerPersonAndMonth() {
        LocalDate march = LocalDate.of(2026, 3, 1);
        AttendanceSummary summary = new AttendanceSummary(BOB, march);
        summary.getStatusDays().put(UserStatusEnum.TDY, 2);
        summary.setUpdatedAt(ZonedDateTime.now());
        summaries.save(summary);

        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO attendance_summaries (dn, month, updated_at) VALUES (?, ?, now())", BOB.toUpperCase(), march));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO attendance_summaries (dn, month, updated_at) VALUES (?, ?, now())", CAROL, march.plusDays(3)));

        jdbc.update("DELETE FROM attendance_summaries");
        assertEquals(0, count("attendance_summary_statuses"));
    }
}
