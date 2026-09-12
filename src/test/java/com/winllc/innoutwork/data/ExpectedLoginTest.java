package com.winllc.innoutwork.data;

import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.model.UserEventRecord;
import com.winllc.innoutwork.model.UserRecord;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One rule decides when a user is expected in, for both the absence check and the home page. */
class ExpectedLoginTest {

    private static UserRecord user(LocalTime preferred, LocalTime average) {
        UserRecord user = new UserRecord();
        user.setChosenLoginTime(preferred);
        user.setAverageLoginTime(average);
        return user;
    }

    private static UserEventRecord lateArrival(LocalTime by) {
        UserEventRecord event = new UserEventRecord();
        event.setDate(LocalDate.now());
        event.setStatus(UserStatusEnum.LATE_ARRIVAL);
        event.setLoginByTime(by);
        return event;
    }

    @Test
    void aLateArrivalWinsAndSaysSo() {
        assertEquals(Optional.of(new ExpectedLogin(LocalTime.of(11, 0), ExpectedLogin.Source.LATE_ARRIVAL)),
                ExpectedLogin.of(user(LocalTime.of(9, 0), LocalTime.of(8, 0)), List.of(lateArrival(LocalTime.of(11, 0)))));
    }

    @Test
    void thenThePreferredTime() {
        assertEquals(Optional.of(new ExpectedLogin(LocalTime.of(9, 0), ExpectedLogin.Source.PREFERRED)),
                ExpectedLogin.of(user(LocalTime.of(9, 0), LocalTime.of(8, 0)), List.of(lateArrival(null))));
    }

    @Test
    void thenTheAverage() {
        assertEquals(Optional.of(new ExpectedLogin(LocalTime.of(8, 0), ExpectedLogin.Source.AVERAGE)),
                ExpectedLogin.of(user(null, LocalTime.of(8, 0)), List.of()));
    }

    /** A user the application has no record for yet can still have told us they will be late. */
    @Test
    void withoutARecordOnlyALateArrivalApplies() {
        assertTrue(ExpectedLogin.of(null, List.of()).isEmpty());
        assertEquals(ExpectedLogin.Source.LATE_ARRIVAL,
                ExpectedLogin.of(null, List.of(lateArrival(LocalTime.of(10, 0)))).orElseThrow().source());
    }

    @Test
    void sourcesDescribeThemselves() {
        assertEquals("your preferred time", ExpectedLogin.Source.PREFERRED.getDescription());
    }
}
