package com.winllc.innoutwork.data.team;

import java.time.LocalDate;

/** How a team's working day went: each report counted once, as checked in, else status, else nothing. */
public record DailyAttendance(LocalDate date, int checkedIn, int statusOnly, int noRecord) {
}
