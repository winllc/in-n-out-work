package com.winllc.innoutwork.cron;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.model.GlobalCalendarRecord;
import com.winllc.innoutwork.model.UserEventRecord;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.GlobalCalendarRecordRepository;
import com.winllc.innoutwork.repository.NotificationRepository;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;

import java.time.LocalDate;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The expected login time decides when someone counts as absent, and the notification tells the
 * manager what that time was, so both must come from the same rule.
 */
class SendAbsentNotificationsCronTest {

    private static final String DN = "cn=Bob Barker,ou=Users,dc=winllc,dc=com";

    private static UserRecord user(LocalTime chosen, LocalTime average) {
        UserRecord user = new UserRecord();
        user.setDn(DN);
        user.setChosenLoginTime(chosen);
        user.setAverageLoginTime(average);
        return user;
    }

    private static UserEventRecord event(UserStatusEnum status, LocalTime loginBy) {
        UserEventRecord event = new UserEventRecord();
        event.setDn(DN);
        event.setDate(LocalDate.now());
        event.setStatus(status);
        event.setLoginByTime(loginBy);
        return event;
    }

    @Test
    void withNothingElseTheAverageLoginTimeIsExpected() {
        assertEquals(LocalTime.of(8, 30),
                SendAbsentNotificationsCron.expectedLoginTime(user(null, LocalTime.of(8, 30)), List.of()));
    }

    @Test
    void aChosenLoginTimeTakesPrecedenceOverTheAverage() {
        assertEquals(LocalTime.of(9, 0),
                SendAbsentNotificationsCron.expectedLoginTime(user(LocalTime.of(9, 0), LocalTime.of(8, 30)), List.of()));
    }

    @Test
    void aLateArrivalForTodayTakesPrecedenceOverEverything() {
        List<UserEventRecord> today = List.of(
                event(UserStatusEnum.WORK_FROM_HOME, null),
                event(UserStatusEnum.LATE_ARRIVAL, LocalTime.of(11, 0)));

        assertEquals(LocalTime.of(11, 0), SendAbsentNotificationsCron.expectedLoginTime(
                user(LocalTime.of(9, 0), LocalTime.of(8, 30)), today));
    }

    /** Used to throw from findFirst() on the null, failing the whole run. */
    @Test
    void aLateArrivalWithoutATimeFallsBackInsteadOfFailing() {
        List<UserEventRecord> today = List.of(event(UserStatusEnum.LATE_ARRIVAL, null));

        assertEquals(LocalTime.of(9, 0), SendAbsentNotificationsCron.expectedLoginTime(
                user(LocalTime.of(9, 0), LocalTime.of(8, 30)), today));
    }

    @Test
    void withNoTimeKnownNothingIsExpected() {
        assertNull(SendAbsentNotificationsCron.expectedLoginTime(user(null, null), List.of()));
    }

    /** Previously the notification always carried the average, whatever time had actually applied. */
    @Test
    void theNotificationCarriesTheExpectedTimeTheCheckUsed() {
        NotificationService notificationService = mock(NotificationService.class);
        SendAbsentNotificationsCron cron = new SendAbsentNotificationsCron(mock(UserRecordRepository.class),
                mock(CheckInOutRecordRepository.class), mock(UserEventRecordRepository.class), notificationService,
                mock(NotificationRepository.class), new ApplicationProperties(), mock(GlobalCalendarRecordRepository.class));

        cron.createAndSendNotification(user(LocalTime.of(9, 0), LocalTime.of(8, 30)),
                List.of(event(UserStatusEnum.LATE_ARRIVAL, LocalTime.of(11, 0))));

        verify(notificationService).createAbsentNotification(DN, LocalTime.of(11, 0));
    }

    // --- a run ------------------------------------------------------------------------------------------

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    /** Thursday 10 September 2026, 10:30: past a 9:00 expected time plus the default 60-minute grace. */
    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 9, 10, 10, 30, 0, 0, NEW_YORK);

    private UserRecordRepository users;
    private CheckInOutRecordRepository checkIns;
    private UserEventRecordRepository events;
    private NotificationRepository notifications;
    private GlobalCalendarRecordRepository calendar;
    private NotificationService notificationService;

    private SendAbsentNotificationsCron cronAt(ZonedDateTime now, List<UserRecord> everyone) {
        users = mock(UserRecordRepository.class);
        checkIns = mock(CheckInOutRecordRepository.class);
        events = mock(UserEventRecordRepository.class);
        notifications = mock(NotificationRepository.class);
        calendar = mock(GlobalCalendarRecordRepository.class);
        notificationService = mock(NotificationService.class);
        when(calendar.findByDate(any())).thenReturn(List.of());
        when(checkIns.findDistinctDnsWithActionBetween(any(), any(), any())).thenReturn(List.of());
        when(events.findByDate(any())).thenReturn(List.of());
        when(notifications.findDistinctAboutUserDnsBetween(any(), any())).thenReturn(List.of());

        int pageSize = SendAbsentNotificationsCron.PAGE_SIZE;
        when(users.findAllBy(any(Pageable.class))).thenAnswer(inv -> {
            Pageable page = inv.getArgument(0);
            int from = (int) page.getOffset();
            int to = Math.min(from + pageSize, everyone.size());
            return new SliceImpl<>(from < to ? everyone.subList(from, to) : List.of(), page, to < everyone.size());
        });

        return new SendAbsentNotificationsCron(users, checkIns, events, notificationService, notifications,
                new ApplicationProperties(), calendar, Clock.fixed(now.toInstant(), NEW_YORK));
    }

    private static UserRecord userWithDn(String dn, LocalTime preferred) {
        UserRecord user = new UserRecord();
        user.setDn(dn);
        user.setChosenLoginTime(preferred);
        return user;
    }

    private static UserEventRecord status(String dn, UserStatusEnum status) {
        UserEventRecord event = new UserEventRecord();
        event.setDn(dn);
        event.setDate(NOW.toLocalDate());
        event.setStatus(status);
        return event;
    }

    @Test
    void onlyUsersPastTheirWindowWhoHaveNotCheckedInOrBeenAlertedAreAlerted() {
        List<UserRecord> everyone = List.of(
                userWithDn("cn=absent", LocalTime.of(9, 0)),
                userWithDn("cn=checkedin", LocalTime.of(9, 0)),
                userWithDn("cn=alerted", LocalTime.of(9, 0)),
                userWithDn("cn=excused", LocalTime.of(9, 0)),
                userWithDn("cn=notyet", LocalTime.of(10, 0)),          // 10:00 + 60 minutes is after 10:30
                userWithDn("cn=unknown", null),                       // no expected time
                userWithDn("cn=late", LocalTime.of(9, 0)));           // late arrival entered for 11:00
        SendAbsentNotificationsCron cron = cronAt(NOW, everyone);
        when(checkIns.findDistinctDnsWithActionBetween(eq(CheckInOutEnum.CHECK_IN), any(), any())).thenReturn(List.of("CN=CHECKEDIN"));
        when(notifications.findDistinctAboutUserDnsBetween(any(), any())).thenReturn(List.of("cn=ALERTED"));
        UserEventRecord late = status("cn=late", UserStatusEnum.LATE_ARRIVAL);
        late.setLoginByTime(LocalTime.of(11, 0));
        when(events.findByDate(NOW.toLocalDate())).thenReturn(List.of(
                status("CN=Excused", UserStatusEnum.SCHEDULED_LEAVE), late));

        cron.sendNotifications();

        verify(notificationService).createAbsentNotification("cn=absent", LocalTime.of(9, 0));
        verify(notificationService, times(1)).createAbsentNotification(anyString(), any());
    }

    /** The queries run once per run, not once per user. */
    @Test
    void eachRunQueriesOnceHoweverManyUsersThereAre() {
        List<UserRecord> everyone = new ArrayList<>();
        for (int i = 0; i < SendAbsentNotificationsCron.PAGE_SIZE * 2 + 17; i++) {
            everyone.add(userWithDn("cn=user" + i, LocalTime.of(9, 0)));
        }
        SendAbsentNotificationsCron cron = cronAt(NOW, everyone);
        when(checkIns.findDistinctDnsWithActionBetween(any(), any(), any()))
                .thenReturn(everyone.stream().map(UserRecord::getDn).toList());

        cron.sendNotifications();

        ZonedDateTime dayStart = NOW.toLocalDate().atStartOfDay(NEW_YORK);
        ZonedDateTime dayEnd = dayStart.plusDays(1).minusNanos(1);
        verify(calendar, times(1)).findByDate(NOW.toLocalDate());
        verify(checkIns, times(1)).findDistinctDnsWithActionBetween(CheckInOutEnum.CHECK_IN, dayStart, dayEnd);
        verify(events, times(1)).findByDate(NOW.toLocalDate());
        verify(notifications, times(1)).findDistinctAboutUserDnsBetween(dayStart, dayEnd);
        verify(users, times(3)).findAllBy(any(Pageable.class));
        verifyNoInteractions(notificationService);
    }

    @Test
    void someoneAlertedEarlierInTheSameRunIsNotAlertedTwice() {
        SendAbsentNotificationsCron cron = cronAt(NOW, List.of(userWithDn("cn=bob", LocalTime.of(9, 0)), userWithDn("CN=BOB", LocalTime.of(9, 0))));

        cron.sendNotifications();

        verify(notificationService, times(1)).createAbsentNotification(anyString(), any());
    }

    @Test
    void weekendsAndHolidaysAreSkippedWithoutLookingAtUsers() {
        SendAbsentNotificationsCron saturday = cronAt(NOW.plusDays(2), List.of(userWithDn("cn=bob", LocalTime.of(9, 0))));
        saturday.sendNotifications();
        verifyNoInteractions(users, checkIns, events, notificationService);

        SendAbsentNotificationsCron holiday = cronAt(NOW, List.of(userWithDn("cn=bob", LocalTime.of(9, 0))));
        GlobalCalendarRecord record = new GlobalCalendarRecord();
        record.setDate(NOW.toLocalDate());
        record.setHoliday(true);
        when(calendar.findByDate(NOW.toLocalDate())).thenReturn(List.of(record));
        holiday.sendNotifications();
        verifyNoInteractions(users, checkIns, events, notificationService);
    }
}
