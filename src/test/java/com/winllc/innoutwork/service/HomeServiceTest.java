package com.winllc.innoutwork.service;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.constant.NotificationTypeEnum;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.ExpectedLogin;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.UserStatus;
import com.winllc.innoutwork.data.home.AttendanceSummary;
import com.winllc.innoutwork.data.home.HomeDashboard;
import com.winllc.innoutwork.data.home.NotInYet;
import com.winllc.innoutwork.data.home.PersonalSummary;
import com.winllc.innoutwork.data.home.TeamSummary;
import com.winllc.innoutwork.data.home.UpcomingStatus;
import com.winllc.innoutwork.data.metrics.AccountabilityMetrics;
import com.winllc.innoutwork.data.metrics.AccountedFor;
import com.winllc.innoutwork.data.metrics.AgentCoverage;
import com.winllc.innoutwork.data.metrics.UserRef;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.GlobalCalendarRecord;
import com.winllc.innoutwork.model.NotificationRecord;
import com.winllc.innoutwork.model.UserEventRecord;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.GlobalCalendarRecordRepository;
import com.winllc.innoutwork.repository.NotificationRepository;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
import com.winllc.innoutwork.repository.UserRecordRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpSession;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rules behind the home page. The clock and JVM zone are pinned so "late", "today" and local-day
 * boundaries are deterministic.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HomeServiceTest {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    /** A Thursday. */
    private static final LocalDate DAY = LocalDate.of(2026, 9, 10);
    private static final ZonedDateTime NOW = DAY.atTime(10, 30).atZone(NEW_YORK);

    private static final String ME = "cn=Alice Adams,ou=Users,dc=winllc,dc=com";
    private static final String BOB = "cn=Bob Barker,ou=Users,dc=winllc,dc=com";
    private static final String CAROL = "cn=Carol Clark,ou=Users,dc=winllc,dc=com";
    private static final String DAVE = "cn=Dave Davis,ou=Users,dc=winllc,dc=com";

    @Mock private UserService userService;
    @Mock private UserRecordRepository userRecords;
    @Mock private UserEventRecordRepository events;
    @Mock private CheckInOutRecordRepository checkIns;
    @Mock private GlobalCalendarRecordRepository calendar;
    @Mock private NotificationRepository notifications;
    @Mock private AccountabilityMetricsService accountability;

    private HomeService service;
    private MockHttpSession session;
    private final List<CheckInOutRecord> history = new ArrayList<>();
    private TimeZone originalZone;

    @BeforeEach
    void setUp() {
        originalZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone(NEW_YORK));

        service = new HomeService(userService, userRecords, events, checkIns, calendar, notifications,
                accountability, new ApplicationProperties(), Clock.fixed(NOW.toInstant(), NEW_YORK));

        session = viewing(DAY);

        when(userService.getUserStatus(anyString(), any())).thenReturn(UserStatus.builder().dn(ME).status("NONE").build());
        when(userService.getDirectReports(any(), any())).thenReturn(List.of());
        when(userRecords.findByDnIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(userRecords.findAllByLowercaseDnIn(any())).thenReturn(List.of());
        when(events.findByDnIgnoreCaseAndDate(anyString(), any())).thenReturn(List.of());
        when(events.findByDnIgnoreCaseAndDateBetween(anyString(), any(), any())).thenReturn(List.of());
        when(events.findByLowercaseDnInAndDateBetween(any(), any(), any())).thenReturn(List.of());
        when(checkIns.findByDnIgnoreCaseAndTimestampIsBetweenOrderByTimestampDesc(anyString(), any(), any()))
                .thenReturn(history);
        when(calendar.findByDateBetween(any(), any())).thenReturn(List.of());
        when(notifications.findByForUserDnIgnoreCaseAndStatusResponseDateNullAndIgnore(anyString(), eq(false)))
                .thenReturn(List.of());
    }

    @AfterEach
    void restoreZone() {
        TimeZone.setDefault(originalZone);
    }

    private static MockHttpSession viewing(LocalDate day) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("systemTime", day.atTime(12, 0).atZone(NEW_YORK));
        return session;
    }

    private HomeDashboard dashboard() {
        return service.forUser(ME, session);
    }

    private PersonalSummary me() {
        return dashboard().me();
    }

    /** Stored rows come back from the database in UTC. */
    private void event(CheckInOutEnum action, LocalDate date, int hour, int minute) {
        CheckInOutRecord record = new CheckInOutRecord();
        record.setDn(ME);
        record.setAction(action);
        record.setTimestamp(date.atTime(hour, minute).atZone(NEW_YORK).withZoneSameInstant(ZoneOffset.UTC));
        history.add(record);
    }

    private static UserEventRecord status(String dn, LocalDate date, UserStatusEnum status) {
        UserEventRecord record = new UserEventRecord();
        record.setDn(dn);
        record.setDate(date);
        record.setStatus(status);
        return record;
    }

    private void iHaveARecord(LocalTime preferred, LocalTime average) {
        UserRecord record = new UserRecord();
        record.setDn(ME);
        record.setChosenLoginTime(preferred);
        record.setAverageLoginTime(average);
        when(userRecords.findByDnIgnoreCase(ME)).thenReturn(Optional.of(record));
    }

    // --- my status ---------------------------------------------------------------------------------

    @Test
    void statusesAreShownInWordsWithTheTableColours() {
        assertEquals("Checked in", HomeService.statusLabel("IN"));
        assertEquals("Checked out", HomeService.statusLabel("OUT"));
        assertEquals("Away", HomeService.statusLabel("AWAY"));
        assertEquals("No activity yet", HomeService.statusLabel("NONE"));
        assertEquals("No activity yet", HomeService.statusLabel(null));
        assertEquals("Work From Home", HomeService.statusLabel("WORK_FROM_HOME"));
        assertEquals("SOMETHING_NEW", HomeService.statusLabel("SOMETHING_NEW"));

        assertEquals("bg-green-lt", HomeService.statusBadge("IN"));
        assertEquals("bg-blue-lt", HomeService.statusBadge("OUT"));
        assertEquals("bg-purple-lt", HomeService.statusBadge("AWAY"));
        assertEquals("bg-secondary-lt", HomeService.statusBadge("NONE"));
        assertEquals("bg-yellow-lt", HomeService.statusBadge("TDY"));
    }

    @Test
    void myStatusAndTimesComeFromTheSameLookupAsTheUserTables() {
        ZonedDateTime in = DAY.atTime(8, 5).atZone(NEW_YORK);
        when(userService.getUserStatus(eq(ME), any())).thenReturn(UserStatus.builder()
                .dn(ME).status("IN").checkedInAt(in).lastStatusChangeAt(in).build());

        PersonalSummary me = me();

        assertEquals(DAY, me.day());
        assertEquals("Checked in", me.statusLabel());
        assertEquals(in, me.checkedInAt());
        assertEquals(in, me.statusSince());
        assertNull(me.checkedOutAt());
    }

    @Test
    void myExpectedTimeSaysWhereItCameFrom() {
        iHaveARecord(LocalTime.of(9, 0), LocalTime.of(8, 40));

        PersonalSummary me = me();

        assertEquals(new ExpectedLogin(LocalTime.of(9, 0), ExpectedLogin.Source.PREFERRED), me.expected());
        assertEquals(LocalTime.of(8, 40), me.averageLoginTime());
    }

    @Test
    void aLateArrivalTodayIsMyExpectedTime() {
        iHaveARecord(LocalTime.of(9, 0), LocalTime.of(8, 40));
        UserEventRecord late = status(ME, DAY, UserStatusEnum.LATE_ARRIVAL);
        late.setLoginByTime(LocalTime.of(11, 0));
        when(events.findByDnIgnoreCaseAndDate(ME, DAY)).thenReturn(List.of(late));

        assertEquals(ExpectedLogin.Source.LATE_ARRIVAL, me().expected().source());
    }

    @Test
    void withNoRecordNothingIsExpected() {
        PersonalSummary me = me();

        assertNull(me.expected());
        assertNull(me.averageLoginTime());
    }

    // --- last 30 days ----------------------------------------------------------------------------------

    @Test
    void workingDaysAreCountedOnceEachSkippingWeekendsAndHolidays() {
        LocalDate labourDay = LocalDate.of(2026, 9, 7);
        GlobalCalendarRecord holiday = new GlobalCalendarRecord();
        holiday.setDate(labourDay);
        holiday.setHoliday(true);
        GlobalCalendarRecord notAHoliday = new GlobalCalendarRecord();
        notAHoliday.setDate(LocalDate.of(2026, 9, 1));
        notAHoliday.setHoliday(false);
        when(calendar.findByDateBetween(DAY.minusDays(29), DAY)).thenReturn(List.of(holiday, notAHoliday));

        event(CheckInOutEnum.CHECK_IN, DAY, 8, 0);
        event(CheckInOutEnum.CHECK_IN, DAY.minusDays(1), 8, 0);
        event(CheckInOutEnum.CHECK_IN, DAY.minusDays(2), 8, 0);
        event(CheckInOutEnum.CHECK_IN, DAY.minusDays(2), 13, 0);            // second the same day
        event(CheckInOutEnum.CHECK_IN, LocalDate.of(2026, 9, 2), 23, 30);   // already Sep 3 in UTC
        event(CheckInOutEnum.CHECK_IN, LocalDate.of(2026, 9, 5), 10, 0);    // Saturday
        event(CheckInOutEnum.CHECK_IN, labourDay, 10, 0);                   // holiday
        event(CheckInOutEnum.LOCK, LocalDate.of(2026, 8, 31), 12, 0);       // not a check-in
        when(events.findByDnIgnoreCaseAndDateBetween(ME, DAY.minusDays(29), DAY)).thenReturn(List.of(
                status(ME, LocalDate.of(2026, 9, 4), UserStatusEnum.WORK_FROM_HOME),
                status(ME, LocalDate.of(2026, 9, 3), UserStatusEnum.STANDARD),
                status(ME, DAY.minusDays(1), UserStatusEnum.TDY)));         // checked in too

        AttendanceSummary att = me().last30Days();

        // Aug 12 to Sep 10 holds 22 weekdays; Labour Day leaves 21.
        assertEquals(21, att.workingDays());
        assertEquals(4, att.checkedIn());
        assertEquals(1, att.statusOnly());
        assertEquals(16, att.noRecord());
    }

    @Test
    void theHistoryWindowIsThe30DaysEndingOnTheDayShown() {
        me();

        verify(checkIns).findByDnIgnoreCaseAndTimestampIsBetweenOrderByTimestampDesc(ME,
                DAY.minusDays(29).atStartOfDay(NEW_YORK), DAY.plusDays(1).atStartOfDay(NEW_YORK).minusNanos(1));
        verify(events).findByDnIgnoreCaseAndDateBetween(ME, DAY.minusDays(29), DAY);
    }

    @Test
    void attendanceShares() {
        AttendanceSummary att = new AttendanceSummary(20, 15, 3, 2);

        assertEquals(75.0, att.percentOfWorkingDays(att.checkedIn()));
        assertEquals(0.0, new AttendanceSummary(0, 0, 0, 0).percentOfWorkingDays(0));
    }

    // --- my agent ----------------------------------------------------------------------------------------

    @Test
    void anAgentThatReportedThisWeekIsReporting() {
        event(CheckInOutEnum.LOCK, DAY.minusDays(6), 17, 0);
        event(CheckInOutEnum.CHECK_IN, DAY.minusDays(20), 8, 0);

        PersonalSummary me = me();

        assertFalse(me.agentQuiet());
        assertEquals(DAY.minusDays(6).atTime(17, 0).atZone(NEW_YORK), me.lastReported());
    }

    @Test
    void anAgentSilentForAWeekIsQuiet() {
        event(CheckInOutEnum.CHECK_IN, DAY.minusDays(7), 8, 0);

        assertTrue(me().agentQuiet());
    }

    @Test
    void anAgentWithNothingIn30DaysIsQuiet() {
        PersonalSummary me = me();

        assertTrue(me.agentQuiet());
        assertNull(me.lastReported());
    }

    // --- coming up --------------------------------------------------------------------------------------

    @Test
    void myUpcomingStatusesAreTheNextTwoWeeksInDateOrder() {
        when(events.findByDnIgnoreCaseAndDateBetween(ME, DAY.plusDays(1), DAY.plusDays(14))).thenReturn(List.of(
                status(ME, DAY.plusDays(9), UserStatusEnum.TDY),
                status(ME, DAY.plusDays(2), UserStatusEnum.SCHEDULED_LEAVE),
                status(ME, DAY.plusDays(3), UserStatusEnum.STANDARD)));

        List<UpcomingStatus> upcoming = me().upcoming();

        assertEquals(List.of(DAY.plusDays(2), DAY.plusDays(9)), upcoming.stream().map(UpcomingStatus::date).toList());
        assertEquals(List.of("Scheduled Leave", "TDY"), upcoming.stream().map(UpcomingStatus::label).toList());
    }

    // --- the team ---------------------------------------------------------------------------------------

    @Test
    void someoneWithNoReportsHasNoTeamSection() {
        HomeDashboard home = dashboard();

        assertFalse(home.manager());
        assertNull(home.team());
        verify(accountability, never()).forTeam(any(), any());
    }

    private void myReportsAre(String... dns) {
        List<UserStatus> reports = new ArrayList<>();
        for (String dn : dns) {
            reports.add(UserStatus.builder().dn(dn).build());
        }
        when(userService.getDirectReports(eq(new LdapDn(ME)), any())).thenReturn(reports);
    }

    private void unaccounted(String... dns) {
        List<UserRef> refs = new ArrayList<>();
        for (String dn : dns) {
            refs.add(new UserRef(dn, LdapDn.cnOf(dn)));
        }
        when(accountability.forTeam(any(), any())).thenReturn(new AccountabilityMetrics(
                new AccountedFor(DAY, null, dns.length, 0, 0, 0, refs, refs.size()),
                List.of(), new AgentCoverage(dns.length, 0, 0, dns.length, 7, List.of())));
    }

    private static UserRecord record(String dn, LocalTime preferred, LocalTime average) {
        UserRecord record = new UserRecord();
        record.setDn(dn);
        record.setChosenLoginTime(preferred);
        record.setAverageLoginTime(average);
        return record;
    }

    @Test
    void aManagersTeamIsMeasuredOverTheirReportsOnly() {
        myReportsAre(BOB, CAROL);
        unaccounted();

        TeamSummary team = dashboard().team();

        assertEquals(2, team.reportCount());
        verify(accountability).forTeam(DAY, List.of(BOB, CAROL));
    }

    /** Now is 10:30 and the grace period 60 minutes. */
    @Test
    void notInYetShowsEachExpectedTimeAndFlagsThosePastItAndTheGracePeriod() {
        myReportsAre(BOB, CAROL, DAVE);
        unaccounted(BOB, CAROL, DAVE);
        when(userRecords.findAllByLowercaseDnIn(any())).thenReturn(List.of(
                record(BOB.toUpperCase(), LocalTime.of(9, 0), null),   // 9:00 + 60 < 10:30
                record(CAROL, null, LocalTime.of(10, 0))));            // 10:00 + 60 > 10:30

        List<NotInYet> notIn = dashboard().team().notInYet();

        assertEquals(List.of(
                new NotInYet(BOB, "Bob Barker", LocalTime.of(9, 0), true),
                new NotInYet(CAROL, "Carol Clark", LocalTime.of(10, 0), false),
                new NotInYet(DAVE, "Dave Davis", null, false)), notIn);
    }

    @Test
    void exactlyAtTheEndOfTheGracePeriodIsNotYetLate() {
        myReportsAre(BOB);
        unaccounted(BOB);
        when(userRecords.findAllByLowercaseDnIn(any())).thenReturn(List.of(record(BOB, LocalTime.of(9, 30), null)));

        assertFalse(dashboard().team().notInYet().getFirst().late());
    }

    @Test
    void onAPastDayEveryoneNotInIsFlagged() {
        session = viewing(DAY.minusDays(1));
        myReportsAre(BOB, CAROL);
        unaccounted(BOB, CAROL);
        when(userRecords.findAllByLowercaseDnIn(any())).thenReturn(List.of(record(BOB, LocalTime.of(23, 0), null)));

        assertTrue(dashboard().team().notInYet().stream().allMatch(NotInYet::late));
    }

    @Test
    void notificationsAwaitingMyResponseAreNewestFirst() {
        myReportsAre(BOB);
        unaccounted();
        NotificationRecord older = NotificationRecord.builder().id(1L).aboutUserDn(BOB)
                .type(NotificationTypeEnum.ABSENT).notificationDate(NOW.minusDays(2)).build();
        NotificationRecord newer = NotificationRecord.builder().id(2L).aboutUserDn(BOB)
                .type(NotificationTypeEnum.ABSENT).notificationDate(NOW.minusHours(1)).build();
        when(notifications.findByForUserDnIgnoreCaseAndStatusResponseDateNullAndIgnore(ME, false))
                .thenReturn(List.of(older, newer));

        assertEquals(List.of(2L, 1L), dashboard().team().awaitingResponse().stream().map(NotificationRecord::getId).toList());
    }

    @Test
    void theTeamsUpcomingStatusesAreByDateThenName() {
        myReportsAre(BOB, CAROL);
        unaccounted();
        when(events.findByLowercaseDnInAndDateBetween(List.of(BOB.toLowerCase(), CAROL.toLowerCase()),
                DAY.plusDays(1), DAY.plusDays(14))).thenReturn(List.of(
                status(CAROL, DAY.plusDays(3), UserStatusEnum.TDY),
                status(BOB, DAY.plusDays(5), UserStatusEnum.SCHEDULED_LEAVE),
                status(BOB, DAY.plusDays(3), UserStatusEnum.WORK_FROM_HOME),
                status(CAROL, DAY.plusDays(4), UserStatusEnum.STANDARD)));

        List<UpcomingStatus> upcoming = dashboard().team().upcoming();

        assertEquals(List.of("Bob Barker", "Carol Clark", "Bob Barker"), upcoming.stream().map(UpcomingStatus::name).toList());
        assertEquals(List.of(DAY.plusDays(3), DAY.plusDays(3), DAY.plusDays(5)),
                upcoming.stream().map(UpcomingStatus::date).toList());
    }
}
