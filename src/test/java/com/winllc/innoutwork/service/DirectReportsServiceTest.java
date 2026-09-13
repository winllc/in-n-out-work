package com.winllc.innoutwork.service;

import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.data.home.AttendanceSummary;
import com.winllc.innoutwork.data.metrics.AccountabilityMetrics;
import com.winllc.innoutwork.data.metrics.AccountedFor;
import com.winllc.innoutwork.data.metrics.AgentCoverage;
import com.winllc.innoutwork.data.team.AbsenceAlerts;
import com.winllc.innoutwork.data.team.DailyAttendance;
import com.winllc.innoutwork.data.team.DirectReportsSummary;
import com.winllc.innoutwork.data.team.ReportAttendance;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.GlobalCalendarRecord;
import com.winllc.innoutwork.model.NotificationRecord;
import com.winllc.innoutwork.model.UserEventRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.GlobalCalendarRecordRepository;
import com.winllc.innoutwork.repository.NotificationRepository;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpSession;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The direct reports page's metrics. The JVM zone is pinned so local days are deterministic; the
 * window is the 30 days ending Thursday 10 September 2026, which holds 22 weekdays.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DirectReportsServiceTest {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 10);
    private static final LocalDate FROM = DAY.minusDays(29);

    private static final String MANAGER = "cn=Alice Adams,ou=Users,dc=winllc,dc=com";
    private static final String BOB = "cn=Bob Barker,ou=Users,dc=winllc,dc=com";
    private static final String CAROL = "cn=Carol Clark,ou=Users,dc=winllc,dc=com";

    @Mock private UserService userService;
    @Mock private AccountabilityMetricsService accountability;
    @Mock private CheckInOutRecordRepository checkIns;
    @Mock private UserEventRecordRepository events;
    @Mock private GlobalCalendarRecordRepository calendar;
    @Mock private NotificationRepository notifications;

    private DirectReportsService service;
    private MockHttpSession session;
    private final List<CheckInOutRecord> records = new ArrayList<>();
    private final List<UserEventRecord> statuses = new ArrayList<>();
    private final List<NotificationRecord> alerts = new ArrayList<>();
    private TimeZone originalZone;

    @BeforeEach
    void setUp() {
        originalZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone(NEW_YORK));

        service = new DirectReportsService(userService, accountability, checkIns, events, calendar, notifications);
        session = new MockHttpSession();
        session.setAttribute("systemTime", DAY.atTime(12, 0).atZone(NEW_YORK));

        when(checkIns.findByLowercaseDnInAndTimestampBetween(any(), any(), any())).thenReturn(records);
        when(events.findByLowercaseDnInAndDateBetween(any(), any(), any())).thenReturn(statuses);
        when(notifications.findAboutLowercaseDnInBetween(any(), any(), any())).thenReturn(alerts);
        when(calendar.findByDateBetween(any(), any())).thenReturn(List.of());
        when(accountability.forTeam(any(), any())).thenReturn(new AccountabilityMetrics(
                new AccountedFor(DAY, null, 0, 0, 0, 0, List.of(), 0), List.of(),
                new AgentCoverage(0, 0, 0, 0, 7, List.of())));
    }

    @AfterEach
    void restoreZone() {
        TimeZone.setDefault(originalZone);
    }

    private void reportsAre(String... dns) {
        List<LdapUser> reports = new ArrayList<>();
        for (String dn : dns) {
            reports.add(LdapUser.builder().dn(dn).build());
        }
        when(userService.findDirectReports(new LdapDn(MANAGER))).thenReturn(reports);
    }

    /** Stored rows come back from the database in UTC. */
    private void record(String dn, CheckInOutEnum action, LocalDate date, int hour, int minute) {
        CheckInOutRecord record = new CheckInOutRecord();
        record.setDn(dn);
        record.setAction(action);
        record.setTimestamp(date.atTime(hour, minute).atZone(NEW_YORK).withZoneSameInstant(ZoneOffset.UTC));
        records.add(record);
    }

    private void status(String dn, LocalDate date, UserStatusEnum status) {
        UserEventRecord record = new UserEventRecord();
        record.setDn(dn);
        record.setDate(date);
        record.setStatus(status);
        statuses.add(record);
    }

    private static NotificationRecord alert(long id, String uuid, UserStatusEnum response, boolean responseDate) {
        return NotificationRecord.builder().id(id).notificationUuid(uuid).aboutUserDn(BOB)
                .statusResponse(response).statusResponseDate(responseDate ? ZonedDateTime.now() : null).build();
    }

    private DirectReportsSummary summary() {
        return service.forManager(MANAGER, session);
    }

    private ReportAttendance row(DirectReportsSummary summary, String name) {
        return summary.reports().stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
    }

    // --- no reports ----------------------------------------------------------------------------------

    @Test
    void someoneWithNoReportsGetsAnEmptySummaryWithoutQueryingAnything() {
        when(userService.findDirectReports(any())).thenReturn(List.of());

        DirectReportsSummary summary = summary();

        assertEquals(0, summary.reportCount());
        assertEquals(DAY, summary.day());
        assertTrue(summary.reports().isEmpty());
        assertTrue(summary.daily().isEmpty());
        verifyNoInteractions(checkIns, events, notifications, accountability);
    }

    // --- queries ---------------------------------------------------------------------------------------

    @Test
    void theWholeTeamIsReadInOneQueryEachOverThe30DayWindow() {
        reportsAre(BOB, CAROL.toUpperCase());

        summary();

        Set<String> lower = Set.of(BOB.toLowerCase(), CAROL.toLowerCase());
        ZonedDateTime start = FROM.atStartOfDay(NEW_YORK);
        ZonedDateTime end = DAY.plusDays(1).atStartOfDay(NEW_YORK).minusNanos(1);
        verify(checkIns).findByLowercaseDnInAndTimestampBetween(eq(lower), eq(start), eq(end));
        verify(events).findByLowercaseDnInAndDateBetween(eq(lower), eq(FROM), eq(DAY));
        verify(notifications).findAboutLowercaseDnInBetween(eq(lower), eq(start), eq(end));
        verify(calendar).findByDateBetween(FROM, DAY);
        verify(accountability).forTeam(eq(DAY), argThat((Collection<String> dns) ->
                new ArrayList<>(dns).equals(List.of(BOB, CAROL.toUpperCase()))));
    }

    @Test
    void theSameReportListedTwiceIsCountedOnce() {
        reportsAre(BOB, BOB.toUpperCase());

        assertEquals(1, summary().reportCount());
    }

    // --- attendance --------------------------------------------------------------------------------------

    @Test
    void eachReportsWorkingDaysAreCountedOnceAndAddUpForTheTeam() {
        reportsAre(BOB, CAROL);
        record(BOB, CheckInOutEnum.CHECK_IN, DAY, 8, 0);
        record(BOB, CheckInOutEnum.CHECK_IN, DAY, 13, 0);              // second the same day
        record(BOB, CheckInOutEnum.CHECK_IN, DAY.minusDays(1), 9, 0);
        record(BOB, CheckInOutEnum.CHECK_IN, LocalDate.of(2026, 9, 5), 10, 0); // Saturday
        record(CAROL.toUpperCase(), CheckInOutEnum.CHECK_IN, DAY, 7, 30);
        record(CAROL, CheckInOutEnum.LOCK, DAY.minusDays(2), 12, 0);   // not a check-in
        status(CAROL, DAY.minusDays(1), UserStatusEnum.WORK_FROM_HOME);
        status(BOB, DAY.minusDays(1), UserStatusEnum.TDY);            // checked in too

        DirectReportsSummary summary = summary();

        assertEquals(new AttendanceSummary(22, 2, 0, 20), row(summary, "Bob Barker").attendance());
        assertEquals(new AttendanceSummary(22, 1, 1, 20), row(summary, "Carol Clark").attendance());
        assertEquals(new AttendanceSummary(44, 3, 1, 40), summary.attendance());
        assertEquals(7, summary.attendance().checkedInPercent());
    }

    @Test
    void holidaysAreNotWorkingDays() {
        reportsAre(BOB);
        GlobalCalendarRecord labourDay = new GlobalCalendarRecord();
        labourDay.setDate(LocalDate.of(2026, 9, 7));
        labourDay.setHoliday(true);
        when(calendar.findByDateBetween(FROM, DAY)).thenReturn(List.of(labourDay));

        DirectReportsSummary summary = summary();

        assertEquals(21, summary.attendance().workingDays());
        assertFalse(summary.daily().stream().anyMatch(d -> d.date().equals(labourDay.getDate())));
    }

    /** Every report appears in exactly one slot each working day, so a column always totals the team. */
    @Test
    void eachWorkingDayPartitionsTheTeam() {
        reportsAre(BOB, CAROL);
        record(BOB, CheckInOutEnum.CHECK_IN, DAY, 8, 0);
        status(CAROL, DAY, UserStatusEnum.TDY);
        status(BOB, DAY.minusDays(1), UserStatusEnum.SCHEDULED_LEAVE);

        List<DailyAttendance> daily = summary().daily();

        assertEquals(22, daily.size());
        assertEquals(FROM, daily.getFirst().date(), "the window opens on a Wednesday");
        assertEquals(new DailyAttendance(DAY, 1, 1, 0), daily.getLast());
        assertEquals(new DailyAttendance(DAY.minusDays(1), 0, 1, 1), daily.get(daily.size() - 2));
        assertTrue(daily.stream().allMatch(d -> d.checkedIn() + d.statusOnly() + d.noRecord() == 2));
    }

    @Test
    void theChartSeriesMirrorTheDailyEntries() {
        reportsAre(BOB);
        record(BOB, CheckInOutEnum.CHECK_IN, DAY, 8, 0);

        DirectReportsSummary summary = summary();

        assertEquals(22, summary.dailyDates().size());
        assertEquals("2026-09-10", summary.dailyDates().getLast());
        assertEquals(1, summary.dailyCheckedIn().getLast());
        assertEquals(0, summary.dailyStatusOnly().getLast());
        assertEquals(0, summary.dailyNoRecord().getLast());
        assertEquals(1, summary.dailyNoRecord().getFirst());
    }

    // --- arrival -----------------------------------------------------------------------------------------

    @Test
    void averageArrivalUsesEachWorkingDaysFirstCheckInOnly() {
        reportsAre(BOB, CAROL);
        record(BOB, CheckInOutEnum.CHECK_IN, DAY, 8, 0);
        record(BOB, CheckInOutEnum.CHECK_IN, DAY, 14, 0);              // later sign-in, ignored
        record(BOB, CheckInOutEnum.CHECK_IN, DAY.minusDays(1), 9, 0);
        record(BOB, CheckInOutEnum.CHECK_IN, LocalDate.of(2026, 9, 6), 5, 0); // Sunday, ignored
        record(CAROL, CheckInOutEnum.CHECK_IN, DAY, 10, 0);

        DirectReportsSummary summary = summary();

        assertEquals(LocalTime.of(8, 30), row(summary, "Bob Barker").averageArrival());
        assertEquals(LocalTime.of(10, 0), row(summary, "Carol Clark").averageArrival());
        assertEquals(LocalTime.of(9, 0), summary.averageArrival());
    }

    @Test
    void withNoCheckInsThereIsNoAverageArrival() {
        reportsAre(BOB);

        DirectReportsSummary summary = summary();

        assertNull(summary.averageArrival());
        assertNull(summary.reports().getFirst().averageArrival());
    }

    // --- agents --------------------------------------------------------------------------------------------

    @Test
    void eachReportsAgentLastReportedAndWhetherItIsQuiet() {
        reportsAre(BOB, CAROL, "cn=Dave Davis,ou=Users,dc=winllc,dc=com");
        record(BOB, CheckInOutEnum.LOCK, DAY.minusDays(6), 17, 0);
        record(CAROL, CheckInOutEnum.CHECK_IN, DAY.minusDays(7), 8, 0);

        DirectReportsSummary summary = summary();

        ReportAttendance bob = row(summary, "Bob Barker");
        assertEquals(DAY.minusDays(6).atTime(17, 0).atZone(NEW_YORK), bob.lastReported());
        assertFalse(bob.agentQuiet());
        assertTrue(row(summary, "Carol Clark").agentQuiet());
        ReportAttendance dave = row(summary, "Dave Davis");
        assertNull(dave.lastReported());
        assertTrue(dave.agentQuiet());
    }

    @Test
    void reportsAreListedByName() {
        reportsAre(CAROL, "cn=aaron Able,ou=Users,dc=winllc,dc=com", BOB);

        assertEquals(List.of("aaron Able", "Bob Barker", "Carol Clark"),
                summary().reports().stream().map(ReportAttendance::name).toList());
    }

    // --- absence alerts ------------------------------------------------------------------------------------

    @Test
    void alertsAreCountedOncePerAbsenceAcrossTheManagersNotified() {
        reportsAre(BOB);
        alerts.add(alert(1, "u1", null, false));                      // manager 1, unanswered
        alerts.add(alert(2, "u1", null, false));                      // manager 2, same absence
        alerts.add(alert(3, "u2", UserStatusEnum.ABSENT_EXCUSED, true));
        alerts.add(alert(4, "u2", UserStatusEnum.ABSENT_EXCUSED, true));
        alerts.add(alert(5, "u3", null, true));                       // marked read only
        alerts.add(alert(6, null, UserStatusEnum.LATE_ARRIVAL, true)); // no uuid
        alerts.add(alert(7, "u4", UserStatusEnum.ABSENT_EXCUSED, true));

        AbsenceAlerts result = summary().alerts();

        assertEquals(5, result.raised());
        assertEquals(3, result.answered());
        assertEquals(2, result.unanswered());
        assertEquals(List.of(new AbsenceAlerts.AlertOutcome("Absent Excused", 2),
                new AbsenceAlerts.AlertOutcome("Late Arrival", 1)), result.outcomes());
    }

    @Test
    void anAlertIsAnsweredWhenAnyManagerResponds() {
        AbsenceAlerts result = DirectReportsService.alerts(List.of(
                alert(1, "u1", null, false), alert(2, "u1", UserStatusEnum.TDY, true)));

        assertEquals(1, result.raised());
        assertEquals(1, result.answered());
    }

    @Test
    void noAlertsIsZero() {
        AbsenceAlerts result = DirectReportsService.alerts(List.of());

        assertEquals(0, result.raised());
        assertEquals(0, result.unanswered());
        assertTrue(result.outcomes().isEmpty());
    }

    // --- attendance summary arithmetic -------------------------------------------------------------------

    @Test
    void attendanceSummariesAddAndRound() {
        AttendanceSummary total = new AttendanceSummary(20, 15, 3, 2).plus(new AttendanceSummary(20, 10, 5, 5));

        assertEquals(new AttendanceSummary(40, 25, 8, 7), total);
        assertEquals(63, total.checkedInPercent());
        assertNull(new AttendanceSummary(0, 0, 0, 0).checkedInPercent());
    }

    @Test
    void theEmptySummaryHasTheWindowButNoData() {
        DirectReportsSummary none = DirectReportsSummary.none(DAY);

        assertEquals(FROM, none.from());
        assertEquals(0, none.alerts().raised());
        assertTrue(none.dailyDates().isEmpty());
    }
}
