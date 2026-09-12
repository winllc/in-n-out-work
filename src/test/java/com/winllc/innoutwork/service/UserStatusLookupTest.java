package com.winllc.innoutwork.service;

import com.github.benmanes.caffeine.cache.LoadingCache;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.data.UserStatus;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.UserEventRecord;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
import com.winllc.innoutwork.repository.UserRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpSession;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link UserService#getUserStatus} fills the Status, Checked In, Checked Out, Last Status Change
 * and Notes columns of every user table. Every one of those is looked up by the DN it is given,
 * so a wrong DN shows up only as a row of blanks and "NONE".
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserStatusLookupTest {

    private static final String DN = "cn=Bob Barker,ou=Users,dc=winllc,dc=com";
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final ZonedDateTime DAY = ZonedDateTime.of(2026, 9, 10, 0, 0, 0, 0, ZONE);

    @Mock
    private CheckInOutRecordRepository checkInOutRecordRepository;
    @Mock
    private UserRecordRepository userRecordRepository;
    @Mock
    private UserEventRecordRepository userEventRecordRepository;

    private UserService userService;
    private MockHttpSession session;
    private final List<CheckInOutRecord> todaysRecords = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        CheckInOutService checkInOutService = new CheckInOutService(checkInOutRecordRepository, userRecordRepository);
        userService = new UserService(userRecordRepository, mock(LdapService.class), new ApplicationProperties(),
                (LoadingCache<String, LdapUser>) mock(LoadingCache.class), checkInOutService, userEventRecordRepository);

        // The date picker stores the day being viewed on the session.
        session = new MockHttpSession();
        session.setAttribute("systemTime", DAY.plusHours(15));

        when(checkInOutRecordRepository.findByTimestampBetweenAndDnIgnoreCaseOrderByTimestampDesc(any(), any(), anyString()))
                .thenReturn(todaysRecords);
        when(userRecordRepository.findByDnIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(userEventRecordRepository.findByDnIgnoreCaseAndDate(anyString(), any())).thenReturn(List.of());
    }

    private void record(CheckInOutEnum action, int hour, int minute) {
        CheckInOutRecord record = new CheckInOutRecord();
        record.setDn(DN);
        record.setAction(action);
        record.setTimestamp(DAY.withHour(hour).withMinute(minute));
        todaysRecords.add(record);
    }

    private UserStatus status() {
        return userService.getUserStatus(DN, session);
    }

    @Test
    void noRecordsTodayIsNone() {
        UserStatus status = status();

        assertEquals(DN, status.getDn());
        assertEquals("NONE", status.getStatus());
        assertNull(status.getLastStatusChangeAt());
        assertNull(status.getCheckedInAt());
        assertNull(status.getCheckedOutAt());
    }

    @Test
    void aCheckInIsIn() {
        record(CheckInOutEnum.CHECK_IN, 8, 0);

        assertEquals("IN", status().getStatus());
    }

    @Test
    void anUnlockIsIn() {
        record(CheckInOutEnum.CHECK_IN, 8, 0);
        record(CheckInOutEnum.LOCK, 12, 0);
        record(CheckInOutEnum.UNLOCK, 13, 0);

        assertEquals("IN", status().getStatus());
    }

    @Test
    void aLockIsAway() {
        record(CheckInOutEnum.CHECK_IN, 8, 0);
        record(CheckInOutEnum.LOCK, 12, 0);

        assertEquals("AWAY", status().getStatus());
    }

    @Test
    void aCheckOutIsOut() {
        record(CheckInOutEnum.CHECK_IN, 8, 0);
        record(CheckInOutEnum.CHECK_OUT, 17, 0);

        assertEquals("OUT", status().getStatus());
    }

    /** The repository orders newest first, but the status must not depend on it. */
    @Test
    void theMostRecentRecordWinsWhateverOrderTheyArriveIn() {
        record(CheckInOutEnum.CHECK_OUT, 17, 0);
        record(CheckInOutEnum.CHECK_IN, 8, 0);
        record(CheckInOutEnum.LOCK, 12, 0);

        UserStatus status = status();

        assertEquals("OUT", status.getStatus());
        assertEquals(DAY.withHour(17).toInstant(), status.getLastStatusChangeAt().toInstant());
    }

    @Test
    void checkedInIsTheFirstCheckInAndCheckedOutTheLastCheckOut() {
        record(CheckInOutEnum.CHECK_IN, 10, 0);
        record(CheckInOutEnum.CHECK_OUT, 12, 0);
        record(CheckInOutEnum.CHECK_IN, 8, 30);
        record(CheckInOutEnum.CHECK_OUT, 17, 45);
        record(CheckInOutEnum.CHECK_IN, 13, 0);

        UserStatus status = status();

        assertEquals(DAY.withHour(8).withMinute(30).toInstant(), status.getCheckedInAt().toInstant());
        assertEquals(DAY.withHour(17).withMinute(45).toInstant(), status.getCheckedOutAt().toInstant());
    }

    /** A leave or TDY entered for the day replaces the badge from the check-in records. */
    @Test
    void aNonStandardEventForTheDayOverridesTheStatus() {
        record(CheckInOutEnum.CHECK_IN, 8, 0);
        UserEventRecord event = new UserEventRecord();
        event.setStatus(UserStatusEnum.WORK_FROM_HOME);
        when(userEventRecordRepository.findByDnIgnoreCaseAndDate(eq(DN), eq(DAY.toLocalDate())))
                .thenReturn(List.of(event));

        assertEquals("WORK_FROM_HOME", status().getStatus());
    }

    @Test
    void aStandardEventLeavesTheStatusAlone() {
        record(CheckInOutEnum.CHECK_IN, 8, 0);
        UserEventRecord event = new UserEventRecord();
        event.setStatus(UserStatusEnum.STANDARD);
        when(userEventRecordRepository.findByDnIgnoreCaseAndDate(eq(DN), any())).thenReturn(List.of(event));

        assertEquals("IN", status().getStatus());
    }

    @Test
    void notesAndMetadataComeFromTheUsersRecord() {
        UserRecord record = new UserRecord();
        record.setDn(DN);
        record.setNotes("In the lab");
        record.setOrganization("WinLLC");
        record.setEmployeeType("FT");
        when(userRecordRepository.findByDnIgnoreCase(DN)).thenReturn(Optional.of(record));

        UserStatus status = status();

        assertEquals("In the lab", status.getNotes());
        assertEquals("WinLLC", status.getOrganization());
        assertEquals("FT", status.getEmployeeType());
    }

    /** Every lookup must use the DN the caller passed; a mismatch is the blank-row failure. */
    @Test
    void everyLookupUsesTheGivenDnForTheViewedDay() {
        status();

        ArgumentCaptor<ZonedDateTime> start = ArgumentCaptor.forClass(ZonedDateTime.class);
        ArgumentCaptor<ZonedDateTime> end = ArgumentCaptor.forClass(ZonedDateTime.class);
        verify(checkInOutRecordRepository).findByTimestampBetweenAndDnIgnoreCaseOrderByTimestampDesc(
                start.capture(), end.capture(), eq(DN));
        verify(userRecordRepository).findByDnIgnoreCase(DN);
        verify(userEventRecordRepository).findByDnIgnoreCaseAndDate(DN, DAY.toLocalDate());

        assertEquals(DAY, start.getValue());
        assertEquals(DAY.plusDays(1).minusNanos(1), end.getValue());
    }

    @Test
    void withoutAChosenDayTodayIsUsed() {
        session.removeAttribute("systemTime");

        status();

        ArgumentCaptor<ZonedDateTime> start = ArgumentCaptor.forClass(ZonedDateTime.class);
        verify(checkInOutRecordRepository).findByTimestampBetweenAndDnIgnoreCaseOrderByTimestampDesc(
                start.capture(), any(), eq(DN));
        assertEquals(ZonedDateTime.now(ZONE).truncatedTo(ChronoUnit.DAYS).toLocalDate(),
                start.getValue().toLocalDate());
    }
}
