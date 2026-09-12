package com.winllc.innoutwork.cron;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.model.UserEventRecord;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.GlobalCalendarRecordRepository;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.service.NotificationService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
        UserEventRecordRepository events = mock(UserEventRecordRepository.class);
        when(events.findByDnIgnoreCaseAndDate(eq(DN), any()))
                .thenReturn(List.of(event(UserStatusEnum.LATE_ARRIVAL, LocalTime.of(11, 0))));
        SendAbsentNotificationsCron cron = new SendAbsentNotificationsCron(mock(UserRecordRepository.class),
                mock(CheckInOutRecordRepository.class), events, notificationService, new ApplicationProperties(),
                mock(GlobalCalendarRecordRepository.class));

        cron.createAndSendNotification(user(LocalTime.of(9, 0), LocalTime.of(8, 30)));

        verify(notificationService).createAbsentNotification(DN, LocalTime.of(11, 0));
        verify(events).findByDnIgnoreCaseAndDate(DN, LocalDate.now());
    }
}
