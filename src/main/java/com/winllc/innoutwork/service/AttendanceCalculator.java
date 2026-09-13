package com.winllc.innoutwork.service;

import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.constant.DateTimeConstants;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.home.AttendanceSummary;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.GlobalCalendarRecord;
import com.winllc.innoutwork.model.UserEventRecord;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * How working days are counted, shared by the home page and the direct reports page so both give
 * the same answer. A working day is a weekday that is not a holiday; each is counted once, as
 * checked in, else covered by a status such as leave, else nothing recorded.
 */
final class AttendanceCalculator {

    private AttendanceCalculator() {
    }

    /** The working days from {@code from} to {@code to}, both included, in order. */
    static List<LocalDate> workingDays(LocalDate from, LocalDate to, Collection<GlobalCalendarRecord> calendar) {
        Set<LocalDate> holidays = calendar.stream()
                .filter(GlobalCalendarRecord::isHoliday)
                .map(GlobalCalendarRecord::getDate)
                .collect(Collectors.toSet());
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (!DateTimeConstants.WEEKEND_DAYS.contains(date.getDayOfWeek()) && !holidays.contains(date)) {
                days.add(date);
            }
        }
        return days;
    }

    /** One user's working days, given that user's records and status entries. */
    static AttendanceSummary summarize(List<LocalDate> workingDays, Collection<CheckInOutRecord> records,
                                       Collection<UserEventRecord> events, ZoneId zone) {
        Set<LocalDate> checkedIn = firstCheckIns(records, zone).keySet();
        Set<LocalDate> statusDays = statusDays(events);

        int in = 0;
        int statusOnly = 0;
        for (LocalDate day : workingDays) {
            if (checkedIn.contains(day)) {
                in++;
            } else if (statusDays.contains(day)) {
                statusOnly++;
            }
        }
        return new AttendanceSummary(workingDays.size(), in, statusOnly, workingDays.size() - in - statusOnly);
    }

    /** The earliest check-in on each local day that has one. */
    static Map<LocalDate, ZonedDateTime> firstCheckIns(Collection<CheckInOutRecord> records, ZoneId zone) {
        Map<LocalDate, ZonedDateTime> first = new HashMap<>();
        for (CheckInOutRecord record : records) {
            if (record.getAction() != CheckInOutEnum.CHECK_IN || record.getTimestamp() == null) {
                continue;
            }
            ZonedDateTime local = record.getTimestamp().withZoneSameInstant(zone);
            first.merge(local.toLocalDate(), local, (a, b) -> a.isBefore(b) ? a : b);
        }
        return first;
    }

    /** Days carrying a status other than STANDARD, which means no status at all. */
    static Set<LocalDate> statusDays(Collection<UserEventRecord> events) {
        return events.stream()
                .filter(e -> e.getStatus() != null && e.getStatus() != UserStatusEnum.STANDARD && e.getDate() != null)
                .map(UserEventRecord::getDate)
                .collect(Collectors.toSet());
    }
}
