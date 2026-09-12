package com.winllc.innoutwork.data;

import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.model.UserEventRecord;
import com.winllc.innoutwork.model.UserRecord;

import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * When a user is expected in on a day, and which rule gave that time. The absence check and the
 * home page both use this, so what a user is told matches what they are held to.
 */
public record ExpectedLogin(LocalTime time, Source source) {

    public enum Source {
        LATE_ARRIVAL("late arrival today"),
        PREFERRED("your preferred time"),
        AVERAGE("your average arrival");

        private final String description;

        Source(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * A late-arrival entry for the day, else the preferred login time from the profile, else the
     * average login time.
     *
     * @param user         the user's record; may be null when the application has none yet
     * @param eventsForDay the user's status entries for the day
     * @return empty when no time is known
     */
    public static Optional<ExpectedLogin> of(UserRecord user, List<UserEventRecord> eventsForDay) {
        Optional<LocalTime> lateArrival = eventsForDay.stream()
                .filter(event -> event.getStatus() == UserStatusEnum.LATE_ARRIVAL)
                .map(UserEventRecord::getLoginByTime)
                .filter(Objects::nonNull)
                .findFirst();
        if (lateArrival.isPresent()) {
            return Optional.of(new ExpectedLogin(lateArrival.get(), Source.LATE_ARRIVAL));
        }
        if (user != null && user.getChosenLoginTime() != null) {
            return Optional.of(new ExpectedLogin(user.getChosenLoginTime(), Source.PREFERRED));
        }
        if (user != null && user.getAverageLoginTime() != null) {
            return Optional.of(new ExpectedLogin(user.getAverageLoginTime(), Source.AVERAGE));
        }
        return Optional.empty();
    }
}
