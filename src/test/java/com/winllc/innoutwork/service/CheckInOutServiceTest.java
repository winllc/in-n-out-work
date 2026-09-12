package com.winllc.innoutwork.service;

import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.constant.DateTimeConstants;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.UserRecordRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockHttpSession;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
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
 * {@link CheckInOutService} decides what a status event is stored as, keeps each user's average
 * login time, and defines "the day" every table and metric is filtered to.
 * <p>
 * The record repository is backed by a list and answers its queries the way Spring Data would
 * (inclusive {@code Between}, case-insensitive DN), so day windows and ordering are evaluated for
 * real rather than stubbed to whatever a test expects. The JVM zone is pinned to the one the app
 * runs in, so midnight and DST cases are deterministic.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CheckInOutServiceTest {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final String BOB = "cn=Bob Barker,ou=Users,dc=winllc,dc=com";
    private static final String CAROL = "cn=Carol Clark,ou=Users,dc=winllc,dc=com";
    private static final ZonedDateTime SEP_10 = ZonedDateTime.of(2026, 9, 10, 0, 0, 0, 0, NEW_YORK);

    @Mock
    private CheckInOutRecordRepository recordRepository;
    @Mock
    private UserRecordRepository userRecordRepository;

    private CheckInOutService service;
    private final List<CheckInOutRecord> stored = new ArrayList<>();
    private TimeZone originalZone;

    @BeforeEach
    void setUp() {
        originalZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone(NEW_YORK));

        service = new CheckInOutService(recordRepository, userRecordRepository);

        when(recordRepository.save(any(CheckInOutRecord.class))).thenAnswer(inv -> {
            CheckInOutRecord record = inv.getArgument(0);
            stored.add(record);
            return record;
        });
        when(recordRepository.findByTimestampBetweenAndDnIgnoreCaseOrderByTimestampDesc(any(), any(), anyString()))
                .thenAnswer(inv -> query(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), null));
        when(recordRepository.findByDnIgnoreCaseAndTimestampIsBetweenAndActionEqualsOrderByTimestampDesc(
                anyString(), any(), any(), any()))
                .thenAnswer(inv -> query(inv.getArgument(1), inv.getArgument(2), inv.getArgument(0), inv.getArgument(3)));
        when(userRecordRepository.findByDnIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(userRecordRepository.save(any(UserRecord.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void restoreZone() {
        TimeZone.setDefault(originalZone);
    }

    private List<CheckInOutRecord> query(ZonedDateTime start, ZonedDateTime end, String dn, CheckInOutEnum action) {
        return stored.stream()
                .filter(r -> r.getDn().equalsIgnoreCase(dn))
                .filter(r -> !r.getTimestamp().isBefore(start) && !r.getTimestamp().isAfter(end))
                .filter(r -> action == null || r.getAction() == action)
                .sorted(Comparator.comparing(CheckInOutRecord::getTimestamp).reversed())
                .toList();
    }

    /** An existing row, as loaded back from the database: timestamps come back as UTC instants. */
    private void existing(String dn, CheckInOutEnum action, ZonedDateTime at) {
        stored.add(record(dn, action, at.withZoneSameInstant(ZoneOffset.UTC)));
    }

    private static CheckInOutRecord record(String dn, CheckInOutEnum action, ZonedDateTime at) {
        CheckInOutRecord record = new CheckInOutRecord();
        record.setDn(dn);
        record.setAction(action);
        record.setTimestamp(at);
        return record;
    }

    private CheckInOutRecord save(String dn, CheckInOutEnum action, ZonedDateTime at) {
        return service.saveCheckInOutRecord(record(dn, action, at));
    }

    private UserRecord userRecord(String dn) {
        UserRecord user = new UserRecord();
        user.setDn(dn);
        when(userRecordRepository.findByDnIgnoreCase(dn)).thenReturn(Optional.of(user));
        return user;
    }

    /**
     * A local time some weekdays back. Weekend check-ins are left out of the average, so tests step
     * over Saturdays and Sundays, and use yesterday or earlier as "today" so every timestamp is in
     * the past and inside the 30-day window that ends now, whatever day and time the suite runs.
     */
    private static ZonedDateTime daysAgoAt(int weekdays, int hour, int minute) {
        ZonedDateTime day = ZonedDateTime.now(NEW_YORK).truncatedTo(ChronoUnit.DAYS);
        for (int stepped = 0; stepped < weekdays; ) {
            day = day.minusDays(1);
            if (!DateTimeConstants.WEEKEND_DAYS.contains(day.getDayOfWeek())) {
                stepped++;
            }
        }
        return day.withHour(hour).withMinute(minute);
    }

    /** The most recent Saturday or Sunday before today. */
    private static ZonedDateTime lastWeekendDayAt(int hour, int minute) {
        ZonedDateTime day = ZonedDateTime.now(NEW_YORK).truncatedTo(ChronoUnit.DAYS).minusDays(1);
        while (!DateTimeConstants.WEEKEND_DAYS.contains(day.getDayOfWeek())) {
            day = day.minusDays(1);
        }
        return day.withHour(hour).withMinute(minute);
    }

    // --- what an event is stored as ----------------------------------------------------------

    /** Unlocking the workstation is how most people arrive, so the day's first unlock is a check-in. */
    @Test
    void theFirstUnlockOfTheDayIsRecordedAsACheckIn() {
        assertEquals(CheckInOutEnum.CHECK_IN, save(BOB, CheckInOutEnum.UNLOCK, SEP_10.withHour(8)).getAction());
    }

    @Test
    void anUnlockAfterEarlierActivityThatDayStaysAnUnlock() {
        existing(BOB, CheckInOutEnum.CHECK_IN, SEP_10.withHour(8));
        existing(BOB, CheckInOutEnum.LOCK, SEP_10.withHour(12));

        assertEquals(CheckInOutEnum.UNLOCK, save(BOB, CheckInOutEnum.UNLOCK, SEP_10.withHour(13)).getAction());
    }

    @Test
    void yesterdaysActivityDoesNotCountAsEarlierActivity() {
        existing(BOB, CheckInOutEnum.LOCK, SEP_10.minusDays(1).withHour(18));

        assertEquals(CheckInOutEnum.CHECK_IN, save(BOB, CheckInOutEnum.UNLOCK, SEP_10.withHour(8)).getAction());
    }

    /**
     * The day is the local calendar day. 23:30 on the 9th in New York is already the 10th in UTC,
     * which is how the row comes back from the database.
     */
    @Test
    void theDayStartsAtLocalMidnightNotUtcMidnight() {
        existing(BOB, CheckInOutEnum.LOCK, SEP_10.minusMinutes(30));

        assertEquals(CheckInOutEnum.CHECK_IN, save(BOB, CheckInOutEnum.UNLOCK, SEP_10.withHour(0).withMinute(15)).getAction());
    }

    @Test
    void activityLateTheSameLocalDayStillCounts() {
        existing(BOB, CheckInOutEnum.LOCK, SEP_10.withHour(21));

        assertEquals(CheckInOutEnum.UNLOCK, save(BOB, CheckInOutEnum.UNLOCK, SEP_10.withHour(23)).getAction());
    }

    @Test
    void anotherUsersActivityDoesNotCount() {
        existing(CAROL, CheckInOutEnum.CHECK_IN, SEP_10.withHour(7));

        assertEquals(CheckInOutEnum.CHECK_IN, save(BOB, CheckInOutEnum.UNLOCK, SEP_10.withHour(8)).getAction());
    }

    /** DNs arrive from certificates in whatever case the sender used. */
    @Test
    void earlierActivityIsMatchedIgnoringDnCase() {
        existing(BOB.toUpperCase(), CheckInOutEnum.CHECK_IN, SEP_10.withHour(8));

        assertEquals(CheckInOutEnum.UNLOCK, save(BOB, CheckInOutEnum.UNLOCK, SEP_10.withHour(13)).getAction());
    }

    @Test
    void locksAndCheckOutsAreStoredAsSentWithoutLookingAtTheDay() {
        assertEquals(CheckInOutEnum.LOCK, save(BOB, CheckInOutEnum.LOCK, SEP_10.withHour(12)).getAction());
        assertEquals(CheckInOutEnum.CHECK_OUT, save(BOB, CheckInOutEnum.CHECK_OUT, SEP_10.withHour(17)).getAction());

        verify(recordRepository, never()).findByTimestampBetweenAndDnIgnoreCaseOrderByTimestampDesc(any(), any(), anyString());
        verify(userRecordRepository, never()).findByDnIgnoreCase(anyString());
    }

    @Test
    void anExplicitCheckInIsStoredAsACheckInEvenAfterEarlierActivity() {
        existing(BOB, CheckInOutEnum.CHECK_OUT, SEP_10.withHour(12));

        assertEquals(CheckInOutEnum.CHECK_IN, save(BOB, CheckInOutEnum.CHECK_IN, SEP_10.withHour(13)).getAction());
    }

    @Test
    void theSavedRecordIsReturnedAndPersistedOnce() {
        CheckInOutRecord saved = save(BOB, CheckInOutEnum.LOCK, SEP_10.withHour(12));

        assertEquals(List.of(saved), stored);
        assertEquals(BOB, saved.getDn());
        assertEquals(SEP_10.withHour(12), saved.getTimestamp());
    }

    // --- average login time --------------------------------------------------------------------

    /** The average after a check-in is over every check-in in the window, this one included. */
    @Test
    void aCheckInUpdatesTheAverageLoginTimeIncludingItself() {
        UserRecord bob = userRecord(BOB);
        existing(BOB, CheckInOutEnum.CHECK_IN, daysAgoAt(3, 8, 0));
        existing(BOB, CheckInOutEnum.CHECK_IN, daysAgoAt(2, 9, 0));

        save(BOB, CheckInOutEnum.CHECK_IN, daysAgoAt(1, 10, 0));

        assertEquals(LocalTime.of(9, 0), bob.getAverageLoginTime());
        verify(userRecordRepository).save(bob);
    }

    /** Otherwise a new user has no average until their second day. */
    @Test
    void theFirstCheckInEverSetsTheAverageToThatTime() {
        UserRecord bob = userRecord(BOB);

        save(BOB, CheckInOutEnum.CHECK_IN, daysAgoAt(1, 8, 45));

        assertEquals(LocalTime.of(8, 45), bob.getAverageLoginTime());
    }

    @Test
    void aPromotedUnlockUpdatesTheAverageLikeACheckIn() {
        UserRecord bob = userRecord(BOB);
        existing(BOB, CheckInOutEnum.CHECK_IN, daysAgoAt(2, 8, 0));

        save(BOB, CheckInOutEnum.UNLOCK, daysAgoAt(1, 9, 0));

        assertNotNull(bob.getAverageLoginTime());
        verify(userRecordRepository).save(bob);
    }

    @Test
    void theAverageOnlyCountsTheUsersOwnCheckInsFromTheLast30Days() {
        UserRecord bob = userRecord(BOB);
        existing(BOB, CheckInOutEnum.CHECK_IN, daysAgoAt(2, 8, 0));
        existing(BOB, CheckInOutEnum.CHECK_IN, daysAgoAt(3, 8, 0));
        existing(BOB, CheckInOutEnum.CHECK_IN, daysAgoAt(32, 5, 0));   // too old
        existing(BOB, CheckInOutEnum.UNLOCK, daysAgoAt(2, 13, 0));     // not a check-in
        existing(CAROL, CheckInOutEnum.CHECK_IN, daysAgoAt(2, 6, 0));  // someone else

        save(BOB, CheckInOutEnum.CHECK_IN, daysAgoAt(1, 8, 0));

        assertEquals(LocalTime.of(8, 0), bob.getAverageLoginTime());
    }

    /**
     * The workstation task posts a check-in on every Windows logon, so a reboot or signing back in
     * after lunch adds a second check-in that day. Arrival is the first one; later ones must not
     * drag the expected login time, which drives the absence alerts, later in the day.
     */
    @Test
    void onlyTheFirstCheckInOfEachDayCountsTowardsTheAverage() {
        UserRecord bob = userRecord(BOB);
        for (int day = 5; day >= 2; day--) {
            existing(BOB, CheckInOutEnum.CHECK_IN, daysAgoAt(day, 8, 0));
        }
        existing(BOB, CheckInOutEnum.CHECK_IN, daysAgoAt(4, 13, 0));   // reboot
        existing(BOB, CheckInOutEnum.CHECK_IN, daysAgoAt(2, 13, 0));   // back from lunch

        save(BOB, CheckInOutEnum.CHECK_IN, daysAgoAt(1, 8, 0));

        assertEquals(LocalTime.of(8, 0), bob.getAverageLoginTime());
    }

    /** Today's arrival already counted; signing in again later must leave the average where it is. */
    @Test
    void aSecondCheckInTheSameDayDoesNotMoveTheAverage() {
        UserRecord bob = userRecord(BOB);
        existing(BOB, CheckInOutEnum.CHECK_IN, daysAgoAt(2, 8, 0));
        existing(BOB, CheckInOutEnum.CHECK_IN, daysAgoAt(1, 9, 0));

        save(BOB, CheckInOutEnum.CHECK_IN, daysAgoAt(1, 14, 0));

        assertEquals(LocalTime.of(8, 30), bob.getAverageLoginTime());
    }

    /** The absence check skips weekends, so a quick weekend sign-in must not shift the weekday expectation. */
    @Test
    void weekendCheckInsAreLeftOutOfTheAverage() {
        UserRecord bob = userRecord(BOB);
        existing(BOB, CheckInOutEnum.CHECK_IN, daysAgoAt(2, 8, 0));
        existing(BOB, CheckInOutEnum.CHECK_IN, lastWeekendDayAt(14, 0));

        save(BOB, CheckInOutEnum.CHECK_IN, daysAgoAt(1, 8, 0));

        assertEquals(LocalTime.of(8, 0), bob.getAverageLoginTime());
    }

    @Test
    void theAverageWindowIsTheLast30DaysOfCheckInsForTheUser() {
        userRecord(BOB);

        save(BOB, CheckInOutEnum.CHECK_IN, daysAgoAt(1, 8, 0));

        ArgumentCaptor<ZonedDateTime> from = ArgumentCaptor.forClass(ZonedDateTime.class);
        ArgumentCaptor<ZonedDateTime> to = ArgumentCaptor.forClass(ZonedDateTime.class);
        verify(recordRepository).findByDnIgnoreCaseAndTimestampIsBetweenAndActionEqualsOrderByTimestampDesc(
                eq(BOB), from.capture(), to.capture(), eq(CheckInOutEnum.CHECK_IN));
        assertEquals(Duration.ofDays(30), Duration.between(from.getValue(), to.getValue()).truncatedTo(ChronoUnit.MINUTES));
        assertTrue(Duration.between(to.getValue(), ZonedDateTime.now()).abs().toMinutes() < 1, "window should end now");
    }

    @Test
    void aCheckInWithoutAUserRecordIsStillSaved() {
        CheckInOutRecord saved = save(BOB, CheckInOutEnum.CHECK_IN, daysAgoAt(1, 8, 0));

        assertEquals(List.of(saved), stored);
        verify(userRecordRepository, never()).save(any());
    }

    @Test
    void locksUnlocksAndCheckOutsLeaveTheAverageAlone() {
        UserRecord bob = userRecord(BOB);
        bob.setAverageLoginTime(LocalTime.of(8, 0));
        existing(BOB, CheckInOutEnum.CHECK_IN, daysAgoAt(1, 7, 0));

        save(BOB, CheckInOutEnum.LOCK, daysAgoAt(1, 12, 0));
        save(BOB, CheckInOutEnum.UNLOCK, daysAgoAt(1, 13, 0));
        save(BOB, CheckInOutEnum.CHECK_OUT, daysAgoAt(1, 17, 0));

        assertEquals(LocalTime.of(8, 0), bob.getAverageLoginTime());
        verify(userRecordRepository, never()).save(any());
    }

    // --- calculateAverage ----------------------------------------------------------------------

    @Test
    void theAverageOfNoTimestampsIsNull() {
        assertNull(CheckInOutService.calculateAverage(null));
        assertNull(CheckInOutService.calculateAverage(List.of()));
    }

    @Test
    void theAverageIsTheMeanTimeOfDayWhateverTheDate() {
        LocalTime average = CheckInOutService.calculateAverage(List.of(
                SEP_10.withHour(8), SEP_10.minusDays(3).withHour(9).withMinute(30)));

        assertEquals(LocalTime.of(8, 45), average);
    }

    @Test
    void theAverageDropsFractionalSeconds() {
        assertEquals(LocalTime.of(8, 0, 0), CheckInOutService.calculateAverage(List.of(
                SEP_10.withHour(8), SEP_10.withHour(8).withSecond(1))));
    }

    /** Unlike a circular mean, times that do not straddle midnight keep their ordinary mean. */
    @Test
    void daytimeTimesKeepTheirOrdinaryMean() {
        assertEquals(LocalTime.of(9, 0), CheckInOutService.calculateAverage(List.of(
                SEP_10.withHour(8), SEP_10.withHour(8), SEP_10.withHour(11))));
    }

    @Test
    void timesEitherSideOfMidnightAverageToMidnight() {
        assertEquals(LocalTime.MIDNIGHT, CheckInOutService.calculateAverage(List.of(
                SEP_10.withHour(23).withMinute(30), SEP_10.withHour(0).withMinute(30))));
        assertEquals(LocalTime.MIDNIGHT, CheckInOutService.calculateAverage(List.of(
                SEP_10.withHour(22), SEP_10.withHour(2))));
    }

    @Test
    void anOvernightAverageCanFallBeforeMidnight() {
        assertEquals(LocalTime.of(23, 40), CheckInOutService.calculateAverage(List.of(
                SEP_10.withHour(23), SEP_10.withHour(23).withMinute(30), SEP_10.withHour(0).withMinute(30))));
    }

    @Test
    void anOvernightAverageCanFallAfterMidnight() {
        assertEquals(LocalTime.of(0, 20), CheckInOutService.calculateAverage(List.of(
                SEP_10.withHour(23).withMinute(30), SEP_10.withHour(0).withMinute(30), SEP_10.withHour(1))));
    }

    /** Opposite times have no circular mean; the ordinary mean is the fallback. */
    @Test
    void timesEvenlySpreadRoundTheClockFallBackToTheOrdinaryMean() {
        assertEquals(LocalTime.NOON, CheckInOutService.calculateAverage(List.of(
                SEP_10.withHour(6), SEP_10.withHour(18))));
    }

    /**
     * Stored rows come back in UTC; averaged as-is, an 8:00 arrival would count as 12:00 in
     * summer and 13:00 in winter. Converted to local time first, both are 8:00.
     */
    @Test
    void storedTimestampsAreAveragedAsLocalWallClockTimeAcrossDst() {
        CheckInOutRecord summer = record(BOB, CheckInOutEnum.CHECK_IN,
                ZonedDateTime.of(2026, 10, 30, 8, 0, 0, 0, NEW_YORK).withZoneSameInstant(ZoneOffset.UTC));
        CheckInOutRecord winter = record(BOB, CheckInOutEnum.CHECK_IN,
                ZonedDateTime.of(2026, 11, 2, 8, 0, 0, 0, NEW_YORK).withZoneSameInstant(ZoneOffset.UTC));

        LocalTime average = CheckInOutService.calculateAverage(List.of(
                summer.getZonedDateTimestamp(), winter.getZonedDateTimestamp()));

        assertEquals(LocalTime.of(8, 0), average);
    }

    // --- the viewed day ------------------------------------------------------------------------

    private static MockHttpSession viewing(ZonedDateTime systemTime) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("systemTime", systemTime);
        return session;
    }

    private ZonedDateTime[] windowForFindRecords(MockHttpSession session) {
        service.findRecords(session);
        ArgumentCaptor<ZonedDateTime> start = ArgumentCaptor.forClass(ZonedDateTime.class);
        ArgumentCaptor<ZonedDateTime> end = ArgumentCaptor.forClass(ZonedDateTime.class);
        verify(recordRepository).findByTimestampBetweenOrderByTimestampDesc(start.capture(), end.capture());
        return new ZonedDateTime[]{start.getValue(), end.getValue()};
    }

    private static void assertIsLocalDay(ZonedDateTime day, ZonedDateTime[] window) {
        assertEquals(day.toInstant(), window[0].toInstant(), "window start");
        assertEquals(day.plusDays(1).minusNanos(1).toInstant(), window[1].toInstant(), "window end");
    }

    @Test
    void theViewedDayRunsFromLocalMidnightToTheLastNanosecond() {
        assertIsLocalDay(SEP_10, windowForFindRecords(viewing(SEP_10.withHour(15))));
    }

    @Test
    void withNothingChosenTheViewedDayIsToday() {
        assertIsLocalDay(ZonedDateTime.now(NEW_YORK).truncatedTo(ChronoUnit.DAYS),
                windowForFindRecords(new MockHttpSession()));
    }

    /**
     * layout.html posts the picked date as {@code selectedDates[0].toISOString()}: local midnight
     * expressed in UTC. The day shown must still be the day that was picked, midnight to midnight
     * locally, not 8pm the evening before to 8pm.
     */
    @Test
    void aDatePickedInTheBrowserIsTheLocalDayEvenThoughItArrivesInUtc() {
        ZonedDateTime picked = ZonedDateTime.parse("2026-09-10T04:00:00.000Z");

        assertIsLocalDay(SEP_10, windowForFindRecords(viewing(picked)));
    }

    @Test
    void theDayTheClocksGoBackIs25HoursLong() {
        ZonedDateTime fallBack = ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, NEW_YORK);

        ZonedDateTime[] window = windowForFindRecords(viewing(fallBack.withHour(12)));

        assertIsLocalDay(fallBack, window);
        assertEquals(Duration.ofHours(25).minusNanos(1), Duration.between(window[0], window[1]));
    }

    @Test
    void everyDayQueryUsesTheSameWindow() {
        MockHttpSession session = viewing(SEP_10.withHour(15));
        Pageable page = PageRequest.of(0, 10);

        service.findRecords(page, session);
        service.findRecordsForUser(BOB, session);

        ZonedDateTime end = SEP_10.plusDays(1).minusNanos(1);
        verify(recordRepository).findByTimestampBetween(SEP_10, end, page);
        verify(recordRepository).findByTimestampBetweenAndDnIgnoreCaseOrderByTimestampDesc(SEP_10, end, BOB);
    }

    @Test
    void recordsForAUserAreOnlyThatUsersRecordsForTheDay() {
        existing(BOB, CheckInOutEnum.CHECK_IN, SEP_10.withHour(8));
        existing(BOB, CheckInOutEnum.CHECK_OUT, SEP_10.withHour(17));
        existing(BOB, CheckInOutEnum.CHECK_IN, SEP_10.minusDays(1).withHour(8));
        existing(CAROL, CheckInOutEnum.CHECK_IN, SEP_10.withHour(9));

        List<CheckInOutRecord> records = service.findRecordsForUser(BOB, viewing(SEP_10.withHour(12)));

        assertEquals(List.of(CheckInOutEnum.CHECK_OUT, CheckInOutEnum.CHECK_IN),
                records.stream().map(CheckInOutRecord::getAction).toList());
    }

    // --- pass-throughs -------------------------------------------------------------------------

    @Test
    void aRecordIsFoundBySessionId() {
        CheckInOutRecord record = record(BOB, CheckInOutEnum.CHECK_IN, SEP_10);
        when(recordRepository.findFirstBySessionId("abc")).thenReturn(Optional.of(record));

        assertEquals(Optional.of(record), service.lookupBySessionId("abc"));
        assertTrue(service.lookupBySessionId("missing").isEmpty());
    }

    @Test
    void theRecordCountComesFromTheRepository() {
        when(recordRepository.count()).thenReturn(42L);

        assertEquals(42L, service.getCheckInOutRecordCount());
    }
}
