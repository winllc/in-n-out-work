package com.winllc.innoutwork.data.team;

import com.winllc.innoutwork.data.home.AttendanceSummary;

import java.time.LocalTime;
import java.time.ZonedDateTime;

/**
 * One direct report over the history window.
 *
 * @param averageArrival their mean first check-in on working days in the window, or null
 * @param lastReported   their workstation agent's latest event up to the day shown, or null
 * @param agentQuiet     whether that agent has sent nothing for a week or more
 */
public record ReportAttendance(String dn, String name, AttendanceSummary attendance, LocalTime averageArrival,
                               ZonedDateTime lastReported, boolean agentQuiet) {
}
