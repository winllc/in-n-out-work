package com.winllc.innoutwork.service;

import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.metrics.AccountabilityMetrics;
import com.winllc.innoutwork.data.metrics.AccountedFor;
import com.winllc.innoutwork.data.metrics.AgentCoverage;
import com.winllc.innoutwork.data.metrics.LastSeen;
import com.winllc.innoutwork.data.metrics.StatusMixEntry;
import com.winllc.innoutwork.data.metrics.StoppedAgent;
import com.winllc.innoutwork.data.metrics.UserRef;
import com.winllc.innoutwork.model.GlobalCalendarRecord;
import com.winllc.innoutwork.model.UserEventRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.GlobalCalendarRecordRepository;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
import com.winllc.innoutwork.repository.UserRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rules behind the accountability metrics: who is expected, who counts as accounted for, how the
 * status mix partitions them, and when an agent counts as having stopped.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountabilityMetricsServiceTest {

    /** A Thursday. */
    private static final LocalDate DAY = LocalDate.of(2026, 9, 10);
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private static final String ALICE = "cn=Alice Adams,ou=Users,dc=winllc,dc=com";
    private static final String BOB = "cn=Bob Barker,ou=Users,dc=winllc,dc=com";
    private static final String CAROL = "cn=Carol Clark,ou=Users,dc=winllc,dc=com";
    private static final String DAVE = "cn=Dave Davis,ou=Users,dc=winllc,dc=com";
    private static final String ERIN = "cn=Erin Evans,ou=Users,dc=winllc,dc=com";

    @Mock
    private CheckInOutRecordRepository checkIns;
    @Mock
    private UserEventRecordRepository events;
    @Mock
    private UserRecordRepository users;
    @Mock
    private GlobalCalendarRecordRepository calendar;

    private AccountabilityMetricsService service;

    @BeforeEach
    void setUp() {
        service = new AccountabilityMetricsService(checkIns, events, users, calendar);
        when(checkIns.findDistinctDnsWithActionBetween(any(), any(), any())).thenReturn(List.of());
        when(checkIns.findDistinctDnsBetween(any(), any())).thenReturn(List.of());
        when(checkIns.findLastSeenBetween(any(), any())).thenReturn(List.of());
        when(checkIns.findLastSeenByLowercaseDnInBetween(any(), any(), any())).thenReturn(List.of());
        when(events.findByDate(any())).thenReturn(List.of());
        when(users.findAllDns()).thenReturn(List.of());
        when(calendar.findByDate(any())).thenReturn(List.of());
    }

    private void active(String... dns) {
        when(checkIns.findDistinctDnsBetween(any(), any())).thenReturn(List.of(dns));
    }

    private void checkedIn(String... dns) {
        when(checkIns.findDistinctDnsWithActionBetween(eq(CheckInOutEnum.CHECK_IN), any(), any()))
                .thenReturn(List.of(dns));
    }

    private void statuses(UserEventRecord... records) {
        when(events.findByDate(DAY)).thenReturn(List.of(records));
    }

    private static UserEventRecord status(String dn, UserStatusEnum status) {
        UserEventRecord record = new UserEventRecord();
        record.setDn(dn);
        record.setDate(DAY);
        record.setStatus(status);
        return record;
    }

    private AccountabilityMetrics metrics() {
        return service.forDay(DAY);
    }

    private static List<String> names(List<UserRef> users) {
        return users.stream().map(UserRef::name).toList();
    }

    // --- accounted for -----------------------------------------------------------------------------

    @Test
    void checkedInAndStatusedUsersAreAccountedForAndTheRestAreCounted() {
        active(ALICE, BOB, CAROL, DAVE);
        checkedIn(ALICE);
        statuses(status(BOB, UserStatusEnum.WORK_FROM_HOME));

        AccountedFor af = metrics().accountedFor();

        assertTrue(af.workingDay());
        assertEquals(4, af.expected());
        assertEquals(2, af.accounted());
        assertEquals(1, af.checkedIn());
        assertEquals(1, af.withStatus());
        assertEquals(50, af.ratePercent());
        assertEquals(2, af.unaccountedTotal());
    }

    /** The metrics page is aggregate only: organisation-wide figures carry counts, never names. */
    @Test
    void organisationWideFiguresNameNobody() {
        active(ALICE, BOB, CAROL);
        when(users.findAllDns()).thenReturn(List.of(ALICE, BOB, CAROL));
        lastSeen(List.of(seen(ALICE, DAY), seen(BOB, DAY.minusDays(10))));

        AccountabilityMetrics metrics = metrics();

        assertEquals(3, metrics.accountedFor().unaccountedTotal());
        assertTrue(metrics.accountedFor().unaccounted().isEmpty());
        assertEquals(1, metrics.agentCoverage().stopped());
        assertTrue(metrics.agentCoverage().stoppedUsers().isEmpty());
    }

    @Test
    void teamFiguresListTheUnaccountedByName() {
        active(ALICE, BOB, CAROL, DAVE);
        checkedIn(ALICE);
        statuses(status(BOB, UserStatusEnum.WORK_FROM_HOME));

        AccountedFor af = service.forTeam(DAY, List.of(DAVE, ALICE, BOB, CAROL)).accountedFor();

        assertEquals(List.of("Carol Clark", "Dave Davis"), names(af.unaccounted()));
        assertEquals(CAROL, af.unaccounted().getFirst().dn());
    }

    /** Someone on a long leave has no recent activity, but their status makes them expected and accounted for. */
    @Test
    void aStatusedUserWithNoRecentActivityIsStillExpected() {
        active(ALICE);
        checkedIn(ALICE);
        statuses(status(ERIN, UserStatusEnum.SCHEDULED_LEAVE));

        AccountedFor af = metrics().accountedFor();

        assertEquals(2, af.expected());
        assertEquals(100, af.ratePercent());
    }

    /** A work-from-home user who also connects is counted once. */
    @Test
    void aUserWhoCheckedInAndHasAStatusCountsOnce() {
        active(ALICE);
        checkedIn(ALICE);
        statuses(status(ALICE, UserStatusEnum.WORK_FROM_HOME));

        AccountedFor af = metrics().accountedFor();

        assertEquals(1, af.expected());
        assertEquals(1, af.accounted());
        assertEquals(1, af.checkedIn());
        assertEquals(1, af.withStatus());
    }

    /** STANDARD means no special status; it does not account for anyone. */
    @Test
    void aStandardStatusDoesNotCount() {
        active(ALICE);
        statuses(status(ALICE, UserStatusEnum.STANDARD));

        AccountedFor af = metrics().accountedFor();

        assertEquals(0, af.accounted());
        assertEquals(0, af.withStatus());
        assertEquals(1, af.unaccountedTotal());
    }

    /** Even an unexcused absence is accounted for: the whereabouts are known, and the mix shows which it is. */
    @Test
    void anAbsenceStatusIsAccountedFor() {
        active(ALICE);
        statuses(status(ALICE, UserStatusEnum.ABSENT_UNEXCUSED));

        assertEquals(100, metrics().accountedFor().ratePercent());
    }

    @Test
    void dnCaseDifferencesAreTheSameUser() {
        active(ALICE, ALICE.toUpperCase());
        checkedIn(ALICE.toUpperCase());
        statuses(status(BOB.toUpperCase(), UserStatusEnum.TDY));

        AccountedFor af = metrics().accountedFor();

        assertEquals(2, af.expected());
        assertEquals(2, af.accounted());
    }

    @Test
    void theUnaccountedListIsCappedButTheTotalIsNot() {
        List<String> many = new ArrayList<>();
        IntStream.range(0, AccountabilityMetricsService.LIST_LIMIT + 5)
                .forEach(i -> many.add("cn=User %03d,ou=Users,dc=winllc,dc=com".formatted(i)));
        AccountedFor af = service.forTeam(DAY, many).accountedFor();

        assertEquals(AccountabilityMetricsService.LIST_LIMIT, af.unaccounted().size());
        assertEquals(AccountabilityMetricsService.LIST_LIMIT + 5, af.unaccountedTotal());
        assertEquals("User 000", af.unaccounted().getFirst().name());
    }

    @Test
    void withNobodyExpectedThereIsNoRate() {
        AccountedFor af = metrics().accountedFor();

        assertTrue(af.workingDay());
        assertEquals(0, af.expected());
        assertNull(af.ratePercent());
    }

    @Test
    void expectedMeansActiveInTheLast30DaysThroughTheEndOfTheDay() {
        metrics();

        verify(checkIns).findDistinctDnsBetween(DAY.minusDays(30).atStartOfDay(ZONE),
                DAY.plusDays(1).atStartOfDay(ZONE).minusNanos(1));
        verify(checkIns).findDistinctDnsWithActionBetween(CheckInOutEnum.CHECK_IN, DAY.atStartOfDay(ZONE),
                DAY.plusDays(1).atStartOfDay(ZONE).minusNanos(1));
    }

    // --- non-working days ------------------------------------------------------------------------------

    @Test
    void aWeekendHasNoRateAndNobodyIsUnaccounted() {
        LocalDate saturday = LocalDate.of(2026, 9, 12);
        active(ALICE, BOB);
        checkedIn(ALICE);

        AccountedFor af = service.forDay(saturday).accountedFor();

        assertFalse(af.workingDay());
        assertEquals("Saturday", af.nonWorkingReason());
        assertNull(af.ratePercent());
        assertEquals(1, af.expected(), "only those who turned up");
        assertEquals(1, af.checkedIn());
        assertTrue(af.unaccounted().isEmpty());
        assertEquals(0, af.unaccountedTotal());
    }

    @Test
    void aHolidayIsNamedAfterItsTitle() {
        GlobalCalendarRecord laborDay = new GlobalCalendarRecord();
        laborDay.setDate(DAY);
        laborDay.setHoliday(true);
        laborDay.setTitle("Company Holiday");
        when(calendar.findByDate(DAY)).thenReturn(List.of(laborDay));
        active(ALICE);

        AccountedFor af = metrics().accountedFor();

        assertEquals("Company Holiday", af.nonWorkingReason());
        assertTrue(af.unaccounted().isEmpty());
    }

    @Test
    void aCalendarEventThatIsNotAHolidayIsAWorkingDay() {
        GlobalCalendarRecord event = new GlobalCalendarRecord();
        event.setDate(DAY);
        event.setHoliday(false);
        event.setTitle("All hands");
        when(calendar.findByDate(DAY)).thenReturn(List.of(event));

        assertTrue(metrics().accountedFor().workingDay());
    }

    // --- status mix -------------------------------------------------------------------------------------

    @Test
    void theMixPartitionsExpectedUsersInAFixedOrder() {
        active(ALICE, BOB, CAROL, DAVE, ERIN);
        checkedIn(ALICE, BOB, DAVE);
        statuses(status(DAVE, UserStatusEnum.WORK_FROM_HOME),
                status(CAROL, UserStatusEnum.TDY),
                status(BOB.toUpperCase(), UserStatusEnum.STANDARD));

        AccountabilityMetrics metrics = metrics();
        List<StatusMixEntry> mix = metrics.statusMix();

        assertEquals(List.of("CHECKED_IN", "WORK_FROM_HOME", "TDY", "UNACCOUNTED"),
                mix.stream().map(StatusMixEntry::key).toList());
        assertEquals(List.of(2, 1, 1, 1), mix.stream().map(StatusMixEntry::count).toList());
        assertEquals(metrics.accountedFor().expected(), mix.stream().mapToInt(StatusMixEntry::count).sum());
        assertEquals("Work From Home", mix.get(1).label());
    }

    @Test
    void emptyCategoriesAreLeftOut() {
        active(ALICE);
        checkedIn(ALICE);

        assertEquals(List.of("CHECKED_IN"), metrics().statusMix().stream().map(StatusMixEntry::key).toList());
    }

    /** Several statuses for one user on a day resolve the same way whatever order they load in. */
    @Test
    void aUserWithSeveralStatusesGetsTheFirstDeclaredOne() {
        active(ALICE);
        statuses(status(ALICE, UserStatusEnum.LATE_ARRIVAL), status(ALICE, UserStatusEnum.WORK_FROM_HOME));

        List<StatusMixEntry> mix = metrics().statusMix();

        assertEquals(1, mix.size());
        assertEquals("WORK_FROM_HOME", mix.getFirst().key());
    }

    @Test
    void segmentWidthsAreShares() {
        StatusMixEntry entry = new StatusMixEntry("TDY", "TDY", 1);

        assertEquals(25.0, entry.percentOf(4));
        assertEquals(0.0, entry.percentOf(0));
    }

    // --- a team -------------------------------------------------------------------------------------------

    /** A manager needs the report with no recent activity and no status to show as unaccounted for. */
    @Test
    void everyTeamMemberIsExpectedOnAWorkingDayEvenWithoutRecentActivity() {
        checkedIn(ALICE, DAVE);
        statuses(status(BOB, UserStatusEnum.TDY), status(ERIN, UserStatusEnum.WORK_FROM_HOME));

        AccountabilityMetrics team = service.forTeam(DAY, List.of(ALICE, BOB, CAROL));
        AccountedFor af = team.accountedFor();

        assertEquals(3, af.expected());
        assertEquals(2, af.accounted());
        assertEquals(List.of("Carol Clark"), names(af.unaccounted()));
        assertEquals(List.of("CHECKED_IN", "TDY", "UNACCOUNTED"),
                team.statusMix().stream().map(StatusMixEntry::key).toList());
    }

    @Test
    void peopleOutsideTheTeamAreLeftOutEvenWhenActive() {
        active(ALICE, DAVE, ERIN);
        checkedIn(DAVE);

        AccountedFor af = service.forTeam(DAY, List.of(ALICE)).accountedFor();

        assertEquals(1, af.expected());
        assertEquals(0, af.accounted());
    }

    @Test
    void onAWeekendOnlyTeamMembersWhoTurnedUpAreExpected() {
        LocalDate saturday = LocalDate.of(2026, 9, 12);
        checkedIn(ALICE);

        AccountedFor af = service.forTeam(saturday, List.of(ALICE, BOB)).accountedFor();

        assertEquals("Saturday", af.nonWorkingReason());
        assertEquals(1, af.expected());
        assertTrue(af.unaccounted().isEmpty());
    }

    @Test
    void teamAgentCoverageIsOverTheMembersNotEveryUser() {
        when(users.findAllDns()).thenReturn(List.of(ALICE, BOB, CAROL, DAVE));
        lastSeen(List.of(seen(ALICE, DAY), seen(BOB, DAY.minusDays(12))));

        AgentCoverage coverage = service.forTeam(DAY, List.of(ALICE.toUpperCase(), BOB, CAROL)).agentCoverage();

        assertEquals(3, coverage.users());
        assertEquals(1, coverage.reporting());
        assertEquals(1, coverage.stopped());
        assertEquals(1, coverage.neverReported());
        verify(users, never()).findAllDns();
    }

    // --- agent coverage ---------------------------------------------------------------------------------

    /** The same last-seen rows whether the service reads them organisation-wide or for a team. */
    private void lastSeen(List<LastSeen> seen) {
        when(checkIns.findLastSeenBetween(any(), any())).thenReturn(seen);
        when(checkIns.findLastSeenByLowercaseDnInBetween(any(), any(), any())).thenReturn(seen);
    }

    private static LastSeen seen(String dn, LocalDate day) {
        return new LastSeen(dn.toLowerCase(), day.atTime(9, 0).atZone(ZONE));
    }

    @Test
    void usersAreReportingStoppedOrNever() {
        when(users.findAllDns()).thenReturn(List.of(ALICE, BOB, CAROL, DAVE));
        lastSeen(List.of(
                seen(ALICE, DAY),
                seen(BOB, DAY.minusDays(6)),     // first day of the 7-day window
                seen(CAROL, DAY.minusDays(7))));  // one day before it

        AgentCoverage coverage = metrics().agentCoverage();

        assertEquals(4, coverage.users());
        assertEquals(2, coverage.reporting());
        assertEquals(1, coverage.stopped());
        assertEquals(1, coverage.neverReported());
        assertEquals(50, coverage.reportingPercent());
        assertEquals(7, coverage.reportingDays());
    }

    @Test
    void teamFiguresListTheAgentsThatStopped() {
        lastSeen(List.of(seen(ALICE, DAY), seen(CAROL, DAY.minusDays(7))));

        AgentCoverage coverage = service.forTeam(DAY, List.of(ALICE, CAROL)).agentCoverage();

        assertEquals(List.of(new StoppedAgent(CAROL, "Carol Clark", DAY.minusDays(7))), coverage.stoppedUsers());
    }

    @Test
    void theAgentsThatStoppedMostRecentlyAreListedFirst() {
        lastSeen(List.of(
                seen(ALICE, DAY.minusDays(20)), seen(BOB, DAY.minusDays(9)), seen(CAROL, DAY.minusDays(9))));

        List<String> stopped = service.forTeam(DAY, List.of(ALICE, BOB, CAROL)).agentCoverage()
                .stoppedUsers().stream().map(StoppedAgent::name).toList();

        assertEquals(List.of("Bob Barker", "Carol Clark", "Alice Adams"), stopped);
    }

    /** User records and event rows can disagree on DN case; the list shows the user record's spelling. */
    @Test
    void lastSeenMatchesUsersIgnoringCase() {
        when(users.findAllDns()).thenReturn(List.of(ALICE, ALICE.toUpperCase()));
        lastSeen(List.of(seen(ALICE.toUpperCase(), DAY.minusDays(10))));

        assertEquals(1, metrics().agentCoverage().users());
        AgentCoverage team = service.forTeam(DAY, List.of(ALICE, ALICE.toUpperCase())).agentCoverage();
        assertEquals(1, team.users());
        assertEquals(ALICE, team.stoppedUsers().getFirst().dn());
    }

    @Test
    void lastSeenIsReadUpToTheEndOfTheDayMeasured() {
        metrics();

        verify(checkIns).findLastSeenBetween(DAY.minusDays(89).atStartOfDay(ZONE),
                DAY.plusDays(1).atStartOfDay(ZONE).minusNanos(1));
    }

    @Test
    void theLastReportedDayIsTheLocalDay() {
        // 01:30 UTC on the 1st is still the evening of Aug 31 in New York.
        ZonedDateTime utc = ZonedDateTime.of(2026, 9, 1, 1, 30, 0, 0, ZoneId.of("UTC"));
        lastSeen(List.of(new LastSeen(ALICE.toLowerCase(), utc)));

        assertEquals(utc.withZoneSameInstant(ZONE).toLocalDate(),
                service.forTeam(DAY, List.of(ALICE)).agentCoverage().stoppedUsers().getFirst().lastSeen());
    }

    /** Only the last 90 days are read; activity before that counts as none. */
    @Test
    void agentCoverageLooksBack90DaysOrganisationWideAndOnlyAtMembersForATeam() {
        when(users.findAllDns()).thenReturn(List.of(ALICE));

        metrics();
        service.forTeam(DAY, List.of(BOB.toUpperCase(), CAROL));

        ZonedDateTime from = DAY.minusDays(89).atStartOfDay(ZONE);
        ZonedDateTime to = DAY.plusDays(1).atStartOfDay(ZONE).minusNanos(1);
        verify(checkIns).findLastSeenBetween(from, to);
        verify(checkIns).findLastSeenByLowercaseDnInBetween(
                argThat((java.util.Collection<String> dns) -> new java.util.HashSet<>(dns).equals(java.util.Set.of(BOB.toLowerCase(), CAROL.toLowerCase()))),
                eq(from), eq(to));
    }

    @Test
    void anEmptyTeamDoesNotQueryLastSeen() {
        service.forTeam(DAY, List.of());

        verify(checkIns, never()).findLastSeenByLowercaseDnInBetween(any(), any(), any());
    }

    @Test
    void withNoUsersThereIsNoCoveragePercent() {
        assertNull(metrics().agentCoverage().reportingPercent());
    }
}
