package com.winllc.innoutwork.data.home;

/**
 * How a run of working days went. Every working day is counted once: checked in, else covered
 * by a status such as leave, else nothing recorded.
 *
 * @param workingDays weekdays in the window that were not holidays
 * @param checkedIn   working days with a check-in
 * @param statusOnly  working days with a status and no check-in
 * @param noRecord    working days with neither
 */
public record AttendanceSummary(int workingDays, int checkedIn, int statusOnly, int noRecord) {

    public double percentOfWorkingDays(int days) {
        return workingDays == 0 ? 0 : days * 100.0 / workingDays;
    }
}
