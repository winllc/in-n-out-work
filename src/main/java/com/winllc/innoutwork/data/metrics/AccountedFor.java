package com.winllc.innoutwork.data.metrics;

import java.time.LocalDate;
import java.util.List;

/**
 * Who is accounted for on a day: checked in, or carrying a status such as work from home or leave.
 *
 * @param day              the day measured
 * @param nonWorkingReason why nobody is expected ("Saturday", a holiday's title), or null on a working day
 * @param expected         users expected that day: active recently, or given a status for the day
 * @param accounted        expected users who checked in or have a status
 * @param checkedIn        expected users who checked in
 * @param withStatus       expected users with a status, whether or not they also checked in
 * @param unaccounted      the first unaccounted users by name, capped for display; empty for organisation-wide figures
 * @param unaccountedTotal all unaccounted users, including any beyond the cap
 */
public record AccountedFor(LocalDate day, String nonWorkingReason, int expected, int accounted,
                           int checkedIn, int withStatus, List<UserRef> unaccounted, int unaccountedTotal) {

    public boolean workingDay() {
        return nonWorkingReason == null;
    }

    /** Whole percent accounted for; null when nobody is expected, including on non-working days. */
    public Integer ratePercent() {
        if (!workingDay() || expected == 0) {
            return null;
        }
        return (int) Math.round(accounted * 100.0 / expected);
    }
}
