package com.winllc.innoutwork.data.home;

import java.time.LocalTime;

/**
 * An unaccounted direct report.
 *
 * @param expected when they were expected in, or null when no time is known
 * @param late     whether they are past that time plus the absence grace period (always, for a past day)
 */
public record NotInYet(String dn, String name, LocalTime expected, boolean late) {
}
