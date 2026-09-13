package com.winllc.innoutwork.data.team;

import com.winllc.innoutwork.data.home.AttendanceSummary;
import com.winllc.innoutwork.data.metrics.AccountabilityMetrics;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * The metrics on a manager's direct reports page.
 *
 * @param day            the day shown, which follows the date picker
 * @param from           the first day of the {@link #HISTORY_DAYS}-day history window ending on {@code day}
 * @param reportCount    direct reports in the directory
 * @param today          accounted for, status mix and agent coverage for {@code day}
 * @param attendance     every report's working days in the window added together ("report-days")
 * @param averageArrival the mean first check-in over those days, or null when nobody checked in
 * @param alerts         absence alerts raised about the reports in the window
 * @param daily          one entry per working day in the window, oldest first
 * @param reports        one row per report, by name
 */
public record DirectReportsSummary(LocalDate day, LocalDate from, int reportCount, AccountabilityMetrics today,
                                   AttendanceSummary attendance, LocalTime averageArrival, AbsenceAlerts alerts,
                                   List<DailyAttendance> daily, List<ReportAttendance> reports) {

    public static final int HISTORY_DAYS = 30;

    // Plain columns of the daily series for the chart script: lists of strings and numbers inline into
    // JavaScript predictably, where records and dates depend on the serializer in use.

    public List<String> dailyDates() {
        return daily.stream().map(d -> d.date().toString()).toList();
    }

    public List<Integer> dailyCheckedIn() {
        return daily.stream().map(DailyAttendance::checkedIn).toList();
    }

    public List<Integer> dailyStatusOnly() {
        return daily.stream().map(DailyAttendance::statusOnly).toList();
    }

    public List<Integer> dailyNoRecord() {
        return daily.stream().map(DailyAttendance::noRecord).toList();
    }

    public static DirectReportsSummary none(LocalDate day) {
        return new DirectReportsSummary(day, day.minusDays(HISTORY_DAYS - 1L), 0, null,
                new AttendanceSummary(0, 0, 0, 0), null, new AbsenceAlerts(0, 0, List.of()), List.of(), List.of());
    }
}
