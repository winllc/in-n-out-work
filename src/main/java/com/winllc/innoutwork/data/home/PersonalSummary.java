package com.winllc.innoutwork.data.home;

import com.winllc.innoutwork.data.ExpectedLogin;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * The signed-in user's own figures.
 *
 * @param day                 the day shown, which follows the date picker
 * @param statusLabel         today's status in words
 * @param statusBadge         Tabler badge class for it
 * @param statusSince         when that status began, or null
 * @param checkedInAt         the first check-in of the day, or null
 * @param checkedOutAt        the last check-out of the day, or null
 * @param expected            when they are expected in and why, or null when no time is known
 * @param averageLoginTime    their average arrival over recent weekdays, or null
 * @param last30Days          attendance over the 30 days ending on {@code day}
 * @param lastReported        the latest event from their workstation agent in that window, or null
 * @param agentQuiet          whether that agent has sent nothing for {@link #QUIET_AFTER_DAYS} days or more
 * @param upcoming            their statuses for the next {@link #UPCOMING_DAYS} days
 */
public record PersonalSummary(LocalDate day, String statusLabel, String statusBadge, ZonedDateTime statusSince,
                              ZonedDateTime checkedInAt, ZonedDateTime checkedOutAt, ExpectedLogin expected,
                              LocalTime averageLoginTime, AttendanceSummary last30Days,
                              ZonedDateTime lastReported, boolean agentQuiet, List<UpcomingStatus> upcoming) {

    public static final int UPCOMING_DAYS = 14;
    public static final int QUIET_AFTER_DAYS = 7;
}
